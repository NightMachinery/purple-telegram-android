package org.telegram.messenger.purple;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.util.*;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;

final class PurpleSyncChatRequestCases {
    static final Peer SELF=Peer.self(), CHANNEL=Peer.of(PeerKind.Channel,44), GROUP=Peer.of(PeerKind.Chat,33), USER=Peer.of(PeerKind.User,22);
    static TLRPC.InputPeer inputPeer(Peer target) {
        if(target==null)return null;
        TLRPC.InputPeer input;
        switch(target.kind){
            case Self: return new TLRPC.TL_inputPeerSelf();
            case User: input=new TLRPC.TL_inputPeerUser();input.user_id=target.id;break;
            case Chat: input=new TLRPC.TL_inputPeerChat();input.chat_id=target.id;break;
            default:input=new TLRPC.TL_inputPeerChannel();input.channel_id=target.id;
        }
        input.access_hash=77;return input;
    }
    static SerializedData wire(TLObject value){SerializedData output=new SerializedData();value.serializeToStream(output);return new SerializedData(output.toByteArray());}
    static TLRPC.TL_message mark(Peer target,int id,int date){TLRPC.TL_message value=message(target);value.id=id;value.date=date;value.out=target.kind!=PeerKind.Self;if(target.kind==PeerKind.Chat){value.from_id=peer(SELF);value.flags|=1<<8;}return value;}
    static TLRPC.messages_Messages messages(TLRPC.Message... rows){TLRPC.TL_messages_messages value=new TLRPC.TL_messages_messages();value.messages.addAll(Arrays.asList(rows));return value;}
    static PurpleSyncChatRequests.Page page(int total,Integer nextRate,TLRPC.Message... rows){TLRPC.TL_messages_messagesSlice value=new TLRPC.TL_messages_messagesSlice();value.count=total;value.messages.addAll(Arrays.asList(rows));if(nextRate!=null){value.flags=1;value.next_rate=nextRate;}return PurpleSyncChatRequests.page(ORIGIN,value,PurpleSyncChatRequests.Search.Global,null);}
    static PurpleSyncChatClient.Info info(Peer chat){return info(chat,input(chat));}
    static PurpleSyncChatClient.Info info(Peer chat,PurpleSyncChatFacts.Input raw){PurpleSyncChatFacts.MappedPeer mapped=PurpleSyncChatFacts.peer(ORIGIN,raw);return new PurpleSyncChatClient.Info(ORIGIN,chat,mapped,PurpleSyncChatClient.Error.None,"synthetic",mapped.memberIds,true);}
    static void run(){
        for(boolean broadcast:new boolean[]{false,true}){
            TLRPC.TL_messages_searchGlobal request=PurpleSyncChatRequests.global(broadcast?PurpleSyncChatRequests.Search.Broadcasts:PurpleSyncChatRequests.Search.Global,PurpleSyncChatRequests.Cursor.first(),null);
            SerializedData bytes=wire(request);check(bytes.readInt32(true)==TLRPC.TL_messages_searchGlobal.constructor,"R1 real constructor");check(bytes.readInt32(true)==(broadcast?2:0),"R1 no folder/community/other flags");
            check(bytes.readString(true).equals("#purplechatsync"),"R1 tag");check(bytes.readInt32(true)==TLRPC.TL_inputMessagesFilterEmpty.constructor,"R1 empty filter");
            check(bytes.readInt32(true)==0&&bytes.readInt32(true)==0&&bytes.readInt32(true)==0,"R1 dates/rate zero");check(bytes.readInt32(true)==TLRPC.TL_inputPeerEmpty.constructor,"R1 offset empty");check(bytes.readInt32(true)==0&&bytes.readInt32(true)==100,"R1 offset and limit");
        }
        PurpleSyncChatRequests.Cursor cursor=new PurpleSyncChatRequests.Cursor(17,CHANNEL,19);
        TLRPC.TL_messages_searchGlobal request=PurpleSyncChatRequests.global(PurpleSyncChatRequests.Search.Global,cursor,inputPeer(CHANNEL));
        check(request.offset_rate==17&&request.offset_id==19&&request.offset_peer.channel_id==44&&request.offset_peer.access_hash==77,"R1 full cursor/hash");
        check(PurpleSyncChatRequests.global(PurpleSyncChatRequests.Search.Global,cursor,inputPeer(USER))==null,"typed cursor mismatch refuses");
        for(PurpleSyncChatRequests.Cursor bad:new PurpleSyncChatRequests.Cursor[]{new PurpleSyncChatRequests.Cursor(-1,null,0),new PurpleSyncChatRequests.Cursor(0,null,1),new PurpleSyncChatRequests.Cursor(1,null,0),new PurpleSyncChatRequests.Cursor(0,CHANNEL,0)})check(PurpleSyncChatRequests.global(PurpleSyncChatRequests.Search.Global,bad,inputPeer(CHANNEL))==null,"invalid R1 cursor");
        for(PurpleSyncChatRequests.Search kind:new PurpleSyncChatRequests.Search[]{PurpleSyncChatRequests.Search.Self,PurpleSyncChatRequests.Search.SettingsHint,PurpleSyncChatRequests.Search.Dialog}){
            Peer target=kind==PurpleSyncChatRequests.Search.Dialog?CHANNEL:SELF;
            TLRPC.TL_messages_search search=PurpleSyncChatRequests.search(kind,target,inputPeer(target),0);SerializedData bytes=wire(search);
            check(bytes.readInt32(true)==TLRPC.TL_messages_search.constructor,"R2/Q/F1 genuine constructor");check(bytes.readInt32(true)==0,"R2/Q/F1 no saved/from/topic filters");
            TLRPC.InputPeer decoded=TLRPC.InputPeer.TLdeserialize(bytes,bytes.readInt32(true),true);check(PurpleSyncChatRequests.matches(target,decoded),"R2/Q/F1 typed target");
            check(bytes.readString(true).equals(kind==PurpleSyncChatRequests.Search.SettingsHint?"#purplesync":"#purplechatsync"),"R2/Q/F1 query");
            check(bytes.readInt32(true)==(kind==PurpleSyncChatRequests.Search.SettingsHint?TLRPC.TL_inputMessagesFilterDocument.constructor:TLRPC.TL_inputMessagesFilterEmpty.constructor),"R2/Q/F1 filter");
            for(int n=0;n<4;n++)check(bytes.readInt32(true)==0,"R2/Q/F1 limits prefix");check(bytes.readInt32(true)==(kind==PurpleSyncChatRequests.Search.SettingsHint?1:100),"R2/Q/F1 limit");
        }
        check(PurpleSyncChatRequests.search(PurpleSyncChatRequests.Search.Self,CHANNEL,inputPeer(CHANNEL),0)==null,"R2 never nonself");
        for(Peer target:new Peer[]{SELF,USER,GROUP,CHANNEL}){
            TLObject exact=PurpleSyncChatRequests.exact(target,9,inputPeer(target));SerializedData bytes=wire(exact);
            check(bytes.readInt32(true)==(target.equals(CHANNEL)?TLRPC.TL_channels_getMessages.constructor:TLRPC.TL_messages_getMessages.constructor),"R3 namespace "+target.token());
            if(target.equals(CHANNEL)){TLRPC.InputChannel channel=TLRPC.InputChannel.TLdeserialize(bytes,bytes.readInt32(true),true);check(channel.channel_id==44&&channel.access_hash==77,"R3 native channel hash");}
            check(bytes.readInt32(true)==0x1cb5c415&&bytes.readInt32(true)==1&&bytes.readInt32(true)==9,"R3 exact-id vector");
            check(PurpleSyncChatRequests.full(target,inputPeer(target))!=null,"full request typed");
        }
        TLRPC.InputPeer noHash=inputPeer(CHANNEL);noHash.access_hash=0;check(PurpleSyncChatRequests.exact(CHANNEL,1,noHash)==null,"zero hash refuses");
        check(PurpleSyncChatRequests.exact(SELF,0,inputPeer(SELF))==null,"nonpositive id refuses");
        TLRPC.TL_channels_getParticipants members=PurpleSyncChatRequests.members(CHANNEL,inputPeer(CHANNEL));check(members.limit==200&&members.offset==0&&members.hash==0&&members.filter instanceof TLRPC.TL_channelParticipantsRecent,"readers exact bounded shape");
        for(TLObject bad:new TLObject[]{null,new TLRPC.TL_messages_messagesNotModified(),new TLRPC.TL_messages_channelMessages()})check(!PurpleSyncChatRequests.page(ORIGIN,bad,PurpleSyncChatRequests.Search.Global,null).recognized,"unqualified global reply refused");
        TLRPC.TL_messages_channelMessages channelPage=new TLRPC.TL_messages_channelMessages();channelPage.count=1;channelPage.messages.add(mark(CHANNEL,9,50));check(PurpleSyncChatRequests.page(ORIGIN,channelPage,PurpleSyncChatRequests.Search.Dialog,CHANNEL).recognized,"typed channel dialog page");
        channelPage.inexact=true;check(!PurpleSyncChatRequests.page(ORIGIN,channelPage,PurpleSyncChatRequests.Search.Dialog,CHANNEL).recognized,"channel inexact held");
        TLRPC.TL_messages_messagesSlice slice=new TLRPC.TL_messages_messagesSlice();slice.inexact=true;check(!PurpleSyncChatRequests.page(ORIGIN,slice,PurpleSyncChatRequests.Search.Global,null).recognized,"inexact held");slice.inexact=false;slice.flags=8;check(!PurpleSyncChatRequests.page(ORIGIN,slice,PurpleSyncChatRequests.Search.Global,null).recognized,"search flood flag held");
        check(!PurpleSyncChatRequests.page(ORIGIN,messages(mark(USER,1,50)),PurpleSyncChatRequests.Search.Self,SELF).recognized,"wrong dialog held");
        PurpleSyncChatRequests.Pages pages=new PurpleSyncChatRequests.Pages(ORIGIN,PurpleSyncChatRequests.Search.Global);
        check(pages.add(page(2,7,mark(CHANNEL,10,60)))==PurpleSyncChatRequests.PageStatus.Next,"global next page");check(pages.cursor().rate==7&&pages.cursor().peer.equals(CHANNEL)&&pages.cursor().id==10,"global rate/peer/id propagated");
        check(pages.add(page(2,null,mark(SELF,8,50)))==PurpleSyncChatRequests.PageStatus.Complete,"complete coverage");check(pages.add(page(2,null))==PurpleSyncChatRequests.PageStatus.Incomplete,"page after complete rejected");
        pages=new PurpleSyncChatRequests.Pages(ORIGIN,PurpleSyncChatRequests.Search.Global);pages.add(page(3,7,mark(CHANNEL,10,60)));check(pages.add(page(3,7,mark(CHANNEL,10,60)))==PurpleSyncChatRequests.PageStatus.Incomplete,"cursor loop held");
        pages=new PurpleSyncChatRequests.Pages(ORIGIN,PurpleSyncChatRequests.Search.Self);pages.add(page(3,null,mark(SELF,10,60)));check(pages.add(page(3,null,mark(SELF,11,61)))==PurpleSyncChatRequests.PageStatus.Incomplete,"self nondecreasing id held");
        pages=new PurpleSyncChatRequests.Pages(ORIGIN,PurpleSyncChatRequests.Search.Global);for(int n=0;n<20;n++)check(pages.add(page(21,n,mark(CHANNEL,100-n,100-n)))==(n==19?PurpleSyncChatRequests.PageStatus.Capped:PurpleSyncChatRequests.PageStatus.Next),"20-page cap "+n);
        pages=new PurpleSyncChatRequests.Pages(ORIGIN,PurpleSyncChatRequests.Search.Global);pages.add(page(3,null,mark(SELF,1,50)));check(pages.add(page(4,null,mark(SELF,2,51)))==PurpleSyncChatRequests.PageStatus.Incomplete,"changing count held");
        pages=new PurpleSyncChatRequests.Pages(ORIGIN,PurpleSyncChatRequests.Search.Global);check(pages.add(page(1,null))==PurpleSyncChatRequests.PageStatus.Incomplete,"empty nonzero count held");
        classify();messageEmptyPeerFlag();nativeClient();
    }
    static void classify(){
        for(Peer chat:new Peer[]{SELF,CHANNEL,GROUP,USER}){
            Cache at=new Cache(chat,7,50);TLRPC.TL_message mark=mark(chat,7,50);PurpleSyncChatClient.Info facts=info(chat);
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(mark),null,facts).state==DirectRead.Valid,"R3 valid "+chat.token());
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(),null,facts).state==DirectRead.Unknown,"zero-row R3 unknown");
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(mark(chat,7,999)),null,facts).state==DirectRead.Unknown,"contradictory immutable mark date held");
            TLRPC.TL_messageEmpty missing=new TLRPC.TL_messageEmpty();missing.id=7;missing.flags=1;missing.peer_id=peer(chat);
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(missing),null,facts).state==DirectRead.MarkGone,"matching empty peer control");
            missing.peer_id=peer(Peer.of(PeerKind.User,99));check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(missing),null,facts).state==DirectRead.Unknown,"contradictory empty peer held");
            missing.peer_id=null;check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(missing),null,facts).state==DirectRead.Unknown,"flagged absent empty peer held");
            check(PurpleSyncChatTelegram.classify(ORIGIN,new Cache(chat,7,0),messages(mark),null,facts).state==DirectRead.Unknown,"invalid expected mark date held");
            mark.message="edited";check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(mark),null,facts).state==DirectRead.MarkGone,"edited not mark");
            mark.message="#purplechatsync v2";check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(mark),null,facts).state==DirectRead.Valid,"newer exact remains mark");
            mark.media=new TLRPC.TL_messageMediaGeo();check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(mark),null,facts).state==DirectRead.Unknown,"fresh unknown C0");
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(),null,new PurpleSyncChatClient.Info(ORIGIN,chat,null,PurpleSyncChatClient.Error.Unknown,"",null,false)).state==DirectRead.Unknown,"absence requires independent readability");
            TLRPC.TL_error flood=new TLRPC.TL_error();flood.text="FLOOD_WAIT_1";check(PurpleSyncChatTelegram.classify(ORIGIN,at,null,flood,facts).state==DirectRead.Unknown,"network/flood Unknown");
        }
        TLRPC.TL_error denied=new TLRPC.TL_error();denied.text="CHANNEL_PRIVATE";Cache at=new Cache(CHANNEL,7,50);
        check(PurpleSyncChatTelegram.classify(ORIGIN,at,null,denied,new PurpleSyncChatClient.Info(ORIGIN,CHANNEL,null,PurpleSyncChatClient.Error.ChannelPrivate,"",null,false)).state==DirectRead.ChatGone,"double private confirms loss");
        check(PurpleSyncChatTelegram.classify(ORIGIN,at,null,denied,info(CHANNEL)).state==DirectRead.Unknown,"private contradicts readable");
        TLRPC.messages_Messages wrong=messages(mark(SELF,7,50));check(PurpleSyncChatTelegram.classify(ORIGIN,at,wrong,null,info(CHANNEL)).state==DirectRead.Unknown,"R3 wrong dialog Unknown");
        TLRPC.TL_messages_messagesNotModified unchanged=new TLRPC.TL_messages_messagesNotModified();check(PurpleSyncChatTelegram.classify(ORIGIN,at,unchanged,null,info(CHANNEL)).state==DirectRead.Unknown,"R3 NotModified Unknown");
        PurpleSyncChatFacts.Input migrated=input(GROUP);migrated.group.deactivated=true;check(PurpleSyncChatTelegram.classify(ORIGIN,new Cache(GROUP,7,50),messages(),null,info(GROUP,migrated)).state==DirectRead.Migrated,"qualified deactivation migration");
    }
    static void messageEmptyPeerFlag(){
        for(Peer chat:new Peer[]{SELF,CHANNEL}){
            Cache at=new Cache(chat,7,50);
            for(boolean flagged:new boolean[]{false,true}){
                SerializedData output=new SerializedData();
                output.writeInt32(TLRPC.TL_messageEmpty.constructor);output.writeInt32(flagged?1:0);output.writeInt32(7);
                if(flagged)peer(chat).serializeToStream(output);
                SerializedData bytes=new SerializedData(output.toByteArray());
                TLRPC.Message missing=TLRPC.Message.TLdeserialize(bytes,bytes.readInt32(true),true);
                check(missing.getClass()==TLRPC.TL_messageEmpty.class&&missing.peer_id!=null,"real TL Empty has a peer object even when absent");
                check((missing.flags&1)==(flagged?1:0),"real TL Empty preserves wire peer flag");
                check(flagged?chat.equals(PurpleSyncChatFacts.token(missing.peer_id,ORIGIN.userId)):PurpleSyncChatFacts.token(missing.peer_id,ORIGIN.userId)==null,"real TL Empty flagged peer versus synthetic default");
                check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(missing),null,info(chat)).state==DirectRead.MarkGone,"real TL Empty scoped readable MarkGone "+chat.token()+"/"+flagged);
                PurpleSyncChatClient.Info unreadable=new PurpleSyncChatClient.Info(ORIGIN,chat,null,PurpleSyncChatClient.Error.Unknown,"",null,false);
                check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(missing),null,unreadable).state==DirectRead.Unknown,"real TL Empty still needs independent readable facts");
                if(flagged){
                    missing.peer_id=peer(Peer.of(PeerKind.User,99));
                    check(PurpleSyncChatTelegram.classify(ORIGIN,at,messages(missing),null,info(chat)).state==DirectRead.Unknown,"real TL Empty contradicting flagged peer remains Unknown");
                }
            }
        }
    }
    static final class Native implements PurpleSyncChatTelegram.Platform {
        long clock=1000;public long now(){return clock;}
        boolean active=true,logoutOnPeers;int sent,accepted,cancelled;final Map<Integer,PurpleSyncChatTelegram.Reply> replies=new LinkedHashMap<>();final Map<Runnable,Long> timers=new LinkedHashMap<>();final List<TLObject> requests=new ArrayList<>();
        public boolean current(){return active;}public void dispatch(Runnable task){task.run();}public int send(TLObject request,PurpleSyncChatTelegram.Reply done){requests.add(request);replies.put(++sent,done);return sent;}
        public void cancel(int token){++cancelled;}public void delay(Runnable task,long millis){timers.put(task,millis);}public void cancelDelay(Runnable task){timers.remove(task);}public TLRPC.InputPeer input(Peer chat){return inputPeer(chat);}
        public void peers(List<TLRPC.User> users,List<TLRPC.Chat> chats){++accepted;if(logoutOnPeers)active=false;}public List<Peer> fallback(){return Collections.emptyList();}
        void reply(int token,TLObject data){replies.get(token).accept(data,null);}
    }
    static void nativeClient(){
        Native platform=new Native();PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,platform);List<PurpleSyncChatRequests.Page> pages=new ArrayList<>();
        client.search(PurpleSyncChatRequests.Search.Global,PurpleSyncChatRequests.Cursor.first(),null,pages::add);check(platform.sent==1&&platform.timers.size()==1,"read-only request starts timeout");
        platform.reply(1,messages());platform.reply(1,messages());check(pages.size()==1&&platform.timers.isEmpty(),"request once-only completion");
        client.search(PurpleSyncChatRequests.Search.Global,PurpleSyncChatRequests.Cursor.first(),null,pages::add);Runnable timeout=platform.timers.keySet().iterator().next();platform.timers.remove(timeout);platform.clock+=30000;timeout.run();platform.reply(2,messages());check(pages.size()==1&&platform.cancelled==1&&platform.timers.size()==1,"timeout keeps transient pending and rejects late reply");
        client.cancel();check(pages.size()==1&&platform.timers.isEmpty(),"cancel owns pending retry lifetime");
        platform=new Native();client=new PurpleSyncChatTelegram(ORIGIN,platform);platform.logoutOnPeers=true;client.search(PurpleSyncChatRequests.Search.Global,PurpleSyncChatRequests.Cursor.first(),null,pages::add);platform.reply(1,messages());check(platform.accepted==1&&pages.size()==1,"logout during peer observer suppresses callback");client.cancel();
        platform=new Native();client=new PurpleSyncChatTelegram(ORIGIN,platform);List<PurpleSyncChatClient.Info> infos=new ArrayList<>();client.info(SELF,infos::add);
        TLRPC.TL_users_userFull full=new TLRPC.TL_users_userFull();PurpleSyncChatFacts.Input self=input(SELF);full.full_user=self.userFull;full.users.add(self.user);platform.reply(1,full);check(infos.size()==1&&infos.get(0).eligibility().allowed()&&infos.get(0).readersKnown,"production full-info normalization self");
        client.info(SELF,infos::add);full=new TLRPC.TL_users_userFull();full.full_user=self.userFull;platform.reply(2,full);check(infos.size()==2&&!infos.get(1).readable(),"cached native user cannot replace fresh full-info metadata");client.cancel();
        platform=new Native();client=new PurpleSyncChatTelegram(ORIGIN,platform);infos.clear();client.info(CHANNEL,infos::add);
        PurpleSyncChatFacts.Input channel=input(CHANNEL);channel.groupFull.can_view_participants=true;TLRPC.TL_messages_chatFull groupFull=new TLRPC.TL_messages_chatFull();groupFull.full_chat=channel.groupFull;groupFull.chats.add(channel.group);platform.reply(1,groupFull);check(platform.requests.get(1) instanceof TLRPC.TL_channels_getParticipants,"full-info counts trigger reader roster");
        TLRPC.TL_channels_channelParticipants roster=new TLRPC.TL_channels_channelParticipants();roster.count=1;TLRPC.TL_channelParticipantCreator creator=new TLRPC.TL_channelParticipantCreator();creator.peer=peer(SELF);roster.participants.add(creator);platform.reply(2,roster);check(infos.size()==1&&infos.get(0).readersKnown&&infos.get(0).members.contains(11L),"current peer-based participant normalization");client.cancel();
        platform=new Native();client=new PurpleSyncChatTelegram(ORIGIN,platform);infos.clear();client.info(CHANNEL,infos::add);PurpleSyncChatFacts.Input contradictory=input(CHANNEL);contradictory.group.broadcast=false;contradictory.group.megagroup=true;contradictory.groupFull.admins_count=2;
        groupFull=new TLRPC.TL_messages_chatFull();groupFull.full_chat=contradictory.groupFull;groupFull.chats.add(contradictory.group);platform.reply(1,groupFull);check(infos.size()==1&&!infos.get(0).readersKnown,"contradictory full-info admin/member counts held");client.cancel();
        check(PurpleSyncChatTelegram.memberIds(roster,0L,11)!=null,"creator reader self");roster.participants.add(creator);check(PurpleSyncChatTelegram.memberIds(roster,0L,11)==null,"duplicate/inconsistent roster refuses");
        roster.participants.clear();TLRPC.TL_channelParticipantSelf participantSelf=new TLRPC.TL_channelParticipantSelf();participantSelf.user_id=11;roster.participants.add(participantSelf);check(PurpleSyncChatTelegram.memberIds(roster,0L,11)!=null,"self participant user_id normalization");
        participantSelf.peer=peer(SELF);check(PurpleSyncChatTelegram.memberIds(roster,0L,11)==null,"contradictory self participant held");
        platform=new Native();client=new PurpleSyncChatTelegram(ORIGIN,platform);List<SettingsHint> hints=new ArrayList<>();client.hint(hints::add);TLRPC.TL_message doc=mark(SELF,3,60);doc.message="#purplesync";doc.media=new TLRPC.TL_messageMediaDocument();doc.media.document=new TLRPC.TL_document();platform.reply(1,messages(doc));check(hints.size()==1&&hints.get(0)==SettingsHint.Found,"Q own self document");
        client.hint(hints::add);doc.from_scheduled=true;platform.reply(2,messages(doc));check(hints.get(1)==SettingsHint.Found,"Q excluded first hit still requires complete inventory");client.cancel();
    }
}
