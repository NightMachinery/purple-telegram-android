package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.security.Security;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

public final class PurpleArchiveUnavailableCases {
    private static int checks;
    private static void check(boolean value, String label) { ++checks; if (!value) throw new AssertionError(label); }
    private static void refresh(int slot) throws Exception {
        CountDownLatch done = new CountDownLatch(1); PurpleArchive.refresh(slot, done::countDown);
        long end = System.nanoTime() + 20_000_000_000L;
        while (done.getCount() != 0 && System.nanoTime() < end) {
            java.util.ArrayList<Runnable> tasks;
            synchronized (org.telegram.messenger.AndroidUtilities.class) {
                tasks = new java.util.ArrayList<>(org.telegram.messenger.AndroidUtilities.pending);
                org.telegram.messenger.AndroidUtilities.pending.clear();
            }
            for (Runnable task : tasks) task.run();
            Thread.sleep(5);
        }
        check(done.getCount() == 0, "Refresh delivers after owner recovery");
    }
    private static void loaded(PurpleArchiveLoginCases.Preferences prefs) throws Exception {
        PurpleArchive.class.getMethod("loginLoaded", int.class, android.content.SharedPreferences.class, String.class)
            .invoke(null, 0, prefs.value, prefs.memory.get("user"));
    }
    private static void noNonce(String mode) throws Exception {
        UserConfig a = UserConfig.getInstance(0); a.user = 11; a.active = true;
        PurpleArchiveLoginCases.Preferences prefs = new PurpleArchiveLoginCases.Preferences(); a.preferences = prefs.value;
        prefs.value.edit().putString("user", "legacy-user-serialization").apply();
        if (mode.equals("baseline")) {
            refresh(0); check(PurpleArchive.status(0).state.equals("Unavailable"), "loaded pre-hook login cannot open archive");
            PurpleArchive.runCapture(PurpleAccountFiles.capture(0), 0, (worker, active) -> {}, System.nanoTime() + 2_000_000_000L);
            check(PurpleArchive.status(0).state.equals("Unavailable") && PurpleArchive.status(0).gap, "real capture reaches reported unavailable-plus-gap state");
            check(!prefs.memory.containsKey("purpleArchiveLoginV1"), "ordinary cold load has no archive nonce");
            return;
        }
        if (mode.equals("bootstrap-failed")) {
            prefs.fail = true; loaded(prefs); refresh(0);
            check(PurpleArchive.status(0).state.equals("LoginUnavailable"), "failed durable bootstrap blocks even memory-visible nonce");
            check(PurpleArchivePlatformProvider.creates.get() == 0, "failed bootstrap cannot create an archive key");
            return;
        }
        if (mode.startsWith("orphan-")) {
            PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.files, "0-11");
            File retained = null;
            if (mode.equals("orphan-key")) PurpleArchivePlatformProvider.keys.put(PurpleArchiveKeys.alias("0-11", "retained", "key"), new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"));
            else {
                PurpleArchiveFiles.Directory store = paths.store("retained", true);
                if (!mode.equals("orphan-store")) {
                    retained = mode.equals("orphan-journal") ? store.child("causal", true).file("retained.journal") : store.file("archive.sqlite");
                    Files.write(retained.toPath(), new byte[]{1, 2, 3});
                }
            }
            loaded(prefs); refresh(0);
            check(!PurpleArchive.status(0).state.equals("Ready") && !PurpleArchive.status(0).state.equals("Unavailable"), "standalone retained content receives a distinct refusal");
            check(PurpleArchivePlatformProvider.creates.get() == 0 && PurpleArchivePlatformProvider.removals.get() == 0, "bootstrap neither creates nor erases keys for retained content");
            if (retained != null) check(Arrays.equals(Files.readAllBytes(retained.toPath()), new byte[]{1, 2, 3}), "standalone retained bytes remain unchanged");
            if (mode.equals("orphan-key")) check(PurpleArchivePlatformProvider.keys.size() == 1, "standalone retained key remains owned");
            return;
        }
        boolean gapOnly = mode.equals("upgrade-gap") || mode.equals("upgrade-outbox");
        if (mode.equals("upgrade-gap")) {
            PurpleArchive.runCapture(PurpleAccountFiles.capture(0), 0, (worker, active) -> {}, System.nanoTime() + 2_000_000_000L);
            long end = System.nanoTime() + 5_000_000_000L;
            PurpleArchivePaths pending = new PurpleArchivePaths(ApplicationLoader.files, "0-11");
            while (pending.metadata.read("capture-gap.json", 65536).bytes == null && System.nanoTime() < end) Thread.sleep(5);
            check(pending.metadata.read("capture-gap.json", 65536).bytes != null, "real failed first capture persists gap metadata without content");
        }
        if (mode.equals("upgrade-outbox")) {
            PurpleArchivePaths pending = new PurpleArchivePaths(ApplicationLoader.files, "0-11");
            pending.metadata.publish("gap-outbox.json", pending.metadata.read("gap-outbox.json", 65536), PurpleArchiveCatalogue.bytes(new org.json.JSONObject()
                .put("account", "0-11").put("store", "old").put("generation", "1").put("id", "gap")
                .put("reason", "capture_interrupted").put("observedAt", "1")));
        }
        loaded(prefs); String nonce = PurpleArchiveLogin.context(0);
        check(prefs.disk.get("user").equals("legacy-user-serialization") && prefs.disk.get("purpleArchiveLoginV1").equals(nonce), "legacy serialized user and new nonce persist together");
        check(prefs.disk.get("purpleArchiveBootstrapV1").equals("true"), "bootstrap erasure fence is durable");
        refresh(0); check(PurpleArchive.status(0).state.equals("Ready"), "never-archived existing login reaches Ready");
        check(PurpleArchive.status(0).gap == gapOnly, "diagnostic-only metadata retains truthful gap state");
        PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.files, "0-11");
        PurpleArchiveBinding binding = new PurpleArchiveBinding("0-11", PurpleArchiveBinding.json(paths.metadata.read("binding.json", 65536).bytes));
        File database = paths.store(binding.store, false).file("archive.sqlite");
        prefs.restart(); loaded(prefs); check(PurpleArchiveLogin.context(0).equals(nonce), "restart does not rotate bootstrapped context");
        refresh(0); check(PurpleArchive.status(0).state.equals("Ready"), "same bootstrapped login reopens");
        if (mode.equals("legacy-record")) paths.metadata.remove("login.json", false);
        prefs.value.edit().remove("purpleArchiveLoginV1").remove("purpleArchiveBootstrapV1").apply();
        loaded(prefs); byte[] before = Files.readAllBytes(database.toPath()); refresh(0);
        check(PurpleArchive.status(0).state.equals("LoginRecordUnavailable"), "nonce-less existing archive is never adopted");
        check(Arrays.equals(before, Files.readAllBytes(database.toPath())) && PurpleArchivePlatformProvider.removals.get() == 0, "bootstrap never erases or rewrites retained database/key");
        prefs.restart(); loaded(prefs); refresh(0);
        check(PurpleArchive.status(0).state.equals("LoginRecordUnavailable") && database.exists(), "restart cannot forget bootstrap erasure fence");
        PurpleArchive.RemovalRequest discard = PurpleArchive.prepareRemoval(0); check(discard != null, "refused legacy archive remains explicitly discardable");
        CountDownLatch done = new CountDownLatch(1); check(PurpleArchive.remove(discard, done::countDown), "confirmed discard accepted");
        long end = System.nanoTime() + 20_000_000_000L; while (!discard.completed && System.nanoTime() < end) Thread.sleep(5);
        check(discard.completed, "confirmed discard completes");
        refresh(0); check(PurpleArchive.status(0).state.equals("Ready"), "Refresh prepares fresh storage after explicit discard");
    }
    private static void recovery() throws Exception {
        UserConfig a = UserConfig.getInstance(0); a.user = 11; a.active = true;
        PurpleArchiveLoginCases.Preferences prefs = new PurpleArchiveLoginCases.Preferences(); a.preferences = prefs.value;
        prefs.value.edit().putString("user", "legacy-user-serialization").apply(); loaded(prefs);
        PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.files, "0-22");
        File outside = new File(ApplicationLoader.files, "outside"); check(outside.mkdir(), "outside sentinel created");
        Files.delete(paths.metadata.file.toPath()); Files.createSymbolicLink(paths.metadata.file.toPath(), outside.toPath());
        java.lang.reflect.Method scanner = PurpleArchive.class.getDeclaredMethod("recoverRetained"); scanner.setAccessible(true); scanner.invoke(null);
        check(PurpleArchive.status(0).state.equals("Recovery unavailable") && PurpleArchive.status(0).gap, "unsafe retained account reaches startup failure state");
        Files.delete(paths.metadata.file.toPath());
        refresh(0); check(PurpleArchive.status(0).state.equals("Ready"), "Refresh reruns scanner and opens active account");
        java.lang.reflect.Field problem = PurpleArchive.class.getDeclaredField("startupProblem"); problem.setAccessible(true);
        check(!problem.getBoolean(null), "successful Refresh clears sticky startup failure");
        check(outside.isDirectory(), "recovery never traverses unsafe retained account");
    }
    private static void diagnostics() throws Exception {
        Class<?> failures = Class.forName("org.telegram.messenger.purple.PurpleArchiveFailures");
        java.lang.reflect.Method record = failures.getDeclaredMethod("record", int.class, String.class, Throwable.class); record.setAccessible(true);
        java.lang.reflect.Method reason = failures.getDeclaredMethod("reason", Throwable.class); reason.setAccessible(true);
        record.invoke(null, 0, "store_open", new java.io.IOException("inert-private-sentinel /private/sentinel 123456789"));
        record.invoke(null, -1, "retained_aliases", new java.security.KeyStoreException("inert-private-sentinel"));
        PurpleDiagLog.writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
        String log = new String(Files.readAllBytes(new File(ApplicationLoader.files, "purple-diagnostics/purple-diagnostic.log").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        check(log.contains("archive open_failed") && log.contains("step=store_open") && log.contains("reason=archive_storage_failed"), "open failure identifies step and fixed reason");
        check(log.contains("archive recovery_failed") && log.contains("step=retained_aliases") && log.contains("reason=archive_key_failed"), "startup recovery failure identifies step and fixed reason");
        check(!log.contains("sentinel") && !log.contains("123456789") && !log.contains("/private") && !log.contains("invalid"), "failure events contain no exception text, path or identifier");
        check(reason.invoke(null, new IllegalArgumentException("archive metadata JSON")).equals("archive_metadata_invalid"), "native canonical JSON refusal has the metadata diagnostic class");
        for (Throwable error : new Throwable[]{new PurpleArchiveStore.Failure("Archive login nonce unavailable"), new PurpleArchiveStore.Failure("Archive catalogue authority ambiguous"),
            new PurpleArchiveStore.Failure("Archive interrupted journal missing"), new PurpleArchiveStore.Failure("Archive query incomplete"), new org.json.JSONException("inert-private-sentinel"), new IllegalArgumentException("archive metadata JSON"), new UnsatisfiedLinkError("inert-private-sentinel")}) {
            String code = (String) reason.invoke(null, error); check(PurpleDiagLogStore.VOCABULARY.contains(code), "every classified failure has a diagnostic vocabulary code");
        }
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root = new File(args[1]); check(root.mkdir(), "exclusive unavailable fixture");
        ApplicationLoader.files = new File(root, "files"); check(ApplicationLoader.files.mkdir(), "exclusive app files");
        if (args[2].equals("diagnostics")) diagnostics(); else if (args[2].equals("recovery")) recovery(); else noNonce(args[2]);
        System.out.println("ARCHIVE_UNAVAILABLE_CHECKS=" + checks + " mode=" + args[2]);
    }
}
