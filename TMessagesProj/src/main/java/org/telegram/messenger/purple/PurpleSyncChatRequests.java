package org.telegram.messenger.purple;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatRequests {
    enum Search { Global, Broadcasts, Self, Dialog, SettingsHint }
    enum PageStatus { Next, Complete, Capped, Incomplete }

    static final class Cursor {
        final int rate, id;
        final Peer peer;
        Cursor(int rate, Peer peer, int id) { this.rate = rate; this.peer = peer; this.id = id; }
        static Cursor first() { return new Cursor(0, null, 0); }
    }

    static final class Page {
        final List<TLRPC.Message> messages = new ArrayList<>();
        final List<Cache> positions = new ArrayList<>();
        int total;
        Integer nextRate;
        boolean full, recognized;
    }

    static TLRPC.TL_messages_searchGlobal global(Search kind, Cursor cursor, TLRPC.InputPeer offset) {
        if (kind != Search.Global && kind != Search.Broadcasts || cursor == null || cursor.rate < 0 || cursor.id < 0
            || cursor.peer == null && (cursor.id != 0 || cursor.rate != 0) || cursor.peer != null && (cursor.id <= 0 || !matches(cursor.peer, offset))) return null;
        TLRPC.TL_messages_searchGlobal request = new TLRPC.TL_messages_searchGlobal();
        request.broadcasts_only = kind == Search.Broadcasts;
        request.q = "#purplechatsync"; request.filter = new TLRPC.TL_inputMessagesFilterEmpty(); request.limit = 100;
        request.offset_rate = cursor.rate; request.offset_id = cursor.id;
        request.offset_peer = cursor.peer == null ? new TLRPC.TL_inputPeerEmpty() : offset;
        return request;
    }

    static TLRPC.TL_messages_search search(Search kind, Peer target, TLRPC.InputPeer peer, int offsetId) {
        if (kind != Search.Self && kind != Search.Dialog && kind != Search.SettingsHint || target == null || !matches(target, peer)
            || kind != Search.Dialog && target.kind != PeerKind.Self || offsetId < 0) return null;
        boolean hint = kind == Search.SettingsHint;
        TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = peer; request.q = hint ? "#purplesync" : "#purplechatsync";
        request.filter = hint ? new TLRPC.TL_inputMessagesFilterDocument() : new TLRPC.TL_inputMessagesFilterEmpty();
        request.limit = hint ? 1 : 100; request.offset_id = offsetId; request.saved_reaction = null;
        return request;
    }

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

    static Page page(PurpleSyncChatOrigin origin, TLObject response, Search kind, Peer target) {
        Page result = new Page();
        if (origin == null || response == null || kind == null || (kind == Search.Self || kind == Search.SettingsHint) && !Peer.self().equals(target)
            || kind == Search.Dialog && target == null || !(response instanceof TLRPC.messages_Messages)) return result;
        TLRPC.messages_Messages data = (TLRPC.messages_Messages) response;
        if (data.getClass() == TLRPC.TL_messages_messages.class) { result.recognized = result.full = true; result.total = data.messages.size(); }
        else if (data.getClass() == TLRPC.TL_messages_messagesSlice.class) {
            result.recognized = !data.inexact && (data.flags & (1 << 1)) == 0 && data.search_flood == null && (data.flags & (1 << 3)) == 0;
            result.total = data.count; if ((data.flags & 1) != 0) result.nextRate = data.next_rate;
        } else if (data.getClass() == TLRPC.TL_messages_channelMessages.class && kind == Search.Dialog && target.kind == PeerKind.Channel) {
            result.recognized = !data.inexact && (data.flags & (1 << 1)) == 0; result.total = data.count;
        } else return result;
        for (TLRPC.Message message : data.messages) {
            Peer chat = message != null ? PurpleSyncChatFacts.token(message.peer_id, origin.userId) : null;
            if (message == null || message.getClass() != TLRPC.TL_message.class || chat == null || message.id <= 0 || message.date <= 0 || target != null && !target.equals(chat)) {
                result.recognized = false; return result;
            }
            result.messages.add(message); result.positions.add(new Cache(chat, message.id, message.date));
        }
        return result;
    }

    static final class Pages {
        private final PurpleSyncChatOrigin origin;
        private final Search kind;
        private final Set<String> seen = new HashSet<>(), cursors = new HashSet<>();
        private Cursor cursor = Cursor.first();
        private Integer total;
        private int pages;
        private boolean finished;
        Pages(PurpleSyncChatOrigin origin, Search kind) { this.origin = origin; this.kind = kind; }
        PageStatus add(Page page) {
            if (finished || origin == null || page == null || !page.recognized || kind == Search.SettingsHint || page.total < 0
                || page.positions.size() > 100 || total != null && total != page.total || page.nextRate != null && page.nextRate < 0) return incomplete();
            ++pages; total = page.total;
            for (Cache position : page.positions) {
                if (position.chat == null || position.markId <= 0 || position.markDate <= 0) return incomplete();
                seen.add(position.chat.token() + "/" + position.markId);
            }
            if (seen.size() > page.total || page.full && seen.size() != page.total) return incomplete();
            if (seen.size() == page.total) { finished = true; return PageStatus.Complete; }
            if (page.positions.isEmpty()) return incomplete();
            Cache last = page.positions.get(page.positions.size() - 1);
            Cursor next = new Cursor(page.nextRate != null ? page.nextRate : 0, last.chat, last.markId);
            String key = next.rate + "/" + next.peer.token() + "/" + next.id;
            if (!cursors.add(key) || cursor.id != 0 && (kind == Search.Self || kind == Search.Dialog) && next.id >= cursor.id) return incomplete();
            cursor = next;
            if (pages >= 20) { finished = true; return PageStatus.Capped; }
            return PageStatus.Next;
        }
        private PageStatus incomplete() { finished = true; return PageStatus.Incomplete; }
        Cursor cursor() { return cursor; }
        int count() { return pages; }
    }
    private PurpleSyncChatRequests() { }
}
