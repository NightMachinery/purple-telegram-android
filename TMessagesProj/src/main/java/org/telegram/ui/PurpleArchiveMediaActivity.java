package org.telegram.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleArchiveMediaAccess;
import org.telegram.messenger.purple.PurpleArchiveMediaProvider;
import org.telegram.ui.ActionBar.Theme;

public final class PurpleArchiveMediaActivity extends Activity implements MediaController.MediaPlayerControl {
    private final java.util.concurrent.ThreadPoolExecutor io = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), r -> { Thread t = new Thread(r, "PurpleArchivePreview"); t.setDaemon(true); return t; });
    private final AtomicBoolean disposed = new AtomicBoolean();
    private PurpleArchiveMediaAccess.Capability capability;
    private volatile MediaPlayer player;
    private volatile int duration, position, audioSession;
    private volatile boolean playing, prepared;
    private TextView status;
    private ImageView image;
    private SurfaceView surface;
    private MediaController controller;
    private Bitmap bitmap;
    private Uri uri;
    private FrameLayout root;
    public static boolean supported(String mime) { return mime.startsWith("image/") || Build.VERSION.SDK_INT >= 26 && (mime.startsWith("audio/") || mime.startsWith("video/")); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); uri = getIntent().getData(); capability = PurpleArchiveMediaProvider.lookup(uri);
        root = new FrameLayout(this); root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite)); setContentView(root);
        status = new TextView(this); status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        status.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        status.setText(text(R.string.PurpleArchiveOpening)); root.addView(status, new FrameLayout.LayoutParams(-1, -2));
        if (capability == null || !supported(capability.mime)) { unavailable(); return; }
        capability.onRevoked(this::unavailable);
        if (capability.mime.startsWith("image/")) {
            image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); root.addView(image, 0, new FrameLayout.LayoutParams(-1, -1));
            enqueue(this::decode);
        } else {
            controller = new MediaController(this); controller.setMediaPlayer(this); controller.setAnchorView(root);
            root.setOnTouchListener((view, event) -> { if (prepared && capability.available()) controller.show(); return true; });
            if (capability.mime.startsWith("video/")) {
                surface = new SurfaceView(this); root.addView(surface, 0, new FrameLayout.LayoutParams(-1, -1));
                surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                    @Override public void surfaceCreated(SurfaceHolder holder) { enqueue(() -> openPlayer(holder)); }
                    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
                    @Override public void surfaceDestroyed(SurfaceHolder holder) { stop(); }
                });
            } else enqueue(() -> openPlayer(null));
        }
    }
    private void decode() {
        Bitmap decoded = null;
        try {
            if (!valid()) return;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(input, null, options); }
            if (options.outWidth <= 0 || options.outHeight <= 0 || !valid()) throw new java.io.IOException("Archive image unavailable");
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            try (InputStream input = getContentResolver().openInputStream(uri)) { decoded = BitmapFactory.decodeStream(input, null, options); }
            if (decoded == null) throw new java.io.IOException("Archive image codec unavailable");
            Bitmap result = decoded; decoded = null;
            AndroidUtilities.runOnUIThread(() -> { if (valid()) { bitmap = result; image.setImageBitmap(result); status.setVisibility(View.GONE); } else result.recycle(); });
        } catch (Exception | LinkageError e) { AndroidUtilities.runOnUIThread(this::unavailable); }
        finally { if (decoded != null) decoded.recycle(); }
    }
    private boolean valid() { return !disposed.get() && capability != null && capability.available(); }
    private void openPlayer(SurfaceHolder holder) {
        if (!valid() || player != null) return;
        try {
            MediaPlayer value = new MediaPlayer(); player = value;
            value.setOnPreparedListener(ready -> {
                enqueue(() -> {
                    if (!valid() || player != ready) return;
                    duration = ready.getDuration(); audioSession = ready.getAudioSessionId(); prepared = true; ready.start(); playing = true;
                    AndroidUtilities.runOnUIThread(() -> { if (valid()) { status.setVisibility(View.GONE); controller.show(); progress(); } });
                });
            });
            value.setOnCompletionListener(done -> { playing = false; position = duration; });
            value.setOnErrorListener((failed, what, extra) -> { AndroidUtilities.runOnUIThread(this::unavailable); return true; });
            if (holder != null) value.setDisplay(holder);
            value.setDataSource(this, uri); if (!valid()) throw new java.io.IOException("Archive player revoked"); value.prepareAsync();
        } catch (Exception | LinkageError e) { AndroidUtilities.runOnUIThread(this::unavailable); }
    }
    private void progress() {
        if (!valid() || !prepared) return;
        enqueue(() -> { try { MediaPlayer value = player; if (value != null && valid()) position = value.getCurrentPosition(); } catch (RuntimeException ignored) {} });
        AndroidUtilities.runOnUIThread(this::progress, 500);
    }
    private void unavailable() {
        status.setText(text(R.string.PurpleArchiveMediaUnavailable)); status.setVisibility(View.VISIBLE);
        if (image != null) image.setImageDrawable(null);
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        if (controller != null) controller.hide(); stop();
    }
    private void enqueue(Runnable action) {
        if (disposed.get()) return;
        try { io.execute(() -> { if (!disposed.get()) action.run(); }); }
        catch (java.util.concurrent.RejectedExecutionException e) { AndroidUtilities.runOnUIThread(this::unavailable); }
    }
    private void stop() {
        if (!disposed.compareAndSet(false, true)) return;
        playing = prepared = false;
        if (capability != null) capability.close();
        if (image != null) image.setImageDrawable(null);
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        io.getQueue().clear();
        io.execute(() -> { MediaPlayer value = player; player = null; if (value != null) { try { value.reset(); } catch (RuntimeException ignored) {} value.release(); } });
        io.shutdown();
    }
    @Override protected void onStop() { unavailable(); super.onStop(); }
    @Override protected void onDestroy() { stop(); if (bitmap != null) { bitmap.recycle(); bitmap = null; } super.onDestroy(); }
    @Override public void start() { enqueue(() -> { MediaPlayer value = player; if (value != null && valid() && prepared) { value.start(); playing = true; } }); }
    @Override public void pause() { enqueue(() -> { MediaPlayer value = player; if (value != null && prepared) { value.pause(); playing = false; } }); }
    @Override public int getDuration() { return duration; }
    @Override public int getCurrentPosition() { return position; }
    @Override public void seekTo(int millis) { enqueue(() -> { MediaPlayer value = player; if (value != null && valid() && prepared) value.seekTo(Math.max(0, Math.min(duration, millis))); }); }
    @Override public boolean isPlaying() { return playing; }
    @Override public int getBufferPercentage() { return 100; }
    @Override public boolean canPause() { return true; }
    @Override public boolean canSeekBackward() { return true; }
    @Override public boolean canSeekForward() { return true; }
    @Override public int getAudioSessionId() { return audioSession; }
    private static String text(int id) { return LocaleController.getString(id); }
}
