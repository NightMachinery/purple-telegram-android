package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

final class PurplePinnedMusicBar extends LinearLayout implements PurplePinnedMusic.Listener {

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView titleView;
    private final TextView detailView;
    private final ImageView pauseButton;
    private final ImageView retryButton;
    private final ImageView closeButton;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint donePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint failedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final Runnable countdownTick = this::render;

    private Utilities.Callback2<Boolean, Boolean> onShownChanged;

    private boolean bound;
    private int account;
    private long dialogId;
    private long topicId;
    private boolean binding;
    private boolean bindAnimated;
    private boolean shown;
    private PurplePinnedMusic.Snapshot snapshot;
    private float doneFraction;
    private float failedFraction;
    private int retryLabel = R.string.PurplePinnedMusicBarRetry;

    PurplePinnedMusicBar(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(48));
        setPadding(0, 0, dp(4), 0);
        setWillNotDraw(false);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        titleView = text(context, 14);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        texts.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        detailView = text(context, 13);
        texts.addView(detailView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 16, 6, 4, 8));

        pauseButton = button(context, R.drawable.msg_round_pause_m, R.string.PurplePinnedMusicBarPause);
        pauseButton.setOnClickListener(v -> {
            if (bound && snapshot != null) {
                PurplePinnedMusic.setPaused(account, dialogId, topicId, !snapshot.paused);
            }
        });
        retryButton = button(context, R.drawable.msg_retry, R.string.PurplePinnedMusicBarRetry);
        retryButton.setOnClickListener(v -> {
            if (bound) {
                PurplePinnedMusic.retryFailed(account, dialogId, topicId);
            }
        });
        closeButton = button(context, R.drawable.miniplayer_close, R.string.PurplePinnedMusicBarDismiss);
        closeButton.setOnClickListener(v -> {
            if (bound) {
                PurplePinnedMusic.dismiss(account, dialogId, topicId);
            }
        });
        updateColors();
    }

    void setOnShownChanged(Utilities.Callback2<Boolean, Boolean> onShownChanged) {
        this.onShownChanged = onShownChanged;
    }

    void bind(int account, long dialogId, long topicId, boolean animated) {
        if (bound && this.account == account && this.dialogId == dialogId && this.topicId == topicId) {
            return;
        }
        unbind(animated);
        this.account = account;
        this.dialogId = dialogId;
        this.topicId = topicId;
        bound = true;
        binding = true;
        bindAnimated = animated;
        try {
            PurplePinnedMusic.addListener(account, dialogId, topicId, this);
        } finally {
            binding = false;
        }
    }

    void unbind() {
        unbind(false);
    }

    private void unbind(boolean animated) {
        if (!bound) {
            return;
        }
        bound = false;
        PurplePinnedMusic.removeListener(account, dialogId, topicId, this);
        apply(null, animated);
    }

    @Override
    public void onPinnedMusicChanged(PurplePinnedMusic.Snapshot snapshot) {
        if (bound) {
            apply(snapshot, !binding || bindAnimated);
        }
    }

    private void apply(PurplePinnedMusic.Snapshot snapshot, boolean animated) {
        this.snapshot = snapshot;
        render();
        boolean show = snapshot != null;
        if (show != shown) {
            shown = show;
            if (onShownChanged != null) {
                onShownChanged.run(show, animated);
            }
        }
    }

    void updateColors() {
        titleView.setTextColor(color(Theme.key_chat_topPanelTitle));
        detailView.setTextColor(color(Theme.key_chat_topPanelMessage));
        PorterDuffColorFilter icon = new PorterDuffColorFilter(color(Theme.key_chat_topPanelClose), PorterDuff.Mode.SRC_IN);
        int selector = color(Theme.key_inappPlayerClose) & 0x19ffffff;
        for (ImageView button : new ImageView[] { pauseButton, retryButton, closeButton }) {
            button.setColorFilter(icon);
            button.setBackground(Theme.createSelectorDrawable(selector, 1, dp(18)));
        }
        int line = color(Theme.key_chat_topPanelLine);
        donePaint.setColor(line);
        trackPaint.setColor(line);
        trackPaint.setAlpha(Color.alpha(line) / 5);
        failedPaint.setColor(color(Theme.key_text_RedRegular));
        invalidate();
    }

    private void render() {
        AndroidUtilities.cancelRunOnUIThread(countdownTick);
        PurplePinnedMusic.Snapshot s = snapshot;
        if (s == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldownMs = s.finished ? 0 : Math.max(0, s.cooldownUntilMs - now);
        setText(titleView, title(s, cooldownMs > 0));
        String detail = detail(s, cooldownMs);
        setText(detailView, detail);
        detailView.setVisibility(detail.isEmpty() ? GONE : VISIBLE);

        pauseButton.setVisibility(s.finished ? GONE : VISIBLE);
        int pauseIcon = s.paused ? R.drawable.msg_round_play_m : R.drawable.msg_round_pause_m;
        if (!Integer.valueOf(pauseIcon).equals(pauseButton.getTag())) {
            pauseButton.setTag(pauseIcon);
            pauseButton.setImageResource(pauseIcon);
            setLabel(pauseButton, s.paused ? R.string.PurplePinnedMusicBarResume : R.string.PurplePinnedMusicBarPause);
        }
        retryButton.setVisibility(s.retryable > 0 || s.scanFailed ? VISIBLE : GONE);
        int label = s.scanFailed ? R.string.PurplePinnedMusicBarRetrySearch : R.string.PurplePinnedMusicBarRetry;
        if (label != retryLabel) {
            retryLabel = label;
            setLabel(retryButton, label);
        }
        closeButton.setVisibility(s.finished ? VISIBLE : GONE);

        doneFraction = s.selected > 0 ? Math.min(1f, s.completed / (float) s.selected) : 0f;
        failedFraction = s.selected > 0 ? Math.min(1f - doneFraction, s.failed / (float) s.selected) : 0f;
        invalidate();

        if (cooldownMs > 0 && isAttachedToWindow()) {
            AndroidUtilities.runOnUIThread(countdownTick, (cooldownMs - 1) % 1000 + 1);
        }
    }

    private static String title(PurplePinnedMusic.Snapshot s, boolean cooling) {
        if (s.scanFailed) {
            return getString(R.string.PurplePinnedMusicBarSearchFailed);
        }
        if (s.finished) {
            if (s.failed > 0) {
                return getString(R.string.PurplePinnedMusicBarDoneWithFailures);
            }
            return getString(s.selected == 0 ? R.string.PurplePinnedMusicBarNone : R.string.PurplePinnedMusicBarDone);
        }
        if (s.paused) {
            return getString(R.string.PurplePinnedMusicBarPaused);
        }
        if (cooling) {
            return getString(R.string.PurplePinnedMusicBarRateLimited);
        }
        if (s.scanning) {
            return getString(R.string.PurplePinnedMusicBarSearching);
        }
        return getString(s.retrying ? R.string.PurplePinnedMusicBarRetrying : R.string.PurplePinnedMusicBarDownloading);
    }

    private static String detail(PurplePinnedMusic.Snapshot s, long cooldownMs) {
        StringBuilder text = new StringBuilder();
        if (cooldownMs > 0) {
            append(text, formatString(R.string.PurplePinnedMusicBarCooldown,
                    AndroidUtilities.formatShortDuration((int) ((cooldownMs + 999) / 1000))));
        }
        if (s.scanning) {
            append(text, formatString(R.string.PurplePinnedMusicBarPinned, s.pinned));
        }
        if (s.selected > 0) {
            append(text, formatString(R.string.PurplePinnedMusicBarProgress, s.completed, s.selected));
        }
        if (s.active > 0) {
            append(text, formatString(R.string.PurplePinnedMusicBarActive, s.active));
        }
        if (s.failed > 0) {
            append(text, formatString(R.string.PurplePinnedMusicBarFailedCount, s.failed));
        }
        return text.toString();
    }

    private static void setText(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private static void append(StringBuilder text, String part) {
        if (text.length() > 0) {
            text.append(" · ");
        }
        text.append(part);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (snapshot == null) {
            return;
        }
        float start = dp(16);
        float end = getWidth() - dp(16);
        float bottom = getHeight() - dp(3);
        float top = bottom - dp(2);
        segment(canvas, start, end, top, bottom, trackPaint);
        float done = start + (end - start) * doneFraction;
        if (doneFraction > 0) {
            segment(canvas, start, done, top, bottom, donePaint);
        }
        if (failedFraction > 0) {
            segment(canvas, done, done + (end - start) * failedFraction, top, bottom, failedPaint);
        }
    }

    private void segment(Canvas canvas, float from, float to, float top, float bottom, Paint paint) {
        if (LocaleController.isRTL) {
            float width = getWidth();
            rect.set(width - to, top, width - from, bottom);
        } else {
            rect.set(from, top, to, bottom);
        }
        float radius = (bottom - top) / 2f;
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        render();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(countdownTick);
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private static TextView text(Context context, int sizeDp) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        return view;
    }

    private ImageView button(Context context, int icon, int label) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setTag(icon);
        setLabel(view, label);
        addView(view, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL));
        return view;
    }

    private static void setLabel(View view, int label) {
        String text = getString(label);
        view.setContentDescription(text);
        if (Build.VERSION.SDK_INT >= 26) {
            view.setTooltipText(text);
        }
    }
}
