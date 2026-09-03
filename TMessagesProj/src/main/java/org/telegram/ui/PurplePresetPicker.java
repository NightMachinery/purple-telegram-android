/*
 * This is the source code of Purple Telegram for Android.
 *
 * The Work Mode preset picker: the one control that switches presets, reached
 * from the chat list menu and from Settings. It mirrors the desktop fork's
 * purple_preset_box.cpp - see docs/purple/work_mode.md, "Choosing a preset".
 *
 * There is no editing UI here on purpose. Everything the picker cannot do -
 * writing a preset, editing a list - is done in settings.toml, so the file's
 * path is printed at the bottom rather than a set of controls that would have
 * to keep up with the schema.
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;

import java.util.ArrayList;
import java.util.List;

public final class PurplePresetPicker {

    private PurplePresetPicker() {
    }

    /**
     * Shows the preset picker over {@code fragment}.
     *
     * Choosing a row is the whole of it: {@link PurpleGate#setPreset(String)}
     * writes state.toml, reloads and refreshes every chat list on its own, so
     * nothing here touches the model or the caller's list.
     *
     * @param fragment the fragment to attach the dialog to; a null one, or one
     *                 with no activity behind it, is ignored
     */
    public static void show(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        // Settings may never have been read on this run - the picker can be the
        // first thing that asks, on an account whose chat list has not drawn yet.
        PurpleGate.ensureLoaded();
        final PurpleCore.Loaded state = PurpleGate.state();
        if (state == null || state.presets == null || state.presets.isEmpty()) {
            showUnconfigured(fragment, activity, resourcesProvider);
            return;
        }
        final List<PurpleCore.PresetInfo> presets = state.presets;

        // Which row carries the tick. Normal is index 0, so a preset sits at
        // its index in the file plus one.
        //
        // When the active preset is not in the file at all - deleted, renamed,
        // or lost to a half-finished edit - no row is checked, deliberately, and
        // this is the one rule in here that is not a matter of taste. Checking
        // Normal instead would look tidier and would be a disaster: the
        // selection callback would fire and switch the account to Normal,
        // unhiding every chat the missing preset was hiding, over a typo
        // mid-edit. Nothing checked plus the line below it is the honest
        // picture. Same rule as the core's resolved_cache fallback, enforced
        // here in the UI.
        int active = -1;
        if (!state.activeMissing) {
            if (state.normal) {
                active = 0;
            } else {
                for (int a = 0; a < presets.size(); ++a) {
                    // Case-insensitively, the way the core matches preset names.
                    if (presets.get(a).name != null && presets.get(a).name.equalsIgnoreCase(state.preset)) {
                        active = a + 1;
                        break;
                    }
                }
            }
        }
        final int checked = active;

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(getString(R.string.PurpleWorkMode));

        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        builder.setView(layout);

        final int count = presets.size() + 1;
        for (int a = 0; a < count; ++a) {
            final PurpleCore.PresetInfo preset = a == 0 ? null : presets.get(a - 1);
            final boolean choosable = preset == null || preset.resolves;

            final RadioColorCell cell = new RadioColorCell(activity, resourcesProvider);
            cell.setPadding(dp(4), 0, dp(4), 0);
            cell.setCheckColor(
                    Theme.getColor(Theme.key_radioBackground, resourcesProvider),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
            cell.setTextAndText2AndValue(titleOf(preset), summaryOf(preset), a == checked);
            if (choosable) {
                cell.setBackground(Theme.createSelectorDrawable(
                        Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_ALL));
                // Normal is the bypass, which the gate spells as a null preset.
                final String name = preset == null ? null : preset.name;
                final boolean alreadyActive = a == checked;
                cell.setOnClickListener(v -> {
                    builder.getDismissRunnable().run();
                    if (!alreadyActive) {
                        PurpleGate.setPreset(name);
                    }
                });
            } else {
                // A preset the engine could not resolve says so in place of a
                // summary and cannot be chosen: applying it would hide whatever
                // the last working resolution hid, under a name that no longer
                // describes it.
                cell.setEnabled(false);
                cell.setAlpha(0.5f);
            }
            layout.addView(cell);
        }

        // The file's problems, under the choices they explain. Until these were
        // shown, a preset that silently did nothing because of a mistyped list
        // name looked exactly like one that was working.
        final List<String> errors = new ArrayList<>();
        if (state.activeMissing) {
            errors.add(formatString(R.string.PurplePresetActiveMissing, state.preset));
        }
        if (!state.ok && !TextUtils.isEmpty(state.error)) {
            errors.add(formatString(R.string.PurplePresetError, state.error));
        }
        if (!errors.isEmpty()) {
            // An error and a warning read identically in body text, and the
            // difference is the whole point of having two words: an error means
            // the file did not load, a warning means it loaded with something
            // ignored. The prefixes stay, so this does not rest on colour alone.
            layout.addView(note(activity, TextUtils.join("\n", errors),
                    Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)));
        }
        // A preset list that came out of a copy the user cannot see, because
        // the file they can see is gone, is exactly the kind of thing that has
        // to be said rather than quietly worked around.
        if (PurpleGate.usedLastGood()) {
            layout.addView(note(activity, getString(R.string.PurplePresetLastGood),
                    Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)));
        }
        if (state.warnings != null && !state.warnings.isEmpty()) {
            final List<String> lines = new ArrayList<>();
            for (final String warning : state.warnings) {
                lines.add(formatString(R.string.PurplePresetWarning, warning));
            }
            layout.addView(note(activity, TextUtils.join("\n", lines),
                    Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider)));
        }
        layout.addView(note(activity,
                formatString(R.string.PurplePresetFileNote, PurpleSettings.settingsFile().getAbsolutePath()),
                Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider)));

        builder.setNegativeButton(getString(R.string.Close), null);
        fragment.showDialog(builder.create());
    }

    /** The title a row carries: the preset's own name for its tab, or Normal. */
    private static String titleOf(PurpleCore.PresetInfo preset) {
        if (preset == null) {
            return getString(R.string.PurplePresetNormal);
        }
        // The name the preset's tab would carry, not the TOML key underneath it.
        // A picker offering "work" above a tab reading "Work" is one thing with
        // two names. The key is only a fallback for a preset the core gave no
        // title, which should not happen.
        return TextUtils.isEmpty(preset.title) ? preset.name : preset.title;
    }

    /**
     * What a preset will do, in the words a chat list would use, so the choice
     * can be made from the picker rather than from memory of what was typed.
     *
     * The counts are what gets <i>through</i>, never what is hidden: under this
     * model anything a preset does not name is hidden by falling through, so
     * counting the hidden would be counting the whole account.
     */
    private static String summaryOf(PurpleCore.PresetInfo preset) {
        if (preset == null) {
            // Normal is a bypass rather than a permissive preset, and saying so
            // is the difference between "the one that allows everything" and
            // "the one that is not running".
            return getString(R.string.PurplePresetNormalInfo);
        }
        if (!preset.resolves) {
            return getString(R.string.PurplePresetUnresolved);
        }
        final List<String> parts = new ArrayList<>();
        parts.add(preset.letsThrough > 0
                ? formatPluralString("PurplePresetLetsThrough", preset.letsThrough)
                : getString(R.string.PurplePresetLetsNothingThrough));
        if (preset.silences > 0) {
            parts.add(formatPluralString("PurplePresetSilences", preset.silences));
        }
        if (preset.gated > 0) {
            parts.add(formatPluralString("PurplePresetGated", preset.gated));
        }
        return TextUtils.join(", ", parts);
    }

    /** One line of note under the rows, in {@code color}. */
    private static TextView note(Activity activity, CharSequence text, int color) {
        final TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(color);
        view.setText(text);
        view.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);
        // 24dp matches the AlertDialog's own message text, which these lines sit
        // under; the radio rows indent themselves past it.
        view.setPadding(dp(24), dp(8), dp(24), 0);
        return view;
    }

    /**
     * What the picker says with nothing to pick. An empty single-choice list
     * would read as "your presets are gone" rather than "you have not written
     * any", so it names the file instead.
     */
    private static void showUnconfigured(BaseFragment fragment, Activity activity,
            Theme.ResourcesProvider resourcesProvider) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(getString(R.string.PurpleWorkMode));
        builder.setMessage(formatString(R.string.PurplePresetNotConfigured,
                PurpleSettings.settingsFile().getAbsolutePath()));
        builder.setPositiveButton(getString(R.string.OK), null);
        fragment.showDialog(builder.create());
    }
}
