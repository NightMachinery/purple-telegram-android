package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChat {
    public enum Phase { Unset, Searching, Use, Paused, Lost }
    public static final class Status {
        public final Phase phase;
        public final Resolution resolution;
        public final String reason;
        public final boolean complete, postingHeld, newReaders;
        public final long retryAt;
        Status(PurpleSyncChatDiscovery.Status status) {
            phase=Phase.valueOf(status.phase.name());resolution=status.resolution;reason=status.reason;
            newReaders=status.newReaders;complete=status.complete;postingHeld=status.postingHeld;retryAt=status.retryAt;
        }
        public boolean selfReadable() { return complete&&phase==Phase.Use&&resolution.action==Action.Use&&Peer.self().equals(resolution.readChat); }
        public boolean selfPostable() { return selfReadable()&&!postingHeld; }
    }
    private static final class Holder {
        final Platform env;
        final PurpleSyncChatService service;
        Holder(Platform env) { this.env=env;service=new PurpleSyncChatService(env); }
    }
    private static final java.util.concurrent.ConcurrentHashMap<Integer,Holder> accounts=new java.util.concurrent.ConcurrentHashMap<>();
    private static DispatchQueue queue;
    private static synchronized DispatchQueue queue(){if(queue==null)queue=new DispatchQueue("purpleSyncChat");return queue;}
    private static Holder account(int slot) {
        if(slot<0||slot>=UserConfig.MAX_ACCOUNT_COUNT)return null;
        Holder holder=accounts.get(slot);
        if(holder!=null&&!holder.env.current()){
            accounts.remove(slot,holder);holder.service.close(!holder.env.identityCurrent());holder=accounts.get(slot);
        }
        if(holder==null){
            try{Platform env=new Platform(slot);if(!env.current())return null;holder=new Holder(env);accounts.put(slot,holder);}
            catch(Exception|LinkageError error){return null;}
        }
        return holder;
    }
    public static void startSession(int slot) { AndroidUtilities.runOnUIThread(()->{Holder holder=account(slot);if(holder!=null)holder.service.startSession();}); }
    public static void ensureResolved(int slot,boolean force,Consumer<Status> done) {
        AndroidUtilities.runOnUIThread(()->{Holder holder=account(slot);if(holder==null){done.accept(unavailable());return;}holder.service.ensureResolved(force,"config",null,status->done.accept(new Status(status)));});
    }
    public static void checkBeforePost(int slot,String stream,Consumer<Status> done) {
        AndroidUtilities.runOnUIThread(()->{Holder holder=account(slot);if(holder==null){done.accept(unavailable());return;}holder.service.checkBeforePost(stream,null,status->done.accept(new Status(status)));});
    }
    public static Status status(int slot) { Holder holder=accounts.get(slot);return holder!=null&&holder.env.current()?new Status(holder.service.status()):unavailable(); }
    public static PurpleSyncChatScreen.Control screen(int slot) {
        Holder holder=account(slot);
        return holder!=null?new PurpleSyncChatScreen.Control(new ScreenPort(holder)):null;
    }
    private static final class ScreenPort implements PurpleSyncChatScreen.Port {
        final Holder holder;
        PurpleSyncChatClient reader;
        volatile Object owner;
        volatile boolean closed;
        ScreenPort(Holder holder) { this.holder=holder; }
        public PurpleSyncChatOrigin origin() { return holder.env.origin; }
        public boolean current() { return !closed&&holder.env.current(); }
        public boolean begin(Object owner) {
            if(!current()||this.owner!=null||!PurpleSyncOperations.begin(holder.env.operationKey(),owner,this::current))return false;
            this.owner=owner;return true;
        }
        public void end(Object owner) {
            if(this.owner!=owner)return;
            this.owner=null;PurpleSyncOperations.end(holder.env.operationKey(),owner);
        }
        public void resolve(Object owner,Consumer<PurpleSyncChatScreen.Frame> done) {
            PurpleSyncChatService.Borrowed borrowed=new PurpleSyncChatService.Borrowed(holder.env.operationKey(),owner,this::current);
            holder.service.ensureResolved(true,"config",borrowed,ignored->{if(current())done.accept(holder.service.screen());});
        }
        public void inspect(Peer chat,Consumer<PurpleSyncChatClient.Info> done) {
            if(reader!=null)reader.cancel();
            PurpleSyncChatClient captured=holder.env.client();reader=captured;
            captured.info(chat,info->{if(reader!=captured||!current())return;reader=null;captured.cancel();done.accept(info);});
        }
        public void remember(PurpleSyncChatScreen.Frame frame,PurpleSyncChatScreen.Candidate candidate,Consumer<Boolean> done) {
            Object captured=owner;
            holder.env.worker(()->{
                boolean saved=false;
                try {
                    PurpleSyncChatService.Binding binding=holder.env.binding();
                    if(current()&&captured!=null&&captured==owner&&PurpleSyncOperations.owned(holder.env.operationKey(),captured)
                            &&binding.ok&&binding.bound==frame.binding.bound&&binding.chat.equals(frame.binding.chat)
                            &&frame.canConsent(candidate.chat)&&candidate.allowed) {
                        PurpleSyncChatCache.Data old=frame.opening.data;
                        java.util.Set<String> warned=new java.util.HashSet<>(old.warned);warned.add("config");
                        PurpleSyncChatCache.Data next=new PurpleSyncChatCache.Data(old.location,candidate.title,
                                old.readers!=null?old.readers:PurpleSyncChatCache.ReadersSnapshot.from(candidate.info),warned,old.journal,old.newReaders);
                        saved=PurpleSyncChatCache.save(holder.env.cache,frame.opening,origin(),holder.env.authority,next,
                                ()->current()&&captured==owner&&PurpleSyncOperations.owned(holder.env.operationKey(),captured));
                    }
                } catch(RuntimeException|LinkageError error) { saved=false; }
                boolean result=saved;holder.env.ui(()->{if(current()&&captured==owner)done.accept(result);});
            });
        }
        public void cancel(Object owner) {
            closed=true;if(reader!=null){reader.cancel();reader=null;}holder.service.cancelBorrowed(owner);
        }
    }
    static void underLock(PurpleSyncFileContext context,Object owner,String key,boolean beforePost,BooleanSupplier current,Consumer<Status> done) {
        underLock(context,owner,key,beforePost,current,current,done);
    }
    static void underLock(PurpleSyncFileContext context,Object owner,String key,boolean beforePost,BooleanSupplier lockCurrent,
            BooleanSupplier current,Consumer<Status> done) {
        AndroidUtilities.runOnUIThread(()->{
            if(context==null||context.origin==null||!context.current()||!lockCurrent.getAsBoolean()||!current.getAsBoolean()){done.accept(unavailable());return;}
            Holder holder=account(context.origin.slot);
            if(holder==null||holder.env.context.origin.lifetime!=context.origin.lifetime||!holder.env.context.origin.key.equals(context.origin.key)){done.accept(unavailable());return;}
            PurpleSyncChatService.Borrowed borrowed=new PurpleSyncChatService.Borrowed(key,owner,lockCurrent);
            Consumer<PurpleSyncChatDiscovery.Status> reply=status->{if(current.getAsBoolean())done.accept(new Status(status));};
            if(beforePost)holder.service.checkBeforePost("config",borrowed,reply);
            else holder.service.ensureResolved(false,"config",borrowed,reply);
        });
    }
    static void cancelOperation(int slot,Object owner){AndroidUtilities.runOnUIThread(()->{Holder holder=accounts.get(slot);if(holder!=null)holder.service.cancelBorrowed(owner);});}
    public static void identityChanged(int slot) {
        Holder retired=accounts.get(slot);
        AndroidUtilities.runOnUIThread(()->{if(retired!=null){accounts.remove(slot,retired);retired.service.close(true);}});
    }
    private static Status unavailable(){return new Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Paused,resolve(new ResolveInput(1)),"AccountUnavailable",false,true,0));}

    private static final class Platform implements PurpleSyncChatService.Env {
        final PurpleSyncFileContext context;
        final PurpleSyncChatOrigin origin;
        final String authority;
        final PurpleSyncChatCache.IO cache;
        volatile PurpleSyncTelegramTransport inventory;
        volatile PurpleSyncChatClient inventoryFacts;
        Platform(int slot) throws Exception {
            context=new PurpleSyncFileContext(slot);
            if(context.origin==null||context.files==null)throw new IllegalStateException("sync chat context");
            authority=PurpleSyncChatCache.authority(PurpleArchiveLogin.context(slot));
            if(authority==null)throw new IllegalStateException("sync chat authority");
            origin=new PurpleSyncChatOrigin(slot,context.origin.key.startsWith("1-")?1:0,context.origin.userId,UUID.randomUUID().toString());
            cache=PurpleSyncChatCache.file(new File(PurpleAccountFiles.root(),"sync-chat/"+context.origin.key+".json"));
        }
        boolean identityCurrent(){return context.origin!=null&&context.origin.current();}
        public PurpleSyncChatOrigin origin(){return origin;}
        public String authority(){return authority;}
        public String operationKey(){return context.origin.key+"\n"+context.files.syncDirectory.getPath();}
        public boolean current(){
            if(!context.current())return false;
            try{return authority.equals(PurpleSyncChatCache.authority(PurpleArchiveLogin.context(origin.slot)));}
            catch(Exception|LinkageError error){return false;}
        }
        public long now(){return android.os.SystemClock.elapsedRealtime();}
        public void ui(Runnable task){AndroidUtilities.runOnUIThread(task);}
        public void worker(Runnable task){queue().postRunnable(task);}
        public void delay(Runnable task,long millis){AndroidUtilities.runOnUIThread(task,millis);}
        public void cancelDelay(Runnable task){AndroidUtilities.cancelRunOnUIThread(task);}
        public PurpleSyncChatCache.IO cache(){return cache;}
        public PurpleSyncChatService.Binding binding(){
            if(!current())return new PurpleSyncChatService.Binding(false,false,Peer.self());
            File file=new File(context.files.syncDirectory,"state.json");PurpleAccountFileSnapshot state=PurpleAccountFileSnapshot.read(file);
            if(state.status==PurpleAccountFileSnapshot.Status.Missing)return new PurpleSyncChatService.Binding(true,false,Peer.self());
            if(state.status!=PurpleAccountFileSnapshot.Status.Present)return new PurpleSyncChatService.Binding(false,false,Peer.self());
            PurpleAccountSyncCore.Result inspected=PurpleAccountSyncCore.inspectState(state.bytes);
            PurpleAccountSyncCore.Result binding=PurpleAccountSyncCore.checkAccountBinding(origin.slot,state.bytes);
            if(!"Valid".equals(inspected.status)||!"None".equals(inspected.error)||!"Valid".equals(binding.status)||!"None".equals(binding.error)
                ||!state.matches(file)||!current())return new PurpleSyncChatService.Binding(false,false,Peer.self());
            if("Unbound".equals(binding.verdict))return new PurpleSyncChatService.Binding(true,false,Peer.self());
            if(!"Bound".equals(binding.verdict))return new PurpleSyncChatService.Binding(false,false,Peer.self());
            try{JSONObject value=new JSONObject(new String(state.bytes,StandardCharsets.UTF_8));Peer chat=Peer.parse(value.optString("chat","self"),origin.userId);
                return new PurpleSyncChatService.Binding(chat!=null,true,chat!=null?chat:Peer.self());}
            catch(Exception error){return new PurpleSyncChatService.Binding(false,false,Peer.self());}
        }
        public PurpleSyncChatClient client(){return new PurpleSyncChatTelegram(origin,PurpleSyncChatTelegram.platform(origin,this::current));}
        public void inventory(Peer target,BooleanSupplier current,Consumer<RecordPresence> done){
            if(target==null || !current.getAsBoolean() || !this.current()){done.accept(RecordPresence.Unknown);return;}
            PurpleSyncChatClient reader=client();inventoryFacts=reader;
            reader.info(target,info->{
                if(inventoryFacts!=reader || !current.getAsBoolean() || !this.current())return;
                inventoryFacts=null;reader.cancel();
                if(info==null || !info.readable() || !origin.equals(info.origin) || !target.equals(info.chat)){
                    done.accept(RecordPresence.Unknown);return;
                }
                PurpleSyncClient scoped=PurpleSyncTelegramClient.create(info,()->current.getAsBoolean()&&this.current());
                PurpleSyncTelegramTransport transport=new PurpleSyncTelegramTransport(scoped);inventory=transport;
                transport.check(null,result->{
                    RecordPresence presence=result.status==PurpleSyncTransport.CheckStatus.Finished
                            && result.inventory!=null && result.inventory.sameOrigin(scoped)
                            ? recordPresence(result.inventory,origin.userId,target):RecordPresence.Unknown;
                    ui(()->{if(inventory==transport)inventory=null;if(current.getAsBoolean()&&this.current())done.accept(presence);});
                });
            });
        }
        public void cancelInventory(){
            PurpleSyncChatClient facts=inventoryFacts;inventoryFacts=null;if(facts!=null)facts.cancel();
            PurpleSyncTelegramTransport running=inventory;inventory=null;if(running!=null)running.cancel();
        }
    }
    static RecordPresence recordPresence(PurpleSyncInventory inventory,long expectedUserId){
        return recordPresence(inventory,expectedUserId,Peer.self());
    }
    static RecordPresence recordPresence(PurpleSyncInventory inventory,long expectedUserId,Peer chat){
        if(inventory==null||inventory.accountUserId!=expectedUserId||!chat.equals(inventory.chat)||!inventory.scanComplete)return RecordPresence.Unknown;
        for(int i=0;i<inventory.count();++i){
            if(inventory.transport[i]!=PurpleSyncInventory.FETCHED)return RecordPresence.Unknown;
            PurpleAccountSyncCore.Result parsed=PurpleAccountSyncCore.inspectConfigRecord(inventory.bytes[i]);
            if(!"Valid".equals(parsed.status)||!"None".equals(parsed.error))return RecordPresence.Unknown;
        }
        return inventory.count()==0?RecordPresence.Empty:RecordPresence.Present;
    }
    private PurpleSyncChat(){ }
}
