/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The always-on Purple diagnostic log: Purple lines only, bounded, in
 * app-private storage, built only from the typed fields of
 * {@link PurpleDiagLogStore}. Settings exports it as a text file the user can
 * share, which replaces asking for a device log.
 *
 * Writing never blocks or throws: the line is formatted on the caller's
 * thread, mirrored to FileLog, and appended on one low-priority daemon thread
 * through a bounded queue that drops lines when full. Export and Clear are
 * user actions, so they are never dropped: when the queue is full they run on
 * a second thread instead.
 */
public final class PurpleDiagLog {
    static final String DIRECTORY = "purple-diagnostics";
    /**
     * Exports live in a folder of their own under the FileProvider's
     * {@code files/logs} root, so the backup rules can exclude exactly it.
     */
    static final String EXPORT_DIRECTORY = "logs/purple-diagnostics";
    /** Where the first diagnostic build wrote exports; Export and Clear still remove them. */
    static final String LEGACY_EXPORT_DIRECTORY = "logs";
    static final String EXPORT_PREFIX = "purple-diagnostic-";
    static final int QUEUE = 256;

    /** Export and Clear when the writer's queue is full. Unbounded, because only taps submit here. */
    static final ThreadPoolExecutor control = executor("purple-diagnostics-control", new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.AbortPolicy());
    /** Appends, then Export and Clear in order. Package-private for the host cases. */
    static final ThreadPoolExecutor writer = executor("purple-diagnostics", new ArrayBlockingQueue<>(QUEUE),
            (task, pool) -> {
                if (task instanceof Control) control.execute(task);
            });
    private static PurpleDiagLogStore store;
    private static String lastBody;
    private static long lastAt;

    private static ThreadPoolExecutor executor(String name, BlockingQueue<Runnable> queue,
            RejectedExecutionHandler rejected) {
        ThreadPoolExecutor result = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, queue, task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, rejected);
        result.allowCoreThreadTimeOut(true);
        return result;
    }

    /** A user action: a full writer queue hands it to {@link #control} instead of dropping it. */
    private static final class Control implements Runnable {
        private final Runnable task;

        Control(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            task.run();
        }
    }

    private PurpleDiagLog() {
    }

    public interface Exported { void run(File file); }

    public interface Cleared { void run(boolean cleared); }

    public static PurpleDiagLogStore.Field slot(int slot) { return PurpleDiagLogStore.slot(slot); }
    public static PurpleDiagLogStore.Field mode(int mode) { return PurpleDiagLogStore.mode(mode); }
    public static PurpleDiagLogStore.Field count(String key, int value) { return PurpleDiagLogStore.count(key, value); }
    public static PurpleDiagLogStore.Field version(String key, int value) { return PurpleDiagLogStore.version(key, value); }
    public static PurpleDiagLogStore.Field flag(String key, boolean value) { return PurpleDiagLogStore.flag(key, value); }
    public static PurpleDiagLogStore.Field reason(String code) { return PurpleDiagLogStore.reason(code); }
    public static PurpleDiagLogStore.Field code(String key, String value) { return PurpleDiagLogStore.code(key, value); }
    public static PurpleDiagLogStore.Field error(Throwable error) { return PurpleDiagLogStore.error(error); }

    /**
     * Records one event. {@code area}, {@code event} and every field name and
     * code are words of {@link PurpleDiagLogStore#VOCABULARY}. The same event
     * with the same fields within five seconds is recorded once, so a screen
     * that rebuilds repeatedly cannot flood the log.
     */
    public static void event(String area, String event, PurpleDiagLogStore.Field... fields) {
        try {
            final long now = System.currentTimeMillis();
            final String line = PurpleDiagLogStore.line(now, area, event, fields);
            final String body = line.substring(line.indexOf(' ') + 1);
            synchronized (PurpleDiagLog.class) {
                if (body.equals(lastBody) && now - lastAt >= 0 && now - lastAt < 5000) return;
                lastBody = body;
                lastAt = now;
            }
            FileLog.e("Purple: " + line);
            writer.execute(() -> {
                PurpleDiagLogStore target = store();
                if (target != null) target.append(line);
            });
        } catch (Throwable ignored) {
        }
    }

    private static synchronized PurpleDiagLogStore store() {
        if (store == null) {
            try {
                store = new PurpleDiagLogStore(new File(ApplicationLoader.getFilesDirFixed(), DIRECTORY),
                        PurpleDiagLogStore.LIMIT);
            } catch (Throwable e) {
                return null;
            }
        }
        return store;
    }

    /** Queues a user action that a full queue cannot drop; {@code failed} runs if it cannot be queued at all. */
    private static void submit(Runnable task, Runnable failed) {
        try {
            writer.execute(new Control(task));
        } catch (Throwable e) {
            failed.run();
        }
    }

    /**
     * Deletes the log, both files, and every exported copy still on this
     * device, then runs {@code done} on the UI thread with whether all of it
     * is gone.
     */
    public static void clear(Cleared done) {
        PurpleHintHistory.clear();
        submit(() -> {
            PurpleDiagLogStore target = store();
            boolean cleared = target == null || target.clear();
            cleared &= deleteExports();
            final boolean result = cleared;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
            event("diag", cleared ? "cleared" : "clear_failed");
        }, () -> AndroidUtilities.runOnUIThread(() -> done.run(false)));
    }

    /** Removes every export this app wrote; false when one of them could not be deleted. */
    private static boolean deleteExports() {
        boolean ok = true;
        for (String folder : new String[]{EXPORT_DIRECTORY, LEGACY_EXPORT_DIRECTORY}) {
            File[] old;
            try {
                old = new File(ApplicationLoader.getFilesDirFixed(), folder).listFiles();
            } catch (Throwable e) {
                return false;
            }
            if (old == null) continue;
            for (File each : old) {
                if (each.isFile() && each.getName().startsWith(EXPORT_PREFIX) && each.getName().endsWith(".txt")
                        && !each.delete()) ok = false;
            }
        }
        return ok;
    }

    /**
     * Writes the header and every stored line to a fresh text file under
     * {@link #EXPORT_DIRECTORY}, and hands it to {@code done} on the UI
     * thread, or null when it could not be written. Earlier exports are
     * removed first, so at most one copy stays on the device until Clear.
     */
    public static void export(Exported done) {
        export(done, "");
    }

    static void export(Exported done, String section) {
        String computed;
        try {
            computed = header();
        } catch (Throwable e) {
            computed = "Purple Telegram diagnostic log\n----\n";
        }
        final String header = computed + section;
        submit(() -> {
            File file = null;
            Throwable failure = null;
            int lines = 0;
            try {
                PurpleDiagLogStore target = store();
                File directory = new File(ApplicationLoader.getFilesDirFixed(), EXPORT_DIRECTORY);
                if (target != null && (directory.isDirectory() || directory.mkdirs())) {
                    deleteExports();
                    SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
                    stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
                    File candidate = new File(directory, EXPORT_PREFIX + stamp.format(new Date()) + ".txt");
                    String body = target.read();
                    for (int i = 0; i < body.length(); ++i) if (body.charAt(i) == '\n') ++lines;
                    try (FileOutputStream out = new FileOutputStream(candidate)) {
                        out.write((header + (body.isEmpty() ? "(no Purple diagnostic lines yet)\n" : body))
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    file = candidate;
                }
            } catch (Throwable e) {
                file = null;
                failure = e;
            }
            if (file != null) event("diag", "exported", count("lines", lines));
            else event("diag", "export_failed", error(failure));
            final File result = file;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        }, () -> AndroidUtilities.runOnUIThread(() -> done.run(null)));
    }

    /**
     * What a reader needs to place the lines: versions and counts only. Read
     * on the calling thread because the account count reads UserConfig.
     */
    static String header() {
        int accounts = 0;
        try {
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) {
                if (UserConfig.getInstance(i).isClientActivated()) ++accounts;
            }
        } catch (Throwable e) {
            accounts = -1;
        }
        String code;
        try {
            code = String.valueOf(ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode);
        } catch (Throwable e) {
            code = "?";
        }
        String android;
        try {
            android = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        } catch (Throwable e) {
            android = "?";
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "Purple Telegram diagnostic log\n"
                + "exported: " + format.format(new Date()) + "\n"
                + "app version: " + BuildVars.BUILD_VERSION_STRING + " (" + code + ")\n"
                + "purple: " + PurpleVersion.VERSION + "\n"
                + "build commit: " + BuildConfig.PURPLE_GIT_HASH + "\n"
                + "purple core: " + BuildConfig.PURPLE_CORE_HASH + "\n"
                + "android: " + android + "\n"
                + "signed-in accounts: " + accounts + "\n"
                + "privacy: lines hold fixed vocabulary words, slot indexes, modes, versions,\n"
                + "  counts and flags, and exception types with scrubbed messages (no ids,\n"
                + "  phone numbers, emails, @handles, hex tokens, paths or quoted text).\n"
                + "----\n";
    }
}
