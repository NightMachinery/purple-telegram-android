package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import java.util.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatModelCases {
    static PurpleSyncChatCache.Data cache(Peer peer,int id,int date){return new PurpleSyncChatCache.Data(new Cache(peer,id,date),"original",PurpleSyncChatCache.ReadersSnapshot.from(info(peer)),Collections.singleton("config"),new JSONObject(),false);}
    static final class World {
        final Map<String,TLRPC.Message> exact=new HashMap<>();
        final Map<Peer,PurpleSyncChatClient.Info> peers=new HashMap<>();
        final Map<Peer,RecordPresence> records=new HashMap<>();
        final List<PurpleSyncChatDiscovery.Command> commands=new ArrayList<>();
        final List<TLRPC.Message> global=new ArrayList<>(),self=new ArrayList<>(),broadcasts=new ArrayList<>();
        final Map<Peer,List<TLRPC.Message>> dialogs=new HashMap<>();
        final Map<String,DirectRead> reads=new HashMap<>();
        World(){records.put(SELF,RecordPresence.Empty);}
        SettingsHint hint=SettingsHint.Absent;boolean failSearch,capSearch;int exacts,infos,inventories;
        PurpleSyncChatDiscovery.Reply reply(PurpleSyncChatDiscovery.Command command){
            commands.add(command);PurpleSyncChatDiscovery.Reply reply=new PurpleSyncChatDiscovery.Reply(command);reply.success=true;
            switch(command.step){
                case Search:
                    List<TLRPC.Message> hits=command.search==PurpleSyncChatRequests.Search.Global?global:command.search==PurpleSyncChatRequests.Search.Self?self:command.search==PurpleSyncChatRequests.Search.Broadcasts?broadcasts:dialogs.getOrDefault(command.chat,Collections.emptyList());
                    reply.page=PurpleSyncChatRequests.page(ORIGIN,messages(hits.toArray(new TLRPC.Message[0])),command.search,command.chat);
                    if(failSearch&&command.search==PurpleSyncChatRequests.Search.Global)reply.success=false;
                    if(capSearch&&command.search==PurpleSyncChatRequests.Search.Global){int n=command.cursor.id==0?100:command.cursor.id-1;reply.page=page(200,n,mark(CHANNEL,n,n+100));}
                    break;
                case PeerInfo:++infos;reply.info=peers.getOrDefault(command.chat,info(command.chat));break;
                case ExactMark:
                    ++exacts;String key=command.mark.chat.token()+"/"+command.mark.markId;TLRPC.Message message=exact.get(key);
                    reply.exact=new PurpleSyncChatClient.Exact(reads.getOrDefault(key,message==null?DirectRead.MarkGone:DirectRead.Valid),message,peers.getOrDefault(command.chat,info(command.chat)));break;
                case SettingsHint:reply.hint=hint;break;
                case Inventory:++inventories;reply.records=records.getOrDefault(command.chat,RecordPresence.Unknown);break;
                default:throw new AssertionError("unexpected idle reply");
            }
            return reply;
        }
        void add(TLRPC.Message value){global.add(value);if(SELF.equals(PurpleSyncChatFacts.token(value.peer_id,11)))self.add(value);exact.put(PurpleSyncChatFacts.token(value.peer_id,11).token()+"/"+value.id,value);}
        void drain(PurpleSyncChatDiscovery model,long now){int remaining=250;while(model.command().step!=PurpleSyncChatDiscovery.Step.Idle){check(remaining-->0,"model bounded command progress");check(model.complete(reply(model.command()),now),"model accepts matching reply");}}
        long searches(PurpleSyncChatRequests.Search search){return commands.stream().filter(command->command.step==PurpleSyncChatDiscovery.Step.Search&&command.search==search).count();}
    }
    static PurpleSyncChatDiscovery model(PurpleSyncChatCache.Data original){return new PurpleSyncChatDiscovery(ORIGIN,original,false,SELF,Collections.emptyList(),"config");}
    static void run(){
        World world=new World();PurpleSyncChatDiscovery model=model(null);model.start(1000);PurpleSyncChatDiscovery.Command first=model.command();
        check(!model.complete(new PurpleSyncChatDiscovery.Reply(new PurpleSyncChatOrigin(0,0,11,"replacement"),first.run,first.ticket),1000),"login-fenced reply");
        check(!model.complete(new PurpleSyncChatDiscovery.Reply(ORIGIN,first.run+1,first.ticket),1000),"run-fenced reply");
        check(!model.complete(new PurpleSyncChatDiscovery.Reply(ORIGIN,first.run,first.ticket+1),1000),"ticket-fenced reply");world.drain(model,1000);
        check(model.status().phase==PurpleSyncChatDiscovery.Phase.Unset&&model.status().complete,"complete no-mark no-hint Unset");check(world.searches(PurpleSyncChatRequests.Search.Self)==1,"R2 retained");check(world.searches(PurpleSyncChatRequests.Search.Broadcasts)==1,"no-mark broadcast fallback");
        world=new World();world.add(mark(SELF,7,50));world.add(mark(CHANNEL,8,60));model=model(null);model.start(1000);world.drain(model,1000);
        check(model.status().reason.equals("InventoryUnknown")&&model.status().postingHeld&&model.cache()==null,"nonself inventory Unknown cannot prove empty or move");check(world.exacts==1&&world.inventories==1,"changed winner fresh read then inventory");
        world=new World();world.add(mark(SELF,7,50));world.add(mark(CHANNEL,8,60));world.records.put(CHANNEL,RecordPresence.Empty);model=model(null);model.start(1000);world.drain(model,1000);
        check(model.status().resolution.action==Action.Fill&&model.status().postingHeld&&model.cache()==null,"complete Empty Fill never commits");
        world=new World();world.add(mark(SELF,7,50));world.add(mark(CHANNEL,8,60));world.records.put(CHANNEL,RecordPresence.Present);model=model(null);model.start(1000);world.drain(model,1000);
        check(model.status().resolution.action==Action.Use&&CHANNEL.equals(model.status().resolution.readChat)&&model.cache().location.markId==8,"complete Present winner resolves with original cache");
        PurpleSyncChatCache.Data original=cache(SELF,7,50);world=new World();world.add(mark(SELF,7,50));world.add(mark(CHANNEL,8,60));model=model(original);model.start(1000);world.drain(model,1000);
        check(model.status().resolution.action==Action.Follow&&model.cache()==original&&model.status().resolution.cache.equals(original.location),"Follow preserves original cache");
        world=new World();world.add(mark(SELF,7,50));world.add(mark(CHANNEL,8,60));world.exact.remove("channel:44/8");model=model(null);model.start(1000);world.drain(model,1000);
        check(model.status().phase==PurpleSyncChatDiscovery.Phase.Use&&SELF.equals(model.status().resolution.readChat)&&world.exacts==2,"deleted stale search winner pruned and old mark reread");
        world=new World();world.add(mark(SELF,7,50));TLRPC.TL_message unknown=mark(GROUP,9,70);unknown.from_id=null;world.add(unknown);model=model(original);model.start(1000);world.drain(model,1000);
        check(!model.status().complete&&model.status().postingHeld&&model.cache()==original&&SELF.equals(model.status().resolution.readChat),"Unknown C0 scan preserves read cache and holds");
        world=new World();world.add(mark(SELF,7,50));world.failSearch=true;model=model(original);model.start(1000);world.drain(model,1000);
        check(!model.status().complete&&model.status().postingHeld&&model.cache()==original,"incomplete R1 cannot be hidden by R2 hit");
        world=new World();world.capSearch=true;model=model(original);model.start(1000);world.drain(model,1000);
        check(!model.status().complete&&world.searches(PurpleSyncChatRequests.Search.Global)==20&&world.searches(PurpleSyncChatRequests.Search.Broadcasts)==1&&model.cache()==original,"R1 cap retains cache");
        List<Peer> loaded=new ArrayList<>();for(int n=1;n<=51;n++)loaded.add(Peer.of(PeerKind.Chat,n));world=new World();model=new PurpleSyncChatDiscovery(ORIGIN,null,false,SELF,loaded,"config");model.start(1000);world.drain(model,1000);
        check(world.searches(PurpleSyncChatRequests.Search.Dialog)==50&&!model.status().complete,"F1 max50 plus truncated evidence holds");
        for(RecordPresence presence:RecordPresence.values()){
            world=new World();world.hint=SettingsHint.Found;world.records.put(SELF,presence);model=model(null);model.start(1000);world.drain(model,1000);
            check(world.inventories==1,"Q requires complete byte-validated inventory "+presence);
            check(presence==RecordPresence.Present?model.status().phase==PurpleSyncChatDiscovery.Phase.Use&&model.cache().location.markId==0:presence==RecordPresence.Empty?model.status().phase==PurpleSyncChatDiscovery.Phase.Unset:!model.status().complete&&model.status().postingHeld,"Q outcome "+presence);
        }
        world=new World();world.hint=SettingsHint.Unknown;model=model(null);model.start(1000);world.drain(model,1000);check(!model.status().complete&&model.status().postingHeld,"Q error not absence");
        world=new World();model=model(original);model.start(1000);world.drain(model,1000);
        for(int n=0;n<4;n++){
            check(model.status().phase==(n<3?PurpleSyncChatDiscovery.Phase.Searching:PurpleSyncChatDiscovery.Phase.Lost),"Searching final Lost "+n);
            check(model.status().postingHeld&&model.cache()==original&&SELF.equals(model.status().resolution.readChat),"Searching preserves read cache "+n);
            if(n<3){long due=1000+(n==0?30000:n==1?120000:600000);check(model.status().retryAt==due,"Searching retry interval "+n);model.tick(due-1);check(model.command().step==PurpleSyncChatDiscovery.Step.Idle,"not early retry");model.tick(due);world.drain(model,1000);}
        }
        world=new World();world.add(mark(SELF,7,50));model=model(original);model.startBeforePost(1000);check(model.command().step==PurpleSyncChatDiscovery.Step.ExactMark,"before-post fast fresh R3");world.drain(model,1000);
        check(world.searches(PurpleSyncChatRequests.Search.Global)==0&&world.exacts==1&&!model.status().postingHeld,"valid cached R3 avoids full search");
        world=new World();model=model(original);model.startBeforePost(1000);world.drain(model,1000);check(world.searches(PurpleSyncChatRequests.Search.Global)==1&&model.status().phase==PurpleSyncChatDiscovery.Phase.Searching,"confirmed cached loss resolves globally");
        world=new World();world.add(mark(SELF,7,50));world.reads.put("self/7",DirectRead.Unknown);model=model(original);model.startBeforePost(1000);world.drain(model,1000);check(model.status().reason.equals("UnknownMark")&&model.cache()==original,"fresh Unknown preserves cache");
        PurpleSyncChatCache.Data pending=new PurpleSyncChatCache.Data(original.location,"old",original.readers,original.warned,json("pending",true),false);model=model(pending);model.start(1000);check(model.command().step==PurpleSyncChatDiscovery.Step.Idle&&model.status().reason.equals("PendingMove")&&model.cache()==pending,"pending journal no automatic movement");
        world=new World();model=new PurpleSyncChatDiscovery(ORIGIN,null,true,SELF,Collections.emptyList(),"config");model.start(1000);world.drain(model,1000);check(model.status().phase==PurpleSyncChatDiscovery.Phase.Use&&model.cache().location.markId==0&&world.searches(PurpleSyncChatRequests.Search.SettingsHint)==0,"v1 implicit self binding");
        model=new PurpleSyncChatDiscovery(ORIGIN,original,true,CHANNEL,Collections.emptyList(),"config");model.start(1000);check(model.command().step==PurpleSyncChatDiscovery.Step.Idle&&model.status().reason.equals("BoundChatMismatch")&&model.cache()==original,"state/cache typed chat mismatch held before self-only I/O");model.startBeforePost(1000);check(model.command().step==PurpleSyncChatDiscovery.Step.Idle&&model.status().reason.equals("BoundChatMismatch"),"fast before-post cannot override bound-chat refusal");
        world=new World();world.add(mark(SELF,7,50));PurpleSyncChatFacts.Input refused=input(SELF);refused.userFull.ttl_period=10;world.peers.put(SELF,info(SELF,refused));model=model(original);model.startBeforePost(1000);world.drain(model,1000);check(model.status().postingHeld&&model.status().reason.equals("ChatIneligible"),"fresh eligibility change holds before post");
        world=new World();world.add(mark(SELF,7,50));PurpleSyncChatClient.Info selfInfo=info(SELF);world.peers.put(SELF,new PurpleSyncChatClient.Info(ORIGIN,SELF,selfInfo.peer,PurpleSyncChatClient.Error.None,"",null,false));model=model(original);model.startBeforePost(1000);world.drain(model,1000);check(model.status().postingHeld&&model.status().reason.equals("UnknownReaders"),"unknown readers holds before post");
        world=new World();world.add(mark(CHANNEL,7,50));PurpleSyncChatFacts.Input increased=input(CHANNEL);increased.groupFull.participants_count=2;
        PurpleSyncChatFacts.MappedPeer increasedFacts=PurpleSyncChatFacts.peer(ORIGIN,increased);world.peers.put(CHANNEL,new PurpleSyncChatClient.Info(ORIGIN,CHANNEL,increasedFacts,PurpleSyncChatClient.Error.None,"",new HashSet<>(Arrays.asList(11L,22L)),true));
        model=model(cache(CHANNEL,7,50));model.startBeforePost(1000);world.drain(model,1000);check(!model.status().postingHeld&&model.status().newReaders&&model.cache().newReaders,"new reader warning keeps previously accepted posting per C6");
        model=model(null);model.start(1000);first=model.command();model.cancel();check(!model.complete(new PurpleSyncChatDiscovery.Reply(first),1000),"cancel fences all late completions");
        // D-M2B5-13: FinishThenFollow leaves an unmarked cache {N, 0, 0}. For a group or channel it reads MarkGone through PeerInfo, never an exact read of id 0.
        world=new World();world.add(mark(SELF,7,50));world.records.put(SELF,RecordPresence.Present);model=model(cache(CHANNEL,0,0));model.start(1000);world.drain(model,1000);
        check(model.status().resolution.action==Action.Follow&&new Cache(SELF,7,50).equals(model.status().resolution.winner)&&world.commands.stream().noneMatch(c->c.step==PurpleSyncChatDiscovery.Step.ExactMark&&c.mark!=null&&c.mark.markId==0),"unmarked group or channel cache reads MarkGone and follows the winner");
        // A seeded chat's own history is searched after Saved Messages, so its mark is found when account-wide search misses it.
        TLRPC.TL_message seeded=mark(CHANNEL,8,60);
        for(boolean seed:new boolean[]{false,true}){
            world=new World();world.add(mark(SELF,7,50));world.dialogs.put(CHANNEL,new ArrayList<>(Collections.singletonList(seeded)));world.exact.put(CHANNEL.token()+"/8",seeded);world.records.put(CHANNEL,RecordPresence.Present);
            model=new PurpleSyncChatDiscovery(ORIGIN,original,false,SELF,Collections.emptyList(),"config",seed?Collections.singletonList(CHANNEL):Collections.emptyList());model.start(1000);world.drain(model,1000);
            check(world.searches(PurpleSyncChatRequests.Search.Dialog)==(seed?1:0)&&new Cache(CHANNEL,8,60).equals(model.status().resolution.winner)==seed,"seeded chat history decides whether its unindexed mark is seen "+seed);
        }
    }
}
