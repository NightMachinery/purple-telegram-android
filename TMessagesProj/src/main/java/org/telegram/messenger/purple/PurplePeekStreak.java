package org.telegram.messenger.purple;

import android.os.SystemClock;
import android.provider.Settings;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public final class PurplePeekStreak {
    private static PurplePeekStreakModel state;
    private static boolean failed;
    private static String boot;
    public static final class Delay {
        public final int base, extra, seconds, resetMinutes;
        private final long revision;
        private final PurplePeekLocalSettings.Snapshot policy;
        private Delay(int base, PurplePeekLocalSettings.Snapshot policy) {
            this.base = Math.max(0, base); this.policy = policy;
            extra = state.extra(policy.escalation, policy.step, policy.maxExtra);
            seconds = (int) Math.min(Integer.MAX_VALUE, (long) this.base + extra);
            resetMinutes = policy.resetMinutes; revision = state.revision;
        }
        public boolean current() { return !failed && policy.current() && state != null && state.revision == revision; }
    }
    private static String boot() {
        if (boot != null) return boot;
        try {
            int count = Settings.Global.getInt(ApplicationLoader.applicationContext.getContentResolver(), "boot_count", -1);
            if (count >= 0) return boot = "count-" + count;
        } catch (RuntimeException e) { FileLog.e(e); }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String id = reader.readLine();
            if (id != null && id.matches("[a-fA-F0-9-]{36}")) return boot = id;
        } catch (java.io.IOException | RuntimeException e) { FileLog.e(e); }
        return boot = "";
    }
    private static long milliseconds(long seconds) {
        return seconds <= 0 ? 0 : seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
    }
    private static boolean load() {
        if (failed) return false;
        if (state == null) {
            try {
                String stored = PurplePeekLocalSettings.readStreak();
                state = PurplePeekStreakModel.decode(stored);
                if (stored.isEmpty() && !PurplePeekLocalSettings.storeStreak(state.encode())) failed = true;
            }
            catch (RuntimeException e) { FileLog.e(e); failed = true; }
        }
        return !failed;
    }
    private static void save(String previous) {
        if (!state.encode().equals(previous) && !PurplePeekLocalSettings.storeStreak(state.encode())) failed = true;
    }
    public static synchronized void observe(PurpleCore.Loaded loaded) {
        if (loaded == null || !load()) return;
        String previous = state.encode();
        state.observe(loaded.clock.peeking, milliseconds(loaded.clock.peekDeadline),
                SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot());
        save(previous);
    }
    public static synchronized Delay delay(int base, PurpleCore.Loaded loaded) {
        observe(loaded);
        if (!load()) return null;
        PurplePeekLocalSettings.Snapshot policy = PurplePeekLocalSettings.snapshot();
        String previous = state.encode();
        state.reset(SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot(), policy.resetMinutes);
        save(previous);
        return failed || policy.revision == Long.MAX_VALUE ? null : new Delay(base, policy);
    }
    private static final class Reservation {
        final String previous, reserved;
        final long revision;
        Reservation(String previous, String reserved, long revision) {
            this.previous = previous; this.reserved = reserved; this.revision = revision;
        }
    }
    private static synchronized Reservation reserve(PurpleCore.PeekChange preview) {
        if (!load()) return null;
        String previous = state.encode();
        long wall = System.currentTimeMillis();
        state.started(preview.left <= 0 ? 0 : wall + preview.left * 1000L, SystemClock.elapsedRealtime(), wall, boot());
        if (!PurplePeekLocalSettings.storeStreak(state.encode())) { failed = true; return null; }
        return new Reservation(previous, state.encode(), state.revision);
    }
    private static synchronized void refund(Reservation reservation) {
        if (state == null || state.revision != reservation.revision || !state.encode().equals(reservation.reserved)) return;
        PurplePeekStreakModel restored = PurplePeekStreakModel.decode(reservation.previous);
        restored.revision = state.revision + 1; state = restored;
        if (!PurplePeekLocalSettings.storeStreak(state.encode())) failed = true;
    }
    public static PurpleCore.PeekChange activatePeek(int seconds, PurpleGate.PeekSettings settings, boolean extension) {
        if (!PurpleGate.currentPeekSettings(settings)) return null;
        byte[] before = PurpleState.read();
        PurpleCore.PeekChange preview = extension ? PurpleCore.extendPeek(before, seconds) : PurpleCore.startPeek(before, seconds);
        if (preview == null || preview.refused) return null;
        if (extension && !preview.extended) return PurpleGate.extendPeek(seconds, settings);
        if (!preview.peeking) return null;
        Reservation reservation = reserve(preview);
        if (reservation == null) return null;
        if (!PurpleGate.currentPeekSettings(settings) || !java.util.Arrays.equals(before, PurpleState.read())) {
            refund(reservation); return null;
        }
        PurpleCore.PeekChange result = extension ? PurpleGate.extendPeek(seconds, settings) : PurpleGate.startPeek(seconds, settings);
        if ((result == null || result.refused || !result.peeking || extension && !result.extended)
                && java.util.Arrays.equals(before, PurpleState.read())) refund(reservation);
        return result;
    }
    private PurplePeekStreak() { }
}
