package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.MediaActionDrawable;
import org.telegram.ui.Components.RadialProgress2;

public final class PurpleRecordingPlayButton extends View {
    private final RadialProgress2 radial;
    private final Theme.ResourcesProvider resources;

    public PurpleRecordingPlayButton(Context context, Theme.ResourcesProvider resources) {
        super(context);
        this.resources = resources;
        radial = new RadialProgress2(this, resources);
        radial.setColorKeys(Theme.key_chat_inLoader, Theme.key_chat_inLoaderSelected,
                Theme.key_chat_inMediaIcon, Theme.key_chat_inMediaIconSelected);
        radial.setIcon(MediaActionDrawable.ICON_PLAY, false, false);
        setFocusable(true);
    }

    public void setPlaying(boolean playing) {
        radial.setIcon(playing ? MediaActionDrawable.ICON_PAUSE : MediaActionDrawable.ICON_PLAY, true, true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int inset = AndroidUtilities.dp(2);
        radial.setProgressRect(inset, inset, getWidth() - inset, getHeight() - inset);
        radial.setProgressColor(Theme.getColor(isPressed() ? Theme.key_chat_inAudioSelectedProgress : Theme.key_chat_inAudioProgress, resources));
        radial.draw(canvas);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        radial.setPressed(pressed, false);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() { super.onAttachedToWindow(); radial.onAttachedToWindow(); }

    @Override
    protected void onDetachedFromWindow() { radial.onDetachedFromWindow(); super.onDetachedFromWindow(); }
}
