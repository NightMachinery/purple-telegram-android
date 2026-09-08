/*
 * This is the source code of Purple Telegram for Android.
 *
 * The one path everything that edits settings.toml goes down. Mirrors the
 * desktop fork's write side - see docs/purple/config.md.
 *
 * Every write is a splice: the core rewrites the lines it is responsible for
 * and leaves every other byte where the user put it. That is the point of the
 * file being TOML at all, and it is why nothing here re-serialises anything.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;

import java.nio.charset.Charset;

public final class PurpleWriter {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private PurpleWriter() {
    }

    /**
     * Writes what a splice produced, and reloads.
     *
     * The three outcomes are all normal and only one of them is a failure. A
     * refusal - an unwritable shape, a rule that has moved under the screen -
     * leaves both the file and the running resolution exactly as they were. A
     * splice that changed nothing is not an error either, and is not worth a
     * write that would only churn the file's timestamp.
     *
     * @param reason what asked for it, for the log line and the reload
     * @return null on success, or a reason to show the user
     */
    public static String apply(PurpleCore.SpliceResult result, String reason) {
        if (result == null) {
            return "no result from the core";
        }
        if (!result.ok()) {
            FileLog.e("Purple: " + reason + " refused: " + result.error);
            return result.error;
        }
        if (!result.changed || result.text == null) {
            return null;
        }
        if (!PurpleSettings.writeAtomic(
                PurpleSettings.settingsFile(), result.text.getBytes(UTF_8))) {
            return "could not write settings.toml";
        }
        // The file the gate reads has changed under it. The watcher would get
        // there on its own, but only after its quiet period, and a switch the
        // user has just flipped should take effect now.
        PurpleGate.reload(reason);
        return null;
    }

    /**
     * Sets one boolean under one table - the Premium switch, the suggestions
     * flag, the schedule's own.
     *
     * Reads the file again rather than trusting anything the screen was built
     * from: an import or an edit may have landed while it was open, and a
     * splice against stale text would write back a file that had moved on.
     * Every entry point below does the same, for the same reason.
     */
    public static String setTableBool(
            String table, String key, boolean value, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.setTableBool(settings, table, key, value),
                reason);
    }

    /**
     * Rewrites one schedule rule, key by key.
     *
     * The three {@code expected} values are what the screen read off the rule
     * it is editing. The core refuses when the rule at {@code index} no longer
     * says them, which is what stops a dialog left open across somebody else's
     * edit from rewriting a rule other than the one on it.
     */
    public static String setScheduleRule(
            int index, int expectedFrom, int expectedTill, String expectedPreset,
            boolean enabled, int[] days, int from, int till, String preset,
            String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.setScheduleRule(settings, index, expectedFrom, expectedTill,
                        expectedPreset, enabled, days, from, till, preset),
                reason);
    }

    /** Writes a new rule after the last one, or starts the section. */
    public static String appendScheduleRule(
            boolean enabled, int[] days, int from, int till, String preset,
            String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.appendScheduleRule(settings, enabled, days, from, till, preset),
                reason);
    }

    /** Takes one rule out, on the same expectation as {@link #setScheduleRule}. */
    public static String removeScheduleRule(
            int index, int expectedFrom, int expectedTill, String expectedPreset,
            String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.removeScheduleRule(
                        settings, index, expectedFrom, expectedTill, expectedPreset),
                reason);
    }
}
