package org.telegram.messenger.purple;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;

public final class PurpleRecordingSendAs {
    private static final Map<SendMessageChatArguments, PurpleRecordingSendIdentity> identities = Collections.synchronizedMap(new WeakHashMap<>());
    private PurpleRecordingSendAs() { }
    public static SendMessageChatArguments freeze(int account, long dialog, SendMessageChatArguments original) {
        PurpleRecordingSendIdentity frozen = identities.get(original);
        if (frozen != null && frozen.matches(account, UserConfig.getInstance(account).getClientUserId(), dialog)) return original;
        SendMessageChatArguments.Builder builder = new SendMessageChatArguments.Builder();
        if (original != null) {
            builder.setWelcomeMessageChatId(original.welcomeMessageChatId);
            builder.setQuickReplyShortcut(original.quickReplyShortcut, original.quickReplyShortcutId);
        }
        SendMessageChatArguments result = builder.build();
        long user = UserConfig.getInstance(account).getClientUserId();
        long peer = user;
        if (dialog < 0 && !DialogObject.isEncryptedDialog(dialog)) {
            MessagesController controller = MessagesController.getInstance(account);
            TLRPC.Chat chat = controller.getChat(-dialog);
            TLRPC.ChatFull full = controller.getChatFull(-dialog);
            if (full != null && full.default_send_as != null) peer = MessageObject.getPeerId(full.default_send_as);
            else if (chat != null && ((chat.admin_rights != null && chat.admin_rights.anonymous)
                    || ChatObject.isChannelAndNotMegaGroup(chat) && !chat.signatures)) peer = -chat.id;
        }
        identities.put(result, new PurpleRecordingSendIdentity(account, user, dialog, peer));
        return result;
    }
    public static boolean reject(int account, long dialog, SendMessageChatArguments arguments) {
        PurpleRecordingSendIdentity identity = identities.get(arguments);
        return !PurpleRecordingDelivery.allowed(arguments)
                || identity != null && !identity.matches(account, UserConfig.getInstance(account).getClientUserId(), dialog);
    }
    public static TLRPC.Peer apply(int account, long dialog, SendMessageChatArguments arguments, TLRPC.Peer fallback) {
        PurpleRecordingSendIdentity identity = identities.get(arguments);
        return identity != null && fallback != null && identity.matches(account, UserConfig.getInstance(account).getClientUserId(), dialog)
                ? MessagesController.getInstance(account).getPeer(identity.peer) : fallback;
    }
}
