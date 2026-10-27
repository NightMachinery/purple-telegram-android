package org.telegram.messenger.purple;

import java.io.File;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatManual extends PurpleSyncRunner.Platform implements PurpleSyncTransport {
    interface Port {
        PurpleSyncFileContext context();
        PurpleSyncChatOrigin origin();
        String authority();
        String key();
        String device();
        PurpleSyncSettingsFile.Contents settings();
        boolean current();
        void worker(Runnable task);
        void ui(Runnable task);
        PurpleSyncChatService.Binding binding();
        PurpleSyncChatCache.IO cache();
        PurpleSyncChatScreen.Frame frame();
        void resolve(boolean beforePost,PurpleSyncChatService.Borrowed borrowed,Consumer<PurpleSyncChatDiscovery.Status> done);
        void cancel(Object owner);
        PurpleSyncTransport records(PurpleSyncChatClient.Info info,BooleanSupplier current);
    }
    private final Port port;
    private final BooleanSupplier foreground;
    private volatile PurpleSyncChatScreen.Frame frame;
    private volatile PurpleSyncChatActivation consent;
    private volatile PurpleSyncTransport transport;
    private volatile boolean admitted,issued;
    private volatile Consumer<PurpleRpcRetry.Transient> waiting=ignored->{};
    @Override public void waiting(Consumer<PurpleRpcRetry.Transient> observer){waiting=observer;}
    private volatile Object owner,lease;
    private volatile long generation;

    PurpleSyncChatManual(PurpleSyncChat.Holder holder,BooleanSupplier foreground) {
        this(new Port(){
            public PurpleSyncFileContext context(){return holder.env.context;}
            public PurpleSyncChatOrigin origin(){return holder.env.origin;}
            public String authority(){return holder.env.authority;}
            public String key(){return holder.env.operationKey();}
            public String device(){return PurpleDevice.id();}
            public PurpleSyncSettingsFile.Contents settings(){return PurpleSyncSettingsFile.read(holder.env.context);}
            public boolean current(){return holder.env.current();}
            public void worker(Runnable task){holder.env.worker(task);}
            public void ui(Runnable task){holder.env.ui(task);}
            public PurpleSyncChatService.Binding binding(){return holder.env.binding();}
            public PurpleSyncChatCache.IO cache(){return holder.env.cache;}
            public PurpleSyncChatScreen.Frame frame(){return holder.service.screen();}
            public void resolve(boolean beforePost,PurpleSyncChatService.Borrowed borrowed,Consumer<PurpleSyncChatDiscovery.Status> done){
                if(beforePost)holder.service.checkBeforePost("config",borrowed,done);else holder.service.ensureResolved(false,"config",borrowed,done);
            }
            public void cancel(Object owner){holder.service.cancelBorrowed(owner);}
            public PurpleSyncTransport records(PurpleSyncChatClient.Info info,BooleanSupplier current){return new PurpleSyncTelegramTransport(PurpleSyncTelegramClient.create(info,current));}
        },foreground);
    }
    PurpleSyncChatManual(Port port,BooleanSupplier foreground){super(port.context());this.port=port;this.foreground=foreground;}
    private boolean available(){return foreground.getAsBoolean()&&context.current();}
    @Override public boolean current(int account,long user){return context.current()&&context.origin.slot==account&&context.origin.userId==user;}
    @Override public PurpleSyncChatScreen.Candidate recipient(){PurpleSyncChatScreen.Frame value=frame;return value!=null?value.current:null;}
    @Override public boolean consent(PurpleSyncInventory inventory){
        PurpleSyncChatScreen.Frame value=frame;
        if(!available()||value==null||value.current==null||!value.current.allowed||value.opening==null||value.opening.data==null
                ||inventory==null||!port.origin().equals(inventory.origin)||!value.current.chat.equals(inventory.chat)||!inventory.scanComplete)return false;
        if(consent!=null&&consent.accepts(inventory)&&consent.renew(value,issued)){reviewedInventory=inventory;return true;}
        consent=null;admitted=false;issued=false;
        pendingConsent=inventory;reviewedInventory=inventory;return true;
    }
    private volatile PurpleSyncInventory pendingConsent,reviewedInventory;
    private File journalFile(){return new File(context.files.syncDirectory,"sync-chat-setup.json");}
    private boolean validate(boolean initializing){
        if(!available()||owner==null||!PurpleSyncOperations.owned(port.key(),owner)||!port.current())return false;
        PurpleSyncChatScreen.Frame value=frame;
        PurpleSyncChatService.Binding binding=port.binding();
        if(!binding.ok||!binding.bound&&(reviewedInventory==null||reviewedInventory.count()!=0))return false;
        PurpleAccountFileSnapshot journal=PurpleAccountFileSnapshot.read(journalFile(),16384);
        if(journal.status==PurpleAccountFileSnapshot.Status.ReadError)return false;
        try {
            if(consent==null){
                PurpleSyncInventory inventory=pendingConsent;
                if(value==null||value.current==null||value.opening==null||value.opening.data==null||inventory==null)return false;
                PurpleSyncChatActivation next=new PurpleSyncChatActivation(port.origin(),value.opening.data.location,value.current,journal.bytes,port.authority());
                if(!next.accepts(inventory))return false;
                consent=next;pendingConsent=null;
            }
            if(!consent.renew(value,issued)||!java.util.Arrays.equals(consent.journal,journal.bytes))return false;
            if(binding.bound&&!binding.chat.equals(consent.mark.chat))return false;
            if(initializing&&!binding.bound&&!pendingEmpty())return false;
            PurpleSyncChatCache.Loaded opening=PurpleSyncChatCache.load(port.cache(),port.origin(),port.authority());
            if(!opening.ok||opening.data==null||opening.data.pendingMove()||!consent.mark.equals(opening.data.location))return false;
            if(!opening.data.accepted("config")){
                java.util.Set<String> warned=new java.util.HashSet<>(opening.data.warned);warned.add("config");
                PurpleSyncChatCache.Data next=new PurpleSyncChatCache.Data(opening.data.location,opening.data.title,
                        opening.data.readers!=null?opening.data.readers:PurpleSyncChatCache.ReadersSnapshot.from(consent.candidate.info),warned,opening.data.journal,opening.data.newReaders);
                if(!PurpleSyncChatCache.save(port.cache(),opening,port.origin(),port.authority(),next,
                        ()->available()&&owner!=null&&PurpleSyncOperations.owned(port.key(),owner)&&port.current()))return false;
            }
            return true;
        }catch(RuntimeException|LinkageError error){return false;}
    }
    private boolean pendingEmpty(){return frame!=null&&frame.binding!=null&&!frame.binding.bound;}
    @Override public void prepareApply(Object owner,String key,BooleanSupplier current,Consumer<Boolean> done){
        PurpleSyncInventory shown=reviewedInventory;
        resolveChat(true,owner,key,current,ready->{
            if(!Boolean.TRUE.equals(ready)||!current.getAsBoolean()){done.accept(false);return;}
            check(null,result->done.accept(current.getAsBoolean()&&result.status==CheckStatus.Finished&&sameInventory(shown,result.inventory)));
        });
    }
    static boolean sameInventory(PurpleSyncInventory a,PurpleSyncInventory b){
        return a!=null&&b!=null&&a.scanComplete&&b.scanComplete&&a.accountUserId==b.accountUserId&&a.chat.equals(b.chat)
                &&java.util.Objects.equals(a.origin,b.origin)&&java.util.Arrays.equals(a.ids,b.ids)
                &&java.util.Arrays.equals(a.transport,b.transport)&&java.util.Arrays.equals(a.documentIds,b.documentIds)
                &&java.util.Arrays.equals(a.editDates,b.editDates)&&java.util.Arrays.deepEquals(a.bytes,b.bytes);
    }
    @Override public PurpleAccountSyncCore.Result initialize(int account,String install,String device,String space){
        if(!validate(true))return PurpleAccountSyncCore.inspectState(null);
        return PurpleAccountSyncCore.initializeBoundLocalState(account,install,device,space,consent.mark.chat);
    }
    @Override public boolean workerEffects(){return true;}
    @Override public void checkEffects(BooleanSupplier effects,Consumer<Boolean> done){
        port.worker(()->{boolean valid=false;try{valid=available()&&port.current()&&effects.getAsBoolean();}catch(RuntimeException|LinkageError ignored){}
            boolean result=valid;port.ui(()->done.accept(result));});
    }
    @Override public PurpleSyncSettingsFile.Contents readSettings(){return port.settings();}
    @Override public String device(){return port.device();}
    @Override public PurpleAccountSyncStore store(){
        Peer chat=frame!=null&&frame.current!=null?frame.current.chat:Peer.self();
        return new PurpleAccountSyncStore(context,chat);
    }
    @Override public boolean requiresRecovery(){return frame==null||frame.current==null||Peer.self().equals(frame.current.chat);}
    @Override public PurpleSyncRecoveryModel.Offer recovery(PurpleSyncInventory inventory,PurpleSyncSettingsFile.Contents local){
        return Peer.self().equals(inventory.chat)?super.recovery(inventory,local):null;
    }
    @Override public void resolveChat(boolean beforePost,Object owner,String key,BooleanSupplier current,Consumer<Boolean> done){
        if(!available()||!current.getAsBoolean()){done.accept(false);return;}
        if(this.owner!=owner||lease==null){this.owner=owner;lease=owner;}
        Object captured=lease;long run=++generation;
        BooleanSupplier live=()->run==generation&&lease==captured&&available()&&current.getAsBoolean();
        port.worker(()->{
            boolean valid=live.getAsBoolean()&&port.current()&&(!beforePost||validate(false));
            admitted=valid&&beforePost;
            port.ui(()->{
                if(!live.getAsBoolean())return;
                if(!valid){done.accept(false);return;}
                PurpleSyncChatService.Borrowed borrowed=new PurpleSyncChatService.Borrowed(key,captured,live,value->{if(live.getAsBoolean())waiting.accept(value);},()->live.getAsBoolean()&&admitted);
                Consumer<PurpleSyncChatDiscovery.Status> reply=status->{
                    if(!live.getAsBoolean())return;
                    PurpleSyncChatScreen.Frame next=port.frame();
                    boolean readable=status.complete&&status.phase==PurpleSyncChatDiscovery.Phase.Use&&status.resolution.action==Action.Use
                            &&next.current!=null&&next.current.allowed&&next.current.chat.equals(status.resolution.readChat)
                            &&(!beforePost||!status.postingHeld);
                    if(!readable){done.accept(false);return;}
                    PurpleSyncChatScreen.Frame previous=frame;frame=next;
                    if(consent!=null&&!consent.renew(next,issued)){
                        admitted=false;
                        if(beforePost){frame=previous;done.accept(false);return;}
                        consent=null;pendingConsent=null;issued=false;
                    }
                    port.worker(()->{
                        boolean ready=live.getAsBoolean()&&port.current()&&(!beforePost||validate(false));
                        port.ui(()->{
                            if(!live.getAsBoolean())return;
                            if(ready&&(transport==null||previous==null||previous.current==null||!previous.current.chat.equals(next.current.chat)))
                                transport=port.records(next.current.info,this::available);
                            done.accept(ready);
                        });
                    });
                };
                port.resolve(beforePost,borrowed,reply);
            });
        });
    }
    @Override public void cancelChat(Object owner){
        if(this.owner!=owner)return;
        Object captured=lease;lease=null;this.owner=null;admitted=false;++generation;
        port.ui(()->port.cancel(captured));
    }
    @Override public void check(Progress progress,CheckDone done){
        PurpleSyncTransport active=transport;
        if(active==null||!available()){done.onCheckDone(new CheckResult(CheckStatus.SyncChatHeld,null,false));return;}
        active.check(progress,result->{if(available())done.onCheckDone(result);});
    }
    @Override public void post(byte[] bytes,PostDone done){done.onPostDone(new PostResult(PostStatus.NeedsReview,0,null));}
    @Override public void post(byte[] bytes,PostAuthority authority,PostDone done){
        PurpleSyncChatActivation activated=consent;PurpleSyncTransport active=transport;
        if(activated==null||active==null||authority==null||!authority.current()){post(bytes,done);return;}
        active.post(bytes,new PostAuthority(){
            public boolean current(){return available()&&consent==activated&&authority.current();}
            public void check(Consumer<Boolean> done){authority.check(ok->{if(Boolean.TRUE.equals(ok)&&current()){done.accept(true);}else done.accept(false);});}
            public void issued(){if(current())issued=true;}
            public void waiting(PurpleRpcRetry.Transient value){if(current())authority.waiting(value);}
            public boolean permits(PurpleSyncChatOrigin origin,Peer chat){return current()&&admitted&&activated.origin.equals(origin)&&activated.mark.chat.equals(chat);}
        },done);
    }
    @Override public void cancel(){PurpleSyncTransport active=transport;if(active!=null)active.cancel();}
}
