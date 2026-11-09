/*
 * This is the source code of Purple Telegram for Android.
 *
 * The F10 registry's rules: what each recording event does to the manifest
 * and the bin, crash recovery at start, auto-clear and the orphan scan
 * (docs/media-recording-design.md, F10). Pure Java: the formats and decisions
 * come from purple-core through the Core interface, and the Android side
 * (PurpleRecordingRegistry) only feeds it events, so the host suite runs it as
 * is over the real core.
 */

package org.telegram.messenger.purple;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Not thread safe: {@link PurpleRecordingRegistry} calls it from its one
 * thread. Every change is written to the manifest before the method returns.
 *
 * <p>Nothing here logs. The registry writes counts and states to the diag log
 * from the {@link Outcome}s, never a path, a chat or an id.
 */
public final class PurpleRecordingStore {

    /** What the store asks purple-core (and, for rounds, the client's muxer). */
    public interface Core {
        PurpleRecordingCore.OggFinish finaliseOgg(File file);

        PurpleRecordingCore.OggScan scanOgg(File file);

        boolean binItemExpired(long binnedAt, long now, int keepSeconds);

        /**
         * Recovers a round video that has no index into {@code out}, from its
         * media data and journal ({@code journal} may be null). Null when
         * nothing could be rebuilt; the raw file is then kept.
         */
        RoundResult recoverRound(File media, File journal, File out);

        default boolean matchesLongOriginal(PurpleRecordingEntry entry, File file) { return false; }
    }

    /** A round recovery's result. */
    public static final class RoundResult {
        /** Some of the media data could not be read: "Partly recovered". */
        public final boolean partial;
        public final long durationMs;

        public RoundResult(boolean partial, long durationMs) {
            this.partial = partial;
            this.durationMs = durationMs;
        }
    }

    /** Counts of what one call did, for the diag log. */
    public static final class Outcome {
        public int recovered;
        public int partial;
        public int unrecoverable;
        public int finishedCleanly;
        public int missing;
        public int expired;
        public int orphans;
        public int failures;
    }

    private final PurpleRecordingManifest manifest;
    private final Core core;
    private final List<PurpleRecordingEntry> entries;
    private int nextId;

    public PurpleRecordingStore(PurpleRecordingManifest manifest, Core core, long nowSeconds) {
        this.manifest = manifest;
        this.core = core;
        this.entries = manifest.load(nowSeconds);
    }

    /** A copy of every entry, oldest first. */
    public List<PurpleRecordingEntry> entries() {
        return new ArrayList<>(entries);
    }

    /** The bin's items, newest first. */
    public List<PurpleRecordingEntry> bin() {
        final List<PurpleRecordingEntry> list = new ArrayList<>();
        for (PurpleRecordingEntry e : entries) if (e.inBin()) list.add(e);
        Collections.sort(list, (a, b) -> Long.compare(b.startedAt != 0 ? b.startedAt : b.binnedAt * 1000,
                a.startedAt != 0 ? a.startedAt : a.binnedAt * 1000));
        return list;
    }

    public PurpleRecordingEntry find(String id) {
        for (PurpleRecordingEntry e : entries) if (e.id.equals(id)) return e;
        return null;
    }

    // ---- Recording events ----------------------------------------------------

    /** A recording started: its entry is written before anything else happens. */
    public PurpleRecordingEntry begin(int account, long dialogId, long topicId, int replyId, String kind,
            long startedAtMs) throws IOException {
        return begin(account, dialogId, topicId, replyId, kind, startedAtMs, false);
    }

    public PurpleRecordingEntry begin(int account, long dialogId, long topicId, int replyId, String kind,
            long startedAtMs, boolean awaitingQueue) throws IOException {
        final PurpleRecordingEntry e = new PurpleRecordingEntry();
        e.id = Long.toString(startedAtMs, 36) + "-" + Integer.toString(nextId++, 36);
        e.account = account;
        e.dialogId = dialogId;
        e.topicId = topicId;
        e.replyId = replyId;
        e.kind = kind;
        e.startedAt = startedAtMs;
        e.state = PurpleRecordingEntry.RECORDING;
        e.awaitingQueue = awaitingQueue;
        entries.add(e);
        save();
        return e;
    }

    /** The recorder's file (and a round's journal) became known. */
    public void attach(PurpleRecordingEntry e, File media, File journal) throws IOException {
        e.media = media == null ? null : media.getAbsolutePath();
        e.journal = journal == null ? null : journal.getAbsolutePath();
        save();
    }

    public File reserveSnapshot(PurpleRecordingEntry e, File media, File journal) throws IOException {
        final String previousBase = e.snapshotBase, previousSource = e.snapshotSource, previousJournal = e.snapshotJournal;
        final boolean committed = e.snapshotCommitted;
        e.snapshotBase = new File(manifest.binDir(), java.util.UUID.randomUUID() + ".snapshot").getAbsolutePath();
        e.snapshotSource = media.getAbsolutePath(); e.snapshotJournal = journal == null ? null : journal.getAbsolutePath();
        e.snapshotCommitted = false;
        try { save(); } catch (IOException failure) {
            e.snapshotBase = previousBase; e.snapshotSource = previousSource; e.snapshotJournal = previousJournal;
            e.snapshotCommitted = committed;
            throw failure;
        }
        return new File(e.snapshotBase);
    }

    public void reserveLongOriginal(PurpleRecordingEntry entry, File source, String generation,
            long device, long inode) throws IOException {
        final String media = entry.media, previousSource = entry.snapshotSource, previousGeneration = entry.originalGeneration;
        final long previousDevice = entry.originalDevice, previousInode = entry.originalInode;
        final boolean original = entry.longOriginal, awaiting = entry.awaitingQueue, committed = entry.snapshotCommitted;
        entry.media = source.getAbsolutePath(); entry.snapshotSource = entry.media;
        entry.originalGeneration = generation; entry.originalDevice = device; entry.originalInode = inode;
        entry.longOriginal = true; entry.awaitingQueue = true; entry.snapshotCommitted = false;
        try { save(); } catch (IOException failure) {
            entry.media = media; entry.snapshotSource = previousSource; entry.originalGeneration = previousGeneration;
            entry.originalDevice = previousDevice; entry.originalInode = previousInode;
            entry.longOriginal = original; entry.awaitingQueue = awaiting; entry.snapshotCommitted = committed;
            throw failure;
        }
    }

    public void keepLongOriginal(PurpleRecordingEntry entry, long nowSeconds) throws IOException {
        final File source = new File(entry.snapshotSource), target = binFile(entry);
        if (!core.matchesLongOriginal(entry, source)) throw new IOException("original identity");
        final byte[] before = PurpleRecordingFiles.digest(source);
        PurpleRecordingFiles.copy(source, target);
        if (!core.matchesLongOriginal(entry, source)
                || !java.util.Arrays.equals(before, PurpleRecordingFiles.digest(source))
                || !java.util.Arrays.equals(before, PurpleRecordingFiles.digest(target))
                || PurpleRecordingFiles.mp4Finished(target) != 1) throw new IOException("original copy");
        final String previous = entry.media, state = entry.state;
        final long binned = entry.binnedAt, duration = entry.durationMs;
        final boolean awaiting = entry.awaitingQueue, committed = entry.snapshotCommitted;
        entry.media = target.getAbsolutePath(); entry.state = PurpleRecordingEntry.UNSENT;
        entry.durationMs = PurpleRecordingFiles.mp4DurationMs(target); entry.binnedAt = nowSeconds;
        entry.awaitingQueue = false; entry.snapshotCommitted = true;
        try { save(); } catch (IOException failure) {
            entry.media = previous; entry.state = state; entry.durationMs = duration; entry.binnedAt = binned;
            entry.awaitingQueue = awaiting; entry.snapshotCommitted = committed;
            throw failure;
        }
    }

    public void snapshotKept(PurpleRecordingEntry e, File media, boolean discarded, long nowSeconds) throws IOException {
        e.discarded = discarded;
        e.snapshotCommitted = true;
        if (e.snapshotJournal != null) e.journal = e.snapshotJournal;
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(media, "r")) {
            keepFailed(e, input.getChannel(), nowSeconds);
        }
    }

    public void cleanupSnapshot(PurpleRecordingEntry e) {
        if (!e.snapshotCommitted) return;
        for (String path : new String[]{e.snapshotSource, e.snapshotJournal,
                e.snapshotBase == null ? null : e.snapshotBase + ".error",
                e.snapshotBase == null ? null : e.snapshotBase + ".discard",
                e.snapshotBase == null ? null : e.snapshotBase + ".drop"}) {
            if (e.longOriginal && path != null && path.equals(e.snapshotSource)
                    && !core.matchesLongOriginal(e, new File(path))) continue;
            if (path != null && !path.equals(e.media) && !path.equals(e.journal)) new File(path).delete();
        }
    }

    /** Writes an entry whose fields the caller changed (view-once, the account's user id). */
    public void update(PurpleRecordingEntry e) throws IOException {
        save();
    }

    /** Upstream's send path has the recording; a start finds it unfinished only if the send never finished. */
    public void sending(PurpleRecordingEntry e) throws IOException {
        e.state = PurpleRecordingEntry.SENDING;
        save();
    }

    public void keepCompleted(PurpleRecordingEntry e, File media, long nowSeconds) throws IOException {
        if (PurpleRecordingFiles.mp4Finished(media) != 1) throw new IOException("unfinished round");
        final File target = binFile(e);
        PurpleRecordingFiles.copy(media, target);
        final String previous = e.media;
        final String state = e.state;
        final long binned = e.binnedAt;
        e.media = target.getAbsolutePath();
        e.state = PurpleRecordingEntry.UNSENT;
        e.binnedAt = nowSeconds;
        e.durationMs = PurpleRecordingFiles.mp4DurationMs(target);
        try {
            save();
        } catch (IOException failure) {
            e.media = previous; e.state = state; e.binnedAt = binned;
            throw failure;
        }
    }

    public void keepFailed(PurpleRecordingEntry e, FileChannel bytes, long nowSeconds) throws IOException {
        final File target = binFile(e);
        PurpleRecordingFiles.copy(bytes, target);
        final String previous = e.media, journal = e.journal, state = e.state;
        final long binned = e.binnedAt;
        if (journal != null && new File(journal).isFile()) {
            final File copy = new File(target.getPath() + ".pjr");
            PurpleRecordingFiles.copy(new File(journal), copy);
            e.journal = copy.getAbsolutePath();
        }
        e.media = target.getAbsolutePath(); e.binnedAt = nowSeconds;
        try {
            settle(e, nowSeconds, new Outcome());
            save();
        } catch (IOException failure) {
            e.media = previous; e.journal = journal; e.state = state; e.binnedAt = binned;
            throw failure;
        }
    }

    /** A file in the bin directory for a copy no entry owns yet (a cancelled round's snapshot). */
    public File scratch(String name) throws IOException {
        return new File(manifest.binDir(), name);
    }

    public void duration(PurpleRecordingEntry e, long durationMs) throws IOException {
        if (durationMs <= e.durationMs) return;
        e.durationMs = durationMs;
        save();
    }

    /**
     * The recording was handed to upstream's send path, or was too short to
     * keep: the entry is dropped and its journal deleted. The media file is
     * upstream's and is left alone.
     */
    public void finish(PurpleRecordingEntry e) throws IOException {
        entries.remove(e);
        deleteJournal(e);
        save();
    }

    /**
     * The user discarded the recording. With {@code keep} (keep_discarded_p)
     * its bytes are copied into the bin from {@code bytes}, the descriptor the
     * registry held open on the recorder's file (upstream may already have
     * deleted the name), and finalised there as a "Discarded" item; otherwise
     * the entry is dropped.
     */
    public boolean discard(PurpleRecordingEntry e, FileChannel bytes, boolean keep, long nowSeconds) throws IOException {
        if (!keep || bytes == null) {
            finish(e);
            return false;
        }
        final File target = binFile(e);
        try {
            PurpleRecordingFiles.copy(bytes, target);
        } catch (IOException failure) {
            // A full disk: no entry owns a partial copy, so nothing would sweep it.
            target.delete();
            throw failure;
        }
        e.media = target.getAbsolutePath();
        e.discarded = true;
        e.state = PurpleRecordingEntry.UNSENT;
        e.binnedAt = nowSeconds;
        settle(e, nowSeconds, new Outcome());
        save();
        return true;
    }

    // ---- At start --------------------------------------------------------------

    /**
     * Every entry still recording or sending belongs to a crash. One whose file
     * is gone is dropped (upstream deleted or sent it); one whose file is a
     * finished recording without pending queue ownership ended cleanly;
     * every other one moves into the bin and is recovered there.
     */
    public Outcome recoverAtStart(long nowSeconds) throws IOException {
        final Outcome outcome = new Outcome();
        for (PurpleRecordingEntry e : new ArrayList<>(entries)) {
            if (!e.unfinished()) { cleanupSnapshot(e); continue; }
            if (e.longOriginal && e.originalGeneration != null) {
                try { keepLongOriginal(e, nowSeconds); cleanupSnapshot(e); count(e, outcome); }
                catch (IOException failure) { outcome.failures++; }
                continue;
            }
            File reserved = null;
            boolean drop = false, discarded = false;
            if (e.snapshotBase != null) {
                for (String policy : new String[]{".error", ".discard", ".drop"}) {
                    final File candidate = new File(e.snapshotBase + policy);
                    if (candidate.isFile()) { reserved = candidate; drop = policy.equals(".drop"); discarded = policy.equals(".discard"); break; }
                }
                if (reserved == null && e.media != null && new File(e.media).isFile()) {
                    final File original = new File(e.media);
                    if (!finished(e, original) || e.awaitingQueue || e.segment) reserved = original;
                }
            }
            if (reserved != null) {
                try {
                    if (drop) {
                        e.snapshotCommitted = true;
                        cleanupSnapshot(e);
                        finish(e);
                    } else snapshotKept(e, reserved, discarded, nowSeconds);
                    cleanupSnapshot(e);
                    if (!drop) {
                        if (PurpleRecordingEntry.UNRECOVERABLE.equals(e.state)) outcome.unrecoverable++;
                        else count(e, outcome);
                    }
                } catch (IOException failure) { outcome.failures++; }
                continue;
            }
            final File media = e.media == null ? null : new File(e.media);
            if (media == null || !media.isFile() || media.length() == 0) {
                outcome.missing++;
                entries.remove(e);
                deleteJournal(e);
                continue;
            }
            if (finished(e, media) && !e.segment && !e.awaitingQueue) {
                outcome.finishedCleanly++;
                entries.remove(e);
                deleteJournal(e);
                continue;
            }
            try {
                reserveSnapshot(e, media, e.journal == null ? null : new File(e.journal));
                snapshotKept(e, media, e.discarded, nowSeconds);
                cleanupSnapshot(e);
                if (PurpleRecordingEntry.UNRECOVERABLE.equals(e.state)) outcome.unrecoverable++;
                else count(e, outcome);
            } catch (IOException failure) {
                outcome.failures++;
            }
        }
        save();
        return outcome;
    }

    /** Deletes the bin items {@code bin_keep} has expired, and their files. */
    public Outcome sweep(long nowSeconds, int keepSeconds) throws IOException {
        final Outcome outcome = new Outcome();
        for (PurpleRecordingEntry e : new ArrayList<>(entries)) {
            if (!e.inBin() || !core.binItemExpired(e.binnedAt, nowSeconds, keepSeconds)) continue;
            cleanupSnapshot(e);
            deleteFiles(e);
            entries.remove(e);
            outcome.expired++;
        }
        if (outcome.expired > 0) save();
        return outcome;
    }

    /**
     * The one-time orphan scan: recordings that crashed before Purple kept a
     * registry. V2 voice temporary files and unfinished .ogg recordings in the
     * audio directories, and recorder-named .mp4 files with {@code ftyp} and
     * {@code mdat} but no {@code moov} in the round directories, become
     * "Unknown chat" items. Finished files are never touched: a recording a
     * message refers to always has its end-of-stream flag or its index.
     */
    public Outcome scanOrphans(List<File> audioDirs, List<File> roundDirs, long nowSeconds) throws IOException {
        final Outcome outcome = new Outcome();
        final Set<String> known = new HashSet<>();
        for (PurpleRecordingEntry e : entries) {
            if (e.media != null) known.add(e.media);
            if (e.snapshotSource != null) known.add(e.snapshotSource);
        }
        for (File dir : audioDirs) {
            final File[] files = dir == null ? null : dir.listFiles();
            for (int i = 0; files != null && i < files.length; i++) {
                final File f = files[i];
                if (nowSeconds * 1000 - f.lastModified() < 10_000) continue; // a recording that may be live right now
                if (!f.isFile() || known.contains(f.getAbsolutePath()) || !PurpleRecordingFiles.orphanVoiceName(f.getName())) continue;
                final PurpleRecordingCore.OggScan scan = core.scanOgg(f);
                if (!scan.read || scan.clean() || !scan.hasAudio) continue;
                adoptOrphan(f, PurpleRecordingEntry.VOICE, f.lastModified(), nowSeconds, outcome);
            }
        }
        for (File dir : roundDirs) {
            final File[] files = dir == null ? null : dir.listFiles();
            for (int i = 0; files != null && i < files.length; i++) {
                final File f = files[i];
                if (nowSeconds * 1000 - f.lastModified() < 10_000) continue; // a recording that may be live right now
                if (!f.isFile() || known.contains(f.getAbsolutePath()) || !PurpleRecordingFiles.roundName(f.getName())) continue;
                if (PurpleRecordingFiles.mp4Finished(f) != 0) continue;
                adoptOrphan(f, PurpleRecordingEntry.ROUND, f.lastModified(), nowSeconds, outcome);
            }
        }
        save();
        return outcome;
    }

    private void adoptOrphan(File f, String kind, long modified, long nowSeconds, Outcome outcome) {
        final PurpleRecordingEntry e = new PurpleRecordingEntry();
        e.id = Long.toString(modified, 36) + "-o" + Integer.toString(nextId++, 36);
        e.kind = kind;
        e.orphan = true;
        e.startedAt = modified;
        try {
            final File target = binFile(e);
            PurpleRecordingFiles.move(f, target);
            e.media = target.getAbsolutePath();
            e.state = PurpleRecordingEntry.RECOVERED;
            e.binnedAt = nowSeconds;
            entries.add(e);
            outcome.orphans++;
            settle(e, nowSeconds, outcome);
        } catch (IOException failure) {
            outcome.failures++;
        }
    }

    // ---- The bin's own actions ----------------------------------------------

    /** Deletes one bin item and its files. */
    public void delete(PurpleRecordingEntry e) throws IOException {
        cleanupSnapshot(e);
        deleteFiles(e);
        entries.remove(e);
        save();
    }

    /** Deletes every bin item; recordings in progress are left alone. */
    public int clear() throws IOException {
        int n = 0;
        for (PurpleRecordingEntry e : new ArrayList<>(entries)) {
            if (!e.inBin()) continue;
            cleanupSnapshot(e);
            deleteFiles(e);
            entries.remove(e);
            n++;
        }
        save();
        return n;
    }

    /** A bin item was sent again: it leaves the bin. */
    public void sent(PurpleRecordingEntry e) throws IOException {
        delete(e);
    }

    // ---- Internals -----------------------------------------------------------

    /**
     * Makes a bin item playable where its bytes now are: voice through the
     * Ogg finaliser, a round through recovery into a rebuilt file. What cannot
     * be made playable stays raw as unrecoverable.
     */
    private void settle(PurpleRecordingEntry e, long nowSeconds, Outcome outcome) throws IOException {
        final File media = new File(e.media);
        final String inBinState = e.discarded ? PurpleRecordingEntry.UNSENT : PurpleRecordingEntry.RECOVERED;
        if (!e.round()) {
            final PurpleRecordingCore.OggFinish finish = core.finaliseOgg(media);
            if (finish.playable()) {
                e.state = inBinState;
                if (finish.durationMs > 0) e.durationMs = finish.durationMs;
                count(e, outcome);
            } else {
                e.state = PurpleRecordingEntry.UNRECOVERABLE;
                outcome.unrecoverable++;
            }
            return;
        }
        if (PurpleRecordingFiles.mp4Finished(media) == 1) {
            final long duration = PurpleRecordingFiles.mp4DurationMs(media);
            if (duration > 0) e.durationMs = duration;
            e.state = inBinState;
            count(e, outcome);
            return;
        }
        final File journal = journalFor(e);
        final File rebuilt = new File(media.getPath() + ".rebuilt");
        final RoundResult result = core.recoverRound(media, journal, rebuilt);
        if (result == null || !rebuilt.isFile()) {
            rebuilt.delete();
            e.state = PurpleRecordingEntry.UNRECOVERABLE;
            outcome.unrecoverable++;
            return;
        }
        if (!rebuilt.renameTo(media)) {
            rebuilt.delete();
            throw new IOException("rename failed");
        }
        if (journal != null && e.journal != null && journal.getAbsolutePath().equals(e.journal)) {
            journal.delete();
            e.journal = null;
        }
        e.partial = result.partial;
        if (result.durationMs > 0) e.durationMs = result.durationMs;
        e.state = inBinState;
        count(e, outcome);
    }

    /**
     * Whether the recorder's file is a finished recording: an Ogg stream that
     * ended, or an MP4 with its index. Such a recording ended cleanly between
     * the registry's writes, and upstream owns it.
     */
    private boolean finished(PurpleRecordingEntry e, File media) {
        if (e.round()) return PurpleRecordingFiles.mp4Finished(media) == 1;
        return core.scanOgg(media).clean();
    }

    private static File journalFor(PurpleRecordingEntry e) {
        if (e.journal != null && new File(e.journal).isFile()) return new File(e.journal);
        return null;
    }

    private static void count(PurpleRecordingEntry e, Outcome outcome) {
        if (e.discarded) return;
        if (e.partial) outcome.partial++;
        else outcome.recovered++;
    }

    private File binFile(PurpleRecordingEntry e) throws IOException {
        return new File(manifest.binDir(), e.id + (e.round() ? ".mp4" : ".ogg"));
    }

    /** Deletes a bin item's media and journal: only files inside the bin directory. */
    private void deleteFiles(PurpleRecordingEntry e) {
        for (String path : new String[]{e.media, e.journal}) {
            if (path == null) continue;
            final File f = new File(path);
            if (manifest.bin.getAbsolutePath().equals(f.getAbsoluteFile().getParent())) f.delete();
        }
    }

    private static void deleteJournal(PurpleRecordingEntry e) {
        if (e.journal != null) new File(e.journal).delete();
    }

    private void save() throws IOException {
        manifest.save(entries);
    }
}
