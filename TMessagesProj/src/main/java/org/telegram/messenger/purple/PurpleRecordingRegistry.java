/*
 * This is the source code of Purple Telegram for Android.
 *
 * F10's registry on Android: it watches upstream's recording notifications,
 * finds the recorder's file, and feeds PurpleRecordingStore. See
 * docs/media-recording-design.md, F10, and the README's "Media recording".
 */

package org.telegram.messenger.purple;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Upstream's recorders already put their bytes on disk as they record; what a
 * crash loses is knowing which file belonged to which chat. The registry
 * keeps that in {@code files/purple-recordings/recordings.json} without any
 * upstream hook for voice:
 *
 * <ul>
 * <li>{@code recordStarted} with a chat's class guid begins an entry; the
 *     chat was registered by its {@code PurpleChatHooks} through
 *     {@link #watchChat}. One second later the newest recorder-named file in
 *     MEDIA_DIR_AUDIO is the recording's file (V2 names it only in a listener
 *     private to MediaController). The registry holds that file open.</li>
 * <li>{@code audioDidSent} without a draft document means upstream sent it:
 *     the entry is dropped. With one, the recording paused into its voice
 *     draft. {@code audioRecordTooShort} drops the entry.</li>
 * <li>A stop that is followed by neither, while upstream deletes the file, is
 *     a discard. The bytes are still readable through the descriptor held
 *     open, so with {@code keep_discarded_p} they are copied into the bin as
 *     a "Discarded" item.</li>
 * <li>At the next start, an entry still recording belongs to a crash and is
 *     recovered into the bin. Then the bin is swept by {@code bin_keep}, and
 *     once per install the orphan scan looks for crashes from before.</li>
 * </ul>
 *
 * <p>The diag log gets counts and states only: never a path, a chat, an id or
 * a file's contents.
 */
public final class PurpleRecordingRegistry {

    private PurpleRecordingRegistry() {
    }

    /** What a chat screen tells the registry about the recording it starts. */
    public interface ChatTarget {
        /**
         * {account, dialog id, topic id, reply message id, client user id,
         * flags (PurpleRecordingSessions.FLAG_*)}, read when a recording
         * starts and again when it stops.
         */
        long[] target();
    }

    private static final String PREFS = "purple_recordings";
    private static final String ORPHAN_SCAN_DONE = "orphan_scan_v1";

    private static final int[] EVENTS = {
        NotificationCenter.recordStarted, NotificationCenter.recordStopped, NotificationCenter.recordResumed,
        NotificationCenter.audioDidSent, NotificationCenter.audioRecordTooShort,
    };

    /** Chat screens by class guid; touched on the UI thread only. */
    private static final Map<Integer, ChatTarget> chats = new HashMap<>();


    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "PurpleRecordings");
        t.setDaemon(true);
        return t;
    });

    /** The store and the event handling; touched on the worker thread, except snapshotCancel. */
    private static PurpleRecordingStore store;
    private static volatile PurpleRecordingSessions sessions;
    private static boolean started;

    // ---- Start -----------------------------------------------------------------

    /**
     * Starts the registry once per process: recovery, the sweep and the
     * orphan scan on the worker thread, then the observers on the UI thread.
     * Called when the accounts are loaded, because entries name account slots.
     */
    public static void start() {
        synchronized (PurpleRecordingRegistry.class) {
            if (started) return;
            started = true;
        }
        PurpleRoundJournal.listener = new PurpleRoundJournal.Listener() {
            @Override
            public void registered(File media, File journal) {
                roundRegistered(media, journal);
            }

            @Override
            public void stopped() {
                PurpleDiagLog.event("recordings", "journal_stopped");
            }
        };
        worker.execute(PurpleRecordingRegistry::startOnWorker);
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                final int account = a;
                for (int id : EVENTS) {
                    NotificationCenter.getInstance(account).addObserver((event, acc, args) -> onEvent(event, acc, args), id);
                }
            }
        });
        worker.scheduleWithFixedDelay(PurpleRecordingRegistry::sweep, 3600, 3600, TimeUnit.SECONDS);
    }

    static File directory() {
        return new File(ApplicationLoader.getFilesDirFixed(), "purple-recordings");
    }

    private static void startOnWorker() {
        try {
            final long now = System.currentTimeMillis() / 1000;
            store = new PurpleRecordingStore(new PurpleRecordingManifest(directory()), CORE, now);
            sessions = new PurpleRecordingSessions(store, ENV);
            final PurpleRecordingStore.Outcome recovered = store.recoverAtStart(now);
            log("recovery", recovered);
            sweep();
            final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(ORPHAN_SCAN_DONE, false)) {
                final PurpleRecordingStore.Outcome orphans = store.scanOrphans(audioDirs(), roundDirs(), now);
                prefs.edit().putBoolean(ORPHAN_SCAN_DONE, true).apply();
                log("orphan_scan", orphans);
            }
            changed();
        } catch (Throwable e) {
            FileLog.e(e);
            PurpleDiagLog.event("recordings", "startup", PurpleDiagLog.reason("io_failed"));
        }
    }

    private static List<File> audioDirs() {
        return Arrays.asList(FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO));
    }

    static List<File> roundDirs() {
        return Arrays.asList(FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT),
                new File(ApplicationLoader.getFilesDirFixed(), "cache"), ApplicationLoader.getFilesDirFixed());
    }

    private static void sweep() {
        if (store == null) return;
        try {
            final PurpleRecordingStore.Outcome swept = store.sweep(System.currentTimeMillis() / 1000,
                    PurpleRecordingSettings.read().binKeepSeconds);
            if (swept.expired > 0) {
                PurpleDiagLog.event("recordings", "sweep", PurpleDiagLog.count("expired", swept.expired));
                changed();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void log(String event, PurpleRecordingStore.Outcome o) {
        if (o.recovered + o.partial + o.unrecoverable + o.missing + o.finishedCleanly + o.orphans + o.failures == 0) return;
        PurpleDiagLog.event("recordings", event,
                PurpleDiagLog.count("recovered", o.recovered), PurpleDiagLog.count("partial", o.partial),
                PurpleDiagLog.count("unrecoverable", o.unrecoverable), PurpleDiagLog.count("missing", o.missing),
                PurpleDiagLog.count("finished_cleanly", o.finishedCleanly), PurpleDiagLog.count("orphans", o.orphans),
                PurpleDiagLog.count("failures", o.failures));
    }

    // ---- Chats -----------------------------------------------------------------

    /** A chat screen exists; its recordings start with this target. UI thread. */
    public static void watchChat(int guid, ChatTarget target) {
        chats.put(guid, target);
    }

    /** The chat screen was destroyed. UI thread. */
    public static void unwatchChat(int guid) {
        chats.remove(guid);
    }

    // ---- Events ----------------------------------------------------------------

    /** {account, dialog, topic, reply, client user id, flags} of a chat, read now; null if not registered. */
    private static long[] target(int guid) {
        final ChatTarget chat = chats.get(guid);
        return chat == null ? null : chat.target();
    }

    private static void onEvent(int event, int account, Object... args) {
        if (event == NotificationCenter.recordResumed) {
            worker.execute(() -> { if (sessions != null) sessions.resumed(account); });
            return;
        }
        if (args.length == 0 || !(args[0] instanceof Integer)) return;
        final int guid = (Integer) args[0];
        final String key = account + ":" + guid;
        if (event == NotificationCenter.recordStarted) {
            final boolean voice = args.length < 2 || !(args[1] instanceof Boolean) || (Boolean) args[1];
            final long[] target = target(guid);
            final long startedAt = System.currentTimeMillis();
            worker.execute(() -> { if (sessions != null) sessions.started(key, account, target, voice, startedAt); });
        } else if (event == NotificationCenter.recordStopped) {
            final int reason = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : 0;
            final long[] target = target(guid);
            // A V2 round cancel: its file executor will finish the movie and then
            // empty the file at its path, so the file is renamed into the bin
            // now, on this thread (an atomic, instant rename on the same file
            // system). Upstream's open descriptor writes its moov into the moved
            // file; erase then empties a fresh file at the old path. The stop is
            // handled a second later, once that last write is done.
            final PurpleRecordingSessions now = sessions;
            final File source = now != null && (reason == 0 || reason == 6) ? now.snapshotSource(key) : null;
            final File bin = new File(directory(), "bin");
            final File moved = new File(bin, "cancel-" + System.nanoTime() + ".snapshot");
            if (source != null && (bin.isDirectory() || bin.mkdirs()) && source.renameTo(moved)) {
                worker.schedule(() -> {
                    now.snapshotDone(key, moved, -1);
                    now.stopped(key, reason, target);
                }, 1000, TimeUnit.MILLISECONDS);
            } else {
                worker.execute(() -> { if (sessions != null) sessions.stopped(key, reason, target); });
            }
        } else if (event == NotificationCenter.audioDidSent) {
            final boolean draft = args.length > 1 && args[1] != null;
            final String path = args.length > 2 && args[2] instanceof String ? (String) args[2] : null;
            final long[] target = draft ? target(guid) : null;
            worker.execute(() -> { if (sessions != null) sessions.didSent(key, draft, path, account, target); });
        } else if (event == NotificationCenter.audioRecordTooShort) {
            worker.execute(() -> { if (sessions != null) sessions.tooShort(key); });
        }
    }

    /** A round recorder registered a movie (PurpleRoundJournal's listener). */
    static void roundRegistered(File media, File journal) {
        worker.execute(() -> { if (sessions != null) sessions.roundRegistered(media, journal); });
    }

    private static final PurpleRecordingSessions.Env ENV = new PurpleRecordingSessions.Env() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            worker.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public File newestVoice(long startedAt) {
            return PurpleRecordingFiles.newestVoice(FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO), startedAt);
        }

        @Override
        public boolean keepDiscarded() {
            return PurpleRecordingSettings.read().keepDiscarded;
        }

        @Override
        public void flushJournal(String path) {
            PurpleRoundJournal.flush(path);
        }

        @Override
        public void closeJournal(String path) {
            PurpleRoundJournal.close(path);
        }

        @Override
        public void changed() {
            PurpleRecordingRegistry.changed();
        }

        @Override
        public void discarded(String kind, boolean kept, boolean failed) {
            if (failed) {
                PurpleDiagLog.event("recordings", "discarded", PurpleDiagLog.reason("io_failed"));
            } else {
                PurpleDiagLog.event("recordings", "discarded", PurpleDiagLog.code("kind", kind), PurpleDiagLog.flag("kept", kept));
            }
        }
    };

    // ---- The bin ---------------------------------------------------------------

    /** Listeners told on the UI thread when the bin changed. */
    private static final List<Runnable> listeners = new ArrayList<>();

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void changed() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable r : new ArrayList<>(listeners)) r.run();
        });
    }

    /** Callback with the bin's items, newest first, on the UI thread. */
    public interface Items {
        void items(List<PurpleRecordingEntry> items);
    }

    public static void bin(Items done) {
        start();
        worker.execute(() -> {
            final List<PurpleRecordingEntry> items = store == null ? new ArrayList<>() : store.bin();
            AndroidUtilities.runOnUIThread(() -> done.items(items));
        });
    }

    /** The number of items in the bin, for the settings row; -1 before the registry has loaded. */
    public static void count(Items done) {
        bin(done);
    }

    public static void delete(String id) {
        worker.execute(() -> {
            final PurpleRecordingEntry e = store == null ? null : store.find(id);
            if (e == null) return;
            try {
                store.delete(e);
            } catch (IOException ignored) {
            }
            changed();
        });
    }

    public static void clear() {
        worker.execute(() -> {
            if (store == null) return;
            try {
                PurpleDiagLog.event("recordings", "bin_cleared", PurpleDiagLog.count("count", store.clear()));
            } catch (IOException ignored) {
            }
            changed();
        });
    }

    /** A bin item was handed to upstream's send path: it leaves the bin. */
    public static void sent(String id) {
        worker.execute(() -> {
            final PurpleRecordingEntry e = store == null ? null : store.find(id);
            if (e == null) return;
            try {
                store.sent(e);
            } catch (IOException ignored) {
            }
            changed();
        });
    }

    /** Runs {@code task} on the registry's thread (the bin's heavy work: waveforms, copies). */
    static void onWorker(Runnable task) {
        worker.execute(task);
    }

    // ---- The core ----------------------------------------------------------------

    private static final PurpleRecordingStore.Core CORE = new PurpleRecordingStore.Core() {
        @Override
        public PurpleRecordingCore.OggFinish finaliseOgg(File file) {
            return PurpleRecordingCore.finaliseOgg(file);
        }

        @Override
        public PurpleRecordingCore.OggScan scanOgg(File file) {
            return PurpleRecordingCore.scanOgg(file);
        }

        @Override
        public boolean binItemExpired(long binnedAt, long now, int keepSeconds) {
            return PurpleRecordingCore.binItemExpired(binnedAt, now, keepSeconds);
        }

        @Override
        public PurpleRecordingStore.RoundResult recoverRound(File media, File journal, File out) {
            return PurpleRoundRecovery.recover(media, journal, out);
        }
    };
}
