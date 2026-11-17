package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public final class PurplePlaylistsSyncActorCases {
    private static int checks;
    private static final byte[] EMPTY = bytes("{\"format\":1,\"playlists\":{},\"purged\":{}}");
    private static final byte[] EDIT = bytes("{\"format\":1,\"playlists\":{\"pl-aaaaaaaaaaaaaaab\":{\"title\":[\"同步 ✓\",300],\"deleted\":[false,300]}},\"purged\":{}}");
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject object(byte[] value) throws Exception { return new JSONObject(new String(value, StandardCharsets.UTF_8)); }
    private static void check(boolean value, String name) { ++checks; if (!value) throw new AssertionError(name); }
    private static final class Timer implements PurplePlaylistsSyncActor.Scheduler {
        Runnable deadline;
        final java.util.Set<Runnable> timers = new java.util.HashSet<>();
        public long now() { return 1000; }
        public void post(Runnable task, long delay) { check(delay == 600000 || delay == 120000, "whole sync/prune deadline bounded"); timers.add(task); if (delay == 600000) deadline = task; }
        public void cancel(Runnable task) { check(timers.remove(task), "cancel only owned whole-sync/prune deadline"); if (task == deadline) deadline = null; }
    }
    private static final class Client extends PurplePlaylistsSyncPublicationCases.Client {
        final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
        final PurplePlaylistsWorkerCases.Fixture fixture;
        final PurplePlaylistsSyncPublicationCases.Authority authority;
        PurplePlaylistsSyncPublicationCases.Port port;
        int histories, downloads, sends;
        final java.util.TreeMap<Integer, TLRPC.TL_message> remote = new java.util.TreeMap<>(Collections.reverseOrder());
        final java.util.Map<Integer, File> bodies = new java.util.HashMap<>();
        boolean holdHistory, holdSend, holdDelete, failHistory, interruptHistory;
        Reply<PurpleSyncAutoCore.Outcome> heldDelete;
        PurpleRpcRetry.Kind transientKind;
        long transientDeadline;
        boolean recoveredTransient, clearUploadTransient;
        java.util.function.Consumer<PurpleRpcRetry.Transient> transientObserver = value -> { };
        @Override public void transientWait(java.util.function.Consumer<PurpleRpcRetry.Transient> observer) { transientObserver = observer == null ? value -> { } : observer; }
        void noteFailure() { if (transientKind != null) transientObserver.accept(new PurpleRpcRetry.Transient(transientKind, transientDeadline, recoveredTransient)); }
        Reply<History> held;
        Client(PurplePlaylistsWorkerCases.Fixture fixture, byte[] library) throws Exception {
            this.fixture = fixture; authority = new PurplePlaylistsSyncPublicationCases.Authority(this);
            byte[] payload = PurplePlaylistsSyncCore.encode(library, 101).publication();
            file = fixture.files.resolve("wire.gz").toFile(); Files.write(file.toPath(), payload);
            message = PurplePlaylistsSyncPublicationCases.message(41, payload.length);
            remote.put(41, message); bodies.put(41, file);
        }
        @Override public void runOnMain(Runnable task) { queued.add(task); }
        @Override public void runOnWorker(Runnable task) { queued.add(task); }
        @Override public int requestHistory(int offset, int limit, Reply<History> reply) {
            ++histories; if (recoveredTransient) noteFailure();
            if (failHistory || interruptHistory && offset != 0) { noteFailure(); reply.onReply(History.failed("NETWORK")); }
            else if (holdHistory) held = reply;
            else {
                java.util.List<Message> values = new ArrayList<>();
                for (TLRPC.TL_message item : remote.values()) if (offset == 0 || item.id < offset) values.add(wrap(item));
                reply.onReply(History.page(values, interruptHistory ? null : remote.size()));
            }
            return ++tokens;
        }
        private Message wrap(TLRPC.TL_message item) {
            return new Message(item.id, true, false, true, item.message, PurplePlaylistsSyncPublication.NAME,
                    chat, item.media.document.id, item.media.document.size, item.edit_date, item);
        }
        @Override public int requestMessage(int id, Reply<Lookup> reply) {
            ++lookupCount; reply.onReply(remote.containsKey(id) ? Lookup.found(wrap(remote.get(id))) : Lookup.missing()); return ++tokens;
        }
        @Override public void deleteOwn(int id, java.util.function.BooleanSupplier current, Reply<PurpleSyncAutoCore.Outcome> reply) {
            check(current.getAsBoolean() && remote.containsKey(id), "actor deletion remains scoped to fresh own target");
            if (holdDelete) { heldDelete = reply; return; }
            remote.remove(id); bodies.remove(id); reply.onReply(PurpleSyncAutoCore.Outcome.Deleted);
        }
        @Override public Object download(Message value, Reply<File> reply) { ++downloads; reply.onReply(bodies.get(value.id)); return "download-" + value.id; }
        @Override public PurplePlaylistsSyncPublication playlistPublication(PurpleSyncTransport.PostAuthority authority,
                java.util.function.BooleanSupplier scope) {
            port = new PurplePlaylistsSyncPublicationCases.Port(this, this.authority);
            PurplePlaylistsSyncPublication.Port endpoint = new PurplePlaylistsSyncPublication.Port() {
                public int upload(long file, int index, byte[] bytes, Reply<Boolean> reply) {
                    if (mode == 1 && !clearUploadTransient) noteFailure();
                    return port.upload(file, index, bytes, reply);
                }
                public TLRPC.InputPeer input() { return port.input(); }
                public int receipt(TLObject response, long random) { return port.receipt(response, random); }
                public void schedule(Runnable task, long delay) { port.schedule(task, delay); }
                public void unschedule(Runnable task) { port.unschedule(task); }
                public int send(TLRPC.TL_messages_sendMedia request, Reply<PurplePlaylistsSyncPublication.Rpc> reply) {
                    ++sends; port.sent = request;
                    check(Files.exists(fixture.folder.resolve("sync-publication.bin")), "actual durable native journal precedes actor send");
                    try {
                        byte[] wire = port.uploaded.toByteArray();
                        file = fixture.files.resolve("wire-published-" + sends + ".gz").toFile(); Files.write(file.toPath(), wire);
                        message = PurplePlaylistsSyncPublicationCases.message(remote.firstKey() + 1, wire.length);
                        remote.put(message.id, message); bodies.put(message.id, file);
                    } catch (Exception error) { throw new AssertionError(error); }
                    if (!holdSend) {
                        if (mode == 5) reply.onReply(new PurplePlaylistsSyncPublication.Rpc(null, "TransportUnknown"));
                        else {
                            TLRPC.TL_updates updates = new TLRPC.TL_updates();
                            org.telegram.tgnet.tl.TL_update.TL_updateMessageID id = new org.telegram.tgnet.tl.TL_update.TL_updateMessageID();
                            id.id = message.id; id.random_id = request.random_id; updates.updates.add(id);
                            org.telegram.tgnet.tl.TL_update.TL_updateNewMessage added = new org.telegram.tgnet.tl.TL_update.TL_updateNewMessage();
                            added.message = message; updates.updates.add(added);
                            reply.onReply(new PurplePlaylistsSyncPublication.Rpc(updates, null));
                        }
                    }
                    return ++tokens;
                }
            };
            PurplePlaylistsSyncPublication operation = new PurplePlaylistsSyncPublication(this, authority, scope, endpoint);
            port.operation = operation; return operation;
        }
        void pump(java.util.function.BooleanSupplier ready) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!ready.getAsBoolean() && System.nanoTime() < end) {
                Runnable next = queued.poll(); if (next != null) next.run(); else Thread.sleep(1);
            }
            check(ready.getAsBoolean(), "bounded whole actor callback");
            Runnable next; while ((next = queued.poll()) != null) next.run();
        }
    }
    private static void seed(PurplePlaylistsWorker worker, byte[] library, byte[] local) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        check(worker.postStore(null, (store, self) -> {
            check(store.write(store.read(), library, local, PurplePlaylistsStore.Kind.LocalEdit).ready(), "seed actual store"); gate.countDown();
        }, gate::countDown), "seed admitted");
        check(gate.await(10, TimeUnit.SECONDS), "seed completes");
    }
    private static byte[][] saved(PurplePlaylistsWorker worker) throws Exception {
        CountDownLatch gate = new CountDownLatch(1); byte[][] value = new byte[2][];
        check(worker.postStore(null, (store, self) -> { PurplePlaylistsStore.Read read = store.read();
            value[0] = read.library(); value[1] = read.state(); gate.countDown(); }, gate::countDown), "read admitted");
        check(gate.await(10, TimeUnit.SECONDS) && value[0] != null, "read completes"); return value;
    }
    private static PurplePlaylistsSyncActor run(PurplePlaylistsWorker worker, Client client, boolean manual,
            List<PurplePlaylistsSyncActor.Result> result) {
        PurplePlaylistsSyncActor actor = new PurplePlaylistsSyncActor(worker, client, client.authority, () -> true, new Timer());
        actor.start(manual, result::add); return actor;
    }
    private static void lifecycle() throws Exception {
        for (boolean publish : new boolean[]{false, true}) {
            PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
            PurplePlaylistsWorker worker = fixture.worker(0); AtomicInteger notices = new AtomicInteger();
            AutoCloseable observation = worker.observeSync(notices::incrementAndGet);
            try {
                seed(worker, publish ? EDIT : EMPTY, bytes("{\"format\":1,\"sync\":true,\"unsynced_since\":50,\"future\":7}"));
                Client client = new Client(fixture, publish ? EMPTY : EDIT);
                List<PurplePlaylistsSyncActor.Result> results = new ArrayList<>();
                run(worker, client, false, results); client.pump(() -> !results.isEmpty());
                check(results.size() == 1 && results.get(0).status == PurplePlaylistsSyncActor.Status.Ready
                        && results.get(0).commit.synced, "whole actor reaches durable Synced " + publish + ":" + results.get(0).status + ":" + results.get(0).detail + ":" + (results.get(0).commit.plan == null ? "none" : results.get(0).commit.plan.detail()));
                check(client.sends == (publish ? 1 : 0), "publication conditional on actual shared plan " + publish);
                check(!publish || results.get(0).prune != null && results.get(0).prune.deleted == 1
                        && !client.remote.containsKey(41), "actor prunes covered old record and rereads surviving witness");
                byte[][] actual = saved(worker);
                check(java.util.Arrays.equals(actual[0], PurplePlaylistsCore.canonicalize(EDIT, 101).canonicalBytes()), "actor merges or publishes actual latest local");
                check("synced".equals(object(actual[1]).getJSONObject("status").getString("result"))
                        && !object(actual[1]).has("unsynced_since") && object(actual[1]).getInt("future") == 7, "durable typed acknowledgement preserves unknowns");
                check(notices.get() > 0, "actual sync commit invalidates and notifies open screens");
                int beforeHistory = client.histories, beforeDownload = client.downloads, beforeNotices = notices.get();
                byte[] stateBefore = actual[1]; List<PurplePlaylistsSyncActor.Result> steady = new ArrayList<>();
                run(worker, client, false, steady); client.pump(() -> !steady.isEmpty());
                check(steady.get(0).status == PurplePlaylistsSyncActor.Status.Ready && steady.get(0).commit.synced, "memo-only steady actor remains Synced");
                check(client.histories - beforeHistory == 1 && client.downloads == beforeDownload, "steady one-request path with zero downloads");
                check(java.util.Arrays.equals(stateBefore, saved(worker)[1]) && notices.get() == beforeNotices, "steady actor does not write or invalidate open screens");
                check(client.sends == (publish ? 1 : 0), "steady never republishes");
            } finally { observation.close(); fixture.stop(worker); }
        }
    }
    private static void unknown() throws Exception {
        PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
        PurplePlaylistsWorker worker = fixture.worker(0);
        try {
            seed(worker, EDIT, bytes("{\"sync\":true,\"unsynced_since\":50}"));
            Client client = new Client(fixture, EMPTY); client.mode = 5;
            List<PurplePlaylistsSyncActor.Result> first = new ArrayList<>(); run(worker, client, true, first);
            client.pump(() -> !first.isEmpty());
            check(first.get(0).status == PurplePlaylistsSyncActor.Status.OutcomeUnknown && first.get(0).sendIssued
                    && client.sends == 1, "lost send receipt remains unknown with exact journal");
            TLRPC.TL_message uncertain = client.remote.remove(42);
            client.mode = 0; List<PurplePlaylistsSyncActor.Result> absent = new ArrayList<>();
            run(worker, client, true, absent); client.pump(() -> !absent.isEmpty());
            check(absent.get(0).status == PurplePlaylistsSyncActor.Status.OutcomeUnknown && client.sends == 1,
                    "unknown absent publication never causes a duplicate send");
            client.remote.put(42, uncertain);
            List<PurplePlaylistsSyncActor.Result> recovered = new ArrayList<>();
            run(worker, client, true, recovered); client.pump(() -> !recovered.isEmpty());
            check(recovered.get(0).status == PurplePlaylistsSyncActor.Status.Ready && recovered.get(0).commit.synced
                    && client.sends == 1, "fresh actor reconciles exact journal without another send");
        } finally { fixture.stop(worker); }
    }
    private static void cancelAndDisabled() throws Exception {
        PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture(); PurplePlaylistsWorker worker = fixture.worker(0);
        try {
            seed(worker, EMPTY, bytes("{\"sync\":false}")); Client client = new Client(fixture, EDIT);
            List<PurplePlaylistsSyncActor.Result> disabled = new ArrayList<>(); run(worker, client, false, disabled);
            client.pump(() -> !disabled.isEmpty()); check(disabled.get(0).status == PurplePlaylistsSyncActor.Status.Disabled
                    && client.histories == 0 && client.sends == 0, "automatic sync Off has no network");
            client.holdHistory = true; List<PurplePlaylistsSyncActor.Result> canceled = new ArrayList<>();
            PurplePlaylistsSyncActor actor = run(worker, client, true, canceled); client.pump(() -> client.held != null);
            actor.close(); client.held.onReply(PurpleSyncClient.History.page(Collections.singletonList(client.wrap()), 1));
            client.pump(() -> !canceled.isEmpty()); check(canceled.size() == 1 && canceled.get(0).status == PurplePlaylistsSyncActor.Status.Canceled
                    && client.sends == 0 && !client.canceled.isEmpty(), "cancellation settles once and cancels exact request");
        } finally { fixture.stop(worker); }
    }
    private static void emptyAndDeadline() throws Exception {
        PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture(); PurplePlaylistsWorker worker = fixture.worker(0);
        try {
            seed(worker, EMPTY, bytes("{\"sync\":true}")); Client empty = new Client(fixture, EMPTY); empty.remote.clear();
            List<PurplePlaylistsSyncActor.Result> settled = new ArrayList<>(); run(worker, empty, false, settled); empty.pump(() -> !settled.isEmpty());
            check(settled.get(0).commit.synced && empty.sends == 0 && empty.downloads == 0,
                    "empty first-use library is acknowledged without creating a record");
            seed(worker, EDIT, bytes("{\"sync\":true,\"unsynced_since\":50}")); Client held = new Client(fixture, EMPTY); held.holdSend = true;
            Timer timer = new Timer(); List<PurplePlaylistsSyncActor.Result> results = new ArrayList<>();
            PurplePlaylistsSyncActor actor = new PurplePlaylistsSyncActor(worker, held, held.authority, () -> true, timer);
            actor.start(true, results::add); held.pump(() -> held.sends == 1); timer.deadline.run(); held.pump(() -> !results.isEmpty());
            check(results.size() == 1 && results.get(0).status == PurplePlaylistsSyncActor.Status.TimedOut && results.get(0).sendIssued,
                    "whole actor deadline retains issued-send uncertainty");
            actor.close(); check(results.size() == 1, "timeout and cancel cannot settle twice");
        } finally { fixture.stop(worker); }
    }
    private static void typedOutcomes() throws Exception {
        for (PurplePlaylistsLocalCore.SyncResult result : PurplePlaylistsLocalCore.SyncResult.values()) {
            JSONObject state = object(PurplePlaylistsLocalCore.checkpoint(bytes("{\"unsynced_since\":50,\"future\":7}"), result, 1000, 101).canonicalBytes());
            check(state.getJSONObject("status").getString("result").equals(result.name().toLowerCase(java.util.Locale.ROOT)
                    .replace("newerformat", "newer_format")), "actual typed native outcome " + result);
            check(result == PurplePlaylistsLocalCore.SyncResult.Synced ? !state.has("unsynced_since")
                    : state.getLong("unsynced_since") == 50, "earliest age for typed result " + result);
            check(state.getInt("future") == 7, "unknown members survive typed status " + result);
        }
    }
    private static void transportStates() throws Exception {
        for (boolean history : new boolean[]{true, false}) for (PurpleRpcRetry.Kind kind : PurpleRpcRetry.Kind.values()) {
            PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
            PurplePlaylistsWorker worker = fixture.worker(0);
            try {
                seed(worker, EDIT, bytes("{\"sync\":true,\"unsynced_since\":50,\"future\":7,\"status\":{\"future_status\":8}}"));
                Client client = new Client(fixture, EMPTY); client.failHistory = history; client.mode = history ? 0 : 1;
                client.transientKind = kind; client.transientDeadline = kind == PurpleRpcRetry.Kind.Flood ? Long.MAX_VALUE : 19000;
                List<PurplePlaylistsSyncActor.Result> results = new ArrayList<>();
                run(worker, client, true, results); client.pump(() -> !results.isEmpty());
                PurplePlaylistsSyncActor.Result result = results.get(0);
                boolean offline = kind == PurpleRpcRetry.Kind.Transport || kind == PurpleRpcRetry.Kind.Timeout;
                check(result.status == (offline ? PurplePlaylistsSyncActor.Status.Offline : PurplePlaylistsSyncActor.Status.Wait), "actual transport outcome " + history + kind + ":" + result.status);
                JSONObject state = object(saved(worker)[1]); JSONObject status = state.getJSONObject("status");
                check(status.getString("result").equals(offline ? "offline" : "wait") && PurplePlaylistsLocalCore.retryAt(saved(worker)[1]) == client.transientDeadline,
                        "exact retry deadline survives actual native state and file write " + kind);
                check(state.getLong("unsynced_since") == 50 && state.getInt("future") == 7 && status.getInt("future_status") == 8, "wait preserves age and unknowns");
                check(result.checkpoint != null && result.checkpoint.stateCommitted && result.stateCommitted && result.stateMayHaveChanged,
                        "final checkpoint and aggregate write truth exposed");
                check(result.retryAt == client.transientDeadline && !result.sendIssued && !result.deleteIssued, "failed discovery/upload issuance is truthful");
                Client reopened = new Client(fixture, EMPTY); List<PurplePlaylistsSyncActor.Result> waiting = new ArrayList<>();
                run(worker, reopened, true, waiting); reopened.pump(() -> !waiting.isEmpty());
                check(waiting.get(0).status == result.status && waiting.get(0).retryAt == result.retryAt && reopened.histories == 0 && reopened.sends == 0,
                        "fresh manual actor respects persisted server cooldown without RPC");
                byte[] cleared = PurplePlaylistsLocalCore.checkpoint(saved(worker)[1], PurplePlaylistsLocalCore.SyncResult.Synced, 20000, 101).canonicalBytes();
                check(PurplePlaylistsLocalCore.retryAt(cleared) == 0 && !object(cleared).getJSONObject("status").has("retry_at_ms"), "successful native checkpoint clears deadline");
            } finally { fixture.stop(worker); }
        }
    }
    private static void managedWaitWithoutReply() throws Exception {
        PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
        PurplePlaylistsWorker worker = fixture.worker(0);
        try {
            seed(worker, EDIT, bytes("{\"sync\":true,\"unsynced_since\":50}"));
            Client client = new Client(fixture, EMPTY); client.holdHistory = true;
            List<PurplePlaylistsSyncActor.Result> results = new ArrayList<>();
            run(worker, client, true, results); client.pump(() -> client.held != null);
            client.transientObserver.accept(new PurpleRpcRetry.Transient(PurpleRpcRetry.Kind.Flood, 900000, false));
            client.pump(() -> !results.isEmpty());
            check(results.get(0).status == PurplePlaylistsSyncActor.Status.Wait && results.get(0).retryAt == 900000
                    && PurplePlaylistsLocalCore.retryAt(saved(worker)[1]) == 900000, "managed long wait persists before final RPC reply");
            check(!client.canceled.isEmpty() && client.histories == 1 && client.sends == 0, "long wait cancels only its pending request without automatic replay");
            client.held.onReply(PurpleSyncClient.History.page(Collections.emptyList(), 0));
            client.pump(() -> results.size() == 1); check(results.size() == 1, "late managed response cannot settle again");
        } finally { fixture.stop(worker); }
    }
    private static void committedWriteCancellation() throws Exception {
        for (boolean partial : new boolean[]{true, false}) {
            PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
            PurplePlaylistsWorker worker = fixture.worker(0);
            java.util.concurrent.atomic.AtomicReference<PurplePlaylistsSyncActor> actor = new java.util.concurrent.atomic.AtomicReference<>();
            List<PurplePlaylistsSyncActor.Result> results = new ArrayList<>();
            AtomicInteger writes = new AtomicInteger();
            AutoCloseable observation = worker.observeSync(() -> {
                if (writes.incrementAndGet() == (partial ? 1 : 2)) {
                    actor.get().close(); check(results.isEmpty(), "cancellation inside actual committed write waits for its result");
                }
            });
            try {
                seed(worker, partial ? EDIT : EMPTY, bytes("{\"sync\":true,\"unsynced_since\":50}"));
                Client client = new Client(fixture, partial ? EMPTY : EDIT); actor.set(run(worker, client, true, results));
                client.pump(() -> !results.isEmpty());
                PurplePlaylistsSyncActor.Result result = results.get(0);
                check(result.status == PurplePlaylistsSyncActor.Status.Canceled && result.stateCommitted && result.stateMayHaveChanged,
                        "cancel result includes persisted write before queued callback " + partial);
                check(partial ? result.commit == null && result.checkpoint != null && result.checkpoint.stateCommitted
                        : result.commit != null && result.commit.write.stateCommitted, "checkpoint and final commit cancellation both retain actual write truth " + partial);
                check(!result.sendIssued && client.sends == 0, "cancellation before queued publication makes no send claim");
            } finally { observation.close(); fixture.stop(worker); }
        }
    }
    private static void recoveredWait() throws Exception {
        for (boolean failUpload : new boolean[]{false, true}) {
            PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
            PurplePlaylistsWorker worker = fixture.worker(0);
            try {
                seed(worker, EDIT, bytes("{\"sync\":true,\"unsynced_since\":50}"));
                Client client = new Client(fixture, EMPTY); client.recoveredTransient = true;
                client.transientKind = PurpleRpcRetry.Kind.Flood; client.transientDeadline = 2000;
                if (failUpload) client.mode = 1;
                List<PurplePlaylistsSyncActor.Result> results = new ArrayList<>();
                // The upload failure below is permanent, after the discovery retry recovered.
                if (failUpload) client.clearUploadTransient = true;
                run(worker, client, true, results); client.pump(() -> !results.isEmpty());
                check(results.get(0).status == (failUpload ? PurplePlaylistsSyncActor.Status.Failed : PurplePlaylistsSyncActor.Status.Ready)
                        && results.get(0).retryAt == 0, "recovered short wait cannot misclassify later outcome");
            } finally { fixture.stop(worker); }
        }
    }
    private static void deleteCancellation() throws Exception {
        PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
        PurplePlaylistsWorker worker = fixture.worker(0);
        try {
            seed(worker, EDIT, bytes("{\"sync\":true,\"unsynced_since\":50}"));
            Client client = new Client(fixture, EMPTY); client.holdDelete = true;
            List<PurplePlaylistsSyncActor.Result> results = new ArrayList<>();
            PurplePlaylistsSyncActor actor = run(worker, client, true, results);
            client.pump(() -> client.heldDelete != null);
            actor.close(); client.pump(() -> !results.isEmpty());
            PurplePlaylistsSyncActor.Result result = results.get(0);
            check(result.status == PurplePlaylistsSyncActor.Status.OutcomeUnknown && result.deleteIssued && result.sendIssued,
                    "cancel after actual deletion request retains both issued operations");
            check(result.prune != null && result.prune.deleted == 0 && result.prune.status == PurplePlaylistsSyncPrune.Status.OutcomeUnknown,
                    "unfinished deletion has immutable partial prune result");
            check(result.stateCommitted && result.stateMayHaveChanged, "canceled actor keeps earlier durable commit truth");
            client.heldDelete.onReply(PurpleSyncAutoCore.Outcome.Deleted); actor.close(); client.pump(() -> results.size() == 1);
            check(results.size() == 1 && result.prune.deleted == 0, "late delete reply cannot mutate published result or settle twice");
        } finally { fixture.stop(worker); }
    }
    private static void pendingStoreCancellation() throws Exception {
        PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
        PurplePlaylistsWorker worker = fixture.worker(0);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            seed(worker, EDIT, bytes("{\"sync\":true}"));
            fixture.hook.set(() -> { entered.countDown(); try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("bounded pending store release");
            } catch (InterruptedException error) { throw new AssertionError(error); } });
            Client client = new Client(fixture, EMPTY); List<PurplePlaylistsSyncActor.Result> results = new ArrayList<>();
            PurplePlaylistsSyncActor actor = run(worker, client, true, results);
            check(entered.await(10, TimeUnit.SECONDS), "actual actor store action entered");
            actor.close(); check(results.isEmpty(), "cancel waits for admitted store action to settle");
            release.countDown(); client.pump(() -> !results.isEmpty());
            check(results.size() == 1 && results.get(0).status == PurplePlaylistsSyncActor.Status.Canceled && !results.get(0).stateMayHaveChanged,
                    "pending read cancellation completes once with final no-write truth");
        } finally { release.countDown(); fixture.stop(worker); }
    }
    private static void schedule() {
        final class Port implements PurplePlaylistsSyncSchedule.Port {
            long clock = 1000, delay; boolean foreground = true, current = true; int calls; Runnable task;
            public long now() { return clock; }
            public boolean current() { return current; }
            public boolean foreground() { return foreground; }
            public void post(Runnable action, long delay) { task = action; this.delay = delay; }
            public void cancel(Runnable action) { if (task == action) task = null; }
            public void sync() { ++calls; }
            void fire() { Runnable action = task; task = null; action.run(); }
        }
        Port port = new Port(); PurplePlaylistsSyncSchedule schedule = new PurplePlaylistsSyncSchedule(port);
        schedule.resume(); check(port.delay == 900000, "open-client fifteen-minute backstop");
        schedule.edited(1000); check(port.delay == 60000, "edit quiet period is sixty seconds");
        Runnable stale = port.task; port.clock = 2000; schedule.edited(1000);
        check(port.delay == 60000, "a later edit restarts quiet period"); stale.run(); check(port.calls == 0, "canceled scheduler callback inert");
        port.clock = 590000; schedule.edited(1000); check(port.delay == 11000, "earliest unsynced caps repeated editing at ten minutes");
        port.clock = 601000; port.fire(); check(port.calls == 1, "maximum-age deadline starts one sync");
        port.clock = 601001; schedule.edited(601001); check(port.task == null, "busy sync never overlaps");
        schedule.complete(); check(port.delay == 60000, "edits during a sync survive completion");
        port.foreground = false; schedule.suspend(); schedule.chatUpdated(); check(port.task == null, "background client has no network timer");
        port.foreground = true; schedule.resume(); check(port.delay == 1, "bound-chat update resumes immediately in foreground");
        port.fire(); check(port.calls == 2, "chat event dispatches one coalesced sync"); schedule.complete();
        stale = port.task; schedule.close(); stale.run(); check(port.calls == 2 && port.task == null, "closed account cancels its scheduler");
    }
    private static void checkpointResume() throws Exception {
        PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
        PurplePlaylistsWorker worker = fixture.worker(0);
        try {
            seed(worker, EMPTY, bytes("{\"sync\":true,\"unsynced_since\":50}"));
            Client interrupted = new Client(fixture, EDIT); interrupted.interruptHistory = true;
            List<PurplePlaylistsSyncActor.Result> results = new ArrayList<>();
            run(worker, interrupted, true, results); interrupted.pump(() -> !results.isEmpty());
            PurplePlaylistsSyncActor.Result failed = results.get(0); byte[][] persisted = saved(worker);
            check(failed.status == PurplePlaylistsSyncActor.Status.Failed && failed.libraryCommitted && failed.stateCommitted,
                    "interrupted full scan preserves actual earlier durable record merge");
            check(!failed.sendIssued && !failed.deleteIssued && interrupted.sends == 0 && interrupted.remote.containsKey(41),
                    "incomplete coverage never publishes or deletes");
            check(Arrays.equals(persisted[0], PurplePlaylistsCore.canonicalize(EDIT, 101).canonicalBytes())
                    && object(persisted[1]).getJSONObject("memo").has("41"), "paired checkpoint survives scan interruption");
            check(!"synced".equals(object(persisted[1]).getJSONObject("status").getString("result")), "interrupted scan remains visibly unsynced");
            Client resumed = new Client(fixture, EDIT); List<PurplePlaylistsSyncActor.Result> complete = new ArrayList<>();
            run(worker, resumed, true, complete); resumed.pump(() -> !complete.isEmpty());
            check(complete.get(0).status == PurplePlaylistsSyncActor.Status.Ready && complete.get(0).commit.synced,
                    "fresh actor completes full coverage using durable record receipts");
            check(resumed.downloads == 0 && resumed.sends == 0, "rescan uses validated durable memo without repeated bodies or send");
            check(!object(saved(worker)[1]).has("unsynced_since"), "full receipt rescan alone clears earliest unsynced age");
        } finally { fixture.stop(worker); }
    }
    public static void main(String[] args) throws Exception {
        checkpointResume();
        schedule(); typedOutcomes(); lifecycle(); unknown(); cancelAndDisabled(); emptyAndDeadline(); transportStates(); managedWaitWithoutReply(); committedWriteCancellation(); recoveredWait(); deleteCancellation(); pendingStoreCancellation();
        System.out.println("PLAYLIST_SYNC_ACTOR_CHECKS=" + checks); System.out.println("PLAYLIST_SYNC_ACTOR_OK");
    }
}
