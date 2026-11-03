package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;

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
}
