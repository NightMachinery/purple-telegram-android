package org.telegram.messenger.purple;

import android.support.v4.media.session.PlaybackStateCompat;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;

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

    /** The steps the Seek step row offers, in seconds. */
    public static int[] choices() {
        return STEPS.clone();
    }

    /** How settings.toml spells a step: {@code "10s"}. */
    public static String settingValue(int seconds) {
        return validStep(seconds) + "s";
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

    /** The playing message as {@link #step(int, Player, int)} sees it. */
    interface Player {
        /** The player's live position, or a negative value when nothing plays. */
        long positionMs();

        /** The duration; zero or less is unknown. */
        long durationMs();

        /** Seeks to {@code ms}; whether the player took it. */
        boolean seekMs(long ms);
    }

    /**
     * Jumps the playing message one step back ({@code direction < 0}) or
     * forward. Called from the strip's buttons and from the media session's
     * {@code onRewind}, {@code onFastForward} and seek custom actions, on the
     * main thread.
     *
     * @return whether a seek was made
     */
    public static boolean step(int direction) {
        final MediaController controller = MediaController.getInstance();
        final MessageObject playing = controller.getPlayingMessageObject();
        return playing != null && step(direction, new Live(controller, playing), stepSeconds());
    }

    /**
     * The seek itself, from the player's own position and duration rather
     * than the message's cached progress: a paused round's {@code videoPlayer}
     * branch never writes {@code audioProgress}, and a message's attribute
     * duration can disagree with the file's.
     */
    static boolean step(int direction, Player player, int stepSeconds) {
        final long positionMs = player.positionMs();
        if (positionMs < 0) {
            return false;
        }
        final long target = targetMs(positionMs, player.durationMs(), direction, stepSeconds);
        return target >= 0 && player.seekMs(target);
    }

    /** The real player: {@code MediaController}'s live position and its millisecond seek. */
    private static final class Live implements Player {
        private final MediaController controller;
        private final MessageObject playing;

        Live(MediaController controller, MessageObject playing) {
            this.controller = controller;
            this.playing = playing;
        }

        @Override
        public long positionMs() {
            return controller.getProgressMs(playing);
        }

        /**
         * The audio player's duration when it has one, else the duration a
         * player last reported for this message (either player writes it),
         * else the message's own.
         */
        @Override
        public long durationMs() {
            final long audio = controller.getDuration();
            if (audio > 0) {
                return audio;
            }
            if (playing.audioPlayerDuration > 0) {
                return playing.audioPlayerDuration * 1000L;
            }
            return (long) (playing.getDuration() * 1000);
        }

        /**
         * Seeks, then writes the message's progress and posts a progress
         * change, so a paused message's cell and the floating round show the
         * new position at once (the player's progress timer does neither
         * while paused).
         */
        @Override
        public boolean seekMs(long ms) {
            if (!controller.seekToProgressMs(playing, ms)) {
                return false;
            }
            final long duration = durationMs();
            if (duration > 0) {
                final float progress = Math.max(0f, Math.min(1f, ms / (float) duration));
                playing.audioProgress = progress;
                playing.audioProgressSec = (int) (ms / 1000);
                NotificationCenter.getInstance(playing.currentAccount).postNotificationName(
                        NotificationCenter.messagePlayingProgressDidChanged, playing.getId(), progress);
            }
            return true;
        }
    }

    /** The media session's custom action names for the seek buttons. */
    static final String ACTION_BACK = "org.telegram.purple.SEEK_BACK";
    static final String ACTION_FORWARD = "org.telegram.purple.SEEK_FORWARD";

    /**
     * The actions the media session adds to upstream's, so headsets, car
     * systems and watches can send rewind and fast-forward; the session's
     * default {@code onMediaButtonEvent} maps {@code KEYCODE_MEDIA_REWIND} and
     * {@code KEYCODE_MEDIA_FAST_FORWARD} to them.
     *
     * It also adds two custom actions to {@code state}, "Back N s" and
     * "Forward N s" at the current step, so the system media controls (the
     * notification and the lock screen on Android 13 and later, which draw a
     * session's custom actions) show seek buttons. Only for a voice or round
     * message, which has no previous and next, so they take those two slots:
     * music keeps its system controls as upstream draws them, because custom
     * actions added here come before upstream's shuffle and repeat and would
     * push those out of the slots the system shows (see
     * {@link #seekButtons}). {@link #onCustomAction} handles them.
     */
    public static long sessionActions(PlaybackStateCompat.Builder state) {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (state != null && seekButtons(playing != null && playing.isMusic())) {
            final int step = stepSeconds();
            state.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(ACTION_BACK,
                    LocaleController.formatString(R.string.PurpleSeekBack, step), R.drawable.purple_seek_back).build());
            state.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(ACTION_FORWARD,
                    LocaleController.formatString(R.string.PurpleSeekForward, step), R.drawable.purple_seek_forward).build());
        }
        return SESSION_ACTIONS;
    }

    /** Rewind and fast-forward, which {@link #sessionActions} adds for every message. */
    static final long SESSION_ACTIONS = PlaybackStateCompat.ACTION_REWIND | PlaybackStateCompat.ACTION_FAST_FORWARD;

    /**
     * Whether the session's state carries the seek custom actions: not for
     * music, whose shuffle and repeat custom actions would otherwise lose
     * their slots in the system media controls.
     */
    static boolean seekButtons(boolean music) {
        return !music;
    }

    /**
     * The media session callback's early return for the seek custom actions.
     *
     * @return whether {@code action} was one of Purple's, which then seeks
     */
    public static boolean onCustomAction(String action) {
        final int direction = direction(action);
        if (direction == 0) {
            return false;
        }
        step(direction);
        return true;
    }

    static int direction(String action) {
        return ACTION_BACK.equals(action) ? -1 : ACTION_FORWARD.equals(action) ? 1 : 0;
    }

    /** The button label, "−10" or "+10", with a real minus sign. */
    public static String label(int direction, int stepSeconds) {
        return "\u2066" + (direction < 0 ? "−" : "+") + stepSeconds + "\u2069";
    }
}
