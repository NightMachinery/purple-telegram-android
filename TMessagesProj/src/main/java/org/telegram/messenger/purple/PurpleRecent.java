/*
 * This is the source code of Purple Telegram for Android.
 *
 * The [recent] grace period: a chat stays in the view for a while after you
 * stop looking at it. Mirrors the desktop fork's History::purpleSetOpened() and
 * purpleShownAsRecent() - see docs/purple/work_mode.md, "Staying a little
 * longer after you close it".
 *
 * An unread-watching mode has one sharp edge and it is the edge you meet first:
 * reading a chat is exactly what takes it out of the view, so it vanishes on
 * the frame you click away from it. This is the repair.
 */

package org.telegram.messenger.purple;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public final class PurpleRecent {

    /**
     * One chat counting down, after you closed it.
     *
     * A list rather than a map keyed by id, because only a handful of chats are
     * ever in grace at once and the account has to be part of the key - packing
     * two numbers into one to save a linear scan over three entries would be
     * paying in clarity for nothing.
     */
    private static final class Grace {
        final int account;
        final long dialogId;
        final long until; // SystemClock.elapsedRealtime() millis.

        Grace(int account, long dialogId, long until) {
            this.account = account;
            this.dialogId = dialogId;
            this.until = until;
        }
    }

    private static final ArrayList<Grace> grace = new ArrayList<>();

    /**
     * Whether anything is open or counting down.
     *
     * The whole point of this flag: {@link #shown} is asked once per row per
     * rebuild, and almost always the answer is "nothing is going on" - which
     * this settles with one volatile read and no lock.
     */
    private static volatile boolean active;

    private static volatile long openedDialogId;
    private static volatile int openedAccount = -1;

    /**
     * Whether the open chat's grace will apply when it closes.
     *
     * Decided when the chat is opened, not when it is closed, and that is not an
     * optimisation: two of the three scopes ask where the chat was at that
     * moment, and reading it is precisely what moves the answer. By the time you
     * close it, "was it in the view" has become "was it in the view before I
     * read it", which is a question nothing can answer afterwards.
     */
    private static volatile boolean openedEligible;

    private static final Runnable SWEEP = PurpleRecent::sweep;

    private PurpleRecent() {
    }

    /**
     * Called when a chat becomes, or stops being, the one you are looking at.
     *
     * @param dialogId the chat now open, or 0 when none is
     */
    public static void setOpened(int currentAccount, long dialogId) {
        final int seconds = staySeconds();
        final long wasId = openedDialogId;
        final int wasAccount = openedAccount;
        boolean changed = false;

        if (wasId != 0 && (wasId != dialogId || wasAccount != currentAccount)) {
            // The clock starts on close, not on open, which is the whole
            // difference between a setting that works and one that does not:
            // reading something for five minutes must not burn the grace before
            // you have finished with it.
            if (openedEligible && seconds > 0) {
                synchronized (grace) {
                    drop(wasAccount, wasId);
                    grace.add(new Grace(wasAccount, wasId,
                            SystemClock.elapsedRealtime() + seconds * 1000L));
                }
                changed = true;
            }
            openedDialogId = 0;
            openedAccount = -1;
            openedEligible = false;
        }

        if (dialogId != 0 && (wasId != dialogId || wasAccount != currentAccount)) {
            // Whatever it had banked is spent: it is open again, and the grace
            // it earns this time is decided from scratch below.
            synchronized (grace) {
                drop(currentAccount, dialogId);
            }
            // Before the fields are set, so the eligibility test below - which
            // asks the chat list, which asks back here - sees this chat as not
            // open rather than as trivially shown by its own grace.
            final boolean eligible = seconds > 0 && eligible(currentAccount, dialogId);
            openedDialogId = dialogId;
            openedAccount = currentAccount;
            openedEligible = eligible;
            changed = true;
        }

        refreshActive();
        if (changed && seconds > 0 && PurpleGate.filtering()) {
            // Both directions. Opening can reveal a chat the preset hides, under
            // the wider scopes, and closing one the grace does not cover has to
            // take it away again.
            arm();
            PurpleGate.refreshLists();
        }
    }

    /**
     * Whether this chat is in the view because you were recently looking at it.
     *
     * Re-reads the setting on every query, so turning {@code [recent]} off takes
     * effect at once rather than at the end of whatever was already running.
     */
    public static boolean shown(int currentAccount, long dialogId) {
        if (!active || staySeconds() <= 0) {
            return false;
        }
        if (dialogId == openedDialogId && currentAccount == openedAccount) {
            // Open right now: it stays regardless, and it is not counting down
            // yet - the clock starts when you stop looking at it.
            return openedEligible;
        }
        final long now = SystemClock.elapsedRealtime();
        synchronized (grace) {
            for (int i = 0, n = grace.size(); i < n; ++i) {
                final Grace entry = grace.get(i);
                if (entry.dialogId == dialogId && entry.account == currentAccount) {
                    return now < entry.until;
                }
            }
        }
        return false;
    }

    /** What {@code stay_visible_after_close} is set to, in seconds. */
    private static int staySeconds() {
        final PurpleCore.Loaded current = PurpleGate.state();
        return current == null ? 0 : current.clock.recentSeconds;
    }

    /**
     * Whether a chat opened now would earn a grace period when it closes.
     *
     * {@code already_in_view} is the narrow repair - only a chat that was in the
     * view when it was opened, so nothing you open can pull in a chat the preset
     * was hiding. {@code any_open_chat} is the single rule, and the only one that
     * helps when you reach a hidden chat through search. The third is that,
     * minus the chats already one click away on a folder tab that is showing.
     */
    private static boolean eligible(int currentAccount, long dialogId) {
        final PurpleCore.Loaded current = PurpleGate.state();
        if (current == null) {
            return false;
        }
        switch (current.clock.recentScope) {
        case PurpleCore.RECENT_ANY_OPEN_CHAT:
            return true;
        case PurpleCore.RECENT_EXCEPT_IN_FOLDER:
            return !reachableElsewhere(currentAccount, dialogId);
        default: {
            final TLRPC.Dialog dialog =
                    MessagesController.getInstance(currentAccount).dialogs_dict.get(dialogId);
            return dialog != null && PurpleGate.shown(currentAccount, dialog);
        }
        }
    }

    /**
     * Whether a folder tab the preset is showing already holds this chat.
     *
     * Asked of the strip rather than of the account's folders, because a folder
     * with no tab is not somewhere you can reach the chat from. The desktop also
     * checks the preset's extra views here; there are none on Android yet, and
     * this is one of the places that will need them when there are.
     *
     * Runs once per chat opened, not per row, so building the strip's view here
     * is the honest way to ask rather than a cost worth avoiding.
     */
    private static boolean reachableElsewhere(int currentAccount, long dialogId) {
        try {
            final MessagesController controller = MessagesController.getInstance(currentAccount);
            final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (dialog == null) {
                return false;
            }
            final ArrayList<MessagesController.DialogFilter> shown =
                    PurpleGate.shownFilters(controller.getDialogFiltersUnrestricted());
            if (shown == null) {
                return false;
            }
            for (int a = 0, n = shown.size(); a < n; ++a) {
                final MessagesController.DialogFilter filter = shown.get(a);
                if (filter == null || filter.isDefault()) {
                    // The default tab is the view being decided, so it is not
                    // "elsewhere".
                    continue;
                }
                if (PurpleGate.folderHolds(currentAccount, filter, dialogId)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // A list the UI thread was rewriting underneath us. "Not reachable
            // elsewhere" is the generous answer, and generous is the safe
            // direction here: the worst it costs is one grace period given
            // where a stricter reading would have withheld it.
            return false;
        }
        return false;
    }

    /** Drops any entry for this chat. Callers hold the lock. */
    private static void drop(int account, long dialogId) {
        for (int i = grace.size() - 1; i >= 0; --i) {
            final Grace entry = grace.get(i);
            if (entry.dialogId == dialogId && entry.account == account) {
                grace.remove(i);
            }
        }
    }

    /** Recomputes the fast-path flag from what is actually outstanding. */
    private static void refreshActive() {
        synchronized (grace) {
            active = openedDialogId != 0 || !grace.isEmpty();
        }
    }

    /**
     * Arms one timer for the earliest deadline outstanding.
     *
     * One rather than a timer per chat, because nothing else would ever bring
     * the row back: no message arrives and no unread moves when a clock runs
     * out, so without this the chat would sit in the view until something
     * unrelated rebuilt the list.
     */
    private static void arm() {
        AndroidUtilities.cancelRunOnUIThread(SWEEP);
        long earliest = 0;
        synchronized (grace) {
            for (int i = 0, n = grace.size(); i < n; ++i) {
                final long until = grace.get(i).until;
                if (earliest == 0 || until < earliest) {
                    earliest = until;
                }
            }
        }
        if (earliest == 0) {
            return;
        }
        AndroidUtilities.runOnUIThread(SWEEP,
                Math.max(earliest - SystemClock.elapsedRealtime(), 0L));
    }

    /** Drops what has run out, rebuilds the lists if anything did, and re-arms. */
    private static void sweep() {
        final long now = SystemClock.elapsedRealtime();
        boolean dropped = false;
        synchronized (grace) {
            for (int i = grace.size() - 1; i >= 0; --i) {
                if (grace.get(i).until <= now) {
                    grace.remove(i);
                    dropped = true;
                }
            }
        }
        refreshActive();
        if (dropped) {
            PurpleGate.refreshLists();
        }
        arm();
    }
}
