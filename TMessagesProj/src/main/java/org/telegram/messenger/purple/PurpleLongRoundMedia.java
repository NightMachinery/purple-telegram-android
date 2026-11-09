package org.telegram.messenger.purple;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.MessageDigest;

public final class PurpleLongRoundMedia {
    private static final long RESERVE = 64L * 1024 * 1024;
    public static final class Facts {
        public final long durationMs;
        public final int width, height, rotation, audioTrack;
        Facts(long durationMs, int width, int height, int rotation, int audioTrack) {
            this.durationMs = durationMs; this.width = width; this.height = height;
            this.rotation = rotation; this.audioTrack = audioTrack;
        }
    }
    public static final class Output {
        public final File file;
        public final Facts facts;
        public final boolean circle;
        Output(File file, Facts facts, boolean circle) { this.file = file; this.facts = facts; this.circle = circle; }
        public VideoEditedInfo info() {
            final VideoEditedInfo result = new VideoEditedInfo() {
                @Override public boolean needConvert() { return false; }
            };
            result.startTime = result.endTime = -1;
            result.roundVideo = circle;
            result.estimatedSize = file.length(); result.estimatedDuration = facts.durationMs;
            result.resultWidth = result.originalWidth = facts.width;
            result.resultHeight = result.originalHeight = facts.height;
            result.rotationValue = facts.rotation; result.framerate = 30;
            result.originalPath = file.getAbsolutePath();
            return result;
        }
    }
    public static final class Work {
        public final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<File> owned = new ArrayList<>();
        private final long deadline = SystemClock.elapsedRealtime() + 30 * 60_000L;
        private void check() throws IOException {
            if (cancelled.get() || SystemClock.elapsedRealtime() > deadline) throw new IOException("cancelled");
        }
        private File create(File directory) throws IOException {
            check();
            final File file = File.createTempFile("purple-long-", ".mp4", directory);
            owned.add(file);
            return file;
        }
        public void discard() {
            for (File file : owned) if (file.exists() && !file.delete()) PurpleDiagLog.event("recordings", "long_copy_cleanup", PurpleDiagLog.reason("io_failed"));
            owned.clear();
        }
        public void handed(File file) { owned.remove(file); }
    }
    private PurpleLongRoundMedia() { }

    public static Facts inspect(File file, boolean part) throws Exception {
        if (!file.isFile() || file.length() == 0 || file.length() > 256L * 1024 * 1024) throw new IOException("media");
        final MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        long duration;
        int width, height, rotation;
        try {
            metadata.setDataSource(file.getAbsolutePath());
            duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            width = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            final String value = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            rotation = value == null ? 0 : Integer.parseInt(value);
        } finally { metadata.release(); }
        if (width <= 0 || width != height || width > 1440 || duration <= 0 || duration > PurpleLongRoundPlan.MAX_MS) {
            throw new IOException("format");
        }
        final MediaExtractor extractor = new MediaExtractor();
        int audio = -1, video = -1;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat format = extractor.getTrackFormat(i);
                final String mime = format.getString(MediaFormat.KEY_MIME);
                if ("video/avc".equals(mime)) video = i;
                if ("audio/mp4a-latm".equals(mime)) audio = i;
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    duration = Math.max(duration, (format.getLong(MediaFormat.KEY_DURATION) + 999) / 1000);
                }
            }
            if (video < 0 || audio < 0) throw new IOException("tracks");
            if (part) {
                for (int track : new int[]{video, audio}) {
                    extractor.selectTrack(track);
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    final long first = extractor.getSampleTime();
                    if (first < 0 || first > 100_000 || track == video &&
                            (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) throw new IOException("start");
                    long previous = -1;
                    int count = 0;
                    while (extractor.getSampleTime() >= 0) {
                        final long pts = extractor.getSampleTime();
                        if (pts < previous || pts > 60_000_000L || ++count > 100_000) throw new IOException("timestamps");
                        previous = pts;
                        if (!extractor.advance()) break;
                    }
                    if (previous - first < (duration - 200) * 1000) throw new IOException("missing samples");
                    extractor.unselectTrack(track);
                }
            }
        } finally { extractor.release(); }
        if (duration > PurpleLongRoundPlan.MAX_MS || part && duration > PurpleLongRoundPlan.CIRCLE_MS) throw new IOException("duration");
        return new Facts(duration, width, height, rotation, audio);
    }

    public static List<Output> prepare(File source, File directory, boolean circles, Work work) throws Exception {
        final Facts facts = inspect(source, false);
        final PurpleLongRoundPlan plan = new PurpleLongRoundPlan(facts.durationMs);
        final long budget = source.length() + facts.durationMs * 1_500_000L / 8000;
        if (directory.getUsableSpace() < RESERVE + budget * 2) throw new IOException("space");
        final byte[] before = digest(source, work);
        final List<Output> outputs = new ArrayList<>();
        if (!circles) {
            final File copy = work.create(directory);
            PurpleRecordingFiles.copy(source, copy);
            work.check();
            if (!Arrays.equals(before, digest(copy, work))) throw new IOException("copy");
            outputs.add(new Output(copy, inspect(copy, false), false));
        } else {
            for (int i = 0; i < plan.count; i++) {
                work.check();
                if (directory.getUsableSpace() < RESERVE + budget) throw new IOException("space");
                final File file = work.create(directory);
                final long start = plan.startUs(i), end = plan.endUs(i);
                final VideoEditedInfo info = new VideoEditedInfo();
                info.roundVideo = true;
                final MediaCodecVideoConvertor.ConvertVideoParams params = MediaCodecVideoConvertor.ConvertVideoParams.of(
                        source.getAbsolutePath(), file, 0, facts.rotation, false, facts.width, facts.height,
                        facts.width, facts.height, 30, 1_200_000, 0, start, end, -1, true,
                        (end - start + 999) / 1000, new MediaController.VideoConvertorListener() {
                            @Override public boolean checkConversionCanceled() {
                                return work.cancelled.get() || SystemClock.elapsedRealtime() > work.deadline;
                            }
                            @Override public void didWriteData(long size, float progress) { }
                        }, info);
                final PurpleLongRoundAudioInput audio = new PurpleLongRoundAudioInput(source.getAbsolutePath(), facts.audioTrack);
                params.purpleAudioInput = audio; params.purpleAudioDurationUs = end - start;
                try {
                    if (new MediaCodecVideoConvertor().convertVideo(params)) throw new IOException("conversion");
                } finally { audio.release(); }
                work.check();
                final Facts actual = inspect(file, true);
                plan.validatePart(i, actual.durationMs);
                outputs.add(new Output(file, actual, true));
            }
        }
        if (!Arrays.equals(before, digest(source, work))) throw new IOException("source changed");
        work.check();
        return outputs;
    }
    private static byte[] digest(File file, Work work) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] bytes = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(bytes)) != -1) { work.check(); digest.update(bytes, 0, count); }
        }
        return digest.digest();
    }
}
