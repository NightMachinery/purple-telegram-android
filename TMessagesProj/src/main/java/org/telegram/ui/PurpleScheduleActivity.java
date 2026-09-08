/*
 * This is the source code of Purple Telegram for Android.
 *
 * The schedule: which preset runs when, as rows instead of [[schedule.rules]]
 * blocks. Every write goes through the core's splice, addressed by the rule's
 * position in the raw array and fenced by what this screen read off it, so a
 * dialog left open while the file moved underneath refuses rather than
 * rewriting somebody else's rule.
 *
 * The tick needs no change for any of this: every write reloads, and the tick
 * reads the schedule the gate is holding.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.TimePickerDialog;
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
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class PurpleScheduleActivity extends UniversalFragment
        implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_ENABLED = 1;
    private static final int ROW_PAUSED = 2;
    private static final int ROW_ADD = 3;

    /** Rule rows carry sourceIndex above this, so one id space serves both. */
    private static final int RULE_BASE = 1000;


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

        final UItem paused = UItem.asCheck(ROW_PAUSED, getString(R.string.PurpleSchedulePauseToday));
        // state.toml, not settings.toml: a pause is about today and expires on
        // its own, so it is not something the file should remember.
        paused.checked = (state != null && state.clock.schedulePaused);
        items.add(paused);
        items.add(UItem.asShadow(getString(R.string.PurpleSchedulePauseTodayInfo)));

        items.add(UItem.asHeader(getString(R.string.PurpleScheduleRulesHeader)));
        items.add(UItem.asShadow(statusLine(state)));

        if (state != null) {
            for (int a = 0, n = state.scheduleRules.size(); a < n; ++a) {
                final PurpleCore.ScheduleRule rule = state.scheduleRules.get(a);
                // A two-part row: the switch alone is enabled_p, and the rest
                // of it opens the editor. One row rather than two because they
                // are one rule, and the quick answer - "not this week" - should
                // not cost a dialog.
                final UItem row = UItem.asButtonCheck(
                        RULE_BASE + rule.index, window(rule), rule.preset);
                row.checked = rule.enabled;
                items.add(row);
            }
        }
        items.add(UItem.asButton(ROW_ADD, getString(R.string.PurpleScheduleAddRule)));
        items.add(UItem.asShadow(getString(R.string.PurpleScheduleRulesInfo)));

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

    /** What the schedule is doing right now, above the rules. */
    private static CharSequence statusLine(PurpleCore.Loaded state) {
        if (state == null || state.scheduleRules.isEmpty()) {
            return getString(R.string.PurpleScheduleNoRules);
        }
        final PurpleCore.ScheduleRule now = ruleAt(state, state.scheduleNow);
        if (now == null || !state.scheduleEnabled || state.clock.schedulePaused) {
            return getString(R.string.PurpleScheduleNothingNow);
        }
        return formatString(R.string.PurpleScheduleNow, now.preset, timeText(now.till));
    }

    /** The parser's complaints about rules it dropped, in file order. */
    private static List<String> brokenRules(PurpleCore.Loaded state) {
        final List<String> result = new ArrayList<>();
        if (state == null) {
            return result;
        }
        for (int a = 0, n = state.warnings.size(); a < n; ++a) {
            final String warning = state.warnings.get(a);
            if (warning != null && warning.startsWith("schedule rule ")) {
                result.add(warning);
            }
        }
        return result;
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
     * The schedule in one line, for the Work Mode settings row.
     *
     * Here rather than there because everything it needs to say - the rule
     * running, the one coming, how a window is named - is already written on
     * this screen, and two implementations of "now: work until 17:00" would
     * drift the first time one of them was corrected.
     */
    public static CharSequence summary(PurpleCore.Loaded state) {
        if (state == null || state.scheduleRules.isEmpty()) {
            return getString(R.string.PurpleScheduleNoRules);
        }
        if (!state.scheduleEnabled) {
            return getString(R.string.PurpleScheduleOff);
        }
        if (state.clock.schedulePaused) {
            return getString(R.string.PurpleSchedulePaused2);
        }
        final String count =
                formatPluralString("PurpleScheduleRuleCount", state.scheduleRules.size());
        final PurpleCore.ScheduleRule now = ruleAt(state, state.scheduleNow);
        // The clock line alone once there is one: a settings row's value has
        // room for about twenty characters, and "1 rule, now: work until..."
        // lost the only part of it worth reading. The count is the first
        // thing the schedule screen itself shows.
        if (now != null) {
            return formatString(R.string.PurpleScheduleNow,
                    now.preset, timeText(now.till));
        }
        final PurpleCore.ScheduleRule next = nextRule(state.scheduleRules);
        if (next == null) {
            return count;
        }
        return formatString(R.string.PurpleScheduleNext,
                next.preset, timeText(next.from));
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
            write(PurpleWriter.setTableBool("schedule", "enabled_p", !item.checked, "schedule switch"));
            return;
        }
        if (item.id == ROW_PAUSED) {
            // Not a splice: this one lives in state.toml and has its own writer,
            // which reloads for itself.
            PurpleGate.setSchedulePaused(!item.checked, 0);
            refresh();
            return;
        }
        if (item.id == ROW_ADD) {
            editRule(null);
            return;
        }
        if (item.id >= RULE_BASE) {
            // Re-read rather than trusting the row: a reload may have landed
            // between the draw and the tap, and every write below is fenced on
            // what the rule says right now.
            final PurpleCore.Loaded fresh = PurpleGate.state();
            final PurpleCore.ScheduleRule rule = (fresh == null)
                    ? null
                    : ruleAt(fresh.scheduleRules, "", item.id - RULE_BASE);
            if (rule == null) {
                refresh();
                return;
            }
            if (!onSwitch(view, x)) {
                editRule(rule);
                return;
            }
            // The switch alone: only enabled_p moves, and every other key is
            // written back exactly as read - which is also what makes the
            // expected fingerprint match.
            write(PurpleWriter.setScheduleRule(
                    rule.ruleset, rule.index, rule.from, rule.till, rule.preset,
                    !rule.enabled, rule.days, rule.from, rule.till, rule.preset,
                    "schedule rule switch"));
        }
    }

    /**
     * Whether a tap landed on the row's switch rather than on the row.
     *
     * The cell hands out its own switch, so the split is asked of the view that
     * drew it instead of guessed from a width this screen would have to keep in
     * step with the cell.
     */
    private static boolean onSwitch(View view, float x) {
        if (!(view instanceof NotificationsCheckCell)) {
            return false;
        }
        final View box = ((NotificationsCheckCell) view).getCheckBox();
        return box != null && x >= box.getLeft() && x <= box.getRight();
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

    // ---- the rule editor -----------------------------------------------------

    /**
     * Opens the editor over one rule, or over nothing for a new one.
     *
     * The rule is re-read from the gate at Save rather than trusted from here,
     * and the fingerprint sent with the write is the one this dialog was opened
     * on - so a file that moved while the dialog stood open is refused by the
     * core instead of being quietly overwritten.
     */
    private void editRule(PurpleCore.ScheduleRule existing) {
        final Context context = getParentActivity();
        final PurpleCore.Loaded state = PurpleGate.state();
        if (context == null || state == null) {
            return;
        }

        final boolean[] days = new boolean[8];
        if (existing != null) {
            for (int a = 0; a < existing.days.length; ++a) {
                if (existing.days[a] >= 1 && existing.days[a] <= 7) {
                    days[existing.days[a]] = true;
                }
            }
        } else {
            // Weekdays: the window somebody adding a work schedule almost always
            // wants, and the one that is most tedious to tick seven times.
            for (int a = 1; a <= 5; ++a) {
                days[a] = true;
            }
        }
        final int[] from = { existing != null ? existing.from : 9 * 60 };
        final int[] till = { existing != null ? existing.till : 17 * 60 };
        final boolean[] enabled = { existing == null || existing.enabled };
        final String[] preset = { existing != null
                ? existing.preset
                : (state.presets.isEmpty() ? NORMAL_PRESET : state.presets.get(0).name) };

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        for (int day = 1; day <= 7; ++day) {
            final int index = day;
            final CheckBoxCell cell = new CheckBoxCell(context, 1);
            cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
            cell.setText(dayName(day), "", days[day], true);
            cell.setPadding(AndroidUtilities.dp(LocaleController.isRTL ? 16 : 8), 0,
                    AndroidUtilities.dp(LocaleController.isRTL ? 8 : 16), 0);
            cell.setOnClickListener(v -> {
                days[index] = !days[index];
                ((CheckBoxCell) v).setChecked(days[index], true);
            });
            layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }

        final TextView fromRow = timeRow(context, layout, R.string.PurpleScheduleFrom, from[0]);
        fromRow.setOnClickListener(v -> pickTime(context, from[0], minutes -> {
            from[0] = minutes;
            fromRow.setText(getString(R.string.PurpleScheduleFrom) + "   " + timeText(minutes));
        }));
        final TextView tillRow = timeRow(context, layout, R.string.PurpleScheduleTo, till[0]);
        tillRow.setOnClickListener(v -> pickTime(context, till[0], minutes -> {
            till[0] = minutes;
            tillRow.setText(getString(R.string.PurpleScheduleTo) + "   " + timeText(minutes));
        }));

        // Normal first, and by hand: it is a preset name the file may use like
        // any other, but it is not written in settings.toml, so it is not in
        // the list the core hands over. Without it a window could turn Work
        // Mode on and no window could turn it off again.
        final ArrayList<RadioColorCell> radios = new ArrayList<>();
        addPresetRadio(context, layout, radios, preset,
                NORMAL_PRESET, getString(R.string.PurplePresetNormal));
        for (int a = 0, n = state.presets.size(); a < n; ++a) {
            final PurpleCore.PresetInfo info = state.presets.get(a);
            addPresetRadio(context, layout, radios, preset,
                    info.name, TextUtils.isEmpty(info.title) ? info.name : info.title);
        }

        final CheckBoxCell enabledCell = new CheckBoxCell(context, 1);
        enabledCell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        enabledCell.setText(getString(R.string.PurpleScheduleEnabledRow), "", enabled[0], false);
        enabledCell.setPadding(AndroidUtilities.dp(LocaleController.isRTL ? 16 : 8), 0,
                AndroidUtilities.dp(LocaleController.isRTL ? 8 : 16), 0);
        enabledCell.setOnClickListener(v -> {
            enabled[0] = !enabled[0];
            enabledCell.setChecked(enabled[0], true);
        });
        layout.addView(enabledCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(existing == null
                ? R.string.PurpleScheduleRuleNew
                : R.string.PurpleScheduleRuleEdit));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save),
                (dialog, which) -> saveRule(existing, days, from[0], till[0], enabled[0], preset[0]));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        if (existing != null) {
            builder.setNeutralButton(getString(R.string.Delete),
                    (dialog, which) -> confirmDelete(existing));
        }
        showDialog(builder.create());
    }

    /** The name the core gives the no-preset preset, spelled as the file does. */
    private static final String NORMAL_PRESET = "normal";

    private void addPresetRadio(
            Context context,
            LinearLayout layout,
            ArrayList<RadioColorCell> radios,
            String[] chosen,
            String name,
            CharSequence label) {
        final RadioColorCell cell = new RadioColorCell(context);
        cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
        cell.setTextAndValue(label, name.equalsIgnoreCase(chosen[0]));
        cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> {
            chosen[0] = name;
            for (int b = 0; b < radios.size(); ++b) {
                radios.get(b).setChecked(radios.get(b) == v, true);
            }
        });
        radios.add(cell);
        layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
    }

    private TextView timeRow(Context context, LinearLayout layout, int label, int minutes) {
        final TextView row = new TextView(context);
        row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        row.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        row.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        row.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        row.setText(getString(label) + "   " + timeText(minutes));
        layout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        return row;
    }

    private interface Picked {
        void run(int minutes);
    }

    private void pickTime(Context context, int minutes, Picked picked) {
        final int start = Math.max(0, minutes);
        // 24 hours whatever the phone is set to: the file writes "09:00" and a
        // picker that said "9:00 AM" would be describing a different notation
        // than the one being edited.
        new TimePickerDialog(
                context,
                (view, hour, minute) -> picked.run(hour * 60 + minute),
                (start / 60) % 24,
                start % 60,
                true).show();
    }

    private void saveRule(
            PurpleCore.ScheduleRule existing,
            boolean[] days,
            int from,
            int till,
            boolean enabled,
            String preset) {
        int count = 0;
        for (int a = 1; a <= 7; ++a) {
            if (days[a]) {
                ++count;
            }
        }
        if (count == 0) {
            error(getString(R.string.PurpleScheduleNoDays));
            return;
        }
        if (from == till) {
            // A window with no width is not a rule, and the core refuses it
            // too - said here so the answer arrives before the write.
            error(getString(R.string.PurpleScheduleSameTime));
            return;
        }
        if (TextUtils.isEmpty(preset)) {
            error(getString(R.string.PurpleScheduleNoPreset));
            return;
        }
        final int[] chosen = new int[count];
        int at = 0;
        for (int a = 1; a <= 7; ++a) {
            if (days[a]) {
                chosen[at++] = a;
            }
        }
        if (existing == null) {
            write(PurpleWriter.appendScheduleRule(
                    "", enabled, chosen, from, till, preset, "schedule rule added"));
            return;
        }
        write(PurpleWriter.setScheduleRule(
                existing.ruleset, existing.index, existing.from, existing.till,
                existing.preset, enabled, chosen, from, till, preset,
                "schedule rule edited"));
    }

    private void confirmDelete(PurpleCore.ScheduleRule rule) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PurpleScheduleDeleteTitle));
        builder.setMessage(ruleLine(rule));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> write(
                PurpleWriter.removeScheduleRule(rule.ruleset, rule.index, rule.from,
                        rule.till, rule.preset, "schedule rule removed")));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void error(CharSequence text) {
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createErrorBulletin(text).show();
        }
    }
}
