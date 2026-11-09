package org.telegram.messenger.purple;

import android.widget.Toast;
import org.telegram.messenger.*;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public final class PurpleLongRoundOriginals {
    private static final Map<String, PurpleLongRoundOriginal> current = new ConcurrentHashMap<>();
    private static final Map<PurpleLongRoundOriginal, Boolean> pending = new ConcurrentHashMap<>();
    private static final Map<String, android.system.StructStat> writers = new ConcurrentHashMap<>();
    private static final Map<String, Owner> owners = new ConcurrentHashMap<>();
    private PurpleLongRoundOriginals() { }
    public static Owner owner(int account, int guid) {
        final Owner owner = new Owner(account + ":" + guid, PurpleRecordingRegistry.roundOriginalTarget(account, guid));
        owners.put(owner.key, owner);
        return owner;
    }
    public static final class Owner {
        private final String key;
        private final long[] target;
        private final Map<String, PurpleLongRoundOriginal> captured = new ConcurrentHashMap<>();
        private Owner(String key, long[] target) { this.key = key; this.target = target == null ? null : target.clone(); }
        public void arm(File writer, long durationMs) {
            if (writer == null || durationMs <= PurpleLongRoundPlan.CIRCLE_MS) return;
            try { writers.put(writer.getAbsolutePath(), android.system.Os.stat(writer.getAbsolutePath())); }
            catch (Exception missing) { PurpleDiagLog.event("recordings", "original_writer", PurpleDiagLog.reason("io_failed")); }
        }
        public void capture(File file, File writer, long durationMs) throws java.io.IOException {
            if (durationMs <= PurpleLongRoundPlan.CIRCLE_MS) return;
            if (target == null) throw new java.io.IOException("original owner unavailable");
            try {
                final PurpleLongRoundOriginal previous = captured.get(file.getAbsolutePath());
                if (previous != null && previous.matches(file)) { if (!previous.saved) persist(previous); return; }
                final PurpleLongRoundOriginal original = new PurpleLongRoundOriginal(file, target);
                captured.put(file.getAbsolutePath(), original);
                original.writer(writer);
                pending.put(original, true);
                if (owners.get(key) == this) current.put(key, original);
                original.busy.set(true);
                try { original.saved = PurpleRecordingRegistry.keepLongOriginalBlocking(original); }
                finally { original.busy.set(false); }
                if (original.saved) { original.closeSaved(); pending.remove(original); if (writer != null) writers.remove(writer.getAbsolutePath()); }
                else AndroidUtilities.runOnUIThread(() -> notice(false));
            } catch (Exception failure) { throw new java.io.IOException("original reservation", failure); }
        }
    }
    public static boolean protectErase(File file) {
        if (protectWriter(file)) return true;
        for (PurpleLongRoundOriginal original : pending.keySet()) {
            if ((!original.saved && original.path.equals(file.getAbsolutePath())) || original.protectsWriter(file)) return true;
        }
        return false;
    }
    public static boolean protectWriter(File file) {
        final android.system.StructStat identity = writers.get(file.getAbsolutePath());
        if (identity != null) {
            try {
                final android.system.StructStat current = android.system.Os.stat(file.getAbsolutePath());
                if (current.st_dev == identity.st_dev && current.st_ino == identity.st_ino) return true;
            } catch (Exception missing) { return false; }
        }
        for (PurpleLongRoundOriginal original : pending.keySet()) if (original.protectsWriter(file)) return true;
        return false;
    }
    public static void capture(int account, int guid, File file, long durationMs) {
        if (file == null || durationMs <= PurpleLongRoundPlan.CIRCLE_MS) return;
        final String key = account + ":" + guid;
        final PurpleLongRoundOriginal previous = current.get(key);
        if (previous != null && previous.matches(file)) { persist(previous); return; }
        final long[] target = PurpleRecordingRegistry.roundOriginalTarget(account, guid);
        if (target == null) { notice(false); return; }
        try {
            final PurpleLongRoundOriginal original = new PurpleLongRoundOriginal(file, target);
            current.put(key, original); pending.put(original, true);
            persist(original);
        } catch (Exception failure) { notice(false); }
    }
    private static void persist(PurpleLongRoundOriginal original) {
        if (original.saved || !original.busy.compareAndSet(false, true)) return;
        PurpleRecordingRegistry.keepLongOriginal(original, saved -> {
            original.busy.set(false);
            original.saved |= saved;
            if (saved) {
                original.closeSaved(); pending.remove(original);
                if (original.writerPath != null) writers.remove(original.writerPath);
                if (original.released) current.values().removeIf(value -> value == original);
            } else notice(false);
        });
    }
    public static boolean beforeV2(int account, int guid, File file, long durationMs, VideoEditedInfo selection) {
        if (file == null) return true;
        try {
            final long start = selection == null ? -1 : selection.startTime;
            final long end = selection == null ? -1 : selection.endTime;
            if (start < -1 || end < -1 || start > durationMs || end > durationMs) throw new PurpleLongRoundSelection.Invalid();
            new PurpleLongRoundSelection(durationMs, start < 0 ? -1 : start * 1000, end < 0 ? -1 : end * 1000);
        } catch (PurpleLongRoundSelection.Invalid invalid) {
            Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.PurpleLongRoundSelectionInvalid), Toast.LENGTH_LONG).show();
            return false;
        }
        return ready(account, guid, file, durationMs);
    }
    public static boolean ready(int account, int guid, File file, long durationMs) {
        if (file == null || durationMs <= PurpleLongRoundPlan.CIRCLE_MS) return true;
        final PurpleLongRoundOriginal original = current.get(account + ":" + guid);
        if (original != null && original.matches(file) && original.saved
                && PurpleRecordingSendAs.sameUser(account, original.target[4])) return true;
        capture(account, guid, file, durationMs);
        notice(true);
        return false;
    }
    public static void release(int account, int guid) {
        owners.remove(account + ":" + guid);
        final PurpleLongRoundOriginal original = current.remove(account + ":" + guid);
        if (original != null) { original.released = true; original.closeSaved(); }
    }
    public static void retryPending() {
        for (PurpleLongRoundOriginal original : new java.util.ArrayList<>(pending.keySet())) persist(original);
    }
    private static void notice(boolean saving) {
        Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(saving
                ? R.string.PurpleLongRoundOriginalSaving : R.string.PurpleLongRoundOriginalFailed), Toast.LENGTH_LONG).show();
    }
}
