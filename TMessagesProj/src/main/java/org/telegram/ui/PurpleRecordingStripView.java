package org.telegram.ui;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationManagerCompat;
import org.telegram.messenger.*;
import org.telegram.messenger.purple.*;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

public final class PurpleRecordingStripView extends LinearLayout {
    private final TextView target;
    private final TextView pause;
    private final TextView preview;
    private long generation;
    private final Runnable tick = new Runnable() {
        public void run() { refresh(); }
    };
    public PurpleRecordingStripView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClickable(true);
        target = button(context, "", () -> context.startActivity(PurpleRecordingDock.openIntent()));
        target.setEllipsize(TextUtils.TruncateAt.END);
        addView(target, new LayoutParams(0, -1, 1));
        pause = button(context, "", () -> PurpleRecordingDock.pause(generation));
        addView(pause, new LayoutParams(AndroidUtilities.dp(48), -1));
        preview = button(context, LocaleController.getString(R.string.PurpleRecordingPreview), () -> PurpleRecordingDock.preview(generation));
        addView(preview, new LayoutParams(AndroidUtilities.dp(60), -1));
        TextView send = button(context, LocaleController.getString(R.string.Send), () -> PurpleRecordingDock.send(generation));
        addView(send, new LayoutParams(AndroidUtilities.dp(48), -1));
        TextView discard = button(context, "×", () -> {
            final long token = generation;
            new AlertDialog.Builder(getContext()).setTitle(LocaleController.getString(R.string.DiscardVoiceMessageTitle))
                    .setMessage(LocaleController.getString(R.string.DiscardVoiceMessageDescription))
                    .setPositiveButton(LocaleController.getString(R.string.DiscardVoiceMessageAction), (d, w) -> PurpleRecordingDock.discard(token))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null).show();
        });
        discard.setContentDescription(LocaleController.getString(R.string.DiscardVoiceMessageAction));
        addView(discard, new LayoutParams(AndroidUtilities.dp(36), -1));
        target.setOnLongClickListener(v -> { PurpleRecordingDock.preview(generation); return true; });
    }
    private TextView button(Context context, String text, Runnable action) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }
    public void refresh() {
        PurpleRecordingDockState state = PurpleRecordingDock.state;
        generation = state.generation;
        removeCallbacks(tick);
        if (!state.active() || getVisibility() != VISIBLE) return;
        postDelayed(tick, 1000);
        setBackgroundColor(Theme.getColor(Theme.key_inappPlayerBackground));
        for (int i = 0; i < getChildCount(); i++) ((TextView) getChildAt(i)).setTextColor(Theme.getColor(Theme.key_inappPlayerTitle));
        String time = android.text.format.DateUtils.formatElapsedTime(state.duration(SystemClock.elapsedRealtime()) / 1000);
        target.setText((state.paused ? "Ⅱ " : "● ") + PurpleRecordingDock.title() + " " + time);
        pause.setText(LocaleController.getString(state.paused ? R.string.PurpleRecordingResume : R.string.PurpleRecordingPause));
        preview.setVisibility(getWidth() != 0 && getWidth() < AndroidUtilities.dp(420) ? GONE : VISIBLE);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        post(tick);
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("purple_recordings", Context.MODE_PRIVATE);
        if (!NotificationManagerCompat.from(getContext()).areNotificationsEnabled() && !prefs.getBoolean("controls_hint", false)) {
            prefs.edit().putBoolean("controls_hint", true).apply();
            Toast.makeText(getContext(), LocaleController.getString(R.string.PurpleRecordingNotificationHint), Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
}
