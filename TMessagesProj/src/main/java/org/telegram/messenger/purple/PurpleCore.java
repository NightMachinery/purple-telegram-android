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
    private static native String loadNative(byte[] settingsUtf8, byte[] stateUtf8,
            String deviceId, String devicePlatform, String deviceClass);

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

    private static native String setSchedulePausedNative(
            byte[] stateUtf8, boolean paused, long until);

    private static native String scheduleTickNative(byte[] stateUtf8,
            String deviceId, String devicePlatform, String deviceClass);

    private static native String focusTickNative(
            byte[] stateUtf8, boolean active, String enterTarget);

    private static native String setOverrideNative(
            byte[] stateUtf8, long bareId, int kind, int seconds);

    private static native String deciderNative(long bareId, int kind);

    private static native String listsForNative(byte[] settingsUtf8, long bareId);

    private static native String spliceMemberNative(
            byte[] settingsUtf8, String list, long bareId, boolean add, String titlesJson);

    private static native String addListNative(
            byte[] settingsUtf8, String name, String title);

    private static native String setTableBoolNative(
            byte[] settingsUtf8, String table, String key, boolean value);

    private static native String setTableStringNative(
            byte[] settingsUtf8, String table, String key, String value);

    private static native String addRulesetNative(
            byte[] settingsUtf8, String name, String device, String mode);

    private static native String removeRulesetNative(byte[] settingsUtf8, String name);

    private static native String setRulesetStringNative(
            byte[] settingsUtf8, String name, String key, String value);

    private static native String setScheduleRuleNative(
            byte[] settingsUtf8, String ruleset, int index, int expectedFrom,
            int expectedTill, String expectedPreset, boolean enabled, int[] days,
            int from, int till, String preset);

    private static native String appendScheduleRuleNative(
            byte[] settingsUtf8, String ruleset, boolean enabled, int[] days,
            int from, int till, String preset);

    private static native String removeScheduleRuleNative(
            byte[] settingsUtf8, String ruleset, int index, int expectedFrom,
            int expectedTill, String expectedPreset);

    private static native boolean shouldAutoSendNative(byte[] settingsUtf8,
            byte[] stateUtf8, byte[] fileBytes, boolean wroteFromImport);

    private static native String noteSentNative(byte[] stateUtf8, byte[] fileBytes);

    private static native String noteImportedNative(byte[] stateUtf8, byte[] fileBytes);

    private static native int lastSeenReasonNative(
            boolean exactKnown, boolean coarse, boolean byMe);

    private static native String rememberTradeNative(
            byte[] stateUtf8, long peer, long readAt, long wasOnline);

    private static native long[] rememberedTradeNative(
            byte[] stateUtf8, long peer, long now);

    private static native boolean tradeAllowedNative(
            byte[] stateUtf8, long peer, long now);

    private static native String tradesNative(byte[] stateUtf8, long now);

    /**
     * What the running preset would say about one chat with the peek set aside.
     * Prefer {@link PurpleGate#hiddenWhilePeeking}, which is the only caller
     * and the only question this answers.
     */
    private static native int visibleUnpeekedNative(long bareId, int kind);

    private static native String screenTimeReportNative(byte[] settingsUtf8,
            byte[] logUtf8, long fromMs, long toMs, int bucketUnit, String timeZone);

    private static native String screenTimeLedgerNative(byte[] settingsUtf8,
            byte[] logUtf8, long dayStartMs, String timeZone);

    private static native String screenTimePruneNative(byte[] settingsUtf8,
            byte[] logUtf8, long nowMs);

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

        /**
         * When the pause runs out, in local wall-clock seconds. Zero means it
         * lasts until it is lifted by hand, which is what a pause has always
         * been and what an older state.toml still says - a different thing
         * from a deadline that has already passed, and the reason this is not
         * simply a number of hours left.
         */
        public final long schedulePausedUntil;

        /**
         * True when there is a schedule to pause: this device runs at least one
         * rule, or the file describes a ruleset at all. The second half matters
         * on a phone whose only rules are the laptop's - the schedule is still
         * there to be switched off, even though nothing on this device fires.
         */
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
                boolean schedulePaused, long schedulePausedUntil,
                boolean scheduleConfigured,
                List<Override> overrides, long nextOverrideDeadline, int hideScope,
                int recentSeconds, int recentScope, int recentStyle) {
            this.peeking = peeking;
            this.peekDeadline = peekDeadline;
            this.peekSeconds = peekSeconds;
            this.schedulePaused = schedulePaused;
            this.schedulePausedUntil = schedulePausedUntil;
            this.scheduleConfigured = scheduleConfigured;
            this.overrides = overrides;
            this.nextOverrideDeadline = nextOverrideDeadline;
            this.hideScope = hideScope;
            this.recentSeconds = recentSeconds;
            this.recentScope = recentScope;
            this.recentStyle = recentStyle;
        }

        static final Clock NONE = new Clock(false, 0, 0, false, 0, false,
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
                    object.optLong("schedulePausedUntil", 0),
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

        /**
         * True when this tick is the one that lifted a pause that had run out.
         *
         * Worth its own flag rather than being folded into {@code applied}: the
         * pause is what the ticking itself is conditioned on, so a cleared
         * pause that nothing reread would stop the clock that had just cleared
         * it - even on a tick that moved no preset.
         */
        public final boolean unpaused;

        /** The preset the schedule now wants. */
        public final String target;

        /** The preset left in place instead, when {@code applied} is false. */
        public final String kept;

        /** What put {@code kept} there, for the log line. */
        public final String keptSource;

        public final String text;

        Tick(boolean applied, boolean unpaused, String target, String kept,
                String keptSource, String text) {
            this.applied = applied;
            this.unpaused = unpaused;
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
    public static String setSchedulePaused(byte[] state, boolean paused, long until) {
        try {
            return setSchedulePausedNative(state, paused, until);
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
    public static Tick scheduleTick(byte[] state,
            String deviceId, String devicePlatform, String deviceClass) {
        final String json;
        try {
            json = scheduleTickNative(state, deviceId, devicePlatform, deviceClass);
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
                    object.optBoolean("unpaused", false),
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
     * One rule block, as the schedule screens draw and edit it.
     *
     * Only the rules the parser kept are here. A broken one has no window and
     * no preset to draw, so the screen names it from {@link Loaded#warnings}
     * instead - that is the only place the reason it was dropped survives.
     *
     * {@code index} is the position in the RAW array of its own ruleset,
     * counting the dropped ones, because that is the address the splice edits
     * by: a rule numbered by its position in this list would move the moment a
     * broken one above it was fixed. {@code ruleset} is the other half of that
     * address. {@code from} and {@code till} are minutes since midnight, so
     * {@code "9:00"} and {@code "09:00"} are one rule.
     */
    public static final class ScheduleRule {
        /**
         * Where this rule lives, as every splice op wants it: empty for the
         * flat {@code [[schedule.rules]]} array, a ruleset's name otherwise.
         *
         * Not a display name. The implicit ruleset wrapping the flat rules is
         * called "rules" on screen but is addressed with an empty name, and a
         * file is free to spell out a real ruleset called "rules" as well.
         */
        public final String ruleset;

        public final int index;

        /** The line its header is on, 1-based, for a screen that points at it. */
        public final int line;

        public final boolean enabled;

        /** Monday .. Sunday as 1 .. 7, in the order the file wrote them. */
        public final int[] days;

        public final int from;
        public final int till;
        public final String preset;

        private ScheduleRule(String ruleset, int index, int line, boolean enabled,
                int[] days, int from, int till, String preset) {
            this.ruleset = ruleset;
            this.index = index;
            this.line = line;
            this.enabled = enabled;
            this.days = days;
            this.from = from;
            this.till = till;
            this.preset = preset;
        }
    }

    /**
     * One {@code [[schedule.rulesets]]} block: a named group of rules and the
     * answer to "which devices is this for".
     *
     * Every ruleset the file describes is handed over, not only the ones this
     * device runs - editing the laptop's half of the schedule from the phone is
     * the whole reason for there being one file.
     *
     * The flat {@code [[schedule.rules]]} array arrives as one of these too,
     * first, with {@link #implicit} set: it resolves through exactly the same
     * path as everything else, so a file that never heard of rulesets needs no
     * special case anywhere above this line.
     */
    public static final class ScheduleRuleset {
        /** What it is called, and what to print. "rules" for the implicit one. */
        public final String name;

        /**
         * What to hand a splice op. Empty for the implicit ruleset, whose rules
         * live in the flat array; {@link #name} for every other.
         */
        public final String address;

        /**
         * Which devices it is for: {@code any}, a class ({@code mobile},
         * {@code desktop}), a platform ({@code android}, {@code ios},
         * {@code macos}, {@code windows}, {@code linux}) or a device id.
         * Anything the core does not recognise is taken as a device id, since
         * the list of platforms is closed and the list of devices is not.
         */
        public final String device;

        /** {@code disabled}, {@code enabled} or {@code always}. */
        public final String mode;

        /**
         * What it wants between its windows, or null when it leaves that to
         * {@code [schedule] outside}. Null is not the same answer as "normal".
         */
        public final String outside;

        /** True for the flat {@code [[schedule.rules]]} array in ruleset form. */
        public final boolean implicit;

        /** Its position in the raw array, or -1 for the implicit one. */
        public final int index;

        /** The line its header is on, 1-based, or 0 when there is no file. */
        public final int line;

        /** Its rules in file order, the disabled ones included. */
        public final List<ScheduleRule> rules;

        private ScheduleRuleset(String name, String address, String device, String mode,
                String outside, boolean implicit, int index, int line,
                List<ScheduleRule> rules) {
            this.name = name;
            this.address = address;
            this.device = device;
            this.mode = mode;
            this.outside = outside;
            this.implicit = implicit;
            this.index = index;
            this.line = line;
            this.rules = rules;
        }
    }

    /** The two halves of one rule's address: which ruleset, and where in it. */
    public static final class ScheduleNow {
        public final String ruleset;
        public final int index;

        private ScheduleNow(String ruleset, int index) {
            this.ruleset = ruleset;
            this.index = index;
        }
    }

    /**
     * What {@code [devices]} calls one device id.
     *
     * The label lives in settings.toml rather than in a preference so that it
     * travels with the file: a laptop showing "the phone" beside a ruleset is
     * the entire reason for writing one down. Nothing depends on a device being
     * listed - an unlisted id shows as itself.
     */
    public static final class DeviceLabel {
        public final String id;
        public final String label;

        private DeviceLabel(String id, String label) {
            this.id = id;
            this.label = label;
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
     * Writes a new empty {@code [lists.x]}, just after the last one already
     * there so the lists stay together.
     *
     * The list is created and nothing else: no preset names it yet, so it
     * changes what the app shows only once one does.
     *
     * @param title said only when it says something the name does not
     * @return a refusal in {@code error} for an empty name, one starting with
     *         {@code *}, or a name already taken - all of them worth showing
     */
    public static SpliceResult addList(byte[] settings, String name, String title) {
        ensureLoaded();
        try {
            return splice(addListNative(settings, name, title));
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
     * Sets one string under one table - {@code [schedule] outside}, and the
     * label {@code [devices]} gives an id.
     *
     * The string half of {@link #setTableBool} and written the same way: the
     * value is replaced where it stands, the key and any trailing comment stay
     * as the user wrote them, and a file that never mentioned the table gains
     * it rather than being re-serialised.
     */
    public static SpliceResult setTableString(
            byte[] settings, String table, String key, String value) {
        ensureLoaded();
        try {
            return splice(setTableStringNative(settings, table, key, value));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /**
     * Writes a new empty {@code [[schedule.rulesets]]} block.
     *
     * {@code device} and {@code mode} are written only when they are not the
     * defaults, so a file where every ruleset spells out what it would have
     * meant anyway does not happen.
     *
     * @return a refusal in {@code error} for an empty name or one already
     *         taken - the name is the address every later edit goes through
     */
    public static SpliceResult addRuleset(
            byte[] settings, String name, String device, String mode) {
        ensureLoaded();
        try {
            return splice(addRulesetNative(settings, name, device, mode));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /** Takes a whole ruleset out, its rules with it. */
    public static SpliceResult removeRuleset(byte[] settings, String name) {
        ensureLoaded();
        try {
            return splice(removeRulesetNative(settings, name));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /**
     * Sets one of a ruleset's own string keys - {@code device}, {@code mode},
     * {@code outside}, or {@code name} for a rename.
     *
     * An empty {@code value} takes the key out of the file, which is how a
     * screen says "back to the default" without writing the default down.
     */
    public static SpliceResult setRulesetString(
            byte[] settings, String name, String key, String value) {
        ensureLoaded();
        try {
            return splice(setRulesetStringNative(settings, name, key, value));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /**
     * Rewrites one schedule rule in place, key by key.
     *
     * {@code ruleset} and {@code index} are the rule's address - {@link
     * ScheduleRule#ruleset} and {@link ScheduleRule#index}: which block it
     * lives in, empty for the flat array, and its position in that block's raw
     * array counting the ones the parser threw away. The three {@code expected}
     * values are what the screen read off the rule, and the core refuses when
     * the rule there no longer says them - so a dialog left open while the file
     * moved underneath cannot rewrite a different rule.
     */
    public static SpliceResult setScheduleRule(
            byte[] settings, String ruleset, int index, int expectedFrom,
            int expectedTill, String expectedPreset, boolean enabled, int[] days,
            int from, int till, String preset) {
        ensureLoaded();
        try {
            return splice(setScheduleRuleNative(settings, ruleset, index, expectedFrom,
                    expectedTill, expectedPreset, enabled, days, from, till, preset));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /** Writes a new rule after the ruleset's last one, or starts the section. */
    public static SpliceResult appendScheduleRule(
            byte[] settings, String ruleset, boolean enabled, int[] days, int from,
            int till, String preset) {
        ensureLoaded();
        try {
            return splice(appendScheduleRuleNative(
                    settings, ruleset, enabled, days, from, till, preset));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /** Takes one rule out, on the same expectation as {@link #setScheduleRule}. */
    public static SpliceResult removeScheduleRule(
            byte[] settings, String ruleset, int index, int expectedFrom,
            int expectedTill, String expectedPreset) {
        ensureLoaded();
        try {
            return splice(removeScheduleRuleNative(
                    settings, ruleset, index, expectedFrom, expectedTill, expectedPreset));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
    }

    /**
     * Whether saving these bytes should also post them to Saved Messages.
     *
     * The whole of the ping-pong rule, and it lives in the core on purpose:
     * the app around it has a network, a Saved Messages history and a file
     * watcher, which makes it the worst possible place to prove anything about
     * when a save deserves a document.
     *
     * @param settings the settings.toml that was just written - where the
     *                 switch is read from
     * @param state the current state.toml, or null
     * @param file the same bytes, as the fingerprint is taken over them
     * @param wroteFromImport whether this save IS an import writing the file it
     *                        just received, which never sends
     * @return false whenever the core could not be reached, which is the
     *         direction that costs a document rather than sends a stray one
     */
    public static boolean shouldAutoSend(
            byte[] settings, byte[] state, byte[] file, boolean wroteFromImport) {
        ensureLoaded();
        try {
            return shouldAutoSendNative(settings, state, file, wroteFromImport);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Returns the state.toml text remembering the file this device just sent.
     *
     * @return the text to write, or null when the core could not be reached
     */
    public static String noteSent(byte[] state, byte[] file) {
        ensureLoaded();
        try {
            return noteSentNative(state, file);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * The same for the file this device just wrote because the other one sent
     * it, which is what stops it from being sent straight back.
     */
    public static String noteImported(byte[] state, byte[] file) {
        ensureLoaded();
        try {
            return noteImportedNative(state, file);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return null;
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
     * @param settings       the bytes of settings.toml, or null
     * @param state          the bytes of state.toml, or null
     * @param deviceId       what this install calls itself, for the rulesets
     * @param devicePlatform "android"
     * @param deviceClass    "mobile"
     * @return the resolution now in force, never null
     */
    public static Loaded load(byte[] settings, byte[] state,
            String deviceId, String devicePlatform, String deviceClass) {
        ensureLoaded();
        return Loaded.fromJson(
                loadNative(settings, state, deviceId, devicePlatform, deviceClass));
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
     * The same answer with a running peek set aside, for the one question a
     * peek makes unanswerable: whether the chat you are looking at is one the
     * preset hides.
     *
     * {@link #visible} cannot say. A peek is part of the resolution, so the
     * core already answers {@link #SHOW_ALWAYS} for everything while one runs -
     * which is right for drawing a chat list and wrong for asking why the chat
     * is on it. Asked once per chat opened, never per row.
     */
    public static int visibleUnpeeked(long bareId, int kind) {
        if (!loaded) {
            ensureLoaded();
        }
        return visibleUnpeekedNative(bareId, kind);
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
         * Whether the Channels tab's "similar channels" section is drawn -
         * {@code [suggestions] recommended_channels_p}.
         *
         * Off unless the file says otherwise, which is the opposite default
         * from everything else in the table and deliberately so: it is the one
         * suggestion in the app that is not assembled out of your own chats -
         * the server picks it - and a fork about deciding who reaches you is
         * not the place to switch that on for somebody.
         */
        public final boolean recommendedChannels;

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
         * The preset this device wants between its windows: the most specific
         * chosen ruleset that names one, or {@code [schedule] outside}.
         *
         * Not always "normal" any more, which is what makes it worth handing
         * over: a window ending is a move to THIS, and a status line that
         * assumed Normal would be describing somebody else's file.
         */
        public final String scheduleOutside;

        /**
         * The preset the schedule wants right now, or null when no window
         * covers the moment - which includes a schedule switched off or with no
         * rules. Null is a different answer from wanting Normal, and has to be:
         * otherwise an empty section would quietly force Normal over every
         * other way of choosing a preset.
         */
        public final String scheduleTarget;

        /**
         * The address of the rule the moment is inside, or null when none is -
         * which includes a schedule switched off and one with no rule for this
         * device. Worked out by the core rather than here, so the
         * midnight-crossing case has one implementation rather than two.
         */
        public final ScheduleNow scheduleNow;

        /**
         * Every ruleset the file describes, the implicit one first. All of
         * them, not only the ones this device runs: editing the laptop's half
         * of the schedule from the phone is the point of there being one file.
         */
        public final List<ScheduleRuleset> scheduleRulesets;

        /**
         * The names of the rulesets this device chose, in the order their rules
         * were merged. For a screen that has to explain why a rule is or is not
         * running here.
         */
        public final List<String> scheduleChosen;

        /**
         * The rules this device actually runs, merged out of the rulesets it
         * chose: most specific first, then file order, enabled rules only -
         * which is the order the first-match engine walks them in.
         *
         * Not every rule in the file. A screen listing what a ruleset holds
         * reads {@link ScheduleRuleset#rules} instead; this list is what is
         * happening, not what is written down.
         */
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

        /**
         * Whether saving settings.toml also posts it to Saved Messages -
         * {@code [sync] send_after_save_p}.
         *
         * Off unless the file says otherwise: sending is a message in a real
         * chat, so it should be something asked for once, in words, and not
         * something an upgrade started doing on somebody's behalf.
         */
        public final boolean sendAfterSave;

        /**
         * Whether a coarse last seen says why it is coarse, in the chat header
         * and the profile - {@code [last_seen] reasons_p}.
         *
         * On unless the file says otherwise, matching the core: "last seen
         * recently" with no reason is the fork withholding something it knows.
         */
        public final boolean lastSeenReasons;

        /**
         * Whether the "show mine to see theirs" sheet is offered at all -
         * {@code [last_seen] trade_p}. The reason line is tappable only while
         * this is on, so turning it off leaves the explanation and takes away
         * the offer.
         */
        public final boolean lastSeenTrade;

        /**
         * How long to wait for their exact {@code was_online} after asking,
         * before putting the privacy rules back - {@code trade_hold}. Short by
         * design: the whole exposure is this window.
         */
        public final int lastSeenTradeHold;

        /** How long a read stays worth showing - {@code trade_remember}. */
        public final int lastSeenTradeRemember;

        /**
         * The least time between two trades with the same person -
         * {@code trade_cooldown}. One trade is a moment of exposure chosen on
         * purpose; one offered every time their chat opens is a subscription.
         */
        public final int lastSeenTradeCooldown;

        /**
         * Whether anything is written to screentime.log at all -
         * {@code [screen_time] enabled_p}.
         *
         * Off unless the file says so, and the one default in here that is not
         * a convenience: this is a record of what you looked at and for how
         * long, and nothing should start keeping one because a version number
         * moved.
         */
        public final boolean screenTimeEnabled;

        /**
         * How long one send action counts as active for, in seconds -
         * {@code action_span}. Also the throttle on composer edits: one event
         * per burst of typing, because a per-keystroke log would be a
         * keylogger's shape for no extra truth.
         */
        public final int screenTimeActionSpan;

        /**
         * How close two actions have to be for the whole gap between them to
         * count as active - {@code active_gap}. Read by the screen rather than
         * the recorder: the core applies it at read time.
         */
        public final int screenTimeActiveGap;

        /**
         * How long without any input pauses the session - {@code idle_after}.
         * The recorder's watchdog runs on this, but the pause itself is the
         * core's: the Idle event is stamped back to where the input stopped.
         */
        public final int screenTimeIdleAfter;

        /** How many days of log to keep. Zero keeps everything. */
        public final int screenTimeRetentionDays;

        /**
         * How many {@code [[screen_time.budgets]]} the file declares. A count
         * and not the budgets: a budget is only ever needed beside what has
         * been spent against it, and that is the ledger's answer, not the
         * resolution's.
         */
        public final int screenTimeBudgets;

        /** What this install told the core it calls itself. */
        public final String deviceId;

        /** The friendly names {@code [devices]} gives ids, in file order. */
        public final List<DeviceLabel> devices;

        /** The state.toml text to write back, or null when it did not change. */
        public final String stateText;

        private Loaded(boolean ok, String error, List<String> warnings, int version,
                boolean normal, String preset, String title, int lists, boolean usedCache,
                String cacheReason, boolean activeMissing, List<FolderEntry> folders,
                boolean foldersRestricted, List<String> silencedFolders,
                List<String> quietFolders, List<ExemptFolder> exemptFolders,
                int[] defaultModes, int listCount, List<PresetInfo> presets,
                List<View> views, boolean hideEverywhere,
                boolean hideInvisibleSuggestions, boolean recommendedChannels,
                boolean hideArchive,
                boolean scheduleEnabled, String scheduleTarget, String scheduleOutside,
                ScheduleNow scheduleNow, List<ScheduleRuleset> scheduleRulesets,
                List<String> scheduleChosen, List<ScheduleRule> scheduleRules,
                boolean focusSyncEnabled, String focusSyncEnter, String focusSyncExit,
                Clock clock, boolean premium, boolean sendAfterSave,
                boolean lastSeenReasons, boolean lastSeenTrade, int lastSeenTradeHold,
                int lastSeenTradeRemember, int lastSeenTradeCooldown,
                boolean screenTimeEnabled, int screenTimeActionSpan,
                int screenTimeActiveGap, int screenTimeIdleAfter,
                int screenTimeRetentionDays, int screenTimeBudgets,
                String deviceId, List<DeviceLabel> devices, String stateText) {
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
            this.recommendedChannels = recommendedChannels;
            this.hideArchive = hideArchive;
            this.scheduleEnabled = scheduleEnabled;
            this.scheduleTarget = scheduleTarget;
            this.scheduleOutside = scheduleOutside;
            this.scheduleNow = scheduleNow;
            this.scheduleRulesets = scheduleRulesets;
            this.scheduleChosen = scheduleChosen;
            this.scheduleRules = scheduleRules;
            this.focusSyncEnabled = focusSyncEnabled;
            this.focusSyncEnter = focusSyncEnter;
            this.focusSyncExit = focusSyncExit;
            this.clock = clock;
            this.premium = premium;
            this.sendAfterSave = sendAfterSave;
            this.lastSeenReasons = lastSeenReasons;
            this.lastSeenTrade = lastSeenTrade;
            this.lastSeenTradeHold = lastSeenTradeHold;
            this.lastSeenTradeRemember = lastSeenTradeRemember;
            this.lastSeenTradeCooldown = lastSeenTradeCooldown;
            this.screenTimeEnabled = screenTimeEnabled;
            this.screenTimeActionSpan = screenTimeActionSpan;
            this.screenTimeActiveGap = screenTimeActiveGap;
            this.screenTimeIdleAfter = screenTimeIdleAfter;
            this.screenTimeRetentionDays = screenTimeRetentionDays;
            this.screenTimeBudgets = screenTimeBudgets;
            this.deviceId = deviceId;
            this.devices = devices;
            this.stateText = stateText;
        }

        /** One array of rule blocks out of the load result, empty if absent. */
        private static List<ScheduleRule> rules(JSONArray array) {
            final List<ScheduleRule> result = new ArrayList<>();
            if (array == null) {
                return result;
            }
            for (int i = 0; i < array.length(); ++i) {
                final JSONObject entry = array.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                final JSONArray weekdays = entry.optJSONArray("days");
                final int[] days = new int[weekdays == null ? 0 : weekdays.length()];
                for (int a = 0; a < days.length; ++a) {
                    days[a] = weekdays.optInt(a, 0);
                }
                result.add(new ScheduleRule(
                        entry.optString("ruleset", ""),
                        entry.optInt("index", -1),
                        entry.optInt("line", 0),
                        entry.optBoolean("enabled", true),
                        days,
                        entry.optInt("from", -1),
                        entry.optInt("to", -1),
                        entry.optString("preset", "")));
            }
            return result;
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
                    false, true, false, true, false, null, "normal", null,
                    Collections.<ScheduleRuleset>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<ScheduleRule>emptyList(), false, "", "previous",
                    Clock.NONE, true, false, true, true, 10, 24 * 3600, 5 * 60,
                    false, 3, 30, 60, 90, 0, "",
                    Collections.<DeviceLabel>emptyList(), null);
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
                final List<ScheduleRule> scheduleRules =
                        rules(object.optJSONArray("scheduleRules"));
                final List<ScheduleRuleset> scheduleRulesets = new ArrayList<>();
                final JSONArray rulesets = object.optJSONArray("scheduleRulesets");
                if (rulesets != null) {
                    for (int i = 0; i < rulesets.length(); ++i) {
                        final JSONObject entry = rulesets.optJSONObject(i);
                        if (entry == null) {
                            continue;
                        }
                        scheduleRulesets.add(new ScheduleRuleset(
                                entry.optString("name", ""),
                                entry.optString("address", ""),
                                entry.optString("device", "any"),
                                entry.optString("mode", "enabled"),
                                // Null and "normal" are different answers: one
                                // leaves the question to [schedule] outside and
                                // the other overrides it with the same word.
                                entry.isNull("outside")
                                        ? null
                                        : entry.optString("outside", null),
                                entry.optBoolean("implicit", false),
                                entry.optInt("index", -1),
                                entry.optInt("line", 0),
                                rules(entry.optJSONArray("rules"))));
                    }
                }
                final JSONObject nowAt = object.optJSONObject("scheduleNow");
                final ScheduleNow scheduleNow = (nowAt == null)
                        ? null
                        : new ScheduleNow(
                                nowAt.optString("ruleset", ""),
                                nowAt.optInt("index", -1));
                final List<DeviceLabel> devices = new ArrayList<>();
                final JSONArray labelled = object.optJSONArray("devices");
                if (labelled != null) {
                    for (int i = 0; i < labelled.length(); ++i) {
                        final JSONObject entry = labelled.optJSONObject(i);
                        if (entry == null) {
                            continue;
                        }
                        devices.add(new DeviceLabel(
                                entry.optString("id", ""),
                                entry.optString("label", "")));
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
                        // These three default true, matching the core's own
                        // defaults, so a result that somehow lacks them keeps
                        // hiding rather than quietly revealing.
                        object.optBoolean("hideInvisibleSuggestions", true),
                        // The exception, and false for the same reason the
                        // core has it false: the server's channel picks are
                        // the one suggestion not made out of your own chats,
                        // so a result missing the key leaves them out.
                        object.optBoolean("recommendedChannels", false),
                        object.optBoolean("hideArchive", true),
                        object.optBoolean("scheduleEnabled", true),
                        object.isNull("scheduleTarget")
                                ? null
                                : object.optString("scheduleTarget", null),
                        // "normal" unless the result says otherwise, matching
                        // the core's own default for the key.
                        object.optString("scheduleOutside", "normal"),
                        scheduleNow,
                        scheduleRulesets,
                        names(object, "scheduleChosen"),
                        scheduleRules,
                        // Off unless the result says otherwise, which is also
                        // the core's default: a load that somehow lacks the
                        // table must not start driving the preset.
                        object.optBoolean("focusSyncEnabled", false),
                        object.optString("focusSyncEnter", ""),
                        object.optString("focusSyncExit", "previous"),
                        Clock.fromJson(object),
                        object.optBoolean("premium", true),
                        // Off unless the result says otherwise, matching the
                        // core: nothing should start posting documents because
                        // a load result came back short of a key.
                        object.optBoolean("sendAfterSave", false),
                        // Both on unless the result says otherwise, matching
                        // the core: a load short of the keys should explain a
                        // coarse status rather than quietly go back to
                        // withholding the reason it knows.
                        object.optBoolean("lastSeenReasons", true),
                        object.optBoolean("lastSeenTrade", true),
                        object.optInt("lastSeenTradeHold", 10),
                        object.optInt("lastSeenTradeRemember", 24 * 3600),
                        object.optInt("lastSeenTradeCooldown", 5 * 60),
                        // Off unless the result says otherwise, which is the
                        // core's default and the only one here that matters:
                        // a load short of the key must not start recording.
                        object.optBoolean("screenTimeEnabled", false),
                        object.optInt("screenTimeActionSpan", 3),
                        object.optInt("screenTimeActiveGap", 30),
                        object.optInt("screenTimeIdleAfter", 60),
                        object.optInt("screenTimeRetentionDays", 90),
                        object.optInt("screenTimeBudgets", 0),
                        object.optString("deviceId", ""),
                        devices,
                        object.isNull("stateText") ? null : object.optString("stateText", null));
            } catch (JSONException e) {
                return failed("bad result from the core");
            }
        }
    }

    /**
     * Why a last seen reads as coarse, as the core's {@code LastSeenReason}
     * numbers it.
     *
     * {@link #REASON_NONE} is both "there is a real moment in this status" and
     * "a long time ago": the server has no field saying whether the second is
     * inactivity or a block, and the fork does not guess. Only
     * {@link #REASON_BY_ME} has anything the user can act on, and it is the
     * only one the trade is offered for.
     */
    public static final int REASON_NONE = 0;
    public static final int REASON_BY_ME = 1;
    public static final int REASON_HIDDEN_BY_THEM = 2;

    /**
     * Why one status is coarse. The three booleans are what the TL layer
     * flattens to - the core knows nothing about TL_userStatusRecently and must
     * not learn - so both forks answer this the same way from one rule.
     *
     * @param exactKnown a status carrying a real {@code was_online}
     * @param coarse     one of recently / last week / last month
     * @param byMe       the {@code by_me} flag on a coarse status
     */
    public static int lastSeenReason(boolean exactKnown, boolean coarse, boolean byMe) {
        ensureLoaded();
        return lastSeenReasonNative(exactKnown, coarse, byMe);
    }

    /**
     * Writes down one finished trade and returns the state.toml text to save,
     * or null if the state could not be read.
     *
     * A {@code wasOnlineUnix} of zero records a trade whose hold ran out with
     * no exact status ever arriving. That is still a moment of exposure that
     * happened, and it is what the cooldown counts.
     */
    public static String rememberTrade(byte[] stateUtf8, long peer, long readAtUnix,
            long wasOnlineUnix) {
        ensureLoaded();
        return rememberTradeNative(stateUtf8, peer, readAtUnix, wasOnlineUnix);
    }

    /**
     * What was read for one person, if the read is still inside
     * {@code trade_remember}, or null.
     */
    public static Trade rememberedTrade(byte[] stateUtf8, long peer, long nowUnix) {
        ensureLoaded();
        final long[] pair = rememberedTradeNative(stateUtf8, peer, nowUnix);
        return (pair == null || pair.length < 2)
                ? null
                : new Trade(peer, pair[0], pair[1]);
    }

    /**
     * Whether a trade with this person may be offered now, or whether the last
     * one is still inside {@code trade_cooldown}.
     */
    public static boolean tradeAllowed(byte[] stateUtf8, long peer, long nowUnix) {
        ensureLoaded();
        return tradeAllowedNative(stateUtf8, peer, nowUnix);
    }

    /** Every trade still worth showing, newest read first. */
    public static List<Trade> trades(byte[] stateUtf8, long nowUnix) {
        ensureLoaded();
        final List<Trade> result = new ArrayList<>();
        final String json = tradesNative(stateUtf8, nowUnix);
        if (json == null) {
            return result;
        }
        try {
            final JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); ++i) {
                final JSONObject entry = array.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                result.add(new Trade(
                        entry.optLong("peer", 0),
                        entry.optLong("readAt", 0),
                        entry.optLong("wasOnline", 0)));
            }
        } catch (JSONException e) {
            FileLog.e("Purple: bad trade list from the core", e);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * One remembered trade: we showed this person our last seen for a few
     * seconds, read theirs, and put our privacy rules back.
     */
    public static final class Trade {

        /** The bare user id we traded with. */
        public final long peer;

        /** When we read it, in unix seconds - the "as of". */
        public final long readAtUnix;

        /**
         * Their real {@code was_online}, in unix seconds. Zero for a trade
         * whose hold ran out with nothing exact ever arriving.
         */
        public final long wasOnlineUnix;

        Trade(long peer, long readAtUnix, long wasOnlineUnix) {
            this.peer = peer;
            this.readAtUnix = readAtUnix;
            this.wasOnlineUnix = wasOnlineUnix;
        }
    }


    // ---- [screen_time] -------------------------------------------------------

    /**
     * What a stretch of screen time was spent in, as the core's
     * {@code Purple::ScreenTimeKind} numbers it.
     *
     * The first four are {@link #KIND_PRIVATE} and friends by value, on
     * purpose: {@code kind:groups} in a budget and {@code kinds = ["groups"]}
     * in a list mean the same word. {@link #SCREEN_KIND_ELSEWHERE} is the one
     * that has no counterpart - the chat list, search, settings - and it exists
     * so the splits add up to foreground time rather than to something smaller
     * with no name.
     */
    public static final int SCREEN_KIND_PRIVATE = 0;
    public static final int SCREEN_KIND_GROUP = 1;
    public static final int SCREEN_KIND_CHANNEL = 2;
    public static final int SCREEN_KIND_BOT = 3;
    public static final int SCREEN_KIND_ELSEWHERE = 4;
    public static final int SCREEN_KIND_COUNT = 5;

    /** What a row of bars is a row of - the core's {@code Purple::BucketUnit}. */
    public static final int BUCKET_HOUR_OF_DAY = 0;
    public static final int BUCKET_DAY = 1;
    public static final int BUCKET_WEEK = 2;
    public static final int BUCKET_MONTH = 3;

    /** What a budget is counting - the core's {@code Purple::BudgetTarget}. */
    public static final int BUDGET_ALL = 0;
    public static final int BUDGET_CHAT = 1;
    public static final int BUDGET_KIND = 2;
    public static final int BUDGET_PRESET = 3;

    /** What it does when the day's allowance is gone. */
    public static final int BUDGET_SOFT = 0;
    public static final int BUDGET_HARD = 1;

    /**
     * The whole screen-time report for one window.
     *
     * One call rather than one per view: parsing the log and deriving its
     * sessions is the expensive half, and every number on the screen comes out
     * of the same derivation.
     *
     * @param settings settings.toml, for the thresholds the sessions are
     *                 derived through - the same ones the screen names
     * @param log      screentime.log, whole
     * @param zoneId   the zone a day boundary is decided in, as an IANA id
     */
    public static Report screenTimeReport(byte[] settings, byte[] log,
            long fromMs, long toMs, int bucketUnit, String zoneId) {
        ensureLoaded();
        return Report.fromJson(
                screenTimeReportNative(settings, log, fromMs, toMs, bucketUnit, zoneId));
    }

    /**
     * What every budget has spent on the day {@code dayStartMs} falls in.
     *
     * A moment inside the day rather than a date, because which day a moment
     * belongs to is a local-time question and the answer has to be the same one
     * the buckets used.
     */
    public static List<Budget> screenTimeLedger(byte[] settings, byte[] log,
            long dayStartMs, String zoneId) {
        ensureLoaded();
        final List<Budget> result = new ArrayList<>();
        final String json = screenTimeLedgerNative(settings, log, dayStartMs, zoneId);
        if (json == null) {
            return result;
        }
        try {
            final JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); ++i) {
                final JSONObject entry = array.optJSONObject(i);
                if (entry != null) {
                    result.add(new Budget(entry));
                }
            }
        } catch (JSONException e) {
            FileLog.e("Purple: bad screen time ledger from the core", e);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * The log with everything past {@code retention_days} dropped, as the text
     * to write back, or null when it could not be read - on which the caller
     * leaves the file alone rather than truncating it.
     */
    public static String screenTimePrune(byte[] settings, byte[] log, long nowMs) {
        ensureLoaded();
        return screenTimePruneNative(settings, log, nowMs);
    }

    /** One chat's share of a window. */
    public static final class ChatSpan {

        /** The bare id, as settings.toml writes it. */
        public final long dialogId;

        public final int kind;
        public final long totalMs;
        public final long activeMs;

        ChatSpan(JSONObject json) {
            this.dialogId = json.optLong("dialogId", 0);
            this.kind = json.optInt("kind", SCREEN_KIND_ELSEWHERE);
            this.totalMs = json.optLong("totalMs", 0);
            this.activeMs = json.optLong("activeMs", 0);
        }
    }

    /**
     * One bar, split by chat kind so a chart can stack it.
     *
     * The split arrives rather than being worked out here because bucketing is
     * the part that knows about calendars, and a week is the core's idea of a
     * week wherever the range happens to start.
     */
    public static final class Bucket {

        /** The hour for an hour-of-day row, otherwise the count from the first. */
        public final int index;

        /** The bucket's own window. Zero for hour-of-day, which is not one. */
        public final long startMs;
        public final long endMs;

        /** {@code "13"}, {@code "2026-09-08"}, {@code "2026-W37"}, {@code "2026-09"}. */
        public final String label;

        public final long totalMs;
        public final long activeMs;

        /** Indexed by {@code SCREEN_KIND_*}; always {@link #SCREEN_KIND_COUNT} long. */
        public final long[] kindTotalMs;
        public final long[] kindActiveMs;

        Bucket(JSONObject json) {
            this.index = json.optInt("index", 0);
            this.startMs = json.optLong("startMs", 0);
            this.endMs = json.optLong("endMs", 0);
            this.label = json.optString("label", "");
            this.totalMs = json.optLong("totalMs", 0);
            this.activeMs = json.optLong("activeMs", 0);
            this.kindTotalMs = new long[SCREEN_KIND_COUNT];
            this.kindActiveMs = new long[SCREEN_KIND_COUNT];
            final JSONArray kinds = json.optJSONArray("kinds");
            for (int i = 0; kinds != null && i < kinds.length() && i < SCREEN_KIND_COUNT; ++i) {
                final JSONObject kind = kinds.optJSONObject(i);
                if (kind == null) {
                    continue;
                }
                this.kindTotalMs[i] = kind.optLong("totalMs", 0);
                this.kindActiveMs[i] = kind.optLong("activeMs", 0);
            }
        }
    }

    /**
     * Everything a chart, a ranked list and a headline need about one window.
     *
     * The report is one of these for the whole range and one more for each
     * preset that appears in it, which is what makes the preset chip a lookup
     * in what already came back rather than a second query.
     */
    public static class Scope {

        /** Empty for the whole range; a preset name for one of the chips. */
        public final String preset;

        public final long totalMs;
        public final long activeMs;

        /** Time in chats the running preset was hiding - part of totalMs. */
        public final long hiddenMs;

        /** Ranked, longest first. */
        public final List<ChatSpan> chats;

        /** Indexed by {@code SCREEN_KIND_*}. */
        public final long[] kindTotalMs;
        public final long[] kindActiveMs;

        public final List<Bucket> buckets;

        Scope(JSONObject json) {
            this.preset = json.optString("preset", "");
            this.totalMs = json.optLong("totalMs", 0);
            this.activeMs = json.optLong("activeMs", 0);
            this.hiddenMs = json.optLong("hiddenMs", 0);
            this.chats = spans(json.optJSONArray("chats"));
            this.kindTotalMs = new long[SCREEN_KIND_COUNT];
            this.kindActiveMs = new long[SCREEN_KIND_COUNT];
            final JSONArray kinds = json.optJSONArray("kinds");
            for (int i = 0; kinds != null && i < kinds.length(); ++i) {
                final JSONObject kind = kinds.optJSONObject(i);
                if (kind == null) {
                    continue;
                }
                final int at = kind.optInt("kind", -1);
                if (at >= 0 && at < SCREEN_KIND_COUNT) {
                    this.kindTotalMs[at] = kind.optLong("totalMs", 0);
                    this.kindActiveMs[at] = kind.optLong("activeMs", 0);
                }
            }
            this.buckets = buckets(json.optJSONArray("buckets"));
        }

        static List<ChatSpan> spans(JSONArray array) {
            final List<ChatSpan> result = new ArrayList<>();
            for (int i = 0; array != null && i < array.length(); ++i) {
                final JSONObject entry = array.optJSONObject(i);
                if (entry != null) {
                    result.add(new ChatSpan(entry));
                }
            }
            return Collections.unmodifiableList(result);
        }

        static List<Bucket> buckets(JSONArray array) {
            final List<Bucket> result = new ArrayList<>();
            for (int i = 0; array != null && i < array.length(); ++i) {
                final JSONObject entry = array.optJSONObject(i);
                if (entry != null) {
                    result.add(new Bucket(entry));
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /** The whole range, plus the things only the whole range has. */
    public static final class Report extends Scope {

        /**
         * Every day in the range folded onto one clock - the "reading load".
         * Always there, whatever unit the bars are in, because it answers a
         * different question: not how much, but when in a day.
         */
        public final List<Bucket> reading;

        /** Hour by weekday, rows Monday..Sunday. {@code [7][24]}. */
        public final long[][] heatTotalMs;
        public final long[][] heatActiveMs;

        /** This range against the one of the same length before it. */
        public final long deltaMs;
        public final long previousTotalMs;
        public final long previousActiveMs;

        /**
         * Null for a previous range with nothing in it. There is no percentage
         * change from zero, and a number shown there would be made up.
         */
        public final Integer changePercent;

        /** One scope per preset that appears in the range, longest first. */
        public final List<Scope> presets;

        private Report(JSONObject json) {
            super(json);
            this.reading = buckets(json.optJSONArray("reading"));
            this.heatTotalMs = new long[7][24];
            this.heatActiveMs = new long[7][24];
            final JSONObject heat = json.optJSONObject("heat");
            if (heat != null) {
                fillHeat(heat.optJSONArray("totalMs"), this.heatTotalMs);
                fillHeat(heat.optJSONArray("activeMs"), this.heatActiveMs);
            }
            final JSONObject compare = json.optJSONObject("compare");
            this.deltaMs = compare == null ? 0 : compare.optLong("deltaMs", 0);
            this.previousTotalMs = compare == null ? 0 : compare.optLong("previousTotalMs", 0);
            this.previousActiveMs = compare == null ? 0 : compare.optLong("previousActiveMs", 0);
            this.changePercent = (compare == null || compare.isNull("changePercent"))
                    ? null
                    : Integer.valueOf(compare.optInt("changePercent", 0));
            final List<Scope> scopes = new ArrayList<>();
            final JSONArray array = json.optJSONArray("presets");
            for (int i = 0; array != null && i < array.length(); ++i) {
                final JSONObject entry = array.optJSONObject(i);
                if (entry != null) {
                    scopes.add(new Scope(entry));
                }
            }
            this.presets = Collections.unmodifiableList(scopes);
        }

        private static void fillHeat(JSONArray rows, long[][] into) {
            for (int day = 0; rows != null && day < rows.length() && day < 7; ++day) {
                final JSONArray hours = rows.optJSONArray(day);
                for (int hour = 0; hours != null && hour < hours.length() && hour < 24; ++hour) {
                    into[day][hour] = hours.optLong(hour, 0);
                }
            }
        }

        /**
         * Null for a report the core could not produce. The screen draws
         * nothing rather than zeroes: "no screen time" and "the log could not
         * be read" are two different things to say.
         */
        static Report fromJson(String json) {
            if (json == null) {
                return null;
            }
            try {
                return new Report(new JSONObject(json));
            } catch (JSONException e) {
                FileLog.e("Purple: bad screen time report from the core", e);
                return null;
            }
        }
    }

    /** One {@code [[screen_time.budgets]]} entry, and what it has spent today. */
    public static final class Budget {

        /** Its position in the file, so a screen can address it back. */
        public final int index;

        /** The target as written - {@code "all"}, {@code "kind:groups"}, … */
        public final String target;

        public final int targetKind;

        /** Filled according to {@link #targetKind}; only one ever means anything. */
        public final long chat;
        public final int chatKind;
        public final String preset;

        public final long spentMs;
        public final long perDayMs;

        /** Whether the allowance is gone. */
        public final boolean reached;

        public final int mode;
        public final int snoozeSeconds;
        public final int snoozesPerDay;

        /**
         * Whether this budget offers a snooze at all, with none yet taken - the
         * core's {@code CoverAllowed(0, budget)}. False for a soft budget,
         * which never puts a cover up, and for a hard one written
         * {@code snoozes_per_day = 0}, which is how a cap is made absolute.
         */
        public final boolean snoozable;

        Budget(JSONObject json) {
            this.index = json.optInt("index", 0);
            this.target = json.optString("target", "all");
            this.targetKind = json.optInt("targetKind", BUDGET_ALL);
            this.chat = json.optLong("chat", 0);
            this.chatKind = json.optInt("chatKind", SCREEN_KIND_ELSEWHERE);
            this.preset = json.optString("preset", "");
            this.spentMs = json.optLong("spentMs", 0);
            this.perDayMs = json.optLong("perDayMs", 0);
            this.reached = json.optBoolean("reached", false);
            this.mode = json.optInt("mode", BUDGET_SOFT);
            this.snoozeSeconds = json.optInt("snoozeSeconds", 5 * 60);
            this.snoozesPerDay = json.optInt("snoozesPerDay", 2);
            this.snoozable = json.optBoolean("snoozable", false);
        }

        /**
         * Whether this budget is counting the chat in front of you.
         *
         * The same four cases as the core's {@code BudgetCovers}, over the
         * session's three labels rather than over a session - which is what a
         * cover has in hand: the chat, what it is, and what was running.
         */
        public boolean covers(long dialogId, int kind, String runningPreset) {
            switch (targetKind) {
            case BUDGET_CHAT:
                return chat == dialogId;
            case BUDGET_KIND:
                return chatKind == kind;
            case BUDGET_PRESET:
                return effective(preset).equalsIgnoreCase(effective(runningPreset));
            default:
                return true;
            }
        }

        /** An empty preset name is Normal, the same fall-through the core takes. */
        private static String effective(String preset) {
            return (preset == null || preset.isEmpty()) ? "normal" : preset;
        }
    }

    private PurpleCore() {
    }
}
