package org.telegram.messenger.purple;

import android.support.v4.media.session.PlaybackStateCompat;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;

/**
 * F9, seek back and forward (docs/media-recording-design.md): the strip's seek
 * buttons and the media session's rewind and fast-forward both jump the
 * playing voice, round or music message by the step in
 * {@code [playback] seek_step} (5, 10, 15 or 30 s, default 10).
 *
 * A seek before the start goes to 0:00. A seek forward stops one second
 * before the end, so the queue does not advance by surprise; when the
 * position is already inside that last second, a seek forward stays put
 * rather than jumping back.
 */
public final class PurpleSeek {

    /** The steps the core accepts, in seconds. */
    static final int[] STEPS = {5, 10, 15, 30};

    /** How far before the end a seek forward stops, in milliseconds. */
    static final long END_MARGIN_MS = 1000;

    private PurpleSeek() {
    }

    /**
     * The step in seconds, read from the loaded settings each time so a
     * changed step applies to the next press. A value outside {@link #STEPS}
     * (a core that could not be asked answers with the default) is the
     * default.
     */
    public static int stepSeconds() {
        return validStep(PurpleCore.seekStepSeconds());
    }

    static int validStep(int seconds) {
        for (int step : STEPS) {
            if (step == seconds) {
                return seconds;
            }
        }
        return PurpleCore.SEEK_STEP_DEFAULT;
    }

    /**
     * Where a seek lands.
     *
     * @param positionMs  the current position
     * @param durationMs  the message's duration; zero or less is unknown
     * @param direction   -1 back, +1 forward
     * @param stepSeconds the step
     * @return the target in milliseconds, or -1 when there is nothing to seek:
     *         an unknown duration, a direction of 0, or a target equal to the
     *         position
     */
    static long targetMs(long positionMs, long durationMs, int direction, int stepSeconds) {
        if (durationMs <= 0 || direction == 0) {
            return -1;
        }
        final long position = Math.max(0, Math.min(positionMs, durationMs));
        long target = position + (direction < 0 ? -1L : 1L) * stepSeconds * 1000L;
        if (direction > 0) {
            target = Math.min(target, Math.max(position, durationMs - END_MARGIN_MS));
        }
        target = Math.max(0, target);
        return target == position ? -1 : target;
    }

    /**
     * Jumps the playing message one step back ({@code direction < 0}) or
     * forward. Called from the strip's buttons and from the media session's
     * {@code onRewind} and {@code onFastForward}, on the main thread.
     *
     * @return whether a seek was made
     */
    public static boolean step(int direction) {
        final MediaController controller = MediaController.getInstance();
        final MessageObject playing = controller.getPlayingMessageObject();
        if (playing == null) {
            return false;
        }
        final long durationMs = (long) (playing.getDuration() * 1000);
        final long positionMs = (long) (playing.audioProgress * durationMs);
        final long target = targetMs(positionMs, durationMs, direction, stepSeconds());
        if (target < 0) {
            return false;
        }
        return controller.seekToProgress(playing, target / (float) durationMs);
    }

    /**
     * The actions the media session adds to upstream's, so headsets, car
     * systems and watches can send rewind and fast-forward; the session's
     * default {@code onMediaButtonEvent} maps {@code KEYCODE_MEDIA_REWIND} and
     * {@code KEYCODE_MEDIA_FAST_FORWARD} to them.
     */
    public static long sessionActions() {
        return PlaybackStateCompat.ACTION_REWIND | PlaybackStateCompat.ACTION_FAST_FORWARD;
    }

    /** The button label, "−10" or "+10", with a real minus sign. */
    public static String label(int direction, int stepSeconds) {
        return (direction < 0 ? "−" : "+") + stepSeconds;
    }
}
