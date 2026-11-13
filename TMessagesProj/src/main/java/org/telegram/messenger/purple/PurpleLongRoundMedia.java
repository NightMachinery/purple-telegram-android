package org.telegram.messenger.purple;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import org.telegram.messenger.video.audio_input.AudioInput;
import org.telegram.messenger.video.audio_input.BlankAudioInput;
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
            result.roundVideo = circle; result.muted = facts.audioTrack < 0;
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
        private final PurpleMediaTrace.Take trace = PurpleMediaTrace.operation("split");
        private final List<File> owned = new ArrayList<>();
        private final long deadline = SystemClock.elapsedRealtime() + 30 * 60_000L;
        private void check() throws IOException {
            final boolean cancelledNow = cancelled.get();
            final boolean expired = !cancelledNow && SystemClock.elapsedRealtime() > deadline;
            if (cancelledNow || expired) {
                trace.event(PurpleMediaTrace.Step.SPLIT_CANCELLED, !expired);
                PurpleLongRoundDiagnostics.work(cancelledNow, expired);
                throw new IOException("cancelled");
            }
        }
        private File create(File directory) throws IOException {
            check();
            final File file = File.createTempFile("purple-long-", ".mp4", directory);
            owned.add(file);
            return file;
        }
        public void discard() {
            for (File file : owned) if (file.exists() && !file.delete()) PurpleDiagLog.event("recordings", "long_copy_cleanup", PurpleDiagLog.reason("io_failed"));
            trace.event(PurpleMediaTrace.Step.SPLIT_CLEANUP);
            owned.clear();
        }
        public void handed(File file) { owned.remove(file); }
    }
    private PurpleLongRoundMedia() { }

    public static Facts inspect(File file, boolean part) throws Exception {
        return inspect(file, part, -1);
    }

    private static Facts inspect(File file, boolean part, int partIndex) throws Exception {
        String stage = "media";
        try {
            if (!file.isFile() || file.length() == 0 || file.length() > 256L * 1024 * 1024) {
                PurpleLongRoundDiagnostics.reject(stage, partIndex, "media");
                throw new IOException("media");
            }
            stage = "metadata";
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
            } finally {
                final String previousStage = stage; stage = "metadata_release";
                metadata.release(); stage = previousStage;
            }
            PurpleLongRoundDiagnostics.metadata(partIndex, duration, width, height, rotation);
            if (width <= 0 || width != height || width > 1440 || duration <= 0 || duration > PurpleLongRoundPlan.MAX_MS) {
                PurpleLongRoundDiagnostics.reject(stage, partIndex, "format");
                throw new IOException("format");
            }
            stage = "extractor_open";
            final MediaExtractor extractor = new MediaExtractor();
            int audio = -1, video = -1;
            long audioDurationUs = -1, videoDurationUs = -1;
            try {
                extractor.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    stage = "track_format";
                    final MediaFormat format = extractor.getTrackFormat(i);
                    final String mime = format.getString(MediaFormat.KEY_MIME);
                    if ("video/avc".equals(mime)) video = i;
                    if ("audio/mp4a-latm".equals(mime)) audio = i;
                    else if (mime != null && mime.startsWith("audio/")) {
                        PurpleLongRoundDiagnostics.reject(stage, partIndex, "audio_format");
                        throw new IOException("audio format");
                    }
                    long trackDurationUs = -1;
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        trackDurationUs = format.getLong(MediaFormat.KEY_DURATION);
                        duration = Math.max(duration, (trackDurationUs + 999) / 1000);
                    }
                    if (i == video) videoDurationUs = trackDurationUs;
                    if (i == audio) audioDurationUs = trackDurationUs;
                    PurpleLongRoundDiagnostics.format(partIndex, i, "video/avc".equals(mime) ? "avc"
                            : "audio/mp4a-latm".equals(mime) ? "aac" : "other", trackDurationUs);
                }
                if (video < 0) {
                    PurpleLongRoundDiagnostics.reject(stage, partIndex, "tracks");
                    throw new IOException("tracks");
                }
                if (part) {
                    for (int track : audio < 0 ? new int[]{video} : new int[]{video, audio}) {
                        stage = "track_scan";
                        final String kind = track == video ? "avc" : "aac";
                        extractor.selectTrack(track);
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                        final long first = extractor.getSampleTime();
                        int sync = -1;
                        if (first < 0 || first > 100_000 || track == video &&
                                (sync = extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                            PurpleLongRoundDiagnostics.track(partIndex, track, kind, "start", duration, first, -1, first, 0, sync, true);
                            throw new IOException("start");
                        }
                        final long trackDurationUs = track == video ? videoDurationUs : audioDurationUs;
                        long previous = -1, intervalUs = Long.MAX_VALUE;
                        int count = 0;
                        while (extractor.getSampleTime() >= 0) {
                            final long pts = extractor.getSampleTime();
                            if (pts < previous || pts > 60_000_000L || ++count > 100_000) {
                                PurpleLongRoundDiagnostics.track(partIndex, track, kind, "timestamps", duration, first, previous, pts, count, sync, pts >= previous);
                                throw new IOException("timestamps");
                            }
                            if (previous >= 0 && pts > previous) intervalUs = Math.min(intervalUs, pts - previous);
                            previous = pts;
                            if (!extractor.advance()) break;
                        }
                        long endUs = previous;
                        if (intervalUs <= 200_000 && trackDurationUs >= previous && trackDurationUs <= 60_000_000L) {
                            endUs = Math.min(trackDurationUs, previous + intervalUs);
                        }
                        if ((trackDurationUs >= 0 && trackDurationUs < previous) || endUs - first < (duration - 200) * 1000) {
                            PurpleLongRoundDiagnostics.track(partIndex, track, kind, "missing_samples", duration, first, previous, previous, count, sync, true);
                            throw new IOException("missing samples");
                        }
                        PurpleLongRoundDiagnostics.track(partIndex, track, kind, "ok", duration, first, previous, previous, count, sync, true);
                        stage = "track_unselect";
                        extractor.unselectTrack(track);
                    }
                }
            } finally {
                final String previousStage = stage; stage = "extractor_release";
                extractor.release(); stage = previousStage;
            }
            stage = "inspect_duration";
            PurpleLongRoundDiagnostics.duration(stage, partIndex, duration, part ? PurpleLongRoundPlan.CIRCLE_MS : PurpleLongRoundPlan.MAX_MS);
            if (duration > PurpleLongRoundPlan.MAX_MS || part && duration > PurpleLongRoundPlan.CIRCLE_MS) {
                PurpleLongRoundDiagnostics.reject(stage, partIndex, "duration");
                throw new IOException("duration");
            }
            return new Facts(duration, width, height, rotation, audio);
        } catch (Exception failure) {
            PurpleLongRoundDiagnostics.failure(stage, partIndex, failure);
            throw failure;
        }
    }

    public static List<Output> prepare(File source, File directory, boolean circles, Work work) throws Exception {
        return prepare(source, directory, circles, -1, -1, work);
    }

    public static List<Output> prepare(File source, File directory, boolean circles,
            long requestedStartUs, long requestedEndUs, Work work) throws Exception {
        PurpleMediaTrace.Take previousTrace = PurpleMediaTrace.enter(work.trace);
        work.trace.event(PurpleMediaTrace.Step.SPLIT_CREATED);
        String stage = "source_inspect";
        int partIndex = -1;
        try {
            final Facts facts = inspect(source, false);
            stage = "selection";
            final PurpleLongRoundSelection selection = new PurpleLongRoundSelection(facts.durationMs, requestedStartUs, requestedEndUs);
            final PurpleLongRoundPlan plan = new PurpleLongRoundPlan(selection.durationMs());
            stage = "space";
            final long budget = source.length() + selection.durationMs() * 1_500_000L / 8000;
            if (directory.getUsableSpace() < RESERVE + budget * 2) throw new IOException("space");
            stage = "source_digest";
            final byte[] before = digest(source, work);
            final List<Output> outputs = new ArrayList<>();
            if (!circles && selection.full(facts.durationMs)) {
                partIndex = 0; stage = "copy";
                final File copy = work.create(directory);
                PurpleRecordingFiles.copy(source, copy);
                work.check();
                stage = "copy_digest";
                if (!Arrays.equals(before, digest(copy, work))) throw new IOException("copy");
                outputs.add(new Output(copy, inspect(copy, false), false));
            } else {
                final int count = circles ? plan.count : 1;
                for (int i = 0; i < count; i++) {
                    partIndex = i; stage = "work_check";
                    work.check();
                    stage = "space";
                    if (directory.getUsableSpace() < RESERVE + budget) throw new IOException("space");
                    stage = "create";
                    final File file = work.create(directory);
                    final long start = selection.startUs(i, count), end = selection.endUs(i, count);
                    stage = "conversion_setup";
                    PurpleLongRoundDiagnostics.range(i, start, end);
                    final VideoEditedInfo info = new VideoEditedInfo();
                    info.roundVideo = circles; info.muted = facts.audioTrack < 0 && !circles;
                    final MediaCodecVideoConvertor.ConvertVideoParams params = MediaCodecVideoConvertor.ConvertVideoParams.of(
                            source.getAbsolutePath(), file, 0, facts.rotation, false, facts.width, facts.height,
                            facts.width, facts.height, 30, 1_200_000, 0, start, end, -1, true,
                            (end - start + 999) / 1000, new MediaController.VideoConvertorListener() {
                                @Override public boolean checkConversionCanceled() {
                                    return work.cancelled.get() || SystemClock.elapsedRealtime() > work.deadline;
                                }
                                @Override public void didWriteData(long size, float progress) { }
                            }, info);
                    final AudioInput audio = facts.audioTrack >= 0
                            ? new PurpleLongRoundAudioInput(source.getAbsolutePath(), facts.audioTrack)
                            : circles ? new BlankAudioInput(end - start) : null;
                    params.purpleAudioInput = audio; params.purpleAudioDurationUs = end - start;
                    stage = "conversion";
                    try {
                        final boolean error = new MediaCodecVideoConvertor().convertVideo(params);
                        PurpleLongRoundDiagnostics.conversion(i, error);
                        if (error) throw new IOException("conversion");
                    } finally {
                        final String previousStage = stage; stage = "audio_release";
                        if (audio != null) audio.release();
                        stage = previousStage;
                    }
                    stage = "work_check";
                    work.check();
                    stage = "part_inspect";
                    final Facts actual = inspect(file, circles, i);
                    stage = "output_audio";
                    if ((actual.audioTrack >= 0) != (facts.audioTrack >= 0 || circles)) {
                        PurpleLongRoundDiagnostics.reject(stage, i, "output_audio");
                        throw new IOException("output audio");
                    }
                    stage = "part_duration";
                    PurpleLongRoundDiagnostics.duration(stage, i, actual.durationMs,
                            circles ? (plan.endUs(i) - plan.startUs(i) + 999) / 1000 : selection.durationMs());
                    if (circles) plan.validatePart(i, actual.durationMs);
                    else if (Math.abs(actual.durationMs - selection.durationMs()) > 150) throw new IOException("selected duration");
                    outputs.add(new Output(file, actual, circles));
                }
            }
            stage = "final_digest";
            if (!Arrays.equals(before, digest(source, work))) {
                PurpleLongRoundDiagnostics.reject(stage, partIndex, "source_changed");
                throw new IOException("source changed");
            }
            stage = "final_check";
            work.check();
            return outputs;
        } catch (Exception failure) {
            PurpleLongRoundDiagnostics.failure(stage, partIndex, failure);
            throw failure;
        } finally {
            PurpleMediaTrace.leave(previousTrace);
        }
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
