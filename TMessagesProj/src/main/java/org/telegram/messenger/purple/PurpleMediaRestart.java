package org.telegram.messenger.purple;

import android.os.SystemClock;
import android.view.ViewConfiguration;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;

/**
 * Double-tap on a playing voice or round message restarts it from 0:00.
 *
 * Two hooks in ChatMessageCell call this, each as one guarded early return at
 * the top of its function: {@link #onImageTap} from {@code didClickedImage},
 * which is where a tap inside a downloaded round's circle lands, and
 * {@link #onButton} from {@code didPressButton}, the voice play button (and a
 * round's when its transcription is open). They sit at the tops because the
 * functions below them branch on every message type, and an early return there
 * leaves all of that untouched.
 *
 * The first tap of a pair runs upstream as before (pause, resume or play), so
 * a playing message pauses for under one double-tap interval and then
 * restarts. Waiting on every single tap for a possible second one would avoid
 * that blip but would delay the common case.
 *
 * Each entry records a tap only for the taps it handles, so a round tap that
 * {@code didClickedImage} hands on to {@code didPressButton} (while it is
 * still downloading) is recorded once, not twice. View-once messages are never
 * restarted: they play in SecretVoicePlayer. See docs/media-design.md, F2.
 */
public final class PurpleMediaRestart {

    /** The in-chat taps; the floating round counts its own in PurpleRoundPip. */
    private static final PurpleDoubleTap TAPS = new PurpleDoubleTap();

    private PurpleMediaRestart() {
    }

    /**
     * From the top of {@code ChatMessageCell.didClickedImage}.
     *
     * @return true when this tap restarted the message and the cell must do
     *         nothing else with it
     */
    public static boolean onImageTap(MessageObject message, int buttonState) {
        if (message == null || !imageTapCounts(message.isRoundVideo(), buttonState)) return false;
        return tapped(message);
    }

    /**
     * From the top of {@code ChatMessageCell.didPressButton}.
     *
     * @return true when this tap restarted the message and the cell must do
     *         nothing else with it
     */
    public static boolean onButton(MessageObject message, int buttonState) {
        if (message == null || !buttonCounts(message.isVoice()
                || message.isRoundVideo() && message.isVoiceTranscriptionOpen(), buttonState)) return false;
        return tapped(message);
    }

    /**
     * Whether a tap inside the media counts: only a round that has finished
     * downloading, which draws no button ({@code buttonState == -1}). A round
     * still downloading goes on to {@code didPressButton}, which counts it.
     */
    static boolean imageTapCounts(boolean round, int buttonState) {
        return round && buttonState == -1;
    }

    /**
     * Whether a press of the play button counts: a voice message, or a round
     * whose transcription is open (then its button is play and pause), showing
     * play (0) or pause (1). A round without an open transcription reads 0 and
     * 1 as download and cancel, so the caller never counts it here.
     */
    static boolean buttonCounts(boolean voiceOrRound, int buttonState) {
        return voiceOrRound && (buttonState == 0 || buttonState == 1);
    }

    /** Whether a completed double tap restarts: the message is the playing one and not view-once. */
    static boolean restarts(boolean playing, boolean viewOnce) {
        return playing && !viewOnce;
    }

    private static boolean tapped(MessageObject message) {
        final boolean second = TAPS.tap(message.currentAccount, message.getDialogId(), message.getId(),
                SystemClock.uptimeMillis(), ViewConfiguration.getDoubleTapTimeout());
        if (!second) return false;
        final MediaController player = MediaController.getInstance();
        if (!restarts(player.isPlayingMessage(message), message.isVoiceOnce() || message.isRoundOnce())) return false;
        return restart(message);
    }

    /**
     * Plays the playing message again from 0:00, resuming it if paused; also
     * the floating round's double tap (PurpleRoundPip).
     *
     * @return false when the player could not seek, so the caller's upstream
     *         handling runs instead
     */
    static boolean restart(MessageObject message) {
        final MediaController player = MediaController.getInstance();
        if (!player.seekToProgress(message, 0f)) return false;
        if (player.isMessagePaused()) player.playMessage(message);
        return true;
    }
}
