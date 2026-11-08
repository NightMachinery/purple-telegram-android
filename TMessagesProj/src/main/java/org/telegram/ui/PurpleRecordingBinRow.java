package org.telegram.ui;

import android.content.Context;
import android.graphics.Outline;
import android.net.Uri;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleRecordingEntry;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.VideoPlayer;
import java.io.File;

public final class PurpleRecordingBinRow extends LinearLayout {
    private final PurpleRecordingEntry entry;
    private final ImageView toggle;
    private final PlayPauseDrawable icon = new PlayPauseDrawable(20);
    private final SeekBarView seek;
    private final TextView elapsed, total;
    private final FrameLayout preview;
    private final TextureView texture;
    private final Runnable activate, failure;
    private VideoPlayer player;
    private boolean dragging;
    private final Runnable tick = this::progress;

    public PurpleRecordingBinRow(Context context, Theme.ResourcesProvider resources, PurpleRecordingEntry entry,
            String title, String details, Runnable menu, Runnable activate, Runnable failure) {
        super(context);
        this.entry = entry;
        this.activate = activate;
        this.failure = failure;
        setOrientation(VERTICAL);
        setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(12), AndroidUtilities.dp(18), AndroidUtilities.dp(12));
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resources));
        setOnLongClickListener(v -> { menu.run(); return true; });
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(context, resources, 16, false);
        name.setText(title);
        name.setTypeface(AndroidUtilities.bold());
        name.setOnClickListener(v -> menu.run());
        ImageView more = new ImageView(context);
        more.setImageResource(R.drawable.msg_actions);
        more.setScaleType(ImageView.ScaleType.CENTER);
        more.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resources));
        more.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resources), 1));
        more.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        more.setOnClickListener(v -> menu.run());
        if (LocaleController.isRTL) {
            heading.addView(more, LayoutHelper.createLinear(48, 48));
            heading.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1));
        } else {
            heading.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1));
            heading.addView(more, LayoutHelper.createLinear(48, 48));
        }
        addView(heading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView metadata = text(context, resources, 14, true);
        metadata.setText(details);
        addView(metadata, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        preview = new FrameLayout(context);
        texture = new TextureView(context);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        preview.setClipToOutline(true);
        preview.addView(texture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(preview, LayoutHelper.createLinear(180, 180, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 4));
        preview.setVisibility(GONE);
        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        toggle = new ImageView(context);
        toggle.setScaleType(ImageView.ScaleType.CENTER);
        toggle.setImageDrawable(icon);
        icon.setColor(Theme.getColor(Theme.key_chat_inMediaIcon, resources));
        toggle.setBackground(Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(44),
                Theme.getColor(Theme.key_chat_inAudioProgress, resources), Theme.getColor(Theme.key_chat_inAudioSelectedProgress, resources)));
        toggle.setOnClickListener(v -> toggle());
        seek = new SeekBarView(context, resources);
        seek.setColors(Theme.getColor(Theme.key_chat_inAudioSeekbar, resources), Theme.getColor(Theme.key_chat_inAudioSeekbarFill, resources));
        seek.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override public void onSeekBarDrag(boolean stop, float fraction) {
                if (player != null) player.seekTo((long) (fraction * duration()));
                elapsed.setText(time((long) (fraction * duration())));
            }
            @Override public void onSeekBarPressed(boolean pressed) { dragging = pressed; }
            @Override public CharSequence getContentDescription() { return LocaleController.getString(R.string.PurpleRecordingBinPlay); }
        });
        controls.addView(toggle, LayoutHelper.createLinear(48, 48));
        controls.addView(seek, LayoutHelper.createLinear(0, 48, 1));
        addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 6, 0, 0));
        LinearLayout times = new LinearLayout(context);
        elapsed = text(context, resources, 12, true);
        total = text(context, resources, 12, true);
        elapsed.setText(time(0)); total.setText(time(entry.durationMs)); total.setGravity(Gravity.END);
        times.addView(elapsed, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1));
        times.addView(total, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        addView(times, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 48, 0, 0, 0));
        boolean playable = !PurpleRecordingEntry.UNRECOVERABLE.equals(entry.state) && entry.media != null;
        toggle.setEnabled(playable); seek.setEnabled(playable);
        label(false);
    }

    private static TextView text(Context context, Theme.ResourcesProvider resources, int size, boolean secondary) {
        TextView text = new TextView(context);
        text.setTextSize(size);
        text.setTextColor(Theme.getColor(secondary ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText, resources));
        text.setGravity(Gravity.START);
        return text;
    }

    public void toggle() {
        if (player == null || !player.getPlayWhenReady()) activate.run();
        if (player != null) {
            if (player.getCurrentPosition() >= duration()) player.seekTo(0);
            player.setPlayWhenReady(!player.getPlayWhenReady());
            return;
        }
        final VideoPlayer active = new VideoPlayer();
        player = active;
        active.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override public void onStateChanged(boolean play, int state) {
                if (player != active) return;
                if (state == androidx.media3.common.Player.STATE_ENDED) active.setPlayWhenReady(false);
                label(active.getPlayWhenReady()); progress();
            }
            @Override public void onError(VideoPlayer failed, Exception error) { if (player == active) { stop(); failure.run(); } }
            @Override public void onVideoSizeChanged(int width, int height, int rotation, float ratio) {}
            @Override public void onRenderedFirstFrame() {}
        });
        if (entry.round()) {
            preview.setVisibility(VISIBLE);
            active.setTextureView(texture);
        }
        active.preparePlayer(Uri.fromFile(new File(entry.media)), "other");
        active.setPlayWhenReady(true);
        progress();
    }

    private long duration() {
        return player != null && player.getDuration() > 0 ? player.getDuration() : Math.max(1, entry.durationMs);
    }
    private static String time(long ms) { return AndroidUtilities.formatShortDuration((int) (Math.max(0, ms) / 1000)); }
    private void label(boolean playing) {
        icon.setPause(playing, false);
        toggle.setContentDescription(LocaleController.getString(playing ? R.string.AccActionPause : R.string.AccActionPlay));
    }
    private void progress() {
        removeCallbacks(tick);
        if (player == null) return;
        long position = Math.max(0, Math.min(player.getCurrentPosition(), duration()));
        if (!dragging) { seek.setProgress(position / (float) duration()); elapsed.setText(time(position)); }
        total.setText(time(duration()));
        label(player.getPlayWhenReady());
        if (player.getPlayWhenReady()) postDelayed(tick, 100);
    }
    public void pause() { if (player != null) player.setPlayWhenReady(false); }
    public void stop() {
        removeCallbacks(tick);
        VideoPlayer active = player; player = null;
        if (active != null) active.releasePlayer(true);
        preview.setVisibility(GONE);
        label(false);
        seek.setProgress(0); elapsed.setText(time(0));
    }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
