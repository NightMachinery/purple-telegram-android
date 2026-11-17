package org.telegram.messenger.purple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PurplePlaylistsPublicationWorkerCases {
    private static int checks;
    private static void check(boolean value, String reason) { ++checks; if (!value) throw new AssertionError(reason); }
    private static void await(CountDownLatch latch) throws Exception { check(latch.await(10, TimeUnit.SECONDS), "bounded worker callback"); }
    private static PurplePlaylistsSyncPublication.Attempt attempt(Path root) throws Exception {
        try (PurplePlaylistsPublicationJournalCases.Flow flow = new PurplePlaylistsPublicationJournalCases.Flow(root, 0)) {
            flow.start((value, done) -> done.accept(false)); return flow.port.intent;
        }
    }
    private static void run(Path root, int mode) throws Exception {
        PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
        PurplePlaylistsWorker worker = fixture.worker(0);
        CountDownLatch gate = new CountDownLatch(1), entered = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicBoolean active = new AtomicBoolean(true), accepted = new AtomicBoolean(true); AtomicInteger answers = new AtomicInteger();
        PurplePlaylistsSyncPublication.Attempt attempt = attempt(root);
        try {
            check(worker.postStore(null, (store, self) -> { entered.countDown(); if (!gate.await(10, TimeUnit.SECONDS)) throw new AssertionError("bounded gate"); }, () -> { }), "actual store gate queued");
            await(entered);
            if (mode == 1) for (int i = 0; i < 4; ++i) check(worker.postStore(null, (store, self) -> { }, () -> { }), "four bounded store slots");
            PurplePlaylistsSyncPublication.Journal journal = PurplePlaylistsPublicationJournal.onWorker(worker, attempt.origin, attempt.chat, active::get);
            journal.prepare(attempt, value -> { accepted.set(value); answers.incrementAndGet(); done.countDown(); });
            if (mode == 2) active.set(false);
            if (mode == 3) worker.close();
            gate.countDown(); await(done);
            check(accepted.get() == (mode == 0), "worker journal admission and current scope " + mode);
            check(answers.get() == 1, "completion emitted once " + mode);
            if (mode == 0) {
                CountDownLatch read = new CountDownLatch(1); AtomicBoolean pending = new AtomicBoolean();
                check(worker.postStore(null, (store, self) -> {
                    PurplePlaylistsPublicationJournal actual = new PurplePlaylistsPublicationJournal(store.media(), attempt.origin, attempt.chat, worker::downloadCurrent);
                    PurplePlaylistsPublicationJournal.Read value = actual.read();
                    pending.set(value.status == PurplePlaylistsPublicationJournal.Status.Pending && value.randomId == attempt.randomId);
                    read.countDown();
                }, read::countDown), "queued recovery read");
                await(read); check(pending.get(), "actual serialized account factory persists exact journal");
            }
        } finally { gate.countDown(); fixture.stop(worker); }
        Path file = fixture.folder.resolve("sync-publication.bin");
        check(Files.exists(file) == (mode == 0), "refused account work cannot leave journal " + mode);
        CountDownLatch closed = new CountDownLatch(1); AtomicBoolean value = new AtomicBoolean(true);
        PurplePlaylistsPublicationJournal.onWorker(worker, attempt.origin, attempt.chat, active::get).prepare(attempt, ok -> { value.set(ok); closed.countDown(); });
        await(closed); check(!value.get(), "closed worker refuses journal immediately");
    }
    public static void main(String[] args) throws Exception {
        Path root = java.nio.file.Paths.get(args[0]); Files.createDirectories(root);
        for (int mode = 0; mode < 4; ++mode) run(root, mode);
        System.out.println("PLAYLIST_PUBLICATION_WORKER_CHECKS=" + checks);
        System.out.println("PLAYLIST_PUBLICATION_WORKER_OK");
    }
}
