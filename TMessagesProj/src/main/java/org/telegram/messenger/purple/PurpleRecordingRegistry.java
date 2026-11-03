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
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
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
        /** {account, dialog id, topic id, reply message id}, read when a recording starts. */
        long[] target();
    }

    private static final String PREFS = "purple_recordings";
    private static final String ORPHAN_SCAN_DONE = "orphan_scan_v1";
    private static final long DISCOVER_DELAY_MS = 1000;
    private static final long[] DISCARD_CHECKS_MS = {2000, 6000, 15000};

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

    /** The store and the live sessions; touched on the worker thread only. */
    private static PurpleRecordingStore store;
    private static final Map<String, Session> sessions = new HashMap<>();
    private static boolean started;

    /** A recording in progress, by account and guid. */
    private static final class Session {
        final String key;
        final PurpleRecordingEntry entry;
        final boolean round;
        FileChannel held;
        File file;
        boolean stopped;
        boolean paused;
        int generation;

        Session(String key, PurpleRecordingEntry entry, boolean round) {
            this.key = key;
            this.entry = entry;
            this.round = round;
        }

        void hold(File f) {
            close();
            file = f;
            try {
                held = new RandomAccessFile(f, "r").getChannel();
            } catch (IOException e) {
                held = null;
            }
        }

        void close() {
            if (held != null) {
                try {
                    held.close();
                } catch (IOException ignored) {
                }
                held = null;
            }
        }
    }

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

    private static void onEvent(int event, int account, Object... args) {
        if (event == NotificationCenter.recordResumed) {
            worker.execute(() -> resumed(account));
            return;
        }
        if (args.length == 0 || !(args[0] instanceof Integer)) return;
        final int guid = (Integer) args[0];
        final String key = account + ":" + guid;
        if (event == NotificationCenter.recordStarted) {
            final boolean voice = args.length < 2 || !(args[1] instanceof Boolean) || (Boolean) args[1];
            final ChatTarget chat = chats.get(guid);
            final long[] target = chat == null ? null : chat.target();
            final long startedAt = System.currentTimeMillis();
            worker.execute(() -> started(key, account, target, voice, startedAt));
        } else if (event == NotificationCenter.recordStopped) {
            final int reason = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : 0;
            worker.execute(() -> stopped(key, reason));
        } else if (event == NotificationCenter.audioDidSent) {
            final boolean draft = args.length > 1 && args[1] != null;
            final String path = args.length > 2 && args[2] instanceof String ? (String) args[2] : null;
            worker.execute(() -> didSent(key, draft, path));
        } else if (event == NotificationCenter.audioRecordTooShort) {
            worker.execute(() -> end(key));
        }
    }

    private static void started(String key, int account, long[] target, boolean voice, long startedAt) {
        if (store == null) return;
        final Session existing = sessions.get(key);
        if (existing != null) {
            // A resume after a pause into preview: the same recording.
            existing.stopped = false;
            existing.paused = false;
            return;
        }
        try {
            final PurpleRecordingEntry entry = store.begin(account,
                    target == null ? 0 : target[1], target == null ? 0 : target[2], target == null ? 0 : (int) target[3],
                    voice ? PurpleRecordingEntry.VOICE : PurpleRecordingEntry.ROUND, startedAt);
            final Session session = new Session(key, entry, !voice);
            sessions.put(key, session);
            if (voice) {
                worker.schedule(() -> discover(session), DISCOVER_DELAY_MS, TimeUnit.MILLISECONDS);
            } else {
                attachPendingRound(session);
            }
        } catch (IOException e) {
            PurpleDiagLog.event("recordings", "begin", PurpleDiagLog.reason("io_failed"));
        }
    }

    /** Finds the voice file and holds it open; tried again once if the recorder was slow. */
    private static void discover(Session session) {
        if (sessions.get(session.key) != session) return;
        final File found = PurpleRecordingFiles.newestVoice(FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO),
                session.entry.startedAt);
        if (found == null) {
            if (session.generation++ < 3) {
                worker.schedule(() -> discover(session), DISCOVER_DELAY_MS * 2, TimeUnit.MILLISECONDS);
            }
            return;
        }
        if (found.equals(session.file)) return;
        session.hold(found);
        try {
            store.attach(session.entry, found, null);
        } catch (IOException ignored) {
        }
    }

    private static void resumed(int account) {
        // V2 may continue into a new file after a pause; look again.
        for (Session session : new ArrayList<>(sessions.values())) {
            if (!session.round && session.entry.account == account && session.paused) {
                session.paused = false;
                session.stopped = false;
                session.generation = 0;
                worker.schedule(() -> discover(session), DISCOVER_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static void stopped(String key, int reason) {
        final Session session = sessions.get(key);
        if (session == null) return;
        session.stopped = true;
        if (session.round) {
            // InstantCameraView and InstantCameraView2: 0 or 6 cancel, 2 pause into
            // preview, 4 too short, 5 send.
            if (reason == 2) {
                session.paused = true;
                PurpleRoundJournal.flush(session.entry.journal);
            } else if (reason == 5 || reason == 4) {
                end(key);
            } else {
                scheduleDiscardChecks(session);
            }
            return;
        }
        // MediaController: 1 is a pause into preview (send 2), 0 a send or a cancel.
        if (reason == 1) {
            session.paused = true;
        } else {
            scheduleDiscardChecks(session);
        }
    }

    private static void scheduleDiscardChecks(Session session) {
        for (long delay : DISCARD_CHECKS_MS) {
            worker.schedule(() -> checkDiscarded(session), delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * A stop that upstream answered by deleting the file, with no send: a
     * discard. A file still there after the last check is left in the
     * recording state for the next start to settle.
     */
    private static void checkDiscarded(Session session) {
        if (sessions.get(session.key) != session || !session.stopped || session.paused) return;
        if (session.file != null && session.file.exists()) return;
        sessions.remove(session.key);
        PurpleRoundJournal.close(session.entry.journal);
        final boolean keep = PurpleRecordingSettings.read().keepDiscarded;
        try {
            final boolean kept = store.discard(session.entry, session.held, keep && session.held != null,
                    System.currentTimeMillis() / 1000);
            PurpleDiagLog.event("recordings", "discarded", PurpleDiagLog.code("kind", session.entry.kind),
                    PurpleDiagLog.flag("kept", kept));
            if (kept) changed();
        } catch (IOException e) {
            PurpleDiagLog.event("recordings", "discarded", PurpleDiagLog.reason("io_failed"));
        } finally {
            session.close();
        }
    }

    private static void didSent(String key, boolean draft, String path) {
        final Session session = sessions.get(key);
        if (session == null) return;
        if (!draft) {
            end(key);
            return;
        }
        // Paused into the voice draft: the draft's file is now the recording.
        session.paused = true;
        if (path != null) {
            final File file = new File(path);
            if (!file.equals(session.file) && file.isFile()) {
                session.hold(file);
                try {
                    store.attach(session.entry, file, null);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Sent, or too short: the entry goes, and so does the descriptor. */
    private static void end(String key) {
        final Session session = sessions.remove(key);
        if (session == null) return;
        session.close();
        PurpleRoundJournal.close(session.entry.journal);
        try {
            store.finish(session.entry);
        } catch (IOException ignored) {
        }
    }

    // ---- Rounds (F10 round) ------------------------------------------------------

    /** A registration that came before its recording's recordStarted (legacy creates the movie first). */
    private static File pendingRoundMedia;
    private static File pendingRoundJournal;
    private static long pendingRoundAt;

    /**
     * A round recorder registered a movie. Legacy registers once, just before
     * recordStarted; V2 registers each output generation, the first after
     * recordStarted. The newest generation is the recording's file.
     */
    static void roundRegistered(File media, File journal) {
        worker.execute(() -> {
            Session latest = null;
            for (Session s : sessions.values()) {
                if (s.round && (latest == null || s.entry.startedAt > latest.entry.startedAt)) latest = s;
            }
            if (latest != null) {
                attachRound(latest, media, journal);
            } else {
                pendingRoundMedia = media;
                pendingRoundJournal = journal;
                pendingRoundAt = System.currentTimeMillis();
            }
        });
    }

    private static void attachPendingRound(Session session) {
        if (pendingRoundMedia == null) return;
        if (System.currentTimeMillis() - pendingRoundAt < 10000) {
            attachRound(session, pendingRoundMedia, pendingRoundJournal);
        }
        pendingRoundMedia = null;
        pendingRoundJournal = null;
    }

    private static void attachRound(Session session, File media, File journal) {
        if (media.equals(session.file)) return;
        if (session.entry.journal != null && !session.entry.journal.equals(journal.getPath())) {
            // An earlier V2 generation: upstream replaces its file, and its journal goes with it.
            PurpleRoundJournal.close(session.entry.journal);
            new File(session.entry.journal).delete();
        }
        session.hold(media);
        try {
            store.attach(session.entry, media, journal);
        } catch (IOException ignored) {
        }
    }

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
