package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationManagerCompat;
import org.telegram.messenger.*;
import org.telegram.messenger.purple.*;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;

public final class PurpleRecordingStripView extends LinearLayout {
    private final TextView target, timer, pause, preview, send, discard;
    private final BackupImageView avatar;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final View waveform;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long generation;
    private long avatarDialog = Long.MIN_VALUE;
    private int avatarAccount = -1;
    private boolean avatarHidden;
    private final Runnable tick = this::refresh;

    public PurpleRecordingStripView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClickable(true);
        avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(14));
        addView(avatar, new LayoutParams(AndroidUtilities.dp(28), AndroidUtilities.dp(28)));
        avatar.setOnClickListener(v -> PurpleRecordingDock.open());
        target = button(context, "", PurpleRecordingDock::open);
        target.setEllipsize(TextUtils.TruncateAt.END);
        addView(target, new LayoutParams(0, -1, 1));
        timer = button(context, "", PurpleRecordingDock::open);
        timer.setTextSize(10);
        addView(timer, new LayoutParams(AndroidUtilities.dp(56), -1));
        timer.setOnLongClickListener(v -> { PurpleRecordingDock.preview(generation); return true; });
        waveform = new View(context) {
            @Override protected void onDraw(Canvas canvas) {
                PurpleRecordingDockState state = PurpleRecordingDock.state;
                paint.setColor(Theme.getColor(Theme.key_inappPlayerTitle));
                paint.setStrokeWidth(AndroidUtilities.dp(1));
                for (int i = 0; i < 32; i++) {
                    float x = (i + .5f) * getWidth() / 32;
                    float level = state.levels[(state.levelIndex + i * 2) % state.levels.length];
                    float h = AndroidUtilities.dp(1) + level * getHeight() * .35f;
                    canvas.drawLine(x, getHeight() / 2f - h, x, getHeight() / 2f + h, paint);
                }
            }
        };
        waveform.setOnClickListener(v -> PurpleRecordingDock.open());
        addView(waveform, new LayoutParams(AndroidUtilities.dp(36), -1));
        pause = button(context, "", () -> PurpleRecordingDock.pause(generation));
        addView(pause, new LayoutParams(AndroidUtilities.dp(44), -1));
        preview = button(context, LocaleController.getString(R.string.PurpleRecordingPreview), () -> PurpleRecordingDock.preview(generation));
        addView(preview, new LayoutParams(AndroidUtilities.dp(56), -1));
        send = button(context, "", () -> PurpleRecordingDock.send(generation));
        addView(send, new LayoutParams(AndroidUtilities.dp(44), -1));
        discard = button(context, "×", () -> {
            final long token = generation;
            new AlertDialog.Builder(getContext()).setTitle(LocaleController.getString(R.string.DiscardVoiceMessageTitle))
                    .setMessage(LocaleController.getString(R.string.DiscardVoiceMessageDescription))
                    .setPositiveButton(LocaleController.getString(R.string.DiscardVoiceMessageAction), (d, w) -> PurpleRecordingDock.discard(token))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null).show();
        });
        discard.setContentDescription(LocaleController.getString(R.string.DiscardVoiceMessageAction));
        addView(discard, new LayoutParams(AndroidUtilities.dp(32), -1));
    }
    private TextView button(Context context, String text, Runnable action) {
        TextView view = new TextView(context);
        view.setText(text); view.setTextSize(11); view.setGravity(Gravity.CENTER);
        view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END);
        view.setOnClickListener(v -> action.run());
        return view;
    }
    private void avatar(PurpleRecordingDockState state) {
        boolean hidden = PurpleGate.hiddenEverywhere(state.account, state.dialog);
        if (state.dialog == avatarDialog && state.account == avatarAccount && hidden == avatarHidden) return;
        avatarDialog = state.dialog; avatarAccount = state.account; avatarHidden = hidden;
        TLObject peer = null;
        MessagesController controller = MessagesController.getInstance(state.account);
        if (!hidden) {
            if (DialogObject.isEncryptedDialog(state.dialog)) {
                TLRPC.EncryptedChat chat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(state.dialog));
                if (chat != null) peer = controller.getUser(chat.user_id);
            } else peer = state.dialog > 0 ? controller.getUser(state.dialog) : controller.getChat(-state.dialog);
        }
        if (peer != null) { avatarDrawable.setAvatarType(0); avatarDrawable.setInfo(state.account, peer); avatar.setForUserOrChat(peer, avatarDrawable); }
        else { avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_ANONYMOUS); avatar.setImageDrawable(avatarDrawable); }
        avatar.setContentDescription(PurpleRecordingDock.title());
    }
    public void refresh() {
        PurpleRecordingDockState state = PurpleRecordingDock.state;
        generation = state.generation;
        removeCallbacks(tick);
        if (!state.active() || getVisibility() != VISIBLE) return;
        postDelayed(tick, 100);
        setBackgroundColor(Theme.getColor(Theme.key_inappPlayerBackground));
        for (TextView text : new TextView[]{target, timer, pause, preview, send, discard}) {
            text.setTextColor(Theme.getColor(Theme.key_inappPlayerTitle));
            text.setEnabled(!state.finishing);
        }
        avatar(state);
        target.setText(PurpleRecordingDock.title());
        timer.setText((state.paused ? "Ⅱ " : "● ") + android.text.format.DateUtils.formatElapsedTime(state.duration(SystemClock.elapsedRealtime()) / 1000));
        timer.setContentDescription(LocaleController.getString(state.round ? R.string.PurpleRecordingBinRound : R.string.PurpleRecordingVoice) + " " + timer.getText());
        pause.setText(LocaleController.getString(state.paused ? R.string.PurpleRecordingResume : R.string.PurpleRecordingPause));
        boolean unavailable = PurpleRecordingDock.unavailable() && !DialogObject.isEncryptedDialog(state.dialog);
        send.setText(LocaleController.getString(unavailable ? R.string.PurpleRecordingSendElsewhere : R.string.Send));
        send.setContentDescription(send.getText());
        int width = AndroidUtilities.dp(unavailable ? 100 : 44);
        if (send.getLayoutParams().width != width) { send.getLayoutParams().width = width; send.requestLayout(); }
        preview.setVisibility(getWidth() != 0 && getWidth() < AndroidUtilities.dp(420) ? GONE : VISIBLE);
        waveform.setVisibility(unavailable && getWidth() < AndroidUtilities.dp(420) ? GONE : VISIBLE);
        waveform.invalidate();
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (tick != null) { removeCallbacks(tick); if (visibility == VISIBLE && isAttachedToWindow()) post(tick); }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); removeCallbacks(tick); post(tick);
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("purple_recordings", Context.MODE_PRIVATE);
        if (!NotificationManagerCompat.from(getContext()).areNotificationsEnabled() && !prefs.getBoolean("controls_hint", false)) {
            prefs.edit().putBoolean("controls_hint", true).apply();
            Toast.makeText(getContext(), LocaleController.getString(R.string.PurpleRecordingNotificationHint), Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
}
