package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.widget.ImageView;
import org.telegram.ui.Components.PlayPauseDrawable;
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
    private final TextView target, timer;
    private final ImageView pause, send, discard;
    private final PlayPauseDrawable playPause = new PlayPauseDrawable(16);
    private final Theme.ResourcesProvider resourcesProvider;
    private final BackupImageView avatar;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final View waveform;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long generation;
    private long avatarDialog = Long.MIN_VALUE;
    private int avatarAccount = -1;
    private boolean avatarHidden;
    private Boolean layoutRtl;
    private final Runnable tick = this::refresh;

    public PurpleRecordingStripView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(HORIZONTAL);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setGravity(Gravity.CENTER_VERTICAL);
        setClickable(true);
        avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(14));
        LayoutParams avatarBounds = new LayoutParams(AndroidUtilities.dp(28), AndroidUtilities.dp(28));
        avatarBounds.rightMargin = AndroidUtilities.dp(7);
        addView(avatar, avatarBounds);
        avatar.setOnClickListener(v -> PurpleRecordingDock.open());
        target = button(context, "", PurpleRecordingDock::open);
        target.setEllipsize(TextUtils.TruncateAt.END);
        addView(target, new LayoutParams(0, -1, 1));
        timer = button(context, "", PurpleRecordingDock::open);
        timer.setTextSize(13);
        timer.setTextDirection(TEXT_DIRECTION_LTR);
        addView(timer, new LayoutParams(AndroidUtilities.dp(56), -1));
        timer.setOnLongClickListener(v -> { PurpleRecordingDock.preview(generation); return true; });
        waveform = new View(context) {
            @Override protected void onDraw(Canvas canvas) {
                PurpleRecordingDockState state = PurpleRecordingDock.state;
                paint.setColor(Theme.getColor(Theme.key_inappPlayerTitle, resourcesProvider));
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
        pause = icon(context, () -> PurpleRecordingDock.pause(generation));
        pause.setImageDrawable(playPause);
        send = icon(context, () -> PurpleRecordingDock.send(generation));
        send.setImageResource(R.drawable.send_plane_24);
        discard = icon(context, () -> {
            final long token = generation;
            new AlertDialog.Builder(getContext(), resourcesProvider).setTitle(LocaleController.getString(R.string.DiscardVoiceMessageTitle))
                    .setMessage(LocaleController.getString(R.string.DiscardVoiceMessageDescription))
                    .setPositiveButton(LocaleController.getString(R.string.DiscardVoiceMessageAction), (d, w) -> PurpleRecordingDock.discard(token))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null).show();
        });
        discard.setImageResource(R.drawable.miniplayer_close);
        discard.setContentDescription(LocaleController.getString(R.string.DiscardVoiceMessageAction));
        updateLocaleLayout();
    }
    private ImageView icon(Context context, Runnable action) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setOnClickListener(v -> action.run());
        addView(view, new LayoutParams(AndroidUtilities.dp(36), AndroidUtilities.dp(36)));
        return view;
    }
    private TextView button(Context context, String text, Runnable action) {
        TextView view = new TextView(context);
        view.setText(text); view.setTextSize(15); view.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        view.setTextAlignment(TEXT_ALIGNMENT_GRAVITY);
        view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END);
        view.setOnClickListener(v -> action.run());
        return view;
    }
    private void updateLocaleLayout() {
        boolean rtl = LocaleController.isRTL;
        if (layoutRtl != null && layoutRtl == rtl) return;
        setPadding(AndroidUtilities.dp(rtl ? 4 : 7), 0, AndroidUtilities.dp(rtl ? 7 : 4), 0);
        LayoutParams bounds = (LayoutParams) avatar.getLayoutParams();
        bounds.leftMargin = AndroidUtilities.dp(rtl ? 7 : 0);
        bounds.rightMargin = AndroidUtilities.dp(rtl ? 0 : 7);
        target.setGravity(Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT));
        View[] order = {avatar, target, timer, waveform, pause, send, discard};
        removeAllViews();
        for (int i = 0; i < order.length; i++) {
            View child = order[rtl ? order.length - 1 - i : i];
            addView(child, child.getLayoutParams());
        }
        layoutRtl = rtl;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        updateLocaleLayout();
        super.onMeasure(widthSpec, heightSpec);
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
        updateLocaleLayout();
        PurpleRecordingDockState state = PurpleRecordingDock.state;
        generation = state.generation;
        removeCallbacks(tick);
        if (!state.active() || getVisibility() != VISIBLE) return;
        postDelayed(tick, 100);
        final int primary = Theme.getColor(Theme.key_inappPlayerPerformer, resourcesProvider);
        target.setTextColor(primary);
        timer.setTextColor(Theme.getColor(Theme.key_inappPlayerTitle, resourcesProvider));
        target.setTypeface(AndroidUtilities.bold());
        for (ImageView icon : new ImageView[]{pause, send, discard}) {
            final int color = Theme.getColor(icon == discard ? Theme.key_inappPlayerClose : Theme.key_inappPlayerPlayPause, resourcesProvider);
            icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
            icon.setBackground(Theme.createSelectorDrawable(color & 0x19ffffff, 1, AndroidUtilities.dp(14)));
            icon.setEnabled(!state.finishing);
        }
        playPause.setColor(Theme.getColor(Theme.key_inappPlayerPlayPause, resourcesProvider));
        playPause.setPause(!state.paused, false);
        avatar(state);
        target.setText(PurpleRecordingDock.title());
        timer.setText(android.text.format.DateUtils.formatElapsedTime(state.duration(SystemClock.elapsedRealtime()) / 1000));
        timer.setContentDescription(LocaleController.getString(state.round ? R.string.PurpleRecordingBinRound : R.string.PurpleRecordingVoice) + " " + timer.getText());
        pause.setContentDescription(LocaleController.getString(state.paused ? R.string.PurpleRecordingResume : R.string.PurpleRecordingPause));
        boolean unavailable = PurpleRecordingDock.unavailable() && !DialogObject.isEncryptedDialog(state.dialog);
        send.setContentDescription(LocaleController.getString(unavailable ? R.string.PurpleRecordingSendElsewhere : R.string.Send));
        waveform.setVisibility(getWidth() != 0 && getWidth() < AndroidUtilities.dp(300) ? GONE : VISIBLE);
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
