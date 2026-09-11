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
import android.view.HapticFeedbackConstants;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.LayoutHelper;

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

        // Peek and the schedule pause: the two things that move on a clock
        // rather than on an edit, and the two the file cannot express because
        // they are decisions about right now. This is where someone would look
        // for them, next to the preset they act on.
        addClockRows(activity, layout, resourcesProvider, state);

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
        // The countdown below runs only while the box is open, which is the
        // only time there is anyone to read it. Handed to showDialog rather
        // than set on the dialog, because showDialog installs a listener of its
        // own over anything already there.
        fragment.showDialog(builder.create(), d -> cancelTick());
    }

    /**
     * The one running countdown, so a box opened twice does not tick twice.
     *
     * A field rather than a capture because the dismiss listener has to cancel
     * whatever the last box installed, and there is only ever one box.
     */
    private static final Runnable[] PEEK_TICK = new Runnable[1];

    /**
     * Adds the peek checkbox and its chips, and the schedule pause when the
     * file describes a schedule at all - a switch that holds off nothing
     * explains nothing.
     */
    private static void addClockRows(Activity activity, LinearLayout layout,
            Theme.ResourcesProvider resourcesProvider, PurpleCore.Loaded state) {
        final boolean pausable = state.clock.scheduleConfigured;

        final CheckBoxCell peek = new CheckBoxCell(activity, 1, resourcesProvider);
        peek.setPadding(dp(4), 0, dp(4), 0);

        // The same switch with a number on it. The row is the core's
        // PeekDetentsSeconds() with "until I stop" one position past the end of
        // it, which is exactly where the core puts a length of zero.
        final PeekChips chips = new PeekChips(
                activity, resourcesProvider, state.clock.peekDetents);

        // Follows the gate rather than the click, so a peek ended by its own
        // timer moves the tick here too - and the lit chip with it, since it
        // follows what is left rather than what was asked for.
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                final PurpleCore.Loaded now = PurpleGate.state();
                final boolean peeking = now != null && now.clock.peeking;
                // No divider under the checkbox any more, whatever follows it:
                // the chips below are the same control, and a line drawn
                // between them would cut it in half.
                peek.setText(peekText(now), null, peeking, false);
                chips.refresh(now);
                if (peeking && now.clock.peekDeadline > 0) {
                    AndroidUtilities.runOnUIThread(this, 1000);
                }
            }
        };
        cancelTick();
        PEEK_TICK[0] = tick;

        if (state.normal) {
            // Says why it does nothing rather than sitting there greyed out with
            // no explanation. Starting a peek here would leave one running that
            // no chat list could show the end of. The chips are dimmed under the
            // sentence that explains them rather than carrying one of their own.
            peek.setEnabled(false);
            peek.setAlpha(0.5f);
        } else {
            peek.setBackground(Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_ALL));

            // A tap while a peek is running EXTENDS it, where the desktop's
            // checkbox ends it. A phone has no hotkey to carry the extension,
            // and a tap on the control while the chats are back is nearly always
            // "not yet" rather than "done" - the peek is running because
            // something is still being looked at. Ending it is the long press.
            //
            // It still ends the peek when there is nothing left to extend: the
            // hour cap spent, or no clock on it to move. A control that can
            // start something it cannot stop is worse than one that means two
            // things.
            peek.setOnClickListener(v -> {
                final PurpleCore.Loaded now = PurpleGate.state();
                if (now == null) {
                    return;
                }
                // The tap length is [peek] tap, falling back to auto_off in the
                // core. Read from the load every time rather than captured, so
                // an edit to the file lands on the next tap.
                final int tap = now.clock.peekTap;
                if (!now.clock.peeking) {
                    PurpleGate.startPeek(tap);
                } else if (!PurpleGate.extendPeek(tap).extended) {
                    final boolean untilStopped = now.clock.peekUntilStopped;
                    PurpleGate.stopPeek();
                    Toast.makeText(activity, getString(untilStopped
                                    ? R.string.PurplePeekOver
                                    : R.string.PurplePeekOverCapped),
                            Toast.LENGTH_SHORT).show();
                }
                restartTick(tick);
            });

            // The way out, since the tap no longer is one. Not consumed when
            // nothing is running: a long press that silently does nothing on a
            // control that is off would read as the gesture being broken rather
            // than as there being nothing to end.
            peek.setOnLongClickListener(v -> {
                final PurpleCore.Loaded now = PurpleGate.state();
                if (now == null || !now.clock.peeking) {
                    return false;
                }
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                PurpleGate.stopPeek();
                restartTick(tick);
                return true;
            });

            chips.setOnPick(seconds -> {
                // A chip pressed while a peek is running RESTARTS it at that
                // length rather than adding to it: a chip that says "5 min" and
                // leaves eleven is a chip lying about what it did.
                PurpleGate.startPeek(seconds);
                restartTick(tick);
            });
        }
        layout.addView(peek);
        layout.addView(chips, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        tick.run();

        if (!pausable) {
            return;
        }
        final CheckBoxCell pause = new CheckBoxCell(activity, 1, resourcesProvider);
        pause.setPadding(dp(4), 0, dp(4), 0);
        pause.setText(getString(R.string.PurpleSchedulePause), null,
                state.clock.schedulePaused, false);
        pause.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_ALL));
        pause.setOnClickListener(v -> {
            final boolean wanted = !pause.isChecked();
            // Zero: the box's checkbox is a plain pause, with no moment on it.
            // Pausing until a date is a decision with a picker behind it, and
            // it lives on the schedule screen where there is room to say so.
            if (PurpleGate.setSchedulePaused(wanted, 0)) {
                pause.setChecked(wanted, true);
            }
        });
        layout.addView(pause);
    }

    /**
     * Stops whatever countdown the last box installed.
     *
     * The null check is not decoration: {@code removeCallbacks(null)} matches
     * every pending callback on the handler, so cancelling a countdown that was
     * never started would cancel the whole app's posted work.
     */
    private static void cancelTick() {
        if (PEEK_TICK[0] != null) {
            AndroidUtilities.cancelRunOnUIThread(PEEK_TICK[0]);
            PEEK_TICK[0] = null;
        }
    }

    /**
     * Redraws the row now rather than up to a second from now.
     *
     * Every gesture here changes what the label and the chips say, and the
     * countdown is what would otherwise carry the change - so a stop, which
     * cancels the countdown, would leave the last frame of it on screen.
     */
    private static void restartTick(Runnable tick) {
        AndroidUtilities.cancelRunOnUIThread(tick);
        tick.run();
    }

    /**
     * The lengths a peek can be started at, as a row of chips under the
     * checkbox.
     *
     * Its own small widget rather than the screen-time screen's ChipRow: that
     * one paints itself with the list background and reads the theme without a
     * resources provider, both of which are wrong inside a dialog. What is
     * shared is the shape - a HorizontalScrollView of rounded TextViews - and
     * it is fifteen lines of it.
     *
     * The lengths themselves are never written here. They arrive from the
     * core's PeekDetentsSeconds() through the load, because two hand-written
     * lists is how a phone's chips and a desktop's row come to offer different
     * minutes for the same feature.
     */
    private static final class PeekChips extends HorizontalScrollView {

        private final Theme.ResourcesProvider resourcesProvider;
        private final LinearLayout row;

        /** The detents, with zero - "until I stop" - one position past them. */
        private final List<Integer> lengths = new ArrayList<>();
        private final List<TextView> chips = new ArrayList<>();

        private Utilities.Callback<Integer> onPick;
        private int lit = -1;
        private boolean enabled = true;

        PeekChips(Activity activity, Theme.ResourcesProvider resourcesProvider,
                List<Integer> detents) {
            super(activity);
            this.resourcesProvider = resourcesProvider;
            setHorizontalScrollBarEnabled(false);
            setClipToPadding(false);
            row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(20), dp(2), dp(20), dp(10));
            addView(row, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            if (detents != null) {
                lengths.addAll(detents);
            }
            // Zero is the only thing that means "no clock on it", and the core
            // puts it one position past the last detent rather than somewhere
            // else entirely - so the row is one continuous set of choices.
            lengths.add(0);
            for (int i = 0; i < lengths.size(); ++i) {
                final int seconds = lengths.get(i);
                final TextView chip = new TextView(activity);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                chip.setText(chipText(seconds));
                chip.setSingleLine(true);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(12), dp(5), dp(12), dp(5));
                chip.setOnClickListener(v -> {
                    if (onPick != null) {
                        onPick.run(seconds);
                    }
                });
                final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LayoutHelper.WRAP_CONTENT, dp(28));
                params.rightMargin = dp(6);
                row.addView(chip, params);
                chips.add(chip);
            }
            paint();
        }

        void setOnPick(Utilities.Callback<Integer> callback) {
            onPick = callback;
        }

        /**
         * Which chip is lit, and whether any of them may be pressed.
         *
         * It follows what is LEFT rather than what was asked for, so a
         * five-minute peek with ninety seconds on it lights the two-minute
         * chip. Nothing anywhere remembers the length a peek was started with -
         * state.toml holds a deadline and that is all - and inventing a memory
         * for it so that a highlight could sit still would be storing a fact to
         * make a picture tidier.
         */
        void refresh(PurpleCore.Loaded state) {
            final boolean normal = (state == null) || state.normal;
            final int wanted;
            if (normal || !state.clock.peeking) {
                wanted = -1;
            } else if (state.clock.peekUntilStopped) {
                wanted = lengths.size() - 1;
            } else {
                final long left = state.clock.peekDeadline
                        - System.currentTimeMillis() / 1000L;
                // At least a second, because the rounding reads zero as "until
                // I stop": the last second of a countdown must not light the
                // chip that means the opposite of ending.
                wanted = PurpleCore.peekDetentIndex((int) Math.max(left, 1L));
            }
            if (wanted == lit && (!normal) == enabled) {
                return;
            }
            lit = wanted;
            enabled = !normal;
            paint();
        }

        private void paint() {
            final int on = Theme.getColor(
                    Theme.key_featuredStickers_addButton, resourcesProvider);
            final int off = Theme.multAlpha(Theme.getColor(
                    Theme.key_dialogTextGray2, resourcesProvider), 0.12f);
            for (int i = 0; i < chips.size(); ++i) {
                final TextView chip = chips.get(i);
                final boolean checked = (i == lit);
                chip.setTextColor(checked
                        ? Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider)
                        : Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
                chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                        dp(14), checked ? on : off,
                        Theme.getColor(Theme.key_listSelector, resourcesProvider)));
                chip.setEnabled(enabled);
            }
            setAlpha(enabled ? 1f : 0.5f);
        }

        /**
         * A length as a chip names it, in the core's own units: minutes up to
         * the hour, and the position past the last detent is the one with no
         * clock on it.
         */
        private static CharSequence chipText(int seconds) {
            if (seconds <= 0) {
                return getString(R.string.PurplePeekChipUntilStop);
            } else if (seconds >= 3600 && (seconds % 3600) == 0) {
                return formatString(R.string.PurplePeekChipHours, seconds / 3600);
            }
            return formatString(R.string.PurplePeekChipMinutes, seconds / 60);
        }
    }

    /**
     * What the peek row says, which is three different sentences.
     *
     * A peek ends on a clock and nothing anywhere else says when, so the label
     * counts it down. "Until you turn it off" is a fourth state rather than a
     * countdown that never moves: {@code auto_off = "off"} really does run until
     * it is turned off by hand.
     */
    private static String peekText(PurpleCore.Loaded state) {
        if (state == null || state.normal) {
            return getString(R.string.PurplePeekNormal);
        }
        if (!state.clock.peeking) {
            return getString(R.string.PurplePeek);
        }
        final long deadline = state.clock.peekDeadline;
        if (deadline <= 0) {
            return getString(R.string.PurplePeekingUntilOff);
        }
        final long left = Math.max(deadline - System.currentTimeMillis() / 1000L, 0L);
        return formatString(R.string.PurplePeekingLeft, remaining((int) left));
    }

    /** A count of seconds as a clock reads it: 1:23, or 45s under a minute. */
    private static String remaining(int seconds) {
        return (seconds < 60)
                ? (seconds + "s")
                : String.format(LocaleController.getInstance().getCurrentLocale(),
                        "%d:%02d", seconds / 60, seconds % 60);
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
