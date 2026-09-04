/*
 * This is the source code of Purple Telegram for Android.
 *
 * The Work Mode schedule: rules in settings.toml that put a preset on at a time
 * of day. Mirrors the desktop fork's purple_schedule.cpp - see
 * docs/purple/work_mode.md, "The schedule".
 *
 * All the deciding is in the core. This class is the clock that asks it.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.nio.charset.Charset;

public final class PurpleSchedule {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * How often the clock is re-read, and therefore how late a boundary can be.
     *
     * Computing the exact moment of the next one and sleeping until it would be
     * tidier, and would then have to survive every way a wall clock can move
     * underneath it - a device waking, a timezone change, the DST hour.
     * Re-reading the clock on a cheap tick survives all of them by construction.
     *
     * The same thirty seconds the desktop uses, and it costs the same here: the
     * tick only runs while a schedule is configured and not paused, and it does
     * no work at all unless the answer has moved.
     */
    private static final long TICK_MS = 30 * 1000L;

    private static final Runnable TICK = PurpleSchedule::tick;

    private PurpleSchedule() {
    }

    /**
     * Re-ticks now and keeps ticking, or stops.
     *
     * Called from every reload, because either file can change the answer
     * sooner than the next thirty seconds would - and because a boundary passed
     * while the app was closed has to be caught up on at the next launch, which
     * is the first reload there is.
     */
    public static void refresh() {
        AndroidUtilities.cancelRunOnUIThread(TICK);
        if (!running()) {
            return;
        }
        AndroidUtilities.runOnUIThread(TICK);
    }

    /**
     * Whether there is anything for the clock to do.
     *
     * A file with no rules and a schedule the user has paused are both "no", so
     * the ticking stops entirely rather than waking every thirty seconds to read
     * state.toml and decide nothing. Both start it again through
     * {@link #refresh()}, since both are changes to a file the gate reloads.
     */
    private static boolean running() {
        final PurpleCore.Loaded current = PurpleGate.state();
        return current != null
                && current.clock.scheduleConfigured
                && !current.clock.schedulePaused;
    }

    private static void tick() {
        AndroidUtilities.cancelRunOnUIThread(TICK);
        if (!running()) {
            return;
        }
        AndroidUtilities.runOnUIThread(TICK, TICK_MS);
        apply();
    }

    /**
     * One boundary, if there is one.
     *
     * The core answers null on every tick that is not at a boundary, which is
     * almost all of them: it acts on the recorded target changing rather than on
     * what the rules say right now. That is what lets a preset chosen by hand
     * stand until the next boundary instead of being put back a second later.
     *
     * The reload at the end comes back here through {@link #refresh()}, and the
     * tick it runs finds the target already recorded and does nothing - so this
     * settles after exactly one pass rather than looping.
     */
    private static void apply() {
        final PurpleCore.Tick tick = PurpleCore.scheduleTick(PurpleState.read());
        if (tick == null) {
            return;
        }
        if (!PurpleState.write(tick.text.getBytes(UTF_8))) {
            return;
        }
        FileLog.d("Purple: schedule wants '" + tick.target + "'"
                + (tick.applied
                        ? "."
                        : ", keeping '" + tick.kept + "' (" + tick.keptSource + ")."));
        if (tick.applied) {
            PurpleGate.reload("schedule");
        }
        // When it was not applied, only the recorded target moved: nothing about
        // the view or the silencing changed, so there is nothing to rebuild. The
        // write above is still what matters, and it is what makes this boundary
        // happen once rather than on every tick from here on. The next reload
        // reads the file back, so nothing is left stale by not doing one now.
    }
}
