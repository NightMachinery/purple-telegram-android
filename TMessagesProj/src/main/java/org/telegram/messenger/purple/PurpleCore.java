/*
 * This is the source code of Purple Telegram for Android.
 *
 * The Work Mode core (settings parser, splicer, state, engine) is shared with
 * the desktop fork and lives in TMessagesProj/jni/purple, built into
 * libpurplecore.so. This class is the whole Java surface of it.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PurpleCore {

    /**
     * What {@link #visible} answers with. The show mode is the low nibble and
     * {@link #NOTIFY_BIT} carries the notify flag, so a per-chat query costs no
     * JNI object allocation - it is asked once per row per chat list rebuild.
     */
    public static final int SHOW_ALWAYS = 0;
    public static final int SHOW_MESSAGE = 1;
    public static final int SHOW_MESSAGE_OR_REACTION = 2;
    public static final int SHOW_MENTION = 3;
    public static final int SHOW_NEVER = 4;
    public static final int SHOW_MASK = 0x0f;
    public static final int NOTIFY_BIT = 0x10;

    /**
     * Where the extra views' membership sits in the same packed int: one bit
     * per view, in strip order.
     *
     * It rides along rather than taking a native of its own because the caller
     * is {@code DialogFilter.includesDialog()}, asked once per chat per sort per
     * showing tab. The gate caches the whole int per chat and clears it on every
     * reload, so a tab's membership costs a shift and a mask.
     */
    public static final int VIEW_SHIFT = 8;

    /** The core's own limit on views, main view included. */
    public static final int VIEW_LIMIT = 16;

    /** What a chat is, as the core's Purple::ChatKind numbers it. */
    public static final int KIND_PRIVATE = 0;
    public static final int KIND_GROUP = 1;
    public static final int KIND_CHANNEL = 2;
    public static final int KIND_BOT = 3;

    private static volatile boolean loaded;

    /**
     * Only the bridge is loaded by name. Qt Core and the shared libc++ are
     * listed as dependencies inside libpurplecore.so, so the dynamic linker
     * pulls them in on its own.
     *
     * Loading Qt Core through System.loadLibrary instead would break the app:
     * that path calls the library's JNI_OnLoad, and Qt's expects to be started
     * by a Qt activity with the org.qtproject.qt.android classes present. In
     * an app that only borrows QString it answers JNI_ERR, and the load fails
     * with "JNI_ERR returned from JNI_OnLoad". The linker never calls
     * JNI_OnLoad, so resolving Qt as a dependency sidesteps all of it.
     */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loadLocked();
    }

    private static synchronized void loadLocked() {
        if (loaded) {
            return;
        }
        System.loadLibrary("purplecore");
        loaded = true;
    }

    /**
     * Parses settings.toml. Returns the raw JSON the bridge produces:
     * {"ok":true|false,"version":N,"error":"...","warnings":["...",...]}.
     * Prefer {@link #parse(byte[])}, which unpacks it.
     */
    public static native String parseSettings(byte[] utf8);

    /**
     * Reloads both files into the native gate and resolves the active preset.
     * Returns the raw JSON; prefer {@link #load(byte[], byte[])}.
     */
    private static native String loadNative(byte[] settingsUtf8, byte[] stateUtf8);

    /**
     * What the running resolution says about one chat: the show mode in the low
     * nibble, {@link #NOTIFY_BIT} set when it may still make a sound. Prefer
     * {@link #visible(long, int)}, which makes sure the library is loaded.
     */
    private static native int visibleNative(long bareId, int kind);

    /**
     * Returns the state.toml text that turns {@code preset} on, without
     * touching the gate. Prefer {@link #setPreset(byte[], String)}.
     */
    private static native String setPresetNative(byte[] stateUtf8, String preset);

    private static native String togglePeekNative(byte[] stateUtf8);

    private static native String setSchedulePausedNative(byte[] stateUtf8, boolean paused);

    private static native String scheduleTickNative(byte[] stateUtf8);

    private static native String focusTickNative(
            byte[] stateUtf8, boolean active, String enterTarget);

    private static native String setOverrideNative(
            byte[] stateUtf8, long bareId, int kind, int seconds);

    private static native String deciderNative(long bareId, int kind);

    private static native String listsForNative(byte[] settingsUtf8, long bareId);

    private static native String spliceMemberNative(
            byte[] settingsUtf8, String list, long bareId, boolean add, String titlesJson);

    private static native String setTableBoolNative(
            byte[] settingsUtf8, String table, String key, boolean value);

    private static native String setScheduleRuleNative(
            byte[] settingsUtf8, int index, int expectedFrom, int expectedTill,
            String expectedPreset, boolean enabled, int[] days, int from, int till,
            String preset);

    private static native String appendScheduleRuleNative(
            byte[] settingsUtf8, boolean enabled, int[] days, int from, int till,
            String preset);

    private static native String removeScheduleRuleNative(
            byte[] settingsUtf8, int index, int expectedFrom, int expectedTill,
            String expectedPreset);

    /**
     * The three "until" decisions, numbered as the core's {@code OverrideKind}.
     *
     * A decision about one chat under one preset, with a deadline: show it
     * anyway, hide it anyway, or let it interrupt anyway. {@link #OVERRIDE_NONE}
     * is this side's "there isn't one" and has no counterpart in the core.
     */
    public static final int OVERRIDE_SHOW = 0;
    public static final int OVERRIDE_HIDE = 1;
    public static final int OVERRIDE_NOTIFY = 2;
    public static final int OVERRIDE_NONE = -1;

    /**
     * How far a "hide until" reaches, numbered as the core's {@code HideScope}.
     *
     * {@link #SCOPE_EVERYWHERE} reuses the preset-wide switch below, so one
     * chat gets for a while what {@link Loaded#hideEverywhere} gives a whole
     * preset: it leaves the chat list, and with it the forward picker, the
     * share sheet, search suggestions and recent chats.
     */
    public static final int SCOPE_EVERYWHERE = 0;
    public static final int SCOPE_UNCOUNTED = 1;
    public static final int SCOPE_COUNTED = 2;

    /**
     * Which chats the {@code [recent]} grace period covers, numbered as the
     * core's {@code RecentScope}.
     */
    public static final int RECENT_ALREADY_IN_VIEW = 0;
    public static final int RECENT_ANY_OPEN_CHAT = 1;
    public static final int RECENT_EXCEPT_IN_FOLDER = 2;

    /**
     * How the chat list marks a row that is only there on a clock, numbered as
     * the core's {@code RecentStyle}.
     *
     * Covers both reasons a row can be here on a timer - the close buffer and a
     * "show until" - because one mark is drawn for both.
     */
    public static final int STYLE_NONE = 0;
    public static final int STYLE_STRIPE = 1;
    public static final int STYLE_TIMER = 2;

    /**
     * One extra tab a preset invented.
     *
     * Membership is not here - it travels in the packed per-chat answer. What
     * the file owns, and the server has never heard of, is the name and the
     * pinned order.
     */
    public static final class View {
        public final String name;
        public final long[] pinned;

        View(String name, long[] pinned) {
            this.name = name;
            this.pinned = pinned;
        }
    }

    /** One live "until" decision, as the load result hands it over. */
    public static final class Override {
        public final long peer;
        public final int kind;

        /**
         * When it was made, in local wall-clock seconds.
         *
         * Only the chat-list mark reads this: it draws how much of the span is
         * left, and that needs both ends. Everything else asks whether the
         * decision is still in force, which is {@link #until} alone.
         */
        public final long from;

        /** When it runs out, in local wall-clock seconds. */
        public final long until;

        Override(long peer, int kind, long from, long until) {
            this.peer = peer;
            this.kind = kind;
            this.from = from;
            this.until = until;
        }
    }

    /**
     * The half of the load result that moves on a clock rather than on an edit.
     *
     * Grouped rather than spread across {@link Loaded} because they are read
     * together, by the one control that shows them, and because a load result
     * with five more loose booleans on it stops being readable.
     */
    public static final class Clock {
        /** True while a peek is revealing what the preset hides. */
        public final boolean peeking;

        /**
         * When the running peek ends, in local wall-clock seconds. Zero means
         * {@code auto_off} is turned off, so it runs until it is turned off by
         * hand - which is a different thing from a countdown that never moves,
         * and the reason this is not simply a number of seconds left.
         */
        public final long peekDeadline;

        /** What {@code [peek] auto_off} is set to, in seconds; zero disables it. */
        public final int peekSeconds;

        public final boolean schedulePaused;

        /** True when settings.toml describes a schedule at all. */
        public final boolean scheduleConfigured;

        /**
         * The "until" decisions in force under the running preset.
         *
         * Already narrowed by the bridge to the live ones under this preset, so
         * a lookup here needs no filtering - only the deadline check, which the
         * clock can move under Java between two reloads.
         */
        public final List<Override> overrides;

        /** The earliest deadline outstanding, or zero. What the timer is armed for. */
        public final long nextOverrideDeadline;

        /** One of the {@code SCOPE_} values: how far a "hide until" reaches. */
        public final int hideScope;

        /**
         * How long a chat stays in the view after you stop looking at it. Zero
         * disables the grace period entirely.
         */
        public final int recentSeconds;

        /** One of the {@code RECENT_} values: which chats the grace covers. */
        public final int recentScope;

        /**
         * One of the {@code STYLE_} values: how the chat list marks a row that
         * is only there on a clock. {@link #STYLE_NONE} is the default, and
         * means nothing is drawn.
         */
        public final int recentStyle;

        Clock(boolean peeking, long peekDeadline, int peekSeconds,
                boolean schedulePaused, boolean scheduleConfigured,
                List<Override> overrides, long nextOverrideDeadline, int hideScope,
                int recentSeconds, int recentScope, int recentStyle) {
            this.peeking = peeking;
            this.peekDeadline = peekDeadline;
            this.peekSeconds = peekSeconds;
            this.schedulePaused = schedulePaused;
            this.scheduleConfigured = scheduleConfigured;
            this.overrides = overrides;
            this.nextOverrideDeadline = nextOverrideDeadline;
            this.hideScope = hideScope;
            this.recentSeconds = recentSeconds;
            this.recentScope = recentScope;
            this.recentStyle = recentStyle;
        }

        static final Clock NONE = new Clock(false, 0, 0, false, false,
                Collections.<Override>emptyList(), 0, SCOPE_UNCOUNTED,
                0, RECENT_ALREADY_IN_VIEW, STYLE_NONE);

        static Clock fromJson(JSONObject object) {
            final List<Override> overrides = new ArrayList<>();
            final JSONArray array = object.optJSONArray("overrides");
            if (array != null) {
                for (int i = 0; i < array.length(); ++i) {
                    final JSONObject entry = array.optJSONObject(i);
                    if (entry == null) {
                        continue;
                    }
                    overrides.add(new Override(
                            entry.optLong("peer", 0),
                            entry.optInt("kind", OVERRIDE_SHOW),
                            entry.optLong("from", 0),
                            entry.optLong("until", 0)));
                }
            }
            return new Clock(
                    object.optBoolean("peeking", false),
                    object.optLong("peekDeadline", 0),
                    object.optInt("peekSeconds", 0),
                    object.optBoolean("schedulePaused", false),
                    object.optBoolean("scheduleConfigured", false),
                    overrides,
                    object.optLong("nextOverrideDeadline", 0),
                    object.optInt("hideScope", SCOPE_UNCOUNTED),
                    object.optInt("recentSeconds", 0),
                    object.optInt("recentScope", RECENT_ALREADY_IN_VIEW),
                    object.optInt("recentStyle", STYLE_NONE));
        }
    }

    /** What a {@link #togglePeek(byte[])} did, so the caller can say so. */
    public static final class PeekChange {
        /**
         * True when there was nothing to peek at, because nothing is running.
         * Distinct from "it ended": starting a peek over Normal would leave one
         * running that no chat list could show the end of.
         */
        public final boolean refused;

        public final boolean peeking;

        /** How long this peek will run, or zero for "until you turn it off". */
        public final int seconds;

        /** The state.toml text to write, or null when nothing should be. */
        public final String text;

        PeekChange(boolean refused, boolean peeking, int seconds, String text) {
            this.refused = refused;
            this.peeking = peeking;
            this.seconds = seconds;
            this.text = text;
        }
    }

    /** What one schedule tick decided, or null when it decided nothing. */
    public static final class Tick {
        /** True when the preset itself moved, not only the recorded target. */
        public final boolean applied;

        /** The preset the schedule now wants. */
        public final String target;

        /** The preset left in place instead, when {@code applied} is false. */
        public final String kept;

        /** What put {@code kept} there, for the log line. */
        public final String keptSource;

        public final String text;

        Tick(boolean applied, String target, String kept, String keptSource, String text) {
            this.applied = applied;
            this.target = target;
            this.kept = kept;
            this.keptSource = keptSource;
            this.text = text;
        }
    }

    /** What one focus pass decided, or null when it decided nothing. */
    public static final class FocusChange {
        /**
         * What happened, for the log line: {@code none} when only the flag
         * moved, {@code entered}, {@code restored} when leaving put the
         * pre-focus preset back, {@code schedule} when a window that opened
         * during the session took it instead, {@code exited} when
         * {@code exit_preset} named one outright, {@code kept} when the preset
         * in force was chosen during the session and outlives it.
         */
        public final String change;

        /** The preset in force afterwards. */
        public final String preset;

        /** What put it there, as the core names the source. */
        public final String source;

        /** True while focus is holding the preset. */
        public final boolean session;

        /**
         * What the schedule wanted at the moment this session began, handed
         * back only when one just began. The caller keeps it until
         * {@link #session} goes false: it is what tells leaving whether a
         * window opened or closed while focus held the preset, and state.toml
         * has no key for it.
         */
        public final String enterTarget;

        public final String text;

        FocusChange(String change, String preset, String source, boolean session,
                String enterTarget, String text) {
            this.change = change;
            this.preset = preset;
            this.source = source;
            this.session = session;
            this.enterTarget = enterTarget;
            this.text = text;
        }
    }

    /**
     * Starts or ends a peek.
     *
     * @param state the current state.toml, or null
     * @return what happened; never null, and {@code text} is null when there is
     *         nothing to write
     */
    public static PeekChange togglePeek(byte[] state) {
        final String json;
        try {
            json = togglePeekNative(state);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new PeekChange(true, false, 0, null);
        }
        if (json == null) {
            return new PeekChange(true, false, 0, null);
        }
        try {
            final JSONObject object = new JSONObject(json);
            return new PeekChange(
                    object.optBoolean("refused", false),
                    object.optBoolean("peeking", false),
                    object.optInt("seconds", 0),
                    object.isNull("text") ? null : object.optString("text", null));
        } catch (JSONException e) {
            FileLog.e(e);
            return new PeekChange(true, false, 0, null);
        }
    }

    /**
     * Returns the state.toml text carrying one "until" decision.
     *
     * @param seconds how long it lasts; zero cancels whatever is running
     * @return the text to write, or null when nothing is running to decide
     *         about - under Normal there is no preset for one to belong to
     */
    public static String setOverride(byte[] state, long bareId, int kind, int seconds) {
        try {
            return setOverrideNative(state, bareId, kind, seconds);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * The title of the list deciding this chat, or null when none does.
     *
     * Null is the fall-through rather than an error: a preset names what gets
     * through, so a chat no entry claims is hidden and silenced by saying
     * nothing about it.
     */
    public static String decider(long bareId, int kind) {
        try {
            return deciderNative(bareId, kind);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Returns the state.toml text that holds the schedule off, or lets it go.
     *
     * @return the text to write, or null when the core could not be reached
     */
    public static String setSchedulePaused(byte[] state, boolean paused) {
        try {
            return setSchedulePausedNative(state, paused);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * One tick of the schedule.
     *
     * @return what to do, or null when there is nothing to do - which is every
     *         tick except the ones that land on a boundary
     */
    public static Tick scheduleTick(byte[] state) {
        final String json;
        try {
            json = scheduleTickNative(state);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return null;
        }
        if (json == null) {
            return null;
        }
        try {
            final JSONObject object = new JSONObject(json);
            final String text = object.isNull("text") ? null : object.optString("text", null);
            if (text == null) {
                return null;
            }
            return new Tick(
                    object.optBoolean("applied", false),
                    object.optString("target", ""),
                    object.optString("kept", ""),
                    object.optString("keptSource", ""),
                    text);
        } catch (JSONException e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * One pass of OS focus sync: record what the interruption filter says, and
     * act on it if that is an edge.
     *
     * The detector and the policy in one call, unlike the desktop where they
     * are two - see the bridge. {@code ensureLoaded} is not called here: like
     * the schedule tick this answers from the settings already in the gate, and
     * nothing has loaded them yet before the first reload.
     *
     * @param active     whether the OS says a focus mode is on
     * @param enterTarget what the caller remembers of {@link
     *                    FocusChange#enterTarget}, or null
     * @return what to do, or null when there is nothing to write
     */
    public static FocusChange focusTick(byte[] state, boolean active, String enterTarget) {
        final String json;
        try {
            json = focusTickNative(state, active, enterTarget);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return null;
        }
        if (json == null) {
            return null;
        }
        try {
            final JSONObject object = new JSONObject(json);
            final String text = object.isNull("text") ? null : object.optString("text", null);
            if (text == null) {
                return null;
            }
            return new FocusChange(
                    object.optString("change", "none"),
                    object.optString("preset", ""),
                    object.optString("source", ""),
                    object.optBoolean("session", false),
                    object.isNull("enterTarget")
                            ? null
                            : object.optString("enterTarget", null),
                    text);
        } catch (JSONException e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * One of settings.toml's lists, as the membership menu needs it.
     *
     * {@code members} is every id the list holds, which the caller needs in
     * order to name each line the splice might rewrite - converting an inline
     * array to one line per member rewrites all of them.
     */
    public static final class ListEntry {
        public final String name;
        public final String title;
        public final boolean member;
        public final long[] members;

        private ListEntry(String name, String title, boolean member, long[] members) {
            this.name = name;
            this.title = title;
            this.member = member;
            this.members = members;
        }
    }

    /**
     * One {@code [[schedule.rules]]} block, as the schedule screen draws and
     * edits it.
     *
     * Only the rules the parser kept are here. A broken one has no window and
     * no preset to draw, so the screen names it from {@link Loaded#warnings}
     * instead - that is the only place the reason it was dropped survives.
     *
     * {@code index} is the position in the RAW array, counting the dropped
     * ones, because that is the address the splice edits by: a rule numbered by
     * its position in this list would move the moment a broken one above it was
     * fixed. {@code from} and {@code till} are minutes since midnight, so
     * {@code "9:00"} and {@code "09:00"} are one rule.
     */
    public static final class ScheduleRule {
        public final int index;

        /** The line its header is on, 1-based, for a screen that points at it. */
        public final int line;

        public final boolean enabled;

        /** Monday .. Sunday as 1 .. 7, in the order the file wrote them. */
        public final int[] days;

        public final int from;
        public final int till;
        public final String preset;

        private ScheduleRule(int index, int line, boolean enabled, int[] days,
                int from, int till, String preset) {
            this.index = index;
            this.line = line;
            this.enabled = enabled;
            this.days = days;
            this.from = from;
            this.till = till;
            this.preset = preset;
        }
    }

    /** What a splice did, or why it did nothing. */
    public static final class SpliceResult {
        /** The whole file as it should now be written, or null on failure. */
        public final String text;
        public final boolean changed;
        /** Non-empty means nothing was written. */
        public final String error;

        private SpliceResult(String text, boolean changed, String error) {
            this.text = text;
            this.changed = changed;
            this.error = error;
        }

        public boolean ok() {
            return error == null || error.length() == 0;
        }
    }

    /**
     * Every list in {@code settings}, and whether this chat is in each.
     *
     * Answered from the file rather than from the loaded resolution, so the
     * menu never offers a list that has since been renamed.
     *
     * @param bareId the id as settings.toml writes it
     */
    public static List<ListEntry> listsFor(byte[] settings, long bareId) {
        if (settings == null) {
            return Collections.<ListEntry>emptyList();
        }
        final String json;
        try {
            json = listsForNative(settings, bareId);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return Collections.<ListEntry>emptyList();
        }
        if (json == null) {
            return Collections.<ListEntry>emptyList();
        }
        final List<ListEntry> result = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); ++i) {
                final JSONObject entry = array.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                final JSONArray ids = entry.optJSONArray("members");
                final long[] members = new long[ids == null ? 0 : ids.length()];
                for (int a = 0; a < members.length; ++a) {
                    members[a] = ids.optLong(a, 0);
                }
                result.add(new ListEntry(
                        entry.optString("name", ""),
                        entry.optString("title", ""),
                        entry.optBoolean("member", false),
                        members));
            }
        } catch (JSONException e) {
            FileLog.e(e);
        }
        return result;
    }

    /**
     * Adds or removes one member, returning the file as it should be written.
     *
     * Nothing is written here: the caller decides, so a failed splice leaves
     * both the file and the running resolution exactly as they were.
     *
     * @param titles id to display name, for the comments the splice regenerates
     */
    public static SpliceResult spliceMember(
            byte[] settings, String list, long bareId, boolean add, String titles) {
        ensureLoaded();
        try {
            return splice(spliceMemberNative(settings, list, bareId, add, titles));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /**
     * Sets one boolean under one table - {@code [premium] enabled_p} and the two
     * Work Mode flags the settings screen owns.
     *
     * Written through the splice like everything else: the key, the spacing and
     * any trailing comment stay exactly as the user wrote them, and a file that
     * never mentioned the table gains it rather than being re-serialised.
     */
    public static SpliceResult setTableBool(
            byte[] settings, String table, String key, boolean value) {
        ensureLoaded();
        try {
            return splice(setTableBoolNative(settings, table, key, value));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /**
     * Rewrites one schedule rule in place, key by key.
     *
     * {@code index} is {@link ScheduleRule#index}: the rule's position in the
     * raw array, counting the ones the parser threw away. The three
     * {@code expected} values are what the screen read off the rule, and the
     * core refuses when the rule there no longer says them - so a dialog left
     * open while the file moved underneath cannot rewrite a different rule.
     */
    public static SpliceResult setScheduleRule(
            byte[] settings, int index, int expectedFrom, int expectedTill,
            String expectedPreset, boolean enabled, int[] days, int from, int till,
            String preset) {
        ensureLoaded();
        try {
            return splice(setScheduleRuleNative(settings, index, expectedFrom,
                    expectedTill, expectedPreset, enabled, days, from, till, preset));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /** Writes a new rule after the last one, or starts the section. */
    public static SpliceResult appendScheduleRule(
            byte[] settings, boolean enabled, int[] days, int from, int till,
            String preset) {
        ensureLoaded();
        try {
            return splice(appendScheduleRuleNative(
                    settings, enabled, days, from, till, preset));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /** Takes one rule out, on the same expectation as {@link #setScheduleRule}. */
    public static SpliceResult removeScheduleRule(
            byte[] settings, int index, int expectedFrom, int expectedTill,
            String expectedPreset) {
        ensureLoaded();
        try {
            return splice(removeScheduleRuleNative(
                    settings, index, expectedFrom, expectedTill, expectedPreset));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /** The one shape every splice answers in, unpacked. */
    private static SpliceResult splice(String json) {
        if (json == null) {
            return new SpliceResult(null, false, "no result from the core");
        }
        try {
            final JSONObject object = new JSONObject(json);
            final String error = object.optString("error", "");
            return new SpliceResult(
                    error.length() > 0 ? null : object.optString("text", null),
                    object.optBoolean("changed", false),
                    error);
        } catch (JSONException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "bad result from the core");
        }
    }

    public static ParseResult parse(byte[] utf8) {
        ensureLoaded();
        return ParseResult.fromJson(parseSettings(utf8));
    }

    /**
     * Hands settings.toml and state.toml to the native gate and returns what it
     * resolved. Either array may be null, which is what a missing file looks
     * like on a fresh install.
     *
     * @param settings the bytes of settings.toml, or null
     * @param state    the bytes of state.toml, or null
     * @return the resolution now in force, never null
     */
    public static Loaded load(byte[] settings, byte[] state) {
        ensureLoaded();
        return Loaded.fromJson(loadNative(settings, state));
    }

    /**
     * Whether the running preset shows this chat, and whether it may notify.
     *
     * @param bareId the chat's id as settings.toml writes it
     * @param kind   one of the {@code KIND_} constants
     * @return a {@code SHOW_} value, or'ed with {@link #NOTIFY_BIT}
     */
    public static int visible(long bareId, int kind) {
        if (!loaded) {
            ensureLoaded();
        }
        return visibleNative(bareId, kind);
    }

    /**
     * The state.toml text that makes {@code preset} the active one, chosen by
     * hand. Pure: the caller writes the result and calls
     * {@link #load(byte[], byte[])} again, which is what keeps the file and the
     * resolution moving together.
     *
     * @param state  the current bytes of state.toml, or null
     * @param preset the preset name; empty or "normal" means stock behaviour
     * @return the text to write, or null if the bridge could not produce one
     */
    public static String setPreset(byte[] state, String preset) {
        ensureLoaded();
        return setPresetNative(state, preset == null ? "" : preset);
    }

    /** What the parser made of the file: usable settings, or a syntax error. */
    public static final class ParseResult {
        public final boolean ok;
        public final int version;
        public final String error;
        public final List<String> warnings;

        private ParseResult(boolean ok, int version, String error, List<String> warnings) {
            this.ok = ok;
            this.version = version;
            this.error = error;
            this.warnings = warnings;
        }

        static ParseResult fromJson(String json) {
            final List<String> warnings = new ArrayList<>();
            if (json == null) {
                return new ParseResult(false, 0, "no result from the parser", warnings);
            }
            try {
                final JSONObject object = new JSONObject(json);
                final JSONArray array = object.optJSONArray("warnings");
                if (array != null) {
                    for (int i = 0; i < array.length(); ++i) {
                        warnings.add(array.optString(i, ""));
                    }
                }
                return new ParseResult(
                        object.optBoolean("ok", false),
                        object.optInt("version", 0),
                        object.optString("error", ""),
                        warnings);
            } catch (JSONException e) {
                return new ParseResult(false, 0, "bad result from the parser", warnings);
            }
        }
    }

    /** One preset as the picker shows it, resolved but not made active. */
    public static final class PresetInfo {
        public final String name;
        public final String title;

        /** False for a preset whose definition the engine could not resolve. */
        public final boolean resolves;

        public final int lists;
        public final int letsThrough;
        public final int silences;

        /** Lists that only show while the chat is unread. */
        public final int gated;

        private PresetInfo(String name, String title, boolean resolves, int lists,
                int letsThrough, int silences, int gated) {
            this.name = name;
            this.title = title;
            this.resolves = resolves;
            this.lists = lists;
            this.letsThrough = letsThrough;
            this.silences = silences;
            this.gated = gated;
        }
    }

    /**
     * One entry of a preset's {@code folders} selection, in strip order.
     *
     * The core never resolves these against the account's real folders - it has
     * never heard of a Telegram folder - so a name arrives as written and the
     * {@code "*ALL"} spread arrives as a marker to expand in place. That
     * expansion happens in {@link PurpleGate}, which is the only side that
     * knows what folders exist.
     */
    /** A {@code showMode} the file did not give, so the kind decides instead. */
    public static final int MODE_UNSET = -1;

    /**
     * {@code DefaultShowMode()} by kind, as a fallback for the load that never
     * happened. Only reached when the bridge answered nothing at all, where
     * behaving like stock matters more than being right about a mode.
     */
    private static final int[] STOCK_DEFAULT_MODES = {
        SHOW_MESSAGE,   // KIND_PRIVATE
        SHOW_MENTION,   // KIND_GROUP
        SHOW_ALWAYS,    // KIND_CHANNEL
        SHOW_ALWAYS,    // KIND_BOT
    };

    /**
     * One folder a preset pulls into its main view.
     *
     * The escape hatch: whatever the lists decided, these chats join the view.
     * What it does not decide is <i>when</i> they show - a folder naming no
     * mode leaves its chats to the default for what each one is, because the
     * folder chose which chats come in and the kind still decides when.
     */
    public static final class ExemptFolder {
        public final String name;

        /**
         * True for {@code include_in_main_view = "pinned"}: only the chats
         * pinned inside that folder, in the folder's own pinned order. A folder
         * with nothing pinned in it therefore contributes nothing.
         */
        public final boolean pinnedOnly;

        /** A {@code SHOW_} value, or {@link #MODE_UNSET}. */
        public final int showMode;

        private ExemptFolder(String name, boolean pinnedOnly, int showMode) {
            this.name = name;
            this.pinnedOnly = pinnedOnly;
            this.showMode = showMode;
        }
    }

    public static final class FolderEntry {
        /** The name as settings.toml wrote it; meaningless when {@link #all}. */
        public final String name;

        /** True for the {@code "*ALL"} marker rather than a named folder. */
        public final boolean all;

        /**
         * False for an entry switched off by hand. It stays in the selection
         * rather than being dropped from it, so that {@code "*ALL"} skips it
         * instead of handing it straight back.
         */
        public final boolean enabled;

        /** Whether this folder's tab belongs on the strip. */
        public final boolean show;

        private FolderEntry(String name, boolean all, boolean enabled, boolean show) {
            this.name = name;
            this.all = all;
            this.enabled = enabled;
            this.show = show;
        }
    }

    /**
     * The resolution now in force, and everything the UI needs to say why.
     *
     * A failed settings parse is reported through {@code ok} and {@code error}
     * but leaves the previous resolution running: falling back to stock
     * behaviour would unhide every chat the user hid, over a half-typed edit.
     */
    public static final class Loaded {
        public final boolean ok;
        public final String error;
        public final List<String> warnings;
        public final int version;

        /** True when nothing is running and the app behaves like stock. */
        public final boolean normal;

        public final String preset;
        public final String title;
        public final int lists;

        /** True when the active preset was restored from the cached resolution. */
        public final boolean usedCache;

        /** Why the cache was needed, or empty. */
        public final String cacheReason;

        /** True when the active preset is not in settings.toml at all. */
        public final boolean activeMissing;

        /**
         * The preset's folder selection, in strip order. Empty is meaningful:
         * a preset that says nothing about folders shows no folder tabs at all,
         * and {@code "*ALL"} is how you ask for them back.
         */
        public final List<FolderEntry> folders;

        /**
         * True when the strip is anything other than the account's own folders
         * in the account's own order, so a strip index no longer means a
         * server-side position and reordering has to be refused.
         */
        public final boolean foldersRestricted;

        /**
         * The folders the preset silenced, by name, already narrowed to the
         * enabled ones by the core. Names rather than entries because that is
         * the whole of what the answer needs: they are matched against the
         * account's real folder titles and each match is asked whether it holds
         * the chat. Empty in almost every preset, and checked for emptiness
         * before anything walks anything.
         */
        public final List<String> silencedFolders;

        /**
         * The folders the preset left out of the counts - {@code badge_p =
         * false} - by name, on the same terms as {@link #silencedFolders}. A
         * third axis, independent of the other two: a folder can be silenced
         * without being uncounted and uncounted without being silenced.
         */
        public final List<String> quietFolders;

        /**
         * How many lists settings.toml defines, whatever the running preset
         * names. The membership menu offers all of them, and under
         * {@code normal} the preset's own count is zero.
         */
        public final int listCount;

        /**
         * The folders that pull their chats into the preset's view, whatever
         * the lists decided. Empty in almost every preset, and checked for
         * emptiness before anything walks anything.
         */
        public final List<ExemptFolder> exemptFolders;

        /**
         * {@code DefaultShowMode()} for each {@code KIND_} value, by index.
         * Handed over by the bridge rather than asked per chat: it is four
         * numbers that only change when the core does.
         */
        public final int[] defaultModes;

        public final List<PresetInfo> presets;

        /**
         * The preset's extra tabs, in strip order. Empty in almost every preset,
         * and checked for emptiness before anything builds anything.
         */
        public final List<View> views;

        /**
         * Whether hiding means "gone from the app" rather than "absent from
         * this preset's view of the chat list" - {@code hide_everywhere_p}.
         *
         * Off by default, because a work mode is about what you are looking at
         * and not about what you are allowed to reach: a hidden chat normally
         * stays in the forward picker, in search and in recent chats, and only
         * the view leaves it out. A preset that sets this may not also declare
         * extra views, and the core's parser is what refuses that pairing.
         */
        public final boolean hideEverywhere;

        /**
         * Whether a chat the running preset hides is also kept out of the
         * recent and frequent strips - {@code [suggestions] hide_invisible_p}.
         *
         * On unless the file says otherwise: a work mode that took a chat out
         * of the list and then offered it back in the share sheet would be
         * answering the same question two ways. One switch for the whole app
         * rather than a property of the preset, which is why it is read from
         * the file and not from the resolution.
         */
        public final boolean hideInvisibleSuggestions;

        /**
         * Whether the archive is out of the way while this preset runs -
         * {@code hide_archive_p}: no pull gesture, no row, the same state as an
         * account that has never archived anything.
         *
         * On unless the preset says otherwise, which is the opposite default
         * from {@link #hideEverywhere} and deliberately so: a preset that has
         * already named what gets through has no reason to leave a door to the
         * rest of it open. Only ever asked while a preset is filtering.
         */
        public final boolean hideArchive;

        /** Whether {@code [schedule]} is switched on at all. */
        public final boolean scheduleEnabled;

        /**
         * The preset the schedule wants right now, or null when no window
         * covers the moment - which includes a schedule switched off or with no
         * rules. Null is a different answer from wanting Normal, and has to be:
         * otherwise an empty section would quietly force Normal over every
         * other way of choosing a preset.
         */
        public final String scheduleTarget;

        /**
         * {@link ScheduleRule#index} of the rule the moment is inside, or -1.
         * Worked out by the core rather than here, so the midnight-crossing
         * case has one implementation rather than two.
         */
        public final int scheduleNowIndex;

        /** The rules the parser kept, in file order. */
        public final List<ScheduleRule> scheduleRules;

        /**
         * Whether {@code [focus_sync]} is on - the parser's answer, not the
         * file's. It turns the flag off itself when {@code enter_preset} names
         * nothing that exists, so a switch reading the raw key would sit on
         * while nothing happened.
         */
        public final boolean focusSyncEnabled;

        /** The preset a focus mode turns on, or empty when the file names none. */
        public final String focusSyncEnter;

        /**
         * What leaving one goes back to: a preset name, or {@code previous} -
         * the default - for whatever was running when focus took over.
         */
        public final String focusSyncExit;

        /** Peek and schedule, which move on a clock rather than on an edit. */
        public final Clock clock;

        /**
         * Whether the Premium features this client alone withholds are
         * unlocked. On unless the file says otherwise, which is also what a
         * file with no {@code [premium]} section means.
         */
        public final boolean premium;

        /** The state.toml text to write back, or null when it did not change. */
        public final String stateText;

        private Loaded(boolean ok, String error, List<String> warnings, int version,
                boolean normal, String preset, String title, int lists, boolean usedCache,
                String cacheReason, boolean activeMissing, List<FolderEntry> folders,
                boolean foldersRestricted, List<String> silencedFolders,
                List<String> quietFolders, List<ExemptFolder> exemptFolders,
                int[] defaultModes, int listCount, List<PresetInfo> presets,
                List<View> views, boolean hideEverywhere,
                boolean hideInvisibleSuggestions, boolean hideArchive,
                boolean scheduleEnabled, String scheduleTarget, int scheduleNowIndex,
                List<ScheduleRule> scheduleRules, boolean focusSyncEnabled,
                String focusSyncEnter, String focusSyncExit, Clock clock,
                boolean premium, String stateText) {
            this.ok = ok;
            this.error = error;
            this.warnings = warnings;
            this.version = version;
            this.normal = normal;
            this.preset = preset;
            this.title = title;
            this.lists = lists;
            this.usedCache = usedCache;
            this.cacheReason = cacheReason;
            this.activeMissing = activeMissing;
            this.folders = folders;
            this.foldersRestricted = foldersRestricted;
            this.silencedFolders = silencedFolders;
            this.quietFolders = quietFolders;
            this.listCount = listCount;
            this.exemptFolders = exemptFolders;
            this.defaultModes = defaultModes;
            this.presets = presets;
            this.views = views;
            this.hideEverywhere = hideEverywhere;
            this.hideInvisibleSuggestions = hideInvisibleSuggestions;
            this.hideArchive = hideArchive;
            this.scheduleEnabled = scheduleEnabled;
            this.scheduleTarget = scheduleTarget;
            this.scheduleNowIndex = scheduleNowIndex;
            this.scheduleRules = scheduleRules;
            this.focusSyncEnabled = focusSyncEnabled;
            this.focusSyncEnter = focusSyncEnter;
            this.focusSyncExit = focusSyncExit;
            this.clock = clock;
            this.premium = premium;
            this.stateText = stateText;
        }

        /** One array of folder names out of the load result, empty if absent. */
        private static List<String> names(JSONObject object, String key) {
            final List<String> result = new ArrayList<>();
            final JSONArray array = object.optJSONArray(key);
            if (array != null) {
                for (int i = 0; i < array.length(); ++i) {
                    final String name = array.optString(i, "");
                    if (name.length() > 0) {
                        result.add(name);
                    }
                }
            }
            return result;
        }

        private static Loaded failed(String error) {
            return new Loaded(false, error, Collections.<String>emptyList(), 0, true,
                    "normal", "Normal", 0, false, "", false,
                    Collections.<FolderEntry>emptyList(), false,
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<ExemptFolder>emptyList(), STOCK_DEFAULT_MODES, 0,
                    Collections.<PresetInfo>emptyList(), Collections.<View>emptyList(),
                    false, true, true, false, null, -1,
                    Collections.<ScheduleRule>emptyList(), false, "", "previous",
                    Clock.NONE, true, null);
        }

        static Loaded fromJson(String json) {
            if (json == null) {
                return failed("no result from the core");
            }
            try {
                final JSONObject object = new JSONObject(json);
                final List<String> warnings = new ArrayList<>();
                final JSONArray warned = object.optJSONArray("warnings");
                if (warned != null) {
                    for (int i = 0; i < warned.length(); ++i) {
                        warnings.add(warned.optString(i, ""));
                    }
                }
                final List<FolderEntry> folders = new ArrayList<>();
                final JSONArray named = object.optJSONArray("folders");
                if (named != null) {
                    for (int i = 0; i < named.length(); ++i) {
                        final JSONObject entry = named.optJSONObject(i);
                        if (entry == null) {
                            continue;
                        }
                        folders.add(new FolderEntry(
                                entry.optString("name", ""),
                                entry.optBoolean("all", false),
                                entry.optBoolean("enabled", true),
                                entry.optBoolean("show", true)));
                    }
                }
                final List<String> silencedFolders = names(object, "silencedFolders");
                final List<String> quietFolders = names(object, "quietFolders");
                final List<ExemptFolder> exemptFolders = new ArrayList<>();
                final JSONArray exempt = object.optJSONArray("exemptFolders");
                if (exempt != null) {
                    for (int i = 0; i < exempt.length(); ++i) {
                        final JSONObject entry = exempt.optJSONObject(i);
                        if (entry == null) {
                            continue;
                        }
                        exemptFolders.add(new ExemptFolder(
                                entry.optString("name", ""),
                                entry.optBoolean("pinned", false),
                                entry.optInt("showMode", MODE_UNSET)));
                    }
                }
                int[] defaultModes = STOCK_DEFAULT_MODES;
                final JSONArray modes = object.optJSONArray("defaultModes");
                if (modes != null && modes.length() == STOCK_DEFAULT_MODES.length) {
                    defaultModes = new int[modes.length()];
                    for (int i = 0; i < modes.length(); ++i) {
                        defaultModes[i] = modes.optInt(i, SHOW_MESSAGE);
                    }
                }
                final List<PresetInfo> presets = new ArrayList<>();
                final List<View> views = new ArrayList<>();
                final JSONArray tabs = object.optJSONArray("views");
                if (tabs != null) {
                    for (int i = 0; i < tabs.length(); ++i) {
                        final JSONObject entry = tabs.optJSONObject(i);
                        if (entry == null) {
                            continue;
                        }
                        final JSONArray ids = entry.optJSONArray("pinned");
                        final long[] pinned = new long[ids == null ? 0 : ids.length()];
                        for (int a = 0; a < pinned.length; ++a) {
                            pinned[a] = ids.optLong(a, 0);
                        }
                        views.add(new View(entry.optString("name", ""), pinned));
                    }
                }
                final List<ScheduleRule> scheduleRules = new ArrayList<>();
                final JSONArray rules = object.optJSONArray("scheduleRules");
                if (rules != null) {
                    for (int i = 0; i < rules.length(); ++i) {
                        final JSONObject entry = rules.optJSONObject(i);
                        if (entry == null) {
                            continue;
                        }
                        final JSONArray weekdays = entry.optJSONArray("days");
                        final int[] days = new int[weekdays == null ? 0 : weekdays.length()];
                        for (int a = 0; a < days.length; ++a) {
                            days[a] = weekdays.optInt(a, 0);
                        }
                        scheduleRules.add(new ScheduleRule(
                                entry.optInt("index", -1),
                                entry.optInt("line", 0),
                                entry.optBoolean("enabled", true),
                                days,
                                entry.optInt("from", -1),
                                entry.optInt("to", -1),
                                entry.optString("preset", "")));
                    }
                }

                final JSONArray array = object.optJSONArray("presets");
                if (array != null) {
                    for (int i = 0; i < array.length(); ++i) {
                        final JSONObject entry = array.optJSONObject(i);
                        if (entry == null) {
                            continue;
                        }
                        presets.add(new PresetInfo(
                                entry.optString("name", ""),
                                entry.optString("title", ""),
                                entry.optBoolean("resolves", false),
                                entry.optInt("lists", 0),
                                entry.optInt("letsThrough", 0),
                                entry.optInt("silences", 0),
                                entry.optInt("gated", 0)));
                    }
                }
                return new Loaded(
                        object.optBoolean("ok", false),
                        object.optString("error", ""),
                        warnings,
                        object.optInt("version", 0),
                        object.optBoolean("normal", true),
                        object.optString("preset", "normal"),
                        object.optString("title", "Normal"),
                        object.optInt("lists", 0),
                        object.optBoolean("usedCache", false),
                        object.optString("cacheReason", ""),
                        object.optBoolean("activeMissing", false),
                        folders,
                        object.optBoolean("foldersRestricted", false),
                        silencedFolders,
                        quietFolders,
                        exemptFolders,
                        defaultModes,
                        object.optInt("listCount", 0),
                        presets,
                        views,
                        object.optBoolean("hideEverywhere", false),
                        // The next three default true, matching the core's
                        // own defaults, so a result that somehow lacks them
                        // keeps hiding rather than quietly revealing.
                        object.optBoolean("hideInvisibleSuggestions", true),
                        object.optBoolean("hideArchive", true),
                        object.optBoolean("scheduleEnabled", true),
                        object.isNull("scheduleTarget")
                                ? null
                                : object.optString("scheduleTarget", null),
                        object.optInt("scheduleNowIndex", -1),
                        scheduleRules,
                        // Off unless the result says otherwise, which is also
                        // the core's default: a load that somehow lacks the
                        // table must not start driving the preset.
                        object.optBoolean("focusSyncEnabled", false),
                        object.optString("focusSyncEnter", ""),
                        object.optString("focusSyncExit", "previous"),
                        Clock.fromJson(object),
                        object.optBoolean("premium", true),
                        object.isNull("stateText") ? null : object.optString("stateText", null));
            } catch (JSONException e) {
                return failed("bad result from the core");
            }
        }
    }

    private PurpleCore() {
    }
}
