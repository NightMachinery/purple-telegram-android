package org.telegram.messenger.purple;

import android.os.Build;
import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

public final class PurpleArchive {
    public static final class Status {
        public final String state, bytes;
        public final boolean gap;
        private Status(String state, String bytes, boolean gap) { this.state = state; this.bytes = bytes; this.gap = gap; }
    }
    private static final ConcurrentHashMap<String, Worker> workers = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "PurpleArchiveRecovery"); thread.setDaemon(true); return thread;
    });
    private static volatile boolean initialized;
    private static final java.util.concurrent.atomic.AtomicReferenceArray<PurpleAccountFiles.Identity> revoked = new java.util.concurrent.atomic.AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);
    private static boolean allowed(PurpleAccountFiles.Identity identity) {
        if (identity == null || !identity.current()) return false;
        PurpleAccountFiles.Identity old = revoked.get(identity.slot);
        return old == null || !old.key.equals(identity.key) || old.lifetime != identity.lifetime;
    }
    static final class Worker {
        final String account;
        final Object publication = new Object();
        final ThreadPoolExecutor queue;
        volatile PurpleAccountFiles.Identity identity;
        volatile boolean closing, retired;
        volatile Status status = new Status("Opening", "0", false);
        volatile PurpleArchiveStore store;
        boolean retryScheduled;
        String loginContext;
        String discardSeal;
        final java.util.concurrent.atomic.AtomicLong captureBytes = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean gapScheduled = new java.util.concurrent.atomic.AtomicBoolean();
        volatile long deliveryEpoch;
        Worker(String account) {
            this.account = account;
            queue = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
                Thread thread = new Thread(r, "PurpleArchiveAccount"); thread.setDaemon(true); return thread;
            });
            queue.allowCoreThreadTimeOut(false);
        }
        void control(Runnable task) {
            if (retired) return;
            try { queue.execute(task); }
            catch (java.util.concurrent.RejectedExecutionException e) { if (!retired) recovery.schedule(() -> control(task), 100, TimeUnit.MILLISECONDS); }
        }
        void submit(Runnable task) {
            if (retired) return;
            try { queue.execute(task); }
            catch (java.util.concurrent.RejectedExecutionException e) { status = new Status("Busy", status.bytes, status.gap); }
        }
        void open(PurpleAccountFiles.Identity captured) {
            try {
                if (retired || !allowed(captured)) return;
                if (store != null && (identity == null || identity.lifetime != captured.lifetime || closing)) { store.drainReaders(); store.close(); store = null; }
                identity = captured;
                PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.getFilesDirFixed(), account);
                String context = PurpleArchiveLogin.context(captured.slot);
                if (store != null && !context.equals(loginContext)) { store.drainReaders(); store.close(); store = null; }
                loginContext = context;
                if (paths.metadata.read("removing.json", 65536).bytes != null) {
                    PurpleArchiveRemoval.resume(paths, paths.metadata.read("removing.json", 65536).bytes);
                    closing = true;
                    remove();
                    if (retired || paths.metadata.read("removing.json", 65536).bytes != null) return;
                }
                if (!captured.current()) return;
                if (store != null) {
                    synchronized (publication) {
                        PurpleArchiveCatalogue.requireAdmission(paths, new PurpleArchiveKeys.RemovalProvider());
                        PurpleArchiveLogin.admit(paths, context, new PurpleArchiveKeys.RemovalProvider(), false);
                    }
                }
                closing = false;
                if (store == null) {
                    store = PurpleArchiveStore.open(new PurpleArchiveStore.Environment() {
                        public File files() { return ApplicationLoader.getFilesDirFixed(); }
                        public int api() { return Build.VERSION.SDK_INT; }
                        public boolean current() { return !closing && identity != null && identity.lifetime == captured.lifetime && allowed(captured); }
                        public Object publicationLock() { return publication; }
                        public PurpleArchiveKeys.Provider keys() { return new PurpleArchiveKeys.AndroidProvider(); }
                    }, account, context);
                }
                status = new Status("Opening", status.bytes, status.gap || store.paths.metadata.read("gap-outbox.json", 65536).bytes != null);
                PurpleArchiveSequence.recover(store);
                flushCaptureGap(this);
                PurpleArchiveGaps.flush(store);
                status = new Status("Ready", PurpleArchiveUsage.scan(store), store.hasGaps());
                PurpleArchiveStagingRecovery.start(this);
                PurpleArchiveMediaAccess.recover(this);
            } catch (Exception | LinkageError e) {
                if (store != null) {
                    try { store.drainReaders(); store.close(); store = null; } catch (Exception | LinkageError ignored) {}
                }
                status = new Status(failureState(e), status.bytes, status.gap);
            } finally {
                try { discardSeal = PurpleArchiveDiscard.seal(new PurpleArchivePaths(ApplicationLoader.getFilesDirFixed(), account)); }
                catch (Exception | LinkageError e) { discardSeal = null; }
            }
        }
        void retire() {
            ++deliveryEpoch; retired = true; identity = null; workers.remove(account, this); queue.shutdown();
        }
        void remove() { remove(null); }
        void remove(String expected) {
            try {
                PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.getFilesDirFixed(), account);
                if (store != null) {
                    if (expected != null && !expected.equals(PurpleArchiveDiscard.seal(paths))) throw new PurpleArchiveStore.Failure("Archive discard changed");
                    PurpleArchiveRemoval pending;
                    synchronized (publication) { pending = expected == null ? PurpleArchiveRemoval.begin(paths) : PurpleArchiveRemoval.discard(paths); }
                    pending.finish(store, new PurpleArchiveKeys.RemovalProvider());
                    store.close(); store = null;
                } else {
                    try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) {
                        PurpleArchiveRemoval pending;
                        if (expected != null && !expected.equals(PurpleArchiveDiscard.seal(paths))) throw new PurpleArchiveStore.Failure("Archive discard changed");
                        synchronized (publication) { pending = expected == null ? PurpleArchiveRemoval.begin(paths) : PurpleArchiveRemoval.discard(paths); }
                        if (pending != null) pending.finish(null, new PurpleArchiveKeys.RemovalProvider());
                    }
                }
                status = new Status("Removed", "0", false);
                retire();
            } catch (Exception | LinkageError e) {
                status = new Status("Removal pending", status.bytes, status.gap);
                if (expected == null && !retryScheduled) {
                    retryScheduled = true;
                    recovery.schedule(() -> submit(() -> { retryScheduled = false; remove(); }), 60, TimeUnit.SECONDS);
                }
            }
        }
    }
    private static String failureState(Throwable error) {
        if (error instanceof PurpleArchiveStore.Failure) {
            String reason = ((PurpleArchiveStore.Failure) error).reason;
            if (reason.contains("SchemaMismatch")) return "SchemaMismatch";
            if (reason.contains("schema")) return "ReadOnly";
            if (reason.contains("key")) return "KeyUnavailable";
            if (reason.contains("removal")) return "Removal pending";
        }
        return "Unavailable";
    }
    private static Worker worker(String account) { return workers.computeIfAbsent(account, Worker::new); }
    public static void initialize() {
        if (initialized) return;
        synchronized (PurpleArchive.class) { if (initialized) return; initialized = true; }
        recovery.execute(PurpleArchive::recoverRetained);
    }
    private static boolean inactive(String account) {
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; ++slot) {
            UserConfig config = UserConfig.getInstance(slot);
            if (!config.isConfigLoaded()) return false;
            if (config.isClientActivated() || config.getClientUserId() != 0) {
                PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
                if (identity == null || account.equals(identity.key)) return false;
            }
        }
        return true;
    }
    private static void recoverAccount(String account) {
        if (!PurpleSyncRecoveryFiles.key(account)) return;
        try {
            PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.getFilesDirFixed(), account);
            if (inactive(account)) {
                try {
                    PurpleArchiveKeys.Provider keys = new PurpleArchiveKeys.RemovalProvider();
                    if (PurpleArchiveCatalogue.cleared(paths) && !PurpleArchiveCatalogue.hasUnexpectedStore(paths, null)
                        && !PurpleArchiveCatalogue.hasUnexpectedKey(account, keys, null)) return;
                } catch (Exception | LinkageError e) {}
                Worker worker = worker(account);
                worker.control(() -> {
                    if (!inactive(account)) { if (worker.identity == null) worker.retire(); return; }
                    worker.closing = true; worker.remove();
                });
            } else {
                byte[] pending = paths.metadata.read("removing.json", 65536).bytes;
                if (pending != null) {
                    PurpleArchiveRemoval.resume(paths, pending);
                    Worker worker = worker(account); worker.closing = true; worker.control(worker::remove);
                }
            }
        } catch (Exception | LinkageError e) { startupProblem = true; }
    }
    private static void recoverRetained() {
        try {
            for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; ++slot) {
                if (!UserConfig.getInstance(slot).isConfigLoaded()) {
                    recovery.schedule(PurpleArchive::recoverRetained, 1, TimeUnit.SECONDS); return;
                }
            }
            PurpleCore.ensureLoaded();
            File purple = new File(ApplicationLoader.getFilesDirFixed().getCanonicalFile(), "purple");
            if (PurpleArchiveFiles.mark(purple, true) == null) return;
            PurpleArchiveFiles.Directory root = new PurpleArchiveFiles.Directory(purple, false);
            for (String name : new String[]{"content-archive-state", "content-archive"}) {
                if (PurpleArchiveFiles.mark(root.file(name), true) == null) continue;
                PurpleArchiveFiles.Directory directory = new PurpleArchiveFiles.Directory(root.file(name), true);
                try (PurpleArchiveFiles.Listing listing = directory.listing()) {
                    for (;;) {
                        String[] page = listing.next(); if (page.length == 0) break;
                        for (String account : page) recoverAccount(account);
                    }
                }
            }
            String prefix = "purple.archive.";
            java.util.Enumeration<String> aliases = new PurpleArchiveKeys.RemovalProvider().aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                int end = alias.indexOf('.', prefix.length());
                if (alias.startsWith(prefix) && end > prefix.length()) recoverAccount(alias.substring(prefix.length(), end));
            }
        } catch (Exception | LinkageError e) { startupProblem = true; }
    }
    private static volatile boolean startupProblem;
    public static Status status(int slot) {
        if (Build.VERSION.SDK_INT < 23) return new Status("Requires Android 6 (API 23)", "0", false);
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null) return new Status("Signed out", "0", false);
        Worker worker = workers.get(identity.key);
        return worker == null ? new Status(startupProblem ? "Recovery unavailable" : "Not prepared", "0", startupProblem) : worker.status;
    }
    public static void refresh(int slot, Runnable delivered) {
        initialize();
        PurpleAccountFiles.Identity captured = PurpleAccountFiles.capture(slot);
        if (Build.VERSION.SDK_INT < 23 || captured == null) { AndroidUtilities.runOnUIThread(delivered); return; }
        Worker worker = worker(captured.key);
        worker.submit(() -> {
            worker.open(captured);
            AndroidUtilities.runOnUIThread(() -> { if (captured.current()) delivered.run(); });
        });
    }
    public static final class RemovalRequest {
        private final PurpleAccountFiles.Identity identity;
        private final Worker worker;
        private final String seal;
        private final long epoch;
        private boolean used;
        public volatile boolean completed;
        private RemovalRequest(PurpleAccountFiles.Identity identity, Worker worker) { this.identity = identity; this.worker = worker; this.seal = worker.discardSeal; this.epoch = worker.deliveryEpoch; }
    }
    public static RemovalRequest prepareRemoval(int slot) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        Worker worker = identity == null ? null : workers.get(identity.key);
        if (worker == null || worker.discardSeal == null) return null;
        PurpleArchiveAcquiredSource.revoke(slot);
        PurpleArchiveMediaAccess.revoke(slot);
        return new RemovalRequest(identity, worker);
    }
    public static boolean remove(RemovalRequest request, Runnable delivered) {
        if (Build.VERSION.SDK_INT < 23 || request == null || !request.identity.current()) return false;
        Worker worker = request.worker;
        synchronized (request) { if (request.used || request.epoch != worker.deliveryEpoch || workers.get(request.identity.key) != worker) return false; request.used = true; }
        worker.closing = true;
        PurpleArchiveAcquiredSource.revoke(request.identity.slot);
        PurpleArchiveMediaAccess.revoke(request.identity.slot);
        worker.control(() -> {
            try {
                if (!request.identity.current() || request.epoch != worker.deliveryEpoch)
                    throw new PurpleArchiveStore.Failure("Archive discard context changed");
                worker.closing = true;
                worker.remove(request.seal);
                request.completed = worker.retired && worker.status.state.equals("Removed");
            } catch (Exception | LinkageError e) { worker.status = new Status("Unavailable", worker.status.bytes, worker.status.gap); }
            AndroidUtilities.runOnUIThread(() -> { if (request.identity.current()) delivered.run(); });
        });
        return true;
    }
    private static void fence(Worker worker, PurpleAccountFiles.Identity captured) {
        try {
            synchronized (worker.publication) {
                PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.getFilesDirFixed(), captured.key);
                PurpleArchiveRemoval.begin(paths);
            }
        } catch (Exception | LinkageError e) { worker.status = new Status("Removal pending", worker.status.bytes, worker.status.gap); }
    }
    public static void beforeLogout(int slot) {
        PurpleArchiveMediaAccess.revoke(slot);
        PurpleArchiveLifecycle.revoke(slot); PurpleArchiveAcquiredSource.revoke(slot);
        PurpleAccountFiles.Identity captured = PurpleAccountFiles.capture(slot);
        if (captured == null) return;
        revoked.set(slot, captured);
        if (Build.VERSION.SDK_INT < 23) {
            try { if (!retainedMetadata(captured.key)) return; }
            catch (Exception | LinkageError e) { startupProblem = true; return; }
        }
        Worker worker = worker(captured.key); worker.closing = true;
        worker.control(worker::remove);
    }
    private static boolean retainedMetadata(String account) throws Exception {
        if (PurpleArchiveCatalogue.hasUnexpectedKey(account, new PurpleArchiveKeys.RemovalProvider(), null)) return true;
        File root = new File(ApplicationLoader.getFilesDirFixed().getCanonicalFile(), "purple");
        if (PurpleArchiveFiles.mark(root, true) == null) return false;
        PurpleArchiveFiles.Directory purple = new PurpleArchiveFiles.Directory(root, false);
        for (String name : new String[]{"content-archive-state", "content-archive"}) {
            if (PurpleArchiveFiles.mark(purple.file(name), true) == null) continue;
            PurpleArchiveFiles.Directory directory = new PurpleArchiveFiles.Directory(purple.file(name), true);
            if (PurpleArchiveFiles.mark(directory.file(account), true) != null) return true;
        }
        return false;
    }
    public static void identityChanged(int slot) {
        PurpleArchiveMediaAccess.revoke(slot);
        PurpleArchiveLifecycle.revoke(slot); PurpleArchiveAcquiredSource.revoke(slot);
        for (Worker worker : workers.values()) {
            PurpleAccountFiles.Identity identity = worker.identity;
            if (identity == null || identity.slot != slot) continue;
            worker.submit(() -> {
                if (!worker.closing && worker.identity == identity && !identity.current()) {
                    try { if (worker.store != null) { worker.store.drainReaders(); worker.store.close(); worker.store = null; } worker.status = new Status("Closed", worker.status.bytes, false); worker.retire(); }
                    catch (Exception | LinkageError e) { worker.status = new Status("Close pending", worker.status.bytes, worker.status.gap); }
                }
            });
        }
    }
    interface ArchiveAction { void run(Worker worker, java.util.function.BooleanSupplier active) throws Exception; }
    private static final class CaptureTask implements Runnable {
        final Worker worker;
        final PurpleAccountFiles.Identity identity;
        final java.util.concurrent.atomic.AtomicReference<ArchiveAction> action;
        final java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true), released = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
        final long bytes;
        CaptureTask(Worker worker, PurpleAccountFiles.Identity identity, long bytes, ArchiveAction action) {
            this.worker = worker; this.identity = identity; this.bytes = bytes; this.action = new java.util.concurrent.atomic.AtomicReference<>(action);
        }
        void release() { if (released.compareAndSet(false, true)) worker.captureBytes.addAndGet(-bytes); }
        void cancel() { active.set(false); action.set(null); release(); }
        public void run() {
            ArchiveAction work = action.getAndSet(null);
            try {
                if (work == null || !active.get() || worker.retired || worker.closing || !identity.current()) return;
                if (worker.store == null) worker.open(identity);
                if (!active.get() || worker.store == null || !worker.status.state.equals("Ready") || worker.closing || !identity.current()) return;
                work.run(worker, () -> active.get() && !worker.closing && identity.current());
                result.complete(active.get());
            } catch (Exception | LinkageError e) { captureGap(identity, "capture_write_failed"); }
            finally { release(); result.complete(false); }
        }
    }
    static void capture(PurpleAccountFiles.Identity identity, java.util.ArrayList<PurpleArchiveCapture.Input> inputs) {
        capture(identity, inputs, System.nanoTime() + 250_000_000L);
    }
    static void capture(PurpleAccountFiles.Identity identity, java.util.ArrayList<PurpleArchiveCapture.Input> inputs, long deadline) {
        if (inputs.isEmpty()) return;
        long bytes = 0;
        for (PurpleArchiveCapture.Input input : inputs) bytes += input.key.length() * 2L + (input.body == null ? 0 : input.body.length() * 2L);
        runCapture(identity, bytes, (worker, active) -> {
            for (PurpleArchiveCapture.Input input : inputs) {
                if (!active.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive capture cancelled");
                ++worker.deliveryEpoch;
                String outcome = PurpleArchiveSequence.capture(worker.store, input, active);
                PurpleArchiveInline.capture(worker.store, input, active);
                PurpleDiagLog.event("archive", outcome, PurpleDiagLog.slot(identity.slot));
            }
        }, deadline);
    }
    static boolean media(PurpleAccountFiles.Identity identity, java.util.function.BooleanSupplier active, ArchiveAction action, Runnable refused) {
        if (Build.VERSION.SDK_INT < 23 || identity == null || !allowed(identity) || !active.getAsBoolean()) return false;
        Worker worker = existing(identity);
        if (worker == null || worker.store == null) return false;
        PurpleArchiveStore openingStore = worker.store;
        try {
            worker.queue.execute(() -> {
                try {
                    if (worker.closing || worker.retired || !identity.current() || !active.getAsBoolean()) { refused.run(); return; }
                    if (workers.get(identity.key) != worker || worker.store != openingStore || !worker.status.state.equals("Ready") || !active.getAsBoolean()) { refused.run(); return; }
                    action.run(worker, () -> !worker.closing && !worker.retired && identity.current() && active.getAsBoolean());
                } catch (Exception | LinkageError e) { refused.run(); captureGap(identity, "acquired_media_failed"); }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) { return false; }
    }
    static void runCapture(PurpleAccountFiles.Identity identity, long bytes, ArchiveAction action, long deadline) {
        if (android.os.Build.VERSION.SDK_INT < 23 || identity == null || !allowed(identity)) return;
        Worker worker = worker(identity.key);
        if (worker.closing || worker.retired || bytes > 8 * 1024 * 1024) { captureGap(identity, "capture_overflow"); return; }
        if (worker.captureBytes.addAndGet(bytes) > 16 * 1024 * 1024) {
            worker.captureBytes.addAndGet(-bytes); captureGap(identity, "capture_overflow"); return;
        }
        CaptureTask task = new CaptureTask(worker, identity, bytes, action);
        try {
            if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException();
            worker.queue.execute(task);
            if (!task.result.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) captureGap(identity, "capture_write_failed");
        } catch (Exception | LinkageError e) { task.cancel(); captureGap(identity, "capture_timeout"); }
    }
    static void captureLookup(PurpleAccountFiles.Identity identity, long dialog, long id, PurpleArchiveOrigin.Cause cause, long deadline) {
        runCapture(identity, 0, (worker, active) -> {
            PurpleArchiveStore store = worker.store;
            org.json.JSONObject query = PurpleArchiveCore.call("query", store.arguments().put("query", "lookup")
                .put("dialog", Long.toString(dialog)).put("messageId", Long.toString(id)), store.crypto());
            org.json.JSONObject key;
            try (PurpleArchiveStore.Reader reader = store.reader()) {
                PurpleArchiveSqlite.Result found = reader.read(new PurpleArchiveCore.Statement(query.getJSONObject("statement")));
                if (found.rows != 1) throw new PurpleArchiveStore.Failure("Archive missing or ambiguous identity");
                org.json.JSONObject row = new org.json.JSONObject().put("kind", 2).put("id", found.values[0][0].text())
                    .put("position", "0").put("frame", PurpleArchiveCore.base64(found.values[0][1].bytes));
                key = PurpleArchiveSlice.open(store, reader, row).getJSONObject("value").getJSONObject("key");
            }
            ++worker.deliveryEpoch;
            String outcome = PurpleArchiveSequence.capture(store, new PurpleArchiveCapture.Input(key.toString(), null, cause, false), active);
            PurpleDiagLog.event("archive", outcome, PurpleDiagLog.slot(identity.slot));
        }, deadline);
    }
    static void captureGap(PurpleAccountFiles.Identity identity, String reason) {
        if (Build.VERSION.SDK_INT < 23 || identity == null || !allowed(identity)) return;
        Worker worker = worker(identity.key);
        worker.status = new Status(worker.status.state, worker.status.bytes, true);
        PurpleDiagLog.event("archive", "capture_gap", PurpleDiagLog.slot(identity.slot), PurpleDiagLog.reason(reason));
        if (worker.closing || worker.retired || !worker.gapScheduled.compareAndSet(false, true)) return;
        recovery.execute(() -> {
            try {
                synchronized (worker.publication) {
                    if (worker.closing || worker.retired || !identity.current()) return;
                    PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.getFilesDirFixed(), identity.key);
                    PurpleArchiveFiles.Snapshot old = paths.metadata.read("capture-gap.json", 65536);
                    if (old.bytes == null) paths.metadata.publish("capture-gap.json", old, PurpleArchiveCatalogue.bytes(new org.json.JSONObject()
                        .put("account", identity.key).put("reason", reason).put("observedAt", Long.toString(System.currentTimeMillis() / 1000))));
                }
                worker.submit(() -> {
                    try { if (worker.store != null && !worker.closing) flushCaptureGap(worker); }
                    catch (Exception | LinkageError e) { worker.status = new Status(worker.status.state, worker.status.bytes, true); }
                });
            } catch (Exception | LinkageError e) { worker.status = new Status(worker.status.state, worker.status.bytes, true); }
            finally { worker.gapScheduled.set(false); }
        });
    }
    private static void flushCaptureGap(Worker worker) throws Exception {
        PurpleArchiveFiles.Snapshot pending = worker.store.paths.metadata.read("capture-gap.json", 65536);
        if (pending.bytes == null) return;
        org.json.JSONObject value = PurpleArchiveBinding.json(pending.bytes);
        if (!value.getString("account").equals(worker.account)) throw new PurpleArchiveStore.Failure("Archive gap account changed");
        PurpleArchiveGaps.record(worker.store, value.getString("reason"), Long.parseLong(value.getString("observedAt")));
        worker.store.paths.metadata.remove("capture-gap.json", false);
    }
    static Worker existing(PurpleAccountFiles.Identity identity) { return identity == null ? null : workers.get(identity.key); }
    public static void loginChanging(int slot, long before, long after) { PurpleArchiveLogin.changing(slot, before, after); }
    public static void writeLogin(int slot, long user, android.content.SharedPreferences.Editor editor) { PurpleArchiveLogin.write(slot, user, editor); }
    private PurpleArchive() {}
}
