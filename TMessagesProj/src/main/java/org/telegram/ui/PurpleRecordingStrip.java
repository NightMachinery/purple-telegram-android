package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleSeek;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FragmentContextView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Purple's part of one top context strip ({@link FragmentContextView}), one
 * instance per strip. For now it holds F9's seek buttons
 * (docs/media-recording-design.md, "F9. Seek back and forward"); the
 * recording dock's strip will join it.
 *
 * The two buttons sit left of the speed button and show only in the audio
 * player style. Their labels follow {@code [playback] seek_step}: they are
 * read again whenever the strip enters the audio style, the buttons are
 * attached, or one is pressed, so a changed step shows without a restart.
 */
public final class PurpleRecordingStrip {

    /** Each button's width and height, the same as the speed button's. */
    static final int BUTTON_DP = 36;

    /** Where the speed button ends, counted from the strip's right edge. */
    static final int SPEED_END_DP = 72;

    private final FragmentContextView strip;
    private TextView back;
    private TextView forward;
    private View paddedTitle;
    private int titlePadding;

    public PurpleRecordingStrip(FragmentContextView strip) {
        this.strip = strip;
    }

    /**
     * Called by {@code FragmentContextView.updateStyle} once the new style is
     * set and before its branches lay the strip out: shows the seek buttons in
     * the audio player style and hides them in every other, and keeps the
     * title clear of them by its right padding.
     *
     * It runs before the branches so that a style which sets the title's
     * padding outright (the group call styles) does so after Purple has taken
     * its share back.
     */
    public void updateStyle(int style, View title, Theme.ResourcesProvider resourcesProvider) {
        final boolean audio = style == FragmentContextView.STYLE_AUDIO_PLAYER;
        if (audio && back == null) {
            final Context context = strip.getContext();
            back = button(context, -1, resourcesProvider);
            forward = button(context, 1, resourcesProvider);
            strip.addView(forward, LayoutHelper.createFrame(BUTTON_DP, BUTTON_DP, Gravity.TOP | Gravity.RIGHT, 0, 0, SPEED_END_DP, 0));
            strip.addView(back, LayoutHelper.createFrame(BUTTON_DP, BUTTON_DP, Gravity.TOP | Gravity.RIGHT, 0, 0, SPEED_END_DP + BUTTON_DP, 0));
        }
        if (back != null) {
            back.setVisibility(audio ? View.VISIBLE : View.GONE);
            forward.setVisibility(audio ? View.VISIBLE : View.GONE);
            if (audio) {
                relabel();
            }
        }
        padTitle(audio ? title : null, audio ? dp(2 * BUTTON_DP) : 0);
    }

    private void padTitle(View title, int padding) {
        if (paddedTitle != null && titlePadding != 0) {
            paddedTitle.setPadding(paddedTitle.getPaddingLeft(), paddedTitle.getPaddingTop(),
                    Math.max(0, paddedTitle.getPaddingRight() - titlePadding), paddedTitle.getPaddingBottom());
        }
        paddedTitle = title;
        titlePadding = title == null ? 0 : padding;
        if (title != null && padding != 0) {
            title.setPadding(title.getPaddingLeft(), title.getPaddingTop(),
                    title.getPaddingRight() + padding, title.getPaddingBottom());
        }
    }

    private TextView button(Context context, int direction, Theme.ResourcesProvider resourcesProvider) {
        final TextView button = new TextView(context) {
            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                relabel();
            }
        };
        final int color = Theme.getColor(Theme.key_inappPlayerClose, resourcesProvider);
        button.setTextColor(color);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setBackground(Theme.createSelectorDrawable(color & 0x19ffffff, 1, dp(14)));
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
    }

    private static void label(TextView button, int direction, int step) {
        button.setText(PurpleSeek.label(direction, step));
        button.setContentDescription(formatString(direction < 0 ? R.string.PurpleSeekBack : R.string.PurpleSeekForward, step));
    }
}
