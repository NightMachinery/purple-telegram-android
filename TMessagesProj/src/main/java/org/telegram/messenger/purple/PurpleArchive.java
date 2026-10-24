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
    private static final class Worker {
        final String account;
        final Object publication = new Object();
        final ThreadPoolExecutor queue;
        volatile PurpleAccountFiles.Identity identity;
        volatile boolean closing, retired;
        volatile Status status = new Status("Opening", "0", false);
        PurpleArchiveStore store;
        boolean retryScheduled;
        Worker(String account) {
            this.account = account;
            queue = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
                Thread thread = new Thread(r, "PurpleArchiveAccount"); thread.setDaemon(true); return thread;
            });
            queue.allowCoreThreadTimeOut(false);
        }
        void submit(Runnable task) {
            if (retired) return;
            try { queue.execute(task); }
            catch (java.util.concurrent.RejectedExecutionException e) { status = new Status("Busy", status.bytes, status.gap); }
        }
        void open(PurpleAccountFiles.Identity captured) {
            try {
                if (retired || !captured.current()) return;
                if (store != null && (identity == null || identity.lifetime != captured.lifetime || closing)) { store.drainReaders(); store.close(); store = null; }
                identity = captured;
                PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.getFilesDirFixed(), account);
                if (paths.metadata.read("removing.json", 65536).bytes != null) {
                    closing = true;
                    remove();
                    if (retired || paths.metadata.read("removing.json", 65536).bytes != null) return;
                }
                if (!captured.current()) return;
                if (store != null) {
                    synchronized (publication) { PurpleArchiveCatalogue.requireAdmission(paths, new PurpleArchiveKeys.RemovalProvider()); }
                }
                closing = false;
                if (store == null) {
                    store = PurpleArchiveStore.open(new PurpleArchiveStore.Environment() {
                        public File files() { return ApplicationLoader.getFilesDirFixed(); }
                        public int api() { return Build.VERSION.SDK_INT; }
                        public boolean current() { return !closing && identity != null && identity.lifetime == captured.lifetime && captured.current(); }
                        public Object publicationLock() { return publication; }
                        public PurpleArchiveKeys.Provider keys() { return new PurpleArchiveKeys.AndroidProvider(); }
                    }, account);
                }
                status = new Status("Opening", status.bytes, status.gap || store.paths.metadata.read("gap-outbox.json", 65536).bytes != null);
                PurpleArchiveGaps.flush(store);
                status = new Status("Ready", PurpleArchiveUsage.scan(store), store.hasGaps());
            } catch (Exception | LinkageError e) {
                status = new Status(failureState(e), status.bytes, status.gap);
            }
        }
        void retire() {
            retired = true; identity = null; workers.remove(account, this); queue.shutdown();
        }
        void remove() {
            try {
                PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.getFilesDirFixed(), account);
                if (store != null) {
                    synchronized (publication) { PurpleArchiveRemoval.begin(paths); }
                    PurpleArchiveRemoval pending = PurpleArchiveRemoval.resume(paths, paths.metadata.read("removing.json", 65536).bytes);
                    pending.finish(store, new PurpleArchiveKeys.RemovalProvider());
                    store.close(); store = null;
                } else {
                    try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) {
                        PurpleArchiveRemoval pending;
                        synchronized (publication) { pending = PurpleArchiveRemoval.begin(paths); }
                        if (pending != null) pending.finish(null, new PurpleArchiveKeys.RemovalProvider());
                    }
                }
                status = new Status("Removed", "0", false);
                retire();
            } catch (Exception | LinkageError e) {
                status = new Status("Removal pending", status.bytes, status.gap);
                if (!retryScheduled) {
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
        recovery.execute(() -> {
            try {
                PurpleCore.ensureLoaded();
                File files = ApplicationLoader.getFilesDirFixed().getCanonicalFile();
                File purple = new File(files, "purple");
                if (PurpleArchiveFiles.mark(purple, true) != null) {
                    PurpleArchiveFiles.Directory root = new PurpleArchiveFiles.Directory(purple, false);
                    if (PurpleArchiveFiles.mark(root.file("content-archive-state"), true) != null) {
                        PurpleArchiveFiles.Directory metadata = new PurpleArchiveFiles.Directory(root.file("content-archive-state"), true);
                        try (PurpleArchiveFiles.Listing listing = metadata.listing()) {
                            for (;;) {
                                String[] page = listing.next(); if (page.length == 0) break;
                                for (String account : page) {
                                    if (!PurpleSyncRecoveryFiles.key(account)) continue;
                                    try {
                                        PurpleArchiveFiles.Directory directory = new PurpleArchiveFiles.Directory(metadata.file(account), true);
                                        if (directory.read("removing.json", 65536).bytes != null) {
                                            Worker worker = worker(account); worker.closing = true; worker.submit(worker::remove);
                                        }
                                    } catch (Exception | LinkageError e) { startupProblem = true; }
                                }
                            }
                        }
                    }
                }
            } catch (Exception | LinkageError e) {
                startupProblem = true;
            }
        });
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
        private RemovalRequest(PurpleAccountFiles.Identity identity) { this.identity = identity; }
    }
    public static RemovalRequest prepareRemoval(int slot) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        return identity == null ? null : new RemovalRequest(identity);
    }
    public static boolean remove(RemovalRequest request, Runnable delivered) {
        if (Build.VERSION.SDK_INT < 23 || request == null || !request.identity.current()) return false;
        Worker worker = worker(request.identity.key);
        worker.closing = true;
        fence(worker, request.identity);
        worker.submit(() -> {
            worker.remove();
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
        PurpleAccountFiles.Identity captured = PurpleAccountFiles.capture(slot);
        if (captured == null) return;
        if (Build.VERSION.SDK_INT < 23) {
            try { if (!retainedMetadata(captured.key)) return; }
            catch (Exception | LinkageError e) { startupProblem = true; return; }
        }
        Worker worker = worker(captured.key); worker.closing = true;
        fence(worker, captured);
        worker.submit(worker::remove);
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
        for (Worker worker : workers.values()) {
            PurpleAccountFiles.Identity identity = worker.identity;
            if (identity == null || identity.slot != slot) continue;
            worker.submit(() -> {
                if (!worker.closing && worker.identity == identity && !identity.current() && worker.store != null) {
                    try { worker.store.drainReaders(); worker.store.close(); worker.store = null; worker.status = new Status("Closed", worker.status.bytes, false); worker.retire(); }
                    catch (Exception | LinkageError e) { worker.status = new Status("Close pending", worker.status.bytes, worker.status.gap); }
                }
            });
        }
    }
    private PurpleArchive() {}
}
