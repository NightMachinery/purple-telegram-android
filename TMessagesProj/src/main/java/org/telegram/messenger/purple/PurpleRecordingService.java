package org.telegram.messenger.purple;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.telegram.messenger.*;

public final class PurpleRecordingService extends Service {
    private static final String CHANNEL = "purple_recording";
    private static final int ID = 0x70750520;
    private static PurpleRecordingService instance;
    private PowerManager.WakeLock wakeLock;
    private long generation;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (instance != PurpleRecordingService.this) return;
            update();
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    public static void start() {
        Context context = ApplicationLoader.applicationContext;
        try {
            Intent intent = new Intent(context, PurpleRecordingService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException e) {
            PurpleRecordingDock.serviceReady(false);
        }
    }
    public static void stop() {
        ApplicationLoader.applicationContext.stopService(new Intent(ApplicationLoader.applicationContext, PurpleRecordingService.class));
    }
    public static void refresh() { if (instance != null) instance.update(); }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "purple:recording");
        wakeLock.setReferenceCounted(false);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, LocaleController.getString(R.string.PurpleRecording), NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!PurpleRecordingDock.state.active()) { stopSelf(); return START_NOT_STICKY; }
        generation = PurpleRecordingDock.state.generation;
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID, notification);
            PurpleRecordingDock.serviceReady(true);
        } catch (RuntimeException e) {
            PurpleRecordingDock.serviceLost();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getAction() != null) {
            long generation = intent.getLongExtra("generation", -1);
            if (PurpleRecordingDock.valid(generation)) {
                switch (intent.getAction()) {
                case "pause": PurpleRecordingDock.pause(generation); break;
                case "send": PurpleRecordingDock.send(generation); break;
                case "discard":
                    if (PurpleRecordingDock.state.confirmDiscard(SystemClock.elapsedRealtime())) PurpleRecordingDock.discard(generation);
                    break;
                }
            }
        }
        AndroidUtilities.cancelRunOnUIThread(tick);
        AndroidUtilities.runOnUIThread(tick);
        return START_NOT_STICKY;
    }

    private PendingIntent action(String action, int code) {
        Intent intent = new Intent(this, PurpleRecordingService.class).setAction(action);
        intent.setData(android.net.Uri.parse(PurpleRecordingDock.state.actionIdentity(action)));
        intent.putExtra("generation", PurpleRecordingDock.state.generation);
        return PendingIntent.getService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        PurpleRecordingDockState state = PurpleRecordingDock.state;
        long duration = state.duration(SystemClock.elapsedRealtime());
        PendingIntent open = PendingIntent.getActivity(this, ID, PurpleRecordingDock.openIntent(), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.notification).setContentTitle(PurpleRecordingDock.title())
                .setContentText(LocaleController.getString(state.paused ? R.string.PurpleRecordingPaused : R.string.PurpleRecordingVoice)
                        + (state.paused ? " " + android.text.format.DateUtils.formatElapsedTime(duration / 1000) : ""))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setWhen(System.currentTimeMillis() - duration).setUsesChronometer(!state.paused)
                .addAction(0, LocaleController.getString(state.paused ? R.string.PurpleRecordingResume : R.string.PurpleRecordingPause), action("pause", 1))
                .addAction(0, LocaleController.getString(R.string.Send), action("send", 2))
                .addAction(0, LocaleController.getString(state.discardArmed(SystemClock.elapsedRealtime()) ? R.string.PurpleRecordingDiscardAgain : R.string.DiscardVoiceMessageAction), action("discard", 3));
        return builder.build();
    }
    private void update() {
        PurpleRecordingDockState state = PurpleRecordingDock.state;
        if (!state.active()) { stopSelf(); return; }
        if (!state.paused && !state.finishing) {
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } else if (wakeLock.isHeld()) wakeLock.release();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID, notification());
    }
    @Override public void onDestroy() {
        AndroidUtilities.cancelRunOnUIThread(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (instance == this) instance = null;
        if (PurpleRecordingDock.state.generation == generation) PurpleRecordingDock.serviceLost();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
