package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.SerializedData;

import java.util.Arrays;
import java.util.Collections;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatCases {
    static int checks;
    static final PurpleSyncChatOrigin ORIGIN = new PurpleSyncChatOrigin(0, 0, 11, "synthetic-login");
    static void check(boolean value, String label) { ++checks; if (!value) throw new AssertionError(label); }
    static TLRPC.Peer peer(Peer p) {
        TLRPC.Peer result;
        if (p.kind == PeerKind.Self || p.kind == PeerKind.User) { result = new TLRPC.TL_peerUser(); result.user_id = p.kind == PeerKind.Self ? 11 : p.id; }
        else if (p.kind == PeerKind.Chat) { result = new TLRPC.TL_peerChat(); result.chat_id = p.id; }
        else { result = new TLRPC.TL_peerChannel(); result.channel_id = p.id; }
        return result;
    }
    static TLRPC.TL_message message(Peer chat) {
        TLRPC.TL_message result = new TLRPC.TL_message(); result.id = 7; result.date = 50; result.peer_id = peer(chat); result.message = "#purplechatsync"; return result;
    }
    static PurpleSyncChatFacts.Input input(Peer chat) {
        PurpleSyncChatFacts.Input i = new PurpleSyncChatFacts.Input(ORIGIN, chat); i.canSendFiles = true; i.chosenSendAs = Peer.self();
        if (chat.kind == PeerKind.Self || chat.kind == PeerKind.User) {
            i.user = new TLRPC.TL_user(); i.user.id = chat.kind == PeerKind.Self ? 11 : chat.id; i.user.self = chat.kind == PeerKind.Self;
            i.userFull = new TLRPC.TL_userFull(); i.userFull.id = i.user.id;
        } else {
            i.group = chat.kind == PeerKind.Chat ? new TLRPC.TL_chat() : new TLRPC.TL_channel(); i.group.id = chat.id; i.group.creator = true;
            if (chat.kind == PeerKind.Chat) {
                i.groupFull = new TLRPC.TL_chatFull(); i.groupFull.id = chat.id;
                TLRPC.TL_chatParticipants participants = new TLRPC.TL_chatParticipants(); participants.chat_id = chat.id;
                TLRPC.TL_chatParticipantCreator self = new TLRPC.TL_chatParticipantCreator(); self.user_id = 11; participants.participants.add(self); i.groupFull.participants = participants;
            } else { i.group.broadcast = true; i.groupFull = new TLRPC.TL_channelFull(); i.groupFull.id = chat.id; i.groupFull.flags = 3; i.groupFull.admins_count = 1; i.groupFull.participants_count = 1; }
        }
        return i;
    }
    static Ownership own(PurpleSyncChatFacts.Input i, TLRPC.Message m) { return ownership(PurpleSyncChatFacts.message(ORIGIN, PurpleSyncChatFacts.peer(ORIGIN, i), m), Options.restricted()); }
    static Eligibility eligible() { ChatFacts f = new ChatFacts(); f.kind = Kind.Self; f.canSendFiles = true; return eligibility(f, Options.restricted()); }
    static Mark mark(Peer peer, int id, long date, boolean fresh, RecordPresence records) { return new Mark(new Cache(peer,id,date), MarkStatus.Supported, eligible(), records, true, fresh); }
    static boolean tokenEquals(TLRPC.Peer nativePeer, Peer typed) { return typed.equals(PurpleSyncChatFacts.token(nativePeer,11)); }
    public static void main(String[] args) throws Exception {
        PurpleCore.ensureLoaded();
        for (String name : new String[]{"PurpleSyncChatCore", "PurpleSyncChatOrigin", "PurpleSyncChatFacts"})
            check(new java.io.File(Class.forName("org.telegram.messenger.purple."+name).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new java.io.File(args[0]).getCanonicalFile()),"production overlay origin "+name);
        check(new java.io.File(TLRPC.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new java.io.File(args[1]).getCanonicalFile()),"genuine TLRPC origin");
        Peer self = Peer.self(), user = Peer.of(PeerKind.User,22), group = Peer.of(PeerKind.Chat,33), channel = Peer.of(PeerKind.Channel,44);
        for (String bad : new String[]{"", "0", "user:0", "user:-1", "chat:01", "channel:9223372036854775808", "secret:1", "self:1"}) check(Peer.parse(bad,11)==null,"invalid token "+bad);
        check(Peer.parse("user:11",11).equals(self),"canonical self"); check(Peer.parse("channel:44",11).equals(channel),"typed channel");
        PurpleSyncChatFacts.Input i = input(self); TLRPC.TL_message m = message(self);
        check(own(i,m)==Ownership.Own,"self out false missing sender");
        check(PurpleSyncChatFacts.peer(ORIGIN,i).eligibility().allowed(),"current full self eligibility");
        i.userFull=null; check(!PurpleSyncChatFacts.peer(ORIGIN,i).eligibility().known,"self missing full info"); i=input(self);
        for (String flag : new String[]{"out", "from_scheduled"}) { m=message(self); TLRPC.Message.class.getField(flag).setBoolean(m,true); check(own(i,m)==(flag.equals("out")?Ownership.Own:Ownership.Other),"self "+flag); }
        for (String field : new String[]{"via_bot_id", "via_business_bot_id"}) { m=message(self); TLRPC.Message.class.getField(field).setLong(m,1); check(own(i,m)==Ownership.Other,field); }
        m=message(self);m.fwd_from=new TLRPC.TL_messageFwdHeader();check(own(i,m)==Ownership.Other,"forward");
        m=message(self);m.guestchat_via_from=peer(user);check(own(i,m)==Ownership.Other,"guest bot");
        m=message(self);m.schedule_repeat_period=1;check(own(i,m)==Ownership.Other,"repeated schedule");
        m=message(self);m.ttl_period=1;check(own(i,m)==Ownership.Other,"ttl");
        m=message(self);m.media=new TLRPC.TL_messageMediaPhoto();m.media.ttl_seconds=1;check(own(i,m)==Ownership.Other,"media ttl");
        m=message(self);m.media=new TLRPC.TL_messageMediaGeo();check(own(i,m)==Ownership.Unknown,"unrecognized media");
        m=message(self);m.flags|=1<<9;check(own(i,m)==Ownership.Unknown,"missing flagged media");
        m=message(self);m.peer_id=peer(user);check(own(i,m)==Ownership.Unknown,"wrong dialog");
        m=message(self);m.id=-1;check(own(i,m)==Ownership.Unknown,"local message");
        check(own(i,new TLRPC.TL_messageService())==Ownership.Other,"service");check(own(i,new TLRPC.Message())==Ownership.Unknown,"unknown constructor");
        for (Peer chat : new Peer[]{user,group,channel}) {
            i=input(chat);m=message(chat);m.out=true;
            if(chat.equals(group)){m.from_id=peer(self);m.flags|=1<<8;}
            check(own(i,m)==Ownership.Own,"own route "+chat.token());
            m.out=false;check(own(i,m)==Ownership.Other,"incoming "+chat.token());
        }
        i=input(user);m=message(user);m.out=true;m.from_id=peer(user);check(own(i,m)==Ownership.Own,"private normalized fallback");
        i=input(group);m=message(group);m.out=true;check(own(i,m)==Ownership.Unknown,"missing group sender");m.from_id=peer(group);m.flags|=1<<8;check(own(i,m)==Ownership.Other,"explicit group sender");m.from_id=peer(channel);check(own(i,m)==Ownership.Other,"send as channel");
        i=input(channel);m=message(channel);m.out=true;m.from_id=peer(channel);check(own(i,m)==Ownership.Own,"broadcast own-channel sender");
        i.groupFull.admins_count=2;check(own(i,m)==Ownership.Other,"broadcast other admin");i.groupFull.admins_count=1;i.groupFull.flags=0;check(own(i,m)==Ownership.Unknown,"broadcast missing admin flag");
        i=input(channel);i.group.creator=false;check(own(i,m)==Ownership.Other,"broadcast noncreator");
        i=input(group);i.groupFull.participants=new TLRPC.TL_chatParticipantsForbidden();PurpleSyncChatFacts.MappedPeer unknownRoster=PurpleSyncChatFacts.peer(ORIGIN,i);check(unknownRoster.eligibility().readers.populationUnknown,"forbidden roster unknown");check(unknownRoster.memberIds==null,"no fabricated member list");
        i=input(group);i.groupFull.participants.participants.add(i.groupFull.participants.participants.get(0));check(!PurpleSyncChatFacts.peer(ORIGIN,i).eligibility().known,"duplicate roster");
        i=input(channel);TLRPC.TL_username secondary=new TLRPC.TL_username();secondary.active=true;secondary.username="synthetic";i.group.usernames.add(secondary);check(PurpleSyncChatFacts.peer(ORIGIN,i).eligibility().refusal==Refusal.Public,"secondary public username");
        i=input(channel);i.groupFull.default_send_as=peer(user);check(PurpleSyncChatFacts.peer(ORIGIN,i).eligibility().refusal==Refusal.SendAsOther,"raw default send-as");
        i=input(channel);i.groupFull.id=999;check(!PurpleSyncChatFacts.peer(ORIGIN,i).eligibility().known,"mismatched full info");
        i=input(self);check(PurpleSyncChatFacts.peer(new PurpleSyncChatOrigin(0,0,11,"replacement"),i).kind==Kind.Unknown,"origin login fence");
        for(boolean supergroup:new boolean[]{false,true}){
            Peer target=supergroup?channel:group;PurpleSyncChatFacts.Input raw=input(target);
            if(supergroup){raw.group.broadcast=false;raw.group.megagroup=true;}
            for(Peer sender:new Peer[]{null,user,self}){
                TLRPC.TL_message encoded=message(target);encoded.out=true;encoded.from_id=sender!=null?peer(sender):null;encoded.flags=sender!=null?1<<8:0;
                SerializedData bytes=new SerializedData();encoded.serializeToStream(bytes);SerializedData decodedBytes=new SerializedData(bytes.toByteArray());TLRPC.Message decoded=TLRPC.Message.TLdeserialize(decodedBytes,decodedBytes.readInt32(true),true);
                check(((decoded.flags&(1<<8))!=0)==(sender!=null),"genuine sender-presence flag "+supergroup);
                check(own(raw,decoded)==(sender==null?Ownership.Unknown:sender.equals(self)?Ownership.Own:Ownership.Other),"group/supergroup sender provenance "+supergroup);
            }
        }
        String[] flags={"publicUsername","protectedContent","forum","linkedDiscussion","autoDelete","paidMessages","anonymousAdmin","migrated","slowMode"};
        Refusal[] refusals={Refusal.Public,Refusal.Protected,Refusal.TopicRouting,Refusal.Discussion,Refusal.AutoDelete,Refusal.Paid,Refusal.SendAsOther,Refusal.Migrated,Refusal.SlowMode};
        for(int n=0;n<flags.length;n++){ChatFacts f=new ChatFacts();f.kind=Kind.Supergroup;f.canSendFiles=true;ChatFacts.class.getField(flags[n]).setBoolean(f,true);check(eligibility(f,Options.restricted()).refusal==refusals[n],"eligibility "+flags[n]);}
        for(Kind kind:Kind.values()){ChatFacts f=new ChatFacts();f.kind=kind;f.canSendFiles=true;Eligibility e=eligibility(f,Options.restricted());check(e.known,"typed kind "+kind);}
        for(String text:new String[]{"#purplechatsync", "Header\n#PURPLECHATSYNC v01\n", "#purplechatsync v1"})check(parseMark(text,false,Ownership.Own).status==MarkStatus.Supported,"exact mark");
        for(String text:new String[]{"#purplechatsyncer", "#purplechatsync v0", "#purplechatsync v1 at self", "#purplechatsync v1\ntrailer"})check(parseMark(text,false,Ownership.Own).status==MarkStatus.NotMark,"tag distractor");
        check(parseMark("#purplechatsync v2",false,Ownership.Own).status==MarkStatus.NewerVersion,"newer version");check(parseMark("#purplechatsync",true,Ownership.Own).status==MarkStatus.NotMark,"caption cannot mark");
        for(Ownership ownership:Ownership.values())check(parseMark("#purplechatsync",false,ownership).status==(ownership==Ownership.Own?MarkStatus.Supported:MarkStatus.NotMark),"ownership parser");
        ResolveInput in=new ResolveInput(11);check(resolve(in).action==Action.Unset,"unset");in.settingsHint=SettingsHint.Found;check(resolve(in).action==Action.Use,"hint implicit self");
        in=new ResolveInput(11);in.cache=new Cache(self,7,50);in.cachedEligibility=eligible();check(resolve(in).postingHeld,"unknown direct read holds");check(resolve(in).readChat.equals(self),"unknown retains read cache");
        in.cacheRead=DirectRead.Valid;check(!resolve(in).postingHeld,"valid cache postable");
        for(int n=0;n<4;n++){in.cacheRead=DirectRead.MarkGone;in.searchRetriesCompleted=n;Resolution result=resolve(in);check(result.action==(n<3?Action.Searching:Action.Lost),"search retry "+n);check(result.retryAfterSeconds==(n==0?30:n==1?120:n==2?600:0),"retry seconds");}
        in=new ResolveInput(11);in.marks=Collections.singletonList(mark(channel,8,60,false,RecordPresence.Present));check(resolve(in).action==Action.VerifyMark,"fresh reread mandatory");
        in.marks=Collections.singletonList(mark(channel,8,60,true,RecordPresence.Present));check(resolve(in).action==Action.Use,"fresh winner");
        in.cache=new Cache(self,7,50);check(resolve(in).action==Action.Follow,"follow held");check(resolve(in).cache.equals(in.cache)&&resolve(in).postingHeld,"no moved cache commit");
        in.cache=null;in.marks=Arrays.asList(mark(self,7,50,true,RecordPresence.Present),mark(channel,8,60,true,RecordPresence.Unknown));check(resolve(in).action==Action.ReadWinnerInventory,"inventory gate");
        in.marks=Arrays.asList(mark(channel,8,60,true,RecordPresence.Empty),mark(self,7,50,true,RecordPresence.Present));check(resolve(in).action==Action.Fill,"fill gate");check(resolve(in).cache==null&&resolve(in).postingHeld,"fill no cache commit");
        in.marks=Arrays.asList(mark(channel,8,60,true,RecordPresence.Present),mark(channel,8,60,true,RecordPresence.Present));check(resolve(in).action==Action.Use,"dedup identical");
        in.marks=Arrays.asList(mark(channel,8,60,true,RecordPresence.Present),mark(channel,8,61,true,RecordPresence.Present));check(resolve(in).action==Action.Invalid,"contradictory duplicate");
        Cache a=new Cache(group,7,60),b=new Cache(channel,7,60);check(outranks(a,b)&&!outranks(b,a),"canonical lexical tie ordering");
        for(String invalid:new String[]{"{}","{\"kind\":\"Self\",\"policy\":\"OutgoingVerified\",\"selfUserId\":\"11\"}","{\"kind\":123}"})check(!new JSONObject(requestNative("ownership",invalid)).optBoolean("ok"),"invalid JNI input");
        for (Peer destination : new Peer[]{self,user,group,channel}) {
            m=message(destination);m.out=!destination.equals(self);SerializedData output=new SerializedData();m.serializeToStream(output);SerializedData wire=new SerializedData(output.toByteArray());TLRPC.Message decoded=TLRPC.Message.TLdeserialize(wire,wire.readInt32(true),true);
            check(decoded.getClass()==TLRPC.TL_message.class,"actual TL roundtrip");
            check(decoded.from_id!=null&&tokenEquals(decoded.from_id,destination),"native sender fallback");
            check(own(input(destination),decoded)==(destination.equals(group)?Ownership.Unknown:Ownership.Own),"deserialized native ownership "+destination.token());
        }
        System.out.println("SYNC_CHAT_CHECKS="+checks);
    }
}
