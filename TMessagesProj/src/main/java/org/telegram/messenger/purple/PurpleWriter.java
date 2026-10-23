/*
 * This is the source code of Purple Telegram for Android.
 *
 * The one path everything that edits settings.toml goes down. Mirrors the
 * desktop fork's write side - see docs/purple/config.md.
 *
 * Every write is a splice: the core rewrites the lines it is responsible for
 * and leaves every other byte where the user put it. That is the point of the
 * file being TOML at all, and it is why nothing here re-serialises anything.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;

import java.nio.charset.Charset;

public final class PurpleWriter {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private PurpleWriter() {
    }

    public interface Current { boolean current(); }
    private static boolean writing;

    public static final class Session {
        public final String globalKey;
        public final boolean appWide;
        private final PurpleGlobalAccountChoice.Session context;
        private final PurpleAccountFiles.Identity origin;
        private final PurpleRoutedWriteModel model;
        private final Runnable offlineNotice;
        private final Current view;
        private final long role, revision, generation, epoch;
        private final int gate;
        private boolean retired;
        private Current action = () -> true;

        private Session(PurpleGlobalAccountChoice.Session context, PurpleAccountFiles.Identity origin,
                boolean appWide, Runnable offlineNotice, Current view) throws Exception {
            this.context = context; this.origin = origin; this.offlineNotice = offlineNotice; this.view = view;
            globalKey = context.global;
            role = PurpleAccountOwner.roleEpoch(); revision = PurpleAccountOwner.revision();
            generation = PurpleAccountSettings.generation(); epoch = PurpleAccountSettings.epochNative(); gate = PurpleGate.generation();
            PurpleAccountFiles own = PurpleAccountFiles.resolve(origin.key, context.owner);
            PurpleAccountFiles global = PurpleAccountFiles.resolve(context.global, context.owner);
            if (own == null || global == null) throw new IllegalStateException("missing settings route");
            model = new PurpleRoutedWriteModel(PurpleAccountFiles.root(), own.settings, global.settings,
                    context.owner, context.global, origin.key, appWide, PurpleGlobalAccountChoice.PHYSICAL,
                    new PurpleAccountEditorModel.Context() {
                        public boolean current(boolean refreshed) {
                            return !retired && action.current() && origin.current() && PurpleAccountOwner.ready() && context.currentAfterAccountRefresh(refreshed);
                        }
                        public void refresh() { PurpleAccountSettings.invalidate(); }
                        public void notifyChanged() {
                            org.telegram.messenger.NotificationCenter.getInstance(origin.slot).postNotificationName(
                                    org.telegram.messenger.NotificationCenter.dialogFiltersUpdated);
                        }
                    }, PurpleEditorDirectories.NATIVE);
            this.appWide = model.appWide;
        }
        public boolean current() { return !retired && model.current(); }
        public boolean forAccount(int account) { return origin.slot == account && origin.current(); }
        public boolean sameRoute() {
            return action.current() && view.current() && origin.current() && PurpleAccountOwner.ready()
                    && role == PurpleAccountOwner.roleEpoch() && revision == PurpleAccountOwner.revision()
                    && java.util.Objects.equals(context.owner, PurpleAccountOwner.owner())
                    && java.util.Objects.equals(globalKey, PurpleAccountOwner.global());
        }
        public void retire() { retired = true; }
        public void guard(Current action) { this.action = action; }
        private byte[] bytes() { return current() ? model.bytes() : null; }
        public java.util.List<PurpleCore.ListEntry> lists(long peer) {
            try {
                org.json.JSONObject result = model.lists(peer);
                return result == null ? java.util.Collections.emptyList() : PurpleCore.listEntries(result.getJSONArray("lists"));
            } catch (Exception | UnsatisfiedLinkError e) { return java.util.Collections.emptyList(); }
        }
        public String toggle(String list, long peer, boolean add, String title) {
            try {
                org.json.JSONObject result = model.toggle(list, peer, add, title);
                if (result == null) return "list settings changed or are not editable";
                return applyText(result.optBoolean("changed"), result.optString("text"), "list edit");
            } catch (Exception | UnsatisfiedLinkError e) { return "list settings could not be read"; }
        }
        private String applyText(boolean changed, String text, String reason) {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return "settings writes require the UI thread";
            synchronized (PurpleAccountOwner.class) {
                synchronized (PurpleAccountFiles.class) {
                    synchronized (PurpleAccountSettings.class) {
                        if (writing || !current()) return "settings context changed";
                        if (!changed) return null;
                        if (text == null) return "no settings text from the core";
                        writing = true;
                        try {
                            PurpleAccountEditorModel.Result result = model.save(text);
                            if (!result.ok) return result.changed ? "settings were written but the context changed" : "settings changed or could not be written";
                            if (!context.currentAfterAccountRefresh(true)) return "settings context changed after writing";
                            if (appWide) PurpleGate.reload(reason);
                            if (appWide && !retired && action.current() && view.current() && origin.current() && PurpleAccountOwner.ready()
                                    && role == PurpleAccountOwner.roleEpoch() && revision == PurpleAccountOwner.revision()
                                    && java.util.Objects.equals(context.owner, PurpleAccountOwner.owner())
                                    && java.util.Objects.equals(globalKey, PurpleAccountOwner.global())
                                    && generation + 2 == PurpleAccountSettings.generation() && epoch + 2 == PurpleAccountSettings.epochNative()
                                    && gate + 1 == PurpleGate.generation()) {
                                if (!model.editor.missing) PurpleSync.afterRoutedWrite(origin, model.editor.target,
                                        text.getBytes(UTF_8), reason);
                                boolean offline = true;
                                for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) if (globalKey.equals(id.key)) offline = false;
                                if (offline && offlineNotice != null) offlineNotice.run();
                            }
                            return null;
                        } finally { writing = false; retired = true; }
                    }
                }
            }
        }
    }

    public static Session capture(int account, boolean appWide, Current current, Runnable offlineNotice) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return null;
        synchronized (PurpleAccountOwner.class) {
            synchronized (PurpleAccountFiles.class) {
                synchronized (PurpleAccountSettings.class) {
                    if (writing || current == null || !current.current()) return null;
                    PurpleGlobalAccountChoice.Session context = PurpleGlobalAccountChoice.begin(account, current::current);
                    PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
                    if (context == null || origin == null || !context.current()) return null;
                    try {
                        Session result = new Session(context, origin, appWide, offlineNotice, current);
                        return result.current() ? result : null;
                    } catch (Exception | UnsatisfiedLinkError e) { return null; }
                }
            }
        }
    }

    public static String apply(Session session, PurpleCore.SpliceResult result, String reason) {
        if (session == null || !session.current()) return "settings context changed";
        if (result == null) return "no result from the core";
        if (!result.ok()) { FileLog.e("Purple: " + reason + " refused: " + result.error); return result.error; }
        return session.applyText(result.changed, result.text, reason);
    }

    public static String setPeekStartDelay(Session session, PurpleGate.PeekSettings settings, int seconds) {
        if (session == null || !session.current() || !PurpleGate.currentPeekEditSettings(settings)
                || !java.util.Arrays.equals(session.bytes(), settings.snapshot.bytes())) return "settings.toml changed";
        return apply(session, PurpleCore.setPeekStartDelay(session.bytes(), seconds), "peek start delay");
    }

    public static String setTableBool(
            Session session, String table, String key, boolean value, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session,
                PurpleCore.setTableBool(settings, table, key, value),
                reason);
    }

    public static String setTableString(
            Session session, String table, String key, String value, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session,
                PurpleCore.setTableString(settings, table, key, value),
                reason);
    }

    /**
     * Writes a new empty {@code [[schedule.rulesets]]} block.
     *
     * @return null on success, or the core's refusal - an empty name, or one
     *         already taken, since the name is how every later edit finds it
     */
    public static String addRuleset(
            Session session, String name, String device, String mode, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session,
                PurpleCore.addRuleset(settings, name, device, mode),
                reason);
    }

    public static String addRuleset(Session session, String name, String device, String mode,
            String outside, String reason) {
        byte[] settings = session == null ? null : session.bytes();
        if (settings == null) return "settings context changed";
        PurpleCore.SpliceResult result = PurpleCore.addRuleset(settings, name, device, mode);
        if (result != null && result.ok() && outside != null) {
            result = PurpleCore.setRulesetString(result.text.getBytes(UTF_8), name, "outside", outside);
        }
        if (result == null || !result.ok()) return apply(session, result, reason);
        return session.applyText(!java.util.Arrays.equals(settings, result.text.getBytes(UTF_8)), result.text, reason);
    }

    public static String removeRuleset(Session session, String name, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session, PurpleCore.removeRuleset(settings, name), reason);
    }

    /**
     * Sets one of a ruleset's own string keys - {@code device}, {@code mode},
     * {@code outside}, or {@code name} for a rename.
     *
     * An empty {@code value} takes the key out of the file, which is how a
     * screen says "back to the default" without writing the default down.
     */
    public static String setRulesetString(
            Session session, String name, String key, String value, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session,
                PurpleCore.setRulesetString(settings, name, key, value),
                reason);
    }

    /**
     * Creates a list, empty.
     *
     * No title: the box asks for one name, and a title that only repeats the
     * name would be a line in the file saying nothing. Rename it in the file
     * to give it one.
     *
     * @return null on success, or the core's refusal - an empty name, one
     *         starting with {@code *}, or a name already taken
     */
    public static String addList(Session session, String name, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session, PurpleCore.addList(settings, name, ""), reason);
    }

    /**
     * Rewrites one schedule rule, key by key.
     *
     * The three {@code expected} values are what the screen read off the rule
     * it is editing. The core refuses when the rule at {@code index} no longer
     * says them, which is what stops a dialog left open across somebody else's
     * edit from rewriting a rule other than the one on it.
     *
     * {@code ruleset} is where the rule lives: empty for the flat
     * {@code [[schedule.rules]]} array, a ruleset's name otherwise, and
     * {@code index} then counts within that one.
     */
    public static String setScheduleRule(
            Session session, String ruleset, int index, int expectedFrom, int expectedTill,
            String expectedPreset, boolean enabled, int[] days, int from, int till,
            String preset, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session,
                PurpleCore.setScheduleRule(settings, ruleset, index, expectedFrom,
                        expectedTill, expectedPreset, enabled, days, from, till, preset),
                reason);
    }

    /** Writes a new rule after the ruleset's last one, or starts the section. */
    public static String appendScheduleRule(
            Session session, String ruleset, boolean enabled, int[] days, int from, int till,
            String preset, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session,
                PurpleCore.appendScheduleRule(
                        settings, ruleset, enabled, days, from, till, preset),
                reason);
    }

    /** Takes one rule out, on the same expectation as {@link #setScheduleRule}. */
    public static String removeScheduleRule(
            Session session, String ruleset, int index, int expectedFrom, int expectedTill,
            String expectedPreset, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session,
                PurpleCore.removeScheduleRule(settings, ruleset, index, expectedFrom,
                        expectedTill, expectedPreset),
                reason);
    }

    /**
     * Writes a new {@code [[screen_time.budgets]]} block after the last one.
     *
     * {@code target} is spelled the way the file spells it - {@code "all"},
     * {@code "chat:<id>"}, {@code "kind:<kind>"}, {@code "preset:<name>"} -
     * because that is what the key holds, and the parser is the only thing that
     * knows the grammar. A target the parser cannot read back is refused by the
     * core rather than written, so a screen that got one wrong hears about it
     * instead of leaving a budget that is not there.
     *
     * @return null on success, or a reason to show the user
     */
    public static String appendBudget(
            Session session, String target, int perDaySeconds, String mode, int snoozeSeconds,
            int snoozesPerDay, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session,
                PurpleCore.appendBudget(settings, target, perDaySeconds, mode,
                        snoozeSeconds, snoozesPerDay),
                reason);
    }

    /**
     * Rewrites one budget, key by key.
     *
     * {@code index} is {@link PurpleCore.Budget#sourceIndex} and
     * {@code expectedTarget} is the target the screen read off it. The core
     * refuses when the budget there says something else, which is what stops a
     * dialog left open across somebody else's edit from rewriting a budget
     * other than the one it was opened on.
     */
    public static String setBudget(
            Session session, int index, String expectedTarget, String target, int perDaySeconds,
            String mode, int snoozeSeconds, int snoozesPerDay, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session,
                PurpleCore.setBudget(settings, index, expectedTarget, target,
                        perDaySeconds, mode, snoozeSeconds, snoozesPerDay),
                reason);
    }

    /** Takes one budget out, on the same expectation as {@link #setBudget}. */
    public static String removeBudget(Session session, int index, String expectedTarget, String reason) {
        final byte[] settings = session == null ? null : session.bytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(session, PurpleCore.removeBudget(settings, index, expectedTarget), reason);
    }
}
