package org.telegram.messenger.purple;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

/** Exercises the actual archive owner lifecycle with synthetic accounts and disposable files. */
public final class PurpleArchiveSaturatedLifecycleCases {
    private static int checks;
    private static PurpleArchive.Worker a, b;
    private static PurpleArchiveStore originalA;
    private static Saturation saturation;
    private static String mode;
    private static ScheduledExecutorService recovery() throws Exception {
        Field field = PurpleArchive.class.getDeclaredField("recovery");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(null);
    }
    private static void require(boolean value, String label) {
        ++checks;
        if (!value) throw new IllegalStateException("SETUP_OR_INVARIANT " + label);
    }
    private static void drainUI() {
        ArrayList<Runnable> pending;
        synchronized (AndroidUtilities.class) {
            pending = new ArrayList<>(AndroidUtilities.pending);
            AndroidUtilities.pending.clear();
        }
        for (Runnable runnable : pending) runnable.run();
    }
    private static boolean observe(BooleanSupplier predicate, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!predicate.getAsBoolean() && System.nanoTime() < until) {
            drainUI(); Thread.sleep(5);
        }
        return predicate.getAsBoolean();
    }
    private static <T> T owner(PurpleArchive.Worker worker, Callable<T> call) throws Exception {
        return worker.queue.submit(call).get(10, TimeUnit.SECONDS);
    }
    private static void account(int slot, long user) {
        UserConfig config = UserConfig.getInstance(slot);
        config.active = user != 0; config.user = user;
        PurpleAccountFiles.identityChanged(slot);
    }
    private static void ready(int slot) throws Exception {
        AtomicBoolean delivered = new AtomicBoolean();
        PurpleArchive.refresh(slot, () -> delivered.set(true));
        require(observe(delivered::get, 15), "refresh callback");
        require(PurpleArchive.status(slot).state.equals("Ready"), "real store Ready");
    }
    private static Set<String> aliases(String account) {
        Set<String> result = new HashSet<>();
        String prefix = PurpleArchiveKeys.prefix(account);
        for (String alias : PurpleArchivePlatformProvider.keys.keySet())
            if (alias.startsWith(prefix)) result.add(alias);
        return result;
    }
    private static byte[] hash(File file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
    }
    private static final class Isolation {
        final PurpleArchiveStore store;
        final File database, wal;
        final byte[] dbHash, walHash;
        final Set<String> keys;
        final java.util.Map<String, javax.crypto.SecretKey> keyRefs = new java.util.HashMap<>();
        Isolation() throws Exception {
            store = b.store;
            database = owner(b, () -> b.store.directory.file("archive.sqlite"));
            wal = new File(database.getPath() + "-wal");
            require(database.isFile() && wal.isFile(), "B database and persistent WAL exist");
            dbHash = hash(database); walHash = hash(wal); keys = aliases(b.account);
            require(!keys.isEmpty(), "B synthetic alias exists");
            for (String alias : keys) keyRefs.put(alias, PurpleArchivePlatformProvider.keys.get(alias));
        }
        void verify() throws Exception {
            require(b.store == store && !b.retired && !store.closed(), "B store unchanged");
            require(Arrays.equals(dbHash, hash(database)) && Arrays.equals(walHash, hash(wal)), "B DB/WAL unchanged");
            require(keys.equals(aliases(b.account)), "B aliases unchanged");
            for (String alias : keys) require(keyRefs.get(alias) == PurpleArchivePlatformProvider.keys.get(alias), "B synthetic key object unchanged");
        }
    }
    private static final class Saturation implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean blocked = new AtomicBoolean();
        Saturation() throws Exception {
            a.queue.execute(() -> {
                blocked.set(true); entered.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { blocked.set(false); }
            });
            try {
                if (!entered.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("owner blocker did not start");
                require(ownerBlocked(), "fill owner remains blocked and executor running");
                for (int attempt = 0; attempt < 64 && a.queue.getQueue().remainingCapacity() > 0; ++attempt) {
                    try { a.queue.execute(() -> {}); }
                    catch (java.util.concurrent.RejectedExecutionException rejected) {
                        if (!isFull()) throw rejected;
                        break;
                    }
                }
                full();
            } catch (Exception | Error e) { release.countDown(); throw e; }
        }
        boolean ownerBlocked() {
            return entered.getCount() == 0 && release.getCount() == 1 && blocked.get()
                && !a.queue.isShutdown() && !a.queue.isTerminating() && a.queue.getActiveCount() == 1;
        }
        boolean isFull() {
            return ownerBlocked() && a.queue.getQueue().size() == 64 && a.queue.getQueue().remainingCapacity() == 0;
        }
        void full() {
            require(isFull(), "real account queue saturated");
        }
        public void close() { release.countDown(); }
    }
    private static void setup(File root) throws Exception {
        require(root.mkdir(), "exclusive synthetic fixture root");
        ApplicationLoader.files = new File(root, "files");
        require(ApplicationLoader.files.mkdir(), "exclusive app files port");
        ApplicationLoader.applicationContext = new android.content.Context();
        Security.addProvider(new PurpleArchivePlatformProvider());
        require(Security.getProvider("AndroidKeyStore") instanceof PurpleArchivePlatformProvider, "synthetic provider selected");
        PurpleArchiveLogin.source = slot -> "11111111111111111111111111111111";
        UserConfig.selectedAccount = 0;
        account(0, 11); account(1, 22); ready(0); ready(1);
        a = PurpleArchive.existing(PurpleAccountFiles.capture(0));
        b = PurpleArchive.existing(PurpleAccountFiles.capture(1));
        require(a != null && b != null && a.store != null && b.store != null, "actual two store owners");
        originalA = a.store;
        recovery().submit(() -> {}).get(10, TimeUnit.SECONDS);
        owner(a, () -> null); owner(b, () -> null); drainUI();
    }
    private static boolean identityClose() throws Exception {
        Isolation isolation = new Isolation();
        PurpleAccountFiles.Identity stale = PurpleAccountFiles.capture(0);
        String oldAccount = a.account;
        saturation = new Saturation();
        try {
            // Actual public lifetime change and archive-close hook, not control() as a proxy.
            account(0, 33);
            require(!stale.current(), "old lifetime revoked immediately");
            saturation.full();
        } finally { saturation.close(); }
        boolean terminal = observe(() -> a.retired && originalA.closed()
            && PurpleArchive.boundWorker(oldAccount) == null, 5);
        AtomicBoolean recaptured = new AtomicBoolean();
        PurpleArchive.runCapture(stale, 0, (worker, active) -> recaptured.set(true),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
        require(!recaptured.get(), "stale lifetime cannot recapture");
        // Fixed retired worker must not be revived; old implementation must not retarget its store.
        if (!a.retired) owner(a, () -> { a.open(stale); return null; });
        // Retired fixed workers are never invoked from the fixture main thread.
        require(a.store == originalA || a.store == null && originalA.closed(), "stale open cannot replace store");
        require(PurpleArchive.boundWorker("0-33") == null, "no replacement account worker invented");
        require(!new File(ApplicationLoader.files, "purple/content-archive-state/0-33").exists(), "no replacement archive created");
        isolation.verify();
        return terminal;
    }
    private static boolean removalRetry() throws Exception {
        Isolation isolation = new Isolation();
        String alias = originalA.binding.alias();
        File storeRoot = owner(a, () -> a.store.directory.file);
        File intent = owner(a, () -> a.store.paths.metadata.file("removing.json"));
        PurpleArchivePlatformProvider.failRemoval = true;
        // The real first failure creates the production 60-second retry obligation.
        PurpleArchive.beforeLogout(0); account(0, 0);
        require(observe(() -> a.status.state.equals("Removal pending"), 15), "real failed removal state");
        require(owner(a, () -> a.retryScheduled), "actual retry was scheduled");
        require(intent.isFile() && PurpleArchivePlatformProvider.keys.containsKey(alias), "durable intent and failed-removal alias retained");
        require(!new File(storeRoot, "archive.sqlite").exists() && !new File(storeRoot, "wrapped.key").exists(), "real removal reached provider-delete seam");
        // Recorded after the scheduling owner task completed, so 62 seconds from here
        // is later than the unmodified timer's due time, even on a slow first removal.
        long failureObserved = System.nanoTime();
        saturation = new Saturation();
        try {
            long wait = TimeUnit.SECONDS.toNanos(62) - (System.nanoTime() - failureObserved);
            if (wait > 0) new CountDownLatch(1).await(wait, TimeUnit.NANOSECONDS);
            // Same real scheduler, FIFO due-time barrier; original retry has attempted admission.
            recovery().submit(() -> {}).get(10, TimeUnit.SECONDS);
            saturation.full();
            PurpleArchivePlatformProvider.failRemoval = false;
        } finally { saturation.close(); }
        boolean terminal = observe(() -> a.retired && !a.retryScheduled
            && PurpleArchive.boundWorker(a.account) == null, 5);
        if (terminal) {
            require(!intent.exists() && !storeRoot.exists(), "real retry cleared intent and store");
            require(!PurpleArchivePlatformProvider.keys.containsKey(alias), "real retry removed A alias");
        }
        isolation.verify();
        return terminal;
    }
    private static void cleanup() throws Exception {
        System.out.println("LIFECYCLE_CLEANUP_BEGIN separate_from_oracle=true");
        if (saturation != null) saturation.close();
        PurpleArchivePlatformProvider.failRemoval = false;
        // Cancel synthetic delayed work before fixture cleanup; never cancel product jobs.
        recovery().shutdownNow();
        require(recovery().awaitTermination(10, TimeUnit.SECONDS), "synthetic recovery executor stopped");
        if (a != null && !a.retired) {
            if (mode.equals("removal-retry")) {
                owner(a, () -> { a.remove(); return null; });
                require(a.retired, "separate synthetic pending-removal cleanup");
            } else {
                owner(a, () -> {
                    if (a.store != null && !a.store.closed()) { a.store.drainReaders(); a.store.close(); a.store = null; }
                    a.retire(); return null;
                });
            }
        }
        if (b != null && !b.retired) owner(b, () -> {
            if (b.store != null && !b.store.closed()) { b.store.drainReaders(); b.store.close(); b.store = null; }
            b.retire(); return null;
        });
        if (a != null) require(a.queue.awaitTermination(10, TimeUnit.SECONDS), "A owner stopped");
        if (b != null) require(b.queue.awaitTermination(10, TimeUnit.SECONDS), "B owner stopped");
        System.out.println("LIFECYCLE_CLEANUP=PASS");
    }
    public static void main(String[] args) {
        int exit = 2;
        mode = args.length == 3 ? args[2] : "invalid";
        try {
            require(mode.equals("identity-close") || mode.equals("removal-retry"), "known mode");
            System.load(args[0]); PurpleCore.ensureLoaded(); setup(new File(args[1]));
            boolean terminal = mode.equals("identity-close") ? identityClose() : removalRetry();
            String tag = mode.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            System.out.println("LIFECYCLE_EXPECTED_DEFECT=" + (mode.equals("identity-close") ? "identity_close_dropped" : "removal_retry_dropped"));
            System.out.println("LIFECYCLE_ORACLE_" + tag + "=" + (terminal ? "GREEN" : "RED"));
            System.out.println("LIFECYCLE_SEMANTIC_FAILURES=" + (terminal ? 0 : 1));
            exit = terminal ? 0 : 1;
        } catch (Exception | LinkageError e) {
            System.out.println("LIFECYCLE_SETUP_OR_INVARIANT_FAILURE=" + e.getClass().getSimpleName());
            exit = 2;
        } finally {
            try { cleanup(); }
            catch (Exception | LinkageError e) {
                System.out.println("LIFECYCLE_CLEANUP=FAIL class=" + e.getClass().getSimpleName()); exit = 2;
            }
        }
        System.out.println("ARCHIVE_SATURATED_LIFECYCLE_CHECKS=" + checks);
        System.exit(exit);
    }
}
