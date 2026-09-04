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

    private static native String listsForNative(byte[] settingsUtf8, long bareId);

    private static native String spliceMemberNative(
            byte[] settingsUtf8, String list, long bareId, boolean add, String titlesJson);

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

        Clock(boolean peeking, long peekDeadline, int peekSeconds,
                boolean schedulePaused, boolean scheduleConfigured) {
            this.peeking = peeking;
            this.peekDeadline = peekDeadline;
            this.peekSeconds = peekSeconds;
            this.schedulePaused = schedulePaused;
            this.scheduleConfigured = scheduleConfigured;
        }

        static final Clock NONE = new Clock(false, 0, 0, false, false);

        static Clock fromJson(JSONObject object) {
            return new Clock(
                    object.optBoolean("peeking", false),
                    object.optLong("peekDeadline", 0),
                    object.optInt("peekSeconds", 0),
                    object.optBoolean("schedulePaused", false),
                    object.optBoolean("scheduleConfigured", false));
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
        final String json;
        try {
            json = spliceMemberNative(settings, list, bareId, add, titles);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new SpliceResult(null, false, "the core could not be reached");
        }
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

        /** Peek and schedule, which move on a clock rather than on an edit. */
        public final Clock clock;

        /** The state.toml text to write back, or null when it did not change. */
        public final String stateText;

        private Loaded(boolean ok, String error, List<String> warnings, int version,
                boolean normal, String preset, String title, int lists, boolean usedCache,
                String cacheReason, boolean activeMissing, List<FolderEntry> folders,
                boolean foldersRestricted, List<String> silencedFolders,
                List<String> quietFolders, List<ExemptFolder> exemptFolders,
                int[] defaultModes, int listCount, List<PresetInfo> presets,
                Clock clock, String stateText) {
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
            this.clock = clock;
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
                    Collections.<PresetInfo>emptyList(), Clock.NONE, null);
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
                        Clock.fromJson(object),
                        object.isNull("stateText") ? null : object.optString("stateText", null));
            } catch (JSONException e) {
                return failed("bad result from the core");
            }
        }
    }

    private PurpleCore() {
    }
}
