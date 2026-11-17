package org.telegram.messenger.purple;
import java.util.*;
import java.util.function.*;
import org.telegram.messenger.*;
public final class PurplePlaylistsSyncRuntimeCases {
    private static int checks;
    private static void check(boolean ok, String message) { ++checks; if (!ok) throw new AssertionError(message); }
    private static final class Fixture implements AutoCloseable {
        final PurplePlaylistsWorker worker = new PurplePlaylistsWorker();
        final PurplePlaylistsAccount.Captured account = new PurplePlaylistsAccount.Captured();
        final List<PurplePlaylistsSyncResult> results = new ArrayList<>();
        Fixture() {
            ApplicationLoader.mainInterfacePaused = false; SharedConfig.appLocked = SharedConfig.isWaitingForPasscodeEnter = false;
            PurplePlaylistsStore.available = PurplePlaylistsStore.enabled = true;
            PurpleSyncChat.pending.clear(); PurpleSyncChat.client = new PurpleSyncClient();
            PurplePlaylistsSyncActor.starts = PurplePlaylistsSyncActor.closes = 0; PurplePlaylistsSyncActor.deferClose = false;
            PurplePlaylistsSyncRuntime.attach(worker, account); worker.drain();
        }
        AutoCloseable start() { return PurplePlaylistsSyncRuntime.manual(worker, results::add); }
        Consumer<PurpleSyncChat.Status> resolve() {
            worker.drain(); check(PurpleSyncChat.pending.size() == 1, "one resolution"); return PurpleSyncChat.pending.get(0);
        }
        public void close() {
            PurplePlaylistsSyncActor.deferClose = false; PurplePlaylistsSyncRuntime.stop(0); worker.drain();
            check(NotificationCenter.observers == 0, "owned observers removed");
            check(AndroidUtilities.timers.isEmpty(), "owned timers removed");
        }
    }
    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); Consumer<PurpleSyncChat.Status> late = f.resolve();
            AndroidUtilities.fire(30000);
            check(f.results.size() == 1 && f.results.get(0).status == PurplePlaylistsSyncResult.Status.TimedOut, "missing resolution bounded");
            late.accept(new PurpleSyncChat.Status()); check(PurplePlaylistsSyncActor.starts == 0, "timed-out callback cannot launch actor");
            f.start(); f.worker.drain(); check(PurpleSyncChat.pending.size() == 2, "resolution timeout releases admission");
        }
        try (Fixture f = new Fixture()) {
            AutoCloseable request = f.start(); Consumer<PurpleSyncChat.Status> reply = f.resolve();
            Runnable staleDeadline = AndroidUtilities.timer(30000);
            reply.accept(new PurpleSyncChat.Status()); reply.accept(new PurpleSyncChat.Status());
            check(PurplePlaylistsSyncActor.starts == 1 && PurplePlaylistsSyncActor.manual, "duplicate callback starts only one manual actor");
            staleDeadline.run(); check(f.results.isEmpty(), "resolution timer cannot end running actor");
            f.start(); check(f.results.size() == 1 && f.results.get(0).status == PurplePlaylistsSyncResult.Status.Busy, "single active operation");
            PurplePlaylistsSyncActor.deferClose = true; request.close(); request.close();
            check(PurplePlaylistsSyncActor.closes == 1 && f.results.size() == 1, "cancel waits for admitted write settlement");
            PurplePlaylistsSyncActor.last.finish(PurplePlaylistsSyncActor.Status.OutcomeUnknown);
            PurplePlaylistsSyncResult result = f.results.get(1);
            check(result.status == PurplePlaylistsSyncResult.Status.OutcomeUnknown && result.retryAt == Long.MAX_VALUE,
                    "unknown outcome and exact deadline retained");
            check(result.sendIssued && result.deleteIssued && result.deletedRecords == 3, "issued effects and deletion count retained");
            check(result.libraryCommitted && result.stateCommitted && result.libraryMayHaveChanged && result.stateMayHaveChanged
                    && result.checkpointStatus.equals("Ready") && result.changed(), "final write truth retained");
            PurplePlaylistsSyncActor.last.finish(PurplePlaylistsSyncActor.Status.Ready);
            check(f.results.size() == 2 && result.status == PurplePlaylistsSyncResult.Status.OutcomeUnknown, "once immutable completion");
        }
        for (int kind = 0; kind < 5; ++kind) try (Fixture f = new Fixture()) {
            AutoCloseable request = f.start(); Consumer<PurpleSyncChat.Status> late = f.resolve();
            if (kind == 0) request.close();
            if (kind == 1) { f.account.live = false; AndroidUtilities.fire(1000); }
            if (kind == 2) PurplePlaylistsSyncRuntime.background();
            if (kind == 3) { SharedConfig.appLocked = true; AndroidUtilities.fire(1000); }
            if (kind == 4) PurplePlaylistsSyncRuntime.stop(0);
            check(f.results.size() == 1 && f.results.get(0).status == PurplePlaylistsSyncResult.Status.Canceled, "lifecycle settles resolving operation " + kind);
            late.accept(new PurpleSyncChat.Status()); check(PurplePlaylistsSyncActor.starts == 0, "retired lifecycle callback cannot start " + kind);
        }
        try (Fixture f = new Fixture()) {
            f.start(); f.resolve().accept(new PurpleSyncChat.Status());
            PurpleSyncChat.client.live = false; AndroidUtilities.fire(1000);
            check(f.results.size() == 1 && PurplePlaylistsSyncActor.closes == 1, "route loss cancels only captured actor");
        }
        try (Fixture f = new Fixture()) {
            PurplePlaylistsStore.enabled = false; f.start(); f.resolve().accept(new PurpleSyncChat.Status());
            check(PurplePlaylistsSyncActor.starts == 1 && PurplePlaylistsSyncActor.manual, "manual check allowed with automatic sync off");
        }
        try (Fixture f = new Fixture()) {
            PurplePlaylistsStore.available = false; f.start(); f.worker.drain();
            check(f.results.size() == 1 && f.results.get(0).status == PurplePlaylistsSyncResult.Status.Unavailable
                    && PurpleSyncChat.pending.isEmpty(), "unreadable storage never resolves chat");
        }
        try (Fixture f = new Fixture()) {
            f.worker.full = true; f.start(); f.worker.refusal.run();
            check(f.results.size() == 1 && f.results.get(0).status == PurplePlaylistsSyncResult.Status.Busy, "queue rejection and callback complete once");
        }
        try (Fixture f = new Fixture()) {
            f.start(); Consumer<PurpleSyncChat.Status> old = f.resolve(); PurplePlaylistsSyncRuntime.stop(0);
            PurplePlaylistsWorker next = new PurplePlaylistsWorker();
            PurplePlaylistsSyncRuntime.attach(next, new PurplePlaylistsAccount.Captured()); next.drain();
            List<PurplePlaylistsSyncResult> newer = new ArrayList<>();
            PurplePlaylistsSyncRuntime.manual(next, newer::add); next.drain();
            old.accept(new PurpleSyncChat.Status()); check(PurplePlaylistsSyncActor.starts == 0, "old account request cannot take new runtime");
            PurpleSyncChat.pending.get(1).accept(new PurpleSyncChat.Status());
            check(PurplePlaylistsSyncActor.starts == 1 && newer.isEmpty(), "replacement runtime retains own admission");
        }
        System.out.println("PLAYLIST_SYNC_RUNTIME_CHECKS=" + checks);
        System.out.println("PLAYLIST_SYNC_RUNTIME_OK");
    }
}
