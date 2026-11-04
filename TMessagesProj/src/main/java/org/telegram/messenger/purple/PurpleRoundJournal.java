/*
 * This is the source code of Purple Telegram for Android.
 *
 * The round video journal (F10 option (a)): the sidecar <file>.pjr written
 * next to a recording round video, so a crash can be recovered exactly. See
 * docs/media-recording-design.md, "Round video: the journal", and the byte
 * layout in purple-core's docs/recording-formats.md.
 */

package org.telegram.messenger.purple;

import android.media.MediaCodec;
import android.media.MediaFormat;

import com.googlecode.mp4parser.util.Matrix;

import org.telegram.messenger.video.Mp4Movie;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Upstream's {@code MP4Builder} keeps a round's sample table in memory until
 * {@code finishMovie} writes {@code moov}, so a crash leaves media data with
 * no index. The two round recorders register their movie here right after
 * {@code setCacheFile} (one hook line in {@code InstantCameraView}'s
 * {@code VideoRecorder} and one in {@code RoundVideoMp4Writer}), and
 * {@code Mp4Movie.addTrack} and {@code Mp4Movie.addSample} each call in once.
 * Video conversions, the camera screen and stories use {@code MP4Builder} too
 * and are never registered, so their calls return at the first test.
 *
 * <p>The sent file is unchanged. The journal is append-only:
 *
 * <ul>
 * <li>the config header (the header record and one track record per track,
 *     with the codec configuration) is written once at the start, also when
 *     {@code round_journal} is Off, because repair needs it to decode audio;</li>
 * <li>samples are buffered and appended as one batch every
 *     {@code round_journal} interval (1, 2 or 5 s), on the muxer's own
 *     thread;</li>
 * <li>it is synced every 5 s only where the recorder's {@code MP4Builder}
 *     syncs too: a process crash keeps written data in the page cache, and
 *     only power loss needs the sync.</li>
 * </ul>
 *
 * Every record is encoded by purple-core ({@code EncodeRoundJournal*}), so the
 * layout is stated once. A write that fails (a full disk) stops the journal
 * quietly; the recording continues as upstream records it.
 */
public final class PurpleRoundJournal {

    private PurpleRoundJournal() {
    }

    /** What the registry hears from the journal. */
    public interface Listener {
        /** A round recorder registered its movie: its media file and journal. */
        void registered(File media, File journal);

        /** A journal stopped writing (a full disk, or the core could not be asked). */
        void stopped();
    }

    static volatile Listener listener;

    private static final long SYNC_INTERVAL_NS = 5_000_000_000L;

    private static final Map<Mp4Movie, Journal> journals = Collections.synchronizedMap(new WeakHashMap<>());
    /** Whether any movie is registered: the one test every other MP4Builder user pays. */
    private static volatile boolean any;

    /**
     * A round recorder's movie, right after {@code setCacheFile}.
     *
     * @param split MP4Builder's split-mdat mode, as the recorder passes it to
     *     {@code createMovie}
     * @param sync whether the recorder's MP4Builder syncs its file
     */
    public static void register(Mp4Movie movie, boolean split, boolean sync) {
        try {
            final File media = movie == null ? null : movie.getCacheFile();
            if (media == null) return;
            final int interval = PurpleRecordingSettings.read().roundJournalSeconds;
            final Journal journal = new Journal(new File(media.getPath() + ".pjr"), split, sync, interval);
            synchronized (journals) {
                journals.put(movie, journal);
                any = true;
            }
            final Listener l = listener;
            if (l != null) l.registered(media, journal.file);
        } catch (Throwable ignored) {
            // The journal is a safety net; it never stops a recording.
        }
    }

    /** {@code Mp4Movie.addTrack}, before the track is added: {@code index} is the index it will get. */
    public static void onTrack(Mp4Movie movie, int index, MediaFormat format, boolean audio) {
        if (!any) return;
        final Journal journal = journals.get(movie);
        if (journal != null) journal.track(movie, index, format, audio);
    }

    /** {@code Mp4Movie.addSample}: one sample at its data offset in the media file. */
    public static void onSample(Mp4Movie movie, int track, long offset, MediaCodec.BufferInfo info) {
        if (!any) return;
        final Journal journal = journals.get(movie);
        if (journal != null) journal.sample(track, offset, info);
    }

    /** Appends what is buffered now (a pause, so a crash while paused is exact). */
    public static void flush(String journalPath) {
        final Journal journal = find(journalPath);
        if (journal != null) journal.flush(true);
    }

    /** The recording ended: flushes and closes its journal and forgets the movie. */
    public static void close(String journalPath) {
        if (journalPath == null) return;
        synchronized (journals) {
            for (Map.Entry<Mp4Movie, Journal> entry : new ArrayList<>(journals.entrySet())) {
                if (entry.getValue().file.getPath().equals(journalPath)) {
                    entry.getValue().close();
                    journals.remove(entry.getKey());
                }
            }
            any = !journals.isEmpty();
        }
    }

    private static Journal find(String journalPath) {
        if (journalPath == null) return null;
        synchronized (journals) {
            for (Journal journal : journals.values()) {
                if (journal.file.getPath().equals(journalPath)) return journal;
            }
        }
        return null;
    }

    static int rotation(Matrix matrix) {
        if (Matrix.ROTATE_90.equals(matrix)) return 90;
        if (Matrix.ROTATE_180.equals(matrix)) return 180;
        if (Matrix.ROTATE_270.equals(matrix)) return 270;
        return 0;
    }

    /** One journal. Its methods are synchronized: the muxer's thread appends, the registry flushes and closes. */
    static final class Journal {
        final File file;
        final boolean split;
        final boolean sync;
        final int intervalSeconds;
        private FileOutputStream out;
        private boolean started;
        private boolean stopped;
        private long[] pending = new long[5 * 128];
        private int count;
        private long lastFlushNs = System.nanoTime();
        private long lastSyncNs = System.nanoTime();

        Journal(File file, boolean split, boolean sync, int intervalSeconds) {
            this.file = file;
            this.split = split;
            this.sync = sync;
            this.intervalSeconds = intervalSeconds;
        }

        synchronized void track(Mp4Movie movie, int index, MediaFormat format, boolean audio) {
            if (stopped) return;
            try {
                if (!started) {
                    started = true;
                    out = new FileOutputStream(file, false);
                    append(PurpleRecordingCore.journalStartNative(split, rotation(movie.getMatrix()), movie.getWidth(), movie.getHeight()));
                }
                final String mime = format.getString(MediaFormat.KEY_MIME);
                final int first;
                final int second;
                final int timescale;
                if (audio) {
                    first = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    second = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    timescale = first;
                } else {
                    first = format.getInteger(MediaFormat.KEY_WIDTH);
                    second = format.getInteger(MediaFormat.KEY_HEIGHT);
                    timescale = 90000;
                }
                final ArrayList<byte[]> csd = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    final ByteBuffer buffer = format.containsKey("csd-" + i) ? format.getByteBuffer("csd-" + i) : null;
                    if (buffer == null) break;
                    final ByteBuffer copy = buffer.duplicate();
                    copy.position(0);
                    final byte[] bytes = new byte[copy.limit()];
                    copy.get(bytes);
                    csd.add(bytes);
                }
                append(PurpleRecordingCore.journalTrackNative(index, audio,
                        (mime == null ? "" : mime).getBytes(StandardCharsets.US_ASCII), timescale, first, second,
                        csd.toArray(new byte[0][])));
            } catch (Throwable e) {
                stop();
            }
        }

        synchronized void sample(int track, long offset, MediaCodec.BufferInfo info) {
            if (stopped || !started || intervalSeconds <= 0) return;
            if (count * 5 + 5 > pending.length) pending = java.util.Arrays.copyOf(pending, pending.length * 2);
            final int at = count * 5;
            pending[at] = track;
            pending[at + 1] = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ? 1 : 0;
            pending[at + 2] = info.size;
            pending[at + 3] = offset;
            pending[at + 4] = info.presentationTimeUs;
            count++;
            if (System.nanoTime() - lastFlushNs >= intervalSeconds * 1_000_000_000L || count >= 65536) flush(false);
        }

        synchronized void flush(boolean force) {
            lastFlushNs = System.nanoTime();
            if (stopped || count == 0) return;
            try {
                final byte[] batch = PurpleRecordingCore.journalBatchNative(pending, count);
                count = 0;
                append(batch);
                if (sync && (force || System.nanoTime() - lastSyncNs >= SYNC_INTERVAL_NS)) {
                    lastSyncNs = System.nanoTime();
                    out.getFD().sync();
                }
            } catch (Throwable e) {
                stop();
            }
        }

        synchronized void close() {
            flush(true);
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                out = null;
            }
            stopped = true;
        }

        private void append(byte[] record) throws IOException {
            if (record == null || record.length == 0) throw new IOException("the core refused a record");
            out.write(record);
            out.flush();
        }

        private void stop() {
            if (stopped) return;
            stopped = true;
            count = 0;
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                out = null;
            }
            final Listener l = listener;
            if (l != null) l.stopped();
        }
    }
}
