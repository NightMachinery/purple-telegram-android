package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.Security;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

public final class PurpleArchiveManagerCases {
    private static int checks;
    private static void check(boolean value, String message) { ++checks; if (!value) throw new AssertionError(message); }
    private static void await(BooleanSupplier ready, String message) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(ready.getAsBoolean(), message);
    }
    private static void account(int slot, long user) {
        UserConfig value = UserConfig.getInstance(slot); value.active = user != 0; value.user = user;
        PurpleAccountFiles.identityChanged(slot);
    }
    private static void refresh(int slot) throws Exception {
        PurpleArchive.Status previous = PurpleArchive.status(slot);
        PurpleArchive.refresh(slot, () -> {});
        await(() -> PurpleArchive.status(slot) != previous && PurpleArchive.status(slot).state.equals("Ready"), "actual account worker opens on its owning queue");
    }
    private static PurpleArchiveBinding binding(String account) throws Exception {
        PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.files, account);
        return new PurpleArchiveBinding(account, PurpleArchiveBinding.json(paths.metadata.read("binding.json", 65536).bytes));
    }
    private static File database(String account, PurpleArchiveBinding binding) throws Exception {
        return new PurpleArchivePaths(ApplicationLoader.files, account).store(binding.store, false).file("archive.sqlite");
    }
    private static byte[] digest(File file) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())); }
    private static void session(File root) throws Exception {
        ApplicationLoader.files = new File(root, "files"); check(ApplicationLoader.files.mkdir(), "exclusive application port root");
        account(0, 11); account(1, 22); refresh(0); refresh(1);
        PurpleArchiveBinding a = binding("0-11"), b = binding("0-22");
        File aDb = database(a.account, a), bDb = database(b.account, b); byte[] other = digest(bDb);
        check(PurpleArchivePlatformProvider.creates.get() == 2 && PurpleArchivePlatformProvider.last.randomized
            && !PurpleArchivePlatformProvider.last.authentication, "actual Android key adapter requests dedicated randomized non-biometric policy");
        for (int i = 0; i < 3; ++i) refresh(0);
        check(PurpleArchivePlatformProvider.creates.get() == 2, "same-account refresh retains queue, key and connection ownership");
        UserConfig.selectedAccount = 1; refresh(0);
        check(binding("0-11").store.equals(a.store), "selected account does not redirect requested account storage");
        java.lang.reflect.Field registryField = PurpleArchive.class.getDeclaredField("workers"); registryField.setAccessible(true);
        java.util.Map<?, ?> registry = (java.util.Map<?, ?>) registryField.get(null);
        Object openingWorker = registry.get("0-11");
        java.lang.reflect.Field queueField = openingWorker.getClass().getDeclaredField("queue"); queueField.setAccessible(true);
        java.lang.reflect.Field storeField = openingWorker.getClass().getDeclaredField("store"); storeField.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<PurpleArchiveStore.Reader> held = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
        ((java.util.concurrent.ThreadPoolExecutor) queueField.get(openingWorker)).execute(() -> {
            try { held.set(((PurpleArchiveStore) storeField.get(openingWorker)).reader()); }
            catch (Exception e) { throw new AssertionError(e); }
            finally { acquired.countDown(); }
        });
        check(acquired.await(15, java.util.concurrent.TimeUnit.SECONDS) && held.get() != null, "real manager owns an active reader snapshot");
        account(0, 33);
        await(() -> !registry.containsKey("0-11"), "identity replacement drains readers and retires old account worker");
        check(aDb.exists() && PurpleArchivePlatformProvider.keys.containsKey(a.alias()), "identity close preserves archived files and dedicated key");
        account(0, 11); refresh(0);
        check(binding("0-11").store.equals(a.store), "same identity can reopen retained archive after reader drain");
        PurpleArchive.beforeLogout(0);
        account(0, 33); refresh(0);
        await(() -> !aDb.exists() && !PurpleArchivePlatformProvider.keys.containsKey(a.alias()), "logout deletes opening account after slot reuse");
        check(Arrays.equals(other, digest(bDb)) && binding("0-22").store.equals(b.store), "other account files remain unchanged");
        PurpleArchive.beforeLogout(0); account(0, 0);
        account(0, 11); refresh(0);
        PurpleArchiveBinding again = binding("0-11");
        check(!again.store.equals(a.store) && !again.key.equals(a.key), "relogin replaces retired worker with a fresh store and key");
        check(!PurpleArchive.remove(PurpleArchive.prepareRemoval(3), () -> {}), "signed-out removal request cannot choose another account");
        PurpleArchive.RemovalRequest opening = PurpleArchive.prepareRemoval(0);
        PurpleArchive.beforeLogout(0); account(0, 44); refresh(0);
        check(!PurpleArchive.remove(opening, () -> {}), "confirmation cannot reuse an opening account after lifetime change");
        PurpleArchiveBinding retained = binding("0-44"); File retainedDb = database("0-44", retained);
        PurpleArchivePaths retainedPaths = new PurpleArchivePaths(ApplicationLoader.files, "0-44");
        retainedPaths.metadata.remove("binding.json", false);
        PurpleArchive.refresh(0, () -> {});
        await(() -> {
            try { return retainedPaths.metadata.read("recovery.json", 65536).bytes != null; }
            catch (Exception e) { throw new AssertionError(e); }
        }, "existing worker records durable recovery obligation after binding loss");
        PurpleArchive.beforeLogout(0); account(0, 55);
        await(() -> !retainedDb.exists() && !PurpleArchivePlatformProvider.keys.containsKey(retained.alias()),
            "real logout erases orphaned archive after slot reuse");
        await(() -> {
            try { return retainedPaths.metadata.read("removing.json", 65536).bytes == null; }
            catch (Exception e) { throw new AssertionError(e); }
        }, "orphan worker clears removal obligation only after erasure");
        check(Arrays.equals(other, digest(bDb)) && PurpleArchivePlatformProvider.keys.containsKey(b.alias()),
            "orphan manager cleanup leaves account B files and key untouched");
        account(2, 66); refresh(2);
        PurpleArchiveBinding malformed = binding("0-66"); File malformedDb = database("0-66", malformed);
        PurpleArchivePaths malformedPaths = new PurpleArchivePaths(ApplicationLoader.files, "0-66");
        malformedPaths.metadata.publish("removing.json", malformedPaths.metadata.read("removing.json", 65536), new byte[]{123});
        PurpleArchive.refresh(2, () -> {});
        await(() -> PurpleArchive.status(2).state.equals("Unavailable"), "malformed removal refuses automatic open");
        check(malformedDb.exists() && PurpleArchivePlatformProvider.keys.containsKey(malformed.alias()), "malformed intent never selects deletion");
        await(() -> PurpleArchive.prepareRemoval(2) != null, "refused account exposes explicit recovery context");
        PurpleArchive.RemovalRequest discard = PurpleArchive.prepareRemoval(2);
        check(PurpleArchive.remove(discard, () -> {}), "explicit fenced discard accepts refused record");
        check(!PurpleArchive.remove(discard, () -> {}), "discard token is single-use");
        await(() -> !malformedDb.exists(), "explicit discard erases malformed account archive");
        check(Arrays.equals(other, digest(bDb)) && PurpleArchivePlatformProvider.keys.containsKey(b.alias()), "explicit discard preserves B");

    }
    private static void seed(File root) throws Exception {
        ApplicationLoader.files = new File(root, "files"); check(ApplicationLoader.files.mkdir(), "exclusive restart application root");
        PurpleArchivePaths unsafe = new PurpleArchivePaths(ApplicationLoader.files, "0-7");
        File outside = new File(root, "outside"); check(outside.mkdir(), "exclusive unsafe-account sentinel");
        Files.delete(unsafe.metadata.file.toPath());
        Files.createSymbolicLink(unsafe.metadata.file.toPath(), outside.toPath());
        account(0, 11); account(1, 22); refresh(0); refresh(1);
        PurpleArchiveBinding a = binding("0-11"), b = binding("0-22");
        File aDb = database(a.account, a), bDb = database(b.account, b);
        JSONObject state = new JSONObject().put("aStore", a.store).put("bStore", b.store).put("bDigest", PurpleArchiveCore.base64(digest(bDb)));
        Files.write(new File(root, "restart-fixture.json").toPath(), state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PurpleArchivePlatformProvider.failRemoval = true;
        PurpleArchive.beforeLogout(0); account(0, 0);
        await(() -> !aDb.exists(), "interrupted logout reaches physical removal before platform failure");
        PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.files, "0-11");
        check(paths.metadata.read("removing.json", 65536).bytes != null, "platform failure retains durable restart intent");
    }
    private static void recover(File root) throws Exception {
        ApplicationLoader.files = new File(root, "files");
        JSONObject expected = new JSONObject(new String(Files.readAllBytes(new File(root, "restart-fixture.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
        account(0, 33); account(1, 22);
        PurpleArchivePaths old = new PurpleArchivePaths(ApplicationLoader.files, "0-11");
        PurpleArchive.initialize();
        await(() -> {
            try { return old.metadata.read("removing.json", 65536).bytes == null; } catch (Exception e) { throw new AssertionError(e); }
        }, "production startup scanner resumes signed-out opening account intent after real process restart");
        check(old.metadata.read("binding.json", 65536).bytes == null && PurpleArchivePlatformProvider.creates.get() == 0,
            "startup cleanup never recreates old or newly signed-in keys");
        PurpleArchiveBinding b = binding("0-22");
        check(b.store.equals(expected.getString("bStore")) && Arrays.equals(digest(database(b.account, b)), PurpleArchiveCore.unbase64(expected.getString("bDigest"))),
            "restart slot reuse does not modify the other retained account");
        check(PurpleArchiveFiles.mark(new File(ApplicationLoader.files, "purple/content-archive-state/0-33"), true) == null,
            "cleanup does not open an archive for the replacement account");
        check(Files.isSymbolicLink(new File(ApplicationLoader.files, "purple/content-archive-state/0-7").toPath())
            && new File(root, "outside").isDirectory(), "unsafe retained account is refused without blocking or touching another account cleanup");
    }
    public static void main(String[] args) throws Exception {
        PurpleArchiveLogin.source = new PurpleArchiveLogin.Source() {
            public String nonce(int slot) { return "11111111111111111111111111111111"; }
        };
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root = new File(args[1]); String mode = args[2];
        if (!mode.equals("recover")) check(root.mkdir(), "exclusive manager fixture");
        if (mode.equals("seed")) seed(root); else if (mode.equals("recover")) recover(root); else session(root);
        System.out.println("ARCHIVE_MANAGER_CHECKS=" + checks + " mode=" + mode);
    }
}
