package org.telegram.messenger.purple;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

final class PurplePlaylistsSyncActor implements AutoCloseable {
    enum Status { Ready, Disabled, Refused, Offline, Wait, Failed, OutcomeUnknown, Canceled, TimedOut }
    interface Scheduler {
        long now();
        void post(Runnable task, long delay);
        void cancel(Runnable task);
    }
    static final class Writes {
        final PurplePlaylistsStore.Status status;
        final boolean libraryCommitted, stateCommitted, libraryMayHaveChanged, stateMayHaveChanged;
        private Writes(PurplePlaylistsStore.Write write) {
            status = write == null ? null : write.status;
            libraryCommitted = write != null && write.libraryCommitted;
            stateCommitted = write != null && write.stateCommitted;
            libraryMayHaveChanged = write != null && write.libraryMayHaveChanged;
            stateMayHaveChanged = write != null && write.stateMayHaveChanged;
        }
    }
    static final class Result {
        final Status status;
        final String detail;
        final PurplePlaylistsSyncCommit.Result commit;
        final boolean sendIssued, deleteIssued, libraryCommitted, stateCommitted, libraryMayHaveChanged, stateMayHaveChanged;
        final long retryAt;
        final Writes checkpoint;
        final PurplePlaylistsSyncPrune.Result prune;
        private Result(PurplePlaylistsSyncActor owner) {
            status = owner.finalStatus; detail = owner.finalDetail; commit = owner.committed;
            sendIssued = owner.sendIssued; prune = owner.pruned;
            deleteIssued = prune != null && prune.deleteIssued; retryAt = owner.retryAt;
            checkpoint = owner.checkpoint; libraryCommitted = owner.libraryCommitted;
            stateCommitted = owner.stateCommitted; libraryMayHaveChanged = owner.libraryMayHaveChanged;
            stateMayHaveChanged = owner.stateMayHaveChanged;
        }
    }
    private final PurplePlaylistsWorker worker;
    private final PurpleSyncClient client;
    private final PurpleSyncTransport.PostAuthority authority;
    private final BooleanSupplier scope;
    private final Scheduler scheduler;
    private final PurpleSyncChatOrigin origin;
    private final PurpleSyncChatCore.Peer chat;
    private final AtomicBoolean started = new AtomicBoolean(), finished = new AtomicBoolean();
    private final Runnable timeout = () -> finish(Status.TimedOut, "SyncDeadline");
    private Consumer<Result> done;
    private Status finalStatus;
    private String finalDetail;
    private int pendingStores;
    private boolean delivered, libraryCommitted, stateCommitted, libraryMayHaveChanged, stateMayHaveChanged;
    private Writes checkpoint;
    private volatile long retryAt;
    private volatile PurpleRpcRetry.Transient transientFailure;
    private volatile PurplePlaylistsSyncDiscovery discovery;
    private volatile PurplePlaylistsSyncPublication publication;
    private volatile PurplePlaylistsSyncCommit.Result committed;
    private volatile boolean sendIssued;
    private boolean manual, pruningFinished;
    private volatile boolean failing;
    private volatile PurplePlaylistsSyncPrune pruning;
    private volatile PurplePlaylistsSyncPrune.Result pruned;

    PurplePlaylistsSyncActor(PurplePlaylistsWorker worker, PurpleSyncClient client,
            PurpleSyncTransport.PostAuthority authority, BooleanSupplier scope, Scheduler scheduler) {
        if (worker == null || client == null || authority == null || scope == null || scheduler == null
                || client.origin() == null || worker.accountSlot() != client.origin().slot)
            throw new IllegalArgumentException("MissingPlaylistSyncActorRoute");
        this.worker = worker; this.client = client; this.authority = authority;
        this.scope = scope; this.scheduler = scheduler; origin = client.origin(); chat = client.chat();
    }
    private boolean current() {
        return !finished.get() && scope.getAsBoolean() && worker.downloadCurrent() && client.isActive()
                && origin.equals(client.origin()) && chat.equals(client.chat());
    }
    private void network(Runnable action) {
        client.runOnWorker(() -> {
            if (finished.get() || failing) return;
            if (!current()) { finish(Status.Refused, "SyncRouteChanged"); return; }
            try { action.run(); }
            catch (Exception error) { finish(Status.Failed, "SyncTransportFailed"); }
        });
    }
    void start(boolean manual, Consumer<Result> done) {
        if (done == null || finished.get() || !started.compareAndSet(false, true)) throw new IllegalArgumentException("PlaylistSyncAlreadyStarted");
        this.done = done; this.manual = manual;
        client.playlistTransientWait(value -> {
            if (!current() || value == null) return;
            transientFailure = value; retryAt = Math.max(retryAt, value.retryAt);
            if (!value.shortRetry) network(() -> failure(Status.Failed, "SyncTransport" + value.kind));
        });
        scheduler.post(timeout, 10 * 60 * 1000L);
        read();
    }
    private void read() {
        if (!current()) { finish(Status.Refused, "SyncRouteChanged"); return; }
        if (!store((store, self) -> {
            if (!current() || self != origin.userId) { finish(Status.Refused, "SyncAccountChanged"); return; }
            PurplePlaylistsStore.Read read = store.read();
            if (!read.ready()) { finish(Status.Refused, "SyncStoreUnavailable"); return; }
            JSONObject state = new JSONObject(new String(read.state(), java.nio.charset.StandardCharsets.UTF_8));
            if (!manual && !state.optBoolean("sync", true)) { finish(Status.Disabled, "SyncOff"); return; }
            long waiting = PurplePlaylistsLocalCore.retryAt(read.state());
            if (waiting > scheduler.now()) {
                retryAt = waiting;
                finish("offline".equals(state.getJSONObject("status").optString("result")) ? Status.Offline : Status.Wait, "SyncRetryAfter"); return;
            }
            PurplePlaylistsPublicationJournal journal = new PurplePlaylistsPublicationJournal(store.media(), origin, chat, this::current);
            PurplePlaylistsPublicationJournal.Read intent = journal.read();
            if (intent.status != PurplePlaylistsPublicationJournal.Status.Empty
                    && intent.status != PurplePlaylistsPublicationJournal.Status.Retired && !intent.unresolved()) {
                finish(Status.Refused, "PublicationJournal" + intent.status); return;
            }
            if (intent.unresolved()) state.remove("memo");
            byte[] library = read.library(), local = state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            network(() -> {
                discovery = new PurplePlaylistsSyncDiscovery(client, this::current, pruned != null && pruned.deleted > 0);
                discovery.checkpoint((request, body, accepted) -> {
                    Runnable refused = () -> accepted.accept(null);
                    if (!store((latest, user) -> {
                        if (!current() || user != origin.userId) { refused.run(); return; }
                        PurplePlaylistsSyncCommit.Result result = PurplePlaylistsSyncCommit.checkpoint(
                                latest, request, body, user, scheduler.now(), this::current);
                        synchronized (this) { checkpoint = new Writes(result.write); record(result.write); }
                        worker.syncChanged(result.write);
                        accepted.accept(result.status == PurplePlaylistsSyncCommit.Status.Ready ? result.plan : null);
                    }, refused)) refused.run();
                });
                discovery.start(library, local, manual, scheduler.now(), found -> network(() -> commit(found, null)));
            });
        }, () -> finish(Status.Refused, "SyncWorkerUnavailable"))) finish(Status.Refused, "SyncWorkerBusy");
    }
    private void commit(PurplePlaylistsSyncDiscovery.Result found, PurplePlaylistsSyncPublication.Result published) {
        synchronized (this) { if (finished.get()) return; ++pendingStores; }
        PurplePlaylistsSyncCommit.onWorker(worker, origin, chat, found, published, manual, scheduler.now(),
                this::current, result -> {
                    synchronized (this) { committed = result; record(result.write); }
                    try { network(() -> {
                    if (result.status != PurplePlaylistsSyncCommit.Status.Ready) {
                        finish(result.status == PurplePlaylistsSyncCommit.Status.RetirementPending
                                ? Status.OutcomeUnknown : Status.Failed, result.status.name()); return;
                    }
                    if (published == null && !"None".equals(found.error)) {
                        failure(Status.Failed, found.error); return;
                    }
                    transientFailure = null; retryAt = 0;
                    if (published != null || result.synced || result.plan == null
                            || !"Required".equals(result.plan.detail().optString("publication"))) {
                        ready(result); return;
                    }
                    if (!"None".equals(found.error) || !result.plan.detail().optBoolean("discoveryComplete")) {
                        finish(Status.Failed, "IncompleteDiscovery"); return;
                    }
                    publication = client.playlistPublication(authority, this::current);
                    if (publication == null) { finish(Status.Refused, "PublicationUnavailable"); return; }
                    long random; SecureRandom entropy = new SecureRandom(); do { random = entropy.nextLong(); } while (random == 0);
                    publication.start(result.plan.publication(), random,
                            PurplePlaylistsPublicationJournal.onWorker(worker, origin, chat, this::current), reply -> network(() -> {
                                sendIssued |= reply.sendIssued;
                                if (reply.status != PurplePlaylistsSyncPublication.Status.Confirmed) {
                                    failure(reply.status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown
                                            ? Status.OutcomeUnknown : Status.Failed, reply.error); return;
                                }
                                commit(found, reply);
                            }));
                    }); } finally { storeEnded(); }
                });
    }
    private void ready(PurplePlaylistsSyncCommit.Result result) {
        org.json.JSONObject prune = result.plan == null ? null : result.plan.detail().optJSONObject("prune");
        org.json.JSONArray targets = prune == null ? null : prune.optJSONArray("targets");
        if (pruningFinished || targets == null || targets.length() == 0) {
            finish(Status.Ready, result.synced ? "Synced" : result.plan == null
                    ? "Unchanged" : result.plan.detail().optString("publication")); return;
        }
        pruningFinished = true;
        pruning = new PurplePlaylistsSyncPrune(client, authority, this::current, scheduler);
        pruning.start(result.plan.detail(), reply -> network(() -> {
            pruned = reply;
            if (reply.status != PurplePlaylistsSyncPrune.Status.Ready) {
                failure(reply.status == PurplePlaylistsSyncPrune.Status.OutcomeUnknown
                        ? Status.OutcomeUnknown : Status.Failed, "Prune" + reply.status); return;
            }
            if (reply.deleted == 0) { ready(result); return; }
            PurplePlaylistsSyncDiscovery prior = discovery;
            if (prior != null) prior.close();
            read();
        }));
    }
    private void failure(Status requested, String detail) {
        synchronized (this) { if (finished.get() || failing) return; failing = true; }
        PurplePlaylistsSyncPublication posting = publication;
        PurplePlaylistsSyncPrune removing = pruning;
        if (posting != null && posting.outcomeUnknown() || removing != null && removing.outcomeUnknown()) requested = Status.OutcomeUnknown;
        PurplePlaylistsSyncDiscovery reading = discovery;
        if (reading != null) reading.close();
        if (posting != null) posting.close();
        if (removing != null) removing.close();
        PurpleRpcRetry.Transient waiting = transientFailure;
        Status status = requested == Status.OutcomeUnknown ? requested : waiting == null ? requested
                : waiting.kind == PurpleRpcRetry.Kind.Transport || waiting.kind == PurpleRpcRetry.Kind.Timeout ? Status.Offline : Status.Wait;
        if (waiting != null) retryAt = Math.max(retryAt, waiting.retryAt);
        else retryAt = Math.max(retryAt, client.transientRetryAt());
        if (status == Status.Failed && retryAt > scheduler.now()) status = Status.Wait;
        final Status outcome = status;
        if (!store((store, self) -> {
            if (!current() || self != origin.userId) { finish(Status.Refused, "SyncAccountChanged"); return; }
            PurplePlaylistsStore.Read latest = store.read();
            if (latest.ready()) {
                PurplePlaylistsLocalCore.SyncResult kind = waiting != null
                        ? waiting.kind == PurpleRpcRetry.Kind.Transport || waiting.kind == PurpleRpcRetry.Kind.Timeout
                            ? PurplePlaylistsLocalCore.SyncResult.Offline : PurplePlaylistsLocalCore.SyncResult.Wait
                        : outcome == Status.Wait ? PurplePlaylistsLocalCore.SyncResult.Wait : PurplePlaylistsLocalCore.SyncResult.Failed;
                PurplePlaylistsLocalCore.Result state = PurplePlaylistsLocalCore.checkpoint(latest.state(), kind,
                        scheduler.now(), retryAt, self);
                if (state.valid() && current()) {
                    PurplePlaylistsStore.Write write = store.write(latest, latest.library(), state.canonicalBytes(),
                            PurplePlaylistsStore.Kind.Merge, this::current);
                    synchronized (this) { checkpoint = new Writes(write); record(write); }
                    worker.syncChanged(write);
                }
            }
            finish(outcome, detail);
        }, () -> finish(outcome, detail))) finish(outcome, detail);
    }
    private synchronized void record(PurplePlaylistsStore.Write write) {
        if (write == null) return;
        libraryCommitted |= write.libraryCommitted; stateCommitted |= write.stateCommitted;
        libraryMayHaveChanged |= write.libraryMayHaveChanged; stateMayHaveChanged |= write.stateMayHaveChanged;
    }
    private boolean store(PurplePlaylistsWorker.StoreAction action, Runnable refused) {
        synchronized (this) { if (finished.get()) return false; ++pendingStores; }
        boolean admitted = worker.postStore(null, (store, self) -> {
            try { action.run(store, self); }
            catch (Exception error) { finish(Status.Failed, "SyncStoreFailed"); }
            finally { storeEnded(); }
        }, () -> { try { refused.run(); } finally { storeEnded(); } }, false);
        if (!admitted) storeEnded();
        return admitted;
    }
    private synchronized void storeEnded() { --pendingStores; settle(); }
    private synchronized void settle() {
        if (!finished.get() || pendingStores != 0 || delivered || finalStatus == null) return;
        delivered = true;
        Consumer<Result> reply = done;
        if (reply != null) reply.accept(new Result(this));
    }
    private synchronized void finish(Status status, String detail) {
        if (!finished.compareAndSet(false, true)) return;
        scheduler.cancel(timeout);
        client.playlistTransientWait(null);
        PurplePlaylistsSyncPrune removing = pruning;
        if (removing != null) {
            removing.close(); pruned = removing.snapshot();
            if (pruned.status == PurplePlaylistsSyncPrune.Status.OutcomeUnknown) status = Status.OutcomeUnknown;
        }
        PurplePlaylistsSyncPublication posting = publication;
        if (posting != null) { sendIssued |= posting.sendIssued(); posting.close(); }
        PurplePlaylistsSyncDiscovery reading = discovery;
        if (reading != null) reading.close();
        finalStatus = status; finalDetail = detail;
        settle();
    }
    @Override public void close() { finish(Status.Canceled, "SyncCanceled"); }
}
