package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatTelegram implements PurpleSyncChatClient {
    interface Reply { void accept(TLObject object, TLRPC.TL_error error); }
    interface Platform {
        boolean current();
        void dispatch(Runnable task);
        int send(TLObject request, Reply done);
        void cancel(int token);
        void delay(Runnable task, long millis);
        void cancelDelay(Runnable task);
        default long now(){return System.currentTimeMillis();}
        default void waiting(Object owner,PurpleRpcRetry.Transient value){}
        TLRPC.InputPeer input(Peer peer);
        void peers(List<TLRPC.User> users, List<TLRPC.Chat> chats);
        List<Peer> fallback();
    }
    private final PurpleSyncChatOrigin origin;
    private final Platform platform;
    private final Set<PurpleRpcRequest> requests = new HashSet<>();
    private final PurpleRpcRequest.Stream stream=new PurpleRpcRequest.Stream();
    private Consumer<PurpleRpcRetry.Transient> waiting=ignored->{};
    private boolean closed;
    PurpleSyncChatTelegram(PurpleSyncChatOrigin origin, Platform platform) { this.origin = origin; this.platform = platform; }
    @Override public PurpleSyncChatOrigin origin() { return origin; }
    @Override public boolean current() { return !closed && platform.current(); }
    @Override public void dispatch(Runnable task) { platform.dispatch(task); }
    @Override public List<Peer> fallback() { return current() ? platform.fallback() : Collections.emptyList(); }

    @Override public void transientWait(Consumer<PurpleRpcRetry.Transient> observer){waiting=observer!=null?observer:ignored->{};}
    private void request(TLObject request,Reply done){
        if(!current()||request==null){if(current())done.accept(null,null);return;}
        PurpleRpcRequest[] call=new PurpleRpcRequest[1];
        PurpleRpcRequest.Port port=new PurpleRpcRequest.Port(){
            public boolean current(){return PurpleSyncChatTelegram.this.current();}
            public long now(){return platform.now();}
            public void dispatch(Runnable task){platform.dispatch(task);}
            public void delay(Runnable task,long millis){platform.delay(task,millis);}
            public void cancelDelay(Runnable task){platform.cancelDelay(task);}
            public int send(TLObject value,PurpleRpcRequest.Reply reply){return platform.send(value,reply::accept);}
            public void cancel(int token){platform.cancel(token);}
            public void waiting(PurpleRpcRetry.Transient value){platform.waiting(call[0],value);if(current())waiting.accept(value);}
        };
        call[0]=new PurpleRpcRequest(stream,port,request,30000,(object,error)->{if(current())done.accept(object,error);},ended->{requests.remove(ended);platform.waiting(ended,null);});
        requests.add(call[0]);call[0].start();
    }

    private void acceptPeers(TLObject response) {
        if (!current()) return;
        if (response instanceof TLRPC.messages_Messages) { TLRPC.messages_Messages data = (TLRPC.messages_Messages) response; platform.peers(data.users, data.chats); }
        else if (response instanceof TLRPC.TL_users_userFull) { TLRPC.TL_users_userFull data = (TLRPC.TL_users_userFull) response; platform.peers(data.users, data.chats); }
        else if (response instanceof TLRPC.TL_messages_chatFull) { TLRPC.TL_messages_chatFull data = (TLRPC.TL_messages_chatFull) response; platform.peers(data.users, data.chats); }
        else if (response instanceof TLRPC.TL_channels_channelParticipants) { TLRPC.TL_channels_channelParticipants data = (TLRPC.TL_channels_channelParticipants) response; platform.peers(data.users, data.chats); }
    }

    @Override public void search(PurpleSyncChatRequests.Search kind, PurpleSyncChatRequests.Cursor cursor, Peer target, Consumer<PurpleSyncChatRequests.Page> done) {
        TLObject request = kind == PurpleSyncChatRequests.Search.Global || kind == PurpleSyncChatRequests.Search.Broadcasts
            ? PurpleSyncChatRequests.global(kind, cursor, cursor.peer != null ? platform.input(cursor.peer) : null)
            : PurpleSyncChatRequests.search(kind, target, platform.input(target), cursor.id);
        request(request, (response, error) -> {
            if (error == null) acceptPeers(response);
            if (!current()) return;
            done.accept(PurpleSyncChatRequests.page(origin, error == null ? response : null, kind, target));
        });
    }

    @Override public void info(Peer target, Consumer<Info> done) {
        request(PurpleSyncChatRequests.full(target, platform.input(target)), (response, error) -> {
            if (error != null) { done.accept(unavailable(target, error)); return; }
            acceptPeers(response);
            if (!current()) return;
            PurpleSyncChatFacts.Input input = new PurpleSyncChatFacts.Input(origin, target);
            String title = "";
            if (response != null && response.getClass() == TLRPC.TL_users_userFull.class && (target.kind == PeerKind.Self || target.kind == PeerKind.User)) {
                TLRPC.TL_users_userFull data = (TLRPC.TL_users_userFull) response; input.userFull = data.full_user;
                long expected = target.kind == PeerKind.Self ? origin.userId : target.id;
                for (TLRPC.User user : data.users) if (user.id == expected) { if (input.user != null) { done.accept(unavailable(target,null)); return; } input.user = user; }
                title = input.user != null ? ((input.user.first_name!=null?input.user.first_name:"")+" "+(input.user.last_name!=null?input.user.last_name:"")).trim() : "";
                input.canSendFiles = input.userFull != null && !input.userFull.blocked;
                input.chosenSendAs = Peer.self();
            } else if (response != null && response.getClass() == TLRPC.TL_messages_chatFull.class && (target.kind == PeerKind.Chat || target.kind == PeerKind.Channel)) {
                TLRPC.TL_messages_chatFull data = (TLRPC.TL_messages_chatFull) response; input.groupFull = data.full_chat;
                for (TLRPC.Chat chat : data.chats) if (chat.id == target.id) { if (input.group != null) { done.accept(unavailable(target,null)); return; } input.group = chat; }
                if (input.group != null) {
                    title = input.group.title != null ? input.group.title : "";
                    input.canSendFiles = (ChatObject.isIgnoredChatRestrictionsForBoosters(input.groupFull)
                        || ChatObject.canUserDoAction(input.group,ChatObject.ACTION_SEND_DOCUMENTS)) && ChatObject.canWriteToChat(input.group);
                    input.chosenSendAs = input.groupFull != null && input.groupFull.default_send_as != null
                        ? PurpleSyncChatFacts.token(input.groupFull.default_send_as, origin.userId) : Peer.self();
                }
            }
            PurpleSyncChatFacts.MappedPeer mapped = PurpleSyncChatFacts.peer(origin,input);
            if (mapped.facts == null) { done.accept(new Info(origin,target,mapped,Error.Unknown,title,null,false)); return; }
            Readers readers = mapped.eligibility().readers;
            boolean countsKnown = mapped.kind == Kind.Self || mapped.kind == Kind.Private || mapped.kind == Kind.Bot
                || !readers.populationUnknown && readers.otherMembers != null && readers.otherAdmins != null && readers.otherAdmins <= readers.otherMembers;
            Info result = new Info(origin,target,mapped,Error.None,title,mapped.memberIds,countsKnown,input.groupFull!=null&&input.groupFull.hidden_prehistory);
            if (target.kind == PeerKind.Channel && mapped.eligibility().allowed() && countsKnown && readers.otherMembers != null && readers.otherMembers < 200
                && input.groupFull != null && !input.groupFull.participants_hidden && input.groupFull.can_view_participants) members(result,done);
            else done.accept(result);
        });
    }

    private void members(Info info, Consumer<Info> done) {
        request(PurpleSyncChatRequests.members(info.chat,platform.input(info.chat)), (response,error) -> {
            Set<Long> ids = null;
            if (error == null && response != null && response.getClass() == TLRPC.TL_channels_channelParticipants.class) {
                acceptPeers(response); if (!current()) return; TLRPC.TL_channels_channelParticipants data = (TLRPC.TL_channels_channelParticipants) response;
                ids = memberIds(data, info.peer.facts.otherMembers, origin.userId);
            }
            done.accept(new Info(origin,info.chat,info.peer,info.error,info.title,ids,ids != null,info.hiddenPrehistory));
        });
    }

    static Set<Long> memberIds(TLRPC.TL_channels_channelParticipants data, Long otherMembers, long self) {
        if (otherMembers == null || otherMembers >= 200 || data.count != otherMembers + 1 || data.participants.size() != data.count) return null;
        Set<Long> ids = new HashSet<>();
        for (TLRPC.ChannelParticipant participant : data.participants) {
            if (participant == null || participant.left || memberUserId(participant) <= 0 || !ids.add(memberUserId(participant))
                || participant.getClass() != TLRPC.TL_channelParticipant.class && participant.getClass() != TLRPC.TL_channelParticipantSelf.class
                && participant.getClass() != TLRPC.TL_channelParticipantAdmin.class && participant.getClass() != TLRPC.TL_channelParticipantCreator.class) return null;
        }
        return ids.contains(self) ? ids : null;
    }

    private static long memberUserId(TLRPC.ChannelParticipant value) {
        if (value.getClass() == TLRPC.TL_channelParticipantSelf.class) return value.peer == null ? value.user_id : 0;
        TLRPC.Peer peer = value.peer;
        return peer != null && peer.getClass() == TLRPC.TL_peerUser.class && peer.user_id > 0 && peer.channel_id == 0 && peer.chat_id == 0
            && (value.user_id == 0 || value.user_id == peer.user_id) ? peer.user_id : 0;
    }

    @Override public void exact(Cache location, Consumer<Exact> done) {
        request(PurpleSyncChatRequests.exact(location.chat,location.markId,platform.input(location.chat)), (response,error) -> {
            if (error == null) acceptPeers(response);
            if (!current()) return;
            info(location.chat, readable -> done.accept(classify(origin,location,response,error,readable)));
        });
    }

    void readBack(Cache mark,Consumer<PurpleSyncChatSetup.ReadBack> done) {
        request(PurpleSyncChatRequests.exact(mark.chat,mark.markId,platform.input(mark.chat)),(response,error)->{
            if(error==null)acceptPeers(response);
            if(current())info(mark.chat,info->done.accept(new PurpleSyncChatSetup.ReadBack(response,error,info)));
        });
    }

    static Exact classify(PurpleSyncChatOrigin origin, Cache location, TLObject response, TLRPC.TL_error error, Info info) {
        if (origin == null || location == null || location.markId <= 0 || location.markDate <= 0 || info == null || !origin.equals(info.origin) || !location.chat.equals(info.chat)) return new Exact(DirectRead.Unknown,null,info);
        if (error != null) return new Exact(location.chat.kind == PeerKind.Channel && "CHANNEL_PRIVATE".equals(error.text)
            && info.error == Error.ChannelPrivate ? DirectRead.ChatGone : DirectRead.Unknown,null,info);
        if (!info.readable() || !(response instanceof TLRPC.messages_Messages)) return new Exact(DirectRead.Unknown,null,info);
        if (info.peer.facts.migrated) return new Exact(DirectRead.Migrated,null,info);
        if (response.getClass() != TLRPC.TL_messages_messages.class && !(response.getClass() == TLRPC.TL_messages_channelMessages.class && location.chat.kind == PeerKind.Channel))
            return new Exact(DirectRead.Unknown,null,info);
        TLRPC.messages_Messages data = (TLRPC.messages_Messages) response;
        if (data.inexact || (data.flags & (1 << 1)) != 0 || data.search_flood != null || (data.flags & (1 << 3)) != 0 || data.messages.size() > 1) return new Exact(DirectRead.Unknown,null,info);
        if (data.messages.isEmpty()) return new Exact(DirectRead.Unknown,null,info);
        TLRPC.Message message = data.messages.get(0);
        if (message == null || message.id != location.markId) return new Exact(DirectRead.Unknown,null,info);
        if (message.getClass() == TLRPC.TL_messageEmpty.class) {
            boolean peerPresent=(message.flags&1)!=0;
            return new Exact(peerPresent&&!location.chat.equals(PurpleSyncChatFacts.token(message.peer_id,origin.userId))?DirectRead.Unknown:DirectRead.MarkGone,null,info);
        }
        if (message.getClass() != TLRPC.TL_message.class || !location.chat.equals(PurpleSyncChatFacts.token(message.peer_id,origin.userId))) return new Exact(DirectRead.Unknown,null,info);
        Ownership own = ownership(PurpleSyncChatFacts.message(origin,info.peer,message),Options.restricted());
        if (own == Ownership.Unknown) return new Exact(DirectRead.Unknown,null,info);
        ParsedMark mark = parseMark(message.message,message.media != null && message.media.getClass() != TLRPC.TL_messageMediaEmpty.class,own);
        if(mark.status!=MarkStatus.NotMark&&message.date!=location.markDate)return new Exact(DirectRead.Unknown,null,info);
        return new Exact(mark.status == MarkStatus.NotMark ? DirectRead.MarkGone : DirectRead.Valid,message,info);
    }

    @Override public void hint(Consumer<SettingsHint> done) {
        search(PurpleSyncChatRequests.Search.SettingsHint,PurpleSyncChatRequests.Cursor.first(),Peer.self(), page -> {
            if (!page.recognized || page.positions.size() > 1) { done.accept(SettingsHint.Unknown); return; }
            if (page.messages.isEmpty()) { done.accept(page.total == 0 ? SettingsHint.Absent : SettingsHint.Unknown); return; }
            done.accept(SettingsHint.Found);
        });
    }

    private Info unavailable(Peer target, TLRPC.TL_error error) {
        return new Info(origin,target,null,error != null && target.kind == PeerKind.Channel && "CHANNEL_PRIVATE".equals(error.text) ? Error.ChannelPrivate : Error.Unknown,"",null,false);
    }
    @Override public void cancel() {
        closed = true;
        for(PurpleRpcRequest request:new ArrayList<>(requests))request.cancel();
        requests.clear();
    }

    static Platform platform(PurpleSyncChatOrigin origin, BooleanSupplier current) {
        return new Platform() {
            public boolean current() { return current.getAsBoolean(); }
            public void dispatch(Runnable task) { AndroidUtilities.runOnUIThread(task); }
            public int send(TLObject request, Reply done) { return ConnectionsManager.getInstance(origin.slot).sendRequest(request,done::accept,ConnectionsManager.RequestFlagFailOnServerErrors); }
            public void cancel(int token) { ConnectionsManager.getInstance(origin.slot).cancelRequest(token,true); }
            public void delay(Runnable task, long millis) { AndroidUtilities.runOnUIThread(task,millis); }
            public void cancelDelay(Runnable task) { AndroidUtilities.cancelRunOnUIThread(task); }
            public void waiting(Object owner,PurpleRpcRetry.Transient value){PurpleRpcRequests.waiting(origin.slot,owner,value);}
            public TLRPC.InputPeer input(Peer peer) {
                if (!current() || peer == null) return null;
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
            public void peers(List<TLRPC.User> users, List<TLRPC.Chat> chats) {
                if (!current()) return;
                ArrayList<TLRPC.User> u = new ArrayList<>(users); ArrayList<TLRPC.Chat> c = new ArrayList<>(chats);
                MessagesController mc = MessagesController.getInstance(origin.slot); mc.putUsers(u,false);
                if (!current()) return; mc.putChats(c,false); if (!current()) return;
                MessagesStorage storage = MessagesStorage.getInstance(origin.slot);
                storage.getStorageQueue().postRunnable(() -> { if (current()) storage.putUsersAndChats(u,c,true,false); });
            }
            public List<Peer> fallback() {
                if (!current()) return Collections.emptyList();
                MessagesController mc = MessagesController.getInstance(origin.slot); ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
                dialogs.addAll(new ArrayList<>(mc.getDialogs(0))); dialogs.addAll(new ArrayList<>(mc.getDialogs(1)));
                dialogs.sort((a,b) -> { int priority = Integer.compare(priority(mc,a),priority(mc,b)); return priority != 0 ? priority : Integer.compare(b.last_message_date,a.last_message_date); });
                ArrayList<Peer> result = new ArrayList<>(); Set<Peer> seen = new HashSet<>();
                for (TLRPC.Dialog dialog : dialogs) {
                    if (result.size() >= 51) break;
                    if (dialog.id >= 0) continue;
                    TLRPC.Chat chat = mc.getChat(-dialog.id); if (chat == null || chat.getClass() != TLRPC.TL_channel.class && chat.getClass() != TLRPC.TL_chat.class) continue;
                    Peer peer = Peer.of(chat.getClass() == TLRPC.TL_channel.class ? PeerKind.Channel : PeerKind.Chat,chat.id);
                    if (seen.add(peer)) result.add(peer);
                }
                return result;
            }
        };
    }
    private static int priority(MessagesController mc, TLRPC.Dialog dialog) {
        TLRPC.Chat chat = dialog.id < 0 ? mc.getChat(-dialog.id) : null;
        return chat != null && chat.getClass() == TLRPC.TL_channel.class && chat.broadcast && !chat.megagroup && chat.creator ? "Purple sync".equals(chat.title) ? 0 : 1 : 2;
    }
}
