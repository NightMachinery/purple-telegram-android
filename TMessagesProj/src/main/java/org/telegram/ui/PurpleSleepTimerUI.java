package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleSleepTimer;
import org.telegram.messenger.purple.PurpleSleepTimerRules;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import java.util.Locale;
import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

/** Shared stock player dropdown and duration wheels, without wall-clock formatting. */
public final class PurpleSleepTimerUI {
    public static final int VIEWER_TIMER = 0x70750051, VIEWER_END = 0x70750052;
    private static final int FIRST = 0x70750060;
    private PurpleSleepTimerUI() {}
    public static boolean localOnlyUpdate() { return PurpleSleepTimer.get().localPause(); }
    public static boolean heldSongControls(org.telegram.messenger.MessageObject message,
            View progress, View seek, View play) {
        if (!org.telegram.messenger.purple.PurpleSleepTimerAudio.heldSong(message)) return false;
        progress.setVisibility(View.INVISIBLE);
        seek.setVisibility(View.VISIBLE);
        play.setEnabled(true);
        return true;
    }

    public static void layoutBeside(View button, View speed) {
        if (button == null || speed == null || speed.getVisibility() != View.VISIBLE) return;
        final int right = speed.getLeft();
        button.layout(right - button.getMeasuredWidth(), speed.getTop(), right, speed.getBottom());
    }

    public static String duration(int minutes) {
        if (minutes < 60) return LocaleController.formatPluralString("Minutes", minutes);
        final int h = minutes / 60, m = minutes % 60;
        return m == 0 ? LocaleController.formatPluralString("Hours", h)
                : LocaleController.formatString(R.string.PurpleSleepHoursMinutes,
                    LocaleController.formatPluralString("Hours", h), LocaleController.formatPluralString("Minutes", m));
    }

    public static String compact() {
        final long seconds = (PurpleSleepTimer.get().remaining() + 999) / 1000;
        if (seconds < 60) return LocaleController.formatString(R.string.PurpleSleepSecondsShort, seconds);
        final long minutes = (seconds + 59) / 60;
        return minutes % 60 == 0 ? LocaleController.formatString(R.string.PurpleSleepHoursShort, minutes / 60)
                : LocaleController.formatString(R.string.PurpleSleepMinutesShort, minutes);
    }

    private static int endLabel(Slot slot) {
        final Item item = PurpleSleepTimer.get().current(slot);
        return item == null || item.kind == Kind.SONG ? R.string.PurpleSleepEndTrack
                : item.kind == Kind.VIDEO ? R.string.PurpleSleepEndVideo : R.string.PurpleSleepEndMessage;
    }

    public static String status(Slot slot) {
        final PurpleSleepTimer timer = PurpleSleepTimer.get();
        if (timer.mode() == Mode.OFF) return getString(R.string.PurpleSleepOff);
        if (timer.mode() == Mode.END) {
            final Item item = timer.boundItem();
            return getString(item != null && item.kind == Kind.SONG ? R.string.PurpleSleepAfterTrack
                    : item != null && item.kind == Kind.VIDEO ? R.string.PurpleSleepAfterVideo : R.string.PurpleSleepAfterMessage);
        }
        final long seconds = (timer.remaining() + 999) / 1000;
        final String clock = "\u2066" + String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60) + "\u2069";
        return LocaleController.formatString(R.string.PurpleSleepPausingIn, clock);
    }

    public static void installFeedback() {
        PurpleSleepTimer.get().feedback(event -> {
            final PurpleSleepTimer timer = PurpleSleepTimer.get();
            final String text;
            if (event == PurpleSleepTimer.NOTHING) text = getString(R.string.PurpleSleepNothing);
            else if (event == PurpleSleepTimer.CANCELLED) text = getString(R.string.PurpleSleepCancelled);
            else if (event == PurpleSleepTimer.EXPIRED) text = getString(R.string.PurpleSleepExpired);
            else if (timer.mode() == Mode.END) text = getString(R.string.PurpleSleepStartsAtEnd);
            else text = LocaleController.formatString(event == PurpleSleepTimer.CHANGED
                        ? R.string.PurpleSleepTimeLeft : R.string.PurpleSleepStartsIn,
                        duration((int) Math.max(1, (timer.remaining() + MINUTE - 1) / MINUTE)));
            BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, text).show();
        });
    }

    public static final class Button extends ActionBarMenuItem implements PurpleSleepTimer.Listener {
        private final Theme.ResourcesProvider provider;
        private final boolean compactOnly;
        private final Slot slot;
        private final TextView countdown;
        private final java.util.HashMap<Integer, Runnable> actions = new java.util.HashMap<>();
        private ActionBarMenuSubItem statusRow;
        private boolean eligibleSurface = true;

        public Button(Context context, Theme.ResourcesProvider provider, boolean compactOnly, Slot slot) {
            super(context, null, 0, Theme.getColor(Theme.key_player_time, provider), false, provider);
            this.provider = provider;
            this.compactOnly = compactOnly;
            this.slot = slot;
            setIcon(R.drawable.menu_night_mode_24);
            setLongClickEnabled(false);
            setShowSubmenuByMove(false);
            setShowedFromBottom(true);
            countdown = new TextView(context);
            countdown.setGravity(Gravity.CENTER);
            countdown.setTextSize(13);
            countdown.setSingleLine(true);
            countdown.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(countdown, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            setDelegate(id -> { final Runnable action = actions.get(id); if (action != null) action.run(); });
            setOnClickListener(v -> { rebuild(); toggleSubMenu(); });
            installFeedback();
            changed();
        }

        public void surface(boolean eligible) { eligibleSurface = eligible; changed(); }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            PurpleSleepTimer.get().listen(this);
            changed();
        }

        @Override
        protected void onDetachedFromWindow() {
            PurpleSleepTimer.get().unlisten(this);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int key = compactOnly ? Theme.key_inappPlayerPerformer
                    : PurpleSleepTimer.get().mode() == Mode.OFF ? Theme.key_player_time : Theme.key_player_buttonActive;
            final int color = Theme.getColor(key, provider);
            setIconColor(color);
            countdown.setTextColor(color);
            super.onDraw(canvas);
        }

        @Override
        public void changed() {
            final PurpleSleepTimer timer = PurpleSleepTimer.get();
            setVisibility(eligibleSurface && (!compactOnly || timer.mode() != Mode.OFF) ? View.VISIBLE : View.GONE);
            countdown.setVisibility(timer.mode() == Mode.TIMED ? View.VISIBLE : View.GONE);
            getIconView().setVisibility(timer.mode() == Mode.TIMED ? View.INVISIBLE : View.VISIBLE);
            countdown.setText(timer.mode() == Mode.TIMED ? compact() : "");
            setContentDescription(status(slot));
            if (statusRow != null) statusRow.setText(status(slot));
            requestLayout();
        }

        private void row(String text, Runnable action, boolean enabled) {
            final int id = FIRST + actions.size();
            final ActionBarMenuSubItem row = addSubItem(id, 0, text);
            row.setEnabled(enabled);
            row.setAlpha(enabled ? 1f : .5f);
            actions.put(id, action);
        }

        private void presets() {
            final PurpleSleepTimer timer = PurpleSleepTimer.get();
            final java.util.TreeSet<Integer> values = new java.util.TreeSet<>();
            values.add(15); values.add(30); values.add(60);
            values.add(Math.max(1, Math.min(720, org.telegram.messenger.MessagesController.getGlobalMainSettings()
                    .getInt("purple-sleep-custom-minutes", 45))));
            for (int minutes : values) row(duration(minutes), () -> timer.startMinutes(minutes,
                    minutes != 15 && minutes != 30 && minutes != 60), true);
        }

        private void rebuild() {
            final PurpleSleepTimer timer = PurpleSleepTimer.get();
            timer.check();
            removeAllSubItems();
            actions.clear();
            statusRow = null;
            if (timer.mode() != Mode.OFF) {
                statusRow = addSubItem(FIRST - 1, 0, status(slot));
                statusRow.setEnabled(false);
            }
            if (timer.mode() == Mode.TIMED) {
                row(getString(R.string.PurpleSleepAddFive), () -> timer.addMinutes(5), true);
                row(getString(R.string.PurpleSleepAddFifteen), () -> timer.addMinutes(15), true);
            } else presets();
            row(getString(endLabel(slot)), () -> timer.startEnd(slot), timer.eligible(slot));
            row(getString(R.string.PurpleSleepCustom), () -> custom(getContext(), provider), true);
            if (timer.mode() != Mode.OFF) row(getString(R.string.PurpleSleepTurnOff), () -> timer.cancel(true), true);
        }
    }

    public static void custom(Context context, Theme.ResourcesProvider provider) {
        final PurpleSleepTimer timer = PurpleSleepTimer.get();
        timer.check();
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, provider);
        builder.setTitle(getString(R.string.PurpleSleepTitle));
        builder.setApplyBottomPadding(false);
        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout wheels = new LinearLayout(context);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        final NumberPicker hours = new NumberPicker(context) {
            @Override protected CharSequence getContentDescription(int value) {
                return LocaleController.formatPluralString("Hours", value);
            }
        };
        final NumberPicker minutes = new NumberPicker(context) {
            @Override protected CharSequence getContentDescription(int value) {
                return LocaleController.formatPluralString("Minutes", value);
            }
        };
        for (NumberPicker picker : new NumberPicker[]{hours, minutes}) {
            picker.setItemCount(5);
            picker.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, provider));
            picker.setFormatter(value -> String.format(Locale.US, "%02d", value));
            wheels.addView(picker, LayoutHelper.createLinear(0, 54 * 5, .5f));
        }
        hours.setMinValue(0); hours.setMaxValue(12); hours.setAllItemsCount(13);
        minutes.setMinValue(0); minutes.setMaxValue(59); minutes.setAllItemsCount(60);
        hours.setValue(timer.customMinutes() / 60);
        minutes.setValue(timer.customMinutes() % 60);
        container.addView(wheels, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                0, 12, 0, 12, 0));
        final ButtonWithCounterView start = new ButtonWithCounterView(context, provider).setRound();
        start.setText(getString(R.string.Start), false);
        final Runnable changed = () -> {
            minutes.setMaxValue(hours.getValue() == 12 ? 0 : 59);
            if (hours.getValue() == 12) minutes.setValue(0);
            start.setEnabled(hours.getValue() != 0 || minutes.getValue() != 0);
            start.setAlpha(start.isEnabled() ? 1f : .5f);
        };
        hours.setOnValueChangedListener((p, old, value) -> changed.run());
        minutes.setOnValueChangedListener((p, old, value) -> changed.run());
        changed.run();
        container.addView(start, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 12, 16, 4));
        final TextView cancel = new TextView(context);
        cancel.setText(getString(R.string.Cancel));
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextSize(14);
        cancel.setTextColor(Theme.getColor(Theme.key_dialogTextBlue, provider));
        cancel.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, provider)));
        container.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 16, 12));
        builder.setCustomView(container);
        final BottomSheet sheet = builder.create();
        start.setOnClickListener(v -> {
            if (timer.startMinutes(hours.getValue() * 60 + minutes.getValue(), true)) sheet.dismiss();
        });
        cancel.setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }

    public static boolean viewerAction(int id, Context context, Theme.ResourcesProvider provider) {
        if (id == VIEWER_TIMER) { installFeedback(); custom(context, provider); return true; }
        if (id == VIEWER_END) {
            installFeedback();
            if (PurpleSleepTimer.get().mode() == Mode.OFF) PurpleSleepTimer.get().startEnd(Slot.VIEWER);
            else PurpleSleepTimer.get().cancel(true);
            return true;
        }
        return false;
    }
}
