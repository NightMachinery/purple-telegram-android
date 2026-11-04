/*
 * This is the source code of Purple Telegram for Android.
 *
 * The [recording] table of settings.toml, as the core parsed it. See
 * docs/media-recording-design.md, "Settings".
 */

package org.telegram.messenger.purple;

/**
 * One reading of the {@code [recording]} table. The core parses the table and
 * holds every key to its allowed choices; this class only names the packed
 * values {@link PurpleCore#recordingSettings()} returns, so Java never
 * restates a default or a rule. When the core cannot be asked, {@link #read()}
 * answers with the core's defaults, listed here once for that case.
 */
public final class PurpleRecordingSettings {

    /** The values of {@code round_journal} the settings row offers, in seconds; 0 is "off". */
    public static final int[] JOURNAL_CHOICES = {0, 1, 2, 5};

    /** The values of {@code bin_keep} the settings row offers, in seconds; 0 is "never". */
    public static final int[] BIN_KEEP_CHOICES = {12 * 3600, 24 * 3600, 48 * 3600, 7 * 24 * 3600, 0};

    /** The number of packed values the bridge returns. */
    static final int PACKED = 12;

    /** The core's defaults, in the bridge's order; used only when the core cannot be asked. */
    private static final int[] DEFAULTS = {1, 1, 1, 1, 48 * 3600, 1, 1, 1, 1, 0, 30 * 60, 1};

    public final boolean keepAcrossChats;
    public final boolean background;
    public final boolean roundFloat;
    /** {@code round_journal}: seconds between journal batches, 0 for off (the config header is still written). */
    public final int roundJournalSeconds;
    /** {@code bin_keep}: how long the bin keeps an item, in seconds, 0 for never. */
    public final int binKeepSeconds;
    /** {@code keep_discarded_p}: Discard moves the recording to the bin. */
    public final boolean keepDiscarded;
    public final boolean sendElsewhere;
    public final boolean callPause;
    public final boolean headset;
    public final boolean headsetHoldStart;
    public final int longNudgeSeconds;
    public final boolean previewTrim;
    /** Whether these came from the core, rather than the fallback defaults. */
    public final boolean fromCore;

    PurpleRecordingSettings(int[] packed) {
        fromCore = packed != null && packed.length == PACKED;
        final int[] v = fromCore ? packed : DEFAULTS;
        keepAcrossChats = v[0] != 0;
        background = v[1] != 0;
        roundFloat = v[2] != 0;
        roundJournalSeconds = v[3];
        binKeepSeconds = v[4];
        keepDiscarded = v[5] != 0;
        sendElsewhere = v[6] != 0;
        callPause = v[7] != 0;
        headset = v[8] != 0;
        headsetHoldStart = v[9] != 0;
        longNudgeSeconds = v[10];
        previewTrim = v[11] != 0;
    }

    /** The table as the loaded settings hold it now. */
    public static PurpleRecordingSettings read() {
        return new PurpleRecordingSettings(PurpleCore.recordingSettings());
    }

    /** The settings.toml spelling of a {@code round_journal} choice. */
    public static String journalValue(int seconds) {
        return seconds <= 0 ? "off" : seconds + "s";
    }

    /** The settings.toml spelling of a {@code bin_keep} choice. */
    public static String binKeepValue(int seconds) {
        if (seconds <= 0) return "off";
        if (seconds % (24 * 3600) == 0 && seconds >= 7 * 24 * 3600) return (seconds / (24 * 3600)) + "d";
        return (seconds / 3600) + "h";
    }
}
