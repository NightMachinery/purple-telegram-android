/*
 * This is the source code of Purple Telegram for Android.
 *
 * The F10 registry's event handling: which recording each of upstream's
 * notifications belongs to, and what it does to the store. Pure Java over an
 * Env, so the host suite drives it with upstream's real event sequences; the
 * Android side (PurpleRecordingRegistry) only forwards the notifications.
 */

package org.telegram.messenger.purple;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One session per recording in progress, keyed by account and the chat's
 * class guid. What upstream sends, by recorder (13.1.1):
 *
 * <ul>
 * <li>Voice: {@code recordStarted} at the start only. V2 pauses with
 *     {@code recordStopped} 1 and then {@code audioDidSent} with a draft;
 *     legacy pauses with no {@code recordStopped}: it joins the segments into a
 *     new file and posts {@code recordPaused} and {@code audioDidSent} with the
 *     draft. Both resume with {@code recordResumed}, which names no chat;
 *     legacy records into a new segment. A send is {@code recordStopped} 0 then
 *     {@code audioDidSent} without a draft; a cancel is {@code recordStopped} 0
 *     and the file deleted. Sending or discarding from preview sends nothing.</li>
 * <li>Rounds: a registration from PurpleRoundJournal before
 *     {@code recordStarted} (legacy in prepareEncoder, V2 in
 *     RoundVideoSession.start); {@code recordStopped} 0 or 6 cancel, 2 pause
 *     into preview (then {@code audioDidSent} with the preview copy), 4 too
 *     short, 5 send; {@code recordStarted} again on a resume. Sending from
 *     preview sends nothing.</li>
 * </ul>
 *
 * Because a recording that ends from preview sends nothing, a paused session
 * ends at the next start in its chat, or stays until the next app start
 * settles its entry. A discard from preview is therefore not kept.
 *
 * <p>Thread safety: every method is synchronized. The registry calls them on
 * its worker, except {@link #snapshotCancel}, which must run on the thread
 * that posts the cancel, before upstream's file executor empties the file.
 */
public final class PurpleRecordingSessions {

    /** What the sessions need from the app; the host suite gives a scripted one. */
    public interface Env {
        long now();

        /** Runs {@code task} on the same thread after {@code delayMs}. */
        void schedule(Runnable task, long delayMs);

        /** The newest recorder-named voice file modified since shortly before {@code startedAt}. */
        File newestVoice(long startedAt);

        boolean keepDiscarded();

        void flushJournal(String path);

        void closeJournal(String path);

        /** The bin changed. */
        void changed();

        /** A discard was settled: for the diag log. */
        void discarded(String kind, boolean kept, boolean failed);
    }

    /** Target flags in {@code target[5]}. */
    public static final long FLAG_ONCE = 1;
    public static final long FLAG_QUICK_REPLY = 2;

    static final long DISCOVER_DELAY_MS = 1000;
    /** A voice stop is a discard if upstream deleted the file and sent nothing by then (a legacy join can take seconds). */
    static final long DISCARD_DECIDE_MS = 15000;
    static final long SENDING_CHECK_MS = 2000;
    static final int SENDING_CHECKS = 30;
    /** A registration older than this when its recordStarted comes is not that recording's. */
    static final long PENDING_ROUND_MS = 10000;

    private final PurpleRecordingStore store;
    private final Env env;
    private final Map<String, Session> sessions = new HashMap<>();
    private File pendingMedia;
    private File pendingJournal;
    private long pendingAt;

    public PurpleRecordingSessions(PurpleRecordingStore store, Env env) {
        this.store = store;
        this.env = env;
    }

    /** One recorded file of a recording: legacy voice keeps one per segment. */
    static final class Part {
        final PurpleRecordingEntry entry;
        File file;
        FileChannel held;

        Part(PurpleRecordingEntry entry) {
            this.entry = entry;
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

    static final class Session {
        final String key;
        final boolean round;
        final long[] target;
        Part current;
        /** Legacy voice: the segments recorded before each resume, each its own entry. */
        final List<Part> earlier = new ArrayList<>();
        boolean stopped;
        boolean paused;
        boolean sending;
        int tries;
        /** A cancelled round's bytes, copied before upstream emptied the file. */
        File snapshot;

        Session(String key, boolean round, long[] target) {
            this.key = key;
            this.round = round;
            this.target = target;
        }

        List<Part> parts() {
            final List<Part> all = new ArrayList<>(earlier);
            all.add(current);
            return all;
        }
    }

    // ---- Events ------------------------------------------------------------------

    /**
     * {@code recordStarted}. {@code target} is {account, dialog, topic, reply,
     * client user id, flags}, or null when the chat was not registered.
     */
    public synchronized void started(String key, int account, long[] target, boolean voice, long startedAt) {
        final Session existing = sessions.get(key);
        if (existing != null) {
            if (!existing.paused && !existing.stopped && existing.round == !voice) return;
            // Voice never resumes with recordStarted (both recorders send
            // recordResumed), and a session of the other kind is another
            // recording: only a round may resume here.
            if (!voice && existing.round && !pendingFresh()) {
                // A round resumes into the file it was writing.
                existing.paused = false;
                existing.stopped = false;
                return;
            }
            // The previous recording ended from preview, which upstream does not announce.
            supersede(existing);
        }
        lastPaused.remove(account);
        final PurpleRecordingEntry entry = begin(account, target, voice, startedAt);
        if (entry == null) return;
        final Session session = new Session(key, !voice, target);
        session.current = new Part(entry);
        sessions.put(key, session);
        if (voice) {
            env.schedule(() -> discover(session), DISCOVER_DELAY_MS);
        } else if (pendingFresh()) {
            attachRound(session, pendingMedia, pendingJournal);
            pendingMedia = null;
            pendingJournal = null;
        }
    }

    /** {@code recordStopped}; {@code target} re-reads the view-once flag, or null. */
    public synchronized void stopped(String key, int reason, long[] target) {
        final Session session = sessions.get(key);
        if (session == null) return;
        once(session, target);
        session.stopped = true;
        if (session.round) {
            if (reason == 2) {
                paused(session);
                env.flushJournal(session.current.entry.journal);
                    } else if (reason == 4) {
                end(session);
            } else if (reason == 5) {
                sending(session);
            } else {
                // A cancel is final: settle it now, from the snapshot when there is one.
                session.paused = false;
                discard(session);
            }
            return;
        }
        if (reason == 1) {
            paused(session);
            } else {
            session.paused = false;
            env.schedule(() -> checkDiscarded(session), DISCARD_DECIDE_MS);
        }
    }

    /** {@code audioDidSent}: without a draft upstream sent it; with one it paused into preview. */
    public synchronized void didSent(String key, boolean draft, String path) {
        final Session session = sessions.get(key);
        if (session == null) return;
        if (!draft) {
            end(session);
            return;
        }
        paused(session);
        if (session.round) {
            // The preview is a finished copy; the recording continues in the
            // registered file, which stays the entry's media.
            env.flushJournal(session.current.entry.journal);
            return;
        }
        // A legacy pause joins the earlier segments into one new file: they are in it now.
        for (Part part : session.earlier) {
            part.close();
            try {
                store.finish(part.entry);
            } catch (IOException ignored) {
            }
        }
        session.earlier.clear();
        if (path != null) {
            final File file = new File(path);
            if (!file.equals(session.current.file) && file.isFile()) attach(session.current, file, null);
        }
    }

    /** {@code audioRecordTooShort}. */
    public synchronized void tooShort(String key) {
        final Session session = sessions.get(key);
        if (session != null) end(session);
    }

    /** {@code recordResumed} (no guid): the account's paused recording continues. */
    public synchronized void resumed(int account) {
        // recordResumed names no chat. It belongs to the account's one live
        // recording: the session that paused last, never an older one left
        // paused by a send from preview.
        final Session session = sessions.get(lastPaused.get(account));
        lastPaused.remove(account);
        if (session != null && session.paused) {
            session.paused = false;
            session.stopped = false;
            if (session.round) {
                if (pendingFresh()) {
                    attachRound(session, pendingMedia, pendingJournal);
                    pendingMedia = null;
                    pendingJournal = null;
                }
            } else {
                if (legacyVoice(session.current.file)) {
                    // Legacy resumes into a new segment and keeps the paused,
                    // joined file as the one before it: that file stays its own
                    // entry until the next pause joins both, or the recording ends.
                    session.current.entry.segment = true;
                    try {
                        store.update(session.current.entry);
                    } catch (IOException ignored) {
                    }
                    final PurpleRecordingEntry next = begin(account, session.target, true, env.now());
                    if (next != null) {
                        session.earlier.add(session.current);
                        session.current = new Part(next);
                    }
                }
                // V2 may continue into a new file after a pause; look again.
                session.tries = 0;
                env.schedule(() -> discover(session), DISCOVER_DELAY_MS);
            }
        }
    }

    /** The session that paused last on each account, which a guid-less recordResumed continues. */
    private final Map<Integer, String> lastPaused = new HashMap<>();

    private void paused(Session session) {
        session.paused = true;
        lastPaused.put(session.current.entry.account, session.key);
    }

    /**
     * A round recorder registered a movie. It is kept until the recordStarted
     * that names its session (both recorders register first), or a resume
     * claims it; it never goes to a paused, stopped or sending session.
     */
    public synchronized void roundRegistered(File media, File journal) {
        for (Session s : sessions.values()) {
            if (s.round && !s.paused && !s.stopped && !s.sending && s.current.file == null
                    && env.now() - s.current.entry.startedAt < PENDING_ROUND_MS) {
                attachRound(s, media, journal);
                return;
            }
        }
        dropPending();
        pendingMedia = media;
        pendingJournal = journal;
        pendingAt = env.now();
    }

    /**
     * A round was cancelled: called on the thread that posts the cancel,
     * before upstream's file executor runs. V2 empties the file
     * ({@code RoundVideoRemuxer.erase}: setLength(0), then delete), so the
     * descriptor held open would read nothing; this copies the bytes first.
     */
    public synchronized void snapshotCancel(String key) {
        final Session session = sessions.get(key);
        if (session == null || !session.round || session.current.file == null || !session.current.file.isFile()) return;
        try {
            final File copy = store.scratch(session.current.entry.id + ".snapshot");
            PurpleRecordingFiles.copy(session.current.file, copy);
            session.snapshot = copy;
        } catch (IOException e) {
            session.snapshot = null;
        }
    }

    /** The sessions in progress, for the host suite. */
    synchronized int count() {
        return sessions.size();
    }

    // ---- Internals ---------------------------------------------------------------

    private PurpleRecordingEntry begin(int account, long[] target, boolean voice, long startedAt) {
        try {
            final PurpleRecordingEntry entry = store.begin(account,
                    target == null ? 0 : target[1], target == null ? 0 : target[2], target == null ? 0 : (int) target[3],
                    voice ? PurpleRecordingEntry.VOICE : PurpleRecordingEntry.ROUND, startedAt);
            if (target != null && target.length > 5) {
                entry.userId = target[4];
                entry.once = (target[5] & FLAG_ONCE) != 0;
                entry.quickReply = (target[5] & FLAG_QUICK_REPLY) != 0;
                store.update(entry);
            }
            return entry;
        } catch (IOException e) {
            return null;
        }
    }

    private void once(Session session, long[] target) {
        if (target == null || target.length <= 5) return;
        final boolean once = (target[5] & FLAG_ONCE) != 0;
        boolean changed = false;
        for (Part part : session.parts()) {
            if (part.entry.once != once) {
                part.entry.once = once;
                changed = true;
            }
        }
        if (changed) {
            try {
                store.update(session.current.entry);
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean legacyVoice(File file) {
        return file != null && !file.getName().startsWith("audio_recording_");
    }

    private boolean pendingFresh() {
        return pendingMedia != null && env.now() - pendingAt < PENDING_ROUND_MS;
    }

    /** A registration no recording claimed: its journal is closed and deleted. */
    private void dropPending() {
        if (pendingJournal != null) {
            env.closeJournal(pendingJournal.getPath());
            pendingJournal.delete();
        }
        pendingMedia = null;
        pendingJournal = null;
    }

    private void discover(Session session) {
        if (sessions.get(session.key) != session || session.paused) return;
        final File found = env.newestVoice(session.current.entry.startedAt);
        boolean taken = false;
        for (Session other : sessions.values()) {
            for (Part part : other.parts()) taken |= found != null && part != session.current && found.equals(part.file);
        }
        if (found == null || taken) {
            if (session.tries++ < 3) env.schedule(() -> discover(session), DISCOVER_DELAY_MS * 2);
            return;
        }
        if (!found.equals(session.current.file)) attach(session.current, found, null);
    }

    private void attach(Part part, File media, File journal) {
        part.hold(media);
        try {
            store.attach(part.entry, media, journal);
        } catch (IOException ignored) {
        }
    }

    private void attachRound(Session session, File media, File journal) {
        final PurpleRecordingEntry entry = session.current.entry;
        if (media.equals(session.current.file)) return;
        if (entry.journal != null && !entry.journal.equals(journal.getPath())) {
            // An earlier V2 generation: upstream finishes and replaces its file.
            env.closeJournal(entry.journal);
            new File(entry.journal).delete();
        }
        attach(session.current, media, journal);
    }

    /**
     * The previous recording in this chat ended from preview. A missing file
     * does not mean a discard: a V2 round sent after a trim is remuxed and its
     * old file erased, and clearing media deletes files too. So it simply
     * ends; a discard from preview is not kept (re-check N2).
     */
    private void supersede(Session session) {
        end(session);
    }

    private synchronized void checkDiscarded(Session session) {
        if (sessions.get(session.key) != session || !session.stopped || session.paused || session.sending) return;
        final File file = session.current.file;
        if (file != null && file.exists()) return;
        discard(session);
    }

    private void sending(Session session) {
        session.sending = true;
        for (Part part : session.parts()) {
            try {
                store.sending(part.entry);
            } catch (IOException ignored) {
            }
        }
        session.tries = 0;
        env.schedule(() -> checkSent(session), SENDING_CHECK_MS);
    }

    /**
     * A round handed to upstream's send: settled once its file is finished
     * (moov written, so sendMedia has the whole file) or gone. Until then a
     * crash finds it in {@code sending} and recovers it.
     */
    private synchronized void checkSent(Session session) {
        if (sessions.get(session.key) != session) return;
        final File file = session.current.file;
        if (file == null || !file.exists() || PurpleRecordingFiles.mp4Finished(file) == 1) {
            end(session);
        } else if (session.tries++ < SENDING_CHECKS) {
            env.schedule(() -> checkSent(session), SENDING_CHECK_MS);
        }
    }

    /** Sent, or too short: the entries go, and so do the descriptors and the journal. */
    private void end(Session session) {
        sessions.remove(session.key);
        for (Part part : session.parts()) {
            part.close();
            env.closeJournal(part.entry.journal);
            try {
                store.finish(part.entry);
            } catch (IOException ignored) {
            }
        }
        deleteSnapshot(session);
    }

    private void discard(Session session) {
        sessions.remove(session.key);
        final boolean keep = env.keepDiscarded();
        boolean kept = false;
        boolean failed = false;
        for (Part part : session.parts()) {
            env.closeJournal(part.entry.journal);
            FileChannel bytes = part.held;
            FileChannel snapshot = null;
            try {
                if (part == session.current && session.snapshot != null) {
                    snapshot = new RandomAccessFile(session.snapshot, "r").getChannel();
                    bytes = snapshot;
                }
                kept |= store.discard(part.entry, bytes, keep && bytes != null, env.now() / 1000);
            } catch (IOException e) {
                failed = true;
            } finally {
                part.close();
                if (snapshot != null) {
                    try {
                        snapshot.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        deleteSnapshot(session);
        env.discarded(session.current.entry.kind, kept, failed);
        if (kept) env.changed();
    }

    private static void deleteSnapshot(Session session) {
        if (session.snapshot != null) {
            session.snapshot.delete();
            session.snapshot = null;
        }
    }
}
