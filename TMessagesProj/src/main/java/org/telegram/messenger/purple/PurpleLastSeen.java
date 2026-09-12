/*
 * This is the source code of Purple Telegram for Android.
 *
 * The [last_seen] table: why a last seen reads the way it does, and what a
 * finished trade left behind. Mirrors the desktop fork's last-seen reasons - see
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
import org.telegram.messenger.UserObject;
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
     * Which of the three shapes this user's status has, as one of the
     * {@code PurpleCore.SHAPE_} values.
     *
     * The one fact about a status the core cannot work out for itself: a status
     * is a TL type, and neither fork's TL layer belongs in there. So this is
     * where a {@code TLRPC.UserStatus} is flattened, and the shared rules take
     * it from there.
     *
     * The three coarse spellings are asked about first, because the app
     * normalises their {@code expires} to a sentinel lazily and one that has
     * not been through {@code formatUserStatus} yet still reads as zero.
     *
     * What is left over follows the line the app itself draws, in
     * {@code LocaleController.formatUserStatus}: a null status, a deleted user
     * and an {@code expires} of zero are all "a long time ago" there -
     * {@code userStatusEmpty}, and {@code userStatusHidden}, which is what the
     * app stores for a user whose status it was never told - so they are
     * {@link PurpleCore#SHAPE_LONG_AGO} here. Everything else has a real moment
     * in it, online now included, and is {@link PurpleCore#SHAPE_EXACT}.
     */
    public static int shapeFor(TLRPC.User user) {
        final TLRPC.UserStatus status = (user == null) ? null : user.status;
        if ((status instanceof TLRPC.TL_userStatusRecently)
                || (status instanceof TLRPC.TL_userStatusLastWeek)
                || (status instanceof TLRPC.TL_userStatusLastMonth)) {
            return PurpleCore.SHAPE_COARSE;
        }
        if (status == null || status.expires == 0 || UserObject.isDeleted(user)) {
            return PurpleCore.SHAPE_LONG_AGO;
        }
        return PurpleCore.SHAPE_EXACT;
    }

    /**
     * Why this user's last seen reads the way it does, as one of the
     * {@code PurpleCore.REASON_} values.
     *
     * Only a coarse status has one: {@code by_me} is the flag the server sets
     * when the coarsening is the consequence of OUR privacy rules, and it is
     * read nowhere else. An exact status has nothing to explain, and "a long
     * time ago" is the one the fork refuses to explain, because inactivity and
     * a block look identical there and the server does not say which. Both are
     * {@link PurpleCore#REASON_NONE}, and the core is what says so.
     */
    public static int reasonFor(TLRPC.User user) {
        final TLRPC.UserStatus status = (user == null) ? null : user.status;
        if (status == null || user.self || user.bot || user.deleted) {
            return PurpleCore.REASON_NONE;
        }
        try {
            return PurpleCore.lastSeenReason(shapeFor(user), status.by_me);
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
     * An EXACT status stops here rather than crossing into the native - and
     * only an exact one, which is the change: "a long time ago" used to stop
     * here too, and that is exactly where a remembered read is worth the most,
     * because it is now the only moment anybody has. An exact status is the
     * first thing {@code LastSeenNoteNow} answers and this is the drawing path,
     * where most of the people on screen have one; the one field the core would
     * still have filled in is the cooldown, and nothing asks a plain line about
     * that - see {@link #cooldownLeft}, which asks for it by itself.
     *
     * Ourselves, a bot and a deleted account stop here too. None of them has a
     * status anybody traded for, so there is nothing remembered to put over it.
     */
    private static PurpleCore.LastSeenNote note(TLRPC.User user) {
        if (user == null || user.self || user.bot || user.deleted) {
            return PurpleCore.LastSeenNote.PLAIN;
        }
        final int shape = shapeFor(user);
        if (shape == PurpleCore.SHAPE_EXACT) {
            return PurpleCore.LastSeenNote.PLAIN;
        }
        final int reason = reasonFor(user);
        if (shape == PurpleCore.SHAPE_COARSE) {
            countCoarse(reason);
        }
        return PurpleCore.lastSeenNote(user.id, reason, shape);
    }

    /** When the line below last said anything, so it says it rarely. */
    private static long countedSince;

    /** Coarse statuses drawn since then, and how many the server blamed on us. */
    private static int coarseSeen;

    private static int coarseByMe;

    /**
     * The discriminator for "the offer never appears any more".
     *
     * Three things have to line up before the trade can be offered about
     * somebody - the status is coarse, the server says it is coarse because of
     * OUR rules, and the two switches are on - and from the outside all three
     * failures look the same: no mark, no button, nothing to tap. This says
     * which of them it is, in the shape 14a's counter line took, because the
     * one thing that cannot be worked out by reading the code is what the
     * server is actually sending to one particular account:
     *
     *     Purple: last seen: 12 coarse, 0 by me (reasons on, offer on)
     *
     * A zero on the second number with the switches on means nobody's status is
     * ours to trade for - the account's own last seen is not the thing in the
     * way - and no change in here can conjure an offer out of that. The other
     * shapes name the switch that is off.
     *
     * Counted rather than printed per row, and at most once every half minute,
     * because a member list binds a row at a time while it scrolls. It counts
     * questions rather than people - one row asks for the line and again for
     * the tap - so the two totals are a ratio to read and not a census. Drawing
     * is the UI thread's, so the counters are not synchronised: a number that
     * exists to be read by eye can afford to lose a race it will not have.
     */
    private static void countCoarse(int reason) {
        coarseSeen += 1;
        if (reason == PurpleCore.REASON_BY_ME) {
            coarseByMe += 1;
        }
        final long now = System.currentTimeMillis();
        if (countedSince == 0) {
            countedSince = now;
            return;
        }
        if (now - countedSince < 30_000L) {
            return;
        }
        FileLog.d("Purple: last seen: " + coarseSeen + " coarse, " + coarseByMe
                + " by me (reasons " + (reasons() ? "on" : "off")
                + ", offer " + (tradeOffered() ? "on" : "off") + ")");
        countedSince = now;
        coarseSeen = 0;
        coarseByMe = 0;
    }

    /**
     * The status line with the fork's part appended, or the line untouched.
     *
     * Three answers and no fourth, and which one is the core's to say. A
     * remembered trade replaces the text underneath outright - it is a real
     * moment, read on purpose, and saying "last seen recently" over the top of
     * it would be throwing away the one thing the trade was for. That holds
     * over "a long time ago" as well, for as long as {@code trade_remember}
     * keeps the memory: what gates it is the age of the read, not the shape of
     * the status that has since moved under it. A coarse status the server says
     * is coarse because of OUR rules gets the offer. Everything else - hidden
     * by them, an exact time, "a long time ago" with no read to put over it -
     * is left exactly as the app wrote it, because there is nothing true to
     * add.
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
     * Whether the trade has anything to offer about this person, drawn line or
     * no drawn line.
     *
     * The same question {@link #tappable} answers, asked for an affordance that
     * is not a line. A tap target has to sit on something the user can see, so
     * a tail {@code reasons_p} switched off is rightly not tappable - but the
     * profile's button is drawn beside the status rather than inside it, and it
     * belongs to the offer rather than to the explanation.
     *
     * Gating it on the line left {@code reasons_p = false} with no way into the
     * sheet at all for somebody who had never traded: the tail was gone by
     * request, the remembered line needs a trade to exist before it can be the
     * door, and the seen-by sheet's own button is only reachable from this one.
     * The offer is {@code trade_p} and nothing else, which is what the key says
     * it means.
     */
    public static boolean offered(TLRPC.User user) {
        if (tappable(user)) {
            return true;
        }
        // Not the note's line, because there is no line: the offer stands on
        // the same two facts the tail would have been drawn from, minus the
        // switch that governs drawing it.
        return tradeOffered()
                && shapeFor(user) == PurpleCore.SHAPE_COARSE
                && reasonFor(user) == PurpleCore.REASON_BY_ME;
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
        // SHAPE_EXACT, because the cooldown is filled in before the core looks
        // at the shape at all and an exact status is the cheapest way past it -
        // there is no status being described here, only a number being asked
        // for.
        return PurpleCore.lastSeenNote(
                userId, PurpleCore.REASON_NONE,
                PurpleCore.SHAPE_EXACT).cooldownLeftSeconds;
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
