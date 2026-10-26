package org.telegram.messenger.purple;

import java.util.*;
import java.util.function.Consumer;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatScreenCases {
    static PurpleSyncChatScreen.Frame frame(boolean bound,Peer chat,boolean use,boolean warning) {
        ResolveInput input=new ResolveInput(11);input.settingsBound=bound;input.settingsChat=chat;
        PurpleSyncChatCache.Data data=null;
        if(use){input.cache=new Cache(chat,0,0);input.cacheRead=DirectRead.Valid;input.cachedEligibility=info(chat).eligibility();
            data=new PurpleSyncChatCache.Data(input.cache,"synthetic",PurpleSyncChatCache.ReadersSnapshot.from(info(chat)),Collections.emptySet(),new org.json.JSONObject(),warning);}
        Resolution resolution=resolve(input);
        return new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(use?PurpleSyncChatDiscovery.Phase.Use:PurpleSyncChatDiscovery.Phase.Unset,resolution,use?"ReaderConsent":"",true,use,0,warning)),
                new PurpleSyncChatService.Binding(true,bound,chat),new PurpleSyncChatCache.Loaded(true,null,data),use?info(chat):null,Collections.emptyList());
    }
    static final class Port implements PurpleSyncChatScreen.Port {
        PurpleSyncChatScreen.Frame frame=frame(false,SELF,false,false);
        PurpleSyncChatClient.Info info=info(USER);
        boolean current=true,held,defer;int remembered,begins,ends,inspections;
        ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public PurpleSyncChatOrigin origin(){return ORIGIN;}public boolean current(){return current;}
        public boolean begin(Object owner){if(held)return false;held=true;++begins;return true;}
        public void end(Object owner){if(held){held=false;++ends;}}
        void deliver(Runnable task){if(defer)tasks.add(task);else task.run();}
        public void resolve(Object owner,Consumer<PurpleSyncChatScreen.Frame> done){deliver(()->done.accept(frame));}
        public void inspect(Peer chat,Consumer<PurpleSyncChatClient.Info> done){++inspections;deliver(()->done.accept(info));}
        public void remember(PurpleSyncChatScreen.Frame opening,PurpleSyncChatScreen.Candidate c,Consumer<Boolean> done){++remembered;deliver(()->done.accept(current));}
        public void cancel(Object owner){}
        void drain(){while(!tasks.isEmpty())tasks.removeFirst().run();}
    }
    static void run() {
        int before=checks;
        check(frame(false,SELF,false,false).canSetup,"fresh unbound Unset offers setup");
        check(!frame(true,SELF,false,false).canSetup,"bound Unset cannot change chat");
        check(!frame(false,SELF,true,false).canSetup,"existing records/cache cannot offer fresh setup");
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}) {
            PurpleSyncChatFacts.Input raw=input(chat);PurpleSyncChatScreen.Candidate c=new PurpleSyncChatScreen.Candidate(ORIGIN,chat,info(chat,raw));
            check(c.allowed==info(chat,raw).eligibility().allowed(),"display uses core eligibility "+chat.token());
            raw.canSendFiles=false;
            if(chat.kind==PeerKind.Self)raw.userFull=null;
            c=new PurpleSyncChatScreen.Candidate(ORIGIN,chat,info(chat,raw));
            check(!c.allowed,"unknown/denied candidate refused "+chat.token());
        }
        for(String kind:new String[]{"public","protected","ttl","forum","admins","rights","sendas","discussion"}) {
            PurpleSyncChatFacts.Input raw=input(CHANNEL);
            if(kind.equals("public"))raw.group.username="synthetic";
            if(kind.equals("protected"))raw.group.noforwards=true;
            if(kind.equals("ttl"))raw.groupFull.ttl_period=5;
            if(kind.equals("forum"))raw.group.forum=true;
            if(kind.equals("admins"))raw.groupFull.flags&=~2;
            if(kind.equals("rights"))raw.canSendFiles=null;
            if(kind.equals("sendas"))raw.groupFull.default_send_as=peer(USER);
            if(kind.equals("discussion"))raw.groupFull.linked_chat_id=55;
            Port p=new Port();p.info=info(CHANNEL,raw);PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(p);
            PurpleSyncChatScreen.Candidate[] shown={null};control.inspect(CHANNEL,c->shown[0]=c);
            check(shown[0]!=null&&!shown[0].allowed,"ineligible candidate display "+kind);
            int[] selected={0};control.accept(shown[0],true,true,c->++selected[0]);
            check(selected[0]==0&&p.remembered==0&&!p.held,"same predicate acceptance refuses "+kind);control.close();
        }
        for(String change:new String[]{"none","readers","rights","account","bound","cloud","consent"}) {
            Port p=new Port();PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(p);
            PurpleSyncChatScreen.Candidate[] c={null};control.inspect(USER,v->c[0]=v);
            check(c[0]!=null&&c[0].allowed&&c[0].eligibility.readers.warningRequired,"private reader disclosure");
            if(change.equals("readers"))p.info=new PurpleSyncChatClient.Info(ORIGIN,USER,p.info.peer,p.info.error,p.info.title,Collections.singleton(99L),true);
            if(change.equals("rights")){PurpleSyncChatFacts.Input raw=input(USER);raw.userFull.blocked=true;p.info=info(USER,raw);}
            if(change.equals("account"))p.current=false;
            if(change.equals("bound"))p.frame=frame(true,SELF,false,false);
            int[] accepted={0};control.accept(c[0],!change.equals("cloud"),!change.equals("consent"),v->{if(v!=null)++accepted[0];});
            check(accepted[0]==(change.equals("none")?1:0),"fresh acceptance "+change);
            check(p.remembered==0&&!p.held,"fresh choice does not post/bind/write "+change);control.close();
        }
        Port p=new Port();p.frame=frame(true,USER,true,true);PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(p);
        PurpleSyncChatScreen.Candidate c=new PurpleSyncChatScreen.Candidate(ORIGIN,USER,p.info);
        Port acceptedPort=p;int[] called={0};control.accept(c,true,true,v->{check(!acceptedPort.held,"lock released before completion");if(v!=null)++called[0];});
        check(called[0]==1&&p.remembered==1&&p.frame.newReaders,"bound same-chat consent preserves C6 warning");control.close();
        p=new Port();p.defer=true;control=new PurpleSyncChatScreen.Control(p);control.refresh(v->{throw new AssertionError("closed UI callback");});
        check(p.held,"refresh owns shared operation");control.close();p.drain();check(!p.held&&p.ends==1,"retained callback cannot outlive screen");
        p=new Port();control=new PurpleSyncChatScreen.Control(p);PurpleSyncChatScreen.Control closing=control;
        control.refresh(v->{closing.close();++called[0];});check(!p.held&&p.ends==1,"reentrant close during delivery releases once");
        parity();disclosure();
        PurpleSyncChatModelCases.World world=new PurpleSyncChatModelCases.World();world.add(mark(SELF,7,50));
        PurpleSyncChatFacts.Input refused=input(SELF);refused.userFull.ttl_period=5;world.peers.put(SELF,info(SELF,refused));
        PurpleSyncChatDiscovery discovery=PurpleSyncChatModelCases.model(null);discovery.start(1000);world.drain(discovery,1000);
        check(discovery.refused().size()==1&&discovery.refused().get(0).reason==Refusal.AutoDelete,"owned refused mark survives to screen reason line");
        System.out.println("SYNC_CHAT_SCREEN_CHECKS="+(checks-before));
    }
    static void disclosure() {
        try {
            org.w3c.dom.Document strings=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new java.io.File(System.getProperty("purple.test.strings","TMessagesProj/src/main/res/values/purple_strings.xml")));
            java.util.Map<String,String> values=new java.util.HashMap<>();
            org.w3c.dom.NodeList rows=strings.getElementsByTagName("string");
            for(int i=0;i<rows.getLength();++i) {
                org.w3c.dom.Element row=(org.w3c.dom.Element)rows.item(i);values.put(row.getAttribute("name"),row.getTextContent());
            }
            String line="This includes your settings file, which lists chats and people by id and name, including chats from your other accounts that you added to Work Mode lists.";
            String cloud="The settings file may contain chat IDs and names, including chats from your other accounts that you added to Work Mode lists. It is kept in %1$s, a Telegram cloud chat: it is not end-to-end encrypted, and every signed-in session of this account can read it. state.toml stays on this device.";
            check(line.equals(values.get("PurpleSyncChatSettingsReaders")),"r3 reader settings line verbatim");
            check(cloud.equals(values.get("PurpleSyncChatCloud")),"r3 sync-chat disclosure verbatim");
            PurpleSyncChatScreen.Text text=(key,args)->{
                String value=values.get("PurpleSyncChat"+key);check(value!=null,"disclosure string exists "+key);
                return args.length==0?value:String.format(java.util.Locale.ROOT,value,args);
            };
            for(int slot:new int[]{0,1,3})for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}) {
                PurpleSyncChatOrigin origin=new PurpleSyncChatOrigin(slot,ORIGIN.environment,ORIGIN.userId,"disclosure-origin-"+slot);
                PurpleSyncChatClient.Info old=info(chat);
                PurpleSyncChatFacts.MappedPeer mapped=new PurpleSyncChatFacts.MappedPeer(origin,chat,old.peer.kind,old.peer.creator,old.peer.soleAdmin,old.peer.facts,old.members);
                PurpleSyncChatClient.Info scoped=new PurpleSyncChatClient.Info(origin,chat,mapped,old.error,"synthetic target",old.members,true);
                PurpleSyncChatScreen.Candidate candidate=new PurpleSyncChatScreen.Candidate(origin,chat,scoped);
                String rendered=PurpleSyncChatScreen.disclosure(candidate,text);
                check(rendered.contains("state.toml stays on this device."),"cloud disclosure in each captured account "+slot);
                check(rendered.contains(line)==candidate.eligibility.readers.warningRequired,"settings line for every config reader warning, regardless of home/active slot "+slot+" "+chat.token());
                check(!rendered.contains("user IDs and settings of other accounts")&&!rendered.contains("With several accounts"),"pre-r3 linkage clause absent");
            }
        } catch(Exception error) { throw new AssertionError(error); }
    }
    static void parity() {
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}) {
            PurpleSyncChatIO io=PurpleSyncChatIOCases.io(chat);Cache at=new Cache(chat,7,50);
            TLRPC.TL_messageEmpty empty=new TLRPC.TL_messageEmpty();empty.id=7;
            TLRPC.messages_Messages result=chat.kind==PeerKind.Channel?new TLRPC.TL_messages_channelMessages():messages();
            check(io.lookup(result,null,7,PurpleSyncChatIOCases::meta).status==PurpleSyncClient.LookupStatus.Failed,"zero-row record exact Unknown "+chat.token());
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,result,null,info(chat)).state==DirectRead.Unknown,"zero-row R3 Unknown "+chat.token());
            result.messages.add(empty);result.count=1;
            check(io.lookup(result,null,7,PurpleSyncChatIOCases::meta).status==PurpleSyncClient.LookupStatus.Failed,"peerless record exact Unknown "+chat.token());
            check(io.history(result,null,PurpleSyncChatIOCases::meta).messages==null,"peerless inventory holds "+chat.token());
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,result,null,info(chat)).state==DirectRead.MarkGone,"R3 peerless single-id readable exception "+chat.token());
            PurpleSyncChatClient.Info unknown=new PurpleSyncChatClient.Info(ORIGIN,chat,null,PurpleSyncChatClient.Error.Unknown,"",null,false);
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,result,null,unknown).state==DirectRead.Unknown,"R3 peerless requires independent readable evidence");
            empty.peer_id=peer(Peer.of(PeerKind.User,99));empty.flags=1;
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,result,null,info(chat)).state==DirectRead.Unknown,"R3 contradicting peer Unknown");
            empty.peer_id=peer(chat);
            check(io.lookup(result,null,7,PurpleSyncChatIOCases::meta).status==PurpleSyncClient.LookupStatus.Missing,"record matching scoped empty control");
            check(io.history(result,null,PurpleSyncChatIOCases::meta).messages!=null,"scoped history empty control");
            empty.peer_id=null;empty.flags=0;empty.id=8;
            check(PurpleSyncChatTelegram.classify(ORIGIN,at,result,null,info(chat)).state==DirectRead.Unknown,"R3 wrong-id peerless Unknown");
        }
    }
}
