package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.StaticLayout;

import org.telegram.messenger.ImageReceiver;
import org.telegram.ui.ActionBar.Theme;

public final class PurpleImportButton {

    public static final int INSTANT_BUTTON_TYPE_PURPLE_IMPORT = 0x70750006;

    private PurpleImportButton() {
    }

    public static void draw(Canvas canvas, RectF instantButtonRect, Drawable selectorDrawable,
                            ImageReceiver photoImage, int backgroundLeft, int backgroundWidth,
                            int instantWidth, int instantTextX, StaticLayout instantViewLayout, int color) {
        Theme.chat_instantViewPaint.setColor(color);
        Theme.chat_instantViewButtonPaint.setColor(Theme.multAlpha(color, .10f));

        final int instantY = (int) (photoImage.getImageY() + photoImage.getImageHeight()) + dp(6);
        final int instantX = backgroundLeft + Math.max(dp(8), (backgroundWidth - instantWidth) / 2);
        instantButtonRect.set(instantX, instantY, instantX + instantWidth, instantY + dp(36));

        if (selectorDrawable != null) {
            selectorDrawable.setBounds(instantX, instantY, instantX + instantWidth, instantY + dp(36));
            selectorDrawable.draw(canvas);
        }
        canvas.drawRoundRect(instantButtonRect, dp(6), dp(6), Theme.chat_instantViewButtonPaint);
        if (instantViewLayout != null) {
            canvas.save();
            canvas.translate(instantX + instantTextX, instantY + dp(10.5f));
            instantViewLayout.draw(canvas);
            canvas.restore();
        }
    }
}
