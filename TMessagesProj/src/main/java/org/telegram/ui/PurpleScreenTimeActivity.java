/*
 * This is the source code of Purple Telegram for Android.
 *
 * Settings -> Purple -> Screen time. Everything on this screen is drawn from
 * one report the core derives out of screentime.log; nothing here counts
 * anything, decides what "active" means, or knows what a week is. Change a
 * threshold in settings.toml and this screen redraws the same history
 * differently, because the log is raw events and the arithmetic is applied at
 * read time.
 *
 * The same fragment is the per-chat page: constructed with a dialog id it shows
 * that one chat's daily series and its hour-of-day profile. See logForChat()
 * for how one chat is cut out of a log that has every chat in it.
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.collection.LongSparseArray;
import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleScreenTime;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class PurpleScreenTimeActivity extends UniversalFragment
        implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_ENABLED = 1;
    private static final int ROW_HIDDEN = 2;
    private static final int ROW_EXPORT = 3;
    private static final int ROW_BUDGET_ADD = 4;

    /**
     * The heights the drawn rows take, in dp.
     *
     * Handed to the adapter rather than left to wrap: a custom row is added
     * with MATCH_PARENT height, and a view that does not insist on a size of
     * its own would take the whole list. The chart views insist anyway; saying
     * it twice keeps the row and the drawing the same height by construction.
     */
    private static final int HEIGHT_CHIPS = 50;
    private static final int HEIGHT_HEADLINE = 92;
    private static final int HEIGHT_CHART = 160;
    private static final int HEIGHT_HEAT = 132;

    /** Where the ranked chats and the budgets start numbering. Far apart, and
     *  far above the fixed rows, so a list of any length collides with nothing. */
    private static final int ROW_CHAT_FIRST = 1000;
    private static final int ROW_BUDGET_FIRST = 2000;

    private static final int PERIOD_TODAY = 0;
    private static final int PERIOD_WEEK = 1;
    private static final int PERIOD_MONTH = 2;
    private static final int PERIOD_CUSTOM = 3;

    /** Everything, as against one kind. Not a kind value: -1 is "no filter". */
    private static final int KIND_ANY = -1;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * The digest line on the settings screen, and when it was worked out.
     *
     * Static because the screen that shows it is not this one and must not pay
     * for a log parse while it draws itself. Recomputed lazily and at most
     * every few minutes: it is a sentence about last week, and last week does
     * not move quickly.
     */
    private static volatile String digestLine;
    private static volatile long digestAtMs;
    private static volatile boolean digestRunning;

    private static final long DIGEST_EVERY_MS = 5 * 60 * 1000L;

    /** Zero for the whole screen; a bare id for one chat's own page. */
    private final long chatId;

    private int period = PERIOD_WEEK;
    private long customFromMs;
    private long customToMs;

    /** The chips. {@code activeOnly} draws the active share in place of the total. */
    private boolean activeOnly;
    private int kindFilter = KIND_ANY;
    private String presetFilter;

    private PurpleCore.Report report;
    private List<PurpleCore.Budget> ledger = new ArrayList<>();
    private boolean loading;

    /** The custom views, held rather than rebuilt: they carry their own data. */
    private ChipRow periodChips;
    private ChipRow filterChips;
    private HeadlineView headline;
    private BarChartView chart;
    private BarChartView readingChart;
    private HeatMapView heatMap;

    /** One row per chat, kept across rebuilds. See fillItems. */
    private final LongSparseArray<ChatRow> chatRows = new LongSparseArray<>();

    public PurpleScreenTimeActivity() {
        this(0);
    }

    public PurpleScreenTimeActivity(long chatId) {
        this.chatId = chatId;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogFiltersUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogFiltersUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        // Every reload of the gate ends here, and the switch on this screen is
        // one of the things that causes one. Re-reading is what makes a refused
        // write leave the row exactly where the file is, with no undo.
        if (id == NotificationCenter.dialogFiltersUpdated) {
            refresh();
        }
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        refresh();
        return view;
    }

    @Override
    protected CharSequence getTitle() {
        if (chatId == 0) {
            return getString(R.string.PurpleScreenTimeTitle);
        }
        final CharSequence name = chatName(chatId);
        return TextUtils.isEmpty(name) ? getString(R.string.PurpleScreenTimeTitle) : name;
    }

    // ---- the report ----------------------------------------------------------

    /**
     * Re-derives everything, off the UI thread.
     *
     * The log can be ninety days long and the derivation walks all of it, so it
     * never happens on the thread that is drawing. Two calls in flight are
     * harmless - the later one wins - because nothing here is incremental: the
     * report is the whole answer or it is not there.
     */
    private void refresh() {
        if (!PurpleScreenTime.enabled()) {
            report = null;
            ledger = new ArrayList<>();
            if (listView != null) {
                listView.adapter.update(true);
            }
            return;
        }
        loading = (report == null);
        final long from = fromMs();
        final long to = toMs();
        final int unit = bucketUnit();
        final String zone = PurpleScreenTime.zoneId();
        final long forChat = chatId;
        Utilities.globalQueue.postRunnable(() -> {
            final byte[] settings = PurpleGate.settingsBytes();
            byte[] log = PurpleScreenTime.read();
            if (forChat != 0) {
                log = PurpleScreenTime.logForChat(log, forChat);
            }
            final PurpleCore.Report next =
                    PurpleCore.screenTimeReport(settings, log, from, to, unit, zone);
            // The ledger is always today's, whatever period the bars are in: a
            // budget is a day's allowance and there is no such thing as this
            // month's version of it.
            final List<PurpleCore.Budget> budgets = (forChat != 0)
                    ? new ArrayList<>()
                    : PurpleCore.screenTimeLedger(
                            settings, PurpleScreenTime.read(), startOfToday(), zone);
            AndroidUtilities.runOnUIThread(() -> {
                report = next;
                ledger = budgets;
                loading = false;
                if (listView != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    /** The scope the chips have narrowed to: the whole range, or one preset. */
    private PurpleCore.Scope scope() {
        if (report == null) {
            return null;
        }
        if (presetFilter == null) {
            return report;
        }
        for (PurpleCore.Scope entry : report.presets) {
            if (entry.preset.equalsIgnoreCase(presetFilter)) {
                return entry;
            }
        }
        // The chip names a preset with nothing in the new period. Shown as
        // itself with nothing in it rather than quietly falling back to the
        // whole range, which would look like the filter had been ignored.
        return null;
    }

    private long fromMs() {
        final Calendar calendar = Calendar.getInstance();
        switch (period) {
        case PERIOD_TODAY:
            return startOfToday();
        case PERIOD_MONTH:
            calendar.add(Calendar.DAY_OF_YEAR, -29);
            return startOfDay(calendar);
        case PERIOD_CUSTOM:
            return customFromMs > 0 ? customFromMs : startOfToday();
        default:
            calendar.add(Calendar.DAY_OF_YEAR, -6);
            return startOfDay(calendar);
        }
    }

    private long toMs() {
        if (period == PERIOD_CUSTOM && customToMs > 0) {
            return customToMs;
        }
        // The end of today rather than now: a half-open window ending at this
        // moment would make the last bar shrink as you looked at it.
        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        return startOfDay(calendar);
    }

    private int bucketUnit() {
        return (period == PERIOD_TODAY)
                ? PurpleCore.BUCKET_HOUR_OF_DAY
                : PurpleCore.BUCKET_DAY;
    }

    private static long startOfToday() {
        return startOfDay(Calendar.getInstance());
    }

    private static long startOfDay(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    // ---- the rows ------------------------------------------------------------

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final boolean on = PurpleScreenTime.enabled();

        if (chatId == 0) {
            final UItem enabled = UItem.asCheck(ROW_ENABLED, getString(R.string.PurpleScreenTimeRow));
            enabled.checked = on;
            items.add(enabled);
            items.add(UItem.asShadow(getString(R.string.PurpleScreenTimeInfo)));
            if (!on) {
                return;
            }
        } else if (!on) {
            items.add(UItem.asShadow(getString(R.string.PurpleScreenTimeInfo)));
            return;
        }

        if (periodChips == null) {
            periodChips = new ChipRow(context);
        }
        periodChips.set(periodLabels(), period, value -> {
            if (value == PERIOD_CUSTOM) {
                pickRange();
            } else {
                period = value;
                refresh();
            }
        });
        items.add(UItem.asCustom(periodChips, HEIGHT_CHIPS));

        final PurpleCore.Scope scope = scope();
        if (report == null) {
            items.add(UItem.asShadow(getString(
                    loading ? R.string.PurpleScreenTimeLoading : R.string.PurpleScreenTimeNoData)));
            return;
        }

        if (headline == null) {
            headline = new HeadlineView(context);
        }
        headline.set(scope, report, activeOnly);
        items.add(UItem.asCustom(headline, HEIGHT_HEADLINE));

        if (chart == null) {
            chart = new BarChartView(context);
        }
        chart.set(scope == null ? null : scope.buckets, kindFilter, activeOnly);
        items.add(UItem.asCustom(chart, HEIGHT_CHART));

        if (filterChips == null) {
            filterChips = new ChipRow(context);
        }
        filterChips.setToggles(filterLabels(), filterStates(), this::onFilter);
        items.add(UItem.asCustomShadow(filterChips, HEIGHT_CHIPS));

        if (chatId == 0) {
            items.add(UItem.asHeader(getString(R.string.PurpleScreenTimeChats)));
            final List<PurpleCore.ChatSpan> chats = ranked(scope);
            if (chats.isEmpty()) {
                items.add(UItem.asShadow(getString(R.string.PurpleScreenTimeNoChats)));
            } else {
                final long top = chats.get(0).totalMs;
                for (int i = 0; i < chats.size(); ++i) {
                    final PurpleCore.ChatSpan span = chats.get(i);
                    // Kept per chat rather than made per rebuild: a UItem is
                    // the same item as another when it holds the same view, so
                    // reusing them is what stops every filter tap from
                    // animating the whole list out and back in again.
                    ChatRow row = chatRows.get(span.dialogId);
                    if (row == null) {
                        row = new ChatRow(context);
                        chatRows.put(span.dialogId, row);
                    }
                    row.set(currentAccount, span, top, activeOnly);
                    items.add(UItem.asCustom(ROW_CHAT_FIRST + i, row));
                }
                items.add(UItem.asShadow(getString(R.string.PurpleScreenTimeChatsInfo)));
            }
        }

        items.add(UItem.asHeader(getString(R.string.PurpleScreenTimeReading)));
        if (readingChart == null) {
            readingChart = new BarChartView(context);
        }
        readingChart.set(report.reading, kindFilter, activeOnly);
        items.add(UItem.asCustom(readingChart, HEIGHT_CHART));
        items.add(UItem.asShadow(getString(R.string.PurpleScreenTimeReadingInfo)));

        if (chatId == 0) {
            items.add(UItem.asButton(ROW_HIDDEN, getString(R.string.PurpleScreenTimeHidden),
                    formatSpan(report.hiddenMs)));
            items.add(UItem.asShadow(getString(R.string.PurpleScreenTimeHiddenInfo)));

            // Only for a long period. Seven days of hour-by-weekday is the
            // same seven bars the chart above already drew, one per row, and a
            // single day of it is one row with something in it.
            if (period == PERIOD_MONTH || period == PERIOD_CUSTOM) {
                items.add(UItem.asHeader(getString(R.string.PurpleScreenTimeHeat)));
                if (heatMap == null) {
                    heatMap = new HeatMapView(context);
                }
                heatMap.set(activeOnly ? report.heatActiveMs : report.heatTotalMs);
                items.add(UItem.asCustom(heatMap, HEIGHT_HEAT));
                items.add(UItem.asShadow(getString(R.string.PurpleScreenTimeHeatInfo)));
            }

            fillBudgets(items);

            items.add(UItem.asButton(ROW_EXPORT, getString(R.string.PurpleScreenTimeExport)));
            items.add(UItem.asShadow(formatString(R.string.PurpleScreenTimeFileInfo,
                    PurpleScreenTime.file().getAbsolutePath())));
        }
    }

    /**
     * The budgets, listed with what each has spent today, and the row that adds
     * one.
     *
     * Every row is an editor: tapping a budget opens the same dialog the Add
     * row opens, prefilled, with a Delete. The three writes go through the
     * core's budget splices, so a block keeps its comments and its spacing and
     * only the keys the dialog actually changed are rewritten.
     */
    private void fillBudgets(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.PurpleScreenTimeBudgets)));
        for (int i = 0; i < ledger.size(); ++i) {
            final PurpleCore.Budget budget = ledger.get(i);
            items.add(UItem.asButton(ROW_BUDGET_FIRST + i, budgetName(budget),
                    budgetValue(budget)));
        }
        items.add(UItem.asButton(ROW_BUDGET_ADD,
                getString(R.string.PurpleScreenTimeBudgetAdd)));
        items.add(UItem.asShadow(getString(ledger.isEmpty()
                ? R.string.PurpleScreenTimeBudgetsEmpty
                : R.string.PurpleScreenTimeBudgetsInfo)));
    }

    private CharSequence budgetName(PurpleCore.Budget budget) {
        return targetLabel(PurpleGate.state(), budget.target);
    }

    /**
     * A target as a person reads it - the same answer for a budget in the file
     * and for one being chosen in the dialog, which is why it works off the
     * target string rather than off the fields the parser made of it.
     */
    private CharSequence targetLabel(PurpleCore.Loaded state, String target) {
        final String text = (target == null) ? "" : target.trim();
        if (text.length() == 0 || "all".equalsIgnoreCase(text)) {
            return getString(R.string.PurpleScreenTimeBudgetAll);
        }
        final int colon = text.indexOf(':');
        if (colon <= 0) {
            return text;
        }
        final String prefix = text.substring(0, colon).trim().toLowerCase();
        final String rest = text.substring(colon + 1).trim();
        if ("chat".equals(prefix)) {
            final long id = parseId(rest);
            return (id == 0) ? text : chatName(id);
        }
        if ("kind".equals(prefix)) {
            for (int kind = 0; kind < PurpleCore.SCREEN_KIND_COUNT; ++kind) {
                if (kindKey(kind).equalsIgnoreCase(rest)) {
                    return kindLabel(kind);
                }
            }
            return text;
        }
        if ("preset".equals(prefix)) {
            return PurpleScheduleActivity.presetLabel(state, rest);
        }
        return text;
    }

    /** How the file spells a kind inside a budget target. */
    private static String kindKey(int kind) {
        switch (kind) {
        case PurpleCore.SCREEN_KIND_PRIVATE:
            return "private";
        case PurpleCore.SCREEN_KIND_GROUP:
            return "groups";
        case PurpleCore.SCREEN_KIND_CHANNEL:
            return "channels";
        case PurpleCore.SCREEN_KIND_BOT:
            return "bots";
        default:
            return "elsewhere";
        }
    }

    /**
     * A typed or written chat id as this codebase spells one: bare, positive.
     * There is no {@code -100} channel prefix here - see
     * {@link PurpleGate#bareIdOf} - so a signed id pasted out of somewhere else
     * is taken for the chat it names rather than refused.
     */
    private static long parseId(String text) {
        try {
            return Math.abs(Long.parseLong(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private CharSequence budgetValue(PurpleCore.Budget budget) {
        final String spent = formatString(R.string.PurpleScreenTimeBudgetSpent,
                formatSpan(budget.spentMs), formatSpan(budget.perDayMs));
        final String mode = getString(budget.mode == PurpleCore.BUDGET_HARD
                ? R.string.PurpleScreenTimeBudgetHard
                : R.string.PurpleScreenTimeBudgetSoft);
        return budget.reached
                ? (spent + " · " + mode + " · "
                        + getString(R.string.PurpleScreenTimeBudgetReached))
                : (spent + " · " + mode);
    }

    // ---- the budget editor ---------------------------------------------------

    /** What a new budget starts at: an hour a day, soft, nothing else said. */
    private static final int DEFAULT_PER_DAY_SECONDS = 60 * 60;

    /** The core's own defaults for the two snooze keys. */
    private static final int DEFAULT_SNOOZE_SECONDS = 5 * 60;
    private static final int DEFAULT_SNOOZES_PER_DAY = 2;

    /** How many of the report's chats the target picker offers by name. */
    private static final int CHATS_OFFERED = 12;

    /**
     * Opens the editor over one budget, or over nothing for a new one.
     *
     * The target this dialog was opened on is sent with the write, and the core
     * refuses when the budget at that index no longer says it - so a file that
     * moved while the dialog stood open is refused rather than quietly
     * overwritten. A budget has no other identity: the target is what it is
     * about, and everything else in the block is what this dialog is for.
     */
    private void editBudget(PurpleCore.Budget existing) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final PurpleCore.Loaded state = PurpleGate.state();

        // "all" for a new one: the budget list is drawn on the whole screen and
        // not on a chat's own page, so there is no chat this dialog was opened
        // "about" and nothing better to start from.
        final String[] target = { (existing != null) ? existing.target : "all" };
        final int[] perDay = { (existing != null)
                ? (int) (existing.perDayMs / 1000)
                : DEFAULT_PER_DAY_SECONDS };
        final String[] mode = { (existing != null && existing.mode == PurpleCore.BUDGET_HARD)
                ? "hard"
                : "soft" };
        // What the fields opened on, so a box somebody emptied falls back to
        // the number that was in it rather than to the core's default.
        final int openedSnooze =
                ((existing != null) ? existing.snoozeSeconds : DEFAULT_SNOOZE_SECONDS) / 60;
        final int openedSnoozes =
                (existing != null) ? existing.snoozesPerDay : DEFAULT_SNOOZES_PER_DAY;

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final String targetTitle = getString(R.string.PurpleScreenTimeBudgetTarget);
        final TextView targetRow = PurpleScheduleActivity.pickerRow(
                context, layout, targetTitle, targetLabel(state, target[0]));
        targetRow.setOnClickListener(v -> pickTarget(context, state, target[0], chosen -> {
            target[0] = chosen;
            targetRow.setText(targetTitle + "   " + targetLabel(state, chosen));
        }));

        final String perDayTitle = getString(R.string.PurpleScreenTimeBudgetPerDay);
        final TextView perDayRow = PurpleScheduleActivity.pickerRow(
                context, layout, perDayTitle, formatSpan(perDay[0] * 1000L));
        perDayRow.setOnClickListener(v -> pickDuration(context, perDay[0], seconds -> {
            perDay[0] = seconds;
            perDayRow.setText(perDayTitle + "   " + formatSpan(seconds * 1000L));
        }));

        // The two snooze numbers live in their own box so the mode picker can
        // hide them in one call: a soft budget never puts a cover up, so asking
        // how long its snooze lasts would be asking about nothing.
        final LinearLayout snoozeBox = new LinearLayout(context);
        snoozeBox.setOrientation(LinearLayout.VERTICAL);

        final String modeTitle = getString(R.string.PurpleScreenTimeBudgetMode);
        final TextView modeRow = PurpleScheduleActivity.pickerRow(
                context, layout, modeTitle, modeLabel(mode[0]));
        modeRow.setOnClickListener(v -> pickMode(context, mode[0], chosen -> {
            mode[0] = chosen;
            modeRow.setText(modeTitle + "   " + modeLabel(chosen));
            snoozeBox.setVisibility(isHard(chosen) ? View.VISIBLE : View.GONE);
        }));

        layout.addView(snoozeBox,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final EditTextBoldCursor snooze = numberRow(context, snoozeBox,
                getString(R.string.PurpleScreenTimeBudgetSnooze), openedSnooze);
        final EditTextBoldCursor snoozes = numberRow(context, snoozeBox,
                getString(R.string.PurpleScreenTimeBudgetSnoozes), openedSnoozes);
        snoozeBox.setVisibility(isHard(mode[0]) ? View.VISIBLE : View.GONE);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(existing == null
                ? R.string.PurpleScreenTimeBudgetNew
                : R.string.PurpleScreenTimeBudgetEdit));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            // Read whatever the fields hold even for a soft budget. They are
            // the numbers the file already had, and blanking them on the way
            // through would lose a hard budget's snooze settings the moment it
            // was turned soft. The core writes neither key when it is the
            // default anyway.
            final int snoozeSeconds = number(snooze, openedSnooze) * 60;
            final int perDaySnoozes = number(snoozes, openedSnoozes);
            afterWrite(existing == null
                    ? PurpleWriter.appendBudget(target[0], perDay[0], mode[0],
                            snoozeSeconds, perDaySnoozes, "budget added")
                    : PurpleWriter.setBudget(existing.sourceIndex, existing.target,
                            target[0], perDay[0], mode[0], snoozeSeconds, perDaySnoozes,
                            "budget edited"));
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        if (existing != null) {
            builder.setNeutralButton(getString(R.string.Delete), (dialog, which) ->
                    afterWrite(PurpleWriter.removeBudget(
                            existing.sourceIndex, existing.target, "budget removed")));
        }
        showDialog(builder.create());
    }

    /**
     * A budget write's answer.
     *
     * The re-read is not optional and is not the gate's reload doing it: the
     * ledger on this screen comes out of the log and the file together, and a
     * refusal reloads nothing at all - so without this the rows would go on
     * showing the budgets that were there before the edit.
     */
    private void afterWrite(String error) {
        if (error != null && BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this)
                    .createErrorBulletin(formatString(R.string.PurpleWriteFailed, error))
                    .show();
        }
        refresh();
    }

    /** "soft" and "hard" are what the file says; the core reads nothing else. */
    private static boolean isHard(String mode) {
        return "hard".equalsIgnoreCase(mode);
    }

    private static CharSequence modeLabel(String mode) {
        return getString(isHard(mode)
                ? R.string.PurpleScreenTimeBudgetHardName
                : R.string.PurpleScreenTimeBudgetSoftName);
    }

    private interface PickedSeconds {
        void run(int seconds);
    }

    /**
     * What a budget counts, as one radio list.
     *
     * One list rather than a menu of four sub-pickers because the answer is one
     * string with one meaning, and a chooser that asked for a category and then
     * a value would let a half-made answer sit on screen.
     *
     * There is no chat picker in this fork to reuse, and opening the chat list
     * as one would mean pushing a fragment over an open dialog and losing
     * everything else in it. So the chats offered are the ones the report is
     * already holding - which are the chats worth a budget, being the ones the
     * time went to - and the field underneath takes any other id, the same
     * radios-plus-a-field shape the schedule's device picker uses.
     */
    private void pickTarget(
            Context context, PurpleCore.Loaded state, String current,
            PurpleScheduleActivity.Chosen picked) {
        // The chats worked out first, because whether the one this budget is
        // already about is among them decides whether it is a radio or the
        // thing in the field.
        final List<Long> offered = new ArrayList<>();
        final List<PurpleCore.ChatSpan> chats =
                (report == null) ? new ArrayList<>() : report.chats;
        for (int a = 0; a < chats.size() && offered.size() < CHATS_OFFERED; ++a) {
            final long id = chats.get(a).dialogId;
            if (id != 0) {
                // "Elsewhere" is not a chat; it is the kind of the same name.
                offered.add(id);
            }
        }
        final long chat = current.toLowerCase().startsWith("chat:")
                ? parseId(current.substring(5))
                : 0;
        // A chat no radio offers is typed instead, and typing IS choosing it -
        // so no radio starts on, rather than one the field would disagree with.
        final boolean listed = (chat != 0) && offered.contains(chat);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<RadioColorCell> radios = new ArrayList<>();
        final String[] chosen = { (chat != 0 && !listed) ? "" : current };

        PurpleScheduleActivity.addRadio(context, layout, radios, chosen,
                "all", getString(R.string.PurpleScreenTimeBudgetAll));

        section(context, layout, getString(R.string.PurpleScreenTimeBudgetKinds));
        for (int kind = 0; kind < PurpleCore.SCREEN_KIND_COUNT; ++kind) {
            PurpleScheduleActivity.addRadio(context, layout, radios, chosen,
                    "kind:" + kindKey(kind), kindLabel(kind));
        }

        section(context, layout, getString(R.string.PurpleScreenTimeBudgetPresets));
        PurpleScheduleActivity.addRadio(context, layout, radios, chosen,
                "preset:" + PurpleScheduleActivity.NORMAL_PRESET,
                getString(R.string.PurplePresetNormal));
        if (state != null) {
            for (int a = 0, n = state.presets.size(); a < n; ++a) {
                final PurpleCore.PresetInfo info = state.presets.get(a);
                PurpleScheduleActivity.addRadio(context, layout, radios, chosen,
                        "preset:" + info.name,
                        TextUtils.isEmpty(info.title) ? info.name : info.title);
            }
        }

        if (!offered.isEmpty()) {
            section(context, layout, getString(R.string.PurpleScreenTimeChats));
            for (int a = 0; a < offered.size(); ++a) {
                final long id = offered.get(a);
                PurpleScheduleActivity.addRadio(context, layout, radios, chosen,
                        "chat:" + id, chatName(id));
            }
        }

        final EditTextBoldCursor other = new EditTextBoldCursor(context);
        other.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        other.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        other.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        other.setBackgroundDrawable(null);
        other.setSingleLine(true);
        other.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        other.setHint(getString(R.string.PurpleScreenTimeBudgetChatId));
        other.setPadding(dp(20), 0, dp(20), 0);
        if (chat != 0 && !listed) {
            other.setText(String.valueOf(chat));
        }
        layout.addView(other, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PurpleScreenTimeBudgetTarget));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            final long id = parseId(other.getText().toString());
            if (id != 0 && chosen[0].length() == 0) {
                picked.run("chat:" + id);
            } else if (chosen[0].length() > 0) {
                picked.run(chosen[0]);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void pickMode(
            Context context, String current, PurpleScheduleActivity.Chosen picked) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<RadioColorCell> radios = new ArrayList<>();
        final String[] chosen = { isHard(current) ? "hard" : "soft" };
        PurpleScheduleActivity.addRadio(context, layout, radios, chosen, "soft",
                getString(R.string.PurpleScreenTimeBudgetSoftRow));
        PurpleScheduleActivity.addRadio(context, layout, radios, chosen, "hard",
                getString(R.string.PurpleScreenTimeBudgetHardRow));
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PurpleScreenTimeBudgetMode));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save),
                (dialog, which) -> picked.run(chosen[0]));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /**
     * A day's allowance, picked on a 24-hour clock read as a length.
     *
     * The clock is the picker this app already has for hours and minutes, and
     * 24 hours is not a limit worth a second widget: an allowance of a whole
     * day is a budget that can never be reached.
     */
    private void pickDuration(Context context, int seconds, PickedSeconds picked) {
        final int start = Math.max(0, seconds);
        new TimePickerDialog(
                context,
                (view, hour, minute) -> picked.run(hour * 3600 + minute * 60),
                (start / 3600) % 24,
                (start / 60) % 60,
                true).show();
    }

    /**
     * A "Label  [number]" row inside a dialog.
     *
     * A label beside the field rather than in its hint, because the hint goes
     * away the moment there is a value in the box and these two rows are always
     * prefilled.
     */
    private static EditTextBoldCursor numberRow(
            Context context, LinearLayout layout, CharSequence label, int value) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), 0, dp(20), 0);

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)
                | Gravity.CENTER_VERTICAL);
        title.setText(label);
        row.addView(title, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setBackgroundDrawable(null);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)
                | Gravity.CENTER_VERTICAL);
        field.setText(String.valueOf(Math.max(0, value)));
        row.addView(field, LayoutHelper.createLinear(64, LayoutHelper.MATCH_PARENT));

        layout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        return field;
    }

    /** A quiet heading between two runs of radios. */
    private static void section(Context context, LinearLayout layout, CharSequence text) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        view.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)
                | Gravity.BOTTOM);
        view.setPadding(dp(24), 0, dp(24), dp(4));
        view.setText(text);
        layout.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36));
    }

    /** What a number field holds, or {@code fallback} when it holds nothing. */
    private static int number(EditTextBoldCursor field, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(field.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The ranked chats the chips have left, longest first. */
    private List<PurpleCore.ChatSpan> ranked(PurpleCore.Scope scope) {
        final List<PurpleCore.ChatSpan> result = new ArrayList<>();
        if (scope == null) {
            return result;
        }
        for (PurpleCore.ChatSpan span : scope.chats) {
            if (kindFilter != KIND_ANY && span.kind != kindFilter) {
                continue;
            }
            if (span.dialogId == 0) {
                // "Elsewhere" is not a chat and has no page to open; it is on
                // the chart as its own colour and in the total, which is where
                // it belongs.
                continue;
            }
            if ((activeOnly ? span.activeMs : span.totalMs) <= 0) {
                continue;
            }
            result.add(span);
        }
        return result;
    }

    // ---- the chips -----------------------------------------------------------

    private List<CharSequence> periodLabels() {
        final List<CharSequence> labels = new ArrayList<>();
        labels.add(getString(R.string.PurpleScreenTimeToday));
        labels.add(getString(R.string.PurpleScreenTimeWeek));
        labels.add(getString(R.string.PurpleScreenTimeMonth));
        labels.add(period == PERIOD_CUSTOM && customFromMs > 0
                ? rangeLabel()
                : getString(R.string.PurpleScreenTimeCustom));
        return labels;
    }

    private CharSequence rangeLabel() {
        return LocaleController.getInstance().getFormatterDayMonth().format(customFromMs)
                + " – "
                + LocaleController.getInstance().getFormatterDayMonth().format(customToMs - 1);
    }

    private List<CharSequence> filterLabels() {
        final List<CharSequence> labels = new ArrayList<>();
        labels.add(getString(R.string.PurpleScreenTimeActiveOnly));
        for (int kind = 0; kind < PurpleCore.SCREEN_KIND_COUNT; ++kind) {
            labels.add(kindLabel(kind));
        }
        if (report != null) {
            for (PurpleCore.Scope entry : report.presets) {
                labels.add(TextUtils.isEmpty(entry.preset)
                        ? getString(R.string.PurplePresetNormal)
                        : entry.preset);
            }
        }
        return labels;
    }

    private boolean[] filterStates() {
        final List<CharSequence> labels = filterLabels();
        final boolean[] states = new boolean[labels.size()];
        states[0] = activeOnly;
        for (int kind = 0; kind < PurpleCore.SCREEN_KIND_COUNT; ++kind) {
            states[1 + kind] = (kindFilter == kind);
        }
        if (report != null) {
            for (int i = 0; i < report.presets.size(); ++i) {
                states[1 + PurpleCore.SCREEN_KIND_COUNT + i] =
                        samePreset(report.presets.get(i).preset, presetFilter);
            }
        }
        return states;
    }

    /**
     * Whether a preset chip names the one currently filtering.
     *
     * Null is "no filter" and is not a preset name, so it never matches - not
     * even the empty name, which IS a preset: it is how the log spells Normal.
     */
    private static boolean samePreset(String name, String filter) {
        return filter != null && name.equalsIgnoreCase(filter);
    }

    /**
     * One chip pressed. The kind chips and the preset chips are each one choice
     * at a time - two kinds at once would need a second word for what the bars
     * then mean - and pressing the chip that is already on turns it off, which
     * is how a filter is cleared without a "clear" chip of its own.
     */
    private void onFilter(int index) {
        if (index == 0) {
            activeOnly = !activeOnly;
        } else if (index <= PurpleCore.SCREEN_KIND_COUNT) {
            final int kind = index - 1;
            kindFilter = (kindFilter == kind) ? KIND_ANY : kind;
        } else if (report != null) {
            final int at = index - 1 - PurpleCore.SCREEN_KIND_COUNT;
            if (at < report.presets.size()) {
                final String name = report.presets.get(at).preset;
                presetFilter = samePreset(name, presetFilter) ? null : name;
            }
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    /**
     * The custom range: two date pickers, from and then to.
     *
     * The second is only shown once the first has an answer, and the window is
     * closed at the END of the day picked - a range of one day that ended at
     * midnight the morning of it would always be empty.
     */
    private void pickRange() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final Calendar start = Calendar.getInstance();
        new DatePickerDialog(activity, (first, year, month, day) -> {
            final Calendar chosen = Calendar.getInstance();
            chosen.set(year, month, day);
            final long from = startOfDay(chosen);
            final Calendar until = Calendar.getInstance();
            until.setTimeInMillis(from);
            new DatePickerDialog(activity, (second, y2, m2, d2) -> {
                final Calendar end = Calendar.getInstance();
                end.set(y2, m2, d2);
                end.add(Calendar.DAY_OF_YEAR, 1);
                final long to = startOfDay(end);
                if (to <= from) {
                    return;
                }
                customFromMs = from;
                customToMs = to;
                period = PERIOD_CUSTOM;
                refresh();
            }, until.get(Calendar.YEAR), until.get(Calendar.MONTH),
                    until.get(Calendar.DAY_OF_MONTH)).show();
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH),
                start.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ---- taps ----------------------------------------------------------------

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ROW_ENABLED) {
            final String error = PurpleWriter.setTableBool(
                    "screen_time", "enabled_p", !item.checked, "screen time switch");
            if (error != null && BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this)
                        .createErrorBulletin(formatString(R.string.PurpleWriteFailed, error))
                        .show();
            }
            // The write reloads the gate, which comes back through
            // didReceivedNotification and refreshes this screen. Nothing to do
            // here but wait for it, which is what keeps a refused write from
            // needing an undo.
            return;
        }
        if (item.id == ROW_EXPORT) {
            exportCsv();
            return;
        }
        if (item.id == ROW_BUDGET_ADD) {
            editBudget(null);
            return;
        }
        if (item.id >= ROW_BUDGET_FIRST) {
            final int at = item.id - ROW_BUDGET_FIRST;
            if (at >= 0 && at < ledger.size()) {
                editBudget(ledger.get(at));
            }
            return;
        }
        if (item.id >= ROW_CHAT_FIRST) {
            final List<PurpleCore.ChatSpan> chats = ranked(scope());
            final int at = item.id - ROW_CHAT_FIRST;
            if (at >= 0 && at < chats.size()) {
                presentFragment(new PurpleScreenTimeActivity(chats.get(at).dialogId));
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // ---- CSV -----------------------------------------------------------------

    /**
     * The report as a CSV, handed to the share sheet.
     *
     * Two sections in one file - the bars and then the chats - because they are
     * the two tables this screen actually shows and a spreadsheet reads both
     * fine. Written to the app's own cache directory and shared through the
     * FileProvider the settings screen already uses; nothing is written where
     * another app could read it without being handed the URI.
     */
    private void exportCsv() {
        final Activity activity = getParentActivity();
        final PurpleCore.Scope scope = scope();
        if (activity == null || scope == null) {
            return;
        }
        try {
            final StringBuilder csv = new StringBuilder();
            csv.append("bucket,total_seconds,active_seconds\n");
            for (PurpleCore.Bucket bucket : scope.buckets) {
                csv.append(quote(bucket.label)).append(',')
                        .append(bucket.totalMs / 1000).append(',')
                        .append(bucket.activeMs / 1000).append('\n');
            }
            csv.append("\nchat_id,chat_name,kind,total_seconds,active_seconds\n");
            for (PurpleCore.ChatSpan span : scope.chats) {
                csv.append(span.dialogId).append(',')
                        .append(quote(chatName(span.dialogId).toString())).append(',')
                        .append(quote(kindLabel(span.kind).toString())).append(',')
                        .append(span.totalMs / 1000).append(',')
                        .append(span.activeMs / 1000).append('\n');
            }

            final File dir = new File(ApplicationLoader.getFilesDirFixed(), "cache");
            dir.mkdirs();
            final File target = new File(dir, "purple-screen-time.csv");
            final OutputStream out = new FileOutputStream(target);
            try {
                out.write(csv.toString().getBytes(UTF_8));
            } finally {
                out.close();
            }

            final Uri uri = (Build.VERSION.SDK_INT >= 24)
                    ? FileProvider.getUriForFile(
                            activity, ApplicationLoader.getApplicationId() + ".provider", target)
                    : Uri.fromFile(target);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            if (Build.VERSION.SDK_INT >= 24) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            activity.startActivity(Intent.createChooser(
                    intent, getString(R.string.PurpleScreenTimeExport)));
        } catch (Exception e) {
            FileLog.e(e);
            if (BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this)
                        .createErrorBulletin(getString(R.string.PurpleScreenTimeExportFailed))
                        .show();
            }
        }
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    // ---- the digest ----------------------------------------------------------

    /**
     * The line under the Screen time row on the settings screen.
     *
     * "Off" while the switch is off, and otherwise last week in one sentence.
     * Answered from a cache and computed in the background: the settings screen
     * draws before the log has been read, and it is not going to wait.
     */
    public static CharSequence digest(Runnable whenReady) {
        if (!PurpleScreenTime.enabled()) {
            return getString(R.string.PurpleScreenTimeOff);
        }
        final String cached = digestLine;
        final long now = System.currentTimeMillis();
        if (cached == null || now - digestAtMs > DIGEST_EVERY_MS) {
            computeDigest(whenReady);
        }
        return (cached == null) ? getString(R.string.PurpleScreenTimeLoading) : cached;
    }

    private static void computeDigest(Runnable whenReady) {
        if (digestRunning) {
            return;
        }
        digestRunning = true;
        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        final long to = startOfDay(calendar);
        calendar.add(Calendar.DAY_OF_YEAR, -7);
        final long from = startOfDay(calendar);
        final String zone = PurpleScreenTime.zoneId();
        Utilities.globalQueue.postRunnable(() -> {
            PurpleCore.Report week = null;
            try {
                week = PurpleCore.screenTimeReport(
                        PurpleGate.settingsBytes(), PurpleScreenTime.read(),
                        from, to, PurpleCore.BUCKET_DAY, zone);
            } catch (Exception e) {
                FileLog.e(e);
            }
            // The sentence is assembled on the UI thread, not here: naming the
            // top chat means asking MessagesController who an id is, and its
            // maps belong to that thread. The derivation - the part that is
            // actually slow - has already happened by now.
            final PurpleCore.Report settled = week;
            AndroidUtilities.runOnUIThread(() -> {
                digestLine = (settled == null) ? null : formatString(
                        R.string.PurpleScreenTimeDigest,
                        formatSpan(settled.totalMs),
                        percent(settled.activeMs, settled.totalMs),
                        topChatName(settled));
                digestAtMs = System.currentTimeMillis();
                digestRunning = false;
                // The caller's own redraw, not a notification: the settings
                // screen is the one thing that wants this line, and waking
                // every observer of the chat list to deliver a subtitle would
                // be a strange way to say it.
                if (digestLine != null && whenReady != null) {
                    whenReady.run();
                }
            });
        });
    }

    /**
     * The chat the week went into, or a dash.
     *
     * "Elsewhere" is skipped: it is the true top of many weeks and it is not a
     * chat, so naming it would answer a question nobody asked.
     */
    private static CharSequence topChatName(PurpleCore.Report week) {
        for (PurpleCore.ChatSpan span : week.chats) {
            if (span.dialogId != 0) {
                return chatNameFor(UserConfig.selectedAccount, span.dialogId);
            }
        }
        return "\u2014";
    }

    // ---- names, kinds, spans -------------------------------------------------

    private CharSequence chatName(long bareId) {
        return chatNameFor(currentAccount, bareId);
    }

    /**
     * Who a bare id is.
     *
     * The id itself when this account has never heard of it, which is the
     * honest answer rather than a blank: the log outlives a logout, and a
     * chat this install cannot name is still time that was spent.
     */
    private static CharSequence chatNameFor(int account, long bareId) {
        if (bareId == 0) {
            return getString(R.string.PurpleScreenTimeElsewhere);
        }
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.User user = controller.getUser(bareId);
        if (user != null) {
            return UserObject.getUserName(user);
        }
        final TLRPC.Chat chat = controller.getChat(bareId);
        if (chat != null) {
            return chat.title;
        }
        return String.valueOf(bareId);
    }

    private static CharSequence kindLabel(int kind) {
        switch (kind) {
        case PurpleCore.SCREEN_KIND_PRIVATE:
            return getString(R.string.PurpleScreenTimeKindPrivate);
        case PurpleCore.SCREEN_KIND_GROUP:
            return getString(R.string.PurpleScreenTimeKindGroups);
        case PurpleCore.SCREEN_KIND_CHANNEL:
            return getString(R.string.PurpleScreenTimeKindChannels);
        case PurpleCore.SCREEN_KIND_BOT:
            return getString(R.string.PurpleScreenTimeKindBots);
        default:
            return getString(R.string.PurpleScreenTimeElsewhere);
        }
    }

    /** The chart's colour per kind, out of the app's own statistics palette. */
    static int kindColor(int kind) {
        switch (kind) {
        case PurpleCore.SCREEN_KIND_PRIVATE:
            return Theme.getColor(Theme.key_statisticChartLine_blue);
        case PurpleCore.SCREEN_KIND_GROUP:
            return Theme.getColor(Theme.key_statisticChartLine_green);
        case PurpleCore.SCREEN_KIND_CHANNEL:
            return Theme.getColor(Theme.key_statisticChartLine_orange);
        case PurpleCore.SCREEN_KIND_BOT:
            return Theme.getColor(Theme.key_statisticChartLine_purple);
        default:
            // Elsewhere is the leftover and is drawn as one: a muted grey, so
            // the chats it is stacked against stay the thing the eye lands on.
            return Theme.multAlpha(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.45f);
        }
    }

    /** "6 h 12 m", "12 m", "48 s", "0". Never a bare number of milliseconds. */
    public static String formatSpan(long ms) {
        final long seconds = Math.max(0, ms) / 1000;
        if (seconds <= 0) {
            return "0";
        }
        final long hours = seconds / 3600;
        final long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return (minutes > 0) ? (hours + " h " + minutes + " m") : (hours + " h");
        }
        if (minutes > 0) {
            return minutes + " m";
        }
        return seconds + " s";
    }

    /** A share as a whole percent. Zero of zero is zero, not a division. */
    static int percent(long part, long whole) {
        return (whole <= 0) ? 0 : (int) ((part * 100) / whole);
    }

    // ---- the views -----------------------------------------------------------

    /**
     * A row of chips: one choice for the period, several toggles for the
     * filters. Scrolls sideways rather than wrapping, so the row keeps its
     * height whatever the file has named its presets.
     */
    private static class ChipRow extends HorizontalScrollView {

        private final LinearLayout row;

        ChipRow(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setClipToPadding(false);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            addView(row, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }

        void set(List<CharSequence> labels, int selected, Utilities.Callback<Integer> onPick) {
            final boolean[] states = new boolean[labels.size()];
            for (int i = 0; i < states.length; ++i) {
                states[i] = (i == selected);
            }
            setToggles(labels, states, onPick);
        }

        void setToggles(List<CharSequence> labels, boolean[] states,
                Utilities.Callback<Integer> onPick) {
            row.removeAllViews();
            final int on = Theme.getColor(Theme.key_featuredStickers_addButton);
            final int off = Theme.multAlpha(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.12f);
            for (int i = 0; i < labels.size(); ++i) {
                final int index = i;
                final boolean checked = (i < states.length) && states[i];
                final TextView chip = new TextView(getContext());
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                chip.setText(labels.get(i));
                chip.setSingleLine(true);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(14), dp(6), dp(14), dp(6));
                chip.setTextColor(checked
                        ? Theme.getColor(Theme.key_featuredStickers_buttonText)
                        : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                        dp(15), checked ? on : off,
                        Theme.getColor(Theme.key_listSelector)));
                chip.setOnClickListener(v -> onPick.run(index));
                final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LayoutHelper.WRAP_CONTENT, dp(30));
                params.rightMargin = dp(6);
                row.addView(chip, params);
            }
        }
    }

    /** The headline: the total, the share of it that was active, and the change. */
    private static class HeadlineView extends LinearLayout {

        private final TextView total;
        private final TextView detail;

        HeadlineView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            setPadding(dp(21), dp(14), dp(21), dp(14));

            total = new TextView(context);
            total.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
            total.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(total, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            detail = new TextView(context);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            detail.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(detail, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }

        void set(PurpleCore.Scope scope, PurpleCore.Report report, boolean activeOnly) {
            final long shown = (scope == null)
                    ? 0
                    : (activeOnly ? scope.activeMs : scope.totalMs);
            total.setText(formatSpan(shown));
            final StringBuilder text = new StringBuilder();
            if (scope != null) {
                text.append(formatString(R.string.PurpleScreenTimeActiveShare,
                        percent(scope.activeMs, scope.totalMs)));
            }
            // The comparison is against the whole previous range and belongs to
            // the report, not to a chip: "up 12% on last week" is a sentence
            // about the weeks, and narrowing one of them by a filter would make
            // it a comparison of two different questions.
            if (report != null && report.changePercent != null) {
                text.append(" · ");
                final int change = report.changePercent;
                text.append(formatString(change >= 0
                                ? R.string.PurpleScreenTimeUp
                                : R.string.PurpleScreenTimeDown,
                        Math.abs(change)));
            }
            detail.setText(text);
        }
    }

    /**
     * The bars, stacked by chat kind.
     *
     * Drawn by hand rather than through the statistics chart machinery: that is
     * built for server-side series with a time axis and zooming, and this is
     * two dozen bars with five segments each. A Canvas and the app's own theme
     * colours are the whole of it.
     */
    private static class BarChartView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private List<PurpleCore.Bucket> buckets;
        private int kindFilter = KIND_ANY;
        private boolean activeOnly;

        BarChartView(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            text.setTextSize(dp(10));
            text.setTextAlign(Paint.Align.CENTER);
        }

        void set(List<PurpleCore.Bucket> buckets, int kindFilter, boolean activeOnly) {
            this.buckets = buckets;
            this.kindFilter = kindFilter;
            this.activeOnly = activeOnly;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthSpec),
                    MeasureSpec.makeMeasureSpec(dp(160), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (buckets == null || buckets.isEmpty()) {
                return;
            }
            final int count = buckets.size();
            final float left = dp(16);
            final float right = getWidth() - dp(16);
            final float bottom = getHeight() - dp(18);
            final float top = dp(10);
            final float slot = (right - left) / count;
            final float width = Math.max(dp(2), Math.min(dp(18), slot - dp(2)));

            long peak = 1;
            for (PurpleCore.Bucket bucket : buckets) {
                peak = Math.max(peak, value(bucket));
            }

            text.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            for (int i = 0; i < count; ++i) {
                final PurpleCore.Bucket bucket = buckets.get(i);
                final float centre = left + slot * i + slot / 2f;
                final float height = (bottom - top) * value(bucket) / (float) peak;
                float y = bottom;

                // Stacked from the bottom in kind order, so the same colour is
                // always in the same place and a bar can be read across the row
                // without a legend for each one.
                for (int kind = 0; kind < PurpleCore.SCREEN_KIND_COUNT; ++kind) {
                    if (kindFilter != KIND_ANY && kind != kindFilter) {
                        continue;
                    }
                    final long part = activeOnly
                            ? bucket.kindActiveMs[kind]
                            : bucket.kindTotalMs[kind];
                    if (part <= 0) {
                        continue;
                    }
                    final float segment = height * part / (float) Math.max(1, value(bucket));
                    paint.setColor(kindColor(kind));
                    rect.set(centre - width / 2f, y - segment, centre + width / 2f, y);
                    canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                    y -= segment;
                }

                // Every fourth label on a long row, so a month of days does not
                // draw thirty-one overlapping dates.
                final int step = Math.max(1, count / 8);
                if (i % step == 0) {
                    canvas.drawText(shortLabel(bucket.label), centre,
                            getHeight() - dp(5), text);
                }
            }
        }

        private long value(PurpleCore.Bucket bucket) {
            if (kindFilter == KIND_ANY) {
                return activeOnly ? bucket.activeMs : bucket.totalMs;
            }
            return activeOnly
                    ? bucket.kindActiveMs[kindFilter]
                    : bucket.kindTotalMs[kindFilter];
        }

        /** "2026-09-08" is a day; the axis has room for "08". */
        private static String shortLabel(String label) {
            if (label == null) {
                return "";
            }
            final int cut = label.lastIndexOf('-');
            return (cut >= 0 && cut + 1 < label.length()) ? label.substring(cut + 1) : label;
        }
    }

    /**
     * Hour by weekday. Seven rows of twenty-four cells, each shaded by how much
     * of the period's time landed there - the view that says when in a week the
     * app happens, which a row of thirty-one bars cannot.
     */
    private static class HeatMapView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private long[][] values;

        HeatMapView(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            text.setTextSize(dp(9));
        }

        void set(long[][] values) {
            this.values = values;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthSpec),
                    MeasureSpec.makeMeasureSpec(dp(132), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (values == null) {
                return;
            }
            final float left = dp(34);
            final float top = dp(8);
            final float cell = (getWidth() - left - dp(12)) / 24f;
            final float height = dp(14);
            final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);

            long peak = 1;
            for (int day = 0; day < 7; ++day) {
                for (int hour = 0; hour < 24; ++hour) {
                    peak = Math.max(peak, values[day][hour]);
                }
            }

            text.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            for (int day = 0; day < 7; ++day) {
                final float y = top + day * (height + dp(2));
                canvas.drawText(LocaleController.getInstance().getFormatterWeek()
                        .format(dayOfWeekSample(day)), dp(6), y + height - dp(3), text);
                for (int hour = 0; hour < 24; ++hour) {
                    final float share = values[day][hour] / (float) peak;
                    paint.setColor(share <= 0
                            ? Theme.multAlpha(Theme.getColor(
                                    Theme.key_windowBackgroundWhiteGrayText), 0.08f)
                            : Color.argb(
                                    (int) (40 + 215 * share),
                                    Color.red(accent), Color.green(accent), Color.blue(accent)));
                    rect.set(left + hour * cell, y,
                            left + (hour + 1) * cell - dp(1), y + height);
                    canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                }
            }
        }

        /** A moment on the weekday this row is, for the locale's own name of it. */
        private static long dayOfWeekSample(int mondayFirstIndex) {
            final Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            calendar.add(Calendar.DAY_OF_YEAR, mondayFirstIndex);
            return calendar.getTimeInMillis();
        }
    }

    /** One ranked chat: avatar, name, a proportional bar and its active share. */
    private static class ChatRow extends FrameLayout {

        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView name;
        private final TextView value;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private float fraction;
        private float activeFraction;
        private int kind;

        ChatRow(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(18));
            addView(avatar, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.TOP, 16, 8, 0, 0));

            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.TOP, 64, 7, 100, 0));

            value = new TextView(context);
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            value.setGravity(Gravity.RIGHT);
            value.setSingleLine(true);
            value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(value, LayoutHelper.createFrame(96, LayoutHelper.WRAP_CONTENT,
                    Gravity.RIGHT | Gravity.TOP, 0, 9, 16, 0));
        }

        void set(int account, PurpleCore.ChatSpan span, long top, boolean activeOnly) {
            kind = span.kind;
            final long shown = activeOnly ? span.activeMs : span.totalMs;
            fraction = (top <= 0) ? 0 : Math.min(1f, shown / (float) top);
            activeFraction = (span.totalMs <= 0) ? 0 : span.activeMs / (float) span.totalMs;

            final MessagesController controller = MessagesController.getInstance(account);
            final TLRPC.User user = controller.getUser(span.dialogId);
            final TLRPC.Chat chat = (user == null) ? controller.getChat(span.dialogId) : null;
            final TLObject object = (user != null) ? user : chat;
            if (object != null) {
                avatarDrawable.setInfo(account, object);
                avatar.setForUserOrChat(object, avatarDrawable);
                name.setText(user != null ? UserObject.getUserName(user) : chat.title);
            } else {
                // A chat this account cannot name. The id is the honest answer:
                // the log outlives a logout, and the time was still spent.
                avatarDrawable.setInfo(span.dialogId, "#", null);
                avatar.setImageDrawable(avatarDrawable);
                name.setText(String.valueOf(span.dialogId));
            }
            value.setText(formatSpan(shown) + " · "
                    + formatString(R.string.PurpleScreenTimeActiveShare,
                            percent(span.activeMs, span.totalMs)));
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec,
                    MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            // The bar sits under the name rather than beside it: proportional to
            // the longest chat in the list, with the active part drawn solid
            // inside the whole, so both numbers on the right have a picture.
            final float left = dp(64);
            final float right = getWidth() - dp(16);
            final float y = getHeight() - dp(14);
            final float width = (right - left) * fraction;

            paint.setColor(Theme.multAlpha(kindColor(kind), 0.35f));
            rect.set(left, y, left + width, y + dp(4));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);

            paint.setColor(kindColor(kind));
            rect.set(left, y, left + width * activeFraction, y + dp(4));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);

            super.dispatchDraw(canvas);
        }
    }
}
