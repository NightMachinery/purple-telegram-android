/*
 * This is the source code of Purple Telegram for Android.
 *
 * The hard budget's cover: when a `[[screen_time.budgets]]` written `mode =
 * "hard"` has spent its day on this chat, the chat goes behind this and stays
 * there until a snooze or tomorrow.
 *
 * What it deliberately does not do is touch anything real. Messages still
 * arrive, notifications still fire, the chat is still in the list and still
 * searchable - this is a screen in front of a screen and nothing more. And the
 * session keeps recording behind it: sitting on the cover is time in the chat,
 * counted as reading, so the total says what actually happened rather than
 * flattering the budget that put it up.
 *
 * The bookkeeping the log cannot hold lives here too. How many snoozes have
 * been used today and whether a soft budget has already said its piece are
 * facts about this device's afternoon, not events worth keeping for ninety
 * days, so they are preferences.
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleScreenTime;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Calendar;
import java.util.List;

public class PurpleScreenTimeCover extends FrameLayout {

    /** Where the day's snoozes and the day's soft warnings are written down. */
    private static final String PREFS = "purple";

    /** How often the ledger is asked again while a chat sits open. */
    public static final long RECHECK_MS = 60 * 1000L;

    /**
     * What the ledger says about the chat in front of you.
     *
     * Three answers, and they are not the same shape as the budgets: several
     * budgets can cover one chat, and the strictest of them is what happens. A
     * hard one that is out of snoozes outranks one that still has some, and any
     * hard one outranks a soft one - a soft budget that has already said its
     * piece should not stop a cover from going up.
     */
    public static final class Verdict {

        /** Nothing is reached; the chat is as it always was. */
        public static final Verdict NONE = new Verdict(null, false, false);

        /** The budget that decided it, or null. */
        public final PurpleCore.Budget budget;

        /** Whether the chat goes behind the cover. */
        public final boolean cover;

        /** Whether one more snooze is left today. */
        public final boolean snoozable;

        Verdict(PurpleCore.Budget budget, boolean cover, boolean snoozable) {
            this.budget = budget;
            this.cover = cover;
            this.snoozable = snoozable;
        }

        /** A soft budget at its limit: a bulletin once, and nothing else. */
        public boolean soft() {
            return budget != null && !cover;
        }
    }

    /**
     * Asks the ledger what this chat is under, off the UI thread.
     *
     * Off it because the ledger is derived from the whole log like everything
     * else - which is the point: change a threshold and today's spend changes
     * with it, rather than tomorrow's. The answer comes back on the UI thread.
     */
    public static void check(int currentAccount, long dialogId,
            Utilities.Callback<Verdict> whenKnown) {
        if (!PurpleScreenTime.enabled()) {
            whenKnown.run(Verdict.NONE);
            return;
        }
        final PurpleCore.Loaded state = PurpleGate.state();
        final String preset = (state == null || state.normal) ? "" : state.preset;
        if (state != null && state.screenTimeBudgets <= 0) {
            // Nothing to be under. Checked before the parse rather than after,
            // because the common file has no budgets at all and reading ninety
            // days of log to discover that would be a minute of work for a "no".
            whenKnown.run(Verdict.NONE);
            return;
        }
        final long bare = PurpleGate.bareIdOf(currentAccount, dialogId);
        final int kind = PurpleGate.kindOf(currentAccount, dialogId);
        final long dayStart = startOfToday();
        final String zone = PurpleScreenTime.zoneId();
        Utilities.globalQueue.postRunnable(() -> {
            final List<PurpleCore.Budget> ledger = PurpleCore.screenTimeLedger(
                    PurpleGate.settingsBytes(), PurpleScreenTime.read(), dayStart, zone);
            final Verdict verdict = decide(ledger, bare, kind, preset);
            AndroidUtilities.runOnUIThread(() -> whenKnown.run(verdict));
        });
    }

    /**
     * The strictest thing the reached budgets say about this chat.
     *
     * "Snoozable" is the core's rule, finished here: the ledger says whether the
     * budget offers a snooze at all - {@code CoverAllowed(0, budget)}, which is
     * false for a soft one and for a hard one written {@code snoozes_per_day =
     * 0} - and the count of snoozes already taken today is this device's, since
     * nothing about it is in the log.
     */
    private static Verdict decide(List<PurpleCore.Budget> ledger, long dialogId,
            int kind, String preset) {
        Verdict best = Verdict.NONE;
        for (PurpleCore.Budget budget : ledger) {
            if (!budget.reached || !budget.covers(dialogId, kind, preset)) {
                continue;
            }
            if (budget.mode != PurpleCore.BUDGET_HARD) {
                if (best.budget == null) {
                    best = new Verdict(budget, false, false);
                }
                continue;
            }
            if (snoozedUntil(budget.index) > System.currentTimeMillis()) {
                // A snooze is running. It is not that the budget is unreached -
                // it is - only that this is the few minutes that were asked for
                // and granted, and they are still going.
                continue;
            }
            final boolean snoozable = budget.snoozable
                    && snoozesUsed(budget.index) < budget.snoozesPerDay;
            if (!best.cover || (best.snoozable && !snoozable)) {
                best = new Verdict(budget, true, snoozable);
            }
        }
        return best;
    }

    // ---- the day's bookkeeping ----------------------------------------------

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    /**
     * Today, as a number.
     *
     * The key every counter below is scoped by, so nothing has to be cleared at
     * midnight: yesterday's keys simply stop being asked about. They are a
     * handful of integers and they cost nothing to leave behind.
     */
    private static int today() {
        final Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
    }

    private static int snoozesUsed(int index) {
        return prefs().getInt("st_snoozes_" + index + "_" + today(), 0);
    }

    private static long snoozedUntil(int index) {
        return prefs().getLong("st_snooze_until_" + index + "_" + today(), 0);
    }

    private static void snooze(PurpleCore.Budget budget) {
        prefs().edit()
                .putInt("st_snoozes_" + budget.index + "_" + today(),
                        snoozesUsed(budget.index) + 1)
                .putLong("st_snooze_until_" + budget.index + "_" + today(),
                        System.currentTimeMillis() + budget.snoozeSeconds * 1000L)
                .apply();
    }

    /**
     * Whether a soft budget still has its one bulletin to give for this chat
     * today. Once per chat per day: a soft budget is a thing you wanted to know
     * about, and being told the same thing every time the chat opens is how a
     * thing you wanted to know about becomes a thing you stop reading.
     */
    public static boolean claimSoftWarning(long dialogId) {
        final String key = "st_soft_" + dialogId + "_" + today();
        if (prefs().getBoolean(key, false)) {
            return false;
        }
        prefs().edit().putBoolean(key, true).apply();
        return true;
    }

    /** How the bulletin and the cover name what stopped them. */
    public static CharSequence label(int currentAccount, PurpleCore.Budget budget) {
        if (budget == null) {
            return "";
        }
        switch (budget.targetKind) {
        case PurpleCore.BUDGET_CHAT:
            return getString(R.string.PurpleScreenTimeBudgetThisChat);
        case PurpleCore.BUDGET_KIND:
            return budget.target;
        case PurpleCore.BUDGET_PRESET:
            return budget.target;
        default:
            return getString(R.string.PurpleScreenTimeBudgetAll);
        }
    }

    private static long startOfToday() {
        final Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    // ---- the view ------------------------------------------------------------

    private final TextView title;
    private final TextView detail;
    private final TextView button;
    private final TextView note;

    private PurpleCore.Budget budget;
    private Runnable onSnoozed;

    public PurpleScreenTimeCover(Context context) {
        super(context);
        // Opaque, and it swallows every touch that reaches it - see
        // onTouchEvent. The action bar is not covered: it is added to the chat's
        // content view after this one, so it draws on top and takes its own
        // touches, which is what leaves the way out of the chat open.
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        final FrameLayout middle = new FrameLayout(context);
        addView(middle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        middle.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, Gravity.TOP, 32, 0, 32, 0));

        detail = new TextView(context);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        detail.setGravity(Gravity.CENTER);
        detail.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        middle.addView(detail, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, Gravity.TOP, 32, 34, 32, 0));

        button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(20), 0, dp(20), 0);
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(20),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_listSelector)));
        middle.addView(button, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 96, 0, 0));
        button.setOnClickListener(v -> {
            if (budget == null) {
                return;
            }
            snooze(budget);
            if (onSnoozed != null) {
                onSnoozed.run();
            }
        });

        note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setGravity(Gravity.CENTER);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        note.setText(getString(R.string.PurpleScreenTimeCoverNote));
        middle.addView(note, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, Gravity.TOP, 32, 152, 32, 0));
    }

    public void set(int currentAccount, Verdict verdict, Runnable onSnoozed) {
        this.budget = verdict.budget;
        this.onSnoozed = onSnoozed;
        if (verdict.budget == null) {
            return;
        }
        title.setText(formatString(R.string.PurpleScreenTimeCoverTitle,
                label(currentAccount, verdict.budget)));
        detail.setText(formatString(R.string.PurpleScreenTimeCoverDetail,
                PurpleScreenTimeActivity.formatSpan(verdict.budget.perDayMs)));
        if (verdict.snoozable) {
            button.setVisibility(VISIBLE);
            button.setText(formatString(R.string.PurpleScreenTimeCoverSnooze,
                    Math.max(1, verdict.budget.snoozeSeconds / 60)));
        } else {
            // No snooze offered, or the day's are gone. The cover says so by
            // having no button rather than by a greyed-out one that explains
            // itself: there is nothing here to press.
            button.setVisibility(GONE);
        }
    }

    /**
     * Everything stops here. Without this the message list underneath would
     * take the scrolls, which would be a cover you could read straight through.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }
}
