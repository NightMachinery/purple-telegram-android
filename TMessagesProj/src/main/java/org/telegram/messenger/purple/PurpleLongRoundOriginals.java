package org.telegram.messenger.purple;

import android.widget.Toast;
import org.telegram.messenger.*;
import java.io.File;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class PurpleLongRoundOriginals {
    private static final Map<String, PurpleLongRoundOriginal> current = new HashMap<>();
    private static final Map<PurpleLongRoundOriginal, Boolean> pending = new IdentityHashMap<>();
    private PurpleLongRoundOriginals() { }
    public static void capture(int account, int guid, File file, long durationMs) {
        if (file == null || durationMs <= PurpleLongRoundPlan.CIRCLE_MS) return;
        final String key = account + ":" + guid;
        final PurpleLongRoundOriginal previous = current.get(key);
        if (previous != null && previous.matches(file)) { persist(previous); return; }
        final long[] target = PurpleRecordingRegistry.roundOriginalTarget(account, guid);
        if (target == null || pending.size() >= 8) { notice(false); return; }
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
            original.saved = saved;
            if (saved) {
                original.closeSaved(); pending.remove(original);
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
