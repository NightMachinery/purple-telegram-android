/*
 * This is the source code of Purple Telegram for Android.
 *
 * OS focus sync: Do Not Disturb drives a preset. Mirrors the desktop fork's
 * purple_focus.cpp - see docs/purple/work_mode.md, "OS focus sync".
 *
 * The desktop keeps the detection and the policy apart on purpose: the policy
 * is worth proving once and does not change, while the detection reads a file
 * Apple owns. Android needs no such seam - getCurrentInterruptionFilter() is a
 * documented API that answers in one call - so this class is the detector only,
 * and the policy stays in the core-facing bridge where the desktop's is.
 */

package org.telegram.messenger.purple;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.nio.charset.Charset;

public final class PurpleFocus {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * Where the entry target is kept.
     *
     * It belongs in state.toml beside the rest of the focus fields, and it is
     * not there because this client does not own that schema - the core does,
     * and it is compiled into the desktop too. Its own preference file rather
     * than the app's main one so it cannot be mistaken for a setting: nothing
     * reads it but the next focus exit, and losing it costs one restore that
     * behaves the way it did before this existed.
     */
    private static final String PREFS = "purple_focus";
    private static final String KEY_ENTER_TARGET = "schedule_target_at_focus_enter";

    /**
     * Held deliberately, and never unregistered. The filter is a process-wide
     * fact and the receiver costs nothing while nothing changes; unregistering
     * it when the flag goes off would only mean missing the change that comes
     * while it is off and reporting a stale answer when it comes back.
     */
    private static BroadcastReceiver receiver;

    /** Once per spell of not being able to read the filter, not once a reload. */
    private static boolean complained;

    private static final Runnable APPLY = PurpleFocus::apply;

    private PurpleFocus() {
    }

    /**
     * Re-reads the filter and acts on it, off the current call stack.
     *
     * Called from every reload, because the answer can move with the file as
     * well as with the OS: turning [focus_sync] off while it is holding a
     * preset has to hand that preset back, and enter_preset naming a preset
     * that has just appeared turns the whole thing on.
     *
     * Posted rather than run inline for the reason the schedule's tick is:
     * this ends in a reload of its own, and reloading from inside a reload
     * would nest them.
     */
    public static void refresh() {
        AndroidUtilities.cancelRunOnUIThread(APPLY);
        AndroidUtilities.runOnUIThread(APPLY);
    }

    private static void apply() {
        AndroidUtilities.cancelRunOnUIThread(APPLY);
        final PurpleCore.Loaded current = PurpleGate.state();
        if (current == null) {
            return;
        }
        if (current.focusSyncEnabled) {
            // Only once the file asks for it. A fork whose settings.toml never
            // mentions [focus_sync] registers nothing at all.
            start();
        }
        final Boolean active = focusOn();
        if (active == null) {
            // Holding whatever it last saw, the way the desktop's detector does
            // on a file it cannot make sense of. Reporting "focus is off"
            // because we could not read the filter would end a session that is
            // still running.
            return;
        }
        // The core is asked even when focus sync is off, and that is the point
        // of asking on every reload: the flag going off while it holds a preset
        // is exactly the case the core answers by handing the preset back.
        final PurpleCore.FocusChange change = PurpleCore.focusTick(
                PurpleState.read(), active, enterTarget());
        if (change == null) {
            return;
        }
        if (!PurpleState.write(change.text.getBytes(UTF_8))) {
            return;
        }
        remember(change);
        if ("none".equals(change.change)) {
            // Only the flag moved, which is every change of focus that is not
            // an edge for the policy - focus coming on while sync is off, say.
            // Nothing about the view changed, so there is nothing to rebuild,
            // and the write above is what makes the next edge an edge.
            return;
        }
        FileLog.d("Purple: focus " + change.change + " -> '" + change.preset
                + "' (" + change.source + ").");
        PurpleGate.reload("focus");
    }

    /**
     * Whether the OS says a focus mode is on, or null when it will not say.
     *
     * Any filter other than ALL counts, so Priority, Alarms and None are one
     * answer: they differ in what still gets through to the OS, and none of
     * them is "you are available". UNKNOWN is not an answer at all - it is what
     * comes back before the service is ready - so it is null rather than a
     * guess in either direction.
     *
     * No permission is involved: reading the filter is free, and only
     * <i>setting</i> it needs notification policy access.
     */
    private static Boolean focusOn() {
        if (Build.VERSION.SDK_INT < 23) {
            return null;
        }
        try {
            final NotificationManager manager = (NotificationManager) ApplicationLoader
                    .applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
            final int filter = manager == null
                    ? NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                    : manager.getCurrentInterruptionFilter();
            if (filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                if (!complained) {
                    complained = true;
                    FileLog.d("Purple: the interruption filter is unknown; "
                            + "holding whatever focus sync last saw.");
                }
                return null;
            }
            complained = false;
            return filter != NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Starts listening, once. Safe to call from anywhere. */
    private static synchronized void start() {
        if (Build.VERSION.SDK_INT < 23) {
            // Split from the check below rather than folded into it so the
            // API guard is the plain shape both the reader and lint expect.
            return;
        }
        if (receiver != null) {
            return;
        }
        try {
            final BroadcastReceiver created = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refresh();
                }
            };
            final IntentFilter filter = new IntentFilter(
                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            // NOT_EXPORTED does not keep the system's own broadcast out, and it
            // is what every other receiver in this app registers with.
            if (Build.VERSION.SDK_INT >= 33) {
                ApplicationLoader.applicationContext.registerReceiver(
                        created, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ApplicationLoader.applicationContext.registerReceiver(created, filter);
            }
            receiver = created;
        } catch (Exception e) {
            // A missing watch is a degradation and not a failure: the filter is
            // still read at every reload, which is at least once a launch.
            receiver = null;
            FileLog.e(e);
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** What the schedule wanted when the running session began, or null. */
    private static String enterTarget() {
        try {
            return prefs().getString(KEY_ENTER_TARGET, null);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Keeps the entry target for as long as the session lasts.
     *
     * Cleared the moment focus stops holding the preset, so a value left over
     * from an earlier session can never be read as this one's.
     */
    private static void remember(PurpleCore.FocusChange change) {
        try {
            if (change.enterTarget != null) {
                prefs().edit().putString(KEY_ENTER_TARGET, change.enterTarget).apply();
            } else if (!change.session) {
                prefs().edit().remove(KEY_ENTER_TARGET).apply();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
