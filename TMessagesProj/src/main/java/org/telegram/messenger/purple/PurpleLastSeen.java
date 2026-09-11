/*
 * This is the source code of Purple Telegram for Android.
 *
 * The [last_seen] table: why a coarse last seen is coarse, and what a finished
 * trade left behind. Mirrors the desktop fork's last-seen reasons - see
 * docs/purple/work_mode.md, "Last seen: reasons and the trade".
 *
 * The words, and nothing but the words. Which of the three lines a status gets,
 * whether it can be tapped and how long the cooldown has left are one question
 * asked of one rule in the core - LastSeenNoteNow - so the two forks cannot
 * drift apart about it again. What is left here is English and pixels: the
 * catalogue the sentence comes out of, and how much room the caller has for it.
 *
 * The little cache is still in front of state.toml for the trade log and for
 * the sheet, which want the record itself rather than a decision about it. The
 * drawing path does not go through it any more: it asks the gate, which holds
 * the state it was last loaded with, and noteTrade reloads so that it holds the
 * trade that was just written.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PurpleLastSeen {

    /**
     * The trades read out of state.toml, by peer, or null when nothing has been
     * read yet.
     *
     * Rebuilt rather than updated, and only when {@link #cachedGeneration}
     * falls behind the gate: state.toml can also be replaced under us - an
     * import, a push from the other machine - and a map that only ever learned
     * about this device's own trades would keep showing a moment the file no
     * longer holds.
     */
    private static volatile Map<Long, PurpleCore.Trade> cached;

    private static volatile int cachedGeneration = -1;

    private PurpleLastSeen() {
    }

    /** Whether a coarse last seen is allowed to say why - {@code reasons_p}. */
    public static boolean reasons() {
        final PurpleCore.Loaded current = PurpleGate.state();
        return current != null && current.lastSeenReasons;
    }

    /**
     * Whether the "show mine to see theirs" sheet is offered - {@code trade_p}.
     *
     * The reason line is tappable only while this is on, which is the whole
     * difference between the two switches: turning this off leaves the
     * explanation standing and takes away the offer.
     */
    public static boolean tradeOffered() {
        final PurpleCore.Loaded current = PurpleGate.state();
        return current != null && current.lastSeenTrade;
    }

    /** How long to wait for their exact status - {@code trade_hold}, seconds. */
    public static int holdSeconds() {
        final PurpleCore.Loaded current = PurpleGate.state();
        return current == null ? 10 : Math.max(current.lastSeenTradeHold, 0);
    }

    /**
     * Why this user's last seen reads the way it does, as one of the
     * {@code PurpleCore.REASON_} values.
     *
     * The flattening the core asks for: a status carrying a real moment is
     * exact, the three coarse spellings are coarse, and {@code by_me} is the
     * flag the server sets when the coarsening is the consequence of OUR
     * privacy rules. Everything else - {@code userStatusEmpty}, "a long time
     * ago" - is {@link PurpleCore#REASON_NONE}, because inactivity and a block
     * look identical there and the server does not say which.
     */
    public static int reasonFor(TLRPC.User user) {
        final TLRPC.UserStatus status = (user == null) ? null : user.status;
        if (status == null || user.self || user.bot || user.deleted) {
            return PurpleCore.REASON_NONE;
        }
        final boolean exact = (status instanceof TLRPC.TL_userStatusOnline)
                || (status instanceof TLRPC.TL_userStatusOffline);
        final boolean coarse = (status instanceof TLRPC.TL_userStatusRecently)
                || (status instanceof TLRPC.TL_userStatusLastWeek)
                || (status instanceof TLRPC.TL_userStatusLastMonth);
        if (!exact && !coarse) {
            // userStatusEmpty, or userStatusHidden - which is what the app
            // stores for a user whose status it was never told. Neither has a
            // reason to give.
            return PurpleCore.REASON_NONE;
        }
        try {
            return PurpleCore.lastSeenReason(exact, coarse, status.by_me);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            // Asked once per status line drawn, so a build without the core in
            // it must answer rather than log: no reason is the quiet answer,
            // and the quiet answer is the app exactly as it shipped.
            return PurpleCore.REASON_NONE;
        }
    }

    /**
     * What the last trade with this person read, if it is still inside
     * {@code trade_remember}, or null.
     *
     * @param userId the bare user id; a private chat's dialog id is the same
     */
    public static PurpleCore.Trade remembered(long userId) {
        if (userId == 0) {
            return null;
        }
        return snapshot().get(userId);
    }

    /** Every trade still worth showing, newest read first, for the trade log. */
    public static List<PurpleCore.Trade> trades() {
        try {
            return PurpleCore.trades(PurpleState.read(), System.currentTimeMillis() / 1000L);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Writes down a finished trade.
     *
     * A {@code wasOnlineUnix} of zero is recorded too, and deliberately: a
     * trade whose hold ran out with nothing exact ever arriving is still a
     * moment of exposure that happened, and it is what the cooldown counts.
     *
     * @return whether it reached state.toml
     */
    public static boolean noteTrade(long userId, long wasOnlineUnix) {
        if (userId == 0) {
            return false;
        }
        final long now = System.currentTimeMillis() / 1000L;
        final String text;
        try {
            text = PurpleCore.rememberTrade(PurpleState.read(), userId, now, wasOnlineUnix);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return false;
        }
        if (text == null || !PurpleState.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return false;
        }
        // Dropped rather than patched, so the next reader picks the file up
        // exactly as it was written - including the pruning the core did on
        // the way past.
        cached = null;
        cachedGeneration = -1;
        // And the gate is told, because the gate is what the status lines now
        // ask: it holds the state it was last loaded with, and a trade written
        // only to the file would be invisible to every line until something
        // else happened to reload. The same write-then-reload every other state
        // change here does, and the generation bump it carries is what empties
        // the map above for good measure.
        PurpleGate.reload("last seen trade");
        return true;
    }

    /**
     * The width, in dp, under which the reason collapses to the eyes.
     *
     * A chat header on a phone is nowhere near this - it is the whole action
     * bar less the avatar and the two 52dp button gutters - so the long form is
     * for the profile and for a tablet, and the short one is what the header
     * actually shows. Measured against the width the subtitle was GIVEN rather
     * than the text's own, because the question is how much room there is and
     * not how much the text before the mark happened to use.
     */
    private static final int NARROW_DP = 280;

    /**
     * The core's decision for this user's line, and the numbers behind it.
     *
     * One question asked of one rule, so the two apps cannot come to disagree
     * about which of the three lines a status gets - they had already drifted
     * on the last of them once.
     *
     * A status that is not coarse stops here rather than crossing into the
     * native: it is the first thing {@code LastSeenNoteNow} answers, and this
     * is the drawing path, where most of the people on screen have a status
     * with nothing to explain. The one field the core would still have filled
     * in is the cooldown, and nothing asks a plain line about that - see
     * {@link #cooldownLeft}, which asks for it by itself.
     */
    private static PurpleCore.LastSeenNote note(TLRPC.User user) {
        if (user == null || !coarse(user)) {
            return PurpleCore.LastSeenNote.PLAIN;
        }
        return PurpleCore.lastSeenNote(user.id, reasonFor(user), true);
    }

    /**
     * The status line with the fork's part appended, or the line untouched.
     *
     * Three answers and no fourth, and which one is the core's to say. A
     * remembered trade replaces the coarse text outright - it is a real moment,
     * read on purpose, and saying "last seen recently" over the top of it would
     * be throwing away the one thing the trade was for. A coarse status the
     * server says is coarse because of OUR rules gets the offer. Everything
     * else - hidden by them, "a long time ago", an exact time - is left exactly
     * as the app wrote it, because there is nothing true to add.
     *
     * Only the words are decided here, and only because words are the half the
     * core deliberately does not carry: it would have to ship English to one of
     * the two apps' string catalogues.
     *
     * @param availableWidthPx what the subtitle was given to draw in; zero when
     *                         nothing has been measured yet, which reads as
     *                         narrow
     */
    public static CharSequence decorate(TLRPC.User user, CharSequence status,
            int availableWidthPx) {
        if (user == null || status == null) {
            return status;
        }
        final PurpleCore.LastSeenNote note = note(user);
        if (note.line == PurpleCore.LINE_REMEMBERED) {
            return LocaleController.formatString(R.string.PurpleLastSeenAsOf,
                    LocaleController.formatDateOnline(note.wasOnlineUnix, null),
                    ago(note.readAtUnix));
        }
        if (note.line != PurpleCore.LINE_BY_ME_TAIL) {
            return status;
        }
        final boolean narrow = availableWidthPx <= 0
                || availableWidthPx < AndroidUtilities.dp(NARROW_DP);
        return status + " \u00b7 " + LocaleController.getString(narrow
                ? R.string.PurpleLastSeenByMeShort
                : R.string.PurpleLastSeenByMe);
    }

    /**
     * The same line for a row that has no width to offer and wants none.
     *
     * The member lists, the pickers and the contacts tab carry the mark alone -
     * the words belong to the two places that can also be tapped - so they ask
     * for the narrow form outright rather than measuring a status text view
     * that has not been laid out yet at the moment it is filled in.
     */
    public static CharSequence decorate(TLRPC.User user, CharSequence status) {
        return decorate(user, status, 0);
    }

    /**
     * Whether tapping this status line should offer the trade.
     *
     * Straight from the core, which is the repair: a remembered line is
     * tappable too, so the first trade no longer replaces the only door into
     * the sheet and leaves the second one unreachable for a whole
     * {@code trade_remember}. Inside the cooldown the sheet counts the wait
     * down rather than refusing - see PurpleLastSeenTrade.
     */
    public static boolean tappable(TLRPC.User user) {
        return note(user).tappable;
    }

    /**
     * How long until a trade with this person would be allowed again, in
     * seconds, or 0 when one is allowed right now.
     *
     * What the sheet counts down. Asked of the core on its own - with no status
     * to describe - because the sheet is reachable from a line that says
     * nothing about the cooldown, and from the seen-by sheet's button, which
     * has no status line at all.
     */
    public static int cooldownLeft(long userId) {
        if (userId == 0) {
            return 0;
        }
        return PurpleCore.lastSeenNote(
                userId, PurpleCore.REASON_NONE, false).cooldownLeftSeconds;
    }

    /** Whether this status is one of the three coarse spellings. */
    private static boolean coarse(TLRPC.User user) {
        final TLRPC.UserStatus status = (user == null) ? null : user.status;
        return (status instanceof TLRPC.TL_userStatusRecently)
                || (status instanceof TLRPC.TL_userStatusLastWeek)
                || (status instanceof TLRPC.TL_userStatusLastMonth);
    }

    /**
     * How old the news is, in the shortest honest words.
     *
     * Minutes and then hours and no further, because {@code trade_remember}
     * tops out at a day by default and a read the app is still willing to show
     * has never been older than that.
     */
    public static CharSequence ago(long readAtUnix) {
        final long seconds = Math.max(System.currentTimeMillis() / 1000L - readAtUnix, 0L);
        if (seconds < 60) {
            return LocaleController.getString(R.string.PurpleLastSeenAgoNow);
        } else if (seconds < 3600) {
            return LocaleController.formatPluralString(
                    "PurpleLastSeenAgoMinutes", (int) (seconds / 60));
        }
        return LocaleController.formatPluralString(
                "PurpleLastSeenAgoHours", (int) (seconds / 3600));
    }

    /** The trades by peer, rebuilt whenever the gate has moved under us. */
    private static Map<Long, PurpleCore.Trade> snapshot() {
        final int generation = PurpleGate.generation();
        final Map<Long, PurpleCore.Trade> current = cached;
        if (current != null && cachedGeneration == generation) {
            return current;
        }
        final Map<Long, PurpleCore.Trade> rebuilt = new HashMap<>();
        try {
            for (PurpleCore.Trade trade : PurpleCore.trades(
                    PurpleState.read(), System.currentTimeMillis() / 1000L)) {
                rebuilt.put(trade.peer, trade);
            }
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            // An unreadable state file is not a reason to keep asking on every
            // status line: an empty map is the honest answer and the
            // generation stamp keeps it until something moves.
        }
        final Map<Long, PurpleCore.Trade> result = Collections.unmodifiableMap(rebuilt);
        cached = result;
        cachedGeneration = generation;
        return result;
    }
}
