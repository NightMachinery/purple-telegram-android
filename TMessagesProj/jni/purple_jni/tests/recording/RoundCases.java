package org.telegram.messenger.purple;

import android.media.MediaCodec;
import android.media.MediaFormat;

import org.telegram.messenger.video.MP4Builder;
import org.telegram.messenger.video.Mp4Movie;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * F10 round video cases (docs/media-recording-design.md, "Tests"): the
 * journal Purple writes from upstream's Mp4Movie hooks, read back by
 * purple-core, and recovery through purple-core's RecoverRound and the real
 * MP4Builder, Mp4Movie and Track classes from the compile receipt, compared
 * with the index finishMovie writes for the same samples.
 */
public final class RoundCases {

    static int checks;

    public static void main(String[] args) throws Exception {
        PurpleCore.ensureLoaded();
        switch (args[0]) {
        case "journal":
            journal(new File(args[1]));
            System.out.println("ROUND_JOURNAL_CHECKS=" + checks);
            break;
        case "recovery":
            System.load(args[1]);
            recovery(new File(args[2]), new File(args[3]));
            System.out.println("ROUND_RECOVERY_CHECKS=" + checks);
            break;
        default:
            throw new IllegalArgumentException(args[0]);
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    static void settings(String toml) throws Exception {
        check(PurpleCore.load(toml.getBytes(StandardCharsets.UTF_8), null, "fixture", "android", "mobile").ok, "settings load");
    }

    // ---- The stream --------------------------------------------------------------

    /** One sample as a recorder hands it to MP4Builder. */
    static final class In {
        final boolean video;
        final byte[] buffer;
        final long ptsUs;
        final boolean sync;

        In(boolean video, byte[] buffer, long ptsUs, boolean sync) {
            this.video = video;
            this.buffer = buffer;
            this.ptsUs = ptsUs;
            this.sync = sync;
        }
    }

    static final byte[] SPS = {0, 0, 0, 1, 0x67, 0x42, (byte) 0xc0, 0x1f, (byte) 0xda, 0x05, (byte) 0x87, (byte) 0xe8};
    static final byte[] PPS = {0, 0, 0, 1, 0x68, (byte) 0xce, 0x3c, (byte) 0x80};

    static MediaFormat videoFormat() {
        final MediaFormat f = MediaFormat.createVideoFormat("video/avc", 360, 360);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(SPS.clone()));
        f.setByteBuffer("csd-1", ByteBuffer.wrap(PPS.clone()));
        return f;
    }

    static MediaFormat audioFormat(byte[] asc, int sampleRate, int channels) {
        final MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(asc.clone()));
        return f;
    }

    /**
     * A round's samples in arrival order: video frames with an Annex-B start
     * code where MP4Builder writes the length (an IDR every 30 frames, the
     * others type 1, both with first_mb_in_slice 0), at a camera rate that
     * drops from 30 to 15 fps halfway, interleaved with real AAC frames.
     */
    static List<In> stream(List<byte[]> aac, int sampleRate, long seed) {
        final Random random = new Random(seed);
        final List<In> out = new ArrayList<>();
        long videoUs = 0;
        int frame = 0;
        for (int a = 0; a < aac.size(); a++) {
            final long audioUs = a * 1024L * 1_000_000L / sampleRate;
            while (videoUs <= audioUs) {
                final boolean idr = frame % 30 == 0;
                final int payload = idr ? 3000 + random.nextInt(2000) : 300 + random.nextInt(900);
                final byte[] b = new byte[4 + 2 + payload];
                b[3] = 1;
                b[4] = idr ? (byte) 0x65 : (byte) 0x41;
                b[5] = (byte) (0x80 | random.nextInt(0x80));
                for (int i = 6; i < b.length; i++) b[i] = (byte) random.nextInt(256);
                out.add(new In(true, b, videoUs, idr));
                frame++;
                videoUs += videoUs < 1_000_000L ? 33_333 : 66_667;
            }
            out.add(new In(false, aac.get(a), audioUs, true));
        }
        return out;
    }

    /** Raw AAC frames from an ADTS stream, and its AudioSpecificConfig, sample rate and channels. */
    static final class Aac {
        final List<byte[]> frames = new ArrayList<>();
        byte[] asc;
        int sampleRate;
        int channels;
    }

    static Aac readAdts(File file) throws Exception {
        final byte[] bytes = Files.readAllBytes(file.toPath());
        final Aac aac = new Aac();
        final int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000};
        int at = 0;
        while (at + 7 <= bytes.length) {
            final int profile = (bytes[at + 2] >> 6) & 3;
            final int frequency = (bytes[at + 2] >> 2) & 0xf;
            final int channels = ((bytes[at + 2] & 1) << 2) | ((bytes[at + 3] >> 6) & 3);
            final int length = ((bytes[at + 3] & 3) << 11) | ((bytes[at + 4] & 0xff) << 3) | ((bytes[at + 5] & 0xff) >> 5);
            final int header = (bytes[at + 1] & 1) == 0 ? 9 : 7;
            if (aac.asc == null) {
                aac.asc = new byte[]{(byte) (((profile + 1) << 3) | (frequency >> 1)), (byte) (((frequency & 1) << 7) | (channels << 3))};
                aac.sampleRate = rates[frequency];
                aac.channels = channels;
            }
            aac.frames.add(Arrays.copyOfRange(bytes, at + header, at + length));
            at += length;
        }
        check(aac.frames.size() > 100, "the AAC stream has frames");
        return aac;
    }

    /**
     * Records {@code samples} through the real MP4Builder as a round recorder
     * does, with the journal registered, and flushes a journal batch every
     * {@code batchEvery} samples (the interval, in samples), up to sample
     * {@code stopJournalAt}. Writes what the media file and the journal held
     * just before finishMovie, a crash's leftovers, to {@code <media>.crash}
     * and {@code <media>.crash.pjr}; the media file itself is then finished,
     * as the reference.
     */
    static File record(File media, List<In> samples, Aac aac, boolean split, int batchEvery, int stopJournalAt) throws Exception {
        final Mp4Movie movie = new Mp4Movie();
        movie.setCacheFile(media);
        PurpleRoundJournal.register(movie, split, false);
        movie.setRotation(0);
        movie.setSize(360, 360);
        final MP4Builder builder = new MP4Builder().createMovie(movie, split, false);
        builder.setAllowSyncFiles(false);
        final int video = builder.addTrack(videoFormat(), false);
        final int audio = builder.addTrack(audioFormat(aac.asc, aac.sampleRate, aac.channels), true);
        final String journal = media.getPath() + ".pjr";
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int i = 0; i < samples.size(); i++) {
            final In s = samples.get(i);
            info.set(0, s.buffer.length, s.ptsUs, s.sync ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            builder.writeSampleData(s.video ? video : audio, ByteBuffer.wrap(s.buffer.clone()), info, s.video);
            if (batchEvery > 0 && (i + 1) % batchEvery == 0 && i < stopJournalAt) PurpleRoundJournal.flush(journal);
        }
        if (stopJournalAt >= samples.size()) PurpleRoundJournal.flush(journal);
        final File crash = new File(media.getPath() + ".crash");
        Files.write(crash.toPath(), Files.readAllBytes(media.toPath()));
        Files.write(new File(crash.getPath() + ".pjr").toPath(), Files.readAllBytes(new File(journal).toPath()));
        PurpleRoundJournal.close(journal);
        builder.finishMovie();
        return crash;
    }

    // ---- The journal -----------------------------------------------------------------

    static void journal(File work) throws Exception {
        work.mkdirs();
        final List<File> registered = new ArrayList<>();
        final int[] stopped = {0};
        PurpleRoundJournal.listener = new PurpleRoundJournal.Listener() {
            @Override
            public void registered(File media, File journal) {
                registered.add(journal);
            }

            @Override
            public void stopped() {
                stopped[0]++;
            }
        };
        final byte[] asc = {0x11, (byte) 0x88};

        // An unregistered movie (a video conversion) writes nothing.
        final Mp4Movie other = new Mp4Movie();
        other.setCacheFile(new File(work, "conversion.mp4"));
        other.addTrack(videoFormat(), false);
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, 100, 0, 1);
        other.addSample(0, 40, info);
        check(!new File(work, "conversion.mp4.pjr").exists() && registered.isEmpty(), "an unregistered movie has no journal");

        // round_journal 1s: the config header, then one batch per interval.
        settings("[recording]\nround_journal = \"1s\"\n");
        final Mp4Movie movie = new Mp4Movie();
        final File media = new File(work, "round.mp4");
        movie.setCacheFile(media);
        PurpleRoundJournal.register(movie, true, false);
        movie.setSize(360, 360);
        check(registered.size() == 1 && registered.get(0).getName().equals("round.mp4.pjr"), "registration names the sidecar <file>.pjr");
        final String path = registered.get(0).getPath();
        check(!registered.get(0).exists(), "nothing is written before the first track");
        check(movie.addTrack(videoFormat(), false) == 0, "the hook leaves addTrack's index alone");
        movie.addTrack(audioFormat(asc, 48000, 1), true);
        byte[] bytes = Files.readAllBytes(registered.get(0).toPath());
        PurpleRecordingCore.JournalRead read = PurpleRecordingCore.readJournal(bytes);
        check(read.status == PurpleRecordingCore.JournalRead.OK && read.tracks == 2 && read.batches == 0 && read.splitMdat,
                "the config header holds both tracks and the split flag");
        check(bytes.length < 400, "the config header is a few hundred bytes");
        long offset = 40;
        for (int batch = 0; batch < 3; batch++) {
            for (int i = 0; i < 77; i++) {
                info.set(0, 200 + i, (batch * 77 + i) * 13_000L, i % 30 == 0 ? 1 : 0);
                movie.addSample(i % 3 == 0 ? 1 : 0, offset, info);
                offset += info.size;
            }
            PurpleRoundJournal.flush(path);
        }
        bytes = Files.readAllBytes(registered.get(0).toPath());
        read = PurpleRecordingCore.readJournal(bytes);
        check(read.status == PurpleRecordingCore.JournalRead.OK && read.batches == 3 && read.samples == 231 && !read.damaged,
                "three batches of 77 samples read back");
        check(read.goodBytes == bytes.length, "every byte is a good record");
        final int batchBytes = 8 + 4 + 24 * 77 + 4;
        check(bytes.length - batchBytes * 3 < 400, "a batch is 24 bytes a sample plus its framing");
        check(movie.getTracks().get(0).getSamples().size() + movie.getTracks().get(1).getSamples().size() == 231,
                "upstream's sample table is filled as before");

        // A CRC failure stops at the bad batch; a torn append keeps what came before.
        final byte[] flipped = bytes.clone();
        flipped[bytes.length - batchBytes - 30] ^= 0x20;
        read = PurpleRecordingCore.readJournal(flipped);
        check(read.batches == 1 && read.samples == 77 && read.damaged, "a flipped byte in the second batch stops there");
        read = PurpleRecordingCore.readJournal(Arrays.copyOf(bytes, bytes.length - 10));
        check(read.batches == 2 && read.damaged, "a torn last batch is dropped");
        read = PurpleRecordingCore.readJournal(Arrays.copyOf(bytes, 30));
        check(read.status != PurpleRecordingCore.JournalRead.OK || read.tracks < 2, "a journal cut inside the config header has no complete config");
        final byte[] future = bytes.clone();
        future[4] = 2;
        check(PurpleRecordingCore.readJournal(future).status == PurpleRecordingCore.JournalRead.UNKNOWN_VERSION, "another version is reported");

        // Close: later samples are ignored, and flushing what is pending first.
        info.set(0, 99, 9_000_000L, 0);
        movie.addSample(0, offset, info);
        PurpleRoundJournal.close(path);
        read = PurpleRecordingCore.readJournal(Files.readAllBytes(registered.get(0).toPath()));
        check(read.batches == 4 && read.samples == 232, "close flushes the pending sample");
        movie.addSample(0, offset + 99, info);
        read = PurpleRecordingCore.readJournal(Files.readAllBytes(registered.get(0).toPath()));
        check(read.samples == 232, "after close the movie is forgotten");

        // Off: the config header only, and no batch whatever is flushed.
        settings("[recording]\nround_journal = \"off\"\n");
        final Mp4Movie off = new Mp4Movie();
        off.setCacheFile(new File(work, "off.mp4"));
        PurpleRoundJournal.register(off, false, false);
        off.setSize(360, 360);
        off.addTrack(videoFormat(), false);
        off.addTrack(audioFormat(asc, 48000, 1), true);
        for (int i = 0; i < 100; i++) {
            info.set(0, 100, i * 20_000L, 0);
            off.addSample(i % 2, 40 + i * 100L, info);
        }
        final String offPath = new File(work, "off.mp4.pjr").getPath();
        PurpleRoundJournal.flush(offPath);
        PurpleRoundJournal.close(offPath);
        read = PurpleRecordingCore.readJournal(Files.readAllBytes(new File(offPath).toPath()));
        check(read.status == PurpleRecordingCore.JournalRead.OK && read.tracks == 2 && read.batches == 0 && !read.splitMdat,
                "Off writes the config header and no index");

        // 5s: no batch before the interval unless flushed.
        settings("[recording]\nround_journal = \"5s\"\n");
        final Mp4Movie slow = new Mp4Movie();
        slow.setCacheFile(new File(work, "slow.mp4"));
        PurpleRoundJournal.register(slow, true, false);
        slow.addTrack(videoFormat(), false);
        for (int i = 0; i < 50; i++) {
            info.set(0, 100, i * 33_000L, 0);
            slow.addSample(0, 40 + i * 100L, info);
        }
        final String slowPath = new File(work, "slow.mp4.pjr").getPath();
        read = PurpleRecordingCore.readJournal(Files.readAllBytes(new File(slowPath).toPath()));
        check(read.batches == 0, "within the interval samples stay buffered");
        PurpleRoundJournal.close(slowPath);
        read = PurpleRecordingCore.readJournal(Files.readAllBytes(new File(slowPath).toPath()));
        check(read.batches == 1 && read.samples == 50, "and are written at the end of the interval or at close");

        // A failed write stops the journal quietly.
        final Mp4Movie full = new Mp4Movie();
        full.setCacheFile(new File(new File(work, "no-such-directory"), "full.mp4"));
        PurpleRoundJournal.register(full, true, false);
        full.addTrack(videoFormat(), false);
        check(stopped[0] == 1, "a journal that cannot be written stops and says so");
        full.addSample(0, 40, info);
        check(PurpleRoundJournal.rotation(com.googlecode.mp4parser.util.Matrix.ROTATE_90) == 90
                && PurpleRoundJournal.rotation(com.googlecode.mp4parser.util.Matrix.ROTATE_0) == 0, "the rotation is read from the movie's matrix");
    }

    // ---- Recovery ------------------------------------------------------------------

    static void recovery(File adts, File work) throws Exception {
        work.mkdirs();
        PurpleRoundJournal.listener = null;
        // 5 s, so no batch is cut by the clock: the cases flush where they mean to.
        settings("[recording]\nround_journal = \"5s\"\n");
        final Aac aac = readAdts(adts);
        final List<In> samples = stream(aac.frames, aac.sampleRate, 7);
        int videoCount = 0;
        for (In s : samples) if (s.video) videoCount++;
        check(videoCount > 40, "the stream has video frames at both camera rates");

        for (boolean split : new boolean[]{true, false}) {
            final String tag = split ? "split" : "unsplit";
            // A fresh journal: everything journaled before the crash.
            final File media = new File(work, tag + "-exact.mp4");
            final File snapshot = record(media, samples, aac, split, 77, Integer.MAX_VALUE);
            final byte[] crashed = Files.readAllBytes(snapshot.toPath());
            final File snapshotJournal = new File(snapshot.getPath() + ".pjr");
            check(PurpleRecordingFiles.mp4Finished(snapshot) == 0, tag + ": the crash leaves no moov");
            PurpleRoundRecovery.Recovery r = PurpleRoundRecovery.analyse(snapshot, snapshotJournal);
            check(r != null && r.status == PurpleRoundRecovery.EXACT && r.sampleCount() == samples.size(),
                    tag + ": a fresh journal recovers every sample exactly");
            final File rebuilt = new File(work, tag + "-exact.rebuilt.mp4");
            final PurpleRecordingStore.RoundResult result = PurpleRoundRecovery.recover(snapshot,
                    snapshotJournal, rebuilt);
            check(result != null && !result.partial && result.durationMs > 3000, tag + ": the rebuild succeeds");
            check(PurpleRecordingFiles.mp4Finished(rebuilt) == 1, tag + ": the rebuilt file has moov");
            final Mp4Tables reference = Mp4Tables.read(media);
            final Mp4Tables recovered = Mp4Tables.read(rebuilt);
            check(reference.tracks == 2 && recovered.tracks == 2, tag + ": both files have two tracks");
            for (String box : new String[]{"stsz", "stts", "stss", "stsc", "stco"}) {
                check(Arrays.equals(reference.box(box), recovered.box(box)), tag + ": " + box + " matches what finishMovie wrote");
            }
            check(Arrays.equals(Arrays.copyOf(Files.readAllBytes(media.toPath()), reference.moovAt),
                    Arrays.copyOf(Files.readAllBytes(rebuilt.toPath()), recovered.moovAt)),
                    tag + ": the media data is byte for byte the same");

            // Truncated inside each of the last three samples: the journal's
            // samples past the end are dropped, never a torn one kept.
            final int[] ends = sampleEnds(r);
            for (int k = 1; k <= 3; k++) {
                final int last = r.sampleCount() - k;
                final long start = r.samples[last * 5 + 3];
                for (long cut = start + 1; cut < ends[last]; cut += Math.max(1, (ends[last] - start) / 7)) {
                    final File cutFile = new File(work, tag + "-cut.mp4");
                    Files.write(cutFile.toPath(), Arrays.copyOf(crashed, (int) cut));
                    final PurpleRoundRecovery.Recovery c = PurpleRoundRecovery.analyse(cutFile, snapshotJournal);
                    check(c != null && c.status != PurpleRoundRecovery.UNRECOVERABLE && c.sampleCount() == last,
                            tag + ": a cut inside sample " + last + " keeps exactly the samples before it");
                    for (int s = 0; s < c.sampleCount(); s++) {
                        if (c.samples[s * 5 + 3] + c.samples[s * 5 + 2] > cut) check(false, tag + ": a kept sample lies past the cut");
                    }
                }
            }

            // Journal Off: the config header only, so everything is repaired.
            settings("[recording]\nround_journal = \"off\"\n");
            final File offMedia = new File(work, tag + "-off.mp4");
            final File offSnapshot = record(offMedia, samples, aac, split, 0, 0);
            settings("[recording]\nround_journal = \"5s\"\n");
            r = PurpleRoundRecovery.analyse(offSnapshot, new File(offSnapshot.getPath() + ".pjr"));
            check(r != null && r.status == PurpleRoundRecovery.REPAIRED && r.exactSamples == 0, tag + ": Off is repaired from the start");
            check(r.sampleCount() == samples.size(), tag + ": repair finds every sample (" + r.sampleCount() + " of " + samples.size() + ")");
            final File offRebuilt = new File(work, tag + "-off.rebuilt.mp4");
            check(PurpleRoundRecovery.recover(offSnapshot, new File(offSnapshot.getPath() + ".pjr"), offRebuilt) != null, tag + ": and rebuilt");
            final Mp4Tables offReference = Mp4Tables.read(offMedia);
            final Mp4Tables offRecovered = Mp4Tables.read(offRebuilt);
            for (String box : new String[]{"stsz", "stss", "stsc", "stco"}) {
                check(Arrays.equals(offReference.box(box), offRecovered.box(box)), tag + ": repaired " + box + " matches");
            }
            checkTiming(samples, r, aac.sampleRate, tag + ": repaired");

            // A stale journal: batches stop two thirds in; the tail is repaired.
            final File staleMedia = new File(work, tag + "-stale.mp4");
            final int journaled = samples.size() * 2 / 3 / 77 * 77;
            final File staleSnapshot = record(staleMedia, samples, aac, split, 77, journaled);
            r = PurpleRoundRecovery.analyse(staleSnapshot, new File(staleSnapshot.getPath() + ".pjr"));
            check(r != null && r.status == PurpleRoundRecovery.REPAIRED && r.exactSamples == journaled
                    && r.sampleCount() == samples.size(), tag + ": a stale journal gives its exact part and the repaired tail");
            checkTiming(samples, r, aac.sampleRate, tag + ": journal plus tail");
            for (int s = 0; s < journaled; s++) {
                check(r.samples[s * 5 + 4] == samples.get(s).ptsUs, tag + ": the exact part keeps its times");
            }
        }

        // No journal: there is no config header, so nothing is rebuilt and the bin keeps the raw file.
        final File bare = new File(work, "unsplit-exact.mp4.crash");
        check(PurpleRoundRecovery.recover(bare, null, new File(work, "none.mp4")) == null, "without a journal the round is unrecoverable");
        final PurpleRoundRecovery.Recovery none = PurpleRoundRecovery.analyse(bare, null);
        check(none != null && none.status == PurpleRoundRecovery.UNRECOVERABLE && none.reason == 1, "the reason is the missing config");
        check(!new File(work, "none.mp4").exists(), "and no partial file is left");

        // Through the store, as the registry binds it: a crash at start becomes a bin item.
        final File dir = new File(work, "store");
        final PurpleRecordingManifest manifest = new PurpleRecordingManifest(dir);
        PurpleRecordingStore store = new PurpleRecordingStore(manifest, CORE, 1_800_000_000L);
        final File live = new File(work, "live.mp4");
        final File liveCrash = record(live, samples, aac, true, 77, Integer.MAX_VALUE);
        Files.copy(liveCrash.toPath(), live.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(new File(liveCrash.getPath() + ".pjr").toPath(), new File(live.getPath() + ".pjr").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        final PurpleRecordingEntry entry = store.begin(0, 900, 0, 0, PurpleRecordingEntry.ROUND, 1_800_000_000_000L);
        store.attach(entry, live, new File(live.getPath() + ".pjr"));
        store = new PurpleRecordingStore(manifest, CORE, 1_800_000_010L);
        final PurpleRecordingStore.Outcome outcome = store.recoverAtStart(1_800_000_010L);
        final PurpleRecordingEntry item = store.find(entry.id);
        check(outcome.recovered == 1 && item != null && PurpleRecordingEntry.RECOVERED.equals(item.state) && !item.partial,
                "a crashed round is recovered into the bin");
        check(PurpleRecordingFiles.mp4Finished(new File(item.media)) == 1 && item.journal == null
                && !live.exists() && !new File(live.getPath() + ".pjr").exists(), "as a finished file, its journal used up");
        check(item.durationMs > 3000, "with its duration");
    }

    static final PurpleRecordingStore.Core CORE = new PurpleRecordingStore.Core() {
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

    static int[] sampleEnds(PurpleRoundRecovery.Recovery r) {
        final int[] ends = new int[r.sampleCount()];
        for (int s = 0; s < ends.length; s++) ends[s] = (int) (r.samples[s * 5 + 3] + r.samples[s * 5 + 2]);
        return ends;
    }

    /**
     * Repaired times: audio exactly n x 1024 / rate, video anchored to the
     * audio clock within one audio frame of the recorded time, increasing, with
     * IDR frames as the only sync samples.
     */
    static void checkTiming(List<In> samples, PurpleRoundRecovery.Recovery r, int sampleRate, String tag) {
        final long frameUs = 1024L * 1_000_000L / sampleRate + 1;
        long lastVideo = -1;
        boolean ok = true;
        boolean syncOk = true;
        for (int s = 0; s < r.sampleCount(); s++) {
            final In in = samples.get(s);
            final long pts = r.samples[s * 5 + 4];
            if (Math.abs(pts - in.ptsUs) > frameUs) ok = false;
            if (in.video) {
                if (pts <= lastVideo) ok = false;
                lastVideo = pts;
                if ((r.samples[s * 5 + 1] != 0) != in.sync) syncOk = false;
            }
        }
        check(ok, tag + ": every time is within one audio frame of the recorded one");
        check(syncOk, tag + ": sync samples are the IDR frames");
    }

    /** The sample tables of an MP4's moov, by box type, for byte comparison. */
    static final class Mp4Tables {
        final List<byte[]> boxes = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        int tracks;
        int moovAt;

        static Mp4Tables read(File file) throws Exception {
            final byte[] b = Files.readAllBytes(file.toPath());
            final Mp4Tables t = new Mp4Tables();
            t.walk(b, 0, b.length, 0);
            return t;
        }

        void walk(byte[] b, int from, int to, int depth) {
            int at = from;
            while (at + 8 <= to) {
                long size = ((b[at] & 0xffL) << 24) | ((b[at + 1] & 0xffL) << 16) | ((b[at + 2] & 0xffL) << 8) | (b[at + 3] & 0xffL);
                final String type = new String(b, at + 4, 4, StandardCharsets.ISO_8859_1);
                if (size == 1) {
                    size = 0;
                    for (int i = 8; i < 16; i++) size = (size << 8) | (b[at + i] & 0xffL);
                }
                if (size < 8 || at + size > to) return;
                if (depth == 0 && type.equals("moov")) moovAt = at;
                if (type.equals("trak")) tracks++;
                if (Arrays.asList("moov", "trak", "mdia", "minf", "stbl").contains(type)) {
                    walk(b, at + 8, (int) (at + size), depth + 1);
                } else if (Arrays.asList("stsz", "stts", "stss", "stsc", "stco", "co64").contains(type)) {
                    types.add(type);
                    boxes.add(Arrays.copyOfRange(b, at, (int) (at + size)));
                }
                at += size;
            }
        }

        /** Every box of a type, concatenated in track order. */
        byte[] box(String type) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < types.size(); i++) if (types.get(i).equals(type)) out.write(boxes.get(i), 0, boxes.get(i).length);
            return out.toByteArray();
        }
    }
}
