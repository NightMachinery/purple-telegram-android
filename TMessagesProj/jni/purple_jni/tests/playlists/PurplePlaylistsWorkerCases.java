package org.telegram.messenger.purple;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PurplePlaylistsWorkerCases {
    private static int checks;
    private static void check(boolean value, String name) { ++checks; if (!value) throw new AssertionError(name); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("bounded fixture gate"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    private static void directory(Path path) throws Exception {
        Files.createDirectory(path); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    private static void write(Path path, byte[] value) throws Exception {
        Files.write(path, value); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
    private static final class Gate implements Runnable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public void run() { entered.countDown(); await(release); }
    }
    private static final class Ui {
        final BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        final List<PurplePlaylistsWorker.Outcome> outcomes = new ArrayList<>();
        final AtomicBoolean current = new AtomicBoolean(true);
        PurplePlaylistsWorker.Target<Ui> target() {
            return new PurplePlaylistsWorker.Target<>(this, pending::add, current::get,
                    (view, outcome) -> view.outcomes.add(outcome));
        }
        Runnable take() throws Exception {
            Runnable result = pending.poll(10, TimeUnit.SECONDS);
            if (result == null) throw new AssertionError("bounded UI delivery");
            return result;
        }
        PurplePlaylistsWorker.Outcome next() throws Exception {
            int before = outcomes.size(); take().run();
            return outcomes.size() == before ? null : outcomes.get(outcomes.size() - 1);
        }
    }
    static final class Fixture {
        final Path files = Files.createTempDirectory("playlist-worker-");
        final Path root = files.resolve("purple");
        final Path folder = root.resolve("playlists");
        final AtomicReference<Runnable> hook = new AtomicReference<>();
        final AtomicReference<Thread> openingThread = new AtomicReference<>();
        final AtomicInteger opens = new AtomicInteger();
        Fixture() throws Exception {
            Files.setPosixFilePermissions(files, PosixFilePermissions.fromString("rwx------"));
            directory(root); directory(root.resolve("accounts"));
            write(root.resolve("local.toml"), bytes("root_file_account=\"0-101\"\nglobal_account=\"0-101\"\n"));
            write(root.resolve("accounts/root.toml"), bytes("root_file_account=\"0-101\"\n"));
            ApplicationLoader.files = files.toFile();
            PurpleAccountOwner.owner = "0-101"; PurpleAccountOwner.global = "0-101"; PurpleAccountOwner.ready = true;
            ++PurpleAccountOwner.revision; ++PurpleAccountOwner.role;
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) {
                UserConfig config = new UserConfig(); config.active = i < 2; config.user = i == 0 ? 101 : 202;
                UserConfig.instances[i] = config; ConnectionsManager.instances[i] = new ConnectionsManager();
            }
            UserConfig.selectedAccount = 0; android.os.Looper.mainThread = Thread.currentThread();
        }
        PurplePlaylistsWorker worker(int slot) {
            return new PurplePlaylistsWorker(PurplePlaylistsAccount.capture(slot), (captured, current) -> {
                openingThread.set(Thread.currentThread());
                opens.incrementAndGet();
                return PurplePlaylistsAccount.open(captured, () -> {
                    Runnable action = hook.getAndSet(null); if (action != null) action.run();
                    return current.current();
                });
            });
        }
        PurplePlaylistsWorker.Outcome read(PurplePlaylistsWorker worker, Ui ui) throws Exception {
            check(worker.read(ui.target()).admission == PurplePlaylistsWorker.Admission.Accepted, "read admitted without UI I/O");
            PurplePlaylistsWorker.Outcome result = ui.next();
            check(result != null && result.ready(), "actual worker reads real account JNI and files");
            return result;
        }
        void stop(PurplePlaylistsWorker worker) throws Exception {
            worker.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (worker.termination() == PurplePlaylistsWorker.Termination.Running && System.nanoTime() < deadline)
                Thread.sleep(2);
            check(worker.termination() == PurplePlaylistsWorker.Termination.Closed, "native store closes on creator actor after work unwinds");
        }
    }
    private static void snapshotsAndCancellation() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            PurplePlaylistsWorker.Snapshot first = f.read(worker, ui).snapshot;
            check(f.openingThread.get() != Thread.currentThread(), "main never opens native account files");
            check(!Files.exists(f.folder.resolve("library.json")), "read leaves library defaults absent");
            byte[] initial = first.library(); initial[0] = 'x';
            check(first.library()[0] == '{', "snapshot library bytes are defensive");
            UserConfig.selectedAccount = 1;
            worker.setSync(first, true, ui.target()); PurplePlaylistsWorker.Outcome edited = ui.next();
            check(edited.ready() && edited.stateCommitted, "captured account local preference commits");
            check(Files.exists(f.folder.resolve("local.json")) && !Files.exists(f.root.resolve("accounts/0-202/playlists/local.json")), "selected account does not retarget queued publication");
            worker.setSync(first, false, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.Changed, "consumed latest-read snapshot cannot authorize another write");
            PurplePlaylistsWorker.Snapshot second = f.read(worker, ui).snapshot;
            worker.merge(second, bytes("{\"format\":2}"), ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.InvalidRemote, "newer remote refused without replacement");
            worker.setSync(second, false, ui.target());
            check(ui.next().ready(), "invalid remote does not consume the valid read capability");
            PurplePlaylistsWorker.Snapshot third = f.read(worker, ui).snapshot;
            write(f.folder.resolve("library.json"), bytes("{\"format\":2}"));
            worker.read(ui.target()); PurplePlaylistsWorker.Outcome held = ui.next();
            check(held.status == PurplePlaylistsWorker.Status.Unavailable && held.storeStatus == PurplePlaylistsStore.Status.NewerLibrary, "newer local state is held without reset");
            worker.setSync(third, true, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.Changed, "failed started read invalidates prior capability");
            check(Arrays.equals(Files.readAllBytes(f.folder.resolve("library.json")), bytes("{\"format\":2}")), "failed read never repairs newer bytes");
        } finally { f.stop(worker); }
        check(worker.read(ui.target()).admission == PurplePlaylistsWorker.Admission.Closed, "closed actor refuses admission");
    }
    private static void queuedDeliveryAndForeignTickets() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0), other = f.worker(1);
        try {
            PurplePlaylistsWorker.Snapshot first = f.read(worker, ui).snapshot;
            Ui foreign = new Ui(); f.read(other, foreign);
            other.setSync(first, true, foreign.target());
            check(foreign.next().status == PurplePlaylistsWorker.Status.Changed, "foreign actor snapshot refused before filesystem mutation");
            Gate gate = new Gate(); f.hook.set(gate); worker.read(ui.target()); await(gate.entered);
            worker.read(ui.target()); gate.release.countDown();
            Runnable earlier = ui.take(), later = ui.take();
            earlier.run(); check(ui.outcomes.size() == 1, "older queued UI snapshot suppressed after later actor read");
            later.run(); check(ui.outcomes.size() == 2 && ui.outcomes.get(1).ready(), "latest queued UI snapshot admitted");
            worker.read(ui.target()); Runnable staleView = ui.take(); ui.current.set(false); staleView.run();
            check(ui.outcomes.size() == 2, "screen generation rejects late completion independently of execution");
            ui.current.set(true); f.read(worker, ui);
            worker.read(ui.target()); Runnable staleAccount = ui.take(); ++PurpleAccountOwner.revision; staleAccount.run();
            check(ui.outcomes.size() == 3, "account lifetime rechecked at UI delivery");
        } finally { f.stop(worker); f.stop(other); }
    }
    private static void boundedQueueAndRetirement() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); Gate gate = new Gate(); f.hook.set(gate);
        PurplePlaylistsWorker worker = f.worker(0);
        try {
            worker.read(ui.target()); await(gate.entered);
            List<PurplePlaylistsWorker.Request> queued = new ArrayList<>();
            for (int i = 0; i < 16; ++i) {
                PurplePlaylistsWorker.Request request = worker.read(ui.target()); queued.add(request);
                check(request.admission == PurplePlaylistsWorker.Admission.Accepted, "bounded pending slot admitted");
            }
            check(worker.read(ui.target()).admission == PurplePlaylistsWorker.Admission.Busy, "seventeenth pending request refused without blocking UI");
            queued.get(0).cancel(); worker.close();
            check(worker.read(ui.target()).admission == PurplePlaylistsWorker.Admission.Closed, "retirement admission fence is immediate during active native work");
            gate.release.countDown(); f.stop(worker);
            for (Runnable reply; (reply = ui.pending.poll()) != null;) reply.run();
            check(ui.outcomes.isEmpty(), "retired worker cannot publish queued UI results");
            check(!Files.exists(f.folder.resolve("library.json")) && !Files.exists(f.folder.resolve("local.json")), "queued work cannot create files after retirement");
            android.os.Looper.mainThread = null;
            try (PurplePlaylistsStore reopened = PurplePlaylistsAccount.open(PurplePlaylistsAccount.capture(0), () -> true)) {
                check(reopened.read().ready(), "owned writer lock released after retired actor closes");
            }
        } finally { gate.release.countDown(); f.stop(worker); }
    }
    private static void canceledRequestAndPartialTruth() throws Exception {
        for (boolean libraryFirst : new boolean[] { false, true }) {
            Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
            try {
                PurplePlaylistsWorker.Snapshot snapshot = f.read(worker, ui).snapshot;
                CountDownLatch submitted = new CountDownLatch(1);
                AtomicReference<PurplePlaylistsWorker.Request> active = new AtomicReference<>();
                Runnable[] guard = new Runnable[1];
                guard[0] = () -> {
                    await(submitted);
                    boolean stop = Files.exists(f.folder.resolve(libraryFirst ? "library.json" : "local.json"));
                    if (libraryFirst && stop) {
                        try (java.util.stream.Stream<Path> names = Files.list(f.folder)) {
                            stop = names.anyMatch(p -> p.getFileName().toString().startsWith(".playlist-tmp-"));
                        } catch (Exception e) { throw new IllegalStateException(e); }
                    }
                    if (stop) active.get().cancel(); else f.hook.set(guard[0]);
                };
                f.hook.set(guard[0]);
                active.set(libraryFirst ? worker.merge(snapshot, bytes("{\"format\":1,\"playlists\":{},\"purged\":{}}"), ui.target())
                        : worker.setSync(snapshot, true, ui.target()));
                submitted.countDown(); PurplePlaylistsWorker.Outcome result = ui.next(); f.hook.set(null);
                check(result != null && result.status == PurplePlaylistsWorker.Status.Canceled && !result.ready(), "inflight cancellation cannot report optimistic success");
                check(result.stateMayHaveChanged && !result.stateCommitted, "uncertain local publication survives cancellation result");
                if (libraryFirst) {
                    check(result.libraryCommitted && result.libraryMayHaveChanged, "library success plus failed local checkpoint is retained explicitly");
                    check(Files.exists(f.folder.resolve("library.json")) && !Files.exists(f.folder.resolve("local.json")), "partial result matches actual publication boundary");
                } else check(Files.exists(f.folder.resolve("local.json")), "post-rename cancellation preserves complete local bytes");
                f.read(worker, ui);
                check(f.opens.get() == 1, "later request uses healthy session after previous request cancel");
            } finally { f.hook.set(null); f.stop(worker); }
        }
    }
    private static void mergeBackupAndRequestCopy() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            f.read(worker, ui);
            byte[] raw = bytes(" {\"purged\":{},\"playlists\":{},\"format\":1} \n");
            write(f.folder.resolve("library.json"), raw);
            Gate gate = new Gate();
            worker.read(new PurplePlaylistsWorker.Target<>(ui, reply -> {
                ui.pending.add(reply); gate.run();
            }, ui.current::get, (view, outcome) -> view.outcomes.add(outcome)));
            await(gate.entered);
            PurplePlaylistsWorker.Snapshot snapshot = ui.next().snapshot;
            byte[] remote = bytes("{\"format\":1,\"playlists\":{},\"purged\":{}}");
            worker.merge(snapshot, remote, ui.target()); remote[0] = 'x'; gate.release.countDown();
            PurplePlaylistsWorker.Outcome result = ui.next();
            check(result.ready() && result.libraryCommitted && result.stateCommitted, "real merge confirms library before local checkpoint");
            check(Arrays.equals(raw, Files.readAllBytes(f.folder.resolve("library.json.bak"))), "merge retains exact raw prior backup");
            check(result.snapshot.library()[0] == '{', "queued remote payload copied before caller mutation");
        } finally { f.stop(worker); }
    }
    private static void accountRegistry() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui();
        PurplePlaylistsWorker first = null, second = null, replacement = null;
        try {
            first = PurplePlaylistsWorkers.acquire(0);
            check(first != null && first == PurplePlaylistsWorkers.acquire(0), "registry reuses exactly one actor per captured lifetime");
            second = PurplePlaylistsWorkers.acquire(1);
            check(second != null && second != first, "registry gives different captured accounts independent actors");
            check(PurplePlaylistsWorkers.acquire(3) == null, "registry refuses inactive account");
            PurplePlaylistsWorker.Snapshot ticket = f.read(first, ui).snapshot;
            PurpleAccountFiles.userChanging(0, 0);
            replacement = PurplePlaylistsWorkers.acquire(0);
            check(replacement != first, "same-user lifetime replacement immediately retires old actor");
            f.stop(first);
            if (replacement == null) replacement = PurplePlaylistsWorkers.acquire(0);
            check(replacement != null && replacement != first, "replacement opens only after prior creator-thread close");
            replacement.setSync(ticket, false, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.Changed, "old lifetime ticket cannot authorize replacement actor");
            f.read(replacement, ui);
            PurplePlaylistsWorkers.stop(0);
            check(replacement.read(ui.target()).admission == PurplePlaylistsWorker.Admission.Closed, "per-account stop retires admission immediately");
        } finally {
            PurplePlaylistsWorkers.stopAll();
            if (first != null) f.stop(first);
            if (second != null) f.stop(second);
            if (replacement != null && replacement != first) f.stop(replacement);
        }
    }
    private static void typedEditsAndUndo() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        PurplePlaylistsWorker other = null;
        try {
            PurplePlaylistsWorker.Snapshot initial = f.read(worker, ui).snapshot;
            byte[] entropy = new byte[10]; Arrays.fill(entropy, (byte) 11);
            PurplePlaylistsEdits.Command command = PurplePlaylistsEdits.Command.create("Actor 音楽", entropy, 100);
            entropy[0] = 99;
            worker.edit(initial, command, ui.target()); PurplePlaylistsWorker.Outcome created = ui.next();
            check(created != null && created.ready() && created.libraryCommitted && created.stateCommitted,
                    "typed create publishes real library and local checkpoint");
            check(created.edit != null && created.edit.changed && created.undo != null, "confirmed edit exposes opaque Undo ticket");
            String id = created.edit.playlistId;
            check(id.equals("pl-bmfqwcylbmfqwcyl"), "queued entropy is immutable caller copy");
            check(Arrays.equals(Files.readAllBytes(f.folder.resolve("library.json")), created.snapshot.library()), "edit snapshot matches actual durable library");
            worker.edit(initial, PurplePlaylistsEdits.Command.rename(id, "Stale", 200), ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.Changed, "typed edit requires exact latest read ticket");
            worker.edit(created.snapshot, PurplePlaylistsEdits.Command.rename(id, "", 200), ui.target());
            PurplePlaylistsWorker.Outcome invalid = ui.next();
            check(invalid.status == PurplePlaylistsWorker.Status.InvalidEdit && "InvalidTitle".equals(invalid.edit.error), "core refusal stays typed and preserves existing snapshot");
            worker.undo(created.snapshot, created.undo, 300, ui.target()); PurplePlaylistsWorker.Outcome undone = ui.next();
            check(undone.ready() && undone.undo != null && undone.undo.redo
                    && undone.undo.kind == PurplePlaylistsEdits.Kind.Create && undone.snapshot.view.playlists.isEmpty(),
                    "creation Undo exposes a labeled Redo for the removed playlist");
            worker.undo(undone.snapshot, created.undo, 400, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.Changed, "consumed Undo ticket cannot repeat");
            worker.undo(undone.snapshot, undone.undo, 450, ui.target());
            PurplePlaylistsWorker.Outcome redone = ui.next();
            check(redone.ready() && redone.undo != null && !redone.undo.redo
                    && redone.snapshot.view.playlists.get(0).id.equals(id), "creation Redo restores the exact playlist");
            worker.edit(redone.snapshot, PurplePlaylistsEdits.Command.create("Second", 500), ui.target());
            PurplePlaylistsWorker.Outcome second = ui.next();
            String secondId = second.edit.playlistId;
            worker.edit(second.snapshot, PurplePlaylistsEdits.Command.rename(secondId, "New title", 600), ui.target());
            PurplePlaylistsWorker.Outcome renamed = ui.next();
            worker.undo(renamed.snapshot, second.undo, 700, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.Changed, "new confirmed edit retires older Undo ticket");
            other = f.worker(1); PurplePlaylistsWorker.Snapshot foreign = f.read(other, ui).snapshot;
            other.undo(foreign, renamed.undo, 700, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.Changed, "Undo ticket cannot cross captured account actor");
            PurplePlaylistsWorker.Outcome read = f.read(worker, ui);
            check(read.undo == renamed.undo, "ordinary refresh preserves current actor Undo ticket");
            worker.undo(read.snapshot, read.undo, 800, ui.target());
            check(ui.next().ready(), "Undo remains usable after valid refresh");
        } finally { if (other != null) f.stop(other); f.stop(worker); }
    }
    private static void typedPartialCheckpoint() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            PurplePlaylistsWorker.Snapshot initial = f.read(worker, ui).snapshot;
            CountDownLatch submitted = new CountDownLatch(1);
            AtomicReference<PurplePlaylistsWorker.Request> active = new AtomicReference<>();
            Runnable[] guard = new Runnable[1];
            guard[0] = () -> {
                await(submitted); boolean stop = false;
                if (Files.exists(f.folder.resolve("library.json"))) {
                    try (java.util.stream.Stream<Path> names = Files.list(f.folder)) {
                        stop = names.anyMatch(p -> p.getFileName().toString().startsWith(".playlist-tmp-"));
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }
                if (stop) active.get().cancel(); else f.hook.set(guard[0]);
            };
            f.hook.set(guard[0]);
            active.set(worker.edit(initial, PurplePlaylistsEdits.Command.create("Partial", 200), ui.target()));
            submitted.countDown(); PurplePlaylistsWorker.Outcome partial = ui.next(); f.hook.set(null);
            check(partial != null && partial.status == PurplePlaylistsWorker.Status.Canceled && partial.libraryCommitted
                    && !partial.stateCommitted && partial.stateMayHaveChanged, "typed partial commit truth survives cancellation");
            check(partial.undo != null, "confirmed library preserves actor Undo despite canceled checkpoint");
            PurplePlaylistsWorker.Outcome read = f.read(worker, ui);
            worker.edit(read.snapshot, PurplePlaylistsEdits.Command.rename(partial.edit.playlistId, "Partial", 900), ui.target());
            PurplePlaylistsWorker.Outcome repaired = ui.next();
            check(repaired.ready() && !repaired.edit.changed && repaired.edit.stateChanged, "valid noop repairs missing local edit bookkeeping");
            org.json.JSONObject state = new org.json.JSONObject(new String(repaired.snapshot.state(), StandardCharsets.UTF_8));
            check(state.getLong("unsynced_since") == 200, "actor retains original partial edit age through checkpoint repair");
            check(repaired.undo == partial.undo, "checkpoint repair preserves exact current Undo ticket");
            worker.undo(repaired.snapshot, repaired.undo, 1000, ui.target());
            check(ui.next().ready(), "shared Undo succeeds after repaired partial write");
        } finally { f.hook.set(null); f.stop(worker); }
    }
    private static void canceledNoopDelivery() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            PurplePlaylistsWorker.Snapshot initial = f.read(worker, ui).snapshot;
            worker.edit(initial, PurplePlaylistsEdits.Command.create("Noop", 100), ui.target());
            PurplePlaylistsWorker.Outcome created = ui.next();
            check(created.ready(), "no-op cancellation setup commits typed edit");
            PurplePlaylistsWorker.Request request = worker.edit(created.snapshot,
                    PurplePlaylistsEdits.Command.rename(created.edit.playlistId, "Noop", 200), ui.target());
            Runnable reply = ui.take(); request.cancel();
            int before = ui.outcomes.size(); reply.run();
            check(ui.outcomes.size() == before, "canceled no-op cannot deliver optimistic success using older snapshot origin");
        } finally { f.stop(worker); }
    }
    public static void main(String[] args) throws Exception {
        snapshotsAndCancellation(); queuedDeliveryAndForeignTickets(); boundedQueueAndRetirement();
        canceledRequestAndPartialTruth(); mergeBackupAndRequestCopy(); accountRegistry(); typedEditsAndUndo(); typedPartialCheckpoint(); canceledNoopDelivery();
        System.out.println("PLAYLIST_WORKER_CHECKS=" + checks);
    }
}
