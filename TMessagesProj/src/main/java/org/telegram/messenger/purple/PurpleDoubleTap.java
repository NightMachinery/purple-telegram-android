package org.telegram.messenger.purple;

/**
 * Tells a second tap on the same target from a first one.
 *
 * Pure: the caller passes the clock and the timeout, so the host suite drives
 * it with a fake clock. One instance per tap surface (the in-chat cells share
 * one, the round picture-in-picture has its own), because a tap on the chat
 * and then one on the floating round are not a double tap.
 *
 * A second tap consumes the pair: a third tap within the timeout starts a new
 * pair instead of counting as another double tap. See docs/media-design.md, F1
 * and F2.
 */
public final class PurpleDoubleTap {

    private boolean armed;
    private int account;
    private long dialogId;
    private int messageId;
    private long at;

    /**
     * Records a tap and answers whether it completes a double tap.
     *
     * @param now     a monotonic clock in milliseconds
     * @param timeout the longest gap between the two taps, in milliseconds
     * @return true when the previous tap was on the same message and no more
     *         than {@code timeout} ago; the pair is then used up
     */
    public synchronized boolean tap(int account, long dialogId, int messageId, long now, long timeout) {
        final boolean second = armed
                && this.account == account
                && this.dialogId == dialogId
                && this.messageId == messageId
                && now >= at
                && now - at <= timeout;
        if (second) {
            armed = false;
            return true;
        }
        armed = true;
        this.account = account;
        this.dialogId = dialogId;
        this.messageId = messageId;
        at = now;
        return false;
    }

    /** Forgets a recorded first tap, so the next tap is a first one. */
    public synchronized void reset() {
        armed = false;
    }
}
