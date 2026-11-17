package org.telegram.messenger.purple;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;

final class PurplePlaylistsSyncRuntime implements AutoCloseable {
    private static final Map<Integer, PurplePlaylistsSyncRuntime> accounts = new HashMap<>();
    private final PurplePlaylistsWorker worker;
    private final PurplePlaylistsAccount.Captured account;
    private final PurplePlaylistsSyncSchedule schedule;
    private final AutoCloseable edits;
    private final NotificationCenter.NotificationCenterDelegate updates;
    private Request active;
    private boolean closed;

    static void attach(PurplePlaylistsWorker worker, PurplePlaylistsAccount.Captured captured) {
        AndroidUtilities.runOnUIThread(() -> {
            int slot = captured.identity.slot;
            PurplePlaylistsSyncRuntime previous = accounts.get(slot);
            if (previous != null && previous.worker == worker && previous.current()) return;
            if (previous != null) previous.close();
            if (captured.current() && worker.matches(captured)) accounts.put(slot, new PurplePlaylistsSyncRuntime(worker, captured));
        });
    }
    static void sessionReady(int slot) { PurplePlaylistsWorkers.acquire(slot); }
    static void foreground() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; ++slot)
                if (UserConfig.getInstance(slot).isClientActivated()) sessionReady(slot);
            for (PurplePlaylistsSyncRuntime runtime : new java.util.ArrayList<>(accounts.values())) {
                if (runtime.current()) runtime.schedule.resume(); else runtime.close();
            }
        });
    }
    static void background() {
        AndroidUtilities.runOnUIThread(() -> {
            for (PurplePlaylistsSyncRuntime runtime : new java.util.ArrayList<>(accounts.values())) {
                runtime.schedule.suspend();
                if (runtime.active != null) runtime.active.close();
            }
        });
    }
    static AutoCloseable manual(PurplePlaylistsWorker worker, Consumer<PurplePlaylistsSyncResult> done) {
        PurplePlaylistsSyncRuntime runtime = accounts.get(worker.accountSlot());
        if (runtime == null || runtime.worker != worker || !runtime.current()) {
            done.accept(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.Unavailable, "SyncAccountUnavailable"));
            return () -> { };
        }
        return runtime.check(true, done);
    }
    static void stop(int slot) {
        AndroidUtilities.runOnUIThread(() -> { PurplePlaylistsSyncRuntime runtime = accounts.get(slot); if (runtime != null) runtime.close(); });
    }
    private PurplePlaylistsSyncRuntime(PurplePlaylistsWorker worker, PurplePlaylistsAccount.Captured account) {
        this.worker = worker; this.account = account;
        schedule = new PurplePlaylistsSyncSchedule(new PurplePlaylistsSyncSchedule.Port() {
            public long now() { return System.currentTimeMillis(); }
            public boolean current() { return PurplePlaylistsSyncRuntime.this.current(); }
            public boolean foreground() { return eligible(); }
            public void post(Runnable task, long delay) { AndroidUtilities.runOnUIThread(task, delay); }
            public void cancel(Runnable task) { AndroidUtilities.cancelRunOnUIThread(task); }
            public void sync() { check(false, value -> schedule.complete()); }
        });
        edits = worker.observeEdits(() -> AndroidUtilities.runOnUIThread(this::loadDue));
        updates = (id, slot, args) -> {
            if (!eligible() || args.length < 1 || !(args[0] instanceof Long)) return;
            PurpleSyncChatCore.Peer chat = PurpleSyncChat.boundChat(account.identity.slot);
            if (chat != null && (Long) args[0] == PurpleSyncChatIO.dialogId(chat, account.identity.userId)) schedule.chatUpdated();
        };
        NotificationCenter center = NotificationCenter.getInstance(account.identity.slot);
        center.addObserver(updates, NotificationCenter.didReceiveNewMessages);
        center.addObserver(updates, NotificationCenter.replaceMessagesObjects);
        loadDue(); schedule.chatUpdated(); schedule.resume();
    }
    private boolean current() { return !closed && worker.matches(account) && account.current(); }
    private boolean eligible() { return current() && !ApplicationLoader.mainInterfacePaused
            && !SharedConfig.appLocked && !SharedConfig.isWaitingForPasscodeEnter; }
    private void loadDue() {
        if (!current()) { close(); return; }
        worker.postStore(null, (store, self) -> {
            PurplePlaylistsStore.Read read = store.read();
            if (!read.ready()) return;
            JSONObject local = new JSONObject(new String(read.state(), java.nio.charset.StandardCharsets.UTF_8));
            long earliest = local.optLong("unsynced_since"); boolean enabled = local.optBoolean("sync", true);
            AndroidUtilities.runOnUIThread(() -> {
                if (!current()) return;
                if (enabled && earliest > 0) schedule.edited(earliest);
                else if (!enabled) schedule.suspend();
            });
        }, () -> { }, false);
    }
    private Request check(boolean manual, Consumer<PurplePlaylistsSyncResult> done) {
        if (!eligible() || active != null) {
            done.accept(new PurplePlaylistsSyncResult(active != null ? PurplePlaylistsSyncResult.Status.Busy
                    : PurplePlaylistsSyncResult.Status.Unavailable, active != null ? "SyncBusy" : "SyncAccountUnavailable"));
            return null;
        }
        Request request = new Request(done);
        active = request;
        AndroidUtilities.runOnUIThread(request.deadline, 30000);
        AndroidUtilities.runOnUIThread(request.watch, 1000);
        boolean admitted = worker.postStore(null, (store, self) -> {
            PurplePlaylistsStore.Read read = store.read();
            boolean enabled = read.ready() && new JSONObject(new String(read.state(), java.nio.charset.StandardCharsets.UTF_8)).optBoolean("sync", true);
            AndroidUtilities.runOnUIThread(() -> {
                if (!request.resolving()) return;
                if (!read.ready() || !enabled && !manual) {
                    request.finish(new PurplePlaylistsSyncResult(read.ready() ? PurplePlaylistsSyncResult.Status.Disabled
                            : PurplePlaylistsSyncResult.Status.Unavailable, read.ready() ? "SyncOff" : "SyncStoreUnavailable"));
                    return;
                }
                PurpleSyncChat.ensureResolved(account.identity.slot, manual, status -> {
                    if (!request.resolving()) return;
                    if (status == null || status.chat == null || !status.readable(status.chat)) {
                        request.finish(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.Refused, "SyncChatUnavailable")); return;
                    }
                    PurpleSyncClient client = PurpleSyncChat.boundPlaylistsClient(account.identity.slot, status.chat, this::current);
                    if (client == null) {
                        request.finish(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.Refused, "SyncRouteUnavailable")); return;
                    }
                    PurpleSyncTransport.PostAuthority authority = PurpleSyncChat.playlistAuthority(account.identity.slot, client.origin(), status.chat,
                            () -> current() && client.isActive());
                    request.route = client::isActive;
                    request.actor = new PurplePlaylistsSyncActor(worker, client, authority, this::current, new PurplePlaylistsSyncActor.Scheduler() {
                        public long now() { return System.currentTimeMillis(); }
                        public void post(Runnable task, long delay) { AndroidUtilities.runOnUIThread(task, delay); }
                        public void cancel(Runnable task) { AndroidUtilities.cancelRunOnUIThread(task); }
                    });
                    AndroidUtilities.cancelRunOnUIThread(request.deadline);
                    request.actor.start(manual, result -> AndroidUtilities.runOnUIThread(() -> request.finish(new PurplePlaylistsSyncResult(result))));
                });
            });
        }, () -> AndroidUtilities.runOnUIThread(() -> {
            if (request.resolving()) request.finish(new PurplePlaylistsSyncResult(
                    PurplePlaylistsSyncResult.Status.Unavailable, "SyncStoreUnavailable"));
        }), false);
        if (!admitted) request.finish(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.Busy, "SyncStoreBusy"));
        return request;
    }
    private final class Request implements AutoCloseable {
        private final Consumer<PurplePlaylistsSyncResult> done;
        private PurplePlaylistsSyncActor actor;
        private java.util.function.BooleanSupplier route = () -> true;
        private boolean finished, canceling;
        private final Runnable deadline = () -> {
            if (!finished && actor == null) finish(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.TimedOut, "SyncResolutionDeadline"));
        };
        private final Runnable watch = new Runnable() {
            public void run() {
                if (finished) return;
                if (!eligible() || !route.getAsBoolean()) { close(); return; }
                AndroidUtilities.runOnUIThread(this, 1000);
            }
        };
        Request(Consumer<PurplePlaylistsSyncResult> done) { this.done = done; }
        boolean resolving() {
            if (finished || canceling || actor != null || active != this) return false;
            if (!eligible()) { close(); return false; }
            return true;
        }
        void finish(PurplePlaylistsSyncResult result) {
            if (finished) return;
            finished = true;
            AndroidUtilities.cancelRunOnUIThread(deadline); AndroidUtilities.cancelRunOnUIThread(watch);
            if (active == this) active = null;
            done.accept(result);
        }
        @Override public void close() {
            if (finished || canceling) return;
            canceling = true;
            AndroidUtilities.cancelRunOnUIThread(deadline); AndroidUtilities.cancelRunOnUIThread(watch);
            if (actor != null) actor.close();
            else finish(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.Canceled, "SyncCanceled"));
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; schedule.close();
        if (active != null) active.close();
        try { edits.close(); } catch (Exception ignored) { }
        NotificationCenter center = NotificationCenter.getInstance(account.identity.slot);
        center.removeObserver(updates, NotificationCenter.didReceiveNewMessages);
        center.removeObserver(updates, NotificationCenter.replaceMessagesObjects);
        accounts.remove(account.identity.slot, this);
    }
}
