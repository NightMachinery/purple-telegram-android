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
        public final boolean escalation;
        public final int step, maxExtra, resetMinutes;
        private Snapshot(boolean enabled, long revision, boolean escalation, int step, int maxExtra, int resetMinutes) {
            this.enabled = enabled; this.revision = revision; this.escalation = escalation;
            this.step = step; this.maxExtra = maxExtra; this.resetMinutes = resetMinutes;
        }
        public int value(String option) {
            if ("escalation".equals(option)) return escalation ? 1 : 0;
            if ("step".equals(option)) return step;
            if ("max_extra".equals(option)) return maxExtra;
            if ("reset_minutes".equals(option)) return resetMinutes;
            return -1;
        }
        public boolean accepts(String option, int value) {
            return "escalation".equals(option) ? value == 0 || value == 1
                    : "step".equals(option) ? value >= 1 && value <= 300
                    : "max_extra".equals(option) ? value >= 0 && value <= 3600
                    : "reset_minutes".equals(option) && value >= 1 && value <= 1440;
        }
        public boolean current() { return this == snapshot(); }
    }
    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    public static synchronized Snapshot snapshot() {
        if (current == null) {
            try {
                SharedPreferences prefs = preferences();
                current = new Snapshot(prefs.getBoolean(KEY, false), prefs.getLong("revision", 0),
                        prefs.getBoolean("escalation", true), protective(prefs.getInt("step", 10), 1, 300, 10),
                        protective(prefs.getInt("max_extra", 90), 0, 3600, 90), protective(prefs.getInt("reset_minutes", 60), 1, 1440, 60));
            } catch (RuntimeException e) {
                FileLog.e(e);
                current = new Snapshot(true, Long.MAX_VALUE, true, 300, 3600, 1440);
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
            current = new Snapshot(enabled, opening.revision + 1, opening.escalation, opening.step, opening.maxExtra, opening.resetMinutes);
            return true;
        } catch (RuntimeException e) { FileLog.e(e); return false; }
    }
    private static int protective(int value, int min, int max, int fallback) {
        if (value >= min && value <= max) return value;
        FileLog.e(new IllegalStateException("Malformed local Peek wait option; protective default restored"));
        return fallback;
    }
    public static synchronized boolean commitWaitPolicy(Snapshot opening, String option, int value) {
        if (opening == null || !opening.current() || opening.revision == Long.MAX_VALUE || !opening.accepts(option, value)) return false;
        if (opening.value(option) == value) return true;
        try {
            SharedPreferences prefs = preferences();
            SharedPreferences.Editor edit = prefs.edit();
            if ("escalation".equals(option)) edit.putBoolean(option, value == 1);
            else edit.putInt(option, value);
            if (!edit.putLong("revision", opening.revision + 1).commit()) {
                SharedPreferences.Editor undo = prefs.edit();
                if ("escalation".equals(option)) undo.putBoolean(option, opening.escalation);
                else undo.putInt(option, opening.value(option));
                undo.putLong("revision", opening.revision).commit();
                return false;
            }
            current = new Snapshot(opening.enabled, opening.revision + 1,
                    "escalation".equals(option) ? value == 1 : opening.escalation,
                    "step".equals(option) ? value : opening.step,
                    "max_extra".equals(option) ? value : opening.maxExtra,
                    "reset_minutes".equals(option) ? value : opening.resetMinutes);
            return true;
        } catch (RuntimeException e) { FileLog.e(e); return false; }
    }
    static synchronized String readStreak() { return preferences().getString("streak", ""); }
    static synchronized boolean storeStreak(String value) {
        try { return preferences().edit().putString("streak", value).commit(); }
        catch (RuntimeException e) { FileLog.e(e); return false; }
    }
    private PurplePeekLocalSettings() { }
}
