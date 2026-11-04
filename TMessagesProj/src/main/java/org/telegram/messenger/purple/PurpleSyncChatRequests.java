package org.telegram.messenger.purple;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import org.telegram.messenger.MessagesController;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatRequests {
    static TLObject exact(Peer target, int id, TLRPC.InputPeer peer) {
        if (target == null || id <= 0 || !matches(target, peer)) return null;
        if (target.kind == PeerKind.Channel) {
            TLRPC.TL_channels_getMessages request = new TLRPC.TL_channels_getMessages();
            request.channel = channel(peer); request.id.add(id); return request;
        }
        TLRPC.TL_messages_getMessages request = new TLRPC.TL_messages_getMessages(); request.id.add(id); return request;
    }

    static TLObject full(Peer target, TLRPC.InputPeer peer) {
        if (target == null || !matches(target, peer)) return null;
        if (target.kind == PeerKind.Self || target.kind == PeerKind.User) {
            TLRPC.TL_users_getFullUser request = new TLRPC.TL_users_getFullUser();
            if (target.kind == PeerKind.Self) request.id = new TLRPC.TL_inputUserSelf();
            else { TLRPC.TL_inputUser user = new TLRPC.TL_inputUser(); user.user_id = target.id; user.access_hash = peer.access_hash; request.id = user; }
            return request;
        } else if (target.kind == PeerKind.Chat) {
            TLRPC.TL_messages_getFullChat request = new TLRPC.TL_messages_getFullChat(); request.chat_id = target.id; return request;
        }
        TLRPC.TL_channels_getFullChannel request = new TLRPC.TL_channels_getFullChannel(); request.channel = channel(peer); return request;
    }

    static TLRPC.TL_channels_getParticipants members(Peer target, TLRPC.InputPeer peer) {
        if (target == null || target.kind != PeerKind.Channel || !matches(target, peer)) return null;
        TLRPC.TL_channels_getParticipants request = new TLRPC.TL_channels_getParticipants();
        request.channel = channel(peer); request.filter = new TLRPC.TL_channelParticipantsRecent(); request.limit = 200; return request;
    }

    static TLRPC.InputChannel channel(TLRPC.InputPeer peer) {
        TLRPC.TL_inputChannel channel = new TLRPC.TL_inputChannel(); channel.channel_id = peer.channel_id; channel.access_hash = peer.access_hash; return channel;
    }

    static boolean matches(Peer target, TLRPC.InputPeer peer) {
        if (target == null || peer == null) return false;
        switch (target.kind) {
            case Self: return peer.getClass() == TLRPC.TL_inputPeerSelf.class;
            case User: return peer.getClass() == TLRPC.TL_inputPeerUser.class && peer.user_id == target.id && peer.access_hash != 0;
            case Chat: return peer.getClass() == TLRPC.TL_inputPeerChat.class && peer.chat_id == target.id;
            case Channel: return peer.getClass() == TLRPC.TL_inputPeerChannel.class && peer.channel_id == target.id && peer.access_hash != 0;
            default: return false;
        }
    }

            static TLRPC.InputPeer input(PurpleSyncChatOrigin origin, Peer peer, java.util.function.BooleanSupplier current) {
                if (!current.getAsBoolean() || peer == null) return null;
                if (peer.kind == PeerKind.Self) return new TLRPC.TL_inputPeerSelf();
                MessagesController mc = MessagesController.getInstance(origin.slot);
                if (peer.kind == PeerKind.Chat) { TLRPC.TL_inputPeerChat value = new TLRPC.TL_inputPeerChat(); value.chat_id = peer.id; return value; }
                if (peer.kind == PeerKind.User) {
                    TLRPC.User user = mc.getUser(peer.id); if (user == null || user.min || user.deleted || user.access_hash == 0) return null;
                    TLRPC.TL_inputPeerUser value = new TLRPC.TL_inputPeerUser(); value.user_id = peer.id; value.access_hash = user.access_hash; return value;
                }
                TLRPC.Chat chat = mc.getChat(peer.id); if (chat == null || chat.min || chat.access_hash == 0) return null;
                TLRPC.TL_inputPeerChannel value = new TLRPC.TL_inputPeerChannel(); value.channel_id = peer.id; value.access_hash = chat.access_hash; return value;
            }
    private PurpleSyncChatRequests() { }
}
