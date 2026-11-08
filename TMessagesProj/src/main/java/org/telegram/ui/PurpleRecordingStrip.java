package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;

import android.content.Context;
import android.graphics.Canvas;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.purple.PurpleSeek;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FragmentContextView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Purple's part of one top context strip ({@link FragmentContextView}), one
 * instance per strip. It hosts F9's seek buttons and the recording dock's
 * voice or in-chat round controls (docs/recording-continuity.md).
 *
 * The two buttons sit before the speed button and show only in the audio
 * player style. Their labels follow {@code [playback] seek_step}: they are
 * read again whenever the strip enters the audio style, the buttons are
 * attached, one is pressed, or the Seek step row writes a new step
 * ({@link #relabelAll}). Their colour follows the theme: each draw checks it,
 * because {@code FragmentContextView.updateColors} recolours only upstream's
 * buttons.
 */
public final class PurpleRecordingStrip {

    /** A full touch target even though the stock strip used to be only 36dp high. */
    static final int BUTTON_DP = PurplePlayerStripLayout.HEIGHT_DP;

    /** The live strips, for {@link #relabelAll}. Main thread only. */
    private static final java.util.Set<PurpleRecordingStrip> STRIPS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private final FragmentContextView strip;
    private final java.util.function.BooleanSupplier player;
    private Theme.ResourcesProvider resourcesProvider;
    private int color;
    private TextView back;
    private TextView forward;
    private boolean audio;
    private final java.util.Map<View, android.widget.FrameLayout.LayoutParams> stockBounds = new java.util.HashMap<>();
    private org.telegram.ui.PurpleRecordingStripView recording;
    @androidx.annotation.Keep
    private final Runnable changed;

    public PurpleRecordingStrip(FragmentContextView strip, java.util.function.BooleanSupplier player, Runnable relayout) {
        this.strip = strip;
        this.player = player;
        changed = () -> {
            if (!player.getAsBoolean()) return;
            if (recording != null) recording.setVisibility(docked() ? View.VISIBLE : View.GONE);
            relayout.run();
        };
        org.telegram.messenger.purple.PurpleRecordingDock.listen(changed);
        STRIPS.add(this);
    }

    public boolean docked() {
        return player.getAsBoolean() && org.telegram.messenger.purple.PurpleRecordingDock.shown();
    }

    public boolean showRecording() {
        if (!docked()) {
            if (recording != null) recording.setVisibility(View.GONE);
            return false;
        }
        if (recording == null) {
            recording = new org.telegram.ui.PurpleRecordingStripView(strip.getContext());
            strip.addView(recording, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));
        }
        recording.setVisibility(View.VISIBLE);
        recording.bringToFront();
        recording.refresh();
        return true;
    }

    /** Relabels every live strip's buttons after the step changed. Main thread. */
    public static void relabelAll() {
        for (PurpleRecordingStrip strip : new java.util.ArrayList<>(STRIPS)) {
            strip.relabel();
        }
    }

    /** Visibility follows the style; measurement owns the title and control bounds. */
    public void updateStyle(int style, View title, Theme.ResourcesProvider resourcesProvider) {
        for (java.util.Map.Entry<View, android.widget.FrameLayout.LayoutParams> entry : stockBounds.entrySet()) {
            entry.getKey().setLayoutParams(entry.getValue());
        }
        stockBounds.clear();
        audio = style == FragmentContextView.STYLE_AUDIO_PLAYER;
        this.resourcesProvider = resourcesProvider;
        if (audio && back == null) {
            final Context context = strip.getContext();
            back = button(context, -1);
            forward = button(context, 1);
            strip.addView(forward, LayoutHelper.createFrame(BUTTON_DP, BUTTON_DP, Gravity.TOP | Gravity.LEFT));
            strip.addView(back, LayoutHelper.createFrame(BUTTON_DP, BUTTON_DP, Gravity.TOP | Gravity.LEFT));
        }
        if (back != null) {
            back.setVisibility(audio && !docked() ? View.VISIBLE : View.GONE);
            forward.setVisibility(back.getVisibility());
            if (audio) relabel();
        }
    }

    public int height(int style, int stockHeight) {
        return style == FragmentContextView.STYLE_AUDIO_PLAYER ? BUTTON_DP : stockHeight;
    }

    /** Runs after checkPlayer's title padding and speed visibility decisions, before measuring children. */
    public void measure(int width, View title, View play, View close, View speed, boolean sideMenu) {
        if (!audio || back == null) return;
        final boolean recording = docked();
        back.setVisibility(recording ? View.GONE : View.VISIBLE);
        forward.setVisibility(back.getVisibility());
        if (recording) return;
        for (View view : new View[]{title, play, close, speed}) {
            if (view != null && !stockBounds.containsKey(view)) {
                stockBounds.put(view, new android.widget.FrameLayout.LayoutParams(
                        (android.widget.FrameLayout.LayoutParams) view.getLayoutParams()));
            }
        }
        final int touch = dp(BUTTON_DP);
        final int stock = dp(36);
        final PurplePlayerStripLayout bounds = new PurplePlayerStripLayout(width, touch, stock,
                sideMenu ? dp(64) : 0, LocaleController.isRTL, visible(play), visible(close), visible(speed));
        place(play, bounds.playLeft, touch, touch);
        place(close, bounds.closeLeft, stock, touch);
        if (speed != null) place(speed, bounds.speedLeft, stock, touch);
        place(back, bounds.backLeft, touch, touch);
        place(forward, bounds.forwardLeft, touch, touch);
        title.setPadding(0, 0, 0, 0);
        place(title, bounds.titleLeft, bounds.titleWidth, touch);
        if (title instanceof org.telegram.ui.Components.AudioPlayerAlert.ClippingTextViewSwitcher) {
            final org.telegram.ui.Components.AudioPlayerAlert.ClippingTextViewSwitcher switcher =
                    (org.telegram.ui.Components.AudioPlayerAlert.ClippingTextViewSwitcher) title;
            for (TextView text : new TextView[]{switcher.getTextView(), switcher.getNextTextView()}) {
                if (text != null) text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
        }
    }

    private static boolean visible(View view) {
        return view != null && view.getVisibility() == View.VISIBLE && view.getAlpha() > 0;
    }

    private static void place(View view, int left, int width, int height) {
        final android.widget.FrameLayout.LayoutParams params =
                (android.widget.FrameLayout.LayoutParams) view.getLayoutParams();
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = left;
        params.rightMargin = params.topMargin = params.bottomMargin = 0;
        params.width = width;
        params.height = height;
    }

    private TextView button(Context context, int direction) {
        final TextView button = new TextView(context) {
            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                relabel();
            }

            @Override
            protected void onDraw(Canvas canvas) {
                recolor();
                super.onDraw(canvas);
            }
        };
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setTag(direction);
        button.setOnClickListener(v -> {
            PurpleSeek.step(direction);
            relabel();
        });
        return button;
    }

    private void relabel() {
        if (back == null) {
            return;
        }
        final int step = PurpleSeek.stepSeconds();
        label(back, -1, step);
        label(forward, 1, step);
        recolor();
    }

    /**
     * Sets both buttons' text colour and selector from the current theme when
     * it changed since the last time, so a day and night switch with the strip
     * alive recolours them on their next draw.
     */
    private void recolor() {
        if (back == null) {
            return;
        }
        final int now = Theme.getColor(Theme.key_inappPlayerTitle, resourcesProvider);
        if (now == color && back.getBackground() != null) {
            return;
        }
        color = now;
        for (TextView button : new TextView[] {back, forward}) {
            button.setTextColor(now);
            button.setBackground(Theme.createSelectorDrawable(now & 0x19ffffff, 1, dp(14)));
        }
    }

    private static void label(TextView button, int direction, int step) {
        button.setText(PurpleSeek.label(direction, step));
        button.setContentDescription(formatString(direction < 0 ? R.string.PurpleSeekBack : R.string.PurpleSeekForward, step));
    }
}
