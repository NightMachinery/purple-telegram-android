package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;

public final class PurpleArchivePurgeModel implements AutoCloseable {
    public static final class Scope {
        final PurpleAccountFiles.Identity identity;
        final long selection;
        final BooleanSupplier policy;
        final String encoded;
        public final String title, account, aliases;
        private Scope(PurpleAccountFiles.Identity identity, long selection, BooleanSupplier policy, String title, JSONObject value) throws Exception {
            if (identity == null || value.getJSONArray("aliases").length() > 31) throw new IllegalArgumentException("Archive purge scope unavailable");
            this.identity = identity; this.selection = selection; this.policy = policy; this.title = title; account = Long.toString(identity.userId);
            JSONObject scope = new JSONObject().put("chat", value.isNull("chat") ? JSONObject.NULL : value.getJSONObject("chat")).put("aliases", value.getJSONArray("aliases"));
            encoded = scope.toString(); StringBuilder names = new StringBuilder(); JSONArray values = scope.getJSONArray("aliases");
            for (int i = 0; i < values.length(); ++i) { JSONObject chat = values.getJSONObject(i); if (i != 0) names.append(", "); names.append(chat.getInt("kind")).append(':').append(chat.getString("id")); }
            aliases = names.toString();
        }
        public static Scope parent(PurpleArchiveQueries.Scope opening) throws Exception {
            return new Scope(opening.identity, opening.selection, opening.policy, opening.title, opening.value());
        }
        public static Scope account(int slot) throws Exception {
            PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
            int gate = PurpleGate.generation(); long revision = PurpleAccountOwner.observedRevision(), role = PurpleAccountOwner.observedRoleEpoch();
            return new Scope(identity, PurpleArchiveViewerModel.selectionGeneration(), () -> gate == PurpleGate.generation()
                && revision == PurpleAccountOwner.observedRevision() && role == PurpleAccountOwner.observedRoleEpoch(), "", new JSONObject().put("chat", JSONObject.NULL).put("aliases", new JSONArray()));
        }
        JSONObject value() throws Exception { return new JSONObject(encoded); }
        boolean current() {
            return policy.getAsBoolean() && identity.matchesSlot() && identity.current() && UserConfig.selectedAccount == identity.slot
                && selection == PurpleArchiveViewerModel.selectionGeneration() && !org.telegram.messenger.SharedConfig.appLocked
                && !org.telegram.messenger.SharedConfig.isWaitingForPasscodeEnter && !AndroidUtilities.needShowPasscode(false);
        }
    }
    public static final class Preview {
        public final List<Long> counts;
        final String confirmation;
        final PurpleArchiveStore store;
        final String login;
        final long epoch;
        Preview(long[] counts, String confirmation, PurpleArchiveStore store, String login, long epoch) {
            ArrayList<Long> values = new ArrayList<>(); for (long value : counts) values.add(value);
            this.counts = Collections.unmodifiableList(values); this.confirmation = confirmation; this.store = store; this.login = login; this.epoch = epoch;
        }
    }
    public static final class State {
        public final String state, reason;
        public final Preview preview;
        State(String state, String reason, Preview preview) { this.state = state; this.reason = reason; this.preview = preview; }
    }
    public interface Delivered { void result(State state); }
    final Scope scope;
    final PurpleArchive.Worker worker;
    final int kind;
    private volatile PurpleArchiveStore store;
    private volatile String login;
    private volatile long epoch;
    private volatile Preview issued;
    private final AtomicBoolean closed = new AtomicBoolean(), pending = new AtomicBoolean();
    private volatile boolean committed;
    public PurpleArchivePurgeModel(Scope scope, int kind) {
        if (scope == null || kind < 0 || kind > 1 || !scope.current()) throw new IllegalArgumentException("Archive purge opening changed");
        this.scope = scope; this.kind = kind; worker = PurpleArchive.queryWorker(scope.identity);
        if (worker == null || worker.closing || worker.retired) throw new IllegalStateException("Archive purge worker unavailable");
        store = worker.store; login = worker.loginContext; epoch = worker.purgeEpoch;
    }
    boolean memory() { return !closed.get() && scope.current() && !worker.closing && !worker.retired && worker == PurpleArchive.existing(scope.identity)
        && (store == null || worker.store == store && login != null && login.equals(worker.loginContext)); }
    public boolean available() { return memory(); }
    boolean opening() { return memory() && !committed && worker.purgeEpoch == epoch; }
    private void deliver(Delivered delivered, State state) {
        AndroidUtilities.runOnUIThread(() -> { pending.set(false); if (memory()) delivered.result(state); });
    }
    public boolean prepare(Delivered delivered) {
        if (delivered == null || !opening() || !pending.compareAndSet(false, true)) return false;
        issued = null;
        try { worker.queue.execute(() -> {
            State result;
            try {
                if (!opening()) throw new PurpleArchiveStore.Failure("Archive confirmation stale");
                if (store == null) {
                    if (worker.store == null) worker.open(scope.identity);
                    if (!memory() || worker.store == null) throw new PurpleArchiveStore.Failure(worker.status.state);
                    store = worker.store; login = worker.loginContext;
                }
                if (store.maintenance || worker.status.state.startsWith("Maintenance")) { result = new State("Maintenance", worker.status.reason, null); }
                else {
                    if (!worker.status.state.equals("Ready") || PurpleArchiveMaintenance.busy(store)) throw new PurpleArchiveStore.Failure("Archive maintenance Busy");
                    worker.maintenanceDrain();
                    JSONObject confirmation; long[] counts = new long[7];
                    try (PurpleArchiveStore.MaintenanceRead snapshot = store.maintenanceRead()) {
                        if (snapshot.inspection.getBoolean("pending")) throw new PurpleArchiveStore.Failure("Archive maintenance pending");
                        JSONObject plan = PurpleArchiveMaintenance.call(store, "maintenanceQuery", store.arguments().put("query", "counts").put("scope", scope.value()));
                        PurpleArchiveSqlite.Result rows = PurpleArchiveMaintenance.read(snapshot, plan, 7);
                        if (rows.rows != 1) throw new PurpleArchiveStore.Failure("Archive purge counts unavailable");
                        for (int i = 0; i < counts.length; ++i) {
                            if (rows.values[0][i].type != PurpleArchiveSqlite.INTEGER || rows.values[0][i].integer < 0) throw new PurpleArchiveStore.Failure("Archive purge count type");
                            counts[i] = rows.values[0][i].integer;
                        }
                        confirmation = PurpleArchiveMaintenance.call(store, "maintenancePrepare", snapshot.arguments().put("scope", scope.value()).put("kind", kind)).getJSONObject("confirmation");
                    }
                    if (!opening()) throw new PurpleArchiveStore.Failure("Archive confirmation stale");
                    issued = new Preview(counts, confirmation.toString(), store, login, epoch); result = new State("Ready", "", issued);
                }
            } catch (Exception | LinkageError e) { result = new State(PurpleArchiveDeletedModel.failure(e), "purge_review_refused", null); }
            deliver(delivered, result);
        }); return true; }
        catch (java.util.concurrent.RejectedExecutionException e) { pending.set(false); return false; }
    }
    public boolean confirm(Preview preview, Delivered delivered) {
        if (preview == null || preview != issued || preview.store != store || !preview.login.equals(login) || preview.epoch != epoch
            || delivered == null || !opening() || !pending.compareAndSet(false, true)) return false;
        issued = null;
        try { worker.queue.execute(() -> {
            State result; String operation = PurpleArchiveBinding.token();
            try {
                if (!opening() || store.maintenance || !worker.status.state.equals("Ready") || PurpleArchiveMaintenance.busy(store)) throw new PurpleArchiveStore.Failure("Archive confirmation stale");
                worker.maintenanceDrain(); JSONObject plan;
                try (PurpleArchiveStore.MaintenanceRead snapshot = store.maintenanceRead()) {
                    JSONObject arguments = snapshot.arguments().put("scope", scope.value()).put("kind", kind)
                        .put("confirmation", new JSONObject(preview.confirmation)).put("operationId", operation);
                    PurpleArchiveMaintenance.epochs(store, snapshot, arguments, scope.value());
                    plan = PurpleArchiveMaintenance.call(store, "maintenanceBegin", arguments);
                }
                if (!store.executeMaintenance(new PurpleArchiveCore.Program(plan), this::opening).committed) throw new PurpleArchiveStore.Failure("Archive purge not committed");
                committed = true; result = new State("Committed", "purge_in_progress", null);
            } catch (Exception | LinkageError e) {
                result = store.maintenance ? new State("Maintenance", "purge_commit_requires_reconciliation", null)
                    : new State(PurpleArchiveDeletedModel.failure(e), "purge_confirmation_refused", null);
            } finally {
                if (worker.store == store && !worker.closing && !worker.retired && store.maintenance) {
                    worker.maintenanceState("Maintenance", "purge_recovery_pending"); PurpleArchiveMaintenance.resume(worker);
                }
            }
            deliver(delivered, result);
        }); return true; }
        catch (java.util.concurrent.RejectedExecutionException e) { pending.set(false); return false; }
    }
    public boolean retry(Delivered delivered) {
        if (delivered == null || !memory() || !worker.status.state.startsWith("Maintenance") || !pending.compareAndSet(false, true)) return false;
        try { worker.queue.execute(() -> {
            if (memory() && worker.store == store && store != null) { PurpleArchiveMaintenance.resume(worker); deliver(delivered, new State("Committed", "maintenance_retry_queued", null)); }
            else deliver(delivered, new State("Stale", "maintenance_context_changed", null));
        }); return true; }
        catch (java.util.concurrent.RejectedExecutionException e) { pending.set(false); return false; }
    }
    @Override public void close() { closed.set(true); issued = null; }
}
