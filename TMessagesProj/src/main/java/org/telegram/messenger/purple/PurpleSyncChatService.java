package org.telegram.messenger.purple;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatService {
    static final class Binding {
        final boolean ok, bound;
        final Peer chat;
        final String identity,stateStamp;
        Binding(boolean ok, boolean bound, Peer chat) { this(ok,bound,chat,""); }
        Binding(boolean ok, boolean bound, Peer chat,String identity) { this(ok,bound,chat,identity,identity); }
        Binding(boolean ok,boolean bound,Peer chat,String identity,String stamp) { this.ok=ok;this.bound=bound;this.chat=chat;this.identity=identity;stateStamp=stamp; }
        boolean same(Binding other) { return other!=null&&ok&&other.ok&&bound==other.bound&&java.util.Objects.equals(chat,other.chat)&&identity.equals(other.identity); }
    }
    static final class Borrowed {
        final String key;
        final Object owner;
        final BooleanSupplier current;
        final Consumer<PurpleRpcRetry.Transient> waiting;
        final BooleanSupplier setupReleased;
        Borrowed(String key, Object owner, BooleanSupplier current) { this(key,owner,current,ignored->{}); }
        Borrowed(String key,Object owner,BooleanSupplier current,Consumer<PurpleRpcRetry.Transient> waiting) {
            this(key,owner,current,waiting,()->false);
        }
        Borrowed(String key,Object owner,BooleanSupplier current,Consumer<PurpleRpcRetry.Transient> waiting,BooleanSupplier setupReleased) {
            this.key=key;this.owner=owner;this.current=current;this.waiting=waiting;this.setupReleased=setupReleased;
        }
    }
    interface Env {
        PurpleSyncChatOrigin origin();
        String authority();
        String operationKey();
        boolean current();
        long now();
        default long rpcNow(){return now();}
        default void presentation(PurpleRpcRetry.Transient value){}
        default boolean setupPostingHeld(){return false;}
        void ui(Runnable task);
        void worker(Runnable task);
        void delay(Runnable task,long millis);
        void cancelDelay(Runnable task);
        PurpleSyncChatCache.IO cache();
        Binding binding();
        PurpleSyncChatClient client();
        void inventory(Peer peer,BooleanSupplier current,Consumer<RecordPresence> done);
        void cancelInventory();
        default void transientWait(Consumer<PurpleRpcRetry.Transient> observer){}
    }
    private static final class Request {
        PurpleSyncOperations.Capability acquisition;
        final boolean beforePost, force;
        final String stream;
        final Borrowed borrowed;
        final Consumer<PurpleSyncChatDiscovery.Status> done;
        int retries;
        int restarts;
        boolean fullRequired;
        Cache retryAnchor;
        Request(boolean beforePost,boolean force,String stream,Borrowed borrowed,Consumer<PurpleSyncChatDiscovery.Status> done) {
            this.beforePost=beforePost;this.force=force;this.stream=stream;this.borrowed=borrowed;this.done=done;
        }
    }
    private final Env env;
    private final ArrayDeque<Request> waiting = new ArrayDeque<>();
    private PurpleSyncChatDiscovery model;
    private volatile PurpleSyncChatDiscovery.Status status;
    private PurpleSyncChatClient client;
    private PurpleSyncChatCache.Loaded opening;
    private volatile Request active;
    private Binding binding;
    private String lastStream="";
    private volatile boolean closed, owned;
    private boolean cleaning, pendingClose, pendingLogout, logoutDrained;
    private boolean started;
    private volatile long generation;
    private long lastResolved;
    private DiscoveryReceipt receipt;
    private static final class DiscoveryReceipt {
        final PurpleSyncChatOrigin origin;
        final Binding binding;
        final Cache location;
        final String stream;
        final long completedAt;
        DiscoveryReceipt(PurpleSyncChatOrigin origin,Binding binding,Cache location,String stream,long completedAt) {
            this.origin=origin;this.binding=binding;this.location=location;this.stream=stream;this.completedAt=completedAt;
        }
        boolean matches(PurpleSyncChatOrigin origin,Binding binding,PurpleSyncChatCache.Loaded loaded,String stream,long now) {
            return this.origin.equals(origin)&&this.binding.same(binding)&&this.stream.equals(stream)&&now>=completedAt&&now-completedAt<15*60*1000L
                    &&loaded!=null&&loaded.ok&&loaded.data!=null&&!loaded.data.pendingMove()&&location.equals(loaded.data.location);
        }
    }
    private Runnable retry,watchdog;
    private long dispatchedTicket, dispatchedRun;

    PurpleSyncChatService(Env env) {
        this.env=env;
        env.transientWait(value->env.ui(()->transientWait(value)));
        ResolveInput input=new ResolveInput(env.origin().userId);
        status=new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Paused,resolve(input),"NotStarted",false,true,0);
    }
    PurpleSyncChatDiscovery.Status status() { return status; }
    PurpleSyncChatScreen.Frame screen() {
        return new PurpleSyncChatScreen.Frame(env.origin(),new PurpleSyncChat.Status(status),binding,opening,
                model!=null?model.readInfo():null,model!=null?model.refused():Collections.emptyList());
    }
    void quiesceCarry() {
        ArrayDeque<Request> pending=new ArrayDeque<>(waiting);waiting.clear();long run=generation;
        try{if(active!=null)abandon(run);}finally{
            for(Request request:pending)try{request.done.accept(unavailable("CarryPending"));}catch(RuntimeException ignored){}
        }
    }
    void startSession() {
        if(started||closed)return;
        started=true;ensureResolved(false,"config",null,ignored->{});
    }
    void ensureResolved(boolean force,String stream,Borrowed borrowed,Consumer<PurpleSyncChatDiscovery.Status> done) { enqueue(new Request(false,force,stream,borrowed,done)); }
    void checkBeforePost(String stream,Borrowed borrowed,Consumer<PurpleSyncChatDiscovery.Status> done) { enqueue(new Request(true,true,stream,borrowed,done)); }
    private void enqueue(Request request) {
        if(closed||!env.current()){request.done.accept(unavailable("AccountUnavailable"));return;}
        if(!request.stream.equals("config")&&!request.stream.equals("library")){request.done.accept(unavailable("InvalidStream"));return;}
        retireStale();
        if(request.borrowed!=null)waiting.addFirst(request);else waiting.addLast(request);kick();
    }
    private boolean borrowedCurrent(Borrowed borrowed) {
        return borrowed!=null&&env.operationKey().equals(borrowed.key)&&borrowed.current.getAsBoolean()&&PurpleSyncOperations.owned(borrowed.key,borrowed.owner);
    }
    private boolean live(long run) { return !closed&&run==generation&&active!=null&&env.current()&&(owned?active.acquisition!=null&&active.acquisition.current():borrowedCurrent(active.borrowed)); }
    private void retireStale(){if(active!=null&&!live(generation))abandon(generation);}
    private void kick() {
        retireStale();
        if(closed||cleaning||active!=null||waiting.isEmpty())return;
        Request next=waiting.peekFirst();
        if(!env.current()){waiting.removeFirst().done.accept(unavailable("AccountUnavailable"));kick();return;}
        if(next.borrowed!=null&&!borrowedCurrent(next.borrowed)){waiting.removeFirst().done.accept(unavailable("OperationChanged"));kick();return;}

        long opening=generation;
        PurpleSyncOperations.Capability token=next.borrowed==null?PurpleSyncOperations.acquire(env.operationKey(),this,env::current,()->env.ui(this::quiesceCarry)):null;
        boolean installed=false,restart=false;
        try {
            if(closed||cleaning||active!=null||generation!=opening||waiting.peekFirst()!=next||!env.current()){restart=true;return;}
            if(next.borrowed==null&&token==null){PurpleSyncOperations.whenIdle(env.operationKey(),this,()->env.ui(this::kick));return;}
            next.acquisition=token;owned=next.borrowed==null;active=waiting.removeFirst();++generation;installed=true;
            final long run=generation;
            try {cancelRetry();load(run);}catch(RuntimeException|LinkageError error){failed(run,"RequestUnavailable");}
        } finally {
            if(token!=null&&!installed){token.revoke();PurpleSyncOperations.end(token.key,this,token);}
            if(restart&&!closed&&!cleaning&&active==null&&!waiting.isEmpty())kick();
        }
    }
    private void load(long run) {
        present(null);
        try {
        armWatchdog(run);
        env.worker(()->{
            try {
            if(!live(run)){env.ui(()->abandon(run));return;}
            PurpleSyncChatCache.Loaded loaded=PurpleSyncChatCache.load(env.cache(),env.origin(),env.authority());
            Binding binding=env.binding();
            env.ui(()->guarded(run,()->{
                if(!live(run)){abandon(run);return;}
                PurpleSyncChatCache.Loaded previous=opening;Binding previousBinding=this.binding;
                opening=loaded;this.binding=binding;
                if(!loaded.ok){finish(unavailable("CacheUnavailable"),run);return;}
                if(binding==null||!binding.ok){finish(unavailable("BindingUnavailable"),run);return;}
                if(!active.beforePost&&!active.force&&lastResolved>0&&env.now()-lastResolved>=0&&env.now()-lastResolved<15*60*1000L
                    &&lastStream.equals(active.stream)&&status.complete&&status.phase==PurpleSyncChatDiscovery.Phase.Use
                    &&previous!=null&&previous.data!=null&&loaded.data!=null&&java.util.Arrays.equals(previous.bytes,loaded.bytes)
                    &&previousBinding!=null&&previousBinding.same(binding)){
                    finish(status,run,false);return;
                }
                client=env.client();
                if(client!=null)client.transientWait(value->env.ui(()->{if(live(run))transientWait(value);}));
                if(client==null||!client.current()||!env.origin().equals(client.origin())){finish(unavailable("AccountUnavailable"),run);return;}
                model=new PurpleSyncChatDiscovery(env.origin(),loaded.data,binding.bound,binding.chat,client.fallback(),active.stream);
                dispatchedRun=dispatchedTicket=0;
                Cache anchor=loaded.data!=null?loaded.data.location:binding.bound?new Cache(binding.chat,0,0):null;
                if(active.retries>0&&java.util.Objects.equals(anchor,active.retryAnchor))model.startRetry(env.now(),active.retries);
                else if(active.beforePost&&!active.fullRequired&&recentDiscovery())model.startBeforePost(env.now());
                else {receipt=null;model.start(env.now());}
                pump(run);
            }));
            } catch(RuntimeException|LinkageError error) { env.ui(()->failed(run,"LoadUnavailable")); }
        });
        } catch(RuntimeException|LinkageError error) { failed(run,"LoadUnavailable"); }
    }
    private void failed(long run,String reason) { if(live(run))finish(unavailable(reason),run);else abandon(run); }
    private boolean recentDiscovery() { return receipt!=null&&receipt.matches(env.origin(),binding,opening,active.stream,env.now()); }
    private void restartFull(long run) {
        if(!live(run)){abandon(run);return;}
        if(++active.restarts>3){finish(unavailable("BindingChanged"),run);return;}
        active.fullRequired=true;cancelWatchdog();
        if(client!=null){client.cancel();client=null;}env.cancelInventory();if(model!=null)model.cancel();
        load(++generation);
    }
    private void guarded(long run,Runnable task) { try { task.run(); } catch(RuntimeException|LinkageError error) { failed(run,"RequestUnavailable"); } }
    private void pump(long run) {
        if(!live(run)){abandon(run);return;}
        PurpleSyncChatDiscovery.Command command=model.command();
        if(command.step==PurpleSyncChatDiscovery.Step.Idle){
            if(active.beforePost&&model.directOnly()&&!recentDiscovery()){restartFull(run);return;}
            saveAndFinish(run);return;
        }
        if(command.run==dispatchedRun&&command.ticket==dispatchedTicket)return;
        dispatchedRun=command.run;dispatchedTicket=command.ticket;
        Consumer<PurpleSyncChatDiscovery.Reply> complete=reply->env.ui(()->{
            if(live(run)&&model.complete(reply,env.now()))pump(run);
            else if(!live(run))abandon(run);
        });
        try {
            switch(command.step){
                case Search:client.search(command.search,command.cursor,command.chat,page->{PurpleSyncChatDiscovery.Reply reply=new PurpleSyncChatDiscovery.Reply(command);reply.success=page!=null;reply.page=page;complete.accept(reply);});break;
                case PeerInfo:client.info(command.chat,info->{PurpleSyncChatDiscovery.Reply reply=new PurpleSyncChatDiscovery.Reply(command);reply.success=info!=null;reply.info=info;complete.accept(reply);});break;
                case ExactMark:client.exact(command.mark,exact->{PurpleSyncChatDiscovery.Reply reply=new PurpleSyncChatDiscovery.Reply(command);reply.success=exact!=null;reply.exact=exact;complete.accept(reply);});break;
                case SettingsHint:client.hint(hint->{PurpleSyncChatDiscovery.Reply reply=new PurpleSyncChatDiscovery.Reply(command);reply.success=hint!=null;reply.hint=hint!=null?hint:SettingsHint.Unknown;complete.accept(reply);});break;
                case Inventory:env.inventory(command.chat,()->live(run),presence->{PurpleSyncChatDiscovery.Reply reply=new PurpleSyncChatDiscovery.Reply(command);reply.success=presence!=null&&presence!=RecordPresence.Unknown;reply.records=presence!=null?presence:RecordPresence.Unknown;complete.accept(reply);});break;
                default:finish(unavailable("InvalidCommand"),run);
            }
        }catch(RuntimeException|LinkageError error){failed(run,"RequestUnavailable");}
    }
    private void saveAndFinish(long run) {
        PurpleSyncChatDiscovery.Status next=model.status();
        PurpleSyncChatCache.Data proposed=model.cache();
        if(next.complete&&next.resolution.action==Action.Use&&proposed!=null&&!proposed.pendingMove()) {
            try {
                env.worker(()->{
                    try {
                        Binding freshBinding=active!=null&&active.beforePost?env.binding():binding;
                        if(live(run)&&active.beforePost&&!binding.same(freshBinding)){env.ui(()->restartFull(run));return;}
                        if(live(run)&&active.beforePost&&model.directOnly()&&!recentDiscovery()){env.ui(()->restartFull(run));return;}
                        boolean saved=live(run)&&PurpleSyncChatCache.save(env.cache(),opening,env.origin(),env.authority(),proposed,()->live(run));
                        byte[] written=saved?PurpleSyncChatCache.encode(env.origin(),env.authority(),proposed):null;
                        env.ui(()->{if(!live(run)){abandon(run);return;}if(saved)opening=new PurpleSyncChatCache.Loaded(true,written,proposed);
                            if(active.beforePost&&model.directOnly()&&!recentDiscovery()){restartFull(run);return;}
                            finish(saved?next:unavailable("CacheWriteUnavailable"),run);});
                    } catch(RuntimeException|LinkageError error) { env.ui(()->failed(run,"CacheWriteUnavailable")); }
                });
            } catch(RuntimeException|LinkageError error) { failed(run,"CacheWriteUnavailable"); }
        }else finish(next,run);
    }
    private void finish(PurpleSyncChatDiscovery.Status next,long run) { finish(next,run,true); }
    private void finish(PurpleSyncChatDiscovery.Status next,long run,boolean remember) {
        if(!live(run)){abandon(run);return;}
        Request finished=active;PurpleSyncOperations.Capability token=owned?finished.acquisition:null;
        if(token!=null)token.revoke();
        owned=false;active=null;cleaning=true;boolean cleaned=false;
        try {
            if(env.setupPostingHeld()&&next.phase==PurpleSyncChatDiscovery.Phase.Use
                    &&(finished.borrowed==null||!finished.borrowed.setupReleased.getAsBoolean()))
                next=new PurpleSyncChatDiscovery.Status(next.phase,next.resolution,"SetupPostingHeld",next.complete,true,next.retryAt,next.newReaders);
            status=next;
            if(next.complete&&remember&&model!=null&&!model.directOnly()) {
                lastResolved=env.now();lastStream=finished.stream;
                if(next.phase==PurpleSyncChatDiscovery.Phase.Use&&opening!=null&&opening.data!=null)
                    receipt=new DiscoveryReceipt(env.origin(),binding,opening.data.location,finished.stream,env.now());
            }
            PurpleSyncChatClient closing=client;client=null;if(closing!=null)closing.cancel();env.cancelInventory();
            int completedRetries=model!=null?model.retriesCompleted():0;
            cancelWatchdog();present(null);
            if(next.phase==PurpleSyncChatDiscovery.Phase.Searching&&next.retryAt>0){
                int retries=next.reason.equals("TelegramWait")?0:completedRetries+1;Cache anchor=next.resolution.cache;
                retry=()->{
                    retry=null;if(closed||!env.current())return;
                    Request request=new Request(false,true,finished.stream,null,ignored->{});request.retries=retries;request.retryAnchor=anchor;
                    waiting.addLast(request);kick();
                };
                env.delay(retry,Math.max(1,next.retryAt-env.now()));
            }
            cleaned=true;
        } finally {
            try {if(token!=null)PurpleSyncOperations.end(token.key,this,token);}
            finally {cleaning=false;try {drainClose();}finally {if(!cleaned)kick();}}
        }
        try {if(!closed&&env.current())finished.done.accept(next);}finally {kick();}
    }
    private void abandon(long run) {
        if(run!=generation||active==null)return;
        Request abandoned=active;PurpleSyncOperations.Capability token=owned?abandoned.acquisition:null;
        if(token!=null)token.revoke();
        owned=false;active=null;++generation;receipt=null;cleaning=true;boolean cleaned=false;
        PurpleSyncChatDiscovery.Status next;
        try {
            cancelRetry();cancelWatchdog();present(null);
            PurpleSyncChatClient closing=client;client=null;if(closing!=null)closing.cancel();env.cancelInventory();if(model!=null)model.cancel();
            next=unavailable("AccountUnavailable");status=next;cleaned=true;
        } finally {
            try {if(token!=null)PurpleSyncOperations.end(token.key,this,token);}
            finally {cleaning=false;try {drainClose();}finally {if(!cleaned)kick();}}
        }
        try {if(!closed&&env.current())abandoned.done.accept(next);}finally {kick();}
    }
    private PurpleSyncChatDiscovery.Status unavailable(String reason) {
        ResolveInput safe=new ResolveInput(env.origin().userId);
        if(opening!=null&&opening.data!=null)safe.cache=opening.data.location;
        if(binding!=null&&binding.ok){safe.settingsBound=binding.bound;safe.settingsChat=binding.chat;}
        return new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Paused,resolve(safe),reason,false,true,0,opening!=null&&opening.data!=null&&opening.data.newReaders);
    }
    private void transientWait(PurpleRpcRetry.Transient value){
        long run=generation;if(!live(run))return;
        PurpleSyncChatDiscovery.Status safe=unavailable("TelegramWait");
        long remaining=Math.max(0,value.retryAt-env.rpcNow());
        status=new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Searching,safe.resolution,"TelegramWait",false,true,PurpleRpcRetry.add(env.now(),remaining),safe.newReaders);
        cancelWatchdog();watchdog=()->{watchdog=null;if(live(run))transientTimeout(run);else abandon(run);};
        env.delay(watchdog,PurpleRpcRetry.add(remaining,120000));
        if(active!=null&&active.borrowed!=null)active.borrowed.waiting.accept(value);
    }
    private void transientTimeout(long run){
        if(!live(run))return;
        PurpleSyncChatDiscovery.Status safe=unavailable("TelegramWait");
        cancelWatchdog();cancelRetry();
        long next=++generation;receipt=null;active.fullRequired=true;
        if(client!=null){client.cancel();client=null;}env.cancelInventory();if(model!=null)model.cancel();
        if(!live(next)){abandon(next);return;}
        status=new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Searching,safe.resolution,"TelegramWait",false,true,PurpleRpcRetry.add(env.now(),5000),safe.newReaders);
        retry=()->{retry=null;if(live(next))load(next);else abandon(next);};
        env.delay(retry,5000);
        present(new PurpleRpcRetry.Transient(PurpleRpcRetry.Kind.Timeout,PurpleRpcRetry.add(env.rpcNow(),5000),false));
    }
    private void present(PurpleRpcRetry.Transient value){try{env.presentation(value);}catch(RuntimeException|LinkageError ignored){}}
    private void armWatchdog(long run){
        cancelWatchdog();watchdog=()->{watchdog=null;if(run==generation&&active!=null){if(live(run))transientTimeout(run);else abandon(run);}};
        env.delay(watchdog,120000);
    }
    private void cancelWatchdog(){Runnable closing=watchdog;watchdog=null;if(closing!=null)env.cancelDelay(closing);}
    void cancelBorrowed(Object owner){
        waiting.removeIf(request->request.borrowed!=null&&request.borrowed.owner==owner);
        if(active==null||active.borrowed==null||active.borrowed.owner!=owner)return;
        ++generation;cancelRetry();cancelWatchdog();if(client!=null){client.cancel();client=null;}env.cancelInventory();if(model!=null)model.cancel();
        active=null;present(null);status=unavailable("Cancelled");kick();
    }
    private void cancelRetry(){Runnable closing=retry;retry=null;if(closing!=null)env.cancelDelay(closing);}
    void close(boolean logout) {
        if(logout&&!logoutDrained)pendingLogout=true;
        if(!closed){closed=true;++generation;pendingClose=true;}
        waiting.clear();
        drainClose();
    }
    private void drainClose() {
        if(cleaning)return;
        if(!pendingClose){drainLogout();return;}
        pendingClose=false;
        PurpleSyncOperations.Capability token=owned&&active!=null?active.acquisition:null;
        if(token!=null)token.revoke();
        owned=false;active=null;cleaning=true;
        try {
            cancelRetry();cancelWatchdog();present(null);PurpleSyncOperations.forgetIdle(env.operationKey(),this);
            PurpleSyncChatClient closing=client;client=null;if(closing!=null)closing.cancel();env.cancelInventory();if(model!=null)model.cancel();
        } finally {
            try {if(token!=null)PurpleSyncOperations.end(token.key,this,token);}
            finally {cleaning=false;drainLogout();}
        }
    }
    private void drainLogout() {
        if(!pendingLogout||logoutDrained)return;
        pendingLogout=false;logoutDrained=true;
        try {
            env.worker(()->{PurpleSyncChatCache.Loaded loaded=PurpleSyncChatCache.load(env.cache(),env.origin(),env.authority());if(loaded.ok&&loaded.data!=null&&loaded.bytes!=null)env.cache().remove(loaded.bytes);});
        } catch(RuntimeException|LinkageError error) {
            pendingLogout=true;logoutDrained=false;throw error;
        }
    }
}
