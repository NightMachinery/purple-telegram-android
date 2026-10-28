package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleWaitLayout;
import org.telegram.ui.ActionBar.Theme;

final class PurpleWaitCover extends FrameLayout {
    private final LinearLayout countdown, readyContent;
    private final TextView title, remaining, detail;
    private final Button action, abort;

    PurpleWaitCover(Context context, String heading, String breakdown, String actionName,
            Runnable confirm, Runnable cancel) {
        super(context);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        countdown = new LinearLayout(context);
        countdown.setOrientation(LinearLayout.VERTICAL);
        countdown.setGravity(Gravity.CENTER);
        countdown.setPadding(dp(24), dp(24), dp(24), dp(24));
        title = label(context, heading, 24);
        remaining = label(context, "", 80);
        remaining.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        detail = label(context, breakdown, 16);
        countdown.addView(title, row());
        countdown.addView(remaining, row());
        if (breakdown != null) countdown.addView(detail, row());
        abort = new Button(context);
        abort.setText(getString(R.string.PurpleWaitAbort));
        abort.setTextSize(20);
        abort.setOnClickListener(view -> cancel.run());
        countdown.addView(abort, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        addView(countdown, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        readyContent = new LinearLayout(context);
        readyContent.setOrientation(LinearLayout.VERTICAL);
        readyContent.setPadding(dp(24), 0, dp(24), 0);
        readyContent.addView(label(context, getString(R.string.PurpleWaitReadyTitle), 24), row());
        readyContent.addView(label(context, getString(R.string.PurpleWaitReadyHint), 16), row());
        action = new Button(context);
        action.setText(actionName);
        action.setTextSize(20);
        action.setEnabled(false);
        action.setOnClickListener(view -> confirm.run());
        readyContent.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        readyContent.setVisibility(View.GONE);
        addView(readyContent, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static TextView label(Context context, String text, int size) {
        TextView label = new TextView(context);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(size);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setText(text);
        return label;
    }

    void update(long milliseconds, boolean ready, boolean enabled) {
        title.setVisibility(ready ? View.INVISIBLE : View.VISIBLE);
        remaining.setVisibility(ready ? View.INVISIBLE : View.VISIBLE);
        detail.setVisibility(ready ? View.INVISIBLE : View.VISIBLE);
        remaining.setText(String.valueOf((milliseconds + 999) / 1000));
        readyContent.setVisibility(ready ? View.VISIBLE : View.GONE);
        action.setEnabled(enabled);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (readyContent.getVisibility() != View.GONE) {
            readyContent.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (readyContent.getVisibility() != View.GONE) {
            int readyTop = PurpleWaitLayout.readyTop(countdown.getTop() + abort.getTop(), dp(8), readyContent.getMeasuredHeight());
            readyContent.layout(0, readyTop, right - left, readyTop + readyContent.getMeasuredHeight());
        }
    }
}
