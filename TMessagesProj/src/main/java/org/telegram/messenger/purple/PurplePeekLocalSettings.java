package org.telegram.messenger.purple;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public final class PurplePeekLocalSettings {
    private static final String FILE = "purple_peek_local";
    private static final String KEY = "end_on_focus_loss";
    private static Snapshot current;
    public static final class Snapshot {
        public final boolean enabled;
        public final long revision;
        private Snapshot(boolean enabled, long revision) { this.enabled = enabled; this.revision = revision; }
        public boolean current() { return this == snapshot(); }
    }
    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    public static synchronized Snapshot snapshot() {
        if (current == null) {
            try {
                SharedPreferences prefs = preferences();
                current = new Snapshot(prefs.getBoolean(KEY, false), prefs.getLong("revision", 0));
            } catch (RuntimeException e) {
                FileLog.e(e);
                current = new Snapshot(true, Long.MAX_VALUE);
            }
        }
        return current;
    }
    public static synchronized boolean commitFocusPolicy(Snapshot opening, boolean enabled) {
        if (opening == null || !opening.current() || opening.revision == Long.MAX_VALUE) return false;
        if (opening.enabled == enabled) return true;
        try {
            SharedPreferences prefs = preferences();
            if (!prefs.edit().putBoolean(KEY, enabled).putLong("revision", opening.revision + 1).commit()) {
                prefs.edit().putBoolean(KEY, opening.enabled).putLong("revision", opening.revision).commit();
                return false;
            }
            current = new Snapshot(enabled, opening.revision + 1);
            return true;
        } catch (RuntimeException e) { FileLog.e(e); return false; }
    }
    private PurplePeekLocalSettings() { }
}
