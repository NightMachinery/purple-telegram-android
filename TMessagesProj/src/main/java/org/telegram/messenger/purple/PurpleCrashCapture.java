/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Crash capture into Purple's diagnostic log (docs/media-recording-design.md,
 * F11): Java uncaught exceptions and, on API 30 and later, the process exit
 * reasons Android keeps for the app, among them native crashes (with their
 * tombstone on API 31 and later) and ANRs. The records are built by
 * {@link PurpleCrashRecord} from the {@link PurpleDiagLogStore} factories,
 * so they name code (classes, methods, the app's own native libraries,
 * signal and reason codes) and never data.
 */
public final class PurpleCrashCapture {
    static final String PREFS = "purple_crash";
    static final String LAST_EXIT = "last_exit";
    static final int EXITS = 16;

    private static boolean started;

    private PurpleCrashCapture() {
    }

    /**
     * Called once, early in {@code ApplicationLoader.onCreate}, so a crash in
     * the rest of start-up is captured too. Installs the uncaught exception
     * handler, which chains to the one set before it (upstream's
     * {@code BuildVars} installs one only when its logs are on; whichever is
     * installed later chains to the other, so both run), and collects the
     * previous runs' records on a background thread. Never throws.
     *
     * The handler cannot use the diagnostic log's writer, an asynchronous
     * daemon thread that dies with the process, so it writes the record to
     * {@code files/purple-diagnostics/pending-crash.log} synchronously with an
     * fsync, and the next start moves it into the log.
     *
     * Ordering: this runs before {@code ApplicationLoader.onCreate} has
     * initialised AndroidUtilities, so neither the handler nor the collector
     * thread may touch FileLog, AndroidUtilities or ApplicationLoader; the
     * collector writes through {@link PurpleDiagLog#appendNow}, which does not.
     */
    public static synchronized void start(Context context) {
        try {
            if (started || context == null) return;
            started = true;
            final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            final File pending = new File(new File(app.getFilesDir(), PurpleDiagLog.DIRECTORY), PurpleCrashRecord.PENDING);
            final Thread main = Looper.getMainLooper().getThread();
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    PurpleCrashRecord.writePending(pending, System.currentTimeMillis(),
                            PurpleCrashRecord.javaCrash(error, thread == main));
                } catch (Throwable ignored) {
                    // A crash inside the handler must not hide the original one.
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            });
            Thread collector = new Thread(() -> collect(app, pending), "purple-crash");
            collector.setDaemon(true);
            collector.setPriority(Thread.MIN_PRIORITY);
            collector.start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Preloads the handler's classes, then hands the exit reasons newer than
     * the last one recorded to {@link PurpleCrashCollect#run}, which takes the
     * pending file before reading it, and deletes it and advances the cursor
     * only once every line is in the log. Each exit's trace is read and
     * turned into its record before the next exit is read.
     */
    private static void collect(Context app, File pending) {
        try {
            PurpleCrashRecord.preload();
            final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long last = prefs.getLong(LAST_EXIT, 0);
            long newest = last;
            List<PurpleCrashRecord.Exit> exits = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 30) {
                ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> infos = manager == null ? null
                        : manager.getHistoricalProcessExitReasons(app.getPackageName(), 0, EXITS);
                if (infos != null) for (ApplicationExitInfo info : infos) {
                    long time = info.getTimestamp();
                    if (time <= last) continue;
                    newest = Math.max(newest, time);
                    exits.add(exit(app, info));
                }
            }
            final File files = app.getFilesDir();
            PurpleCrashCollect.run(pending, exits, last, newest, System.currentTimeMillis(),
                    lines -> PurpleDiagLog.appendNow(files, lines),
                    value -> prefs.edit().putLong(LAST_EXIT, value).commit());
        } catch (Throwable ignored) {
        }
    }

    @TargetApi(30)
    private static PurpleCrashRecord.Exit exit(Context app, ApplicationExitInfo info) {
        int reason = info.getReason();
        boolean traced = reason == PurpleCrashRecord.REASON_ANR
                || reason == PurpleCrashRecord.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31;
        String process = info.getProcessName();
        boolean main = process != null && process.equals(app.getPackageName());
        if (traced) {
            try (InputStream in = info.getTraceInputStream()) {
                return new PurpleCrashRecord.Exit(info.getTimestamp(), reason, info.getStatus(), info.getImportance(),
                        main, info.getPss(), info.getRss(), in);
            } catch (Throwable ignored) {
                // The trace could not be opened: the record without it.
            }
        }
        return new PurpleCrashRecord.Exit(info.getTimestamp(), reason, info.getStatus(), info.getImportance(),
                main, info.getPss(), info.getRss(), null);
    }
}
