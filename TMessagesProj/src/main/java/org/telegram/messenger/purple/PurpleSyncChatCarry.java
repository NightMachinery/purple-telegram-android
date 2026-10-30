package org.telegram.messenger.purple;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PurpleSyncChatMarkUpdates;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

/**
 * Production carry dispatcher: the PurpleSyncChatMove.Port that turns each carry command into real Telegram reads and
 * writes. One instance per PurpleSyncChatMove, never reused after close(). Every reply and status runs on the worker.
 * A null reply means the outcome is unknown; a non-null reply carries only verified fields, so the executor's own checks
 * hold with their typed reason. See docs/sync-chat-move.md, "Production carry dispatcher".
 */
final class PurpleSyncChatCarry implements PurpleSyncChatMove.Port, AutoCloseable {
    /** Everything the dispatcher needs from the account and the network; {@link #production} binds it to a holder. */
    interface Env {
        PurpleSyncChatOrigin origin();
        String authority();
        String operationKey();
        boolean current();
        void worker(Runnable task);
        void quiesce(Runnable done);
        PurpleSyncChatMoveStore store()throws Exception;
        PurpleSyncChatCache.Loaded cache();
        PurpleSyncChatService.Binding binding();
        PurpleSyncChatClient reads(BooleanSupplier current);
        PurpleSyncChatTelegram.Platform platform(BooleanSupplier current);
        PurpleSyncClient scanner(PurpleSyncChatClient.Info chat,BooleanSupplier current);
        int send(TLObject request,PurpleSyncChatTelegram.Reply done);
        int upload(long file,byte[] bytes,Consumer<PurpleSyncAutoUpload.Result> done);
        void cancel(int token);
        void apply(Peer chat,TLObject response);
        long now();
        long wall();
        PurpleRpcRetry.Transient cooldown();
        void failed(TLRPC.TL_error error);
        void waiting(Object owner,PurpleRpcRetry.Transient value);
        void delay(Runnable task,long millis);
        void cancelDelay(Runnable task);
    }
    static final int MAX_DISCOVERY_STEPS=400;
    private final Env env;
    private final BooleanSupplier current,foreground;
    private final Consumer<PurpleSyncChatMove.Status> status;
    private final PurpleSyncChatOrigin origin;
    private final String authority;
    private final Object lock=new Object();
    private final Map<PurpleSyncChatMove.Command,Run> runs=new IdentityHashMap<>();
    private final List<Run> passes=new ArrayList<>();
    private volatile boolean closed;

    static PurpleSyncChatCarry production(PurpleSyncChat.Holder holder,BooleanSupplier current,BooleanSupplier foreground,Consumer<PurpleSyncChatMove.Status> status){
        return new PurpleSyncChatCarry(new Production(holder),current,foreground,status);
    }
    PurpleSyncChatCarry(Env env,BooleanSupplier current,BooleanSupplier foreground,Consumer<PurpleSyncChatMove.Status> status){
        this.env=env;this.current=current;this.foreground=foreground;this.status=status;origin=env.origin();authority=env.authority();
    }

    public PurpleSyncChatOrigin origin(){return origin;}
    public String authority(){return authority;}
    public String operationKey(){return env.operationKey();}
    public boolean current(){return live();}
    public boolean foreground(){return foreground.getAsBoolean();}
    public void worker(Runnable task){env.worker(task);}
    public void quiesce(Runnable done){env.quiesce(done);}
    public PurpleSyncChatMoveStore open(BooleanSupplier opening,boolean interrupted)throws Exception {
        if(!live()||!opening.getAsBoolean())throw new IOException("AccountUnavailable");
        PurpleSyncChatMoveStore store=env.store();boolean opened=false;
        try{if(interrupted)store.openInterrupted();else store.open();opened=live()&&opening.getAsBoolean();if(!opened)throw new IOException("AccountUnavailable");return store;}
        finally{if(!opened)try{store.close();}catch(Exception|LinkageError ignored){}}
    }
    public void status(PurpleSyncChatMove.Status value){Consumer<PurpleSyncChatMove.Status> observer=status;if(observer!=null)observer.accept(value);}
    public void dispatch(PurpleSyncChatMove.Command command,Consumer<PurpleSyncChatMove.Reply> done){
        if(command==null||done==null)return;
        Run run=new Run(command);run.failure=()->finish(run,null,done);
        synchronized(lock){if(closed||runs.containsKey(command))return;runs.put(command,run);}
        run.post(()->execute(run,reply->finish(run,reply,done)));
    }
    public void cancel(PurpleSyncChatMove.Command command){Run run;synchronized(lock){run=runs.remove(command);}if(run!=null)run.release();}
    /** Read-only fresh resolution for controllers; safe while the carry lease is held. Never writes the sync-chat cache. */
    void freshFrame(String stream,Consumer<PurpleSyncChatScreen.Frame> done){
        if(done==null)return;
        Run run=new Run(null);run.failure=()->endPass(run,null,done);
        synchronized(lock){if(closed)return;passes.add(run);}
        run.post(()->discover(run,stream,pass->endPass(run,pass==null?null:pass.frame(),done)));
    }
    @Override public void close(){
        List<Run> all;
        synchronized(lock){if(closed)return;closed=true;all=new ArrayList<>(runs.values());all.addAll(passes);runs.clear();passes.clear();}
        for(Run run:all)run.release();
    }

    private boolean live(){return !closed&&current.getAsBoolean()&&env.current();}
    private interface Work{void run()throws Exception;}
    /** One command or one freshFrame pass: owns its readers, scans, writes and timers, and fences their callbacks. */
    private final class Run {
        final PurpleSyncChatMove.Command command;
        Runnable failure=()->{};
        private final List<Runnable> owned=new ArrayList<>();
        private volatile boolean released;
        Run(PurpleSyncChatMove.Command command){this.command=command;}
        boolean live(){return !released&&PurpleSyncChatCarry.this.live();}
        void own(Runnable release){boolean late;synchronized(this){late=released;if(!late)owned.add(release);}if(late)quiet(release);}
        void post(Work work){
            env.worker(()->{
                if(released)return;
                if(!live()){failure.run();return;}
                try{work.run();}catch(Exception|LinkageError error){failure.run();}
            });
        }
        void release(){
            List<Runnable> list;
            synchronized(this){if(released)return;released=true;list=new ArrayList<>(owned);owned.clear();}
            for(Runnable value:list)quiet(value);
        }
    }
    private static void quiet(Runnable value){try{value.run();}catch(RuntimeException|LinkageError ignored){}}
    private void finish(Run run,PurpleSyncChatMove.Reply reply,Consumer<PurpleSyncChatMove.Reply> done){
        boolean deliver;
        synchronized(lock){if(runs.get(run.command)!=run)return;runs.remove(run.command);deliver=!closed;}
        deliver&=!run.released;run.release();
        if(deliver)done.accept(reply);
    }
    private void endPass(Run run,PurpleSyncChatScreen.Frame frame,Consumer<PurpleSyncChatScreen.Frame> done){
        boolean deliver;
        synchronized(lock){if(!passes.remove(run))return;deliver=!closed;}
        deliver&=!run.released;run.release();
        if(deliver)done.accept(frame);
    }

    private void execute(Run run,Consumer<PurpleSyncChatMove.Reply> done)throws Exception {
        PurpleSyncChatMove.Command command=run.command;
        switch(command.action){
        case "Fresh":fresh(run,facts->done.accept(facts==null?null:reply(facts)));break;
        case "Source":fresh(run,facts->{
            if(facts==null){done.accept(null);return;}
            PurpleSyncChatMove.Reply reply=reply(facts);
            scan(run,reply.sourceAccess.equals("ChatGone")?null:facts.fresh.source,old->scan(run,facts.fresh.target,destination->{
                reply.source=old;reply.destination=destination;receipts(old,reply.sourceRecords);done.accept(reply);
            }));
        });break;
        case "Pending":fresh(run,facts->{
            if(facts==null){done.accept(null);return;}
            PurpleSyncChatMove.Reply reply=reply(facts);reply.sendIntent="Unknown";
            scan(run,reply.sourceAccess.equals("ChatGone")?null:facts.fresh.source,old->{reply.source=old;done.accept(reply);});
        });break;
        case "Destination":fresh(run,facts->{
            if(facts==null){done.accept(null);return;}
            PurpleSyncChatMove.Reply reply=reply(facts);
            scan(run,facts.fresh.target,destination->{reply.destination=destination;done.accept(reply);});
        });break;
        case "Cleanup":fresh(run,facts->{
            if(facts==null){done.accept(null);return;}
            PurpleSyncChatMove.Reply reply=reply(facts);
            if(reply.sourceAccess.equals("ChatGone")&&facts.fresh.oldRead==DirectRead.ChatGone){done.accept(reply);return;}
            scan(run,facts.fresh.target,destination->{reply.destination=destination;done.accept(reply);});
        });break;
        case "Mark":fresh(run,facts->{
            if(facts==null){done.accept(null);return;}
            PurpleSyncChatMove.Reply reply=reply(facts);reply.markRoute=route(facts.fresh.target);
            exact(run,facts.fresh.target.chat,command.messageId,read->{reply.response=read!=null&&read.error==null?read.response:null;done.accept(reply);});
        });break;
        case "CleanupRead":fresh(run,facts->{
            if(facts==null){done.accept(null);return;}
            PurpleSyncChatMove.Reply reply=reply(facts);
            scan(run,facts.fresh.target,destination->{reply.destination=destination;cleanupRead(run,facts,reply,command.cleanupItem,()->done.accept(reply));});
        });break;
        case "Effect":effect(run,done);break;
        case "CleanupDelete":cleanupDelete(run,done);break;
        default:done.accept(null);
        }
    }
    private static PurpleSyncChatMove.Reply reply(Facts facts){
        PurpleSyncChatMove.Reply reply=new PurpleSyncChatMove.Reply();reply.fresh=facts.fresh;reply.sourceAccess=facts.access;reply.libraryPending=false;return reply;
    }
    private static void receipts(PurpleSyncInventory inventory,List<PurpleSyncChatMoveIO.RecordReceipt> into){
        if(inventory==null)return;
        for(int i=0;i<inventory.count();++i){PurpleSyncChatMoveIO.RecordReceipt receipt=inventory.receipt(i);if(receipt!=null)into.add(receipt);}
    }
    private PurpleSyncChatIO route(PurpleSyncChatClient.Info info){
        try{return info!=null&&info.peer!=null?new PurpleSyncChatIO(origin,info.chat,info.peer):null;}catch(IllegalArgumentException error){return null;}
    }

    /** Fresh facts for one command: a full read-only discovery pass plus fresh full info and input peers of both chats. */
    private static final class Facts {
        final PurpleSyncChatMoveIO.Fresh fresh;
        final String access;
        Facts(PurpleSyncChatMoveIO.Fresh fresh,String access){this.fresh=fresh;this.access=access;}
    }
    private void fresh(Run run,Consumer<Facts> done)throws Exception {
        PurpleSyncChatMove.Command command=run.command;
        Peer source=Peer.parse(command.source,origin.userId),target=Peer.parse(command.target,origin.userId);
        if(source==null||target==null){done.accept(null);return;}
        discover(run,"config",pass->{
            PurpleSyncChatDiscovery.Status resolved=pass==null?null:pass.model.status();
            if(resolved==null||!resolved.complete){done.accept(null);return;}
            ResolveInput input=pass.model.input();boolean full=!pass.model.directOnly();
            info(run,pass.reads,source,old->info(run,pass.reads,target,next->{
                if(old==null||next==null){done.accept(null);return;}
                peers(run,source,target,(oldPeer,nextPeer)->{
                    DirectRead oldRead=pass.opening.data!=null?input.cacheRead
                            :old.readable()?DirectRead.Valid:old.error==PurpleSyncChatClient.Error.ChannelPrivate?DirectRead.ChatGone:DirectRead.Unknown;
                    done.accept(new Facts(new PurpleSyncChatMoveIO.Fresh(origin,authority,old,oldPeer,next,nextPeer,input,full,oldRead),access(old,oldRead)));
                });
            }));
        });
    }
    static String access(PurpleSyncChatClient.Info source,DirectRead oldRead){
        if(oldRead==DirectRead.ChatGone||source.error==PurpleSyncChatClient.Error.ChannelPrivate)return "ChatGone";
        if(!source.readable())return "Unknown";
        if(source.eligibility().refusal==Refusal.Protected)return "Protected";
        return source.eligibility().allowed()?"Readable":"Unknown";
    }
    private void info(Run run,PurpleSyncChatClient reads,Peer chat,Consumer<PurpleSyncChatClient.Info> done){
        reads.dispatch(()->reads.info(chat,value->run.post(()->done.accept(value!=null&&origin.equals(value.origin)&&chat.equals(value.chat)?value:null))));
    }
    private void peers(Run run,Peer source,Peer target,BiConsumer<TLRPC.InputPeer,TLRPC.InputPeer> done){
        PurpleSyncChatTelegram.Platform platform=env.platform(run::live);
        platform.dispatch(()->{
            TLRPC.InputPeer a=run.live()?platform.input(source):null,b=run.live()?platform.input(target):null;
            run.post(()->done.accept(a,b));
        });
    }

    /** One read-only discovery pass. The model starts from the cached location even while a move journal is pending. */
    private final class Pass {
        final PurpleSyncChatCache.Loaded opening;
        final PurpleSyncChatService.Binding binding;
        final PurpleSyncChatClient reads;
        final PurpleSyncChatDiscovery model;
        Pass(PurpleSyncChatCache.Loaded opening,PurpleSyncChatService.Binding binding,PurpleSyncChatClient reads,PurpleSyncChatDiscovery model){
            this.opening=opening;this.binding=binding;this.reads=reads;this.model=model;
        }
        PurpleSyncChatScreen.Frame frame(){
            PurpleSyncChatDiscovery.Status value=model.status();
            return new PurpleSyncChatScreen.Frame(origin,new PurpleSyncChat.Status(value),binding,opening,model.screenInfo(),model.refused(),
                    model.staleMarks(authority),value.complete?model.input().cacheRead:DirectRead.Unknown);
        }
    }
    private void discover(Run run,String stream,Consumer<Pass> done){
        if(!"config".equals(stream)&&!"library".equals(stream)){done.accept(null);return;}
        PurpleSyncChatCache.Loaded opening=env.cache();PurpleSyncChatService.Binding binding=env.binding();
        if(opening==null||!opening.ok||opening.paused||binding==null||!binding.ok){done.accept(null);return;}
        PurpleSyncChatCache.Data data=opening.data;
        PurpleSyncChatCache.Data input=data!=null&&data.pendingMove()?new PurpleSyncChatCache.Data(data.location,data.title,data.readers,data.warned,new JSONObject(),data.newReaders):data;
        PurpleSyncChatClient reads=env.reads(run::live);run.own(()->reads.dispatch(reads::cancel));
        reads.dispatch(()->{
            List<Peer> fallback=run.live()?reads.fallback():Collections.emptyList();
            run.post(()->{
                Pass pass=new Pass(opening,binding,reads,new PurpleSyncChatDiscovery(origin,input,binding.bound,binding.chat,fallback,stream));
                pass.model.start(env.now());pump(run,pass,0,done);
            });
        });
    }
    private void pump(Run run,Pass pass,int steps,Consumer<Pass> done){
        PurpleSyncChatDiscovery.Command command=pass.model.command();
        if(command.step==PurpleSyncChatDiscovery.Step.Idle){done.accept(pass);return;}
        if(steps>=MAX_DISCOVERY_STEPS){done.accept(null);return;}
        Consumer<PurpleSyncChatDiscovery.Reply> next=reply->run.post(()->{
            if(pass.model.command()!=command)return;
            if(pass.model.complete(reply,env.now()))pump(run,pass,steps+1,done);else done.accept(null);
        });
        PurpleSyncChatClient reads=pass.reads;PurpleSyncChatDiscovery.Reply reply=new PurpleSyncChatDiscovery.Reply(command);
        switch(command.step){
            case Search:reads.dispatch(()->reads.search(command.search,command.cursor,command.chat,page->{reply.success=page!=null;reply.page=page;next.accept(reply);}));break;
            case PeerInfo:reads.dispatch(()->reads.info(command.chat,info->{reply.success=info!=null;reply.info=info;next.accept(reply);}));break;
            case ExactMark:reads.dispatch(()->reads.exact(command.mark,exact->{reply.success=exact!=null;reply.exact=exact;next.accept(reply);}));break;
            case SettingsHint:reads.dispatch(()->reads.hint(hint->{reply.success=hint!=null;reply.hint=hint!=null?hint:SettingsHint.Unknown;next.accept(reply);}));break;
            case Inventory:info(run,reads,command.chat,info->scan(run,info,inventory->{
                RecordPresence presence=inventory==null?RecordPresence.Unknown:PurpleSyncChat.recordPresence(inventory,origin.userId,command.chat);
                reply.success=presence!=RecordPresence.Unknown;reply.records=presence;next.accept(reply);
            }));break;
            default:done.accept(null);
        }
    }

    /** A complete owned inventory of one chat, or null; null info (an unreadable or gone chat) skips the scan. */
    private void scan(Run run,PurpleSyncChatClient.Info info,Consumer<PurpleSyncInventory> done){
        if(info==null||!info.readable()||!origin.equals(info.origin)){done.accept(null);return;}
        PurpleSyncClient client=env.scanner(info,run::live);
        PurpleSyncTelegramTransport transport=new PurpleSyncTelegramTransport(client);run.own(transport::cancel);
        transport.check(null,result->run.post(()->done.accept(result!=null&&result.status==PurpleSyncTransport.CheckStatus.Finished&&result.inventory!=null
                &&result.inventory.scanComplete&&result.inventory.sameOrigin(client)&&info.chat.equals(result.inventory.chat)?result.inventory:null)));
    }
    private void exact(Run run,Peer chat,int id,Consumer<PurpleSyncChatSetup.ReadBack> done){
        if(chat==null||id<=0){done.accept(null);return;}
        PurpleSyncChatTelegram reader=new PurpleSyncChatTelegram(origin,env.platform(run::live));run.own(()->reader.dispatch(reader::cancel));
        reader.dispatch(()->reader.readBack(new Cache(chat,id,0),value->run.post(()->done.accept(value))));
    }
    /** Exact evidence for one cleanup item: the scanned record receipt and its own response, or an exact read. */
    private void cleanupRead(Run run,Facts facts,PurpleSyncChatMove.Reply reply,PurpleSyncChatMoveCleanup.Item item,Runnable done){
        PurpleSyncChatClient.Info chat=item!=null&&item.chat.equals(facts.fresh.source.chat)?facts.fresh.source:null;
        reply.markRoute=route(chat);
        if(reply.markRoute==null){done.run();return;}
        Consumer<PurpleSyncInventory> after=old->{
            receipts(old,reply.sourceRecords);PurpleSyncChatMoveIO.RecordReceipt found=null;
            for(PurpleSyncChatMoveIO.RecordReceipt receipt:reply.sourceRecords)if(receipt.id==item.id)found=receipt;
            if(found!=null){reply.response=PurpleSyncChatMoveRequests.messages(found.response());done.run();return;}
            exact(run,item.chat,item.id,read->{reply.response=read!=null&&read.error==null?read.response:null;done.run();});
        };
        if(item.kind.equals("record"))scan(run,chat,after);else after.accept(null);
    }

    /** Fresh facts, then the executor's own renewal; a forward also needs a freshly sealed receipt of its source record. */
    private void renewal(Run run,PurpleSyncChatMoveIO.Permit previous,BiConsumer<PurpleSyncChatMoveIO.Permit,Facts> done){
        if(previous==null){done.accept(null,null);return;}
        run.post(()->fresh(run,facts->{
            if(facts==null){done.accept(null,null);return;}
            Consumer<PurpleSyncChatMoveIO.RecordReceipt> renew=source->run.command.owner().renew(run.command,facts.fresh,source,next->done.accept(next,facts));
            if(!previous.kind.equals("forward")){renew.accept(null);return;}
            scan(run,facts.fresh.source,old->{
                PurpleSyncChatMoveIO.RecordReceipt found=null;List<PurpleSyncChatMoveIO.RecordReceipt> all=new ArrayList<>();receipts(old,all);
                for(PurpleSyncChatMoveIO.RecordReceipt receipt:all)if(receipt.id==previous.sourceId&&receipt.hash.equals(previous.hash))found=receipt;
                if(found==null){done.accept(null,null);return;}
                renew.accept(found);
            });
        }));
    }
    /** The write side of one Effect command. A reply is non-null only when a write response arrived. */
    private final class Write implements PurpleSyncChatMoveIO.WritePort {
        final Run run;
        final Peer chat;
        boolean answered;
        TLObject response;
        Write(Run run,Peer chat){this.run=run;this.chat=chat;}
        public long now(){return env.now();}
        public long wall(){return env.wall();}
        public PurpleRpcRetry.Transient cooldown(){return env.cooldown();}
        public void renew(PurpleSyncChatMoveIO.Permit previous,Consumer<PurpleSyncChatMoveIO.Permit> done){renewal(run,previous,(next,facts)->done.accept(next));}
        public int send(PurpleSyncChatMoveIO.Permit permit,TLObject request,Consumer<PurpleSyncChatMoveIO.WriteResponse> done){
            if(!run.live()||permit==null||!permit.current()||request==null){done.accept(new PurpleSyncChatMoveIO.WriteResponse(null,"StalePermit",0));return 0;}
            int token=env.send(request,(object,error)->run.post(()->{
                answered=true;response=error==null?object:null;if(error!=null)env.failed(error);
                done.accept(new PurpleSyncChatMoveIO.WriteResponse(object,error!=null?error.text:null,error!=null?error.code:0));
            }));
            run.own(()->env.cancel(token));return token;
        }
        public void updates(PurpleSyncChatOrigin issued,TLRPC.Updates updates){if(origin.equals(issued))env.apply(chat,updates);}
        public void cancel(int token){env.cancel(token);}
        public void delay(Runnable task,long millis){env.delay(task,millis);run.own(()->env.cancelDelay(task));}
        public void cancelDelay(Runnable task){env.cancelDelay(task);}
        public void waiting(PurpleRpcRetry.Transient value){env.waiting(run,value);}
        PurpleSyncChatMove.Reply reply(){if(!answered)return null;PurpleSyncChatMove.Reply reply=new PurpleSyncChatMove.Reply();reply.response=response;return reply;}
    }
    private void effect(Run run,Consumer<PurpleSyncChatMove.Reply> done){
        PurpleSyncChatMoveIO.Permit permit=run.command.permit;
        if(permit==null||!permit.current()){done.accept(null);return;}
        Write write=new Write(run,permit.target);
        if(permit.kind.equals("forward")||permit.kind.equals("mark")){
            PurpleSyncChatMoveIO.Effect effect=new PurpleSyncChatMoveIO.Effect(permit,null,write,result->run.post(()->done.accept(write.reply())));
            run.own(()->env.worker(effect::close));effect.start();return;
        }
        PurpleSyncAutoUpload upload=new PurpleSyncAutoUpload(permit,run::live,new Upload(run,write),result->run.post(()->done.accept(write.reply())));
        run.own(upload::cancel);upload.start();
    }
    /**
     * Own and pending records: upload the sealed body, renew through the executor, then send once with that permit.
     * A flood retry only rechecks that the permit is still current; a second authorization would retire the upload's permit.
     */
    private final class Upload implements PurpleSyncAutoUpload.Port {
        final Run run;
        final Write write;
        Facts facts;
        Upload(Run run,Write write){this.run=run;this.write=write;}
        public void worker(Runnable task){env.worker(task);}
        public int upload(long id,byte[] bytes,PurpleSyncClient.Reply<Boolean> reply){return uploadResult(id,bytes,result->reply.onReply(result!=null&&result.id>0));}
        public int uploadResult(long id,byte[] bytes,PurpleSyncClient.Reply<PurpleSyncAutoUpload.Result> reply){
            if(!run.live()){reply.onReply(new PurpleSyncAutoUpload.Result(0,"AccountUnavailable"));return 0;}
            int token=env.upload(id,bytes,reply::onReply);run.own(()->env.cancel(token));return token;
        }
        public void exact(int id,PurpleSyncClient.Reply<byte[]> reply){reply.onReply(null);}
        public int send(int id,TLRPC.TL_inputFile file,PurpleSyncClient.Reply<PurpleSyncAutoUpload.Result> reply){reply.onReply(new PurpleSyncAutoUpload.Result(0,"CarryOnly"));return 0;}
        public void cancel(int token){env.cancel(token);}
        public void beforePost(PurpleSyncClient.Reply<Boolean> done){done.onReply(false);}
        public void renewCarry(PurpleSyncChatMoveIO.Permit previous,PurpleSyncClient.Reply<PurpleSyncChatMoveIO.Permit> done){
            renewal(run,previous,(next,fresh)->{facts=next!=null?fresh:null;done.onReply(next);});
        }
        public int sendCarry(PurpleSyncChatMoveIO.Permit permit,TLRPC.TL_inputFile file,PurpleSyncClient.Reply<PurpleSyncAutoUpload.Result> done){
            Facts renewed=facts;TLObject request=PurpleSyncChatMoveRequests.effect(permit,file);
            PurpleSyncChatIO route=renewed==null||permit==null?null:route(permit.kind.equals("pending")?renewed.fresh.source:renewed.fresh.target);
            if(request==null||route==null||!route.chat.equals(permit.target)){done.onReply(new PurpleSyncAutoUpload.Result(0,"StalePermit"));return 0;}
            PurpleSyncChatPostEffect effect=new PurpleSyncChatPostEffect(new PurpleSyncChatPostEffect.Port(){
                public long now(){return env.now();}
                public long wall(){return env.wall();}
                public PurpleRpcRetry.Transient cooldown(){return env.cooldown();}
                public void renew(Consumer<Boolean> ready){ready.accept(run.live()&&permit.current());}
                public int send(Consumer<PurpleSyncAutoUpload.Result> callback){
                    return write.send(permit,request,response->{
                        boolean applied=true;
                        try{if(response.error==null&&response.response instanceof TLRPC.Updates)env.apply(permit.target,response.response);}catch(RuntimeException error){applied=false;}
                        int id=response.error==null&&applied?PurpleSyncChatUpload.receipt(route,response.response,permit.randomId,0):0;
                        callback.accept(new PurpleSyncAutoUpload.Result(id,id>0?null:response.error!=null?response.error:"OutcomeUnknown",response.code));
                    });
                }
                public void cancel(int token){env.cancel(token);}
                public void delay(Runnable task,long millis){write.delay(task,millis);}
                public void cancelDelay(Runnable task){env.cancelDelay(task);}
                public void waiting(PurpleRpcRetry.Transient value){env.waiting(run,value);}
            },()->run.live()&&permit.current(),done::onReply);
            run.own(()->env.worker(effect::cancel));effect.start(true);return 0;
        }
    }
    /** One cleanup deletion through the existing executor; its renewal re-reads the item and re-checks the deletion. */
    private void cleanupDelete(Run run,Consumer<PurpleSyncChatMove.Reply> done){
        PurpleSyncChatMove.Command command=run.command;PurpleSyncChatMoveCleanup.Permit permit=command.cleanupPermit;
        if(permit==null||!permit.current()){done.accept(null);return;}
        PurpleSyncChatMoveCleanup.WritePort port=new PurpleSyncChatMoveCleanup.WritePort(){
            public boolean current(){return run.live();}
            public long now(){return env.now();}
            public void dispatch(Runnable task){env.worker(task);}
            public void delay(Runnable task,long millis){env.delay(task,millis);run.own(()->env.cancelDelay(task));}
            public void cancelDelay(Runnable task){env.cancelDelay(task);}
            public int send(TLObject request,PurpleRpcRequest.Reply reply){
                int token=env.send(request,(object,error)->run.post(()->{if(error!=null)env.failed(error);reply.accept(object,error);}));
                run.own(()->env.cancel(token));return token;
            }
            public void cancel(int token){env.cancel(token);}
            public void waiting(PurpleRpcRetry.Transient value){env.waiting(run,value);}
            public void renew(PurpleSyncChatMoveCleanup.Permit previous,Consumer<PurpleSyncChatMoveCleanup.Permit> next){
                command.owner().beginCleanupRead(command,read->run.post(()->fresh(run,facts->{
                    if(facts==null){next.accept(null);return;}
                    PurpleSyncChatMove.Reply reply=reply(facts);
                    scan(run,facts.fresh.target,destination->{
                        reply.destination=destination;
                        cleanupRead(run,facts,reply,command.cleanupItem,()->command.owner().renewCleanup(command,read,reply,next));
                    });
                })));
            }
            public void apply(PurpleSyncChatOrigin issued,TLObject response){if(origin.equals(issued))env.apply(command.cleanupItem.chat,response);}
        };
        PurpleSyncChatMoveCleanup.Effect effect=new PurpleSyncChatMoveCleanup.Effect(permit,port,(response,error)->run.post(()->done.accept(error==null&&response!=null?new PurpleSyncChatMove.Reply():null)));
        run.own(()->env.worker(effect::close));effect.start();
    }

    /** Production Env: the account holder, the shared request queues and the app's update handling. */
    private static final class Production implements Env {
        final PurpleSyncChat.Holder holder;
        final PurpleSyncChatOrigin origin;
        private final AtomicInteger sequence=new AtomicInteger();
        private final ConcurrentHashMap<Integer,Wire> wires=new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Runnable,Runnable> delays=new ConcurrentHashMap<>();
        private final class Wire {
            volatile int token;
            volatile boolean cancelled;
            void attach(int value){token=value;if(cancelled&&value>0)ConnectionsManager.getInstance(origin.slot).cancelRequest(value,true);}
            void cancel(){cancelled=true;AndroidUtilities.runOnUIThread(()->{if(token>0)ConnectionsManager.getInstance(origin.slot).cancelRequest(token,true);});}
        }
        Production(PurpleSyncChat.Holder holder){this.holder=holder;origin=holder.env.origin;}
        public PurpleSyncChatOrigin origin(){return origin;}
        public String authority(){return holder.env.authority;}
        public String operationKey(){return holder.env.operationKey();}
        public boolean current(){return holder.env.current();}
        public void worker(Runnable task){PurpleSyncChat.queue().postRunnable(task);}
        public void quiesce(Runnable done){PurpleSyncChat.quiesceCarry(origin,done);}
        public PurpleSyncChatMoveStore store()throws Exception{return new PurpleSyncChatMoveStore(holder.env.context,PurpleDevice.id());}
        public PurpleSyncChatCache.Loaded cache(){return PurpleSyncChatCache.load(holder.env.cache(),origin,holder.env.authority);}
        public PurpleSyncChatService.Binding binding(){return holder.env.binding();}
        public PurpleSyncChatClient reads(BooleanSupplier current){return new PurpleSyncChatTelegram(origin,platform(current));}
        public PurpleSyncChatTelegram.Platform platform(BooleanSupplier current){return PurpleSyncChatTelegram.platform(origin,()->current.getAsBoolean()&&holder.env.current());}
        public PurpleSyncClient scanner(PurpleSyncChatClient.Info chat,BooleanSupplier current){return PurpleSyncTelegramClient.create(chat,()->current.getAsBoolean()&&holder.env.current());}
        public int send(TLObject request,PurpleSyncChatTelegram.Reply done){
            int id=sequence.incrementAndGet();Wire wire=new Wire();wires.put(id,wire);
            AndroidUtilities.runOnUIThread(()->{
                if(wire.cancelled||!holder.env.current()){wires.remove(id);worker(()->done.accept(null,null));return;}
                try{
                    int token=ConnectionsManager.getInstance(origin.slot).sendRequest(request,(response,error)->{
                        byte[] raw=null;try{if(response!=null)raw=PurpleSyncChatSetupReceipts.bytes(response);}catch(Exception|LinkageError ignored){}
                        TLRPC.TL_error failure=null;if(error!=null){failure=new TLRPC.TL_error();failure.code=error.code;failure.text=error.text;}
                        byte[] captured=raw;TLRPC.TL_error copied=failure;wires.remove(id);
                        worker(()->{
                            TLObject owned=null;
                            if(captured!=null){SerializedData data=new SerializedData(captured);try{owned=request.deserializeResponse(data,data.readInt32(true),true);if(data.remaining()!=0)owned=null;}catch(RuntimeException ignored){owned=null;}finally{data.cleanup();}}
                            done.accept(owned,copied);
                        });
                    },ConnectionsManager.RequestFlagFailOnServerErrors);
                    wire.attach(token);
                }catch(RuntimeException|LinkageError error){wires.remove(id);worker(()->done.accept(null,null));}
            });
            return id;
        }
        public int upload(long file,byte[] bytes,Consumer<PurpleSyncAutoUpload.Result> done){
            int id=sequence.incrementAndGet();Wire wire=new Wire();wires.put(id,wire);
            AndroidUtilities.runOnUIThread(()->{
                if(wire.cancelled||!holder.env.current()){wires.remove(id);worker(()->done.accept(new PurpleSyncAutoUpload.Result(0,"AccountUnavailable")));return;}
                try{
                    TLRPC.TL_upload_saveFilePart request=new TLRPC.TL_upload_saveFilePart();
                    request.file_id=file;request.file_part=0;request.bytes=new NativeByteBuffer(bytes.length);request.bytes.writeBytes(bytes);
                    int token=ConnectionsManager.getInstance(origin.slot).sendRequest(request,(response,error)->{
                        wires.remove(id);if(error!=null)failed(error);
                        boolean saved=error==null&&response instanceof TLRPC.TL_boolTrue;
                        PurpleSyncAutoUpload.Result result=new PurpleSyncAutoUpload.Result(saved?1:0,saved?null:error!=null?error.text:"OutcomeUnknown",error!=null?error.code:0);
                        worker(()->done.accept(result));
                    },ConnectionsManager.RequestFlagFailOnServerErrors,ConnectionsManager.ConnectionTypeUpload);
                    wire.attach(token);
                }catch(Exception|LinkageError error){wires.remove(id);worker(()->done.accept(new PurpleSyncAutoUpload.Result(0,"Transport",-1)));}
            });
            return id;
        }
        public void cancel(int token){Wire wire=wires.remove(token);if(wire!=null)wire.cancel();}
        public void apply(Peer chat,TLObject response){
            AndroidUtilities.runOnUIThread(()->{try{PurpleSyncChatMarkUpdates.apply(origin.slot,chat,response,holder.env::current);}catch(RuntimeException ignored){}});
        }
        public long now(){return android.os.SystemClock.elapsedRealtime();}
        public long wall(){return System.currentTimeMillis();}
        public PurpleRpcRetry.Transient cooldown(){return PurpleRpcRequests.writeWaiting(origin.slot,"sync-write");}
        public void failed(TLRPC.TL_error error){PurpleRpcRequests.writeFailure(origin.slot,"sync-write",error);}
        public void waiting(Object owner,PurpleRpcRetry.Transient value){PurpleRpcRequests.waiting(origin.slot,owner,value);}
        public void delay(Runnable task,long millis){
            Runnable timer=()->{if(delays.remove(task)!=null)worker(task);};delays.put(task,timer);AndroidUtilities.runOnUIThread(timer,millis);
        }
        public void cancelDelay(Runnable task){Runnable timer=delays.remove(task);if(timer!=null)AndroidUtilities.cancelRunOnUIThread(timer);}
    }
}
