package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

public final class PurpleRoundControls extends LinearLayout {
    private final ImageView pause;
    private final PlayPauseDrawable playPause = new PlayPauseDrawable(16);

    public PurpleRoundControls(Context context, Runnable flip, Runnable toggle, Object[] background) {
        super(context);
        setLayoutParams(LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
        setOrientation(HORIZONTAL);
        setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6));
        if (background != null) {
            BlurredBackgroundDrawable drawable = ((BlurredBackgroundDrawableViewFactory) background[0])
                    .create(this, (BlurredBackgroundColorProvider) background[1]);
            drawable.setPadding(AndroidUtilities.dp(6));
            drawable.setRadius(AndroidUtilities.dp(21));
            setBackground(drawable);
        }
        ImageView camera = button(context, LocaleController.getString(R.string.AccDescrSwitchCamera), flip);
        RLottieDrawable flipIcon = new RLottieDrawable(R.raw.roundcamera_flip, AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        flipIcon.setCurrentFrame(flipIcon.getFramesCount() - 1);
        camera.setImageDrawable(flipIcon);
        camera.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY));
        pause = button(context, "", toggle);
        playPause.setColor(Color.WHITE);
        pause.setImageDrawable(playPause);
    }

    private ImageView button(Context context, String description, Runnable action) {
        ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setBackground(Theme.createSelectorDrawable(0x19ffffff, 1, AndroidUtilities.dp(21)));
        button.setOnClickListener(v -> action.run());
        addView(button, new LayoutParams(AndroidUtilities.dp(48), AndroidUtilities.dp(48)));
        return button;
    }

    public void update(boolean paused) {
        playPause.setPause(!paused, false);
        pause.setContentDescription(LocaleController.getString(paused ? R.string.PurpleRecordingResume : R.string.PurpleRecordingPause));
    }
}
