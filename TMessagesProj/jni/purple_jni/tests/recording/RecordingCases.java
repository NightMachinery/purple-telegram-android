package org.telegram.messenger.purple;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The media recording cases (docs/media-recording-design.md, "Tests"), run on
 * the real Purple classes from the AGP compile receipt. One program per
 * argument; each prints its completion record.
 */
public final class RecordingCases {

    static int checks;

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
        case "settings":
            settings();
            System.out.println("RECORDING_SETTINGS_CHECKS=" + checks);
            break;
        case "bin":
            bin(new File(args[1]), new File(args[2]), new File(args[3]));
            System.out.println("RECORDING_BIN_CHECKS=" + checks);
            break;
        default:
            throw new IllegalArgumentException(args[0]);
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    /**
     * The real recordingSettings bridge over the real core: every key's
     * default, each boolean on and off, the round_journal and bin_keep choices
     * and the values the core refuses, read from a loaded settings.toml.
     */
    static void settings() throws Exception {
        PurpleRecordingSettings s = load("");
        check(s.fromCore, "a file without the table is read from the core");
        check(s.keepAcrossChats && s.background && s.roundFloat, "F6-F8 default on");
        check(s.roundJournalSeconds == 1, "round_journal defaults to 1 s");
        check(s.binKeepSeconds == 48 * 3600, "bin_keep defaults to 48 h");
        check(s.keepDiscarded, "keep_discarded_p defaults on");
        check(s.sendElsewhere && s.callPause && s.headset && s.previewTrim, "the extras default on");
        check(!s.headsetHoldStart, "headset_hold_start_p defaults off");
        check(s.longNudgeSeconds == 30 * 60, "long_nudge defaults to 30 min");

        final String[] booleans = {"keep_across_chats_p", "background_p", "round_float_p", "keep_discarded_p",
            "send_elsewhere_p", "call_pause_p", "headset_p", "headset_hold_start_p", "preview_trim_p"};
        for (String key : booleans) {
            for (boolean on : new boolean[]{false, true}) {
                s = load("[recording]\n" + key + " = " + on + "\n");
                check(value(s, key) == on, key + " = " + on);
            }
        }

        final String[] journals = {"off", "1s", "2s", "5s", "3s", "abc", "10s"};
        final int[] journalSeconds = {0, 1, 2, 5, 1, 1, 1};
        for (int i = 0; i < journals.length; i++) {
            s = load("[recording]\nround_journal = \"" + journals[i] + "\"\n");
            check(s.roundJournalSeconds == journalSeconds[i], "round_journal " + journals[i]);
        }
        for (int choice : PurpleRecordingSettings.JOURNAL_CHOICES) {
            s = load("[recording]\nround_journal = \"" + PurpleRecordingSettings.journalValue(choice) + "\"\n");
            check(s.roundJournalSeconds == choice, "the row's journal choice " + choice + " reads back");
        }

        final String[] keeps = {"12h", "24h", "48h", "7d", "168h", "off", "30m", "abc"};
        final int[] keepSeconds = {12 * 3600, 24 * 3600, 48 * 3600, 7 * 24 * 3600, 7 * 24 * 3600, 0, 48 * 3600, 48 * 3600};
        for (int i = 0; i < keeps.length; i++) {
            s = load("[recording]\nbin_keep = \"" + keeps[i] + "\"\n");
            check(s.binKeepSeconds == keepSeconds[i], "bin_keep " + keeps[i]);
        }
        for (int choice : PurpleRecordingSettings.BIN_KEEP_CHOICES) {
            s = load("[recording]\nbin_keep = \"" + PurpleRecordingSettings.binKeepValue(choice) + "\"\n");
            check(s.binKeepSeconds == choice, "the row's bin_keep choice " + choice + " reads back");
        }
        check(PurpleRecordingSettings.binKeepValue(7 * 24 * 3600).equals("7d"), "seven days is written 7d");
        check(PurpleRecordingSettings.binKeepValue(0).equals("off"), "never is written off");
        check(PurpleRecordingSettings.journalValue(0).equals("off"), "the journal's Off is written off");

        s = load("recording = 3\n");
        check(s.roundJournalSeconds == 1 && s.keepDiscarded, "a recording key that is not a table keeps every default");

        s = load("[recording]\nround_journal = \"5s\"\nbin_keep = \"7d\"\nkeep_discarded_p = false\n"
                + "[playback]\nseek_step = \"30s\"\n");
        check(s.roundJournalSeconds == 5 && s.binKeepSeconds == 7 * 24 * 3600 && !s.keepDiscarded,
                "the F10 keys read together");
        check(PurpleCore.seekStepSeconds() == 30, "the next table reads alongside them");

        // The fallback when the core cannot be asked is the core's defaults.
        PurpleRecordingSettings fallback = new PurpleRecordingSettings(null);
        PurpleRecordingSettings defaults = load("");
        check(!fallback.fromCore, "no packed values is the fallback");
        check(fallback.roundJournalSeconds == defaults.roundJournalSeconds
                && fallback.binKeepSeconds == defaults.binKeepSeconds
                && fallback.keepDiscarded == defaults.keepDiscarded
                && fallback.longNudgeSeconds == defaults.longNudgeSeconds
                && fallback.headsetHoldStart == defaults.headsetHoldStart,
                "the fallback agrees with the core's defaults");
        check(!new PurpleRecordingSettings(new int[3]).fromCore, "a short packed array is the fallback");
    }

    static PurpleRecordingSettings load(String toml) throws Exception {
        final PurpleCore.Loaded loaded = PurpleCore.load(toml.getBytes(StandardCharsets.UTF_8), null, "fixture", "android", "mobile");
        check(loaded.ok, "the settings file loads: " + toml.replace('\n', ' '));
        final PurpleRecordingSettings s = PurpleRecordingSettings.read();
        check(s.fromCore, "the bridge answered");
        return s;
    }

    static boolean value(PurpleRecordingSettings s, String key) {
        switch (key) {
        case "keep_across_chats_p": return s.keepAcrossChats;
        case "background_p": return s.background;
        case "round_float_p": return s.roundFloat;
        case "keep_discarded_p": return s.keepDiscarded;
        case "send_elsewhere_p": return s.sendElsewhere;
        case "call_pause_p": return s.callPause;
        case "headset_p": return s.headset;
        case "headset_hold_start_p": return s.headsetHoldStart;
        case "preview_trim_p": return s.previewTrim;
        default: throw new IllegalArgumentException(key);
        }
    }

    // ---- The registry's store, the manifest and the bin (F10 voice) -------------

    /** The real core, as the registry binds it; rounds come with F10 round. */
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
            return null;
        }
    };

    static final long NOW = 1_800_000_000L;

    /**
     * {@code voice}: a finished Ogg/Opus voice recording ffmpeg encoded for
     * this run; {@code mp4}: a finished MP4; {@code work}: an empty directory.
     */
    static void bin(File voice, File mp4, File work) throws Exception {
        PurpleCore.ensureLoaded();
        work.mkdirs();
        final byte[] whole = Files.readAllBytes(voice.toPath());
        final PurpleRecordingCore.OggScan full = PurpleRecordingCore.scanOgg(voice);
        check(full.clean() && full.opus && full.durationMs > 2000, "the test recording is a finished Opus stream");

        // The file name patterns of upstream's recorders.
        check(PurpleRecordingFiles.voiceName("audio_recording_voice_1762200000000_3.ogg"), "V2 voice names match");
        check(PurpleRecordingFiles.voiceName("1762200000000_-2147483000.ogg"), "legacy voice names match");
        check(!PurpleRecordingFiles.voiceName("2_5123456789.ogg"), "a downloaded voice message's name does not");
        check(!PurpleRecordingFiles.voiceName("audio_recording_voice_1.ogg"), "a name without the counter does not");
        check(PurpleRecordingFiles.orphanVoiceName("audio_recording_trim_1_2.ogg"), "any V2 temporary .ogg is an orphan candidate");
        check(PurpleRecordingFiles.roundName("round_video_8812345.mp4"), "V2 round names match");
        check(!PurpleRecordingFiles.roundName("round_video_preview_8812345.mp4"), "V2 previews do not");
        check(PurpleRecordingFiles.roundName("1762200000000_-2147483000.mp4"), "legacy round names match");
        check(PurpleRecordingFiles.roundName("camera_tmp.mp4"), "the SD card temporary round matches");
        check(!PurpleRecordingFiles.roundName("2_5123456789012.mp4"), "a downloaded video's name does not");

        // MP4: finished, unfinished and not an MP4.
        check(PurpleRecordingFiles.mp4Finished(mp4) == 1, "a finished MP4 has moov");
        final File unfinished = new File(work, "unfinished.mp4");
        writeUnfinishedMp4(unfinished, 0);
        check(PurpleRecordingFiles.mp4Finished(unfinished) == 0, "ftyp and mdat without moov is unfinished");
        final File split = new File(work, "split.mp4");
        writeUnfinishedMp4(split, 3);
        check(PurpleRecordingFiles.mp4Finished(split) == 0, "split mdat without moov is unfinished");
        check(PurpleRecordingFiles.mp4Finished(voice) == -1, "an Ogg file is not an MP4");
        final long mp4Ms = PurpleRecordingFiles.mp4DurationMs(mp4);
        check(mp4Ms >= 900 && mp4Ms <= 1100, "a finished MP4's duration comes from mvhd: " + mp4Ms);
        check(PurpleRecordingFiles.mp4DurationMs(unfinished) == 0 && PurpleRecordingFiles.mp4DurationMs(voice) == 0,
                "no mvhd, no duration");

        // The manifest: atomic writes and what a reader does with damage.
        final File dir = new File(work, "purple-recordings");
        final PurpleRecordingManifest manifest = new PurpleRecordingManifest(dir);
        check(manifest.load(NOW).isEmpty(), "no manifest reads as empty");
        PurpleRecordingStore store = new PurpleRecordingStore(manifest, CORE, NOW);
        final PurpleRecordingEntry started = store.begin(1, 777, 5, 42, PurpleRecordingEntry.VOICE, NOW * 1000);
        check(new File(dir, "recordings.json").isFile(), "the entry is on disk as soon as the recording starts");
        check(!new File(dir, "recordings.json.tmp").exists(), "no temporary file is left behind");
        List<PurpleRecordingEntry> read = manifest.load(NOW);
        check(read.size() == 1 && read.get(0).id.equals(started.id) && read.get(0).dialogId == 777
                && read.get(0).topicId == 5 && read.get(0).replyId == 42 && read.get(0).account == 1
                && PurpleRecordingEntry.RECORDING.equals(read.get(0).state), "the entry reads back whole");
        final String before = new String(Files.readAllBytes(new File(dir, "recordings.json").toPath()), StandardCharsets.UTF_8);
        check(!before.contains("\n") && before.startsWith("{"), "the manifest is one JSON object");

        // A crash during a recording: the next start recovers it into the bin.
        final File audio = new File(work, "audio");
        audio.mkdirs();
        final File crashed = new File(audio, "audio_recording_voice_1800000000000_1.ogg");
        Files.write(crashed.toPath(), Arrays.copyOf(whole, whole.length * 2 / 3 + 7));
        store.attach(started, crashed, null);
        final File finished = new File(audio, "1800000000000_-5.ogg");
        Files.write(finished.toPath(), whole);
        final PurpleRecordingEntry clean = store.begin(0, 778, 0, 0, PurpleRecordingEntry.VOICE, NOW * 1000 + 1);
        store.attach(clean, finished, null);
        final PurpleRecordingEntry gone = store.begin(0, 779, 0, 0, PurpleRecordingEntry.VOICE, NOW * 1000 + 2);
        store.attach(gone, new File(audio, "1800000000000_-6.ogg"), null);
        final File junk = new File(audio, "1800000000000_-7.ogg");
        Files.write(junk.toPath(), "not a recording at all".getBytes(StandardCharsets.UTF_8));
        final PurpleRecordingEntry broken = store.begin(0, 780, 0, 0, PurpleRecordingEntry.VOICE, NOW * 1000 + 3);
        store.attach(broken, junk, null);

        store = new PurpleRecordingStore(manifest, CORE, NOW + 10);
        final PurpleRecordingStore.Outcome outcome = store.recoverAtStart(NOW + 10);
        check(outcome.recovered == 1 && outcome.finishedCleanly == 1 && outcome.missing == 1 && outcome.unrecoverable == 1,
                "recovery counts one of each");
        PurpleRecordingEntry recovered = store.find(started.id);
        check(recovered != null && PurpleRecordingEntry.RECOVERED.equals(recovered.state), "the crashed recording is recovered");
        check(!crashed.exists(), "the recovered file left Telegram's directory");
        final File inBin = new File(recovered.media);
        check(inBin.getParentFile().equals(manifest.bin), "it is in the bin");
        final PurpleRecordingCore.OggScan after = PurpleRecordingCore.scanOgg(inBin);
        check(after.clean(), "the recovered file is a finished Ogg stream");
        check(recovered.durationMs > 0 && recovered.durationMs < full.durationMs, "its duration is the part that was saved");
        check(recovered.binnedAt == NOW + 10, "it entered the bin at the recovery");
        check(recovered.dialogId == 777 && recovered.replyId == 42, "it keeps its chat for Send to the original chat");
        check(store.find(clean.id) == null && finished.isFile() && finished.length() == whole.length,
                "a recording that had finished is upstream's and is left alone");
        check(store.find(gone.id) == null, "a recording whose file is gone is dropped");
        final PurpleRecordingEntry raw = store.find(broken.id);
        check(raw != null && PurpleRecordingEntry.UNRECOVERABLE.equals(raw.state) && new File(raw.media).isFile()
                && new File(raw.media).length() == 22, "an unreadable recording is kept raw as unrecoverable");
        check(new PurpleRecordingStore(manifest, CORE, NOW).recoverAtStart(NOW + 20).recovered == 0, "recovery runs once per entry");

        // A discard: the bytes come from the descriptor held open while the
        // recorder's file name is already deleted.
        final File discarded = new File(audio, "audio_recording_voice_1800000000000_9.ogg");
        Files.write(discarded.toPath(), whole);
        final PurpleRecordingEntry slip = store.begin(0, 781, 0, 0, PurpleRecordingEntry.VOICE, NOW * 1000 + 4);
        store.attach(slip, discarded, null);
        try (FileChannel held = new RandomAccessFile(discarded, "r").getChannel()) {
            check(discarded.delete(), "upstream deletes the discarded file");
            check(store.discard(slip, held, true, NOW + 30), "with keep_discarded_p the discard is kept");
        }
        check(PurpleRecordingEntry.UNSENT.equals(slip.state) && slip.discarded, "it is a Discarded item");
        check(PurpleRecordingCore.scanOgg(new File(slip.media)).clean(), "its bytes are whole");
        check(slip.durationMs > 2000, "its duration comes from the core");
        // A discarded round that the recorder had already finished (legacy cancel) keeps its duration.
        final PurpleRecordingEntry legacyRound = store.begin(0, 786, 0, 0, PurpleRecordingEntry.ROUND, NOW * 1000 + 9);
        try (FileChannel held = new RandomAccessFile(mp4, "r").getChannel()) {
            check(store.discard(legacyRound, held, true, NOW + 32), "a finished round is kept");
        }
        check(legacyRound.durationMs >= 900 && legacyRound.durationMs <= 1100, "with the duration of the file");
        store.delete(legacyRound);
        final PurpleRecordingEntry slip2 = store.begin(0, 782, 0, 0, PurpleRecordingEntry.VOICE, NOW * 1000 + 5);
        check(!store.discard(slip2, null, false, NOW + 31), "without keep_discarded_p nothing is kept");
        check(store.find(slip2.id) == null, "and the entry is dropped");
        final PurpleRecordingEntry sent = store.begin(0, 783, 0, 0, PurpleRecordingEntry.ROUND, NOW * 1000 + 6);
        final File journal = new File(work, "sent.mp4.pjr");
        Files.write(journal.toPath(), new byte[]{1, 2, 3});
        store.attach(sent, new File(work, "sent.mp4"), journal);
        store.finish(sent);
        check(store.find(sent.id) == null && !journal.exists(), "a sent recording's entry and journal go");

        // The bin lists newest first; expiry at the boundary.
        List<PurpleRecordingEntry> bin = store.bin();
        check(bin.size() == 3, "the bin lists recovered, unrecoverable and discarded items");
        check(bin.get(0).id.equals(slip.id), "newest first");
        final int hour = 3600;
        for (PurpleRecordingEntry e : bin) e.binnedAt = NOW;
        store.sweep(NOW, 0);
        check(store.bin().size() == 3, "bin_keep off never clears");
        check(store.sweep(NOW + hour - 1, hour).expired == 0, "an item just under bin_keep stays");
        final File slipFile = new File(slip.media);
        check(store.sweep(NOW + hour, hour).expired == 3, "an item exactly bin_keep old goes");
        check(!slipFile.exists(), "its file goes with it");
        final PurpleRecordingEntry future = store.begin(0, 784, 0, 0, PurpleRecordingEntry.VOICE, NOW * 1000 + 7);
        future.state = PurpleRecordingEntry.UNSENT;
        future.binnedAt = NOW + 100;
        check(store.sweep(NOW, 60).expired == 0, "an item from the future is kept");
        check(store.find(future.id) != null, "still there");

        // Clear all leaves a recording in progress alone.
        final PurpleRecordingEntry live = store.begin(0, 785, 0, 0, PurpleRecordingEntry.VOICE, NOW * 1000 + 8);
        check(store.clear() == 1 && store.find(live.id) != null && store.bin().isEmpty(), "clear deletes only the bin");
        store.finish(live);

        // The orphan scan: unfinished recorder files only.
        final File orphans = new File(work, "orphans");
        final File rounds = new File(work, "rounds");
        orphans.mkdirs();
        rounds.mkdirs();
        Files.write(new File(orphans, "audio_recording_voice_1700000000000_1.ogg").toPath(), Arrays.copyOf(whole, whole.length / 2));
        Files.write(new File(orphans, "1700000000000_-9.ogg").toPath(), Arrays.copyOf(whole, whole.length / 3));
        Files.write(new File(orphans, "1700000000001_-9.ogg").toPath(), whole);
        Files.write(new File(orphans, "2_51234567.ogg").toPath(), Arrays.copyOf(whole, whole.length / 2));
        Files.write(new File(orphans, "audio_recording_voice_1700000000000_2.ogg").toPath(), Arrays.copyOf(whole, 40));
        writeUnfinishedMp4(new File(rounds, "round_video_123.mp4"), 2);
        writeUnfinishedMp4(new File(rounds, "round_video_preview_123.mp4"), 0);
        writeUnfinishedMp4(new File(rounds, "notes.mp4"), 0);
        Files.copy(mp4.toPath(), new File(rounds, "1700000000000_-3.mp4").toPath());
        final PurpleRecordingStore.Outcome scan = store.scanOrphans(Collections.singletonList(orphans),
                Collections.singletonList(rounds), NOW + 50);
        check(scan.orphans == 3, "two voice orphans and one round orphan are adopted");
        check(new File(orphans, "1700000000001_-9.ogg").isFile(), "a finished recording is not touched");
        check(new File(orphans, "2_51234567.ogg").isFile(), "a file that is not a recorder's is not touched");
        check(new File(orphans, "audio_recording_voice_1700000000000_2.ogg").isFile(), "a file with no audio is not adopted");
        check(new File(rounds, "round_video_preview_123.mp4").isFile() && new File(rounds, "notes.mp4").isFile()
                && new File(rounds, "1700000000000_-3.mp4").isFile(), "previews, foreign names and finished rounds stay");
        int voiceOrphans = 0;
        int roundOrphans = 0;
        for (PurpleRecordingEntry e : store.bin()) {
            check(e.orphan && !e.hasChat(), "an orphan has no chat");
            if (e.round()) {
                roundOrphans++;
                check(PurpleRecordingEntry.UNRECOVERABLE.equals(e.state), "a round orphan without a config header is kept raw");
            } else {
                voiceOrphans++;
                check(PurpleRecordingEntry.RECOVERED.equals(e.state) && PurpleRecordingCore.scanOgg(new File(e.media)).clean(),
                        "a voice orphan is finalised");
            }
        }
        check(voiceOrphans == 2 && roundOrphans == 1, "the orphans are listed by kind");

        // Damage: an unreadable manifest is set aside, an unknown entry version kept raw.
        Files.write(new File(dir, "recordings.json").toPath(), "{\"format\":1,\"entries\":[{\"v\":9,\"id\":\"x\",\"media\":\"/nowhere\"}]}".getBytes(StandardCharsets.UTF_8));
        read = manifest.load(NOW);
        check(read.size() == 1 && PurpleRecordingEntry.UNRECOVERABLE.equals(read.get(0).state) && read.get(0).inBin(),
                "an entry of an unknown version is listed as unrecoverable");
        Files.write(new File(dir, "recordings.json").toPath(), "{\"format\":2}".getBytes(StandardCharsets.UTF_8));
        check(manifest.load(NOW).isEmpty() && !new File(dir, "recordings.json").exists(), "a manifest of an unknown format is set aside");
        Files.write(new File(dir, "recordings.json").toPath(), "{\"format\":1,\"entr".getBytes(StandardCharsets.UTF_8));
        check(manifest.load(NOW).isEmpty() && !new File(dir, "recordings.json").exists(), "a torn manifest is set aside");
        int aside = 0;
        for (String name : dir.list()) if (name.startsWith("recordings.json.unread-")) aside++;
        check(aside >= 2, "set-aside manifests are kept");

        // The expiry rule is the core's.
        check(PurpleRecordingCore.binItemExpired(NOW, NOW + 48 * hour, 48 * hour), "48 h after binning, 48 h expires");
        check(!PurpleRecordingCore.binItemExpired(NOW, NOW + 48 * hour - 1, 48 * hour), "one second before, it does not");
        check(!PurpleRecordingCore.binItemExpired(NOW, NOW + 100 * 24 * hour, 0), "off never expires");
    }

    /** An MP4Builder-shaped file cut before moov: ftyp, then mdat headers and bytes. */
    static void writeUnfinishedMp4(File file, int extraMdats) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'i', 's', 'o', '2'});
            for (int m = 0; m <= extraMdats; m++) {
                out.write(new byte[]{0, 0, 0, 16, 'm', 'd', 'a', 't', 0, 0, 0, 0, 0, 0, 0, 0});
                final byte[] body = new byte[1000];
                body[0] = 0;
                body[1] = 0;
                body[2] = 3;
                body[3] = (byte) 0xe4;
                body[4] = 0x65;
                out.write(body);
            }
        }
    }
}
