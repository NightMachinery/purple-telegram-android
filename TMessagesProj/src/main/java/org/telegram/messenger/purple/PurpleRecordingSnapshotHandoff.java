package org.telegram.messenger.purple;

public final class PurpleRecordingSnapshotHandoff {
    public interface Dispatcher { void later(Runnable task, long delayMs); }
    private PurpleRecordingSnapshotHandoff() { }

    public static boolean stop(PurpleRecordingSessions sessions, String key, int reason, long[] target, Dispatcher dispatcher) {
        if (reason != 0 && reason != 6 && reason != 7) return false;
        final PurpleRecordingSessions.Capture capture = sessions.snapshotCapture(key);
        if (capture == null) return false;
        final PurpleRecordingSessions.SnapshotStop stop = sessions.reserveStop(capture, reason, target);
        if (stop == null) return capture.reserved();
        final boolean renamed = capture.media.renameTo(stop.destination);
        dispatcher.later(() -> sessions.finishSnapshot(stop, renamed), renamed ? 1000 : 0);
        return true;
    }
}
