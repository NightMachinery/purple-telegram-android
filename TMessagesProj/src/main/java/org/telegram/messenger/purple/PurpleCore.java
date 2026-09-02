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
import java.util.List;

public final class PurpleCore {

    private static boolean loaded;

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
    public static synchronized void ensureLoaded() {
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

    public static ParseResult parse(byte[] utf8) {
        ensureLoaded();
        return ParseResult.fromJson(parseSettings(utf8));
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

    private PurpleCore() {
    }
}
