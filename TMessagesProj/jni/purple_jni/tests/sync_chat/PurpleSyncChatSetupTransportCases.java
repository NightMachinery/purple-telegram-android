package org.telegram.messenger.purple;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.tl.TL_update;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatSetupCases.*;

final class PurpleSyncChatSetupTransportCases {
    static final class MemoryReceipts implements PurpleSyncChatSetupReceipts.IO {
        final Map<String,byte[]> values=new HashMap<>();
        boolean fail;
        public byte[] read(String effect){return values.get(effect);}
        public boolean replace(String effect,byte[] old,byte[] next,java.util.function.BooleanSupplier current){
            if(fail||!current.getAsBoolean()||!Arrays.equals(old,values.get(effect)))return false;
            values.put(effect,next.clone());return true;
        }
    }
    static final class Sent {
        final PurpleSyncChatSetup.Command command;
        final TLObject request;
        final PurpleSyncChatTelegram.Reply done;
        Sent(PurpleSyncChatSetup.Command command,TLObject request,PurpleSyncChatTelegram.Reply done){this.command=command;this.request=request;this.done=done;}
    }
    static final class Run implements PurpleSyncChatSetupTransport.Port {
        final Env env;
        final PurpleSyncChatSetup setup;
        final PurpleSyncChatSetupTransport transport;
        final MemoryReceipts disk=new MemoryReceipts();
        final PurpleSyncChatSetupReceipts receipts=new PurpleSyncChatSetupReceipts(ORIGIN,PurpleSyncChatSetup.authority(PurpleSyncChatServiceCases.AUTH),disk);
        final List<Sent> sent=new ArrayList<>();
        final Map<Runnable,Long> timers=new LinkedHashMap<>();
        boolean refuseProof,failRenewSave;
        int renewals,cancelled;
        Run(Peer peer,boolean create){
            env=new Env();setup=new PurpleSyncChatSetup(env,selection(peer,create),ignored->{});
            transport=new PurpleSyncChatSetupTransport(this);env.wire=(request,done)->transport.write(setup.command(),request,done);
        }
        public boolean current(PurpleSyncChatSetup.Command command){return setup.commandCurrent(command);}
        public void dispatch(Runnable task){env.dispatch(task);}
        public int send(TLObject request,PurpleSyncChatTelegram.Reply done){
            check(setup.command()!=null&&setup.live(),"wire dispatch retains actual executor capability");
            PurpleSyncChatSetup.Effect effect=setup.command().effect;
            if(env.readersChange&&(effect==PurpleSyncChatSetup.Effect.Pin||effect==PurpleSyncChatSetup.Effect.Mute||effect==PurpleSyncChatSetup.Effect.Archive))
                check(env.server.newReaders,"sticky C6 cache is published before decoration gate reaches the wire");
            sent.add(new Sent(setup.command(),request,done));return sent.size();
        }
        public void cancel(int token){cancelled++;}
        public void delay(Runnable task,long millis){timers.put(task,env.clock+millis/1000);}
        public void cancelDelay(Runnable task){timers.remove(task);}
        public long nowSeconds(){return env.clock;}
        public boolean waiting(PurpleSyncChatSetup.Command command,long at){return setup.waiting(command,at);}
        public boolean retain(PurpleSyncChatSetup.Command command,TLObject request,TLRPC.Updates updates){
            return command.effect!=PurpleSyncChatSetup.Effect.Create&&command.effect!=PurpleSyncChatSetup.Effect.Mark
                    ||receipts.retain(command,request,updates,()->env.current&&env.journalState().operation.equals(command.operation));
        }
        public void resumeEffect(PurpleSyncChatSetup.Command command,Consumer<Boolean> ready){
            ++renewals;if(!setup.prepare(command)){ready.accept(false);return;}env.renewing=true;PurpleSyncChatSetup.ResumeProof proof=new PurpleSyncChatSetup.ResumeProof();
            env.resolve(env.owner,value->{
                proof.resolution=value.value;
                if(command.effect==PurpleSyncChatSetup.Effect.Create){approve(command,proof,ready);return;}
                env.info(command.chat,first->{
                    proof.first=first.value;
                    if(command.effect==PurpleSyncChatSetup.Effect.Mark)env.inventory(command.chat,inventory->{
                        proof.inventory=inventory.value;
                        env.info(command.chat,second->{proof.second=second.value;approve(command,proof,ready);});
                    });
                    else env.exact(command.mark,raw->{proof.mark=raw.value;approve(command,proof,ready);});
                });
            });
        }
        void approve(PurpleSyncChatSetup.Command command,PurpleSyncChatSetup.ResumeProof proof,Consumer<Boolean> ready){
            env.renewing=false;
            if(failRenewSave)env.file.fail=true;
            boolean allowed=!refuseProof&&setup.renew(command,proof);
            ready.accept(allowed);env.file.fail=false;
        }
        void start(){setup.start();env.drain();}
        Sent last(){return sent.get(sent.size()-1);}
        void error(int code,String text){TLRPC.TL_error error=new TLRPC.TL_error();error.code=code;error.text=text;last().done.accept(null,error);env.drain();}
        void success(){
            Sent value=last();TLRPC.TL_updates updates=new TLRPC.TL_updates();
            if(value.command.effect==PurpleSyncChatSetup.Effect.Create){updates=env.createdUpdates();updates.chats.get(0).photo=new TLRPC.TL_chatPhotoEmpty();env.server.created=true;}
            if(value.command.effect==PurpleSyncChatSetup.Effect.Mark){
                env.server.marked=true;
                TL_update.TL_updateMessageID mapping=new TL_update.TL_updateMessageID();mapping.id=7;mapping.random_id=value.command.random;updates.updates.add(mapping);
                TLRPC.TL_message mark=mark(value.command.chat,7,50);mark.message=PurpleSyncChatSetupRequests.MARK;
                if(value.command.chat.kind==PeerKind.Channel){TL_update.TL_updateNewChannelMessage update=new TL_update.TL_updateNewChannelMessage();update.message=mark;updates.updates.add(update);}
                else{TL_update.TL_updateNewMessage update=new TL_update.TL_updateNewMessage();update.message=mark;updates.updates.add(update);}
            }
            value.done.accept(value.command.effect==PurpleSyncChatSetup.Effect.Mute?new TLRPC.TL_boolTrue():updates,null);env.drain();
        }
        void until(PurpleSyncChatSetup.Effect effect){
            start();for(int count=0;last().command.effect!=effect&&count<10;count++)success();
            check(last().command.effect==effect,"target setup effect reached "+effect);
        }
        void retry(){
            check(timers.size()==1,"one definite-effect retry timer");
            Map.Entry<Runnable,Long> next=timers.entrySet().iterator().next();timers.remove(next.getKey());env.clock=next.getValue();next.getKey().run();env.drain();
        }
        void close(){setup.close();transport.cancel();env.drain();}
    }
    static void run(File root)throws Exception {
        int before=checks;
        for(PurpleSyncChatSetup.Effect effect:PurpleSyncChatSetup.Effect.values()){
            Run run=new Run(null,true);run.until(effect);Sent first=run.last();int count=run.sent.size(),renewals=run.renewals;
            run.error(420,"FLOOD_WAIT_8");
            check(run.setup.live()&&run.env.journalState().deferred&&run.env.journalState().retryAt==run.env.clock+8,"definite deferred effect remains journaled "+effect);
            run.retry();
            check(run.sent.size()==count+1&&run.renewals==renewals+1,"every resumed setup effect renews proof "+effect);
            check(run.last().command==first.command&&run.last().command.operation.equals(first.command.operation)
                    &&run.last().command.ticket==first.command.ticket&&run.last().command.random==first.command.random,"renewal preserves issued effect identity "+effect);
            if(effect==PurpleSyncChatSetup.Effect.Pin||effect==PurpleSyncChatSetup.Effect.Mute||effect==PurpleSyncChatSetup.Effect.Archive)
                check(run.env.server.committed!=null,"canonical mark cache committed before decoration retry "+effect);
            run.success();for(int i=0;run.setup.busy()&&i<8;i++)run.success();
            check(run.setup.phase()==PurpleSyncChatSetup.Phase.Complete&&run.env.server.committed!=null,"setup completes after definite defer "+effect);
            run.close();
        }
        for(String error:new String[]{"FLOOD_PREMIUM_WAIT_2","FLOOD_WAIT_0"}){
            Run run=new Run(SELF,false);run.until(PurpleSyncChatSetup.Effect.Mark);run.error(420,error);run.retry();
            check(run.setup.live()&&run.sent.size()==2,"dedicated setup handles both flood forms and zero wait "+error);run.close();
        }
        for(String error:new String[]{"INTERNAL_SERVER_ERROR","TRANSPORT_UNAVAILABLE","REQUEST_TIMEOUT"}){
            Run run=new Run(null,true);run.until(PurpleSyncChatSetup.Effect.Create);run.error(error.equals("INTERNAL_SERVER_ERROR")?500:-1,error);
            check(run.setup.phase()==PurpleSyncChatSetup.Phase.Held&&run.sent.size()==1&&run.timers.isEmpty()
                    &&run.env.step()==PurpleSyncChatSetup.Step.CreateStarted&&!run.env.journalState().deferred,"ambiguous creation never replays "+error);run.close();
        }
        Run rejected=new Run(null,true);rejected.until(PurpleSyncChatSetup.Effect.Mark);rejected.error(420,"FLOOD_WAIT_8");int count=rejected.sent.size();
        rejected.env.inventoryMode="partial";rejected.retry();
        check(rejected.sent.size()==count&&rejected.setup.phase()==PurpleSyncChatSetup.Phase.Held,"Unknown inventory refuses deferred mark without another send");rejected.close();
        Run save=new Run(null,true);save.until(PurpleSyncChatSetup.Effect.Create);save.error(420,"FLOOD_WAIT_8");save.failRenewSave=true;save.retry();
        check(save.setup.phase()==PurpleSyncChatSetup.Phase.Held&&save.setup.reason().equals("JournalUnavailable")&&save.env.owner==null&&save.sent.size()==1,
                "failed renewal save publishes Held and releases original capability without waiting for wire callback");save.close();
        for(PurpleSyncChatSetup.Effect effect:PurpleSyncChatSetup.Effect.values()){
            Run cancelled=new Run(null,true);cancelled.until(effect);cancelled.error(420,"FLOOD_WAIT_8");int sent=cancelled.sent.size();
            cancelled.close();for(Runnable timer:new ArrayList<>(cancelled.timers.keySet()))timer.run();cancelled.env.drain();
            check(cancelled.sent.size()==sent&&cancelled.env.owner==null,"cancel during deferred setup never writes "+effect);
        }
        for(String bad:new String[]{"date","peer","forward","media","empty","zero","duplicate","inexact","flood-flag","count"}){
            Run run=new Run(null,true);run.until(PurpleSyncChatSetup.Effect.Pin);run.error(420,"FLOOD_WAIT_8");count=run.sent.size();run.env.badExact=bad;run.retry();
            check(run.sent.size()==count&&run.setup.phase()==PurpleSyncChatSetup.Phase.Held,"decoration retry requires whole canonical exact read "+bad);run.close();
        }
        for(String mode:new String[]{"addition","rights","unknown"}){
            Run run=new Run(null,true);run.until(PurpleSyncChatSetup.Effect.Mute);run.error(420,"FLOOD_WAIT_8");count=run.sent.size();
            run.env.readersChange=mode.equals("addition");run.env.badRights=mode.equals("rights");run.env.unknownReaders=mode.equals("unknown");run.retry();
            check(mode.equals("addition")?run.sent.size()==count+1&&run.env.server.newReaders:run.sent.size()==count,
                    "deferred decoration C6 warns for known additions, unknown or lost rights holds "+mode);run.close();
        }
        initialProof();lateReceipt();limit();borrowedWait();
        receipts(root);restartDefinite();postingHold(root);
        System.out.println("SETUP_TRANSPORT_CHECKS="+(checks-before));
    }
    static void initialProof(){
        Run blocked=new Run(null,true);blocked.refuseProof=true;blocked.start();
        check(blocked.sent.isEmpty()&&blocked.setup.phase()==PurpleSyncChatSetup.Phase.Held&&blocked.env.journalState().deferred,
                "proof refusal before first wire dispatch retains definitely-unsent intent");
        blocked.close();Env retry=new Env(blocked.env.file,blocked.env.server);
        PurpleSyncChatSetup resumed=new PurpleSyncChatSetup(retry,null,ignored->{});resumed.start();retry.drain();
        check(resumed.phase()==PurpleSyncChatSetup.Phase.Complete&&retry.creates==1,"explicit resume after proof refusal may safely create once");resumed.close();
        Run mark=new Run(SELF,false);mark.refuseProof=true;mark.start();
        check(mark.sent.isEmpty()&&mark.env.journalState().step==PurpleSyncChatSetup.Step.MarkStarted&&mark.env.journalState().deferred,
                "mark proof refusal cannot become an ambiguous issued mark");mark.close();
    }
    static void lateReceipt()throws Exception{
        Run run=new Run(null,true);run.start();Sent original=run.last();run.close();int posts=run.env.posts;
        run.success();
        PurpleSyncChatSetup.Created retained=run.receipts.created(original.command.operation);
        check(retained!=null&&run.env.posts==posts&&run.setup.phase()==PurpleSyncChatSetup.Phase.Cancelled,
                "late exact original create response is retained without advancing a cancelled setup");
        PurpleSyncChatSetupReceipts other=new PurpleSyncChatSetupReceipts(ORIGIN,"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",run.disk);
        boolean refused=false;try{refused=other.created(original.command.operation)==null;}catch(Exception expected){refused=true;}
        check(refused,"foreign login authority cannot use retained creation response");
        for(String key:new String[]{"effect","request","response"}){
            byte[] prior=run.disk.values.get("Create");org.json.JSONObject value=new org.json.JSONObject(new String(prior,java.nio.charset.StandardCharsets.UTF_8));
            value.put(key,key.equals("effect")?"Mark":java.util.Base64.getEncoder().encodeToString(new byte[]{1,2,3,4}));
            run.disk.values.put("Create",value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));refused=false;
            try{refused=run.receipts.created(original.command.operation)==null;}catch(Exception expected){refused=true;}
            check(refused,"retained receipt rejects corrupt "+key);run.disk.values.put("Create",prior);
        }
    }
    static void limit(){
        Run run=new Run(null,true);run.start();run.error(400,"CHANNELS_TOO_MUCH");
        check(run.setup.phase()==PurpleSyncChatSetup.Phase.Limit&&run.env.owner==null&&run.env.journalState().step==PurpleSyncChatSetup.Step.Ready,
                "literal native limit result returns an explicit resumable limit state");
        run.setup.useSavedMessages();run.env.drain();
        check(run.last().command.effect==PurpleSyncChatSetup.Effect.Mark&&run.last().command.chat.equals(SELF),"limit Saved Messages action uses same explicit setup flow");
        run.success();check(run.setup.phase()==PurpleSyncChatSetup.Phase.Complete&&run.env.pins+run.env.mutes+run.env.archives==0,
                "Saved Messages fallback completes mark-only without channel decorations");run.close();
    }
    static void borrowedWait(){
        PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));
        Native wire=new Native();env.injectedFactory=()->new PurpleSyncChatTelegram(ORIGIN,wire);
        Object owner=new Object();check(PurpleSyncOperations.begin(env.key,owner,()->true),"owned setup borrow for wait forwarding");
        PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleRpcRetry.Transient> waiting=new ArrayList<>();
        service.checkBeforePost("config",new PurpleSyncChatService.Borrowed(env.key,owner,()->true,waiting::add),ignored->{});
        int reply=1;while(!(wire.requests.get(reply-1) instanceof TLRPC.TL_users_getFullUser)){check(reply<30,"bounded setup discovery before full info");wire.reply(reply++,messages(mark(SELF,7,50)));}
        TLRPC.TL_error error=new TLRPC.TL_error();error.code=420;error.text="FLOOD_WAIT_90";wire.replies.get(reply).accept(null,error);
        check(waiting.size()==1&&waiting.get(0).retryAt==wire.clock+90000&&PurpleSyncOperations.owned(env.key,owner),
                "owned service read flood forwards wall-clock wait without completing or replacing the setup borrow");
        service.cancelBorrowed(owner);service.close(false);PurpleSyncOperations.end(env.key,owner);
    }
    static void restartDefinite(){
        for(boolean create:new boolean[]{false,true}){
            Run first=new Run(create?null:SELF,create);first.start();first.error(420,"FLOOD_WAIT_8");
            Memory file=first.env.file;Server server=first.env.server;long deadline=first.env.journalState().retryAt;first.close();
            Env next=new Env(file,server);next.clock=deadline;
            PurpleSyncChatSetup setup=new PurpleSyncChatSetup(next,null,ignored->{});setup.start();next.drain();
            check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&next.creates==(create?1:0)&&next.posts==1,
                    "restart of definitely unsent effect renews instead of ambiguous reconciliation create="+create);setup.close();
        }
    }
    static void postingHold(File root)throws Exception{
        File journal=new File(root,"posting-held-journal.json");
        check(!PurpleSyncChatSetupFlow.postingHeld(journal),"no setup journal leaves existing post behavior unchanged");
        java.nio.file.Files.write(journal.toPath(),new byte[]{1});
        check(PurpleSyncChatSetupFlow.postingHeld(journal),"setup journal including malformed state holds first settings post");
        PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));env.world.add(mark(SELF,7,50));env.setupHeld=true;
        PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> done=new ArrayList<>();service.checkBeforePost("config",null,done::add);
        check(done.size()==1&&done.get(0).complete&&done.get(0).phase==PurpleSyncChatDiscovery.Phase.Use
                &&done.get(0).postingHeld&&!new PurpleSyncChat.Status(done.get(0)).selfPostable(),"setup first settings post remains off while discovery stays readable");service.close(false);
    }
    static void receipts(File root)throws Exception{
        Run create=new Run(null,true);create.until(PurpleSyncChatSetup.Effect.Create);Sent issued=create.last();create.success();
        PurpleSyncChatSetup.Created receipt=create.receipts.created(issued.command.operation);
        check(receipt!=null&&receipt.operation.equals(issued.command.operation)&&receipt.chat.equals(CHANNEL),"retained serialized original create response reconciles exact operation");
        boolean refused=false;try{refused=create.receipts.created(UUID.randomUUID().toString())==null;}catch(Exception expected){refused=true;}check(refused,"wrong operation cannot adopt retained create receipt");
        create.close();
        for(Peer peer:new Peer[]{SELF,USER,GROUP,CHANNEL}){
            Run run=new Run(peer,false);run.until(PurpleSyncChatSetup.Effect.Mark);Sent mark=run.last();run.success();
            PurpleSyncChatSetup.Posted found=run.receipts.mark(mark.command.operation,peer,mark.command.random,run.env.facts(peer));
            check(found!=null&&found.random==mark.command.random&&found.mark.chat.equals(peer),"real TL serialized mark receipt bound to typed peer and random "+peer.kind);
            check(run.receipts.mark(mark.command.operation,peer,mark.command.random+1,run.env.facts(peer))==null,"wrong saved random cannot reconcile mark");
            run.close();
        }
    }
}
