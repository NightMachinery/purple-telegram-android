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

public final class PurplePlaylistsDownloadCases {
    private static int checks;
    private static void check(boolean value, String name) { ++checks; if (!value) throw new AssertionError(name); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(60, TimeUnit.SECONDS)) throw new AssertionError("bounded fixture gate"); }
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
            Runnable result = pending.poll(60, TimeUnit.SECONDS);
            if (result == null) throw new AssertionError("bounded UI delivery");
            return result;
        }
        PurplePlaylistsWorker.Outcome next() throws Exception {
            int before = outcomes.size(); take().run();
            return outcomes.size() == before ? null : outcomes.get(outcomes.size() - 1);
        }
    }
    private static final class Fixture {
        final Path files, root, folder;
        final AtomicReference<Runnable> hook = new AtomicReference<>();
        final AtomicReference<Thread> openingThread = new AtomicReference<>();
        final AtomicInteger opens = new AtomicInteger();
        Fixture() throws Exception { this(null); }
        Fixture(Path existing) throws Exception {
            files = existing == null ? Files.createTempDirectory("playlist-worker-") : existing;
            root = files.resolve("purple"); folder = root.resolve("playlists");
            if (existing == null) {
            Files.setPosixFilePermissions(files, PosixFilePermissions.fromString("rwx------"));
            directory(root); directory(root.resolve("accounts"));
            write(root.resolve("local.toml"), bytes("root_file_account=\"0-101\"\nglobal_account=\"0-101\"\n"));
            write(root.resolve("accounts/root.toml"), bytes("root_file_account=\"0-101\"\n"));
            }
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
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
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
            worker.undo(created.snapshot, created.undo, 300, ui.target());
            PurplePlaylistsWorker.Outcome undone = ui.next();
            check(undone.ready() && undone.undo != null && undone.undo != created.undo && undone.undo.redo
                    && undone.undo.kind == PurplePlaylistsEdits.Kind.Create && undone.snapshot.view.playlists.isEmpty(),
                    "Undo removes the created playlist and issues a new Redo ticket");
            worker.undo(undone.snapshot, created.undo, 400, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.Changed, "consumed Undo ticket cannot repeat");
            worker.undo(undone.snapshot, undone.undo, 450, ui.target());
            PurplePlaylistsWorker.Outcome redone = ui.next();
            check(redone.ready() && redone.undo != null && !redone.undo.redo
                    && redone.snapshot.view.playlists.size() == 1 && redone.snapshot.view.playlists.get(0).id.equals(id),
                    "Redo restores the exact created playlist");
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
    private static final class DownloadFactory implements PurplePlaylistsDownloads.Factory {
        final PurplePlaylistsAccount.Captured captured = PurplePlaylistsAccount.capture(0);
        final AtomicInteger refreshes = new AtomicInteger(), cancels = new AtomicInteger();
        boolean closed;
        @Override public PurplePlaylistsDownloads.Port open(PurplePlaylistsStore.Current current,
                PurplePlaylistsDownloadTransfer.Dispatcher dispatcher) {
            return new PurplePlaylistsDownloadTransfer(captured, current, dispatcher);
        }
        @Override public void refresh(PurplePlaylistsDownloads.Item item, PurplePlaylistsStore.Current current,
                java.util.function.Consumer<PurplePlaylistsDownloadTransfer.Document> done) {
            refreshes.incrementAndGet();
            org.telegram.tgnet.TLRPC.TL_document doc = document(item.document.id, item.document.size);
            doc.file_reference = bytes("refreshed"); doc.dc_id = 8;
            done.accept(new PurplePlaylistsDownloadTransfer.Document(doc));
        }
        @Override public void cancel() { cancels.incrementAndGet(); }
        @Override public void close() { closed = true; }
    }
    private static org.telegram.tgnet.TLRPC.TL_document document(long id, long size) {
        org.telegram.tgnet.TLRPC.TL_document doc = new org.telegram.tgnet.TLRPC.TL_document();
        doc.id = id; doc.size = size; doc.dc_id = 4; doc.access_hash = 987; doc.file_reference = bytes("captured");
        return doc;
    }
    private static PurplePlaylistsDownloads.Item item(PurplePlaylistsWorker.Snapshot snapshot, long id, long size) {
        for (PurplePlaylistsView.Song song : snapshot.view.detail.songs) if (song.documentId.equals(Long.toString(id))) {
            org.telegram.tgnet.TLRPC.TL_message message = new org.telegram.tgnet.TLRPC.TL_message();
            message.id = song.source.messageId;
            message.peer_id = new org.telegram.tgnet.TLRPC.TL_peerUser(); message.peer_id.user_id = 101;
            message.media = new org.telegram.tgnet.TLRPC.TL_messageMediaDocument(); message.media.document = document(id, size);
            PurplePlaylistsDownloads.Item result = new PurplePlaylistsDownloads.Item(new PurplePlaylistsResolver.Result(song, song.source,
                    PurplePlaylistsResolver.Status.Ready, message, 0), "mp3");
            Arrays.fill(message.media.document.file_reference, (byte) 0);
            message.media.document.dc_id = 99; message.media.document.id = 0;
            return result;
        }
        throw new AssertionError("missing fixture song");
    }
    private static PurplePlaylistsWorker.Snapshot selected(PurplePlaylistsWorker worker, Ui ui, String playlist) throws Exception {
        worker.read(playlist, ui.target());
        PurplePlaylistsWorker.Outcome result = ui.next();
        check(result != null && result.ready() && result.snapshot.view.detail != null, "selected actor snapshot setup");
        return result.snapshot;
    }
    private static ConnectionsManager.Sent sent(ConnectionsManager connection) throws Exception {
        ConnectionsManager.Sent request = connection.sent.poll(60, TimeUnit.SECONDS);
        check(request != null, "bounded owned request arrives");
        return request;
    }
    private static void reply(ConnectionsManager.Sent request, byte[] content) throws Exception {
        org.telegram.tgnet.TLRPC.TL_upload_getFile get = (org.telegram.tgnet.TLRPC.TL_upload_getFile) request.request;
        int start = (int)get.offset, size = Math.min(get.limit, content.length - start);
        org.telegram.tgnet.TLRPC.TL_upload_file file = new org.telegram.tgnet.TLRPC.TL_upload_file();
        file.bytes = new org.telegram.tgnet.NativeByteBuffer(false);
        byte[] network = Arrays.copyOfRange(content, start, start + size);
        file.bytes.buffer = java.nio.ByteBuffer.wrap(network);
        request.done.run(file, null);
        Arrays.fill(network, (byte) 0);
    }
    private static void status(PurplePlaylistsDownloads.Control control, PurplePlaylistsDownloads.Status expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (control.status != expected && System.nanoTime() < deadline) Thread.sleep(2);
        check(control.status == expected, "download reaches " + expected + " rather than " + control.status);
    }
    private static Path path(Fixture f, String name) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(f.root)) {
            return paths.filter(p -> p.getFileName().toString().equals(name)).findFirst().orElse(null);
        }
    }
    private static byte[] payload(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; ++i) data[i] = (byte)(seed + i * 31);
        return data;
    }
    private static String setup(PurplePlaylistsWorker worker, Fixture f, Ui ui) throws Exception {
        PurplePlaylistsWorker.Snapshot empty = f.read(worker, ui).snapshot;
        worker.edit(empty, PurplePlaylistsEdits.Command.create("Downloads", 100), ui.target());
        PurplePlaylistsWorker.Outcome created = ui.next(); check(created.ready(), "playlist created for real transfer fixture");
        PurplePlaylistsEdits.Song[] songs = new PurplePlaylistsEdits.Song[9];
        for (int i = 0; i < songs.length; ++i)
            songs[i] = new PurplePlaylistsEdits.Song(Integer.toString(i + 1), "self", i + 11, "", "Song" + i, "Artist", 30);
        worker.edit(created.snapshot, PurplePlaylistsEdits.Command.add(created.edit.playlistId, songs, 200), ui.target());
        check(ui.next().ready(), "seven sources added through real shared-core actor edits");
        return created.edit.playlistId;
    }
    private static void downloadVertical() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            String playlist = setup(worker, f, ui);
            PurplePlaylistsWorker.Snapshot selection = selected(worker, ui, playlist);
            DownloadFactory factory = new DownloadFactory(); PurplePlaylistsDownloads downloads = new PurplePlaylistsDownloads(worker, factory);
            byte[][] data = {payload(262175, 7), payload(37, 9), payload(81, 12)};
            List<PurplePlaylistsDownloads.Item> items = new ArrayList<>();
            for (int i = 0; i < data.length; ++i) items.add(item(selection, i + 1, data[i].length));
            ConnectionsManager connection = ConnectionsManager.getInstance(0);
            connection.beforeSend = request -> {
                try {
                    org.telegram.tgnet.TLRPC.TL_upload_getFile get = (org.telegram.tgnet.TLRPC.TL_upload_getFile)request.request;
                    PurplePlaylistsDownloadsCore.Record[] values = PurplePlaylistsDownloadsCore.records(Files.readAllBytes(path(f, "local.json")), 101);
                    boolean checkpoint = false;
                    for (PurplePlaylistsDownloadsCore.Record record : values)
                        checkpoint |= !record.complete && record.document == get.location.id && !record.identity.isEmpty() && record.token.length() == 32;
                    check(checkpoint, "every owned request follows a durable exact identity checkpoint");
                } catch (Exception e) { throw new AssertionError("durable request admission proof", e); }
            };
            PurplePlaylistsDownloads.Control control = downloads.start(selection, playlist, items);
            ConnectionsManager.Sent first = sent(connection), second = sent(connection);
            check(connection.sent.isEmpty(), "two-active job admits no third owned request");
            for (ConnectionsManager.Sent request : new ConnectionsManager.Sent[] {first, second}) {
                org.telegram.tgnet.TLRPC.TL_upload_getFile get = (org.telegram.tgnet.TLRPC.TL_upload_getFile) request.request;
                check(get.offset == 0 && get.limit == 262144 && !get.cdn_supported && request.dc == 4, "captured DC bounded request declines CDN redirection");
                check(Arrays.equals(get.location.file_reference, bytes("captured")), "request owns copied document reference");
            }
            check(path(f, "local.json") != null, "durable inventory exists before first response");
            reply(second, data[1]);
            ConnectionsManager.Sent third = sent(connection);
            check(((org.telegram.tgnet.TLRPC.TL_upload_getFile)third.request).location.id == 3, "completed row admits next saved row");
            reply(third, data[2]); reply(first, data[0]);
            ConnectionsManager.Sent tail = sent(connection);
            check(((org.telegram.tgnet.TLRPC.TL_upload_getFile)tail.request).offset == 262144, "next owned request continues exact full chunk");
            reply(tail, data[0]); status(control, PurplePlaylistsDownloads.Status.Downloaded);
            check(control.progress.downloaded == 3 && control.progress.active == 0 && control.progress.failed == 0,
                    "shared policy accounts for every completed real-file transfer");
            check(control.completed.size() == 3, "only durably read-back completions exposed");
            for (int i = 0; i < data.length; ++i)
                check(Arrays.equals(Files.readAllBytes(path(f, (i + 1) + ".mp3")), data[i]), "response buffer copy remains intact after callback reuse");
            PurplePlaylistsWorker.Snapshot completed = selected(worker, ui, playlist);
            PurplePlaylistsDownloadsCore.Record[] records = PurplePlaylistsDownloadsCore.records(completed.state(), 101);
            check(records.length == 3 && Arrays.stream(records).allMatch(r -> r.complete && !r.sha256.isEmpty()), "typed inventory projection sees committed proofs");
            check(completed.view.detail.songs.get(0).documentId.equals("1") && completed.view.detail.songs.get(1).documentId.equals("2"), "completion order never rewrites saved playlist order");
            PurplePlaylistsDownloads.Control reused = downloads.start(completed, playlist, items);
            status(reused, PurplePlaylistsDownloads.Status.Downloaded);
            check(connection.sent.isEmpty(), "proven retained files need no new network request");
            check(reused.nativeReady == 3, "batch registers every durable completion after final checkpoint");
            retainedCases(f, worker, playlist, items.get(0), data[0]);


            PurplePlaylistsWorker.Snapshot partialSnapshot = selected(worker, ui, playlist);
            PurplePlaylistsDownloads.Item fourth = item(partialSnapshot, 4, 262149);
            byte[] fourthData = payload(262149, 22);
            CountDownLatch partialSaved = new CountDownLatch(1); AtomicReference<Throwable> problem = new AtomicReference<>();
            worker.postStore(partialSnapshot, (store, self) -> {
                try {
                    PurplePlaylistsDownloadJournal journal = new PurplePlaylistsDownloadJournal(store, self, () -> true);
                    PurplePlaylistsDownloadJournal.Entry entry = journal.prepare(playlist, fourth.song, fourth.source, 4, "mp3", fourthData.length);
                    journal.append(entry, 0, Arrays.copyOfRange(fourthData, 0, 13)); journal.close(entry);
                } catch (Throwable e) { problem.set(e); }
                finally { partialSaved.countDown(); }
            }, () -> { problem.set(new AssertionError("partial setup refused")); partialSaved.countDown(); });
            await(partialSaved); check(problem.get() == null, "partial checkpoint setup succeeds through actual journal " + problem.get());
            PurplePlaylistsWorker.Snapshot resumedSnapshot = selected(worker, ui, playlist);
            PurplePlaylistsDownloads.Control resumed = downloads.start(resumedSnapshot, playlist, java.util.Collections.singletonList(fourth));
            ConnectionsManager.Sent overlap = sent(connection);
            check(((org.telegram.tgnet.TLRPC.TL_upload_getFile)overlap.request).offset == 0, "unaligned partial resumes from aligned overlapping request");
            reply(overlap, fourthData); reply(sent(connection), fourthData);
            status(resumed, PurplePlaylistsDownloads.Status.Downloaded);
            check(Arrays.equals(Files.readAllBytes(path(f, "4.mp3")), fourthData), "verified overlap preserves prefix and appends suffix without truncation");

            PurplePlaylistsWorker.Snapshot refreshSnapshot = selected(worker, ui, playlist);
            PurplePlaylistsDownloads.Item fifth = item(refreshSnapshot, 5, 29);
            PurplePlaylistsDownloads.Control refreshed = downloads.start(refreshSnapshot, playlist, java.util.Collections.singletonList(fifth));
            ConnectionsManager.Sent expired = sent(connection);
            org.telegram.tgnet.TLRPC.TL_error error = new org.telegram.tgnet.TLRPC.TL_error(); error.text = "FILE_REFERENCE_EXPIRED";
            expired.done.run(null, error);
            ConnectionsManager.Sent fresh = sent(connection);
            check(factory.refreshes.get() == 1 && Arrays.equals(((org.telegram.tgnet.TLRPC.TL_upload_getFile)fresh.request).location.file_reference, bytes("refreshed")), "reference retry uses one same-source refreshed document");
            check(fresh.dc == 8, "reference refresh updates actual request DC");
            reply(fresh, payload(29, 34)); status(refreshed, PurplePlaylistsDownloads.Status.Downloaded);
            check(refreshed.completed.get(0).document.dc == 8, "completion carries refreshed document DC");
            check(refreshed.nativeReady == 1, "refreshed completion is registered with native path lookup");
            org.telegram.tgnet.TLRPC.TL_document refreshedDoc = document(5, 29); refreshedDoc.dc_id = 8;
            check(PurplePlaylistsRetained.path(0, refreshedDoc, false) != null, "retained mapping binds refreshed DC");
            check(PurplePlaylistsRetained.path(0, document(5, 29), false) == null, "initial stale DC never registered after refresh");

            PurplePlaylistsWorker.Snapshot waitSnapshot = selected(worker, ui, playlist);
            PurplePlaylistsDownloads.Item sixth = item(waitSnapshot, 6, 19);
            PurplePlaylistsDownloads.Control waiting = downloads.start(waitSnapshot, playlist, java.util.Collections.singletonList(sixth));
            ConnectionsManager.Sent flood = sent(connection); error = new org.telegram.tgnet.TLRPC.TL_error(); error.text = "FLOOD_WAIT_17";
            flood.done.run(null, error); status(waiting, PurplePlaylistsDownloads.Status.Wait);
            check(waiting.waitSeconds == 17 && path(f, "6.mp3") == null && connection.sent.isEmpty(), "server wait is explicit and never auto-retried");
            PurplePlaylistsDownloads.Control retried = downloads.start(selected(worker, ui, playlist), playlist, java.util.Collections.singletonList(sixth));
            reply(sent(connection), payload(19, 43)); status(retried, PurplePlaylistsDownloads.Status.Downloaded);
            check(!factory.closed && factory.cancels.get() > 0, "explicit Retry keeps factory usable after canceled wait ports");

            PurplePlaylistsDownloads.Item seventh = item(selected(worker, ui, playlist), 7, 31);
            PurplePlaylistsDownloads.Control canceled = downloads.start(selected(worker, ui, playlist), playlist, java.util.Collections.singletonList(seventh));
            ConnectionsManager.Sent cancel = sent(connection); canceled.cancel(); status(canceled, PurplePlaylistsDownloads.Status.Canceled);
            check(connection.canceled.contains(cancel.token), "cancel targets this adapter's exact request token");
            reply(cancel, payload(31, 54));
            CountDownLatch fenced = new CountDownLatch(1); worker.postStore(null, (store, self) -> fenced.countDown(), fenced::countDown); await(fenced);
            check(path(f, "7.mp3") == null && path(f, "1.mp3") != null, "late canceled callback cannot publish and completed files remain");

            PurplePlaylistsWorker.Snapshot mismatchSnapshot = selected(worker, ui, playlist);
            PurplePlaylistsDownloads.Item eighth = item(mismatchSnapshot, 8, 91);
            byte[] eighthData = payload(91, 61);
            CountDownLatch mismatchSaved = new CountDownLatch(1);
            worker.postStore(mismatchSnapshot, (store, self) -> {
                try {
                    PurplePlaylistsDownloadJournal journal = new PurplePlaylistsDownloadJournal(store, self, () -> true);
                    PurplePlaylistsDownloadJournal.Entry entry = journal.prepare(playlist, eighth.song, eighth.source, 8, "mp3", eighthData.length);
                    journal.append(entry, 0, Arrays.copyOfRange(eighthData, 0, 13)); journal.close(entry);
                } finally { mismatchSaved.countDown(); }
            }, mismatchSaved::countDown); await(mismatchSaved);
            PurplePlaylistsDownloads.Control mismatch = downloads.start(selected(worker, ui, playlist), playlist, java.util.Collections.singletonList(eighth));
            byte[] wrong = eighthData.clone(); wrong[0] ^= 1; reply(sent(connection), wrong);
            status(mismatch, PurplePlaylistsDownloads.Status.Partial);
            check(mismatch.progress.failed == 1 && path(f, "8.mp3") == null, "mismatched remote prefix never publishes mixed content");
            PurplePlaylistsDownloads.Control repaired = downloads.start(selected(worker, ui, playlist), playlist, java.util.Collections.singletonList(eighth));
            reply(sent(connection), eighthData); status(repaired, PurplePlaylistsDownloads.Status.Downloaded);
            check(Arrays.equals(Files.readAllBytes(path(f, "8.mp3")), eighthData), "explicit retry preserves original checkpointed prefix");

            PurplePlaylistsWorker.Snapshot retiredSnapshot = selected(worker, ui, playlist);
            PurplePlaylistsDownloads.Control retired = downloads.start(retiredSnapshot, playlist,
                    java.util.Collections.singletonList(item(retiredSnapshot, 9, 23)));
            ConnectionsManager.Sent ownedAtRetirement = sent(connection);
            check(register(worker, playlist, items.get(0), document(1, data[0].length), new AtomicBoolean(true)), "completed path revalidates immediately before worker retirement");
            f.stop(worker);
            check(PurplePlaylistsRetained.path(0, document(1, data[0].length), false) == null, "retired account worker immediately revokes retained path");
            check(factory.closed && connection.canceled.contains(ownedAtRetirement.token), "worker retires owned requests before its physical session closes");
            reply(ownedAtRetirement, payload(23, 73));
            check(path(f, "9.mp3") == null && retired.status == PurplePlaylistsDownloads.Status.Canceled,
                    "retired worker rejects late request bytes and reports cancellation");
            PurplePlaylistsAccount.Captured captured = PurplePlaylistsAccount.capture(0);
            PurplePlaylistsWorker reopened = PurplePlaylistsWorkers.acquire(0);
            check(reopened != null && reopened.matches(captured), "account registry acquires replacement worker after prior native close");
            try {
                PurplePlaylistsView.Song keptSong = items.get(0).song;
                org.telegram.tgnet.TLRPC.TL_message keptMessage = new org.telegram.tgnet.TLRPC.TL_message();
                keptMessage.media = new org.telegram.tgnet.TLRPC.TL_messageMediaDocument();
                keptMessage.media.document = document(1, data[0].length);
                PurplePlaylistsResolver.Result keptResult = new PurplePlaylistsResolver.Result(keptSong, keptSong.source,
                        PurplePlaylistsResolver.Status.Ready, keptMessage, 0);
                CountDownLatch prepared = new CountDownLatch(1); AtomicInteger deliveries = new AtomicInteger();
                AutoCloseable receipt = PurplePlaylistsRetained.prepare(captured, playlist, keptResult, () -> true, value -> {
                    check(value == keptResult, "retained preparation preserves exact resolved source result");
                    deliveries.incrementAndGet(); prepared.countDown();
                });
                await(prepared); receipt.close();
                check(deliveries.get() == 1 && PurplePlaylistsRetained.path(0, keptMessage.media.document, false) != null,
                        "real prepare restores durable completed path after account worker reopen");
                Path local = path(f, "local.json");
                Files.setLastModifiedTime(local, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 20000));
                check(PurplePlaylistsRetained.path(0, keptMessage.media.document, false) == null, "old mapping drops before canceled preparation");
                Gate held = new Gate();
                reopened.postStore(null, (store, self) -> held.run(), () -> {}); await(held.entered);
                AtomicInteger stale = new AtomicInteger();
                AutoCloseable canceledPrepare = PurplePlaylistsRetained.prepare(captured, playlist, keptResult, () -> true, value -> stale.incrementAndGet());
                canceledPrepare.close(); held.release.countDown();
                CountDownLatch drained = new CountDownLatch(1); reopened.postStore(null, (store, self) -> drained.countDown(), drained::countDown); await(drained);
                check(stale.get() == 0 && PurplePlaylistsRetained.path(0, keptMessage.media.document, false) == null,
                        "canceled queued prepare neither registers nor delivers a stale source");
                check(connection.sent.isEmpty(), "retained reopen and canceled prepare issue no transport request");
            } finally { f.stop(reopened); }

        } finally { f.stop(worker); }
    }

    private static boolean register(PurplePlaylistsWorker worker, String playlist, PurplePlaylistsDownloads.Item item,
            org.telegram.tgnet.TLRPC.TL_document document, AtomicBoolean scope) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicBoolean result = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean admitted = worker.postStore(null, (store, self) -> {
            try { result.set(PurplePlaylistsRetained.publish(store, self, worker, playlist,
                    item.song, item.source, new PurplePlaylistsDownloadTransfer.Document(document), scope::get)); }
            catch (Throwable e) { failure.set(e); }
            finally { done.countDown(); }
        }, done::countDown);
        check(admitted, "retained inspection is admitted to owning actor"); await(done);
        return failure.get() == null && result.get();
    }
    private static void retainedCases(Fixture f, PurplePlaylistsWorker worker, String playlist,
            PurplePlaylistsDownloads.Item item, byte[] data) throws Exception {
        org.telegram.tgnet.TLRPC.TL_document document = document(1, data.length);
        Path file = path(f, "1.mp3");
        check(file != null && PurplePlaylistsRetained.path(0, document, false).equals(file.toFile()), "native lookup returns exact verified completed file");
        RetainedNativeHooks hooks = new RetainedNativeHooks(); hooks.currentAccount = 0; hooks.cache = f.files.toFile();
        java.io.File fallback = f.files.resolve("ordinary-fallback").toFile();
        check(hooks.lookup(document, false, fallback).equals(file.toFile()), "actual FileLoader lookup hook selects retained file");
        check(hooks.lookup(document, true, fallback).equals(fallback), "actual FileLoader force-cache hook preserves fallback");
        document.localPath = f.files.resolve("explicit-source").toString();
        check(PurplePlaylistsRetained.path(0, document, false) == null, "explicit local source bypasses retained mapping");
        check(hooks.lookup(document, false, fallback).equals(new java.io.File(document.localPath)),
                "actual stock explicit localPath takes precedence over retained mapping");
        check(hooks.lookup(document, true, fallback).equals(fallback), "force-cache still ignores explicit localPath");
        document.localPath = "";
        check(hooks.lookup(document, false, fallback).equals(file.toFile()), "empty localPath retains native completed lookup");
        document.localPath = null;

        Path ordinary = f.files.resolve("ordinary-cache-file"); write(ordinary, bytes("cache"));
        ArrayList<java.io.File> deletion = new ArrayList<>(); deletion.add(file.toFile()); deletion.add(ordinary.toFile());
        hooks.deleteFiles(deletion, 0);
        check(Files.exists(file), "actual stock deleteFiles hook preserves kept file");
        check(!Files.exists(ordinary), "actual stock deleteFiles still deletes ordinary cache file");

        check(PurplePlaylistsRetained.path(1, document, false) == null, "another account has no retained mapping");
        check(PurplePlaylistsRetained.path(0, document, true) == null, "force-cache lookup does not retarget cache operations");
        check(PurplePlaylistsRetained.path(0, document(1, data.length + 1), false) == null, "document size change misses retained mapping");
        document.key = bytes("secret");
        check(PurplePlaylistsRetained.path(0, document, false) == null, "encrypted document cannot consume regular retained file"); document.key = null;
        check(PurplePlaylistsRetained.protects(file.toFile()), "stock deletion guard protects retained path");
        check(PurplePlaylistsRetained.protects(file.getParent().resolve(".incoming/test.part").toFile()), "stock deletion guard also protects owned partials");
        check(!PurplePlaylistsRetained.protects(f.files.resolve("cache/1.mp3").toFile()), "ordinary cache path is not protected");
        check(!PurplePlaylistsRetained.protects(f.root.resolve("other/files/1.mp3").toFile()), "unrelated Purple path is not protected");
        check(!PurplePlaylistsRetained.protects(f.root.resolve("playlists/files/../../outside").toFile()), "path traversal cannot extend protected subtree");
        AtomicBoolean scope = new AtomicBoolean(true);
        check(register(worker, playlist, item, document, scope), "actor reopens durable proof without request");
        scope.set(false);
        check(PurplePlaylistsRetained.path(0, document, false) != null, "completed mapping survives resolver receipt release");
        check(!register(worker, "missing", item, document, new AtomicBoolean(true)), "absent playlist cannot register retained file");
        Path local = path(f, "local.json"); byte[] state = Files.readAllBytes(local);
        Files.setLastModifiedTime(local, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
        check(PurplePlaylistsRetained.path(0, document, false) == null, "inventory revision invalidates mapping");
        check(register(worker, playlist, item, document, new AtomicBoolean(true)), "unchanged durable inventory can be revalidated");
        byte[] wrong = data.clone(); wrong[0] ^= 1; Files.write(file, wrong);
        check(PurplePlaylistsRetained.path(0, document, false) == null, "same-size file modification invalidates mapping");
        check(!register(worker, playlist, item, document, new AtomicBoolean(true)), "full native digest refuses corrupted retained bytes");
        Files.write(file, data);
        check(register(worker, playlist, item, document, new AtomicBoolean(true)), "repaired exact content revalidates against durable identity");
        Path saved = file.resolveSibling("saved-media"); Files.move(file, saved); Files.createSymbolicLink(file, saved.getFileName());
        check(PurplePlaylistsRetained.path(0, document, false) == null, "symlink replacement invalidates mapping");
        check(!register(worker, playlist, item, document, new AtomicBoolean(true)), "symlink cannot gain native retained authority");
        Files.delete(file); Files.move(saved, file);
        check(register(worker, playlist, item, document, new AtomicBoolean(true)), "original inode can be revalidated after refused replacement");
        Files.createLink(saved, file);
        check(PurplePlaylistsRetained.path(0, document, false) == null, "hardlink addition invalidates mapping"); Files.delete(saved);
        check(register(worker, playlist, item, document, new AtomicBoolean(true)), "single-link original revalidates");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        check(PurplePlaylistsRetained.path(0, document, false) == null, "permission weakening invalidates mapping");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        check(register(worker, playlist, item, document, new AtomicBoolean(true)), "private exact file can be registered again");
        check(Arrays.equals(state, Files.readAllBytes(local)), "path registration never rewrites local inventory");
    }

    private static final class MainTasks implements java.util.concurrent.Executor {
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        @Override public void execute(Runnable action) { tasks.add(action); }
        void until(java.util.function.BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!done.getAsBoolean() && System.nanoTime() < deadline) {
                Runnable next = tasks.poll(2, TimeUnit.MILLISECONDS); if (next != null) next.run();
            }
            check(done.getAsBoolean(), "bounded account controls UI delivery");
        }
        void drain() { for (Runnable next; (next = tasks.poll()) != null;) next.run(); }
    }
    private static final class ControlEnvironment implements PurplePlaylistsDownloadControls.Environment {
        final MainTasks main;
        final AtomicInteger sources = new AtomicInteger(), closedSources = new AtomicInteger();
        final DownloadFactory factory = new DownloadFactory();
        int mode;
        ControlEnvironment(MainTasks main) { this.main = main; }
        @Override public boolean unrestricted() { return false; }
        @Override public PurplePlaylistsDownloads.Factory transfers(PurplePlaylistsAccount.Captured account) { return factory; }
        @Override public PurplePlaylistsResolver.Port source(PurplePlaylistsAccount.Captured account, java.util.function.BooleanSupplier scope) {
            return new PurplePlaylistsResolver.Port() {
                boolean closed;
                @Override public boolean current() { return !closed && scope.getAsBoolean() && account.current(); }
                @Override public void defer(Runnable action) { main.execute(action); }
                @Override public void close() { if (!closed) { closed = true; closedSources.incrementAndGet(); } }
                @Override public void read(PurplePlaylistsView.Source source, java.util.function.Consumer<PurplePlaylistsResolver.Reply> done) {
                    sources.incrementAndGet();
                    if (mode != 0) {
                        done.accept(PurplePlaylistsResolver.Reply.failed(mode == 1 ? PurplePlaylistsResolver.Status.Wait
                                : PurplePlaylistsResolver.Status.Restricted, mode == 1 ? 17 : 0)); return;
                    }
                    org.telegram.tgnet.TLRPC.TL_message message = new org.telegram.tgnet.TLRPC.TL_message();
                    message.id = source.messageId; message.peer_id = new org.telegram.tgnet.TLRPC.TL_peerUser(); message.peer_id.user_id = 101;
                    message.media = new org.telegram.tgnet.TLRPC.TL_messageMediaDocument();
                    message.media.document = document(source.messageId - 10, 17); message.media.document.mime_type = "audio/mpeg";
                    message.media.document.attributes.add(new org.telegram.tgnet.TLRPC.TL_documentAttributeAudio());
                    done.accept(new PurplePlaylistsResolver.Reply(message, null, 0, false, false));
                }
            };
        }
    }
    private static void accountControls() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            String playlist = setup(worker, f, ui);
            PurplePlaylistsAccount.Captured account = PurplePlaylistsAccount.capture(0);
            check(PurplePlaylistsDownloadControls.acquire(account, worker) == PurplePlaylistsDownloadControls.acquire(account, worker),
                    "different screens acquire the same account-owned controller");
            MainTasks main = new MainTasks(); ControlEnvironment environment = new ControlEnvironment(main);
            PurplePlaylistsDownloadControls controls = new PurplePlaylistsDownloadControls(account, worker, environment, main);
            ArrayList<PurplePlaylistsDownloadControls.State> observed = new ArrayList<>();
            PurplePlaylistsDownloadControls.Listener listener = observed::add;
            PurplePlaylistsDownloadControls.Observation view = controls.observe(listener); main.drain();
            check(observed.size() == 1 && observed.get(0).status == PurplePlaylistsDownloadControls.Status.Idle,
                    "new stock observer receives immutable idle state");
            PurplePlaylistsWorker.Snapshot firstSelection = selected(worker, ui, playlist);
            check(controls.start(firstSelection, null), "captured download controls admit selected playlist");
            PurplePlaylistsDownloadControls.State resolving = controls.snapshot();
            check(!controls.start(firstSelection, null), "a second screen cannot duplicate an active account batch");
            ConnectionsManager connection = ConnectionsManager.getInstance(0);
            main.until(() -> connection.sent.size() == 2 && controls.snapshot().status == PurplePlaylistsDownloadControls.Status.Running);
            check(environment.sources.get() == 9 && connection.sent.size() == 2, "whole resolver admits only two owned native transfers");
            check(!controls.cancel(resolving), "stale UI Cancel cannot act on a later state");
            int before = observed.size(); view.close();
            CountDownLatch observerClosed = new CountDownLatch(1);
            check(worker.postStore(null, (store, self) -> observerClosed.countDown(), observerClosed::countDown), "observer close barrier admitted");
            await(observerClosed); main.drain();
            check(connection.canceled.isEmpty() && !environment.factory.closed, "leaving a screen retires only its observer");
            view = controls.observe(listener); main.drain();
            check(observed.size() == before + 1 && observed.get(before) == controls.snapshot(), "reopened screen observes existing job without restart");
            PurplePlaylistsDownloadControls.State running = controls.snapshot();
            check(controls.cancel(running) && controls.snapshot().status == PurplePlaylistsDownloadControls.Status.Canceling,
                    "Cancel reports pending cleanup until owning actor responds");
            main.until(() -> controls.snapshot().status == PurplePlaylistsDownloadControls.Status.Canceled);
            check(connection.canceled.size() == 2 && controls.snapshot().canRetry, "real canceled job closes both exact owned requests and offers Retry");
            while (!connection.sent.isEmpty()) reply(connection.sent.remove(), payload(17, 31));
            PurplePlaylistsDownloadControls.State canceled = controls.snapshot();
            check(controls.start(selected(worker, ui, playlist), canceled), "explicit exact-state Retry resolves current source snapshot again");
            main.until(() -> connection.sent.size() == 2 && controls.snapshot().status == PurplePlaylistsDownloadControls.Status.Running);
            check(!controls.cancel(canceled), "stale canceled-state action cannot stop retried job");
            main.drain(); int completed = 0;
            while (completed < 9) {
                ConnectionsManager.Sent request = connection.sent.poll(60, TimeUnit.SECONDS);
                check(request != null, "retried account job keeps bounded transfer pipeline moving");
                reply(request, payload(17, ++completed));
            }
            for (int i = 0; i < 2; ++i) {
                CountDownLatch completion = new CountDownLatch(1);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!worker.postStore(null, (store, self) -> completion.countDown(), completion::countDown)) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("bounded final controls barrier");
                    Thread.sleep(2);
                }
                await(completion);
            }
            check(main.tasks.size() <= 1, "worker progress coalesces to one pending UI delivery while main is busy");
            main.until(() -> controls.snapshot().status == PurplePlaylistsDownloadControls.Status.Downloaded);
            PurplePlaylistsDownloadControls.State done = controls.snapshot();
            check(done.downloaded == 9 && done.total == 9 && done.nativeReady == 9 && !done.canCancel && !done.canRetry,
                    "UI completion distinguishes durable files and verified native registrations");
            check(resolving.status == PurplePlaylistsDownloadControls.Status.Resolving && resolving.downloaded == 0,
                    "published old UI state is not mutated by job completion");
            check(!controls.start(selected(worker, ui, playlist), canceled), "old Retry receipt cannot start after later completion");
            view.close(); before = observed.size(); main.drain(); check(observed.size() == before, "closed observation rejects queued callbacks");
            f.stop(worker); check(controls.snapshot().status == PurplePlaylistsDownloadControls.Status.Changed,
                    "retired account worker fences controls and late completion");
        } finally { f.stop(worker); }
        for (int mode : new int[] {1, 2}) {
            Fixture isolated = new Fixture(); Ui isolatedUi = new Ui(); PurplePlaylistsWorker isolatedWorker = isolated.worker(0);
            try {
                String playlist = setup(isolatedWorker, isolated, isolatedUi);
                MainTasks main = new MainTasks(); ControlEnvironment environment = new ControlEnvironment(main); environment.mode = mode;
                PurplePlaylistsDownloadControls controls = new PurplePlaylistsDownloadControls(PurplePlaylistsAccount.capture(0), isolatedWorker, environment, main);
                controls.start(selected(isolatedWorker, isolatedUi, playlist), null);
                main.until(() -> controls.snapshot().status == (mode == 1 ? PurplePlaylistsDownloadControls.Status.Wait : PurplePlaylistsDownloadControls.Status.Partial));
                check(controls.snapshot().canRetry && ConnectionsManager.getInstance(0).sent.isEmpty(), "source refusal never starts download or automatic Retry");
                check(mode != 1 || controls.snapshot().waitSeconds == 17, "server wait is exposed exactly to stock UI");
                check(mode != 2 || controls.snapshot().unavailable == 9, "restricted sources are reported instead of optimistic success");
            } finally { isolated.stop(isolatedWorker); }
        }
    }

    private static void cancellationQueueFull() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        Gate held = new Gate(), closing = new Gate();
        try {
            String playlist = setup(worker, f, ui);
            PurplePlaylistsWorker.Snapshot snapshot = selected(worker, ui, playlist);
            DownloadFactory actual = new DownloadFactory();
            PurplePlaylistsDownloads.Factory gated = new PurplePlaylistsDownloads.Factory() {
                @Override public PurplePlaylistsDownloads.Port open(PurplePlaylistsStore.Current current,
                        PurplePlaylistsDownloadTransfer.Dispatcher dispatcher) {
                    PurplePlaylistsDownloads.Port port = actual.open(current, dispatcher);
                    return new PurplePlaylistsDownloads.Port() {
                        @Override public void read(PurplePlaylistsDownloadTransfer.Document document, long offset,
                                PurplePlaylistsDownloadTransfer.Completion done) { port.read(document, offset, done); }
                        @Override public void close() { closing.run(); port.close(); }
                    };
                }
                @Override public void refresh(PurplePlaylistsDownloads.Item item, PurplePlaylistsStore.Current current,
                        java.util.function.Consumer<PurplePlaylistsDownloadTransfer.Document> done) { actual.refresh(item, current, done); }
                @Override public void cancel() { actual.cancel(); }
                @Override public void close() { actual.close(); }
            };
            PurplePlaylistsDownloads downloads = new PurplePlaylistsDownloads(worker, gated);
            java.util.concurrent.CopyOnWriteArrayList<PurplePlaylistsDownloads.Snapshot> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            PurplePlaylistsDownloads.Control control = downloads.start(snapshot, playlist,
                    java.util.Collections.singletonList(item(snapshot, 1, 41)), events::add);
            ConnectionsManager connection = ConnectionsManager.getInstance(0);
            ConnectionsManager.Sent pending = sent(connection);
            worker.postStore(null, (store, self) -> held.run(), () -> {}); await(held.entered);
            for (int i = 0; i < 4; ++i) check(worker.postStore(null, (store, self) -> {}, () -> {}), "bounded store slot filled before Cancel");
            check(!worker.postStore(null, (store, self) -> {}, () -> {}), "normal store queue refuses a fifth slot");
            control.cancel(); control.cancel(); held.release.countDown();
            check(closing.entered.await(60, TimeUnit.SECONDS), "Cancel reaches owned cleanup despite a full store queue");
            check(control.status != PurplePlaylistsDownloads.Status.Canceled,
                    "Canceled status is withheld until owned request close completes");
            check(!connection.canceled.contains(pending.token), "close gate observes the real request still owned");
            closing.release.countDown();
            status(control, PurplePlaylistsDownloads.Status.Canceled);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!connection.canceled.contains(pending.token) && System.nanoTime() < deadline) Thread.sleep(2);
            check(connection.canceled.contains(pending.token), "Cancel closes exact request despite a full store queue");
            check(path(f, "1.mp3") == null, "queue-saturated Cancel leaves no completed publication");
            check(!events.isEmpty() && events.get(0).status == PurplePlaylistsDownloads.Status.Running,
                    "published progress snapshots remain immutable after cancellation");
            check(control.snapshot.status == PurplePlaylistsDownloads.Status.Canceled && control.snapshot.progress.canceled,
                    "final immutable snapshot reports actual canceled core job");
            reply(pending, payload(41, 23));
            CountDownLatch drained = new CountDownLatch(1);
            while (!worker.postStore(null, (store, self) -> drained.countDown(), drained::countDown)) Thread.sleep(2);
            await(drained);
            check(path(f, "1.mp3") == null, "late reply cannot publish after queue-saturated cancellation");
        } finally { held.release.countDown(); closing.release.countDown(); f.stop(worker); }
    }

    private static void authorityCases() throws Exception {
        for (String relative : new String[] { "local.toml", "accounts/root.toml", "accounts" }) {
            for (int change = 0; change < (relative.equals("accounts") ? 1 : 5); ++change) {
                Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
                try {
                    String playlist = setup(worker, f, ui);
                    PurplePlaylistsWorker.Snapshot snapshot = selected(worker, ui, playlist);
                    PurplePlaylistsDownloads downloads = new PurplePlaylistsDownloads(worker, new DownloadFactory());
                    PurplePlaylistsDownloads.Control control = downloads.start(snapshot, playlist,
                            java.util.Collections.singletonList(item(snapshot, 1, 17)));
                    reply(sent(ConnectionsManager.getInstance(0)), payload(17, 11));
                    status(control, PurplePlaylistsDownloads.Status.Downloaded);
                    org.telegram.tgnet.TLRPC.TL_document document = document(1, 17);
                    check(PurplePlaylistsRetained.path(0, document, false) != null, "authority fixture begins with a native verified mapping");
                    Path authority = f.root.resolve(relative), saved = authority.resolveSibling("saved-authority");
                    long revision = PurpleAccountOwner.revision;
                    if (relative.equals("accounts")) {
                        Files.move(authority, saved); Files.createSymbolicLink(authority, saved.getFileName());
                    } else {
                        byte[] original = Files.readAllBytes(authority);
                        if (change == 0) {
                            byte[] changed = new String(original, StandardCharsets.UTF_8).replace("0-101", "0-202").getBytes(StandardCharsets.UTF_8);
                            check(original.length == changed.length && !Arrays.equals(original, changed), "authority fixture changes content without changing size");
                            Files.write(authority, changed);
                        } else if (change == 1) {
                            Files.move(authority, saved); write(authority, original);
                        } else if (change == 2) {
                            Files.move(authority, saved); Files.createSymbolicLink(authority, saved.getFileName());
                        } else if (change == 3) Files.delete(authority);
                        else Files.setPosixFilePermissions(authority, PosixFilePermissions.fromString("rw-r--r--"));
                    }
                    check(PurpleAccountOwner.revision == revision, "disk authority changes before cached owner reload");
                    check(PurplePlaylistsRetained.path(0, document, false) == null,
                            "changed " + relative + " immediately revokes cached retained path");
                    check(ConnectionsManager.getInstance(0).sent.isEmpty(), "revoked authority lookup performs no transport request");
                } finally { f.stop(worker); }
            }
        }
    }

    private static PurplePlaylistsDownloadsCore.Record seedRemovalIntent(PurplePlaylistsWorker worker, Ui ui,
            String playlist, int mode) throws Exception {
        PurplePlaylistsWorker.Snapshot selection=selected(worker,ui,playlist);
        PurplePlaylistsDownloads.Item item=item(selection,1,91);
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        AtomicReference<PurplePlaylistsDownloadsCore.Record> result=new AtomicReference<>();
        worker.postStore(selection,(store,self)->{
            try {
                if(mode<2) {
                    PurplePlaylistsStore.Read read=store.read();
                    String token="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
                    PurplePlaylistsDownloadsCore.Result next=PurplePlaylistsDownloadsCore.reserve(read.state(),self,playlist,token,1,"mp3",91);
                    check(next.ready() && store.write(read,read.library(),next.local(),PurplePlaylistsStore.Kind.LocalEdit).ready(),
                            "reserved inactive intent saved through real store");
                    if(mode==1) {store.media().beginMedia(token,91,"",()->true);store.media().closeMedia(token);}
                } else {
                    PurplePlaylistsDownloadJournal journal=new PurplePlaylistsDownloadJournal(store,self,()->true);
                    PurplePlaylistsDownloadJournal.Entry entry=journal.prepare(playlist,item.song,item.source,1,"mp3",91);
                    journal.append(entry,0,payload(mode==3?91:13,44));
                    if(mode==3) {
                        PurplePlaylistsPhysical.MediaFile proof=store.media().sealMedia(entry.token,entry.proof().identity,()->true);
                        PurplePlaylistsStore.Read read=store.read();
                        PurplePlaylistsDownloadsCore.Result next=PurplePlaylistsDownloadsCore.checkpoint(read.state(),self,playlist,
                                entry.token,1,"mp3",91,proof.identity,proof.sha256);
                        check(next.ready() && store.write(read,read.library(),next.local(),PurplePlaylistsStore.Kind.LocalEdit).ready(),
                                "sealed checkpoint saved before simulated interrupted completion");
                        store.media().publishMedia(entry.token,1,"mp3",proof,()->true);
                    }
                    journal.close(entry);
                }
                result.set(PurplePlaylistsDownloadsCore.records(store.read().state(),self)[0]);
            } catch(Throwable e) {error.set(e);} finally {done.countDown();}
        },()->{error.set(new AssertionError("intent seed refused"));done.countDown();});
        await(done);check(error.get()==null,"actual inactive intent seed "+error.get());return result.get();
    }
    private static void inactiveIntentCases() throws Exception {
        for(int mode=0;mode!=4;++mode) {
            Fixture f=new Fixture();Ui ui=new Ui();PurplePlaylistsWorker worker=f.worker(0);
            try {
                String playlist=setup(worker,f,ui);
                PurplePlaylistsDownloadsCore.Record intent=seedRemovalIntent(worker,ui,playlist,mode);
                Path part=f.folder.resolve("files/.incoming/"+intent.token+".part");
                worker.removeDownloads(selected(worker,ui,playlist),true,ui.target());
                PurplePlaylistsWorker.Outcome protectedResult=ui.next();
                check(protectedResult.ready() && protectedResult.cleanup.removed==0
                        && PurplePlaylistsDownloadsCore.records(protectedResult.snapshot.state(),101).length==1,
                        "prune preserves inactive transfer referenced by live playlist");
                PurplePlaylistsWorker.Snapshot before=selected(worker,ui,playlist);
                byte[] library=before.library();
                worker.removeDownloads(before,false,ui.target());PurplePlaylistsWorker.Outcome removed=ui.next();
                if(mode==1) {
                    check(!removed.ready() && !removed.cleanup.filesMayHaveChanged && Files.exists(part),
                            "identityless existing part never adopted or deleted by removal");
                    check(PurplePlaylistsDownloadsCore.records(removed.snapshot.state(),101).length==1,
                            "unproven part keeps its reservation for explicit review");
                    PurplePlaylistsWorker.Snapshot refreshed=selected(worker,ui,playlist);
                    check(refreshed.cleanup != null && refreshed.cleanup.status==PurplePlaylistsRemoval.Status.Pending && !refreshed.cleanup.prune,
                            "ordinary refresh preserves durable explicit cleanup indication");
                    worker.read(ui.target());check(ui.next().cleanup==null,"root view never retargets selected-playlist cleanup into prune");
                    f.stop(worker);worker=f.worker(0);
                    refreshed=selected(worker,ui,playlist);
                    check(refreshed.cleanup!=null && !refreshed.cleanup.prune,"reopened native store restores exact pending selected operation");
                    Files.delete(part);
                    worker.removeDownloads(refreshed,false,ui.target());removed=ui.next();
                }
                check(removed.ready() && removed.cleanup.removed==1 && Arrays.equals(library,removed.snapshot.library()),
                        "inactive absent partial or interrupted publication removed without library edit");
                check(PurplePlaylistsDownloadsCore.records(removed.snapshot.state(),101).length==0
                        && !Files.exists(part) && !Files.exists(f.folder.resolve("files/1.mp3")),
                        "durable native cleanup and inventory readback agree");
                check(selected(worker,ui,playlist).cleanup==null,"only confirmed cleanup clears durable pending marker");
                worker.removeDownloads(before,false,ui.target());check(ui.next().status==PurplePlaylistsWorker.Status.Changed,
                        "consumed intent removal capability cannot replay");
            } finally {f.stop(worker);}
        }
        {
            Fixture f=new Fixture();Ui ui=new Ui();PurplePlaylistsWorker worker=f.worker(0);
            try {
                String playlist=setup(worker,f,ui);
                worker.edit(selected(worker,ui,playlist),PurplePlaylistsEdits.Command.copyCurrent(playlist,300),ui.target());
                String other=ui.next().edit.playlistId;
                worker.edit(selected(worker,ui,other),PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete,other,400),ui.target());ui.next();
                PurplePlaylistsDownloadsCore.Record intent=seedRemovalIntent(worker,ui,playlist,3);
                worker.removeDownloads(selected(worker,ui,playlist),false,ui.target());PurplePlaylistsWorker.Outcome result=ui.next();
                check(result.ready() && result.cleanup.removed==0 && result.cleanup.protectedFiles==1,
                        "interrupted publication is reconciled and protected by Recently deleted reference");
                PurplePlaylistsDownloadsCore.Record record=PurplePlaylistsDownloadsCore.records(result.snapshot.state(),101)[0];
                check(record.complete && record.identity.equals(intent.identity) && Files.exists(f.folder.resolve("files/1.mp3")),
                        "only exact proven published file is promoted to protected inventory");
            } finally {f.stop(worker);}
        }
        {
            Fixture f=new Fixture();Ui ui=new Ui();PurplePlaylistsWorker worker=f.worker(0);
            try {
                String playlist=setup(worker,f,ui);
                PurplePlaylistsDownloadsCore.Record intent=seedRemovalIntent(worker,ui,playlist,2);
                Path part=f.folder.resolve("files/.incoming/"+intent.token+".part");
                Files.move(part,part.resolveSibling(intent.token+".old"));write(part,bytes("foreign"));
                worker.removeDownloads(selected(worker,ui,playlist),false,ui.target());PurplePlaylistsWorker.Outcome result=ui.next();
                check(!result.ready() && !result.cleanup.filesMayHaveChanged && Arrays.equals(Files.readAllBytes(part),bytes("foreign")),
                        "checkpoint identity never authorizes deletion of a replaced part");
                check(PurplePlaylistsDownloadsCore.records(result.snapshot.state(),101).length==1,
                        "failed inode proof retains durable intent");
            } finally {f.stop(worker);}
        }
        {
            Fixture f=new Fixture();Ui ui=new Ui();PurplePlaylistsWorker worker=f.worker(0);
            try {
                String playlist=setup(worker,f,ui);DownloadFactory factory=new DownloadFactory();
                PurplePlaylistsDownloads downloads=new PurplePlaylistsDownloads(worker,factory);
                PurplePlaylistsWorker.Snapshot selection=selected(worker,ui,playlist);
                PurplePlaylistsDownloads.Control control=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,1,91)));
                ConnectionsManager.Sent pending=sent(ConnectionsManager.instances[0]);
                worker.removeDownloads(selected(worker,ui,playlist),false,ui.target());PurplePlaylistsWorker.Outcome result=ui.next();
                check(!result.ready() && result.cleanup.status==PurplePlaylistsRemoval.Status.Busy
                        && !ConnectionsManager.instances[0].canceled.contains(pending.token),
                        "active transfer defers removal without canceling another operation");
                control.cancel();status(control,PurplePlaylistsDownloads.Status.Canceled);
                worker.removeDownloads(selected(worker,ui,playlist),false,ui.target());result=ui.next();
                check(result.ready() && PurplePlaylistsDownloadsCore.records(result.snapshot.state(),101).length==0,
                        "canceled batch reservation no longer blocks explicit removal forever");
                reply(pending,payload(91,55));
                check(!Files.exists(f.folder.resolve("files/1.mp3")),"late canceled callback cannot recreate removed transfer");
            } finally {f.stop(worker);}
        }
    }

    private static void orphanIntentCases() throws Exception {
        Fixture f=new Fixture();Ui ui=new Ui();PurplePlaylistsWorker worker=f.worker(0);
        try {
            String playlist=setup(worker,f,ui);
            worker.edit(selected(worker,ui,playlist),PurplePlaylistsEdits.Command.copyCurrent(playlist,300),ui.target());
            String other=ui.next().edit.playlistId;
            PurplePlaylistsDownloadsCore.Record intent=seedRemovalIntent(worker,ui,playlist,2);
            Path part=f.folder.resolve("files/.incoming/"+intent.token+".part");
            worker.removeDownloads(selected(worker,ui,other),false,ui.target());PurplePlaylistsWorker.Outcome result=ui.next();
            check(result.ready() && result.cleanup.removed==0 && Files.exists(part),"explicit cleanup cannot discard a different playlist inactive intent");
            worker.edit(selected(worker,ui,other),PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete,other,400),ui.target());ui.next();
            worker.edit(selected(worker,ui,playlist),PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.RemoveSongs,playlist,new String[]{"1"},500),ui.target());
            result=ui.next();check(result.ready() && result.cleanup.ready() && result.cleanup.removed==0 && Files.exists(part),
                    "Recently deleted source protects inactive intent after live song removal");
            worker.edit(selected(worker,ui,other),PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.DeleteForever,other,600),ui.target());
            result=ui.next();check(result.ready() && result.cleanup.ready() && result.cleanup.removed==1 && !Files.exists(part),
                    "confirmed final-reference purge prunes inactive transfer with shared core policy");
            check(PurplePlaylistsDownloadsCore.records(result.snapshot.state(),101).length==0 && selected(worker,ui,playlist).cleanup==null,
                    "orphan transfer cleanup reads back empty inventory and cleared prune marker");
        } finally {f.stop(worker);}
    }

    private static void obsoleteCleanupCases() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            String playlist = setup(worker, f, ui);
            worker.edit(selected(worker, ui, playlist), PurplePlaylistsEdits.Command.copyCurrent(playlist, 300), ui.target());
            String other = ui.next().edit.playlistId;
            PurplePlaylistsDownloadsCore.Record intent = seedRemovalIntent(worker, ui, playlist, 1);
            Path part = f.folder.resolve("files/.incoming/" + intent.token + ".part");
            worker.removeDownloads(selected(worker, ui, playlist), false, ui.target());
            check(!ui.next().ready(), "obsolete target seed retains failed selected cleanup");
            PurplePlaylistsWorker.Snapshot marked = selected(worker, ui, playlist);
            byte[] oldLibrary = marked.library();
            PurplePlaylistsDownloadsCore.Result otherMarker = PurplePlaylistsDownloadsCore.cleanup(marked.state(), 101, other, true);
            org.json.JSONObject local = new org.json.JSONObject(new String(otherMarker.local(), StandardCharsets.UTF_8));
            local.put("future_local", new org.json.JSONObject().put("untouched", true));
            local.getJSONObject("android_playlist_downloads").getJSONObject("cleanup").put("future_cleanup", "preserve");
            write(f.folder.resolve("local.json"), bytes(local.toString()));
            worker.edit(selected(worker, ui, playlist), PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete, playlist, 400), ui.target());
            PurplePlaylistsWorker.Outcome deleted = ui.next();
            check(deleted.ready() && selected(worker, ui, playlist).cleanup != null,
                    "Recently deleted keeps selected cleanup scope alive");
            worker.undo(selected(worker, ui, playlist), deleted.undo, 500, ui.target());
            PurplePlaylistsWorker.Outcome restored = ui.next();
            check(restored.ready() && selected(worker, ui, playlist).cleanup != null,
                    "Undo prune cannot clear a live selected cleanup marker");
            worker.edit(selected(worker, ui, playlist), PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete, playlist, 600), ui.target()); ui.next();
            worker.edit(selected(worker, ui, playlist), PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.DeleteForever, playlist, 700), ui.target());
            PurplePlaylistsWorker.Outcome purged = ui.next();
            check(purged.ready() && purged.libraryCommitted && purged.cleanup.ready(),
                    "purge with another shared reference confirms pruning without touching protected part");
            org.json.JSONObject cleanup = new org.json.JSONObject(new String(purged.snapshot.state(), StandardCharsets.UTF_8))
                    .getJSONObject("android_playlist_downloads").getJSONObject("cleanup");
            check(!cleanup.getJSONArray("playlists").toString().contains(playlist),
                    "confirmed global prune clears only purged selected cleanup target");
            check(cleanup.getJSONArray("playlists").toString().contains(other) && cleanup.getString("future_cleanup").equals("preserve"),
                    "normalization preserves live selected target and unknown cleanup fields");
            check(Files.exists(part) && new org.json.JSONObject(new String(purged.snapshot.state(), StandardCharsets.UTF_8))
                    .getJSONObject("future_local").getBoolean("untouched"),
                    "normalization preserves protected part and unrelated local data");
            worker.read(ui.target()); check(ui.next().cleanup == null,
                    "cleared obsolete marker does not retarget another live cleanup into global Retry");
            check(selected(worker, ui, other).cleanup != null && !selected(worker, ui, other).cleanup.prune,
                    "other live selected Retry remains exact after purge");
            worker.merge(selected(worker, ui, other), oldLibrary, ui.target());
            PurplePlaylistsWorker.Outcome merged = ui.next();
            check(merged.ready() && !new org.json.JSONObject(new String(merged.snapshot.state(), StandardCharsets.UTF_8)).getJSONObject("android_playlist_downloads").getJSONObject("cleanup").getJSONArray("playlists").toString().contains(playlist),
                    "merge cannot resurrect a purged cleanup marker from older library");
        } finally { f.stop(worker); }
        Fixture lone = new Fixture(); Ui loneUi = new Ui(); PurplePlaylistsWorker loneWorker = lone.worker(0);
        try {
            String playlist = setup(loneWorker, lone, loneUi);
            PurplePlaylistsDownloadsCore.Record intent = seedRemovalIntent(loneWorker, loneUi, playlist, 1);
            Path part = lone.folder.resolve("files/.incoming/" + intent.token + ".part");
            loneWorker.removeDownloads(selected(loneWorker, loneUi, playlist), false, loneUi.target()); loneUi.next();
            loneWorker.edit(selected(loneWorker, loneUi, playlist), PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete, playlist, 300), loneUi.target()); loneUi.next();
            loneWorker.edit(selected(loneWorker, loneUi, playlist), PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.DeleteForever, playlist, 400), loneUi.target());
            PurplePlaylistsWorker.Outcome failed = loneUi.next();
            check(failed.ready() && failed.libraryCommitted && failed.cleanup.status == PurplePlaylistsRemoval.Status.Failed,
                    "unproven orphan cleanup failure never invalidates confirmed purge");
            loneWorker.read(loneUi.target()); PurplePlaylistsWorker.Snapshot root = loneUi.next().snapshot;
            check(root.cleanup != null && root.cleanup.prune,
                    "purged selected cleanup exposes global Retry while orphan remains");
            PurplePlaylistsDownloadsCore.Result noGlobal = PurplePlaylistsDownloadsCore.cleanup(root.state(), 101, null, false);
            write(lone.folder.resolve("local.json"), noGlobal.local());
            loneWorker.read(playlist, loneUi.target()); PurplePlaylistsWorker.Outcome staleSelection = loneUi.next();
            check(staleSelection.ready() && staleSelection.snapshot.view.detail == null
                    && staleSelection.cleanup != null && staleSelection.cleanup.prune,
                    "purged selected screen exposes global Retry without a preexisting prune flag");
            loneWorker.read(loneUi.target()); root = loneUi.next().snapshot;
            check(root.cleanup != null && root.cleanup.prune,
                    "orphan selected marker alone keeps cleanup reachable from root");
            Files.delete(part);
            loneWorker.removeDownloads(root, true, loneUi.target());
            PurplePlaylistsWorker.Outcome cleaned = loneUi.next();
            check(cleaned.ready() && cleaned.cleanup.removed == 1,
                    "healthy global Retry reconciles orphan absence after purge");
            loneWorker.read(loneUi.target()); PurplePlaylistsWorker.Snapshot clean = loneUi.next().snapshot;
            check(clean.cleanup == null && !new String(clean.state(), StandardCharsets.UTF_8).contains("\"cleanup\""),
                    "confirmed pruning removes unreachable selected marker and global marker together");
        } finally { lone.stop(loneWorker); }
    }

    private static void removalCases() throws Exception {
        Fixture f=new Fixture();Ui ui=new Ui();PurplePlaylistsWorker worker=f.worker(0);
        try {
            String playlist=setup(worker,f,ui);
            DownloadFactory factory=new DownloadFactory();PurplePlaylistsDownloads downloads=new PurplePlaylistsDownloads(worker,factory);
            PurplePlaylistsWorker.Snapshot selection=selected(worker,ui,playlist);
            List<PurplePlaylistsDownloads.Item> items=java.util.Arrays.asList(item(selection,1,91),item(selection,2,91),item(selection,3,91));
            PurplePlaylistsDownloads.Control control=downloads.start(selection,playlist,items);
            byte[] content=payload(91,17);
            reply(sent(ConnectionsManager.instances[0]),content);reply(sent(ConnectionsManager.instances[0]),content);
            reply(sent(ConnectionsManager.instances[0]),content);status(control,PurplePlaylistsDownloads.Status.Downloaded);
            worker.edit(selected(worker,ui,playlist),PurplePlaylistsEdits.Command.copyCurrent(playlist,300),ui.target());
            PurplePlaylistsWorker.Outcome copied=ui.next();check(copied.ready(),"shared-reference playlist copy created");
            String other=copied.edit.playlistId;
            worker.edit(copied.snapshot,PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete,other,400),ui.target());
            check(ui.next().ready(),"protecting reference moves to Recently deleted");
            worker.removeDownloads(selected(worker,ui,playlist),false,ui.target());
            PurplePlaylistsWorker.Outcome protectedResult=ui.next();
            check(protectedResult.ready() && protectedResult.cleanup.ready() && protectedResult.cleanup.removed==0
                    && protectedResult.cleanup.protectedFiles==3,"Recently deleted protects every shared kept file from explicit removal");
            check(Files.exists(f.folder.resolve("files/1.mp3")),"protected retained file remains on disk");
            worker.edit(selected(worker,ui,other),PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.DeleteForever,other,500),ui.target());
            PurplePlaylistsWorker.Outcome purged=ui.next();
            check(purged.ready() && purged.cleanup.ready() && purged.cleanup.removed==0,
                    "confirmed purge cleanup preserves another live playlist reference");
            selection=selected(worker,ui,playlist);byte[] library=selection.library();
            worker.removeDownloads(selection,false,ui.target());PurplePlaylistsWorker.Outcome removed=ui.next();
            check(removed.ready() && removed.cleanup.removed==3 && !removed.cleanup.prune,"explicit removal clears only owned selected files");
            check(Arrays.equals(library,removed.snapshot.library()) && PurplePlaylistsDownloadsCore.records(removed.snapshot.state(),101).length==0,
                    "download removal preserves synced playlist library and reads back cleared inventory");
            check(!Files.exists(f.folder.resolve("files/1.mp3")) && !Files.exists(f.folder.resolve("files/2.mp3")),"actual native unlink removed selected owned files");
            worker.removeDownloads(selection,false,ui.target());check(ui.next().status==PurplePlaylistsWorker.Status.Changed,
                    "stale removal capability cannot replay a mutation");
            selection=selected(worker,ui,playlist);
            control=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,1,91)));
            reply(sent(ConnectionsManager.instances[0]),content);status(control,PurplePlaylistsDownloads.Status.Downloaded);
            byte[] corrupted=payload(91,99);Files.write(f.folder.resolve("files/1.mp3"),corrupted);
            worker.removeDownloads(selected(worker,ui,playlist),false,ui.target());PurplePlaylistsWorker.Outcome refused=ui.next();
            check(!refused.ready() && refused.cleanup.status==PurplePlaylistsRemoval.Status.Failed && !refused.cleanup.filesMayHaveChanged,
                    "changed digest refuses explicit removal without claiming deletion");
            check(Arrays.equals(Files.readAllBytes(f.folder.resolve("files/1.mp3")),corrupted)
                    && PurplePlaylistsDownloadsCore.records(refused.snapshot.state(),101).length==1,
                    "changed file and inventory remain untouched for review");
            Files.write(f.folder.resolve("files/1.mp3"),content);
            worker.edit(selected(worker,ui,playlist),PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.RemoveSongs,playlist,new String[]{"1"},600),ui.target());
            PurplePlaylistsWorker.Outcome edited=ui.next();
            check(edited.ready() && edited.libraryCommitted && edited.cleanup.ready() && edited.cleanup.removed==1,
                    "confirmed Remove songs performs shared-reference cleanup without replaying edit");
            check(!Files.exists(f.folder.resolve("files/1.mp3")),"unreferenced kept file pruned by real worker");
            selection=selected(worker,ui,playlist);
            control=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,2,91)));
            reply(sent(ConnectionsManager.instances[0]),content);status(control,PurplePlaylistsDownloads.Status.Downloaded);
            Files.write(f.folder.resolve("files/2.mp3"),corrupted);
            worker.edit(selected(worker,ui,playlist),PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.RemoveSongs,playlist,new String[]{"2"},700),ui.target());
            edited=ui.next();
            check(edited.ready() && edited.libraryCommitted && edited.undo!=null
                    && edited.cleanup.status==PurplePlaylistsRemoval.Status.Failed,
                    "cleanup failure preserves confirmed edit success and Undo capability");
            Files.delete(f.folder.resolve("files/2.mp3"));
            worker.removeDownloads(selected(worker,ui,playlist),true,ui.target());removed=ui.next();
            check(removed.ready() && removed.cleanup.removed==1 && removed.cleanup.prune,
                    "durable absence reconciliation clears stale proof after missing-file or unlink uncertainty");
            check(PurplePlaylistsDownloadsCore.records(removed.snapshot.state(),101).length==0,
                    "absence reconciliation confirms inventory readback");
            org.json.JSONObject local=new org.json.JSONObject(new String(removed.snapshot.state(),StandardCharsets.UTF_8));
            local.put("android_playlist_downloads",new org.json.JSONObject().put("format",2));
            write(f.folder.resolve("local.json"),bytes(local.toString()));byte[] opaque=Files.readAllBytes(f.folder.resolve("local.json"));
            worker.edit(selected(worker,ui,playlist),PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.RemoveSongs,playlist,new String[]{"3"},800),ui.target());
            edited=ui.next();
            check(edited.ready() && edited.cleanup.status==PurplePlaylistsRemoval.Status.Unavailable,
                    "opaque download cleanup refusal never invalidates confirmed library edit");
            check(new org.json.JSONObject(new String(edited.snapshot.state(),StandardCharsets.UTF_8)).getJSONObject("android_playlist_downloads").getInt("format")==2,
                    "reference cleanup preserves newer opaque client member");
        } finally { f.stop(worker); }
    }

    private static void registration(boolean cancel) throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui();
        PurplePlaylistsWorker seed = f.worker(0);
        String playlist;
        try {
            playlist = setup(seed, f, ui);
            PurplePlaylistsWorker.Snapshot selection = selected(seed, ui, playlist);
            List<PurplePlaylistsDownloads.Item> items = new ArrayList<>();
            for (int i = 1; i <= 9; ++i) items.add(item(selection, i, 91));
            PurplePlaylistsDownloads downloads = new PurplePlaylistsDownloads(seed, new DownloadFactory());
            PurplePlaylistsDownloads.Control control = downloads.start(selection, playlist, items);
            for (int i = 0; i < 9; ++i) reply(sent(ConnectionsManager.instances[0]), payload(91, i));
            status(control, PurplePlaylistsDownloads.Status.Downloaded);
            check(control.nativeReady == 9, "seed proves every completed file before reopening actor");
        } finally { f.stop(seed); }
        PurplePlaylistsWorker worker = f.worker(0);
        try {
            PurplePlaylistsWorker.Snapshot selection = selected(worker, ui, playlist);
            List<PurplePlaylistsDownloads.Item> items = new ArrayList<>();
            for (int i = 1; i <= 9; ++i) {
                items.add(item(selection, i, 91));
                check(PurplePlaylistsRetained.path(0, document(i, 91), false) == null,
                        "retired actor cannot lend an old mapping to registration fixture");
            }
            AtomicBoolean armed = new AtomicBoolean(), publicationEntered = new AtomicBoolean();
            AtomicInteger yieldedReady = new AtomicInteger(-1);
            CountDownLatch yielded = new CountDownLatch(1);
            AtomicReference<List<PurplePlaylistsDownloads.Completed>> captured = new AtomicReference<>();
            PurplePlaylistsDownloads downloads = new PurplePlaylistsDownloads(worker, new DownloadFactory());
            PurplePlaylistsDownloads.Control control = downloads.start(selection, playlist, items, value -> {
                if (value.status != PurplePlaylistsDownloads.Status.Running || value.progress == null
                        || !value.progress.terminal || value.completed != 9 || !armed.compareAndSet(false, true)) return;
                captured.set(value.control.completed);
                if (cancel) {
                    f.hook.set(() -> { publicationEntered.set(true); value.control.cancel(); });
                } else {
                    boolean accepted = worker.postStore(null, (store, self) -> {
                        yieldedReady.set(value.control.nativeReady);
                        yielded.countDown();
                    }, yielded::countDown);
                    if (!accepted) yielded.countDown();
                }
            });
            if (cancel) {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (control.status == PurplePlaylistsDownloads.Status.Running || control.status == PurplePlaylistsDownloads.Status.Queued) {
                    if (System.nanoTime() > until) throw new AssertionError("bounded publication cancellation");
                    Thread.sleep(2);
                }
                check(publicationEntered.get(), "Cancel runs inside actual retained publication authority check");
                check(control.status == PurplePlaylistsDownloads.Status.Canceled && control.nativeReady == 0,
                        "Cancel fences current publication and every later native registration");
                for (int i = 1; i <= 9; ++i)
                    check(PurplePlaylistsRetained.path(0, document(i, 91), false) == null,
                            "canceled final registration publishes no stale native mapping");
                check(control.progress.downloaded == 9, "Cancel during registration preserves completed durable files");
            } else {
                await(yielded);
                check(yieldedReady.get() > 0 && yieldedReady.get() < 9,
                        "queued actor task runs between final native registrations");
                status(control, PurplePlaylistsDownloads.Status.Downloaded);
                check(control.nativeReady == 9 && control.progress.downloaded == 9,
                        "bounded registration covers every requested completion");
                check(control.completed == captured.get(), "registration reuses unchanged immutable completed list");
                try { captured.get().clear(); throw new AssertionError("completed list must be immutable"); }
                catch (UnsupportedOperationException expected) { check(true, "completed list stays immutable across yielded turns"); }
                control.cancel();
                for (int i = 1; i <= 9; ++i)
                    check(PurplePlaylistsRetained.path(0, document(i, 91), false) != null,
                            "successful mapping lifetime belongs to actor rather than terminal batch");
            }
            check(ConnectionsManager.instances[0].sent.isEmpty(), "reopened registration uses real local proof without transport");
        } finally { f.stop(worker); }
    }
    private static org.json.JSONObject fullCache(org.json.JSONObject present, boolean stale) throws Exception {
        org.json.JSONObject records = new org.json.JSONObject().put("1.mp3", present.getJSONObject("files").getJSONObject("1.mp3"));
        records.put("1000.mp3", stale ? new org.json.JSONObject().put("identity", "1:1").put("size", "91")
                .put("sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") : new org.json.JSONObject().put("future", true));
        for (int i=0; i<1022; ++i) records.put("future-"+i, new org.json.JSONObject().put("opaque", i));
        return new org.json.JSONObject().put("format",1).put("files",records).put("future",new org.json.JSONObject().put("opaque","preserved"));
    }
    private static void cacheCapacity() throws Exception {
        Fixture f=new Fixture();Ui ui=new Ui();PurplePlaylistsWorker worker=f.worker(0);
        try {
            String playlist=setup(worker,f,ui);
            worker.preference(selected(worker,ui,playlist),PurplePlaylistsDownloadsCore.Preference.Off,ui.target());ui.next();
            PurplePlaylistsDownloads downloads=new PurplePlaylistsDownloads(worker,new DownloadFactory());
            PurplePlaylistsWorker.Snapshot selection=selected(worker,ui,playlist);
            byte[] content=payload(91,61);
            PurplePlaylistsDownloads.Control first=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,1,91)));
            reply(sent(ConnectionsManager.instances[0]),content);status(first,PurplePlaylistsDownloads.Status.Downloaded);
            Path folder=f.files.resolve("cache/purple-playlists/0-101"),index=folder.resolve("local.json"),media=folder.resolve("files");
            org.json.JSONObject present=new org.json.JSONObject(new String(Files.readAllBytes(index),StandardCharsets.UTF_8));
            Path firstFile=PurplePlaylistsCache.path(0,document(1,91),false).toPath();
            byte[] original=Files.readAllBytes(firstFile);
            org.json.JSONObject unavailable=fullCache(present,false);write(index,bytes(unavailable.toString()));byte[] before=Files.readAllBytes(index);
            selection=selected(worker,ui,playlist);
            PurplePlaylistsDownloads.Control blocked=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,2,91)));
            reply(sent(ConnectionsManager.instances[0]),content);status(blocked,PurplePlaylistsDownloads.Status.Partial);
            check(Arrays.equals(Files.readAllBytes(index),before)&&Arrays.equals(Files.readAllBytes(firstFile),original),
                    "capacity refusal preserves present cache file and unknown entries byte-for-byte");
            org.json.JSONObject eligible=fullCache(present,true);write(index,bytes(eligible.toString()));before=Files.readAllBytes(index);
            Path protectedTarget=f.files.resolve("not-cache-media");write(protectedTarget,bytes("preserve target"));
            Path link=media.resolve("1000.mp3");Files.createSymbolicLink(link,protectedTarget);
            selection=selected(worker,ui,playlist);
            PurplePlaylistsDownloads.Control linked=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,2,91)));
            reply(sent(ConnectionsManager.instances[0]),content);status(linked,PurplePlaylistsDownloads.Status.Partial);
            check(Arrays.equals(before,Files.readAllBytes(index))&&Files.isSymbolicLink(link)
                    &&new String(Files.readAllBytes(protectedTarget),StandardCharsets.UTF_8).equals("preserve target"),
                    "full cache never treats a symlink as confirmed absent or deletes its metadata");
            Files.delete(link);
            selection=selected(worker,ui,playlist);
            PurplePlaylistsDownloads.Control recovered=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,2,91)));
            reply(sent(ConnectionsManager.instances[0]),content);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(recovered.status==PurplePlaylistsDownloads.Status.Running||recovered.status==PurplePlaylistsDownloads.Status.Queued){if(System.nanoTime()>until)throw new AssertionError("bounded capacity completion");Thread.sleep(2);}
            check(recovered.status==PurplePlaylistsDownloads.Status.Downloaded,"confirmed cache eviction frees index capacity for a different song");
            org.json.JSONObject after=new org.json.JSONObject(new String(Files.readAllBytes(index),StandardCharsets.UTF_8));
            check(after.getJSONObject("files").length()==1024&&!after.getJSONObject("files").has("1000.mp3")
                    &&after.getJSONObject("files").has("1.mp3")&&after.getJSONObject("files").has("2.mp3"),
                    "reconciliation replaces only one confirmed-absent record and preserves the capacity bound");
            check(after.getJSONObject("future").getString("opaque").equals("preserved")
                    &&after.getJSONObject("files").getJSONObject("future-0").getInt("opaque")==0,
                    "capacity reconciliation preserves unknown index fields and record names");
            check(Arrays.equals(Files.readAllBytes(firstFile),original)
                    &&Arrays.equals(Files.readAllBytes(PurplePlaylistsCache.path(0,document(2,91),false).toPath()),content),
                    "capacity recovery preserves present bytes and registers only verified new media");
            check(org.telegram.messenger.AutoDeleteMediaTask.usingFilePaths.isEmpty(),"capacity refusal and success release all cache cleanup locks");
        } finally {f.stop(worker);}
    }
    private static void cachePlaybackHook() throws Exception {
        Fixture f=new Fixture();Ui ui=new Ui();PurplePlaylistsWorker worker=PurplePlaylistsWorkers.acquire(0);
        try {
            String playlist=setup(worker,f,ui);
            worker.preference(selected(worker,ui,playlist),PurplePlaylistsDownloadsCore.Preference.Off,ui.target());ui.next();
            PurplePlaylistsWorker.Snapshot selection=selected(worker,ui,playlist);
            PurplePlaylistsDownloads.Item item=item(selection,1,91);
            PurplePlaylistsDownloads downloads=new PurplePlaylistsDownloads(worker,new DownloadFactory());
            PurplePlaylistsDownloads.Control control=downloads.start(selection,playlist,java.util.Collections.singletonList(item));
            reply(sent(ConnectionsManager.instances[0]),payload(91,52));status(control,PurplePlaylistsDownloads.Status.Downloaded);
            java.lang.reflect.Field bindings=PurplePlaylistsCache.class.getDeclaredField("paths");bindings.setAccessible(true);
            ((java.util.Map<?,?>)bindings.get(null)).clear();
            org.telegram.tgnet.TLRPC.TL_message message=new org.telegram.tgnet.TLRPC.TL_message();
            message.media=new org.telegram.tgnet.TLRPC.TL_messageMediaDocument();message.media.document=document(1,91);
            message.media.document.mime_type="audio/mpeg";
            PurplePlaylistsResolver.Result result=new PurplePlaylistsResolver.Result(item.song,item.source,
                    PurplePlaylistsResolver.Status.Ready,message,0);
            CountDownLatch prepared=new CountDownLatch(1);AtomicBoolean sameResult=new AtomicBoolean();
            AutoCloseable receipt=PurplePlaylistsRetained.prepare(PurplePlaylistsAccount.capture(0),playlist,result,()->true,
                    value->{sameResult.set(value==result);prepared.countDown();});
            await(prepared);receipt.close();
            RetainedNativeHooks hook=new RetainedNativeHooks();hook.currentAccount=0;
            java.io.File fallback=f.files.resolve("fallback").toFile();
            check(sameResult.get() && !hook.lookup(message.media.document,false,fallback).equals(fallback),
                    "actual retained/native-player prepare hook rehydrates ordinary cache before callback");
            check(ConnectionsManager.instances[0].sent.isEmpty(),"native-player prepare needs no network for proven cached bytes");
            check(org.telegram.messenger.AutoDeleteMediaTask.usingFilePaths.isEmpty(),"read-only cache prepare leaves completed files evictable");
        } finally { f.stop(worker); }
    }

    private static void cacheRoute() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            String playlist = setup(worker, f, ui);
            PurplePlaylistsWorker.Snapshot selection = selected(worker, ui, playlist);
            worker.preference(selection, PurplePlaylistsDownloadsCore.Preference.Off, ui.target());
            check(ui.next().ready(), "cache route saves explicit Off");
            selection = selected(worker, ui, playlist);
            DownloadFactory factory = new DownloadFactory();
            PurplePlaylistsDownloads downloads = new PurplePlaylistsDownloads(worker, factory);
            ConnectionsManager connection = ConnectionsManager.getInstance(0);
            byte[] content = payload(91, 42);
            PurplePlaylistsDownloads.Item first = item(selection, 1, content.length);
            PurplePlaylistsDownloads.Control control = downloads.start(selection, playlist, java.util.Collections.singletonList(first));
            reply(sent(connection), content); status(control, PurplePlaylistsDownloads.Status.Downloaded);
            org.telegram.tgnet.TLRPC.TL_document document = document(1, content.length);
            java.io.File cached = PurplePlaylistsCache.path(0, document, false);
            check(cached != null && cached.toPath().startsWith(f.files.resolve("cache")), "Off publishes into actual ordinary cache root");
            check(Arrays.equals(Files.readAllBytes(cached.toPath()), content), "bounded owned response reaches cache unchanged");
            check(control.nativeReady == 0 && control.completed.isEmpty(), "cache success is never reported as kept native inventory");
            selection = selected(worker, ui, playlist);
            check(PurplePlaylistsDownloadsCore.records(selection.state(), 101).length == 0, "Off creates no kept inventory records");
            check(!Files.exists(f.folder.resolve("files/1.mp3")), "Off creates no dedicated kept media file");
            check(org.telegram.messenger.AutoDeleteMediaTask.usingFilePaths.isEmpty(), "completed cache files and index are evictable");
            UserConfig.selectedAccount = 1;
            RetainedNativeHooks hook = new RetainedNativeHooks(); hook.currentAccount=0;
            check(hook.lookup(document, false, f.files.resolve("fallback").toFile()).equals(cached),
                    "actual FileLoader hook follows captured account instead of globally selected account");
            check(PurplePlaylistsCache.path(1, document, false) == null, "cache mapping cannot cross account slots");
            UserConfig.selectedAccount = 0;
            PurplePlaylistsDownloads.Control reused = downloads.start(selection, playlist, java.util.Collections.singletonList(first));
            status(reused, PurplePlaylistsDownloads.Status.Downloaded);
            check(connection.sent.isEmpty(), "proven cache hit makes no transfer request");
            Files.delete(cached.toPath());
            check(PurplePlaylistsCache.path(0, document, false) == null, "cache eviction immediately revokes native mapping");
            PurplePlaylistsDownloads.Control refetch = downloads.start(selected(worker, ui, playlist), playlist, java.util.Collections.singletonList(first));
            reply(sent(connection), content); status(refetch, PurplePlaylistsDownloads.Status.Downloaded);
            cached = PurplePlaylistsCache.path(0, document, false);
            check(cached != null && Arrays.equals(Files.readAllBytes(cached.toPath()), content), "evicted ordinary cache refetches safely");
            java.lang.reflect.Field bindings = PurplePlaylistsCache.class.getDeclaredField("paths"); bindings.setAccessible(true);
            ((java.util.Map<?,?>)bindings.get(null)).clear();
            final PurplePlaylistsDownloads.Item restoreItem = item(selected(worker, ui, playlist), 1, content.length);
            org.telegram.tgnet.TLRPC.TL_message restoredMessage = new org.telegram.tgnet.TLRPC.TL_message();
            restoredMessage.media=new org.telegram.tgnet.TLRPC.TL_messageMediaDocument(); restoredMessage.media.document=document(1,content.length);
            restoredMessage.media.document.mime_type="audio/mpeg";
            PurplePlaylistsResolver.Result result = new PurplePlaylistsResolver.Result(restoreItem.song, restoreItem.source,
                    PurplePlaylistsResolver.Status.Ready, restoredMessage, 0);
            CountDownLatch restored = new CountDownLatch(1); AtomicBoolean restoredOk = new AtomicBoolean();
            worker.postStore(null, (store,self) -> {
                try { restoredOk.set(PurplePlaylistsCache.restore(store,self,worker,playlist,result,worker::downloadCurrent)); }
                finally { restored.countDown(); }
            }, restored::countDown);
            await(restored);
            check(restoredOk.get() && PurplePlaylistsCache.path(0,document,false)!=null && connection.sent.isEmpty(),
                    "playback restores proven cache index without network or partial creation");
            selection = selected(worker, ui, playlist);
            PurplePlaylistsDownloads.Control canceled = downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,2,262175)));
            ConnectionsManager.Sent held=sent(connection);
            CountDownLatch overlapDone=new CountDownLatch(1); AtomicBoolean overlapRefused=new AtomicBoolean();
            worker.postStore(null,(store,self)->{
                try { PurplePlaylistsCache.restore(store,self,worker,playlist,result,worker::downloadCurrent); }
                catch(Exception error) { overlapRefused.set(true); }
                finally { overlapDone.countDown(); }
            },overlapDone::countDown);
            await(overlapDone);
            check(overlapRefused.get(),"overlapping cache reader respects actual single native writer lease");
            check(org.telegram.messenger.AutoDeleteMediaTask.usingFilePaths.contains(
                    f.files.resolve("cache/purple-playlists/0-101/writer.lock").toString())
                    && org.telegram.messenger.AutoDeleteMediaTask.usingFilePaths.contains(
                    f.files.resolve("cache/purple-playlists/0-101/local.json").toString()),
                    "failed overlapping reader cannot unlock active writer or index");
            byte[] large=payload(262175,19); reply(held,large);
            ConnectionsManager.Sent late=sent(connection); canceled.cancel(); status(canceled,PurplePlaylistsDownloads.Status.Canceled);
            reply(late,large);
            check(PurplePlaylistsCache.path(0,document(2,large.length),false)==null,
                    "canceled cache transfer ignores late reply and publishes no mapping");
            check(org.telegram.messenger.AutoDeleteMediaTask.usingFilePaths.isEmpty(), "Cancel releases actual cache cleanup locks before terminal");
            selection=selected(worker,ui,playlist);
            PurplePlaylistsDownloads.Control changed=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,3,91)));
            late=sent(connection);
            worker.preference(selected(worker,ui,playlist),PurplePlaylistsDownloadsCore.Preference.On,ui.target());
            check(ui.next().ready(),"keep policy changes while owned request waits");
            reply(late,content); status(changed,PurplePlaylistsDownloads.Status.Partial);
            check(PurplePlaylistsCache.path(0,document(3,91),false)==null
                    && PurplePlaylistsDownloadsCore.records(selected(worker,ui,playlist).state(),101).length==0,
                    "changed policy cannot publish cached bytes or switch an in-flight transfer into kept inventory");
            worker.preference(selected(worker,ui,playlist),PurplePlaylistsDownloadsCore.Preference.Off,ui.target()); ui.next();
            selection=selected(worker,ui,playlist);
            PurplePlaylistsDownloads.Control sourceChanged=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,6,91)));
            late=sent(connection);
            worker.edit(selected(worker,ui,playlist),PurplePlaylistsEdits.Command.songs(
                    PurplePlaylistsEdits.Kind.RemoveSongs,playlist,new String[]{"6"},800),ui.target());
            check(ui.next().ready(),"source can be removed while cache response waits");
            reply(late,content);status(sourceChanged,PurplePlaylistsDownloads.Status.Partial);
            check(PurplePlaylistsCache.path(0,document(6,91),false)==null,"removed source cannot publish a cache mapping");
            worker.preference(selected(worker,ui,playlist),PurplePlaylistsDownloadsCore.Preference.Inherit,ui.target()); ui.next();
            PurpleAccountSettings.value.playlistsKeepDownloaded=false;
            selection=selected(worker,ui,playlist);
            PurplePlaylistsDownloads.Control inherited=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,4,91)));
            reply(sent(connection),content); status(inherited,PurplePlaylistsDownloads.Status.Downloaded);
            check(inherited.nativeReady==0 && PurplePlaylistsCache.path(0,document(4,91),false)!=null,
                    "inherited app default Off uses the cache route");
            worker.preference(selected(worker,ui,playlist),PurplePlaylistsDownloadsCore.Preference.On,ui.target()); ui.next();
            selection=selected(worker,ui,playlist);
            PurplePlaylistsDownloads.Control on=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,5,91)));
            reply(sent(connection),content); status(on,PurplePlaylistsDownloads.Status.Downloaded);
            check(on.nativeReady==1 && PurplePlaylistsDownloadsCore.records(selected(worker,ui,playlist).state(),101).length==1,
                    "explicit On overrides default Off and retains through existing journal");
            worker.preference(selected(worker,ui,playlist),PurplePlaylistsDownloadsCore.Preference.Off,ui.target()); ui.next();
            selection=selected(worker,ui,playlist);
            PurplePlaylistsDownloads.Control rootChanged=downloads.start(selection,playlist,java.util.Collections.singletonList(item(selection,7,91)));
            late=sent(connection);
            Path cacheRoot=f.files.resolve("cache"),oldCache=f.files.resolve("old-cache");
            Files.move(cacheRoot,oldCache);directory(cacheRoot);
            reply(late,content);status(rootChanged,PurplePlaylistsDownloads.Status.Partial);
            check(PurplePlaylistsCache.path(0,document(1,91),false)==null
                    && PurplePlaylistsCache.path(0,document(7,91),false)==null,
                    "cache root replacement revokes old mappings and in-flight publication");
            check(org.telegram.messenger.AutoDeleteMediaTask.usingFilePaths.isEmpty(),"replaced cache root still releases exact cleanup locks");
        } finally { PurpleAccountSettings.value.playlistsKeepDownloaded=true; UserConfig.selectedAccount=0; f.stop(worker); }
    }

    private static void opaqueState() throws Exception {
        for (int mode = 0; mode < 4; ++mode) {
            Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
            try {
                String playlist = setup(worker, f, ui);
                Path file = f.folder.resolve("local.json");
                org.json.JSONObject local = new org.json.JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                local.put("desktop_playlist_downloads", new org.json.JSONObject("{\"format\":99,\"opaque\":[1,2,3]}"));
                if (mode == 1) local.put("android_playlist_downloads", "malformed but preserve me");
                if (mode == 2) local.put("android_playlist_downloads", new org.json.JSONObject("{\"format\":2,\"future\":[3,2,1]}"));
                if (mode == 3) local.put("android_playlist_downloads", new org.json.JSONObject("{\"format\":1,\"preferences\":false}"));
                write(file, bytes(local.toString()));
                String desktop = local.get("desktop_playlist_downloads").toString();
                String android = mode == 0 ? null : local.get("android_playlist_downloads").toString();
                worker.read(playlist, ui.target()); PurplePlaylistsWorker.Outcome read = ui.next();
                check(read != null && read.ready() && read.snapshot.view.detail != null,
                        "opaque download metadata keeps ordinary playlist reads available mode=" + mode);
                if (mode != 0) {
                    check(read.snapshot.preference == null, "opaque inventory has no optimistic typed preference");
                    MainTasks tasks = new MainTasks();
                    PurplePlaylistsDownloadControls controls = new PurplePlaylistsDownloadControls(
                            PurplePlaylistsAccount.capture(0), worker, new ControlEnvironment(tasks), tasks);
                    check(!controls.start(read.snapshot, null), "actual download controls refuse opaque inventory before source request");
                    check(ConnectionsManager.instances[0].sent.isEmpty(), "opaque refusal sends no transfer request");
                    byte[] before = Files.readAllBytes(file);
                    worker.preference(read.snapshot, PurplePlaylistsDownloadsCore.Preference.On, ui.target());
                    PurplePlaylistsWorker.Outcome refused = ui.next();
                    check(!refused.ready() && !refused.stateMayHaveChanged && !refused.libraryMayHaveChanged,
                            "opaque download metadata refuses typed preference without write mode=" + mode);
                    check(Arrays.equals(before, Files.readAllBytes(file)), "refused preference preserves exact bytes");
                    read = f.read(worker, ui);
                }
                worker.edit(read.snapshot, PurplePlaylistsEdits.Command.rename(playlist, "Opaque safe", 500), ui.target());
                PurplePlaylistsWorker.Outcome renamed = ui.next();
                check(renamed.ready() && renamed.undo != null, "rename works over opaque download metadata");
                check(renamed.snapshot.view.detail == null || renamed.snapshot.view.detail.row.title.equals("Opaque safe"), "renamed projection stays valid");
                worker.undo(renamed.snapshot, renamed.undo, 600, ui.target());
                PurplePlaylistsWorker.Outcome undone = ui.next();
                check(undone.ready(), "Undo works over opaque download metadata");
                worker.edit(undone.snapshot, PurplePlaylistsEdits.Command.add(playlist,
                        new PurplePlaylistsEdits.Song[] {new PurplePlaylistsEdits.Song("100", "self", 100, "", "Added", "Artist", 30)}, 700), ui.target());
                PurplePlaylistsWorker.Outcome added = ui.next();
                check(added.ready(), "Add works over opaque download metadata");
                PurplePlaylistsWorker.Snapshot afterAdd = selected(worker, ui, playlist);
                check(afterAdd.view.detail.row.recentUseMs == 700, "Add updates real recent-use bookkeeping despite opaque inventory");
                org.json.JSONObject saved = new org.json.JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                check(desktop.equals(saved.get("desktop_playlist_downloads").toString()), "normal edits preserve Desktop inventory");
                check(mode == 0 ? !saved.has("android_playlist_downloads") : android.equals(saved.get("android_playlist_downloads").toString()),
                        "normal edits preserve opaque Android inventory or its absence");
                check(PurplePlaylistsLocalCore.canonicalize(added.snapshot.state(), 101).valid(), "outer local state stays validated");
                worker.setSync(afterAdd, true, ui.target());
                check(ui.next().ready(), "ordinary local setting still usable with opaque inventory");
                saved = new org.json.JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                check(desktop.equals(saved.get("desktop_playlist_downloads").toString())
                        && (mode == 0 ? !saved.has("android_playlist_downloads") : android.equals(saved.get("android_playlist_downloads").toString())),
                        "ordinary local setting preserves both opaque inventories and absence");
            } finally { f.stop(worker); }
        }
    }

    private static void keepPolicy() throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            String playlist = setup(worker, f, ui);
            PurplePlaylistsWorker.Snapshot initial = selected(worker, ui, playlist);
            check(initial.preference == PurplePlaylistsDownloadsCore.Preference.Inherit && Boolean.TRUE.equals(initial.keepDefault),
                    "typed keep policy starts with inherited current app default");
            byte[] library = Files.readAllBytes(f.folder.resolve("library.json"));
            worker.preference(initial, PurplePlaylistsDownloadsCore.Preference.Off, ui.target());
            PurplePlaylistsWorker.Outcome off = ui.next();
            check(off.ready() && off.stateCommitted && !off.libraryMayHaveChanged && off.snapshot.preference == PurplePlaylistsDownloadsCore.Preference.Off,
                    "Off commits only local keep state and projects verified readback");
            check(Arrays.equals(library, Files.readAllBytes(f.folder.resolve("library.json"))), "local keep choice never changes synced library");
            worker.preference(initial, PurplePlaylistsDownloadsCore.Preference.On, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.Changed, "stale keep snapshot cannot overwrite later preference");
            PurplePlaylistsWorker other = f.worker(1); Ui otherUi = new Ui();
            try {
                other.preference(off.snapshot, PurplePlaylistsDownloadsCore.Preference.On, otherUi.target());
                check(otherUi.next().status == PurplePlaylistsWorker.Status.Changed, "foreign account cannot consume keep preference capability");
            } finally { f.stop(other); }
            UserConfig.selectedAccount = 1;
            worker.preference(off.snapshot, PurplePlaylistsDownloadsCore.Preference.On, ui.target());
            PurplePlaylistsWorker.Outcome on = ui.next();
            check(on.ready() && on.snapshot.preference == PurplePlaylistsDownloadsCore.Preference.On,
                    "queued typed preference retains captured account after selected slot changes");
            UserConfig.selectedAccount = 0;
            worker.preference(on.snapshot, PurplePlaylistsDownloadsCore.Preference.Inherit, ui.target());
            PurplePlaylistsWorker.Outcome inherited = ui.next();
            check(inherited.ready() && inherited.snapshot.preference == PurplePlaylistsDownloadsCore.Preference.Inherit,
                    "Inherit removes override through shared core and readback");
            PurpleAccountSettings.value.playlistsKeepDownloaded = false;
            check(Boolean.FALSE.equals(selected(worker, ui, playlist).keepDefault), "typed app default reflects effective account settings");
            PurpleAccountSettings.available = false;
            check(selected(worker, ui, playlist).keepDefault == null, "unavailable shared settings are never projected as default On");
            PurpleAccountSettings.available = true; PurpleAccountSettings.value.playlistsReady = false;
            check(selected(worker, ui, playlist).keepDefault == null, "invalid effective settings remain unavailable");
            PurpleAccountSettings.value.playlistsReady = true;
            org.json.JSONObject local = new org.json.JSONObject(new String(Files.readAllBytes(f.folder.resolve("local.json")), StandardCharsets.UTF_8));
            local.put("keep27_unknown", "preserve me"); write(f.folder.resolve("local.json"), bytes(local.toString()));
            PurplePlaylistsWorker.Snapshot current = selected(worker, ui, playlist);
            worker.preference(current, PurplePlaylistsDownloadsCore.Preference.Off, ui.target());
            PurplePlaylistsWorker.Outcome preserved = ui.next();
            check(preserved.ready() && new org.json.JSONObject(new String(preserved.snapshot.state(), StandardCharsets.UTF_8)).getString("keep27_unknown").equals("preserve me"),
                    "typed keep write preserves unrelated unknown local state");
            current = selected(worker, ui, playlist);
            org.json.JSONObject external = new org.json.JSONObject(new String(current.state(), StandardCharsets.UTF_8));
            external.put("keep27_unknown", "external edit"); write(f.folder.resolve("local.json"), bytes(external.toString()));
            worker.preference(current, PurplePlaylistsDownloadsCore.Preference.On, ui.target());
            PurplePlaylistsWorker.Outcome raced = ui.next();
            check(!raced.ready() && !raced.stateMayHaveChanged && raced.snapshot.preference == PurplePlaylistsDownloadsCore.Preference.Off,
                    "changed local file refuses keep write without optimistic success");
            check(new org.json.JSONObject(new String(raced.snapshot.state(), StandardCharsets.UTF_8)).getString("keep27_unknown").equals("external edit"),
                    "refused keep write preserves concurrent local edit");
            worker.read("missing", ui.target()); PurplePlaylistsWorker.Snapshot missing = ui.next().snapshot;
            worker.preference(missing, PurplePlaylistsDownloadsCore.Preference.On, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.InvalidEdit, "keep action refuses missing selected playlist");
            current = selected(worker, ui, playlist);
            worker.edit(current, PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete, playlist, 300), ui.target());
            PurplePlaylistsWorker.Outcome deleted = ui.next();
            worker.preference(deleted.snapshot, PurplePlaylistsDownloadsCore.Preference.On, ui.target());
            check(ui.next().status == PurplePlaylistsWorker.Status.InvalidEdit, "keep action refuses soft-deleted playlist");
        } finally {
            PurpleAccountSettings.available = true; PurpleAccountSettings.value.playlistsReady = true;
            PurpleAccountSettings.value.playlistsKeepDownloaded = true; UserConfig.selectedAccount = 0; f.stop(worker);
        }
    }

    private static void restartSeed(Path handoff) throws Exception {
        Fixture f = new Fixture(); Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        String playlist = setup(worker, f, ui);
        PurplePlaylistsWorker.Snapshot snapshot = selected(worker, ui, playlist);
        PurplePlaylistsDownloads.Item eighth = item(snapshot, 8, 91);
        check(worker.postStore(snapshot, (store, self) -> {
            PurplePlaylistsDownloadJournal journal = new PurplePlaylistsDownloadJournal(store, self, () -> true);
            PurplePlaylistsDownloadJournal.Entry entry = journal.prepare(playlist, eighth.song, eighth.source, 8, "mp3", 91);
            check(journal.append(entry, 0, Arrays.copyOf(payload(91, 61), 13)) == 13,
                    "seed leaves thirteen real bytes in a checkpointed native inode");
            PurplePlaylistsDownloadsCore.Record record = PurplePlaylistsDownloadsCore.records(store.read().state(), self)[0];
            check(!record.complete && !record.identity.isEmpty(), "seed intent records exact native inode before process death");
            org.json.JSONObject value = new org.json.JSONObject();
            value.put("files", f.files.toString()); value.put("playlist", playlist); value.put("identity", record.identity);
            value.put("token", record.token); value.put("seedPid", java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(handoff.toFile())) {
                output.write(bytes(value.toString())); output.getFD().sync();
            }
            System.out.println("PLAYLIST_RESTART_SEED_CHECKS=" + checks); System.out.flush();
            Runtime.getRuntime().halt(86);
        }, () -> { throw new AssertionError("seed store admission changed"); }), "seed native checkpoint admitted");
        await(new CountDownLatch(1));
        throw new AssertionError("seed must halt before actor/native cleanup");
    }
    private static void restartRecover(Path handoff, String mode) throws Exception {
        org.json.JSONObject saved = new org.json.JSONObject(new String(Files.readAllBytes(handoff), StandardCharsets.UTF_8));
        check(!saved.getString("seedPid").equals(java.lang.management.ManagementFactory.getRuntimeMXBean().getName()),
                "recovery runs in a distinct JVM after seed process exited");
        Fixture f = new Fixture(java.nio.file.Paths.get(saved.getString("files")));
        byte[] owner = Files.readAllBytes(f.root.resolve("accounts/root.toml"));
        byte[] local = Files.readAllBytes(f.root.resolve("local.toml"));
        byte[] library = Files.readAllBytes(f.folder.resolve("library.json"));
        byte[] state = Files.readAllBytes(f.folder.resolve("local.json"));
        if (mode.equals("inode")) {
            Path staging = path(f, saved.getString("token") + ".part");
            check(staging != null, "checkpoint staging inode exists after process exit");
            Path displaced = staging.resolveSibling(staging.getFileName() + ".displaced");
            Files.move(staging, displaced); write(staging, Arrays.copyOf(payload(91, 61), 13));
        }
        Ui ui = new Ui(); PurplePlaylistsWorker worker = f.worker(0);
        try {
            String playlist = saved.getString("playlist");
            PurplePlaylistsWorker.Snapshot snapshot = selected(worker, ui, playlist);
            check(Arrays.equals(owner, Files.readAllBytes(f.root.resolve("accounts/root.toml")))
                    && Arrays.equals(local, Files.readAllBytes(f.root.resolve("local.toml")))
                    && Arrays.equals(library, Files.readAllBytes(f.folder.resolve("library.json")))
                    && Arrays.equals(state, snapshot.state()), "cold reopen reuses existing authority library and checkpoint without rewriting them");
            PurplePlaylistsDownloadsCore.Record record = PurplePlaylistsDownloadsCore.records(snapshot.state(), 101)[0];
            check(record.identity.equals(saved.getString("identity")) && !record.complete,
                    "cold process receives the original durable incomplete identity");
            PurplePlaylistsDownloads downloads = new PurplePlaylistsDownloads(worker, new DownloadFactory());
            PurplePlaylistsDownloads.Control control = downloads.start(snapshot, playlist,
                    java.util.Collections.singletonList(item(snapshot, 8, 91)));
            if (mode.equals("inode")) {
                status(control, PurplePlaylistsDownloads.Status.Partial);
                check(ConnectionsManager.getInstance(0).sent.isEmpty() && path(f, "8.mp3") == null,
                        "cold recovery refuses replacement staging inode before any network request");
            } else {
                ConnectionsManager.Sent request = sent(ConnectionsManager.getInstance(0));
                check(((org.telegram.tgnet.TLRPC.TL_upload_getFile)request.request).offset == 0,
                        "cold recovery aligns request to revalidate saved prefix");
                byte[] content = payload(91, 61); if (mode.equals("overlap")) content[0] ^= 1;
                reply(request, content);
                status(control, mode.equals("overlap") ? PurplePlaylistsDownloads.Status.Partial : PurplePlaylistsDownloads.Status.Downloaded);
                if (mode.equals("overlap")) check(path(f, "8.mp3") == null && control.progress.failed == 1,
                        "cold recovery rejects changed overlap without publishing mixed bytes");
                else {
                    check(Arrays.equals(Files.readAllBytes(path(f, "8.mp3")), content), "cold recovery publishes all ninety-one exact bytes");
                    check(control.nativeReady == 1 && PurplePlaylistsRetained.path(0, document(8, 91), false) != null,
                            "cold recovery registers only verified completed retained media");
                    PurplePlaylistsWorker.Snapshot done = selected(worker, ui, playlist);
                    check(PurplePlaylistsDownloadsCore.records(done.state(), 101)[0].complete,
                            "cold recovery reads back durable complete inventory");
                }
            }
        } finally { f.stop(worker); }
        System.out.println("PLAYLIST_RESTART_" + mode.toUpperCase(java.util.Locale.ROOT) + "_CHECKS=" + checks);
    }
    private static void cleanupSeed(Path handoff) throws Exception {
        Fixture f=new Fixture();Ui ui=new Ui();PurplePlaylistsWorker worker=f.worker(0);
        String playlist=setup(worker,f,ui);
        PurplePlaylistsDownloadsCore.Record record=seedRemovalIntent(worker,ui,playlist,1);
        worker.removeDownloads(selected(worker,ui,playlist),false,ui.target());
        PurplePlaylistsWorker.Outcome result=ui.next();
        check(!result.ready() && selected(worker,ui,playlist).cleanup!=null,"seed persists failed cleanup before process death");
        org.json.JSONObject value=new org.json.JSONObject().put("files",f.files.toString()).put("playlist",playlist)
                .put("token",record.token).put("seedPid",java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(handoff.toFile())) {
            output.write(bytes(value.toString()));output.getFD().sync();
        }
        System.out.println("PLAYLIST_CLEANUP_SEED_CHECKS="+checks);System.out.flush();Runtime.getRuntime().halt(86);
    }
    private static void cleanupRecover(Path handoff) throws Exception {
        org.json.JSONObject value=new org.json.JSONObject(new String(Files.readAllBytes(handoff),StandardCharsets.UTF_8));
        check(!value.getString("seedPid").equals(java.lang.management.ManagementFactory.getRuntimeMXBean().getName()),
                "pending cleanup reopens in a distinct process");
        Fixture f=new Fixture(java.nio.file.Paths.get(value.getString("files")));Ui ui=new Ui();PurplePlaylistsWorker worker=f.worker(0);
        try {
            String playlist=value.getString("playlist");byte[] before=Files.readAllBytes(f.folder.resolve("local.json"));
            PurplePlaylistsWorker.Snapshot snapshot=selected(worker,ui,playlist);
            Path part=f.folder.resolve("files/.incoming/"+value.getString("token")+".part");
            check(snapshot.cleanup!=null && snapshot.cleanup.status==PurplePlaylistsRemoval.Status.Pending && !snapshot.cleanup.prune,
                    "cold process restores explicit cleanup target and Retry kind");
            check(Arrays.equals(before,snapshot.state()) && Files.exists(part),"reopen indication performs no cleanup or metadata write");
            check(ConnectionsManager.instances[0].sent.isEmpty(),"restored pending indication never schedules network retry");
            worker.removeDownloads(snapshot,false,ui.target());check(!ui.next().ready() && Files.exists(part),
                    "cold cleanup still refuses an identityless existing file");
            Files.delete(part);worker.removeDownloads(selected(worker,ui,playlist),false,ui.target());
            check(ui.next().ready() && selected(worker,ui,playlist).cleanup==null,"healthy explicit retry clears persisted cleanup after readback");
        } finally {f.stop(worker);}
        System.out.println("PLAYLIST_CLEANUP_RECOVER_CHECKS="+checks);
    }

    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("cleanup-seed")) {cleanupSeed(java.nio.file.Paths.get(args[1]));return;}
        if(args.length==2 && args[0].equals("cleanup-recover")) {cleanupRecover(java.nio.file.Paths.get(args[1]));return;}
        if (args.length == 2 && args[0].equals("seed")) { restartSeed(java.nio.file.Paths.get(args[1])); return; }
        if (args.length == 3 && args[0].equals("recover")) { restartRecover(java.nio.file.Paths.get(args[1]), args[2]); return; }
        if (args.length == 1) {
            switch (args[0]) {
                case "removal": removalCases(); inactiveIntentCases(); orphanIntentCases(); obsoleteCleanupCases(); break;
                case "cache": cacheRoute(); cachePlaybackHook(); cacheCapacity(); break;
                case "capacity": cacheCapacity(); break;
                case "registration": registration(false); break;
                case "registration-cancel": registration(true); break;
                case "opaque": opaqueState(); break;
                case "policy": keepPolicy(); break;
                case "controls": accountControls(); break;
                case "cancel": cancellationQueueFull(); break;
                case "authority": authorityCases(); break;
                case "transfer": downloadVertical(); break;
                default: throw new IllegalArgumentException("Unknown download scenario");
            }
            System.out.println("PLAYLIST_FOCUSED_CHECKS=" + checks);
            System.out.println("PLAYLIST_FOCUSED_OK"); return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Unknown download scenario");
        snapshotsAndCancellation(); queuedDeliveryAndForeignTickets(); boundedQueueAndRetirement();
        canceledRequestAndPartialTruth(); mergeBackupAndRequestCopy(); accountRegistry(); typedEditsAndUndo(); typedPartialCheckpoint(); canceledNoopDelivery();
        System.out.println("PLAYLIST_WORKER_CHECKS=" + checks);
        checks = 0; downloadVertical(); authorityCases(); cancellationQueueFull(); accountControls(); keepPolicy(); opaqueState(); cacheRoute(); cachePlaybackHook(); cacheCapacity(); registration(false); registration(true); removalCases(); inactiveIntentCases(); orphanIntentCases(); obsoleteCleanupCases();
        System.out.println("PLAYLIST_DOWNLOAD_TRANSFER_CHECKS=" + checks);
        System.out.println("PLAYLIST_DOWNLOAD_TRANSFER_OK");
    }
}
