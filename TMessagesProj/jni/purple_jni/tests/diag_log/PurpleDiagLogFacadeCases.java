package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host cases for the production PurpleDiagLog facade, run in a JVM of their
 * own with the modern sync platform ports: the export file and its header,
 * no identifiers in the exported text, one export kept at a time, Clear
 * removing exports, and Export and Clear running while a full queue drops
 * lines.
 */
public final class PurpleDiagLogFacadeCases {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }

    /** Runs what the facade posted to the UI thread until {@code done} holds, or fails after ten seconds. */
    private static void ui(AtomicBoolean done, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.get()) {
            List<Runnable> tasks;
            synchronized (AndroidUtilities.class) {
                tasks = new ArrayList<>(AndroidUtilities.pending);
                AndroidUtilities.pending.clear();
            }
            for (Runnable task : tasks) task.run();
            if (done.get()) return;
            if (System.nanoTime() > end) throw new AssertionError(what + " did not finish within ten seconds");
            Thread.sleep(10);
        }
    }

    private static void drained() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!(PurpleDiagLog.writer.getQueue().isEmpty() && PurpleDiagLog.writer.getActiveCount() == 0)) {
            if (System.nanoTime() > end) throw new AssertionError("writer drained");
            Thread.sleep(10);
        }
    }

    private static File export(String what) throws InterruptedException {
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<File> file = new AtomicReference<>();
        PurpleDiagLog.export(result -> {
            file.set(result);
            done.set(true);
        });
        ui(done, what);
        return file.get();
    }

    private static boolean clear(String what) throws InterruptedException {
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean cleared = new AtomicBoolean();
        PurpleDiagLog.clear(result -> {
            cleared.set(result);
            done.set(true);
        });
        ui(done, what);
        return cleared.get();
    }

    private static int exports(File directory) {
        File[] files = directory.listFiles();
        int count = 0;
        if (files != null) for (File each : files) if (each.getName().startsWith(PurpleDiagLog.EXPORT_PREFIX)) ++count;
        return count;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    public static int run(File root) throws Exception {
        checks = 0;
        File app = new File(root, "app");
        check(app.mkdirs(), "fixture root");
        ApplicationLoader.files = app;
        // Two signed-in accounts whose ids must never reach the export.
        UserConfig.getInstance(0).active = true;
        UserConfig.getInstance(0).user = 5550123456L;
        UserConfig.getInstance(2).active = true;
        UserConfig.getInstance(2).user = 777000111L;

        PurpleDiagLog.event("diag", "export", PurpleDiagLog.slot(2), PurpleDiagLog.count("lines", 3));
        PurpleDiagLog.event("diag", "export_failed", PurpleDiagLog.error(new RuntimeException(
                "user alice@example.com @johnsmith +49 151 2345678 token a1b2c3d4e5f6a7b8 id 5550123456 'Work'")));
        PurpleDiagLog.event("diag", "export_failed", PurpleDiagLog.reason("johnsmith"),
                PurpleDiagLog.code("list", "work_projects"));
        drained();

        // A legacy export in files/logs from the first diagnostic build.
        File logs = new File(app, PurpleDiagLog.LEGACY_EXPORT_DIRECTORY);
        check(logs.mkdirs(), "legacy export folder");
        File legacy = new File(logs, PurpleDiagLog.EXPORT_PREFIX + "20261025-000000.txt");
        Files.write(legacy.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        File unrelated = new File(logs, "upstream.txt");
        Files.write(unrelated.toPath(), "upstream".getBytes(StandardCharsets.UTF_8));

        // Export: the file, its folder and header.
        File exported = export("export");
        File folder = new File(app, PurpleDiagLog.EXPORT_DIRECTORY);
        check(exported != null && exported.isFile(), "export wrote a file");
        check(exported.getParentFile().getCanonicalFile().equals(folder.getCanonicalFile()),
                "the export sits in files/logs/purple-diagnostics, under the FileProvider logs root");
        check(exported.getName().matches("purple-diagnostic-\\d{8}-\\d{6}\\.txt"), "export file name: " + exported.getName());
        check(!legacy.exists(), "export removes a legacy export");
        check(unrelated.isFile(), "export leaves other files in files/logs alone");
        String text = read(exported);
        int split = text.indexOf("----\n");
        check(split > 0, "the header ends with a rule");
        String header = text.substring(0, split);
        String body = text.substring(split + 5);
        String[] lines = header.split("\n");
        check(lines[0].equals("Purple Telegram diagnostic log"), "header title");
        check(lines[1].matches("exported: \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"), "export time: " + lines[1]);
        check(lines[2].startsWith("app version: ") && lines[2].endsWith(")"), "app version and code: " + lines[2]);
        check(lines[3].equals("purple: " + PurpleVersion.VERSION), "Purple version: " + lines[3]);
        check(lines[4].equals("build commit: " + BuildConfig.PURPLE_GIT_HASH), "build commit: " + lines[4]);
        check(lines[5].equals("purple core: " + BuildConfig.PURPLE_CORE_HASH), "core commit: " + lines[5]);
        check(lines[6].startsWith("android: "), "Android version: " + lines[6]);
        check(lines[7].equals("signed-in accounts: 2"), "account count: " + lines[7]);
        check(lines[8].startsWith("privacy: "), "privacy note");
        check(lines.length == 11, "no other header lines: " + lines.length);

        // No identifiers anywhere in the file.
        for (String secret : new String[]{"5550123456", "777000111", "alice", "example.com", "johnsmith", "2345678",
                "a1b2c3d4", "Work", "work_projects", app.getPath()}) {
            check(!text.contains(secret), "the export holds no " + secret);
        }
        check(body.contains(" diag export slot=2 lines=3\n"), "the lines follow the header: " + body);
        check(body.contains("error=RuntimeException:\"user <email> <handle> <phone> token <hex> id # <q>\""),
                "the exported error is scrubbed: " + body);
        check(body.contains(" diag export_failed reason=invalid invalid=invalid\n"), "non-words are refused: " + body);
        for (String line : body.split("\n")) {
            check(line.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ [a-z_]+ [a-z_]+( .*)?"), "line shape: " + line);
            String rest = line.substring(line.indexOf(' ') + 1);
            check(!rest.matches(".*\\d{6,}.*") && !rest.contains("/"), "no long number or path: " + line);
        }

        // A second export replaces the first.
        File second = export("second export");
        check(second != null && second.isFile(), "the second export was written");
        check(exports(folder) == 1, "one export at a time");
        check(read(second).contains(" diag exported lines=3\n"), "the first export logged its line count");

        // Clear removes the log and the export.
        Files.write(legacy.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        check(clear("clear"), "clear reports success");
        drained();
        check(exports(folder) == 0 && !legacy.exists(), "clear removes every export, legacy ones too");
        check(unrelated.isFile(), "clear leaves other files in files/logs alone");
        File store = new File(app, PurpleDiagLog.DIRECTORY);
        String[] left = store.list();
        String remaining = left == null || left.length == 0 ? "" : read(new File(store, PurpleDiagLogStore.CURRENT));
        check(!remaining.contains(" diag export slot=2") && remaining.contains(" diag cleared"),
                "clear deleted the old lines and recorded itself: " + remaining);

        // A full queue drops lines but never Export or Clear.
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PurpleDiagLog.writer.execute(() -> {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
        });
        check(blocked.await(10, TimeUnit.SECONDS), "the writer is busy");
        for (int i = 0; i < PurpleDiagLog.QUEUE + 50; ++i) {
            PurpleDiagLog.event("diag", "export", PurpleDiagLog.count("lines", 1000 + i));
        }
        check(PurpleDiagLog.writer.getQueue().remainingCapacity() == 0, "the queue is full");
        long before = PurpleDiagLog.control.getCompletedTaskCount();
        File full = export("export with a full queue");
        check(full != null && full.isFile(), "export still runs when the queue is full");
        check(clear("clear with a full queue"), "clear still runs when the queue is full");
        check(exports(folder) == 0, "that clear removed the export");
        check(PurpleDiagLog.control.getCompletedTaskCount() >= before + 2, "both ran on the control thread");
        release.countDown();
        drained();
        String after = read(new File(store, PurpleDiagLogStore.CURRENT));
        check(after.contains("lines=1000") && !after.contains("lines=1299"), "queued lines were kept and the overflow dropped");
        return checks;
    }
}
