/*
 * This is the source code of Purple Telegram for Android.
 *
 * The Work Mode core (settings parser, splicer, state, engine) is shared with
 * the desktop fork and lives in TMessagesProj/jni/purple, built into
 * libpurplecore.so. This class is the whole Java surface of it.
 */

package org.telegram.messenger.purple;

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

        public final List<PresetInfo> presets;

        /** The state.toml text to write back, or null when it did not change. */
        public final String stateText;

        private Loaded(boolean ok, String error, List<String> warnings, int version,
                boolean normal, String preset, String title, int lists, boolean usedCache,
                String cacheReason, boolean activeMissing, List<FolderEntry> folders,
                boolean foldersRestricted, List<String> silencedFolders,
                List<PresetInfo> presets, String stateText) {
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
            this.presets = presets;
            this.stateText = stateText;
        }

        private static Loaded failed(String error) {
            return new Loaded(false, error, Collections.<String>emptyList(), 0, true,
                    "normal", "Normal", 0, false, "", false,
                    Collections.<FolderEntry>emptyList(), false,
                    Collections.<String>emptyList(),
                    Collections.<PresetInfo>emptyList(), null);
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
                final List<String> silencedFolders = new ArrayList<>();
                final JSONArray silenced = object.optJSONArray("silencedFolders");
                if (silenced != null) {
                    for (int i = 0; i < silenced.length(); ++i) {
                        final String name = silenced.optString(i, "");
                        if (name.length() > 0) {
                            silencedFolders.add(name);
                        }
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
                        presets,
                        object.isNull("stateText") ? null : object.optString("stateText", null));
            } catch (JSONException e) {
                return failed("bad result from the core");
            }
        }
    }

    private PurpleCore() {
    }
}
