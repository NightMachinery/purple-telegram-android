/*
 * This is the source code of Purple Telegram for Android.
 *
 * One ruleset: which devices it is for, what it does about them, and the rules
 * inside it. Opened from every row of PurpleScheduleActivity, which is the list.
 *
 * The ruleset is addressed by NAME and never by position - its position moves
 * whenever one above it is added or taken away, and an index this screen read a
 * minute ago would then edit the wrong ruleset. The flat [[schedule.rules]]
 * array arrives here too, as the implicit ruleset, addressed with an empty name:
 * it has no device and no mode of its own, so those rows are not drawn for it.
 */

package org.telegram.ui;

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
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class PurpleRulesetActivity extends UniversalFragment
        implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_MODE_DISABLED = 1;
    private static final int ROW_MODE_ENABLED = 2;
    private static final int ROW_MODE_ALWAYS = 3;
    private static final int ROW_DEVICE = 4;
    private static final int ROW_OUTSIDE = 5;
    private static final int ROW_ADD = 6;
    private static final int ROW_DELETE = 7;

    /** Rule rows carry sourceIndex above this, so one id space serves both. */
    private static final int RULE_BASE = 1000;

    /**
     * The name every write on this screen goes through. Empty is the flat
     * array, which is exactly what the splice ops want for it.
     */
    private final String address;

    public PurpleRulesetActivity(String address) {
        this.address = (address == null) ? "" : address;
    }


    // Every reload the gate does ends in this signal, whichever account-wide
    // thing caused it. Rebuilding on it is what keeps this screen agreeing with
    // the file when the change did not come from a row on it.
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
        final PurpleCore.ScheduleRuleset ruleset = ruleset(PurpleGate.state());
        return (ruleset == null)
                ? getString(R.string.PurpleScheduleTitle)
                : PurpleScheduleActivity.rulesetTitle(ruleset);
    }

    /**
     * The ruleset this screen is on, found again on every rebuild.
     *
     * By address rather than kept in a field: the file can be rewritten under
     * an open screen - by the editor, an import, or the desktop's copy landing
     * in Saved Messages - and every row here has to be drawn from what the file
     * says now, not from what it said when the screen opened.
     */
    private PurpleCore.ScheduleRuleset ruleset(PurpleCore.Loaded state) {
        if (state == null) {
            return null;
        }
        for (int a = 0, n = state.scheduleRulesets.size(); a < n; ++a) {
            final PurpleCore.ScheduleRuleset ruleset = state.scheduleRulesets.get(a);
            if (ruleset.address.equalsIgnoreCase(address)) {
                return ruleset;
            }
        }
        return null;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final PurpleCore.Loaded state = PurpleGate.state();
        final PurpleCore.ScheduleRuleset ruleset = ruleset(state);
        if (ruleset == null) {
            // Deleted, renamed, or the file stopped parsing while this was open.
            // Said out loud rather than shown as an empty screen, which would
            // read as a ruleset that had lost its rules.
            items.add(UItem.asShadow(getString(R.string.PurpleScheduleRulesetGone)));
            return;
        }

        if (!ruleset.implicit) {
            items.add(UItem.asHeader(getString(R.string.PurpleScheduleMode)));
            items.add(mode(ROW_MODE_DISABLED, "disabled", ruleset.mode));
            items.add(mode(ROW_MODE_ENABLED, "enabled", ruleset.mode));
            items.add(mode(ROW_MODE_ALWAYS, "always", ruleset.mode));
            items.add(UItem.asShadow(getString(R.string.PurpleScheduleModeInfo)));

            items.add(UItem.asButton(ROW_DEVICE, getString(R.string.PurpleScheduleAppliesTo),
                    PurpleScheduleActivity.scopeLabel(state, ruleset.device)));
            items.add(UItem.asButton(ROW_OUTSIDE,
                    getString(R.string.PurpleScheduleOutsideRow),
                    (ruleset.outside == null)
                            ? getString(R.string.PurpleScheduleOutsideSame)
                            : PurpleScheduleActivity.presetLabel(state, ruleset.outside)));
            items.add(UItem.asShadow(chosenLine(state, ruleset)));
        }

        items.add(UItem.asHeader(getString(R.string.PurpleScheduleRulesHeader)));
        for (int a = 0, n = ruleset.rules.size(); a < n; ++a) {
            final PurpleCore.ScheduleRule rule = ruleset.rules.get(a);
            // A two-part row: the switch alone is enabled_p, and the rest of it
            // opens the editor. One row rather than two because they are one
            // rule, and the quick answer - "not this week" - should not cost a
            // dialog.
            final UItem row = UItem.asButtonCheck(RULE_BASE + rule.index,
                    PurpleScheduleActivity.window(rule),
                    PurpleScheduleActivity.presetLabel(state, rule.preset));
            row.checked = rule.enabled;
            items.add(row);
        }
        items.add(UItem.asButton(ROW_ADD, getString(R.string.PurpleScheduleAddRule)));
        items.add(UItem.asShadow(ruleset.implicit
                ? getString(R.string.PurpleScheduleImplicitInfo)
                : getString(R.string.PurpleScheduleRulesInfo)));

        if (!ruleset.implicit) {
            items.add(UItem.asButton(ROW_DELETE,
                    getString(R.string.PurpleScheduleDeleteRuleset)));
            items.add(UItem.asShadow(getString(R.string.PurpleScheduleDeleteRulesetInfo)));
        }
    }

    private static UItem mode(int id, String value, String current) {
        final UItem item = UItem.asRadio(id, PurpleScheduleActivity.modeLabel(value));
        item.checked = value.equalsIgnoreCase(current);
        return item;
    }

    /**
     * Whether this ruleset is one of the ones this device is running.
     *
     * Worth a line of its own because the answer is not readable off the two
     * rows above it: an `enabled' ruleset that applies to this device still
     * loses to a more specific one, and nothing else on the screen would say so.
     */
    private static CharSequence chosenLine(
            PurpleCore.Loaded state, PurpleCore.ScheduleRuleset ruleset) {
        if (state == null) {
            return "";
        }
        for (int a = 0, n = state.scheduleChosen.size(); a < n; ++a) {
            if (state.scheduleChosen.get(a).equalsIgnoreCase(ruleset.name)) {
                return getString(R.string.PurpleScheduleRulesetRunning);
            }
        }
        return getString(R.string.PurpleScheduleRulesetNotRunning);
    }

    // ---- taps ----------------------------------------------------------------

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final PurpleCore.Loaded state = PurpleGate.state();
        final PurpleCore.ScheduleRuleset ruleset = ruleset(state);
        if (ruleset == null) {
            refresh();
            return;
        }
        final Context context = getParentActivity();
        switch (item.id) {
        case ROW_MODE_DISABLED:
            write(PurpleWriter.setRulesetString(
                    ruleset.name, "mode", "disabled", "ruleset mode"));
            return;
        case ROW_MODE_ENABLED:
            write(PurpleWriter.setRulesetString(
                    ruleset.name, "mode", "enabled", "ruleset mode"));
            return;
        case ROW_MODE_ALWAYS:
            write(PurpleWriter.setRulesetString(
                    ruleset.name, "mode", "always", "ruleset mode"));
            return;
        case ROW_DEVICE:
            if (context != null) {
                PurpleScheduleActivity.showScopePicker(this, context, state, ruleset.device,
                        chosen -> write(PurpleWriter.setRulesetString(
                                ruleset.name, "device", chosen, "ruleset device")));
            }
            return;
        case ROW_OUTSIDE:
            if (context != null) {
                // An empty answer takes the key out of the file, which is how a
                // ruleset says "leave it to [schedule] outside" without writing
                // a preset name it does not mean.
                PurpleScheduleActivity.showPresetPicker(this, context, state,
                        getString(R.string.PurpleScheduleOutsideRow), ruleset.outside,
                        getString(R.string.PurpleScheduleOutsideSame),
                        chosen -> write(PurpleWriter.setRulesetString(
                                ruleset.name, "outside", chosen, "ruleset outside")));
            }
            return;
        case ROW_ADD:
            editRule(state, null);
            return;
        case ROW_DELETE:
            confirmDeleteRuleset(ruleset);
            return;
        default:
            break;
        }
        if (item.id >= RULE_BASE) {
            // Re-read rather than trusting the row: a reload may have landed
            // between the draw and the tap, and every write below is fenced on
            // what the rule says right now.
            final PurpleCore.ScheduleRule rule = PurpleScheduleActivity.ruleAt(
                    ruleset.rules, address, item.id - RULE_BASE);
            if (rule == null) {
                refresh();
                return;
            }
            if (!PurpleScheduleActivity.onSwitch(view, x)) {
                editRule(state, rule);
                return;
            }
            // The switch alone: only enabled_p moves, and every other key is
            // written back exactly as read - which is also what makes the
            // expected fingerprint match.
            write(PurpleWriter.setScheduleRule(
                    address, rule.index, rule.from, rule.till, rule.preset,
                    !rule.enabled, rule.days, rule.from, rule.till, rule.preset,
                    "schedule rule switch"));
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

    private void error(CharSequence text) {
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createErrorBulletin(text).show();
        }
    }

    private void confirmDeleteRuleset(PurpleCore.ScheduleRuleset ruleset) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PurpleScheduleDeleteRulesetTitle));
        builder.setMessage(ruleset.name);
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            final String failed = PurpleWriter.removeRuleset(
                    ruleset.name, "ruleset removed");
            if (failed != null) {
                write(failed);
                return;
            }
            // Nothing left on this screen to draw, and the list behind it has
            // already been rebuilt by the reload.
            finishFragment();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // ---- the rule editor -----------------------------------------------------

    /**
     * Opens the editor over one rule, or over nothing for a new one.
     *
     * The fingerprint sent with the write is the one this dialog was opened on,
     * so a file that moved while the dialog stood open is refused by the core
     * instead of being quietly overwritten.
     */
    private void editRule(PurpleCore.Loaded state, PurpleCore.ScheduleRule existing) {
        final Context context = getParentActivity();
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
                : (state.presets.isEmpty()
                        ? PurpleScheduleActivity.NORMAL_PRESET
                        : state.presets.get(0).name) };

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        for (int day = 1; day <= 7; ++day) {
            final int index = day;
            final CheckBoxCell cell = new CheckBoxCell(context, 1);
            cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
            cell.setText(PurpleScheduleActivity.dayName(day), "", days[day], true);
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
            fromRow.setText(getString(R.string.PurpleScheduleFrom) + "   "
                    + PurpleScheduleActivity.timeText(minutes));
        }));
        final TextView tillRow = timeRow(context, layout, R.string.PurpleScheduleTo, till[0]);
        tillRow.setOnClickListener(v -> pickTime(context, till[0], minutes -> {
            till[0] = minutes;
            tillRow.setText(getString(R.string.PurpleScheduleTo) + "   "
                    + PurpleScheduleActivity.timeText(minutes));
        }));

        final TextView presetRow = PurpleScheduleActivity.pickerRow(context, layout,
                getString(R.string.PurpleScheduleRulePreset),
                PurpleScheduleActivity.presetLabel(state, preset[0]));
        presetRow.setOnClickListener(v -> PurpleScheduleActivity.showPresetPicker(
                this, context, state, getString(R.string.PurpleScheduleRulePreset),
                preset[0], null,
                chosen -> {
                    preset[0] = chosen;
                    presetRow.setText(getString(R.string.PurpleScheduleRulePreset) + "   "
                            + PurpleScheduleActivity.presetLabel(state, chosen));
                }));

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
                    (dialog, which) -> confirmDeleteRule(existing));
        }
        showDialog(builder.create());
    }

    private TextView timeRow(Context context, LinearLayout layout, int label, int minutes) {
        final TextView row = new TextView(context);
        row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        row.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        row.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)
                | Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        row.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        row.setText(getString(label) + "   " + PurpleScheduleActivity.timeText(minutes));
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
                    address, enabled, chosen, from, till, preset, "schedule rule added"));
            return;
        }
        write(PurpleWriter.setScheduleRule(
                address, existing.index, existing.from, existing.till, existing.preset,
                enabled, chosen, from, till, preset,
                "schedule rule edited"));
    }

    private void confirmDeleteRule(PurpleCore.ScheduleRule rule) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.PurpleScheduleDeleteTitle));
        builder.setMessage(PurpleScheduleActivity.ruleLine(rule));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> write(
                PurpleWriter.removeScheduleRule(address, rule.index, rule.from,
                        rule.till, rule.preset, "schedule rule removed")));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }
}
