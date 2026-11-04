/*
 * This is the source code of Purple Telegram for Android.
 *
 * Rebuilds a crashed round video (F10): purple-core's RecoverRound reads the
 * journal and scans the media data, and upstream's own MP4Builder writes the
 * result, so it has the layout upstream sends. See
 * docs/media-recording-design.md, "Round video", and purple-core's
 * docs/recording-formats.md, "Recovering a round video".
 */

package org.telegram.messenger.purple;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import org.telegram.messenger.video.MP4Builder;
import org.telegram.messenger.video.Mp4Movie;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

public final class PurpleRoundRecovery {

    private PurpleRoundRecovery() {
    }

    /** In upstream's native library (purple_jni/round_recovery_bridge.cpp), beside the AAC measure shim. */
    private static native Object[] recoverNative(byte[] media, byte[] journal);

    public static final int EXACT = 0;
    public static final int REPAIRED = 1;
    public static final int PARTIAL = 2;
    public static final int UNRECOVERABLE = 3;

    /** What purple-core recovered, before the rebuild. */
    public static final class Recovery {
        /** {@link #EXACT}, {@link #REPAIRED}, {@link #PARTIAL} or {@link #UNRECOVERABLE}. */
        public final int status;
        /** purple-core's RoundUnrecoverable: 0 none, 1 no config, 2 unknown version, 3 not MP4, 4 no video track, 5 no samples. */
        public final int reason;
        public final int exactSamples;
        public final int repairedSamples;
        public final int journalDropped;
        public final long encoderOffsetUs;
        /** Five values per sample: track, sync, size, offset, presentation time (us). */
        public final long[] samples;
        public final String[] mimes;
        /** Seven values per track: kind (1 video, 2 audio), timescale, width, height, sample rate, channels, csd count. */
        public final int[] tracks;
        public final byte[][] csd;
        /** Split mdat, width, height, rotation. */
        public final int[] config;

        Recovery(Object[] packed) {
            final long[] summary = (long[]) packed[0];
            status = (int) summary[0];
            reason = (int) summary[1];
            exactSamples = (int) summary[2];
            repairedSamples = (int) summary[3];
            journalDropped = (int) summary[4];
            encoderOffsetUs = summary[6];
            samples = (long[]) packed[1];
            mimes = (String[]) packed[2];
            tracks = (int[]) packed[3];
            csd = (byte[][]) packed[4];
            config = (int[]) packed[5];
        }

        public int sampleCount() {
            return samples.length / 5;
        }
    }

    /** Asks purple-core; null when the native library cannot be asked. */
    public static Recovery analyse(File media, File journal) {
        try {
            final Object[] packed = recoverNative(PurpleRecordingCore.utf8(media),
                    journal == null ? null : PurpleRecordingCore.utf8(journal));
            return packed == null ? null : new Recovery(packed);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Recovers {@code media} (with {@code journal}, which may be null) into
     * {@code out}. Null when nothing could be rebuilt: the bin then keeps the
     * raw file.
     */
    public static PurpleRecordingStore.RoundResult recover(File media, File journal, File out) {
        final Recovery recovery = analyse(media, journal);
        if (recovery == null || recovery.status == UNRECOVERABLE || recovery.sampleCount() == 0) return null;
        try {
            final long durationUs = rebuild(recovery, media, out);
            return new PurpleRecordingStore.RoundResult(recovery.status == PARTIAL, durationUs / 1000);
        } catch (Throwable e) {
            out.delete();
            return null;
        }
    }

    /**
     * Writes the samples through MP4Builder's public API: createMovie with the
     * recorded split flag, addTrack with a MediaFormat rebuilt from the config
     * header, writeSampleData(track, bytes, info, false) per sample (a video
     * sample on disk already holds its 4-byte length), then finishMovie.
     *
     * @return the duration, from the first to the last presentation time
     */
    static long rebuild(Recovery recovery, File media, File out) throws Exception {
        final Mp4Movie movie = new Mp4Movie();
        movie.setCacheFile(out);
        movie.setRotation(recovery.config[3]);
        movie.setSize(recovery.config[1], recovery.config[2]);
        final MP4Builder builder = new MP4Builder().createMovie(movie, recovery.config[0] != 0, false);
        final int trackCount = recovery.mimes.length;
        final int[] mapped = new int[trackCount];
        int csdAt = 0;
        for (int t = 0; t < trackCount; t++) {
            final int base = t * 7;
            final boolean audio = recovery.tracks[base] == 2;
            final String mime = recovery.mimes[t];
            final MediaFormat format = audio
                    ? MediaFormat.createAudioFormat(mime, recovery.tracks[base + 4], recovery.tracks[base + 5])
                    : MediaFormat.createVideoFormat(mime, recovery.tracks[base + 2], recovery.tracks[base + 3]);
            final int buffers = recovery.tracks[base + 6];
            for (int i = 0; i < buffers; i++) {
                format.setByteBuffer("csd-" + i, ByteBuffer.wrap(recovery.csd[csdAt + i]));
            }
            if (!audio && buffers > 0) avcProfile(format, recovery.csd[csdAt]);
            csdAt += buffers;
            mapped[t] = builder.addTrack(format, audio);
        }
        long first = Long.MAX_VALUE;
        long last = 0;
        try (RandomAccessFile in = new RandomAccessFile(media, "r")) {
            byte[] bytes = new byte[64 * 1024];
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int s = 0; s < recovery.sampleCount(); s++) {
                final int at = s * 5;
                final int track = (int) recovery.samples[at];
                final int size = (int) recovery.samples[at + 2];
                final long pts = recovery.samples[at + 4];
                if (bytes.length < size) bytes = new byte[size];
                in.seek(recovery.samples[at + 3]);
                in.readFully(bytes, 0, size);
                info.set(0, size, pts, recovery.samples[at + 1] != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                builder.writeSampleData(mapped[track], ByteBuffer.wrap(bytes, 0, size), info, false);
                first = Math.min(first, pts);
                last = Math.max(last, pts);
            }
        }
        builder.finishMovie();
        return first == Long.MAX_VALUE ? 0 : Math.max(0, last - first);
    }

    /**
     * The H.264 profile and level for Track's avcC box, from the SPS the
     * recorder's encoder produced (csd-0: a start code, the NAL header, then
     * profile_idc, the constraint flags and level_idc), which MediaFormat
     * carried as "profile" and "level" when the round was recorded.
     */
    static void avcProfile(MediaFormat format, byte[] sps) {
        int at = 0;
        while (at + 3 < sps.length && !(sps[at] == 0 && sps[at + 1] == 0 && sps[at + 2] == 1)) at++;
        at += 3;
        if (at + 3 >= sps.length || (sps[at] & 0x1f) != 7) return;
        final int profileIdc = sps[at + 1] & 0xff;
        final int levelIdc = sps[at + 3] & 0xff;
        final int profile;
        switch (profileIdc) {
        case 66: profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline; break;
        case 77: profile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain; break;
        case 88: profile = MediaCodecInfo.CodecProfileLevel.AVCProfileExtended; break;
        case 100: profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh; break;
        default: profile = -1;
        }
        if (profile >= 0) format.setInteger("profile", profile);
        final int level;
        switch (levelIdc) {
        case 10: level = MediaCodecInfo.CodecProfileLevel.AVCLevel1; break;
        case 11: level = MediaCodecInfo.CodecProfileLevel.AVCLevel11; break;
        case 12: level = MediaCodecInfo.CodecProfileLevel.AVCLevel12; break;
        case 13: level = MediaCodecInfo.CodecProfileLevel.AVCLevel13; break;
        case 20: level = MediaCodecInfo.CodecProfileLevel.AVCLevel2; break;
        case 21: level = MediaCodecInfo.CodecProfileLevel.AVCLevel21; break;
        case 22: level = MediaCodecInfo.CodecProfileLevel.AVCLevel22; break;
        case 30: level = MediaCodecInfo.CodecProfileLevel.AVCLevel3; break;
        case 31: level = MediaCodecInfo.CodecProfileLevel.AVCLevel31; break;
        case 32: level = MediaCodecInfo.CodecProfileLevel.AVCLevel32; break;
        case 40: level = MediaCodecInfo.CodecProfileLevel.AVCLevel4; break;
        case 41: level = MediaCodecInfo.CodecProfileLevel.AVCLevel41; break;
        case 42: level = MediaCodecInfo.CodecProfileLevel.AVCLevel42; break;
        case 50: level = MediaCodecInfo.CodecProfileLevel.AVCLevel5; break;
        case 51: level = MediaCodecInfo.CodecProfileLevel.AVCLevel51; break;
        case 52: level = MediaCodecInfo.CodecProfileLevel.AVCLevel52; break;
        default: level = -1;
        }
        if (level >= 0) format.setInteger("level", level);
    }
}
