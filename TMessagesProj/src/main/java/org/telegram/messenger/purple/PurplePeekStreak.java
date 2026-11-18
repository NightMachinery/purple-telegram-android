package org.telegram.messenger.purple;

import android.os.SystemClock;
import android.provider.Settings;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import java.util.HashMap;

public final class PurplePeekStreak {
    private static final HashMap<String, Entry> entries = new HashMap<>();
    private static String boot;
    private static final class Entry {
        final PurpleAccountFiles.Identity identity;
        PurplePeekStreakModel state;
        boolean failed;
        Entry(PurpleAccountFiles.Identity identity) { this.identity = identity; }
    }
    public static final class Delay {
        public final int base, extra, seconds, resetMinutes;
        private final Entry entry;
        private final long revision;
        private final PurplePeekLocalSettings.Snapshot policy;
        private Delay(Entry entry, int base, PurplePeekLocalSettings.Snapshot policy) {
            this.entry = entry; this.base = Math.max(0, base); this.policy = policy;
            extra = entry.state.extra(policy.escalation, policy.step, policy.maxExtra);
            seconds = (int) Math.min(Integer.MAX_VALUE, (long) this.base + extra);
            resetMinutes = policy.resetMinutes; revision = entry.state.revision;
        }
        public boolean current() {
            synchronized (PurplePeekStreak.class) {
                if (entries.get(entry.identity.key) != entry || entry.failed || !policy.current()
                        || entry.state == null || entry.state.revision != revision) return false;
            }
            return entry.identity.current();
        }
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
    private static Entry load(int account) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(account);
        if (identity == null) return null;
        entries.entrySet().removeIf(value -> value.getValue().identity.slot == identity.slot
                && (!value.getKey().equals(identity.key) || value.getValue().identity.lifetime != identity.lifetime));
        Entry entry = entries.get(identity.key);
        if (entry == null || entry.identity.lifetime != identity.lifetime) {
            entry = new Entry(identity); entries.put(identity.key, entry);
        }
        if (entry.failed) return null;
        if (entry.state == null) {
            try {
                String stored = PurplePeekLocalSettings.readStreak(identity.key);
                entry.state = PurplePeekStreakModel.decode(stored);
                if (stored.isEmpty() && !PurplePeekLocalSettings.storeStreak(identity.key, entry.state.encode())) entry.failed = true;
            } catch (RuntimeException e) { FileLog.e(e); entry.failed = true; }
        }
        return entry.failed ? null : entry;
    }
    private static void save(Entry entry, String previous) {
        if (!entry.state.encode().equals(previous)
                && !PurplePeekLocalSettings.storeStreak(entry.identity.key, entry.state.encode())) entry.failed = true;
    }
    public static void observe(PurpleCore.Loaded loaded) { observe(UserConfig.selectedAccount, loaded); }
    public static synchronized void observe(int account, PurpleCore.Loaded loaded) {
        if (loaded == null) return;
        Entry entry = load(account);
        if (entry == null) return;
        String previous = entry.state.encode();
        entry.state.observe(loaded.clock.peeking, milliseconds(loaded.clock.peekDeadline),
                SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot());
        save(entry, previous);
    }
    public static Delay delay(int base, PurpleCore.Loaded loaded) { return delay(UserConfig.selectedAccount, base, loaded); }
    public static synchronized Delay delay(int account, int base, PurpleCore.Loaded loaded) {
        observe(account, loaded);
        Entry entry = load(account);
        if (entry == null) return null;
        PurplePeekLocalSettings.Snapshot policy = PurplePeekLocalSettings.snapshot();
        String previous = entry.state.encode();
        entry.state.reset(SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot(), policy.resetMinutes);
        save(entry, previous);
        return entry.failed || policy.revision == Long.MAX_VALUE ? null : new Delay(entry, base, policy);
    }
    private static final class Reservation {
        final Entry entry;
        final String previous, reserved;
        final long revision;
        Reservation(Entry entry, String previous, String reserved, long revision) {
            this.entry = entry; this.previous = previous; this.reserved = reserved; this.revision = revision;
        }
    }
    private static synchronized Reservation reserve(int account, PurpleAccountPeek.Result preview) {
        Entry entry = load(account);
        if (entry == null) return null;
        String previous = entry.state.encode();
        long wall = System.currentTimeMillis();
        entry.state.started(preview.left <= 0 ? 0 : wall + preview.left * 1000L, SystemClock.elapsedRealtime(), wall, boot());
        if (!PurplePeekLocalSettings.storeStreak(entry.identity.key, entry.state.encode())) { entry.failed = true; return null; }
        return new Reservation(entry, previous, entry.state.encode(), entry.state.revision);
    }
    private static synchronized void refund(Reservation reservation) {
        Entry entry = reservation.entry;
        if (entries.get(entry.identity.key) != entry || entry.state.revision != reservation.revision
                || !entry.state.encode().equals(reservation.reserved)) return;
        PurplePeekStreakModel restored = PurplePeekStreakModel.decode(reservation.previous);
        restored.revision = entry.state.revision + 1; entry.state = restored;
        if (!PurplePeekLocalSettings.storeStreak(entry.identity.key, entry.state.encode())) entry.failed = true;
    }
    public static PurpleCore.PeekChange activatePeek(int seconds, PurpleGate.PeekSettings settings, boolean extension) {
        if (!PurpleGate.currentPeekSettings(settings) || settings.session == null) return null;
        PurpleAccountPeek.Result preview = settings.session.preview(settings.clock, seconds, extension);
        if (preview == null) return null;
        if (extension && !preview.extended) return PurpleGate.extendPeek(seconds, settings);
        if (!preview.clock.peeking) return null;
        Reservation reservation = reserve(settings.account, preview);
        if (reservation == null) return null;
        if (!PurpleGate.currentPeekSettings(settings)) { refund(reservation); return null; }
        PurpleCore.PeekChange result = extension ? PurpleGate.extendPeek(seconds, settings) : PurpleGate.startPeek(seconds, settings);
        if ((result == null || result.refused || !result.peeking || extension && !result.extended)
                && settings.session.matches(settings.clock)) refund(reservation);
        return result;
    }
    private PurplePeekStreak() { }
}
