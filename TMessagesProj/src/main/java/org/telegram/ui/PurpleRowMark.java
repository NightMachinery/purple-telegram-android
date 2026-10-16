package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.ui.ActionBar.Theme;

public final class PurpleRowMark {

    private final View cell;
    private final RectF rect = new RectF();
    private PurpleGate.Temporary temporary;
    private Paint markPaint;
    private Runnable tick;

    public PurpleRowMark(View cell) {
        this.cell = cell;
        cell.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                if (tick != null) {
                    AndroidUtilities.cancelRunOnUIThread(tick);
                }
            }
        });
    }

    public void clear() {
        temporary = null;
    }

    public void bind(int account, boolean eligible, long dialogId) {
        temporary = eligible
                ? PurpleGate.temporary(account,
                        MessagesController.getInstance(account).dialogs_dict.get(dialogId))
                : null;
    }

    public boolean wantsTimer() {
        return temporary != null && PurpleGate.recentStyle() == PurpleCore.STYLE_TIMER;
    }

    public void drawStripe(Canvas canvas, Theme.ResourcesProvider resourcesProvider) {
        if (temporary == null || PurpleGate.recentStyle() != PurpleCore.STYLE_STRIPE) {
            return;
        }
        if (temporary.untilMs <= SystemClock.elapsedRealtime()) {
            return;
        }
        final float width = dpf2(2.5f);
        final float skip = dp(8);
        final float left = LocaleController.isRTL ? cell.getMeasuredWidth() - width : 0;
        rect.set(left, skip, left + width, cell.getMeasuredHeight() - skip);
        final Paint paint = getPaint(resourcesProvider);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(rect, width / 2f, width / 2f, paint);

    }

    public void drawTimer(Canvas canvas, int countTop, int badgeSize, int badgeMargin,
                          Theme.ResourcesProvider resourcesProvider) {
        final long now = SystemClock.elapsedRealtime();
        final long total = temporary.untilMs - temporary.fromMs;
        if (total <= 0 || temporary.untilMs <= now) {
            return;
        }
        final float part = MathUtils.clamp(
            (temporary.untilMs - now) / (float) total, 0f, 1f);
        final float size = badgeSize;
        final float stroke = dpf2(1.5f);
        final float left = LocaleController.isRTL
            ? badgeMargin
            : cell.getMeasuredWidth() - badgeMargin - size;
        rect.set(left, countTop, left + size, countTop + size);
        rect.inset(stroke, stroke);

        final Paint paint = getPaint(resourcesProvider);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawArc(rect, -90f, -part * 360f, false, paint);
        paint.setStyle(Paint.Style.FILL);

        if (tick == null) {
            tick = cell::invalidate;
        }
        AndroidUtilities.cancelRunOnUIThread(tick);
        AndroidUtilities.runOnUIThread(tick, 1000);

    }

    private Paint getPaint(Theme.ResourcesProvider resourcesProvider) {
        if (markPaint == null) {
            markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        markPaint.setColor(Theme.getColor(temporary.held
            ? Theme.key_windowBackgroundWhiteGreenText
            : Theme.key_chats_unreadCounter, resourcesProvider));
        return markPaint;

    }
}
