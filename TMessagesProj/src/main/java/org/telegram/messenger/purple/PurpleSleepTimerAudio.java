package org.telegram.messenger.purple;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.VideoPlayer;
import java.util.function.BooleanSupplier;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

/** Retains stock player ownership while classifying manual requests and delayed automatic starts. */
public final class PurpleSleepTimerAudio implements PurpleSleepTimer.Port {
    private final MediaController controller;
    private final PurpleSleepTimer timer = PurpleSleepTimer.get();
    private int replacing, automatic;
    private boolean restoring;
    private final PurpleSleepTimerSavedSong.Retirement retirement = new PurpleSleepTimerSavedSong.Retirement();
    private Ticket download, focus, rewind;
    private VideoPlayer player;
    private static MessageObject heldSong;
    public static boolean heldSong(MessageObject message) { return message != null && heldSong == message; }

    public PurpleSleepTimerAudio(MediaController controller) { this.controller = controller; }

    public boolean play(MessageObject message, BooleanSupplier stock) {
        timer.check();
        if (message == null) return stock.getAsBoolean();
        final Item item = PurpleSleepTimer.item(message);
        if (item == null) return stock.getAsBoolean();
        if (automatic == 0) {
            if (timer.takePendingAdvance(item, false)) controller.seekToProgress(message, 0);
            timer.explicitPlay(Slot.AUDIO);
        } else if (!restoring && timer.blocked(Slot.AUDIO)) return false;
        replacing++;
        try {
            player = java.util.Objects.equals(item, timer.current(Slot.AUDIO)) ? player : null;
            timer.attach(Slot.AUDIO, item, message.audioProgress < 1f, player, this);
            heldSong = null;
            final boolean result = stock.getAsBoolean();
            download = timer.ticket(Slot.AUDIO);
            if (timer.blocked(Slot.AUDIO)) controller.purpleSleepPauseLocal();
            return result;
        } finally {
            replacing--;
            if (controller.getPlayingMessageObject() == null) timer.detach(timer.ticket(Slot.AUDIO));
        }
    }

    public void bind(VideoPlayer player, MessageObject message) {
        this.player = player;
        if (timer.owns(Slot.VIEWER, player) && java.util.Objects.equals(PurpleSleepTimer.item(message), timer.current(Slot.VIEWER))) {
            timer.handoff(timer.ticket(Slot.VIEWER), Slot.AUDIO, this);
        } else {
            timer.attach(Slot.AUDIO, PurpleSleepTimer.item(message), message != null && message.audioProgress < 1f, player, this);
        }
    }

    public void unloaded(boolean transfer, Ticket closing) {
        if (replacing != 0 || transfer) return;
        timer.detach(closing);
        if (timer.current(Slot.AUDIO) == null) { player = null; heldSong = null; }
    }

    public void pauseRequested() {
        if (automatic == 0 && !timer.localPause()) timer.explicitPause(Slot.AUDIO);
    }

    public boolean autoPlay(MessageObject message, Ticket request) {
        if (!timer.automatic(request)) return false;
        automatic++;
        try { return controller.playMessage(message); } finally { automatic--; }
    }

    public boolean downloaded(MessageObject message) { return autoPlay(message, download); }
    public void captureFocus() { focus = timer.ticket(Slot.AUDIO); }
    public boolean focus(MessageObject message) { return autoPlay(message, focus); }
    public Ticket capture() { return timer.ticket(Slot.AUDIO); }
    public PurpleSleepTimer.Deferred defer() { return timer.defer(player); }
    public boolean allowDeferred(PurpleSleepTimer.Deferred event) { return timer.allowDeferred(player, event); }
    public boolean automatic(Ticket ticket) { return timer.automatic(ticket); }

    public void pauseAutomatic(MessageObject message) {
        automatic++;
        try { controller.pauseMessage(message); } finally { automatic--; }
    }

    public boolean retiring() { return retirement.active(); }

    public boolean retireBeforeRestore(boolean stopService, Runnable cleanup, BooleanSupplier restore) {
        return retirement.run(stopService && !timer.localPause() && timer.blocked(Slot.AUDIO)
                && controller.purpleHasInterruptedMusic(), cleanup, restore);
    }

    public boolean restore(MessageObject message, BooleanSupplier stock) {
        if (timer.blocked(Slot.AUDIO)) return timer.locally(() -> {
            adoptHeld(message);
            return true;
        });
        final boolean previous = restoring;
        automatic++;
        restoring = true;
        try { return stock.getAsBoolean(); } finally { restoring = previous; automatic--; }
    }

    private void adoptHeld(MessageObject selected) {
        controller.purpleSleepAdoptSong(selected);
        heldSong = selected;
        player = null;
        timer.attach(Slot.AUDIO, PurpleSleepTimer.item(selected), true, null, this);
        org.telegram.messenger.NotificationCenter.getInstance(selected.currentAccount).postNotificationName(
                org.telegram.messenger.NotificationCenter.messagePlayingPlayStateChanged, selected.getId());
    }

    public void changeRoute(boolean paused, Runnable stock) {
        timer.check();
        final MessageObject current = controller.getPlayingMessageObject();
        if (current != null) {
            if (paused) timer.explicitPause(Slot.AUDIO);
            else if (!heldSong(current) && (current.isVoice() || current.isRoundVideo())) timer.routePlay(Slot.AUDIO);
        }
        final boolean previous = restoring;
        automatic++;
        replacing++;
        restoring = true;
        try { stock.run(); } finally { restoring = previous; replacing--; automatic--; }
    }

    public boolean allowRemotePlay() {
        timer.check();
        return !timer.localPause() && !timer.blocked(Slot.AUDIO);
    }

    public Runnable routePause(MessageObject message) {
        final Ticket request = capture();
        return () -> {
            if (timer.valid(request)) pauseAutomatic(message);
        };
    }

    public void beginRewind() { rewind = timer.ticket(Slot.AUDIO); }
    public boolean endRewind() { return timer.automatic(rewind); }
    public void automaticRun(Ticket ticket, Runnable action) {
        if (!timer.automatic(ticket)) return;
        automatic++;
        replacing++;
        try { action.run(); } finally { replacing--; automatic--; }
    }
    public void resumeRewind(Runnable stock) { automaticRun(rewind, stock); }
    public boolean beforeAutomaticEnd() { timer.check(); return !timer.blocked(Slot.AUDIO); }
    public boolean ended(VideoPlayer player) { return timer.realEnd(player); }

    public PurpleSleepTimerSavedSong.Selection saved(MessageObject message, float progress, int ms, int seconds) {
        return new PurpleSleepTimerSavedSong.Selection(PurpleSleepTimer.item(message), progress, ms, seconds);
    }

    public void finishVoice(PurpleSleepTimerSavedSong.Selection saved,
            java.util.List<MessageObject> list, int index, Runnable cleanup) {
        final MessageObject selected = list != null && index >= 0 && index < list.size() ? list.get(index) : null;
        cleanup.run();
        PurpleSleepTimerSavedSong.restore(saved, PurpleSleepTimer.item(selected), state -> {
            selected.forceSeekTo = selected.audioProgress = state.progress;
            selected.audioProgressMs = state.progressMs;
            selected.audioProgressSec = state.progressSec;
            adoptHeld(selected);
        });
    }

    public boolean seekHeld(MessageObject message, float progress, long ms) {
        if (!heldSong(message)) return false;
        final long duration = (long) (message.getDuration() * 1000);
        if (duration <= 0) return false;
        final long target = Math.max(0, Math.min(duration, ms >= 0 ? ms : (long) (progress * duration)));
        message.forceSeekTo = message.audioProgress = target / (float) duration;
        message.audioProgressMs = (int) target;
        message.audioProgressSec = (int) (target / 1000);
        org.telegram.messenger.NotificationCenter.getInstance(message.currentAccount).postNotificationName(
                org.telegram.messenger.NotificationCenter.messagePlayingDidSeek, message.getId(), message.audioProgress);
        return true;
    }

    public boolean advance(java.util.List<MessageObject> list, int previous,
            BooleanSupplier traverse, java.util.function.IntConsumer select,
            java.util.function.IntSupplier selected, Runnable beforePlay) {
        if (list.isEmpty()) return false;
        if (traverse.getAsBoolean()) { select.accept(previous); return false; }
        beforePlay.run();
        return controller.playMessage(list.get(selected.getAsInt()));
    }

    public void pauseLocal(android.animation.ValueAnimator fade, Runnable pause,
            boolean downloading, Runnable pauseDownload) {
        if (fade != null) { fade.removeAllListeners(); fade.cancel(); }
        pause.run();
        if (downloading) pauseDownload.run();
    }

    public static boolean updateLocalTransport(MediaController controller, java.util.function.LongConsumer update) {
        if (!PurpleSleepTimer.get().localPause()) return false;
        final MessageObject message = controller.getPlayingMessageObject();
        update.accept(message == null ? 0 : message.audioProgressMs);
        return true;
    }

    public static void transportPlay(MediaController controller) {
        final PurpleSleepTimer timer = PurpleSleepTimer.get();
        timer.check();
        final MessageObject message = controller.getPlayingMessageObject();
        if (timer.takePendingAdvance(PurpleSleepTimer.item(message), false)) {
            if (controller.purpleSleepAdvance()) return;
            controller.seekToProgress(message, 0);
        }
        controller.playMessage(message);
    }

    @Override public boolean eligibleEnd() {
        return player == null ? controller.getPlayingMessageObject() != null
                : player.getPlaybackState() != androidx.media3.common.Player.STATE_ENDED
                    && (player.player == null || !player.player.isCurrentMediaItemLive());
    }
    @Override public boolean playing() {
        return controller.getPlayingMessageObject() != null && !controller.isMessagePaused();
    }
    @Override public void fenceAutomaticResumes() { controller.purpleSleepFence(); }
    @Override public void pauseLocal() { controller.purpleSleepPauseLocal(); }
    @Override public void finishVoiceLocally() { controller.purpleSleepFinishVoice(); }
}
