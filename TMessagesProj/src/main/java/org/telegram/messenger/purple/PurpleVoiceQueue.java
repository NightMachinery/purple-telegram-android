package org.telegram.messenger.purple;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;

/**
 * The private-chat voice queue ({@code [playback] private_chat_queue_p},
 * default on): in a one-to-one chat, when your manual play starts on the other
 * person's voice or round message, "play next" plays only their messages and
 * skips yours. A manual play on your own message, in Saved Messages, in a group
 * or in a channel changes nothing. The rule itself is the core's
 * ({@link PurpleCore#voiceQueueKeeps}); this class asks it twice when a manual
 * play starts (does the queue keep an outgoing candidate, and an incoming
 * one) and keeps both answers in the anchor, so every later decision of that
 * queue, the live append included, is a field read and no JNI call.
 *
 * Android rebuilds the queue only on a manual play: every caller of
 * {@code ChatActivity.createVoiceMessagesPlaylist} is a user action (a tap,
 * the visible-video autoplay, raise-to-ear), and automatic advance just walks
 * the list that build made. So the anchor is made inside that build, and the
 * setting is read there too: a toggle takes effect from the next manual play,
 * never in the middle of a queue.
 *
 * Two hooks call this:
 * <ul>
 * <li>{@link #skipsInPlaylist}, through {@code PurpleChatHooks}, as one
 *     guarded {@code continue} in {@code createVoiceMessagesPlaylist}'s loop,
 *     before upstream's filter. The loop visits every loaded message of the
 *     chat oldest first, the started one included and before every later
 *     one, so the visit of the started message makes the anchor. (A start
 *     with id 0 never reaches the loop; it makes no anchor, and an older
 *     anchor of its dialog stays until the next manual play.)</li>
 * <li>{@link #skipsAppended}, as one guarded {@code continue} in
 *     {@code MediaController.didReceivedNotification}'s
 *     {@code didReceiveNewMessages} append loop, so a voice message you send
 *     while listening (a reply you record) does not join a confined queue.</li>
 * </ul>
 * Unread mode (raise-to-ear) already keeps only incoming messages, so there
 * both hooks only agree with upstream. See docs/media-design.md, F3.
 */
public final class PurpleVoiceQueue {

    /** The core's rule, as the host suite swaps it for a stub. */
    interface Rule {
        boolean keeps(int chat, boolean startOutgoing, boolean candidateOutgoing);
    }

    /** What one manual play decided. */
    static final class Anchor {
        final int account;
        final long dialogId;
        final int startId;
        final int chat;
        final boolean startOutgoing;
        final boolean confined;
        private final boolean keepsOutgoing;
        private final boolean keepsIncoming;

        Anchor(int account, long dialogId, int startId, int chat, boolean startOutgoing, Rule rule) {
            this.account = account;
            this.dialogId = dialogId;
            this.startId = startId;
            this.chat = chat;
            this.startOutgoing = startOutgoing;
            // The rule's two answers for this queue, read once: a settings
            // change applies from the next manual play, never mid-queue.
            this.keepsOutgoing = rule.keeps(chat, startOutgoing, true);
            this.keepsIncoming = rule.keeps(chat, startOutgoing, false);
            this.confined = !keepsOutgoing || !keepsIncoming;
        }

        boolean startedBy(int account, long dialogId, int startId) {
            return this.account == account && this.dialogId == dialogId && this.startId == startId;
        }

        /** Whether this queue drops a candidate in its own chat. */
        boolean skips(boolean candidateOutgoing) {
            return !(candidateOutgoing ? keepsOutgoing : keepsIncoming);
        }
    }

    private static final Rule CORE = PurpleCore::voiceQueueKeeps;

    private static Anchor anchor;

    private PurpleVoiceQueue() {
    }

    /**
     * From {@code createVoiceMessagesPlaylist}'s loop, for each message it
     * visits.
     *
     * @return true when {@code candidate} must stay out of the queue that a
     *         manual play on {@code start} builds
     */
    public static boolean skipsInPlaylist(MessageObject start, MessageObject candidate) {
        if (start == null || candidate == null) return false;
        // The loop visits the started message once per build, before every
        // later one, so that visit re-reads the setting for this manual play.
        final boolean visitingStart = candidate.getId() == start.getId() && candidate.getDialogId() == start.getDialogId();
        final Anchor current = anchorFor(start.currentAccount, start.getDialogId(), start.getId(),
                chatKind(start.currentAccount, start.getDialogId()), start.isOut(), visitingStart, CORE);
        return current.skips(candidate.isOut());
    }

    /**
     * From MediaController's live append, for each new message of the
     * playing queue's dialog.
     *
     * @return true when {@code candidate} must not join the playing queue
     */
    public static boolean skipsAppended(MessageObject candidate) {
        if (candidate == null) return false;
        return skipsAppended(candidate.currentAccount, candidate.getDialogId(), candidate.isOut());
    }

    /**
     * Whether the switch is on: a queue started on their message in a
     * one-to-one chat drops yours exactly when {@code [playback]
     * private_chat_queue_p} is on, so this asks the core that question
     * instead of keeping a second copy of the setting.
     */
    public static boolean enabled() {
        return !PurpleCore.voiceQueueKeeps(PurpleCore.PLAYBACK_ONE_TO_ONE, false, true);
    }

    static synchronized Anchor anchorFor(int account, long dialogId, int startId, int chat, boolean startOutgoing,
            boolean fresh, Rule rule) {
        if (fresh || anchor == null || !anchor.startedBy(account, dialogId, startId)) {
            anchor = new Anchor(account, dialogId, startId, chat, startOutgoing, rule);
        }
        return anchor;
    }

    static synchronized boolean skipsAppended(int account, long dialogId, boolean outgoing) {
        return anchor != null && anchor.account == account && anchor.dialogId == dialogId
                && anchor.skips(outgoing);
    }

    /** Forgets the anchor; for the host suite. */
    static synchronized void forget() {
        anchor = null;
    }

    private static int chatKind(int account, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) return PurpleCore.PLAYBACK_ONE_TO_ONE;
        if (!DialogObject.isUserDialog(dialogId)) return PurpleCore.PLAYBACK_OTHER;
        return chatKind(dialogId == UserConfig.getInstance(account).getClientUserId(),
                UserObject.isReplyUser(dialogId) || dialogId == UserObject.VERIFY
                        || dialogId == UserObject.ANONYMOUS || UserObject.isService(dialogId));
    }

    /**
     * A user chat, as the rule sees it: Saved Messages is Self, the service
     * chats (Replies, Verification Codes, anonymous admins, Telegram's own
     * notices) are Other, and every other user, bots included, is one-to-one.
     * Secret chats are one-to-one and never reach this.
     */
    static int chatKind(boolean self, boolean service) {
        if (self) return PurpleCore.PLAYBACK_SELF;
        return service ? PurpleCore.PLAYBACK_OTHER : PurpleCore.PLAYBACK_ONE_TO_ONE;
    }
}
