package org.telegram.messenger.purple;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewConfiguration;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

/**
 * The floating round video (PipRoundVideoView): double tap restarts, long
 * press opens the message.
 *
 * A single tap keeps upstream's pause or resume, and dragging is untouched. A
 * double tap restarts the round from 0:00, the same as a double tap on the
 * round in the chat (PurpleMediaRestart) and a double click on Desktop's
 * floating player: the owner's rule is that restart wins the double gesture on
 * both clients. It undoes nothing explicitly; the restart resumes a round the
 * first tap paused.
 *
 * A long press (held for the long-press timeout without moving far enough to
 * start a drag) opens the chat at the message, the way back that upstream's
 * PiP lacks; only the player bar at the top of a screen has one. It takes the
 * player bar's route (FragmentContextView): {@code scrollToMessageId} when that
 * chat is on top, otherwise a new ChatActivity with the message id. That route
 * already runs Work Mode's chat-open policy, so a chat the running preset hides
 * behaves exactly as it does from the player bar. It acts on release, because
 * the PiP's touch handler sees the press only then. A round played from a
 * channel's admin log ({@code eventId != 0}) has no message to open, so its
 * long press stays upstream's toggle.
 *
 * The hook is one line in the {@code ACTION_UP} branch of the PiP's
 * {@code onTouchEvent}, right after it reads the playing message: when this
 * returns true it clears that local, so upstream's toggle below is skipped
 * while the drag state reset and the snap to the screen edge after it still
 * run. An early return there would skip those. See docs/media-design.md, F1.
 */
public final class PurpleRoundPip {

    /** What a tap on the PiP does: upstream's toggle, or Purple's restart, scroll or open. */
    static final int TOGGLE = 0, SCROLL = 1, OPEN = 2, RESTART = 3;

    private static final PurpleDoubleTap TAPS = new PurpleDoubleTap();

    private PurpleRoundPip() {
    }

    /**
     * From the PiP's tap.
     *
     * @param message the playing message the PiP is about to pause or resume
     * @param pressed how long the finger was down, in milliseconds
     * @return true when this tap was handled here and upstream must not toggle
     */
    public static boolean onTap(MessageObject message, long pressed) {
        if (message == null) return false;
        final boolean longPress = pressed >= ViewConfiguration.getLongPressTimeout();
        // A long press is not half of a double tap.
        final boolean second = !longPress && TAPS.tap(message.currentAccount, message.getDialogId(), message.getId(),
                SystemClock.uptimeMillis(), ViewConfiguration.getDoubleTapTimeout());
        if (longPress) TAPS.reset();
        final MediaController player = MediaController.getInstance();
        final BaseFragment top = LaunchActivity.getSafeLastFragment();
        final int action = action(longPress, second, !message.deleted && player.isPlayingMessage(message),
                message.eventId != 0, top != null && top.getCurrentAccount() == message.currentAccount,
                top instanceof ChatActivity && ((ChatActivity) top).getDialogId() == message.getDialogId());
        switch (action) {
        case RESTART:
            return PurpleMediaRestart.restart(message);
        case SCROLL:
            ((ChatActivity) top).scrollToMessageId(message.getId(), 0, false, 0, true, 0);
            return true;
        case OPEN:
            top.presentFragment(new ChatActivity(chatArgs(message.getDialogId(), message.getId())), top instanceof ChatActivity);
            return true;
        default:
            return false;
        }
    }

    /**
     * What a tap does.
     *
     * @param longPress   whether the finger was held for the long-press timeout
     * @param second      whether a short tap completes a double tap on the same message
     * @param exists      whether the message is still the playing one and not deleted
     * @param logEvent    whether it plays from a channel's admin log
     * @param sameAccount whether the screen on top belongs to the message's account
     * @param chatOnTop   whether the message's own chat is the screen on top
     */
    static int action(boolean longPress, boolean second, boolean exists, boolean logEvent,
            boolean sameAccount, boolean chatOnTop) {
        if (!exists) return TOGGLE;
        if (longPress) {
            if (logEvent || !sameAccount) return TOGGLE;
            return chatOnTop ? SCROLL : OPEN;
        }
        return second ? RESTART : TOGGLE;
    }

    /**
     * Whether a playing message that is off the visible list goes to the
     * floating round rather than the chat scrolling to it; see
     * {@code PurpleChatHooks.roundLeavesToPip}.
     *
     * @param round         whether it is a round (the floating view is round-only)
     * @param autoAdvance   upstream's {@code needScroll}: the queue just moved to
     *                      a round within five messages of the previous one
     * @param queueOnScreen whether a message within those five is on screen
     */
    public static boolean leavesToPip(boolean round, boolean autoAdvance, boolean queueOnScreen) {
        return round && !(autoAdvance && queueOnScreen);
    }

    /** The arguments the player bar opens a chat with. */
    private static Bundle chatArgs(long dialogId, int messageId) {
        final Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        args.putInt("message_id", messageId);
        return args;
    }
}
