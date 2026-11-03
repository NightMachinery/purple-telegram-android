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
 * The floating round video (PipRoundVideoView): a double tap opens its message.
 *
 * Upstream's PiP pauses or resumes on a tap and has no way back to the
 * message; only the player bar at the top of a screen has one. A double tap
 * here undoes the first tap's pause or resume, as Desktop's floating player
 * does on a double click, and then opens the chat at the message by the same
 * route the player bar takes (FragmentContextView): {@code scrollToMessageId}
 * when that chat is on top, otherwise a new ChatActivity with the message id.
 * That route already runs Work Mode's chat-open policy, so a chat the running
 * preset hides behaves exactly as it does from the player bar.
 *
 * The hook is one line in the {@code ACTION_UP} branch of the PiP's
 * {@code onTouchEvent}, right after it reads the playing message: when this
 * returns true it clears that local, so upstream's toggle below is skipped
 * while the drag state reset and the snap to the screen edge after it still
 * run. An early return there would skip those. See docs/media-design.md, F1.
 */
public final class PurpleRoundPip {

    /** What a tap on the PiP does beyond upstream's toggle. */
    static final int TOGGLE = 0, SCROLL = 1, OPEN = 2;

    private static final PurpleDoubleTap TAPS = new PurpleDoubleTap();

    private PurpleRoundPip() {
    }

    /**
     * From the PiP's tap.
     *
     * @param message the playing message the PiP is about to pause or resume
     * @return true when this tap was handled here and upstream must not toggle
     */
    public static boolean onTap(MessageObject message) {
        if (message == null) return false;
        final boolean second = TAPS.tap(message.currentAccount, message.getDialogId(), message.getId(),
                SystemClock.uptimeMillis(), ViewConfiguration.getDoubleTapTimeout());
        final MediaController player = MediaController.getInstance();
        final BaseFragment top = LaunchActivity.getSafeLastFragment();
        final int action = action(second, !message.deleted && player.isPlayingMessage(message),
                top != null && top.getCurrentAccount() == message.currentAccount,
                top instanceof ChatActivity && ((ChatActivity) top).getDialogId() == message.getDialogId());
        if (action == TOGGLE) return false;
        // Undo the first tap: it paused a playing round or resumed a paused one.
        if (player.isMessagePaused()) player.playMessage(message);
        else player.pauseMessage(message);
        if (action == SCROLL) {
            ((ChatActivity) top).scrollToMessageId(message.getId(), 0, false, 0, true, 0);
        } else {
            top.presentFragment(new ChatActivity(chatArgs(message.getDialogId(), message.getId())), top instanceof ChatActivity);
        }
        return true;
    }

    /**
     * What a tap does.
     *
     * @param second    whether it completes a double tap on the same message
     * @param exists    whether the message is still the playing one and not deleted
     * @param sameAccount whether the screen on top belongs to the message's account
     * @param chatOnTop whether the message's own chat is the screen on top
     */
    static int action(boolean second, boolean exists, boolean sameAccount, boolean chatOnTop) {
        if (!second || !exists || !sameAccount) return TOGGLE;
        return chatOnTop ? SCROLL : OPEN;
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
