package org.telegram.messenger.purple;

import org.telegram.tgnet.TLRPC;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatFacts {
    public static final class Input {
        public final PurpleSyncChatOrigin origin;
        public final Peer chat;
        public TLRPC.User user;
        public TLRPC.Chat group;
        public TLRPC.UserFull userFull;
        public TLRPC.ChatFull groupFull;
        public Boolean canSendFiles;
        public Peer chosenSendAs;
        public Input(PurpleSyncChatOrigin origin, Peer chat) { this.origin = Objects.requireNonNull(origin); this.chat = Objects.requireNonNull(chat); }
    }

    public static final class MappedPeer {
        public final PurpleSyncChatOrigin origin;
        public final Peer chat;
        public final Kind kind;
        public final Truth creator, soleAdmin;
        public final ChatFacts facts;
        public final Set<Long> memberIds;
        MappedPeer(PurpleSyncChatOrigin origin, Peer chat, Kind kind, Truth creator, Truth soleAdmin, ChatFacts facts, Set<Long> members) {
            this.origin = origin; this.chat = chat; this.kind = kind; this.creator = creator; this.soleAdmin = soleAdmin; this.facts = facts;
            memberIds = members != null ? java.util.Collections.unmodifiableSet(new HashSet<>(members)) : null;
        }
        public Eligibility eligibility() { return PurpleSyncChatCore.eligibility(facts, Options.restricted()); }
    }

    public static Peer token(TLRPC.Peer peer, long selfUserId) {
        if (peer == null || selfUserId <= 0) return null;
        if (peer.getClass() == TLRPC.TL_peerUser.class && peer.user_id > 0 && peer.chat_id == 0 && peer.channel_id == 0)
            return peer.user_id == selfUserId ? Peer.self() : Peer.of(PeerKind.User, peer.user_id);
        if (peer.getClass() == TLRPC.TL_peerChat.class && peer.chat_id > 0 && peer.user_id == 0 && peer.channel_id == 0) return Peer.of(PeerKind.Chat, peer.chat_id);
        if (peer.getClass() == TLRPC.TL_peerChannel.class && peer.channel_id > 0 && peer.user_id == 0 && peer.chat_id == 0) return Peer.of(PeerKind.Channel, peer.channel_id);
        return null;
    }

    public static MappedPeer peer(PurpleSyncChatOrigin origin, Input input) {
        Kind kind = Kind.Unknown; Truth creator = Truth.Unknown, soleAdmin = Truth.Unknown;
        if (origin == null || input == null || !origin.equals(input.origin) || !input.chat.equals(Peer.parse(input.chat.token(), origin.userId)))
            return new MappedPeer(origin, input != null ? input.chat : null, kind, creator, soleAdmin, null, null);
        ChatFacts facts = new ChatFacts(); Set<Long> members = null;
        if (input.chat.kind == PeerKind.Self || input.chat.kind == PeerKind.User) {
            TLRPC.User user = input.user; long expected = input.chat.kind == PeerKind.Self ? origin.userId : input.chat.id;
            if (user == null || user.getClass() != TLRPC.TL_user.class || user.id != expected || user.min || user.deleted
                || user.self != (input.chat.kind == PeerKind.Self)) return unknown(origin, input.chat, kind, creator, soleAdmin);
            facts.kind = kind = user.self ? Kind.Self : user.bot ? Kind.Bot : Kind.Private;
            TLRPC.UserFull full = input.userFull;
            if (full == null || full.getClass() != TLRPC.TL_userFull.class || full.id != expected) return unknown(origin, input.chat, kind, creator, soleAdmin);
            facts.autoDelete = full.ttl_period != 0; facts.protectedContent = full.noforwards_my_enabled || full.noforwards_peer_enabled;
            facts.paidMessages = full.send_paid_messages_stars != 0 || user.send_paid_messages_stars != 0;
            if (kind == Kind.Self) facts.canSendFiles = true;
            else if (full.blocked || full.contact_require_premium || user.contact_require_premium) facts.canSendFiles = false;
            else if (input.canSendFiles != null) facts.canSendFiles = input.canSendFiles;
            else return unknown(origin, input.chat, kind, creator, soleAdmin);
            if (kind == Kind.Self) return new MappedPeer(origin, input.chat, kind, creator, soleAdmin, facts, null);
        } else if (input.chat.kind == PeerKind.Chat) {
            TLRPC.Chat chat = input.group;
            if (chat == null || chat.getClass() != TLRPC.TL_chat.class || chat.id != input.chat.id) return unknown(origin, input.chat, kind, creator, soleAdmin);
            facts.kind = kind = Kind.BasicGroup; creator = truth(chat.creator); facts.creator = chat.creator;
            facts.migrated = chat.deactivated || chat.migrated_to != null;
            TLRPC.ChatFull full = input.groupFull;
            if (full == null || full.getClass() != TLRPC.TL_chatFull.class || full.id != input.chat.id) return unknown(origin, input.chat, kind, creator, soleAdmin);
            facts.protectedContent = chat.noforwards; facts.autoDelete = full.ttl_period != 0;
            facts.admin = chat.creator || chat.admin_rights != null; facts.anonymousAdmin = chat.admin_rights != null && chat.admin_rights.anonymous;
            if (full.participants != null && full.participants.getClass() == TLRPC.TL_chatParticipants.class) {
                if (full.participants.chat_id != input.chat.id) return unknown(origin, input.chat, kind, creator, soleAdmin);
                members = new HashSet<>(); long admins = 0; boolean selfAdmin = false;
                for (TLRPC.ChatParticipant participant : full.participants.participants) {
                    if (participant == null || participant.user_id <= 0 || !members.add(participant.user_id)) return unknown(origin, input.chat, kind, creator, soleAdmin);
                    boolean admin = participant.getClass() == TLRPC.TL_chatParticipantCreator.class || participant.getClass() == TLRPC.TL_chatParticipantAdmin.class;
                    if (!admin && participant.getClass() != TLRPC.TL_chatParticipant.class) return unknown(origin, input.chat, kind, creator, soleAdmin);
                    if (admin) ++admins;
                    selfAdmin |= admin && participant.user_id == origin.userId;
                }
                if (!chat.left && !chat.kicked && members.contains(origin.userId)) { facts.otherMembers = (long) members.size() - 1; facts.otherAdmins = admins - (selfAdmin ? 1 : 0); }
                else members = null;
            }
            if (input.canSendFiles == null) return unknown(origin, input.chat, kind, creator, soleAdmin);
            facts.canSendFiles = !chat.left && !chat.kicked && input.canSendFiles;
        } else if (input.chat.kind == PeerKind.Channel) {
            TLRPC.Chat chat = input.group;
            if (chat == null || chat.getClass() != TLRPC.TL_channel.class || chat.id != input.chat.id || chat.min || chat.broadcast == chat.megagroup)
                return unknown(origin, input.chat, kind, creator, soleAdmin);
            facts.kind = kind = chat.monoforum ? Kind.ChannelDirectMessages : chat.megagroup ? Kind.Supergroup : Kind.Broadcast;
            facts.creator = chat.creator; creator = truth(chat.creator);
            TLRPC.ChatFull full = input.groupFull;
            if (full == null || full.getClass() != TLRPC.TL_channelFull.class || full.id != input.chat.id) return unknown(origin, input.chat, kind, creator, soleAdmin);
            facts.publicUsername = chat.username != null && !chat.username.isEmpty();
            for (TLRPC.TL_username username : chat.usernames) if (username != null && username.active) facts.publicUsername = true;
            facts.protectedContent = chat.noforwards; facts.forum = chat.forum; facts.linkedDiscussion = full.linked_chat_id != 0 || (full.flags & (1 << 14)) != 0;
            facts.autoDelete = full.ttl_period != 0; facts.paidMessages = full.send_paid_messages_stars != 0 || chat.send_paid_messages_stars != 0;
            facts.slowMode = chat.slowmode_enabled || full.slowmode_seconds != 0; facts.admin = chat.creator || chat.admin_rights != null;
            facts.anonymousAdmin = chat.admin_rights != null && chat.admin_rights.anonymous;
            if (full.default_send_as != null) {
                Peer chosen = token(full.default_send_as, origin.userId);
                if (chosen == null) return unknown(origin, input.chat, kind, creator, soleAdmin);
                facts.postsAsOther = !chosen.equals(Peer.self());
            } else if ((full.flags & (1 << 29)) != 0) return unknown(origin, input.chat, kind, creator, soleAdmin);
            if ((full.flags & (1 << 1)) != 0 && full.admins_count > 0) {
                soleAdmin = truth(full.admins_count == 1); facts.soleAdmin = full.admins_count == 1;
                facts.otherAdmins = (long) full.admins_count - (facts.admin ? 1 : 0);
            }
            if (!chat.left && !chat.kicked && (full.flags & 1) != 0 && full.participants_count > 0) facts.otherMembers = (long) full.participants_count - 1;
            if (input.canSendFiles == null || kind == Kind.Broadcast && soleAdmin == Truth.Unknown) return unknown(origin, input.chat, kind, creator, soleAdmin);
            facts.canSendFiles = !chat.left && !chat.kicked && input.canSendFiles;
        }
        if (kind == Kind.Unknown || input.chosenSendAs == null || !input.chosenSendAs.equals(Peer.parse(input.chosenSendAs.token(), origin.userId)))
            return unknown(origin, input.chat, kind, creator, soleAdmin);
        facts.postsAsOther |= !input.chosenSendAs.equals(Peer.self());
        return new MappedPeer(origin, input.chat, kind, creator, soleAdmin, facts, members);
    }

    public static MessageFacts message(PurpleSyncChatOrigin origin, MappedPeer peer, TLRPC.Message message) {
        MessageFacts result = new MessageFacts();
        if (origin == null || peer == null || !origin.equals(peer.origin) || message == null) return result;
        result.kind = peer.kind; result.selfUserId = origin.userId; result.creator = peer.creator; result.soleAdmin = peer.soleAdmin;
        if (message instanceof TLRPC.TL_messageService) { result.regular = Truth.False; return result; }
        if (message.getClass() != TLRPC.TL_message.class || message.id <= 0 || message.date <= 0 || !Objects.equals(token(message.peer_id, origin.userId), peer.chat)) return new MessageFacts();
        result.regular = Truth.True; result.forwarded = truth(message.fwd_from != null || (message.flags & (1 << 2)) != 0);
        result.viaBot = truth(message.via_bot_id != 0 || message.via_business_bot_id != 0 || message.guestchat_via_from != null || message.via_bot_name != null
            || (message.flags & (1 << 11)) != 0 || (message.flags2 & ((1 << 0) | (1 << 19))) != 0);
        result.scheduled = truth(message.from_scheduled || message.schedule_repeat_period != 0 || (message.flags & (1 << 18)) != 0 || (message.flags2 & (1 << 10)) != 0);
        result.hasTtl = message.ttl_period != 0 || message.ttl != 0 || (message.flags & (1 << 25)) != 0 ? Truth.True : mediaTtl(message.media, (message.flags & (1 << 9)) != 0);
        result.out = truth(message.out); result.sender = token(message.from_id, origin.userId); result.sentAsOther = Truth.False;
        if (peer.kind == Kind.BasicGroup || peer.kind == Kind.Supergroup) {
            if ((message.flags & (1 << 8)) == 0) result.sender = null;
            result.sentAsOther = result.sender != null ? truth(!result.sender.equals(Peer.self())) : Truth.Unknown;
        }
        else if (peer.kind == Kind.Broadcast && message.from_id != null) result.sentAsOther = result.sender != null ? truth(!result.sender.equals(Peer.self()) && !result.sender.equals(peer.chat)) : Truth.Unknown;
        return result;
    }

    private static Truth mediaTtl(TLRPC.MessageMedia media, boolean present) {
        if (media == null) return present ? Truth.Unknown : Truth.False;
        if (media.getClass() == TLRPC.TL_messageMediaEmpty.class) return Truth.False;
        if (media.getClass() == TLRPC.TL_messageMediaDocument.class || media.getClass() == TLRPC.TL_messageMediaPhoto.class)
            return truth(media.ttl_seconds != 0 || (media.flags & (1 << 2)) != 0);
        return Truth.Unknown;
    }
    private static MappedPeer unknown(PurpleSyncChatOrigin origin, Peer chat, Kind kind, Truth creator, Truth soleAdmin) {
        return new MappedPeer(origin, chat, kind, creator, soleAdmin, null, null);
    }
    private static Truth truth(boolean value) { return value ? Truth.True : Truth.False; }
    private PurpleSyncChatFacts() { }
}
