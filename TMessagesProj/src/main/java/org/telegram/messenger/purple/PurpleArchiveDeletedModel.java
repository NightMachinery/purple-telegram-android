package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;

public final class PurpleArchiveDeletedModel implements AutoCloseable {
    public interface Delivered { void page(PurpleArchiveQueries.Page page); }
    private static final Set<PurpleArchiveDeletedModel> models = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final java.util.concurrent.ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r,"PurpleArchiveDeletedMonitor"); thread.setDaemon(true); return thread;
    });
    static { monitor.scheduleWithFixedDelay(PurpleArchiveDeletedModel::tick,1,1,TimeUnit.SECONDS); }
    public final PurpleArchiveQueries.Scope scope;
    final PurpleArchive.Worker worker;
    final LongSupplier clock;
    final int order;
    private volatile PurpleArchiveStore store;
    private volatile String login;
    private volatile PurpleArchiveQueries query;
    private final AtomicBoolean stopped = new AtomicBoolean(), fetching = new AtomicBoolean(), closing = new AtomicBoolean(), checking = new AtomicBoolean();
    private volatile String state = "Opening";
    private volatile Runnable revoked;
    public PurpleArchiveDeletedModel(PurpleArchiveQueries.Scope scope) { this(scope,null,null,null,0,System::nanoTime); }
    PurpleArchiveDeletedModel(PurpleArchiveQueries.Scope scope, PurpleArchive.Worker expected, PurpleArchiveStore store, String login, int order, LongSupplier clock) {
        this.scope = scope; this.clock = clock; this.order = order; this.store = store; this.login = login;
        if (!selected(scope)) throw new IllegalStateException("Archive selection changed");
        worker = expected == null ? PurpleArchive.queryWorker(scope.identity) : expected;
        if (worker == null || worker.retired || worker.closing || worker != PurpleArchive.existing(scope.identity)) throw new IllegalStateException("Archive opening worker changed");
        synchronized (models) { if (models.size() >= 16) throw new IllegalStateException("Archive query Busy"); models.add(this); }
    }
    private static boolean selected(PurpleArchiveQueries.Scope scope) {
        return scope.policy.getAsBoolean() && scope.identity.matchesSlot() && UserConfig.selectedAccount == scope.identity.slot && scope.selection == PurpleArchiveViewerModel.selectionGeneration();
    }
    private boolean memory() {
        return !stopped.get() && selected(scope) && !worker.closing && !worker.retired && worker == PurpleArchive.existing(scope.identity)
            && (store == null || worker.store == store && login != null && login.equals(worker.loginContext));
    }
    public boolean available() {
        if (!memory() || query != null && query.expired()) { invalidate("Stale"); return false; } return true;
    }
    public String state() { return state; }
    public boolean refreshSuggested() { PurpleArchiveQueries retained = query; return retained != null && retained.refreshSuggested; }
    public void onRevoked(Runnable action) { revoked = action; if (stopped.get()) AndroidUtilities.runOnUIThread(action); }
    public boolean next(Delivered delivered) { return next(16,delivered); }
    boolean next(int limit, Delivered delivered) {
        if (delivered == null || !available() || !fetching.compareAndSet(false,true)) return false;
        try { worker.queue.execute(() -> read(limit,delivered)); return true; }
        catch (java.util.concurrent.RejectedExecutionException e) { fetching.set(false); invalidate("Busy"); return false; }
    }
    private void read(int limit, Delivered delivered) {
        PurpleArchiveQueries.Page page;
        try {
            if (!memory()) throw new PurpleArchiveStore.Failure("Archive query stale");
            if (store == null) {
                if (worker.store == null) worker.open(scope.identity);
                if (!memory() || worker.store == null || !worker.status.state.equals("Ready")) throw new PurpleArchiveStore.Failure(worker.status.state);
                store = worker.store; login = worker.loginContext;
            }
            if (!memory()) throw new PurpleArchiveStore.Failure("Archive query stale");
            if (query == null) {
                query = new PurpleArchiveQueries(store,scope,login,order,worker.status.bytes,clock);
                query.open();
            }
            page = query.next(limit); state = "Ready";
        } catch (Exception | LinkageError e) { String failure = failure(e); invalidate(failure); page = PurpleArchiveQueries.Page.failure(failure); }
        PurpleArchiveQueries.Page result = page;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (!selected(scope)) return;
                if (result.status.equals("Ready") && !available()) delivered.page(PurpleArchiveQueries.Page.failure(state));
                else delivered.page(result);
            } finally { fetching.set(false); }
        });
    }
    public static String failure(Throwable error) {
        String reason = error.getMessage() == null ? "" : error.getMessage();
        if (reason.contains("SchemaMismatch")) return "SchemaMismatch";
        if (reason.contains("ReadOnly")) return "ReadOnly";
        if (reason.contains("limit") || reason.contains("Busy")) return "Busy";
        if (reason.contains("stale") || reason.contains("revoked")) return "Stale";
        return "Unavailable";
    }
    public PurpleArchiveDeletedModel restart() {
        if (!selected(scope) || worker.retired || worker.closing || worker != PurpleArchive.existing(scope.identity)
            || store != null && (worker.store != store || !login.equals(worker.loginContext))) throw new IllegalStateException("Archive opening changed");
        close(); return new PurpleArchiveDeletedModel(scope,worker,store,login,order,clock);
    }
    public PurpleArchiveViewerModel history(PurpleArchiveQueries.Entry entry) {
        PurpleArchiveQueries opening = query;
        if (!available() || opening == null || entry == null || !entry.body || entry.store != store || !entry.query.equals(opening.token)) return null;
        PurpleArchiveViewerModel history = PurpleArchiveViewerModel.pinned(scope.identity,entry.key,scope.selection,worker,store,login,entry.version,entry.versionDigest,entry.accountGeneration,entry.chatGeneration,scope.policy);
        if (!available() || query != opening) { if (history != null) history.close(); return null; } return history;
    }
    public boolean copy(PurpleArchiveQueries.Entry entry, java.util.function.BooleanSupplier active, java.util.function.Consumer<String> delivered) {
        PurpleArchiveViewerModel history = history(entry);
        if (history == null) return false;
        return history.copyPinned(() -> available() && active.getAsBoolean(), delivered);
    }

    private void invalidate(String reason) {
        if (stopped.compareAndSet(false,true)) {
            state = reason; Runnable action = revoked; if (action != null) AndroidUtilities.runOnUIThread(action);
        }
        requestClose();
    }
    private void requestClose() {
        PurpleArchiveQueries old = query;
        if ((old == null && store == null) || worker.retired && (old == null || old.reader.released())) { models.remove(this); return; }
        if (closing.compareAndSet(false,true)) worker.control(() -> {
            try {
                if (query != null) { query.close(); query = null; }
                if (store != null && !store.closed()) store.retryClosingReaders();
                models.remove(this);
            } catch (Exception | LinkageError e) { PurpleArchive.captureGap(scope.identity,"archive_query_close_pending"); }
            finally { closing.set(false); }
        });
    }
    @Override public void close() { invalidate("Stale"); }
    static void revoke(int slot) { for (PurpleArchiveDeletedModel model : new ArrayList<>(models)) if (slot < 0 || model.scope.identity.slot == slot) model.close(); }
    static void committed(PurpleArchiveStore store) {
        for (PurpleArchiveDeletedModel model : models) if (model.store == store && !model.stopped.get() && model.query != null && !model.query.current()) model.invalidate("Stale");
    }
    private static void tick() {
        for (PurpleArchiveDeletedModel model : new ArrayList<>(models)) {
            if (!model.available()) { model.requestClose(); continue; }
            if (model.query != null && model.checking.compareAndSet(false,true)) model.worker.control(() -> {
                try { if (!model.memory() || model.query != null && !model.query.current()) model.invalidate("Stale"); }
                catch (Exception | LinkageError e) { model.invalidate("Unavailable"); }
                finally { model.checking.set(false); }
            });
        }
    }
}
