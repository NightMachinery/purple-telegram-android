/*
 * This is the source code of Purple Telegram for Android.
 *
 * The schedule: which preset runs when, as rows instead of TOML blocks.
 *
 * This screen is the list of rulesets - the named groups of rules, each aimed
 * at the devices it is for - with the master switch, the pause and the line
 * saying what the schedule is doing right now above them. One ruleset's rules
 * are edited on PurpleRulesetActivity, which every row here opens.
 *
 * Every write goes through the core's splice, a rule addressed by its ruleset
 * and its position in that ruleset's raw array and fenced by what the screen
 * read off it, so a dialog left open while the file moved underneath refuses
 * rather than rewriting somebody else's rule.
 *
 * The tick needs no change for any of this: every write reloads, and the tick
 * reads the schedule the gate is holding.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.DatePickerDialog;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleDevice;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PurpleScheduleActivity extends UniversalFragment
        implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_ENABLED = 1;
    private static final int ROW_PAUSED = 2;
    private static final int ROW_PAUSE_UNTIL = 3;
    private static final int ROW_OUTSIDE = 4;
    private static final int ROW_ADD_RULESET = 5;

    /** Ruleset rows carry their position above this, so one id space serves both. */
    private static final int RULESET_BASE = 1000;

    /** The name the core gives the no-preset preset, spelled as the file does. */
    public static final String NORMAL_PRESET = "normal";

    /** Every scope a ruleset can name that is not a device id, in row order. */
    private static final String[] SCOPES = {
        "any", "desktop", "mobile", "android", "ios", "macos", "windows", "linux",
    };


    // Every reload the gate does ends in this signal, whichever account-wide
    // thing caused it - the preset picker, a schedule boundary, a peek ending,
    // the watcher noticing that settings.toml changed on disk. Rebuilding on it
    // is what keeps this screen agreeing with the file when the change did not
    // come from a row on it.
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
        if (id == NotificationCenter.dialogFiltersUpdated && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.PurpleScheduleTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // Re-read on every rebuild, and every write ends in a reload that
        // rebuilds - so a refused write puts the screen back where the file is
        // without this screen having to undo anything.
        final PurpleCore.Loaded state = PurpleGate.state();

        final UItem enabled = UItem.asCheck(ROW_ENABLED, getString(R.string.PurpleScheduleOn));
        enabled.checked = (state == null || state.scheduleEnabled);
        items.add(enabled);
        items.add(UItem.asShadow(getString(R.string.PurpleScheduleOnInfo)));

        final boolean paused = (state != null && state.clock.schedulePaused);
        final UItem pause = UItem.asCheck(ROW_PAUSED, getString(R.string.PurpleSchedulePause));
        // state.toml, not settings.toml: a pause is a decision about the next
        // few hours and expires on its own, so it is not something the file
        // shared between devices should remember.
        pause.checked = paused;
        items.add(pause);
        if (paused) {
            // Only while something is held off. A deadline row over an unpaused
            // schedule would be asking when to stop doing nothing.
            items.add(UItem.asButton(ROW_PAUSE_UNTIL,
                    getString(R.string.PurpleSchedulePauseUntilRow),
                    untilText(state.clock.schedulePausedUntil)));
        }
        items.add(UItem.asShadow(getString(R.string.PurpleSchedulePauseInfo)));

        items.add(UItem.asHeader(getString(R.string.PurpleScheduleRulesetsHeader)));
        items.add(UItem.asShadow(statusLine(state)));

        if (state != null) {
            for (int a = 0, n = state.scheduleRulesets.size(); a < n; ++a) {
                final PurpleCore.ScheduleRuleset ruleset = state.scheduleRulesets.get(a);
                items.add(UItem.asButton(RULESET_BASE + a,
                        rulesetTitle(ruleset), rulesetSubtitle(state, ruleset)));
            }
        }
        items.add(UItem.asButton(ROW_ADD_RULESET,
                getString(R.string.PurpleScheduleAddRuleset)));
        items.add(UItem.asShadow(getString(R.string.PurpleScheduleRulesetsInfo)));

        items.add(UItem.asButton(ROW_OUTSIDE, getString(R.string.PurpleScheduleOutsideRow),
                presetLabel(state, state == null ? NORMAL_PRESET : state.scheduleOutside)));
        items.add(UItem.asShadow(getString(R.string.PurpleScheduleOutsideInfo)));

        // A rule the parser threw away has no window and no preset to draw, so
        // it cannot be a row. The warning is the only place the reason survives,
        // and saying nothing about it would leave a rule in the file that the
        // screen appears to deny the existence of.
        final List<String> broken = brokenRules(state);
        if (!broken.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.PurpleScheduleBrokenHeader)));
            for (int a = 0, n = broken.size(); a < n; ++a) {
                items.add(UItem.asShadow(broken.get(a)));
            }
        }
    }

    // ---- what the schedule is doing -------------------------------------------

    /**
     * What the schedule is doing right now, in one line.
     *
     * Three shapes, and the difference between them is the whole of what
     * `outside' added. Inside a window it says what runs and what takes over
     * when the window ends, because that is no longer always Normal. Outside
     * one it says what is running now and until when. "Nothing scheduled" is
     * kept for the one case it is still true of: nothing runs, and nothing is
     * what the schedule wants between the windows either.
     */
    public static CharSequence statusLine(PurpleCore.Loaded state) {
        if (state == null) {
            return getString(R.string.PurpleScheduleNoRules);
        }
        if (!state.scheduleEnabled) {
            return getString(R.string.PurpleScheduleOffLine);
        }
        if (state.clock.schedulePaused) {
            return (state.clock.schedulePausedUntil > 0)
                    ? formatString(R.string.PurpleSchedulePausedUntil,
                            dateText(state.clock.schedulePausedUntil))
                    : getString(R.string.PurpleSchedulePausedLine);
        }
        if (state.scheduleRules.isEmpty()) {
            // "No rules" and "none of them are this device's" are different
            // things to be told, and with rulesets the second is the one a
            // phone holding the laptop's schedule will keep seeing.
            return getString(state.scheduleRulesets.isEmpty()
                    ? R.string.PurpleScheduleNoRules
                    : R.string.PurpleScheduleNoneHere);
        }
        final CharSequence outside = presetLabel(state, state.scheduleOutside);
        final PurpleCore.ScheduleRule now = ruleAt(state, state.scheduleNow);
        if (now != null) {
            return formatString(R.string.PurpleScheduleNowThen,
                    presetLabel(state, now.preset), timeText(now.till), outside);
        }
        final PurpleCore.ScheduleRule next = nextRule(state.scheduleRules);
        if (isNormal(state.scheduleOutside)) {
            // Nothing is running, so the interesting half is what comes next -
            // and saying "now: Normal" about stock Telegram would be describing
            // the absence of a preset as one.
            return (next == null)
                    ? getString(R.string.PurpleScheduleNothingNow)
                    : formatString(R.string.PurpleScheduleNext,
                            presetLabel(state, next.preset), timeText(next.from));
        }
        return (next == null)
                ? formatString(R.string.PurpleScheduleNowOnly, outside)
                : formatString(R.string.PurpleScheduleNow, outside, timeText(next.from));
    }

    /**
     * The same line, for the Work Mode settings row.
     *
     * One implementation rather than two: everything the row has to say is
     * already worked out here, and a second copy of "now: work until 17:00,
     * then home" would drift the first time one of them was corrected.
     */
    public static CharSequence summary(PurpleCore.Loaded state) {
        return statusLine(state);
    }

    /** The parser's complaints about rules and rulesets it dropped, in file order. */
    private static List<String> brokenRules(PurpleCore.Loaded state) {
        final List<String> result = new ArrayList<>();
        if (state == null) {
            return result;
        }
        for (int a = 0, n = state.warnings.size(); a < n; ++a) {
            final String warning = state.warnings.get(a);
            // Both prefixes: a rule in the flat array is "schedule rule N", one
            // inside a ruleset is "schedule ruleset 'x' rule N", and so is the
            // complaint about a ruleset that was skipped whole.
            if (warning != null
                    && (warning.startsWith("schedule rule ")
                            || warning.startsWith("schedule ruleset "))) {
                result.add(warning);
            }
        }
        return result;
    }

    // ---- naming ---------------------------------------------------------------

    /** What to call a ruleset on a row. The implicit one has no name of its own. */
    public static CharSequence rulesetTitle(PurpleCore.ScheduleRuleset ruleset) {
        return ruleset.implicit()
                ? getString(R.string.PurpleScheduleRulesHeader)
                : ruleset.name;
    }

    /** "This device · Enabled": who it is for, and what it does about it. */
    public static CharSequence rulesetSubtitle(
            PurpleCore.Loaded state, PurpleCore.ScheduleRuleset ruleset) {
        return scopeLabel(state, ruleset.device) + " · " + modeLabel(ruleset.mode);
    }

    /**
     * What a ruleset's {@code device} means, in words.
     *
     * "This device" wins over the label, because that is the more useful of the
     * two things it could say: a person reading the phone's screen already
     * knows what they called the phone. Anything the core does not recognise is
     * a device id, so it falls through to the label {@code [devices]} gives it,
     * and to the raw id when nothing does.
     */
    public static CharSequence scopeLabel(PurpleCore.Loaded state, String device) {
        final String wanted = (device == null) ? "" : device.trim();
        if (wanted.length() == 0 || wanted.equalsIgnoreCase("any")) {
            return getString(R.string.PurpleScheduleDeviceAny);
        }
        if (state != null && wanted.equalsIgnoreCase(state.deviceId)) {
            return getString(R.string.PurpleScheduleDeviceThis);
        }
        final int known = scopeIndex(wanted);
        if (known >= 0) {
            return getString(scopeString(known));
        }
        final String label = PurpleDevice.labelFor(state, wanted);
        return (label != null) ? label : wanted;
    }

    /** Which of {@link #SCOPES} this is, or -1 for a device id. */
    private static int scopeIndex(String device) {
        for (int a = 0; a < SCOPES.length; ++a) {
            if (SCOPES[a].equalsIgnoreCase(device)) {
                return a;
            }
        }
        return -1;
    }

    private static int scopeString(int index) {
        switch (index) {
        case 1: return R.string.PurpleScheduleDeviceDesktop;
        case 2: return R.string.PurpleScheduleDeviceMobile;
        case 3: return R.string.PurpleScheduleDeviceAndroid;
        case 4: return R.string.PurpleScheduleDeviceIos;
        case 5: return R.string.PurpleScheduleDeviceMacos;
        case 6: return R.string.PurpleScheduleDeviceWindows;
        case 7: return R.string.PurpleScheduleDeviceLinux;
        default: return R.string.PurpleScheduleDeviceAny;
        }
    }

    /** {@code disabled} / {@code enabled} / {@code always}, in words. */
    public static CharSequence modeLabel(String mode) {
        if ("disabled".equalsIgnoreCase(mode)) {
            return getString(R.string.PurpleScheduleModeDisabled);
        }
        if ("always".equalsIgnoreCase(mode)) {
            return getString(R.string.PurpleScheduleModeAlways);
        }
        return getString(R.string.PurpleScheduleModeEnabled);
    }

    /** Whether a preset name means stock Telegram. Empty does too, as the core has it. */
    public static boolean isNormal(String preset) {
        return TextUtils.isEmpty(preset) || NORMAL_PRESET.equalsIgnoreCase(preset);
    }

    /**
     * A preset's title, or its name when the file gave it none.
     *
     * Normal is not in the file - it is the absence of a preset - so it is
     * named by hand here, the same way the preset radio list has to add it.
     */
    public static CharSequence presetLabel(PurpleCore.Loaded state, String preset) {
        if (isNormal(preset)) {
            return getString(R.string.PurplePresetNormal);
        }
        if (state != null) {
            for (int a = 0, n = state.presets.size(); a < n; ++a) {
                final PurpleCore.PresetInfo info = state.presets.get(a);
                if (info.name.equalsIgnoreCase(preset)) {
                    return TextUtils.isEmpty(info.title) ? info.name : info.title;
                }
            }
        }
        return preset;
    }

    // ---- one line per rule ---------------------------------------------------

    /** "Mon-Fri 09:00-17:00": the window, without the preset it turns on. */
    public static CharSequence window(PurpleCore.ScheduleRule rule) {
        return daysText(rule.days) + " " + timeText(rule.from) + "-" + timeText(rule.till);
    }

    /** The whole rule on one line, for a dialog that has no second one. */
    public static CharSequence ruleLine(PurpleCore.ScheduleRule rule) {
        return window(rule) + " → " + rule.preset;
    }

    /**
     * The days, collapsed into runs.
     *
     * "Mon-Fri" rather than "Mon, Tue, Wed, Thu, Fri" because that is how a
     * person says it and how they wrote it in the file. Runs are found over the
     * sorted set, so the order the file listed them in does not change the text.
     */
    public static String daysText(int[] days) {
        final boolean[] on = new boolean[8];
        int count = 0;
        for (int a = 0; a < days.length; ++a) {
            if (days[a] >= 1 && days[a] <= 7 && !on[days[a]]) {
                on[days[a]] = true;
                ++count;
            }
        }
        if (count == 0) {
            return "";
        }
        if (count == 7) {
            return getString(R.string.PurpleScheduleEveryDay);
        }
        final StringBuilder out = new StringBuilder();
        int day = 1;
        while (day <= 7) {
            if (!on[day]) {
                ++day;
                continue;
            }
            int end = day;
            while (end + 1 <= 7 && on[end + 1]) {
                ++end;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(dayName(day));
            if (end > day + 1) {
                out.append('-').append(dayName(end));
            } else if (end == day + 1) {
                out.append(", ").append(dayName(end));
            }
            day = end + 1;
        }
        return out.toString();
    }

    /** Monday .. Sunday as 1 .. 7, named in the user's own locale. */
    public static String dayName(int day) {
        final Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_WEEK, (day == 7) ? Calendar.SUNDAY : day + 1);
        return LocaleController.getInstance().getFormatterWeek().format(calendar.getTime());
    }

    /** Minutes since midnight as "HH:MM", the way the file writes them. */
    public static String timeText(int minutes) {
        if (minutes < 0) {
            return "--:--";
        }
        return String.format(Locale.US, "%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }

    /** A wall-clock second as a date, in the user's own locale. */
    public static String dateText(long unix) {
        return LocaleController.getInstance()
                .getFormatterDayMonth().format(new Date(unix * 1000L));
    }

    /** What the pause row's value says: a date, or that there is no deadline. */
    public static CharSequence untilText(long unix) {
        return (unix > 0)
                ? dateText(unix)
                : getString(R.string.PurpleSchedulePauseUnpaused);
    }

    /** The running rule carrying this address, or null. */
    public static PurpleCore.ScheduleRule ruleAt(
            PurpleCore.Loaded state, PurpleCore.ScheduleNow at) {
        if (state == null || at == null || at.index < 0) {
            return null;
        }
        return ruleAt(state.scheduleRules, at.ruleset, at.index);
    }

    /**
     * The rule at this address, or null.
     *
     * Both halves have to match: a rule is numbered within its own ruleset, so
     * index 0 names one rule per ruleset and the name is what tells them apart.
     */
    public static PurpleCore.ScheduleRule ruleAt(
            List<PurpleCore.ScheduleRule> rules, String ruleset, int index) {
        if (rules == null || index < 0) {
            return null;
        }
        for (int a = 0, n = rules.size(); a < n; ++a) {
            final PurpleCore.ScheduleRule rule = rules.get(a);
            if (rule.index == index && rule.ruleset.equals(ruleset)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * The enabled rule that starts soonest, wrapping past Sunday.
     *
     * Worked out here rather than asked of the core: the core answers what is
     * happening now, and "what happens next" is a question only a screen asks.
     * Positions are minutes from Monday 00:00, so a window earlier in the week
     * than this moment simply lands a week later.
     */
    public static PurpleCore.ScheduleRule nextRule(List<PurpleCore.ScheduleRule> rules) {
        final Calendar calendar = Calendar.getInstance();
        final int dow = calendar.get(Calendar.DAY_OF_WEEK);
        // Calendar counts from Sunday; the core counts Monday .. Sunday as
        // 1 .. 7, the way the file spells its day names.
        final int today = (dow == Calendar.SUNDAY) ? 7 : dow - 1;
        final int nowAt = (today - 1) * 1440
                + calendar.get(Calendar.HOUR_OF_DAY) * 60
                + calendar.get(Calendar.MINUTE);

        PurpleCore.ScheduleRule best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (int a = 0, n = rules.size(); a < n; ++a) {
            final PurpleCore.ScheduleRule rule = rules.get(a);
            if (!rule.enabled || rule.from < 0) {
                continue;
            }
            for (int b = 0; b < rule.days.length; ++b) {
                final int day = rule.days[b];
                if (day < 1 || day > 7) {
                    continue;
                }
                int delta = ((day - 1) * 1440 + rule.from) - nowAt;
                if (delta <= 0) {
                    delta += 7 * 1440;
                }
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = rule;
                }
            }
        }
        return best;
    }

    // ---- taps ----------------------------------------------------------------

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ROW_ENABLED) {
            write(PurpleWriter.setTableBool("schedule", "enabled_p", !item.checked,
                    "schedule switch"));
            return;
        }
        if (item.id == ROW_PAUSED) {
            // Not a splice: this one lives in state.toml and has its own writer,
            // which reloads for itself. Zero on the way in - a pause starts
            // open-ended, and the row below is where a deadline is put on it.
            PurpleGate.setSchedulePaused(!item.checked, 0);
            refresh();
            return;
        }
        if (item.id == ROW_PAUSE_UNTIL) {
            pickUntil();
            return;
        }
        if (item.id == ROW_OUTSIDE) {
            pickOutside();
            return;
        }
        if (item.id == ROW_ADD_RULESET) {
            addRuleset();
            return;
        }
        if (item.id >= RULESET_BASE) {
            // Re-read rather than trusting the row: a reload may have landed
            // between the draw and the tap, and a ruleset that has gone would
            // otherwise open an empty screen.
            final PurpleCore.Loaded state = PurpleGate.state();
            final int at = item.id - RULESET_BASE;
            if (state == null || at >= state.scheduleRulesets.size()) {
                refresh();
                return;
            }
            presentFragment(new PurpleRulesetActivity(
                    state.scheduleRulesets.get(at).address));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void refresh() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void write(String error) {
        if (error != null && BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this)
                    .createErrorBulletin(formatString(R.string.PurpleWriteFailed, error))
                    .show();
        }
        refresh();
    }

    // ---- pausing until a date -------------------------------------------------

    /**
     * When the pause should run out.
     *
     * Midnight at the start of the chosen day, so "until Monday" means the whole
     * of Sunday and none of Monday - which is what a person picking a date on a
     * pause means by it. Tomorrow is the default and the earliest: a deadline
     * earlier than that has already passed by the time it is written, and a
     * pause that lifts itself on the next tick is not a pause.
     */
    private void pickUntil() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final Calendar tomorrow = midnightTomorrow();
        final DatePickerDialog dialog = new DatePickerDialog(
                context,
                (view, year, month, day) -> {
                    final Calendar until = Calendar.getInstance();
                    until.set(year, month, day, 0, 0, 0);
                    until.set(Calendar.MILLISECOND, 0);
                    PurpleGate.setSchedulePaused(true, until.getTimeInMillis() / 1000L);
                    refresh();
                },
                tomorrow.get(Calendar.YEAR),
                tomorrow.get(Calendar.MONTH),
                tomorrow.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMinDate(tomorrow.getTimeInMillis());
        // The way back to an open-ended pause, since the picker itself has no
        // button for "no date at all".
        dialog.setButton(DatePickerDialog.BUTTON_NEUTRAL,
                getString(R.string.PurpleSchedulePauseUnpaused),
                (d, which) -> {
                    PurpleGate.setSchedulePaused(true, 0);
                    refresh();
                });
        showDialog(dialog);
    }

    private static Calendar midnightTomorrow() {
        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    // ---- the schedule's own outside preset ------------------------------------

    private void pickOutside() {
        final Context context = getParentActivity();
        final PurpleCore.Loaded state = PurpleGate.state();
        if (context == null || state == null) {
            return;
        }
        showPresetPicker(this, context, state, getString(R.string.PurpleScheduleOutsideRow),
                state.scheduleOutside, null,
                chosen -> write(PurpleWriter.setTableString(
                        "schedule", "outside", chosen, "schedule outside")));
    }

    // ---- a new ruleset --------------------------------------------------------

    /**
     * Name it, say who it is for and what it does, and write the block.
     *
     * Two writes rather than one: AddRuleset() takes the device and the mode,
     * which are the two keys a ruleset cannot be read without, and `outside' is
     * an override that most rulesets do not want - so it is written afterwards
     * and only when it was asked for, leaving a file that says nothing extra.
     */
    private void addRuleset() {
        final Context context = getParentActivity();
        final PurpleCore.Loaded state = PurpleGate.state();
        if (context == null || state == null) {
            return;
        }
        final String[] device = { "any" };
        final String[] mode = { "enabled" };
        final String[] outside = { null };

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditTextBoldCursor name = new EditTextBoldCursor(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        name.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        name.setBackgroundDrawable(null);
        name.setSingleLine(true);
        name.setHint(getString(R.string.PurpleScheduleRulesetName));
        name.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        layout.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        final TextView appliesTo = pickerRow(context, layout,
                getString(R.string.PurpleScheduleAppliesTo), scopeLabel(state, device[0]));
        appliesTo.setOnClickListener(v -> showScopePicker(this, context, state, device[0], chosen -> {
            device[0] = chosen;
            appliesTo.setText(getString(R.string.PurpleScheduleAppliesTo)
                    + "   " + scopeLabel(state, chosen));
        }));

        final TextView modeRow = pickerRow(context, layout,
                getString(R.string.PurpleScheduleMode), modeLabel(mode[0]));
        modeRow.setOnClickListener(v -> showModePicker(this, context, mode[0], chosen -> {
            mode[0] = chosen;
            modeRow.setText(getString(R.string.PurpleScheduleMode)
                    + "   " + modeLabel(chosen));
        }));

        final TextView outsideRow = pickerRow(context, layout,
                getString(R.string.PurpleScheduleOutsideRow),
                getString(R.string.PurpleScheduleOutsideSame));
        outsideRow.setOnClickListener(v -> showPresetPicker(this, context, state,
                getString(R.string.PurpleScheduleOutsideRow), outside[0],
                getString(R.string.PurpleScheduleOutsideSame),
                chosen -> {
                    outside[0] = (chosen.length() == 0) ? null : chosen;
                    outsideRow.setText(getString(R.string.PurpleScheduleOutsideRow) + "   "
                            + (outside[0] == null
                                    ? getString(R.string.PurpleScheduleOutsideSame)
                                    : presetLabel(state, outside[0])));
                }));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PurpleScheduleRulesetNew));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            final String wanted = name.getText().toString().trim();
            if (wanted.length() == 0) {
                error(getString(R.string.PurpleScheduleRulesetNoName));
                return;
            }
            final String failed = PurpleWriter.addRuleset(
                    wanted, device[0], mode[0], "ruleset added");
            if (failed == null && outside[0] != null) {
                write(PurpleWriter.setRulesetString(
                        wanted, "outside", outside[0], "ruleset outside"));
                return;
            }
            write(failed);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // ---- the pickers, shared with the ruleset screen ---------------------------

    /** What a picker hands back: the chosen value, empty for "say nothing". */
    public interface Chosen {
        void run(String value);
    }

    /**
     * A tappable "Label   value" row inside a dialog.
     *
     * The dialog is a plain LinearLayout rather than a fragment, so there is no
     * UItem to use and no cell that draws a value beside a label; this is the
     * same shape the rule editor's time rows already use.
     */
    public static TextView pickerRow(
            Context context, LinearLayout layout, CharSequence label, CharSequence value) {
        final TextView row = new TextView(context);
        row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        row.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        row.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)
                | Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        row.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        row.setText(label + "   " + value);
        layout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        return row;
    }

    /**
     * The preset radio list, with Normal first.
     *
     * Normal is added by hand: it is a preset name the file may use like any
     * other, but it is not written in settings.toml, so it is not in the list
     * the core hands over. Without it a window could turn Work Mode on and no
     * window could turn it off again.
     *
     * `sameAs' adds a row above it meaning "say nothing here", which answers
     * with an empty string - the way a ruleset defers to [schedule] outside.
     */
    public static void showPresetPicker(
            BaseFragment fragment,
            Context context,
            PurpleCore.Loaded state,
            CharSequence title,
            String current,
            CharSequence sameAs,
            Chosen chosen) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<RadioColorCell> radios = new ArrayList<>();
        final String[] picked = { (current == null) ? "" : current };
        if (sameAs != null) {
            addRadio(context, layout, radios, picked, "", sameAs);
        }
        addRadio(context, layout, radios, picked,
                NORMAL_PRESET, getString(R.string.PurplePresetNormal));
        for (int a = 0, n = state.presets.size(); a < n; ++a) {
            final PurpleCore.PresetInfo info = state.presets.get(a);
            addRadio(context, layout, radios, picked, info.name,
                    TextUtils.isEmpty(info.title) ? info.name : info.title);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save),
                (dialog, which) -> chosen.run(picked[0]));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** Disabled / Enabled / Always, as the core spells them in the file. */
    public static void showModePicker(
            BaseFragment fragment, Context context, String current, Chosen chosen) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<RadioColorCell> radios = new ArrayList<>();
        final String[] picked = { (current == null) ? "enabled" : current };
        addRadio(context, layout, radios, picked, "disabled",
                getString(R.string.PurpleScheduleModeDisabled));
        addRadio(context, layout, radios, picked, "enabled",
                getString(R.string.PurpleScheduleModeEnabled));
        addRadio(context, layout, radios, picked, "always",
                getString(R.string.PurpleScheduleModeAlways));
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PurpleScheduleMode));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save),
                (dialog, which) -> chosen.run(picked[0]));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /**
     * Who a ruleset is for: the eight the core knows, this device by id, and a
     * field for another device's id.
     *
     * The field is the whole reason the list is not just radios: a device id is
     * something you read off the other machine's settings screen and type in
     * once, and there is nowhere else on the phone to type it.
     */
    public static void showScopePicker(
            BaseFragment fragment, Context context, PurpleCore.Loaded state,
            String current, Chosen chosen) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<RadioColorCell> radios = new ArrayList<>();

        final String deviceId = (state == null) ? "" : state.deviceId;
        final boolean isThis = deviceId.length() > 0 && deviceId.equalsIgnoreCase(current);
        final boolean isOther = !isThis && scopeIndex(current) < 0;
        final String[] picked = { isOther ? "" : (current == null ? "any" : current) };

        for (int a = 0; a < SCOPES.length; ++a) {
            addRadio(context, layout, radios, picked, SCOPES[a],
                    getString(scopeString(a)));
        }
        if (deviceId.length() > 0) {
            addRadio(context, layout, radios, picked, deviceId,
                    getString(R.string.PurpleScheduleDeviceThis));
        }
        // The free-text row is not a radio: typing in it IS choosing it, and a
        // radio the field could disagree with would be two answers on screen.
        final EditTextBoldCursor other = new EditTextBoldCursor(context);
        other.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        other.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        other.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        other.setBackgroundDrawable(null);
        other.setSingleLine(true);
        other.setHint(getString(R.string.PurpleScheduleDeviceOther));
        other.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        if (isOther) {
            other.setText(current);
        }
        layout.addView(other, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PurpleScheduleAppliesTo));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            final String typed = other.getText().toString().trim();
            chosen.run(typed.length() > 0 ? typed : picked[0]);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** One radio row in a dialog, keeping the group in step. */
    public static void addRadio(
            Context context,
            LinearLayout layout,
            ArrayList<RadioColorCell> radios,
            String[] picked,
            String value,
            CharSequence label) {
        final RadioColorCell cell = new RadioColorCell(context);
        cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
        cell.setTextAndValue(label, value.equalsIgnoreCase(picked[0]));
        cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> {
            picked[0] = value;
            for (int b = 0; b < radios.size(); ++b) {
                radios.get(b).setChecked(radios.get(b) == v, true);
            }
        });
        radios.add(cell);
        layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
    }

    /**
     * Whether a tap landed on the row's switch rather than on the row.
     *
     * The cell hands out its own switch, so the split is asked of the view that
     * drew it instead of guessed from a width a screen would have to keep in
     * step with the cell.
     */
    public static boolean onSwitch(View view, float x) {
        if (!(view instanceof NotificationsCheckCell)) {
            return false;
        }
        final View box = ((NotificationsCheckCell) view).getCheckBox();
        return box != null && x >= box.getLeft() && x <= box.getRight();
    }

    private void error(CharSequence text) {
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createErrorBulletin(text).show();
        }
    }
}
