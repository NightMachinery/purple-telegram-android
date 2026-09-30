/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleSyncCore;
import org.telegram.messenger.purple.PurpleSyncRunner;
import org.telegram.messenger.purple.PurpleSyncSettingsFile;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

final class PurpleSyncReviewDialog {
    interface Chosen {
        void onChosen(String key);
    }

    static final class ChangePreview {
        private final PurpleSyncRunner runner;
        private final TextView summary;
        private final TextView toggle;
        private final TextView lines;
        private PurpleSyncCore.Diff diff;
        private boolean shown;
        private int asked;

        private ChangePreview(PurpleSyncRunner runner, TextView summary, TextView toggle,
                TextView lines) {
            this.runner = runner;
            this.summary = summary;
            this.toggle = toggle;
            this.lines = lines;
        }

        static ChangePreview add(Context context, LinearLayout layout, PurpleSyncRunner runner) {
            final TextView summary = label(context, layout, "", false);
            final TextView toggle = new TextView(context);
            toggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            toggle.setTextColor(Theme.getColor(Theme.key_dialogTextLink));
            toggle.setGravity(gravity() | Gravity.CENTER_VERTICAL);
            toggle.setPadding(dp(24), dp(8), dp(24), dp(8));
            toggle.setBackground(Theme.getSelectorDrawable(false));
            layout.addView(toggle, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            final TextView lines = new TextView(context);
            lines.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            lines.setTypeface(Typeface.MONOSPACE);
            lines.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            lines.setBackgroundColor(Theme.getColor(Theme.key_dialogBackgroundGray));
            lines.setGravity(Gravity.LEFT);
            lines.setTextDirection(View.TEXT_DIRECTION_LTR);
            lines.setPadding(dp(12), dp(8), dp(12), dp(8));
            lines.setVisibility(View.GONE);
            layout.addView(lines, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 4));
            final ChangePreview preview = new ChangePreview(runner, summary, toggle, lines);
            toggle.setOnClickListener(v -> {
                preview.shown = !preview.shown;
                preview.refreshLines();
            });
            preview.show(null);
            return preview;
        }

        void compare(byte[] before, byte[] after) {
            final int request = ++asked;
            show(null);
            if (runner == null) {
                return;
            }
            runner.diff(before, after, result -> {
                if (request == asked) {
                    show(result);
                }
            });
        }

        void show(PurpleSyncCore.Diff result) {
            diff = (result != null && result.isValid()) ? result : null;
            if (diff == null) {
                summary.setText("");
                summary.setVisibility(View.GONE);
                toggle.setVisibility(View.GONE);
                lines.setVisibility(View.GONE);
                return;
            }
            summary.setText(TextUtils.join("\n", PurpleSyncText.changeLines(diff)));
            summary.setVisibility(View.VISIBLE);
            toggle.setVisibility(View.VISIBLE);
            refreshLines();
        }

        private void refreshLines() {
            toggle.setText(getString(shown
                    ? R.string.PurpleSyncHideLines : R.string.PurpleSyncShowLines));
            if (shown && diff != null) {
                lines.setText(PurpleSyncText.diffText(diff));
            }
            lines.setVisibility(shown && diff != null ? View.VISIBLE : View.GONE);
        }
    }

    private PurpleSyncReviewDialog() {
    }

    static int gravity() {
        return LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
    }

    static TextView label(Context context, LinearLayout layout, CharSequence text,
            boolean secondary) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, secondary ? 14 : 16);
        view.setTextColor(Theme.getColor(secondary
                ? Theme.key_dialogTextGray2 : Theme.key_dialogTextBlack));
        view.setGravity(gravity());
        view.setText(text);
        layout.addView(view, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 4));
        return view;
    }

    static void show(BaseFragment fragment, PurpleSyncRunner runner,
            PurpleSyncRunner.Check check, Chosen chosen) {
        final Context context = fragment.getParentActivity();
        if (context == null || check == null) {
            return;
        }
        final PurpleSyncCore.Review review = check.review;
        final boolean update = review.verdict == PurpleSyncCore.Verdict.UpdateReady;
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(update
                ? formatString(R.string.PurpleSyncUpdateFrom,
                        PurpleSyncText.deviceName(PurpleSyncText.firstDevice(review)))
                : getString(review.bound
                        ? R.string.PurpleSyncActionChoose : R.string.PurpleSyncActionJoin));
        final List<PurpleSyncCore.Choice> choices = review.choices;
        if (choices.isEmpty()) {
            builder.setMessage(PurpleSyncText.joined(PurpleSyncText.choiceIntro(review),
                    getString(R.string.PurpleSyncNothingToChoose)));
            builder.setPositiveButton(getString(R.string.Close), null);
            fragment.showDialog(builder.create());
            return;
        }
        builder.setMessage(PurpleSyncText.choiceIntro(review));

        final byte[] local = (check.local != null
                && check.local.status == PurpleSyncSettingsFile.Status.Present)
                ? check.local.bytes : new byte[0];
        final PurpleSyncCore.Head first = (choices.get(0).key != null)
                ? review.heads.get(choices.get(0).head) : null;

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<RadioColorCell> radios = new ArrayList<>();
        final int[] selected = { 0 };
        final Runnable[] select = new Runnable[1];
        if (update) {
            label(context, layout, optionLabel(review, choices.get(0)), false);
        } else {
            for (int i = 0; i != choices.size(); ++i) {
                final int index = i;
                final RadioColorCell cell = new RadioColorCell(context);
                cell.setPadding(dp(4), 0, dp(4), 0);
                cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                        Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
                final PurpleSyncCore.Choice choice = choices.get(i);
                if (choice.key == null) {
                    cell.setTextAndValue(getString(R.string.PurpleSyncOptionThisDevice), i == 0);
                } else {
                    final PurpleSyncCore.Head head = review.heads.get(choice.head);
                    cell.setTextAndText2AndValue(PurpleSyncText.deviceName(head.name),
                            formatString(R.string.PurpleSyncOptionChanged,
                                    PurpleSyncText.recordTime(head.at)), i == 0);
                }
                cell.setBackground(Theme.getSelectorDrawable(false));
                cell.setOnClickListener(v -> {
                    if (selected[0] == index) {
                        return;
                    }
                    selected[0] = index;
                    for (int b = 0; b != radios.size(); ++b) {
                        radios.get(b).setChecked(b == index, true);
                    }
                    select[0].run();
                });
                radios.add(cell);
                layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                        LayoutHelper.WRAP_CONTENT));
            }
        }
        final TextView compare = label(context, layout, "", false);
        final ChangePreview preview = ChangePreview.add(context, layout, runner);
        final TextView newer = label(context, layout,
                getString(R.string.PurpleSyncNewerSchema), true);
        final TextView disclosure = label(context, layout, "", true);

        final boolean[] done = { false };
        builder.setView(layout);
        builder.setPositiveButton(PurpleSyncText.choiceButton(review,
                !update && choices.get(0).publishes), (dialog, which) -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            chosen.onChosen(choices.get(selected[0]).key);
        });
        builder.setNegativeButton(getString(update
                ? R.string.PurpleSyncNotNow : R.string.Cancel), null);
        final AlertDialog dialog = builder.create();

        select[0] = () -> {
            final PurpleSyncCore.Choice choice = choices.get(selected[0]);
            if (choice.key != null) {
                final PurpleSyncCore.Head head = review.heads.get(choice.head);
                compare.setText(getString(update
                        ? R.string.PurpleSyncCompareApply : R.string.PurpleSyncCompareUse));
                preview.compare(local, bytes(head.text));
                newer.setVisibility(head.newerSchema ? View.VISIBLE : View.GONE);
            } else {
                compare.setText(first != null
                        ? formatString(R.string.PurpleSyncCompareKeep,
                                PurpleSyncText.deviceName(first.name))
                        : getString(R.string.PurpleSyncCompareKeepAlone));
                preview.compare(first != null ? bytes(first.text) : new byte[0], local);
                newer.setVisibility(View.GONE);
            }
            final boolean publishes = !update && choice.publishes;
            disclosure.setText(getString(publishes
                    ? R.string.PurpleSyncDisclosureShare : R.string.PurpleSyncDisclosureJoin)
                    + " " + PurpleSyncText.disclosure());
            disclosure.setVisibility(publishes || !review.bound ? View.VISIBLE : View.GONE);
            final View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (button instanceof TextView) {
                ((TextView) button).setText(PurpleSyncText.choiceButton(review, publishes));
            }
        };
        select[0].run();
        fragment.showDialog(dialog);
    }

    private static String optionLabel(PurpleSyncCore.Review review,
            PurpleSyncCore.Choice choice) {
        if (choice.key == null) {
            return getString(R.string.PurpleSyncOptionThisDevice);
        }
        final PurpleSyncCore.Head head = review.heads.get(choice.head);
        return formatString(R.string.PurpleSyncOptionDevice,
                PurpleSyncText.deviceName(head.name), PurpleSyncText.recordTime(head.at));
    }

    private static byte[] bytes(byte[] text) {
        return (text != null) ? text : new byte[0];
    }
}
