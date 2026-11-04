/*
 * This is the source code of Purple Telegram for Android.
 *
 * F10's calls into purple-core: the Ogg finaliser and page walker, and the
 * Recording bin expiry rule. The byte formats and the decisions live in the
 * core (docs/recording-formats.md there); this class only carries them across
 * JNI, so Java never re-implements a parser.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.nio.charset.StandardCharsets;

public final class PurpleRecordingCore {

    private PurpleRecordingCore() {
    }

    private static native boolean keepOnPauseNative(boolean keepChats, boolean enabled, boolean round, boolean background, boolean ready);

    public static boolean keepOnPause(boolean round, boolean background, boolean ready) {
        try {
            PurpleCore.ensureLoaded();
            PurpleRecordingSettings settings = PurpleRecordingSettings.read();
            return keepOnPauseNative(settings.keepAcrossChats, settings.background, round, background, ready);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return false;
        }
    }

    private static native long[] finaliseOggNative(byte[] path);

    private static native long[] scanOggNative(byte[] path);

    private static native boolean binItemExpiredNative(long binnedAt, long now, int keepSeconds);

    static native byte[] journalStartNative(boolean splitMdat, int rotation, int width, int height);

    static native byte[] journalTrackNative(int index, boolean audio, byte[] mime, int timescale, int first, int second,
            byte[][] csd);

    static native byte[] journalBatchNative(long[] samples, int count);

    static native long[] journalReadNative(byte[] journal);

    /** What finalising an interrupted Ogg/Opus voice recording did. */
    public static final class OggFinish {
        public static final int CLEAN = 0;
        public static final int FINALISED = 1;
        public static final int UNRECOVERABLE = 2;

        /** {@link #CLEAN}, {@link #FINALISED} or {@link #UNRECOVERABLE}. */
        public final int status;
        /** The duration from the last good granule position, or -1. */
        public final long durationMs;
        public final long droppedBytes;
        public final boolean endOfStreamSet;
        /** The file could not be read or written (or the core could not be asked). */
        public final boolean ioError;

        OggFinish(long[] packed) {
            final boolean ok = packed != null && packed.length == 5;
            status = ok ? (int) packed[0] : UNRECOVERABLE;
            durationMs = ok ? packed[1] : -1;
            droppedBytes = ok ? packed[2] : 0;
            endOfStreamSet = ok && packed[3] != 0;
            ioError = !ok || packed[4] != 0;
        }

        /** Whether the file now holds a playable voice recording. */
        public boolean playable() {
            return status == CLEAN || status == FINALISED;
        }
    }

    /** The page walk of an Ogg file. */
    public static final class OggScan {
        public final boolean read;
        public final long pages;
        public final boolean endOfStream;
        public final long goodBytes;
        public final long totalBytes;
        public final long durationMs;
        public final boolean hasAudio;
        public final boolean opus;

        OggScan(long[] packed) {
            final boolean ok = packed != null && packed.length == 8 && packed[0] != 0;
            read = ok;
            pages = ok ? packed[1] : 0;
            endOfStream = ok && packed[2] != 0;
            goodBytes = ok ? packed[3] : 0;
            totalBytes = ok ? packed[4] : 0;
            durationMs = ok ? packed[5] : -1;
            hasAudio = ok && packed[6] != 0;
            opus = ok && packed[7] != 0;
        }

        /** Every byte is a good page and the stream ended: a finished recording. */
        public boolean clean() {
            return read && pages > 0 && endOfStream && goodBytes == totalBytes;
        }
    }

    /**
     * Finalises an interrupted voice recording in place: cuts it after the
     * last good page and sets the end-of-stream flag. An unrecoverable file is
     * left as it was.
     */
    public static OggFinish finaliseOgg(File file) {
        try {
            PurpleCore.ensureLoaded();
            return new OggFinish(finaliseOggNative(utf8(file)));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new OggFinish(null);
        }
    }

    /** Walks an Ogg file's pages without changing it. */
    public static OggScan scanOgg(File file) {
        try {
            PurpleCore.ensureLoaded();
            return new OggScan(scanOggNative(utf8(file)));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new OggScan(null);
        }
    }

    /**
     * Whether a bin item that entered the bin at {@code binnedAt} (Unix
     * seconds) is expired at {@code now} under {@code bin_keep}. When the core
     * cannot be asked nothing expires: deleting is the one mistake that cannot
     * be undone.
     */
    public static boolean binItemExpired(long binnedAt, long now, int keepSeconds) {
        try {
            PurpleCore.ensureLoaded();
            return binItemExpiredNative(binnedAt, now, keepSeconds);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return false;
        }
    }

    /** What purple-core read from a journal's bytes. */
    public static final class JournalRead {
        public static final int MISSING = 0;
        public static final int UNKNOWN_VERSION = 1;
        public static final int NO_CONFIG = 2;
        public static final int OK = 3;

        public final int status;
        public final int tracks;
        public final int batches;
        public final int samples;
        public final long goodBytes;
        public final boolean damaged;
        public final boolean splitMdat;

        JournalRead(long[] packed) {
            final boolean ok = packed != null && packed.length == 7;
            status = ok ? (int) packed[0] : MISSING;
            tracks = ok ? (int) packed[1] : 0;
            batches = ok ? (int) packed[2] : 0;
            samples = ok ? (int) packed[3] : 0;
            goodBytes = ok ? packed[4] : 0;
            damaged = ok && packed[5] != 0;
            splitMdat = ok && packed[6] != 0;
        }
    }

    /** Reads a journal with purple-core's reader (the bin's checks and the host suite). */
    public static JournalRead readJournal(byte[] journal) {
        try {
            PurpleCore.ensureLoaded();
            return new JournalRead(journalReadNative(journal));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return new JournalRead(null);
        }
    }

    static byte[] utf8(File file) {
        return file.getAbsolutePath().getBytes(StandardCharsets.UTF_8);
    }
}
