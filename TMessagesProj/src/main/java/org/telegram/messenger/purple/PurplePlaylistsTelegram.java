package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class PurplePlaylistsTelegram implements PurplePlaylistsResolver.Port {
    private final PurplePlaylistsAccount.Captured account;
    private final BooleanSupplier scope;
    private final MessagesController controller;
    private final ConnectionsManager connection;
    private boolean closed;
    private int requestId;
    private long generation;

    PurplePlaylistsTelegram(PurplePlaylistsAccount.Captured account, BooleanSupplier scope) {
        if (account == null || scope == null) throw new IllegalArgumentException("Missing resolver authority");
        this.account = account; this.scope = scope;
        controller = MessagesController.getInstance(account.identity.slot);
        connection = ConnectionsManager.getInstance(account.identity.slot);
    }

    @Override public boolean current() {
        return !closed && scope.getAsBoolean() && account.current()
                && controller == MessagesController.getInstance(account.identity.slot)
                && connection == ConnectionsManager.getInstance(account.identity.slot);
    }

    @Override public void defer(Runnable task) { AndroidUtilities.runOnUIThread(task, 1); }

    @Override public void read(PurplePlaylistsView.Source source, Consumer<PurplePlaylistsResolver.Reply> done) {
        if (!current()) return;
        if (source.peer.startsWith("channel:")) {
            long id = Long.parseLong(source.peer.substring(8));
            TLRPC.Chat chat = controller.getChat(id);
            if (!(chat instanceof TLRPC.TL_channel) || chat.min || chat.access_hash == 0) {
                resolve(source, id, done);
                return;
            }
        }
        exact(source, done);
    }

    private void resolve(PurplePlaylistsView.Source source, long id, Consumer<PurplePlaylistsResolver.Reply> done) {
        if (source.username == null || source.username.isEmpty()) {
            done.accept(PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.NoAccess, 0));
            return;
        }
        TLRPC.TL_contacts_resolveUsername request = new TLRPC.TL_contacts_resolveUsername();
        request.username = source.username;
        send(request, (value, error) -> {
            if (error != null) { done.accept(failure(error)); return; }
            if (!(value instanceof TLRPC.TL_contacts_resolvedPeer)) {
                done.accept(PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.Failed, 0)); return;
            }
            TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) value;
            TLRPC.Chat found = null;
            for (TLRPC.Chat chat : resolved.chats) if (chat.id == id) found = chat;
            if (!(resolved.peer instanceof TLRPC.TL_peerChannel) || resolved.peer.channel_id != id
                    || !(found instanceof TLRPC.TL_channel) || found.min || found.access_hash == 0) {
                done.accept(PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.NoAccess, 0)); return;
            }
            controller.putUsers(resolved.users, false); controller.putChats(resolved.chats, false);
            if (current()) exact(source, done);
        });
    }

    private void exact(PurplePlaylistsView.Source source, Consumer<PurplePlaylistsResolver.Reply> done) {
        if (!current()) return;
        TLObject request;
        if (source.peer.startsWith("channel:")) {
            long id = Long.parseLong(source.peer.substring(8));
            TLRPC.Chat chat = controller.getChat(id);
            if (!(chat instanceof TLRPC.TL_channel) || chat.min || chat.access_hash == 0) {
                done.accept(PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.NoAccess, 0)); return;
            }
            TLRPC.TL_channels_getMessages get = new TLRPC.TL_channels_getMessages();
            TLRPC.TL_inputChannel channel = new TLRPC.TL_inputChannel();
            channel.channel_id = id; channel.access_hash = chat.access_hash;
            get.channel = channel; get.id.add(source.messageId); request = get;
        } else {
            TLRPC.TL_messages_getMessages get = new TLRPC.TL_messages_getMessages();
            get.id.add(source.messageId); request = get;
        }
        send(request, (value, error) -> {
            if (error != null) { done.accept(failure(error)); return; }
            if (!(value instanceof TLRPC.TL_messages_messages)
                    && !(value instanceof TLRPC.TL_messages_messagesSlice)
                    && !(value instanceof TLRPC.TL_messages_channelMessages)) {
                done.accept(PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.Failed, 0)); return;
            }
            TLRPC.messages_Messages page = (TLRPC.messages_Messages) value;
            if (page.messages.size() > 1) {
                done.accept(PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.Failed, 0)); return;
            }
            TLRPC.Message message = page.messages.isEmpty() ? null : page.messages.get(0);
            if (message != null && message.id != source.messageId) {
                done.accept(PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.Failed, 0)); return;
            }
            controller.putUsers(page.users, false); controller.putChats(page.chats, false);
            long chatId = message == null || message.peer_id == null ? 0
                    : message.peer_id instanceof TLRPC.TL_peerChannel ? message.peer_id.channel_id
                    : message.peer_id instanceof TLRPC.TL_peerChat ? message.peer_id.chat_id : 0;
            TLRPC.Chat chat = chatId == 0 ? null : controller.getChat(chatId);
            boolean restricted = chat != null && controller.getRestrictionReason(chat.restriction_reason) != null;
            done.accept(new PurplePlaylistsResolver.Reply(message, null, 0,
                    chat != null && chat.noforwards, restricted));
        });
    }

    private interface Response { void accept(TLObject value, TLRPC.TL_error error); }

    private void send(TLObject request, Response done) {
        if (!current()) return;
        long epoch = ++generation;
        requestId = connection.sendRequest(request, (value, error) -> defer(() -> {
            if (!current() || epoch != generation) return;
            ++generation;
            requestId = 0;
            done.accept(value, error);
        }));
    }

    static PurplePlaylistsResolver.Reply failure(TLRPC.TL_error error) {
        String text = error.text == null ? "" : error.text;
        for (String prefix : new String[] {"FLOOD_WAIT_", "FLOOD_PREMIUM_WAIT_", "SLOWMODE_WAIT_"}) {
            if (text.startsWith(prefix)) {
                try {
                    long seconds = Long.parseLong(text.substring(prefix.length()));
                    return PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.Wait,
                            (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds)));
                } catch (NumberFormatException e) {
                    return PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.Failed, 0);
                }
            }
        }
        if (text.equals("CHANNEL_PRIVATE") || text.equals("CHANNEL_INVALID")
                || text.equals("CHAT_ADMIN_REQUIRED") || text.equals("USER_BANNED_IN_CHANNEL")
                || text.equals("USERNAME_NOT_OCCUPIED") || text.equals("USERNAME_INVALID"))
            return PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.NoAccess, 0);
        if (text.equals("MESSAGE_ID_INVALID"))
            return PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.Deleted, 0);
        return PurplePlaylistsResolver.Reply.failed(PurplePlaylistsResolver.Status.Failed, 0);
    }

    @Override public void close() {
        if (closed) return;
        closed = true; ++generation;
        if (requestId != 0) connection.cancelRequest(requestId, true);
        requestId = 0;
    }
}
