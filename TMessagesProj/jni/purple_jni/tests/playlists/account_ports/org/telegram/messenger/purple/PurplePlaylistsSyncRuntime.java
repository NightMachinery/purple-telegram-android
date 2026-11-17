package org.telegram.messenger.purple;
final class PurplePlaylistsSyncRuntime {
    static java.util.function.Consumer<PurplePlaylistsSyncResult> manualDone;
    static int manualCalls, manualCloses;
    static boolean synchronous;
    static void attach(PurplePlaylistsWorker worker, PurplePlaylistsAccount.Captured captured) { }
    static void stop(int slot) { }
    static AutoCloseable manual(PurplePlaylistsWorker worker, java.util.function.Consumer<PurplePlaylistsSyncResult> done) {
        ++manualCalls; manualDone = done;
        if (synchronous) done.accept(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.Busy, "SyntheticBusy"));
        return () -> { ++manualCloses; };
    }
}
