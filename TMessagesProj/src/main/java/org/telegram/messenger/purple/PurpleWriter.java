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
        // And the other machine may want it. Nothing happens here unless
        // [sync] send_after_save_p is on; the reload comes first because the
        // send reads the file back, and this device should already be running
        // whatever it is about to post.
        PurpleSync.afterWrite(reason, false);
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
     * Sets one string under one table - {@code [schedule] outside}, and the
     * label {@code [devices]} gives a device id.
     *
     * The string half of {@link #setTableBool}, and read fresh for the same
     * reason.
     */
    public static String setTableString(
            String table, String key, String value, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.setTableString(settings, table, key, value),
                reason);
    }

    /**
     * Writes a new empty {@code [[schedule.rulesets]]} block.
     *
     * @return null on success, or the core's refusal - an empty name, or one
     *         already taken, since the name is how every later edit finds it
     */
    public static String addRuleset(
            String name, String device, String mode, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.addRuleset(settings, name, device, mode),
                reason);
    }

    /** Takes a whole ruleset out, its rules with it. */
    public static String removeRuleset(String name, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(PurpleCore.removeRuleset(settings, name), reason);
    }

    /**
     * Sets one of a ruleset's own string keys - {@code device}, {@code mode},
     * {@code outside}, or {@code name} for a rename.
     *
     * An empty {@code value} takes the key out of the file, which is how a
     * screen says "back to the default" without writing the default down.
     */
    public static String setRulesetString(
            String name, String key, String value, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.setRulesetString(settings, name, key, value),
                reason);
    }

    /**
     * Creates a list, empty.
     *
     * No title: the box asks for one name, and a title that only repeats the
     * name would be a line in the file saying nothing. Rename it in the file
     * to give it one.
     *
     * @return null on success, or the core's refusal - an empty name, one
     *         starting with {@code *}, or a name already taken
     */
    public static String addList(String name, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(PurpleCore.addList(settings, name, ""), reason);
    }

    /**
     * Rewrites one schedule rule, key by key.
     *
     * The three {@code expected} values are what the screen read off the rule
     * it is editing. The core refuses when the rule at {@code index} no longer
     * says them, which is what stops a dialog left open across somebody else's
     * edit from rewriting a rule other than the one on it.
     *
     * {@code ruleset} is where the rule lives: empty for the flat
     * {@code [[schedule.rules]]} array, a ruleset's name otherwise, and
     * {@code index} then counts within that one.
     */
    public static String setScheduleRule(
            String ruleset, int index, int expectedFrom, int expectedTill,
            String expectedPreset, boolean enabled, int[] days, int from, int till,
            String preset, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.setScheduleRule(settings, ruleset, index, expectedFrom,
                        expectedTill, expectedPreset, enabled, days, from, till, preset),
                reason);
    }

    /** Writes a new rule after the ruleset's last one, or starts the section. */
    public static String appendScheduleRule(
            String ruleset, boolean enabled, int[] days, int from, int till,
            String preset, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.appendScheduleRule(
                        settings, ruleset, enabled, days, from, till, preset),
                reason);
    }

    /** Takes one rule out, on the same expectation as {@link #setScheduleRule}. */
    public static String removeScheduleRule(
            String ruleset, int index, int expectedFrom, int expectedTill,
            String expectedPreset, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.removeScheduleRule(settings, ruleset, index, expectedFrom,
                        expectedTill, expectedPreset),
                reason);
    }

    /**
     * Writes a new {@code [[screen_time.budgets]]} block after the last one.
     *
     * {@code target} is spelled the way the file spells it - {@code "all"},
     * {@code "chat:<id>"}, {@code "kind:<kind>"}, {@code "preset:<name>"} -
     * because that is what the key holds, and the parser is the only thing that
     * knows the grammar. A target the parser cannot read back is refused by the
     * core rather than written, so a screen that got one wrong hears about it
     * instead of leaving a budget that is not there.
     *
     * @return null on success, or a reason to show the user
     */
    public static String appendBudget(
            String target, int perDaySeconds, String mode, int snoozeSeconds,
            int snoozesPerDay, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.appendBudget(settings, target, perDaySeconds, mode,
                        snoozeSeconds, snoozesPerDay),
                reason);
    }

    /**
     * Rewrites one budget, key by key.
     *
     * {@code index} is {@link PurpleCore.Budget#sourceIndex} and
     * {@code expectedTarget} is the target the screen read off it. The core
     * refuses when the budget there says something else, which is what stops a
     * dialog left open across somebody else's edit from rewriting a budget
     * other than the one it was opened on.
     */
    public static String setBudget(
            int index, String expectedTarget, String target, int perDaySeconds,
            String mode, int snoozeSeconds, int snoozesPerDay, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(
                PurpleCore.setBudget(settings, index, expectedTarget, target,
                        perDaySeconds, mode, snoozeSeconds, snoozesPerDay),
                reason);
    }

    /** Takes one budget out, on the same expectation as {@link #setBudget}. */
    public static String removeBudget(int index, String expectedTarget, String reason) {
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        return apply(PurpleCore.removeBudget(settings, index, expectedTarget), reason);
    }
}
