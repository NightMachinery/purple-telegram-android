package org.telegram.messenger.purple;

import java.io.File;
import java.util.*;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatAlignmentCases {
    static PurpleSyncChatServiceCases.Env seeded() {
        PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();
        env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));env.world.add(mark(SELF,7,50));return env;
    }
    static void run(File root)throws Exception {
        int before=checks;
        PurpleSyncChatServiceCases.Env env=seeded();PurpleSyncChatService service=new PurpleSyncChatService(env);
        ArrayList<PurpleSyncChatDiscovery.Status> done=new ArrayList<>();
        service.checkBeforePost("config",null,done::add);
        check(done.size()==1&&done.get(0).complete&&!done.get(0).postingHeld,"disk-only cache requires complete discovery before posting");
        check(env.world.searches(PurpleSyncChatRequests.Search.Global)==1&&env.world.exacts==1,"cold before-post full R1/R2 plus fresh R3");
        service.checkBeforePost("config",null,done::add);
        check(env.world.searches(PurpleSyncChatRequests.Search.Global)==1&&env.world.exacts==2,"recent receipt reuses discovery but never R3/full info");
        env.clock+=10*60*1000;service.checkBeforePost("config",null,done::add);
        check(env.world.searches(PurpleSyncChatRequests.Search.Global)==1,"under15min direct control");
        env.clock+=6*60*1000;service.checkBeforePost("config",null,done::add);
        check(env.world.searches(PurpleSyncChatRequests.Search.Global)==2,"direct R3 does not renew full-discovery epoch");service.close(false);
        for(String change:new String[]{"expiry","backward","binding","stream","cache","failure"}) {
            env=seeded();service=new PurpleSyncChatService(env);service.ensureResolved(true,"config",null,ignored->{});
            if(change.equals("expiry"))env.clock+=900000;
            if(change.equals("backward"))env.clock=999;
            if(change.equals("binding"))env.binding=new PurpleSyncChatService.Binding(true,false,SELF,"different-binding-same-chat");
            if(change.equals("cache")){env.file.bytes=null;env.file.seed(PurpleSyncChatModelCases.cache(SELF,8,60));env.world.add(mark(SELF,8,60));}
            if(change.equals("failure")){env.clock+=900000;env.world.failSearch=true;}
            done.clear();service.checkBeforePost(change.equals("stream")?"library":"config",null,done::add);
            check(done.size()==1&&env.world.searches(PurpleSyncChatRequests.Search.Global)>=2,"receipt rejects "+change);
            if(change.equals("failure"))check(done.get(0).postingHeld&&!done.get(0).complete,"expired failed full discovery holds and keeps read cache");
            service.close(false);
        }
        for(String during:new String[]{"r3","save","binding"}) {
            env=seeded();service=new PurpleSyncChatService(env);service.ensureResolved(true,"config",null,ignored->{});
            env.clock+=899000;env.heldClient=true;done.clear();service.checkBeforePost("config",null,done::add);
            check(done.isEmpty()&&env.pending!=null,"hold native R3 completion for expiry "+during);
            if(during.equals("save"))env.heldWorker=true;
            else if(during.equals("binding"))env.binding=new PurpleSyncChatService.Binding(true,false,SELF,"changed-during-R3");
            else env.clock+=2000;
            env.heldClient=false;env.pending.run();
            if(during.equals("save")){check(done.isEmpty(),"save worker still deferred");env.clock+=2000;env.heldWorker=false;while(!env.tasks.isEmpty())env.tasks.remove().run();}
            check(done.size()==1&&done.get(0).complete&&!done.get(0).postingHeld&&env.world.searches(PurpleSyncChatRequests.Search.Global)==2,"expiry/binding during R3 or save forces full discovery before delivery "+during);
            check(!PurpleSyncOperations.busy(env.key),"restart releases account lock "+during);service.close(false);
        }
        env=seeded();service=new PurpleSyncChatService(env);service.ensureResolved(true,"config",null,ignored->{});service.close(false);
        service=new PurpleSyncChatService(env);service.checkBeforePost("config",null,ignored->{});
        check(env.world.searches(PurpleSyncChatRequests.Search.Global)==2,"new service has no disk-derived discovery receipt");service.close(false);
        pageCap(root);readers();
        System.out.println("SYNC_CHAT_ALIGNMENT_CHECKS="+(checks-before));
    }
    static void pageCap(File root) {
        for(int terminal:new int[]{999,1000}) {
            PurpleSyncChatRecordCases.Client client=new PurpleSyncChatRecordCases.Client(new File(root,"no-download-needed"));
            for(int p=1;p<=terminal;++p) {
                TLRPC.TL_messages_messagesSlice page=new TLRPC.TL_messages_messagesSlice();page.count=terminal-1;
                if(p<terminal){TLRPC.TL_message row=mark(SELF,10000-p,50);row.message="ordinary";page.messages.add(row);}
                client.pages.add(page);
            }
            RecordPresence result=PurpleSyncChatRecordCases.presence(client);
            check(result==(terminal==999?RecordPresence.Empty:RecordPresence.Unknown),"terminal page cap boundary "+terminal);
            check(client.history==terminal&&client.downloads==0,"cap has no extra request/download "+terminal);
        }
    }
    static PurpleSyncChatCache.ReadersSnapshot snapshot(Long... members) {
        PurpleSyncChatFacts.Input raw=input(CHANNEL);raw.groupFull.participants_count=members.length;
        PurpleSyncChatClient.Info info=info(CHANNEL,raw);
        return PurpleSyncChatCache.ReadersSnapshot.from(new PurpleSyncChatClient.Info(ORIGIN,CHANNEL,info.peer,info.error,"synthetic",new HashSet<>(Arrays.asList(members)),true));
    }
    static void readers()throws Exception {
        PurpleSyncChatCache.ReadersSnapshot base=snapshot(11L,22L,33L);
        check(!base.newReaders(snapshot(11L,22L)),"departure alone does not warn");
        check(!base.newReaders(snapshot(11L)),"all other readers leave without warning");
        check(base.newReaders(snapshot(11L,22L,44L)),"same-count replacement warns");
        check(base.newReaders(snapshot(11L,44L)),"new id warns even with net departure");
        check(base.newReaders(snapshot(11L,22L,33L,44L)),"member addition warns");
        PurpleSyncChatCache.ReadersSnapshot restored=PurpleSyncChatCache.ReadersSnapshot.parse(base.json());
        check(!restored.newReaders(snapshot(11L,22L))&&restored.newReaders(snapshot(11L,44L)),"private hashed member set survives restart");
        JSONObject noList=base.json();noList.remove("memberHashes");noList.put("memberHash","");
        PurpleSyncChatCache.ReadersSnapshot counts=PurpleSyncChatCache.ReadersSnapshot.parse(noList);
        check(!counts.newReaders(snapshot(11L,22L))&&!counts.newReaders(snapshot(11L,44L)),"count-only departures and replacements remain blind");
        check(counts.newReaders(snapshot(11L,22L,33L,44L)),"count-only population increase warns");
        JSONObject lost=base.json();lost.put("creator",false);
        check(base.newReaders(PurpleSyncChatCache.ReadersSnapshot.parse(lost)),"lost creator rights warns");
        JSONObject legacy=base.json();legacy.remove("memberHashes");
        PurpleSyncChatCache.ReadersSnapshot old=PurpleSyncChatCache.ReadersSnapshot.parse(legacy);
        check(!old.newReaders(snapshot(11L,22L))&&old.newReaders(snapshot(11L,22L,44L)),"legacy aggregate cache departure/replacement controls");
        PurpleSyncChatServiceCases.Memory memory=new PurpleSyncChatServiceCases.Memory();
        PurpleSyncChatCache.Data data=new PurpleSyncChatCache.Data(new Cache(CHANNEL,7,50),"synthetic",base,Collections.singleton("config"),new JSONObject(),true);
        memory.seed(data);PurpleSyncChatCache.Data read=PurpleSyncChatCache.load(memory,ORIGIN,PurpleSyncChatServiceCases.AUTH).data;
        check(read.newReaders&&read.accepted("config")&&read.readers.memberHashes.equals(base.memberHashes),"C6 warning, consent and original roster stay sticky");
        String serialized=base.json().getJSONArray("memberHashes").toString();
        check(!serialized.contains("\\\"22\\\"")&&!serialized.contains("\\\"33\\\""),"member set persists digests, no raw user ids");
    }
}
