package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatSetupEnvironment implements PurpleSyncChatSetup.Env {
    final PurpleSyncChat.Holder holder;
    final BooleanSupplier foreground,admitted;
    final File journalPath;
    final PurpleSyncChatSetup.JournalIO journal;
    final PurpleSyncChatSetupReceipts receipts;
    final PurpleSyncChatSetupTransport transport;
    PurpleSyncChatSetup setup;
    private volatile Object owner,lease;
    private volatile PurpleSyncOperations.Capability acquisition;
    private volatile long generation,acquisitionEpoch;
    private final Set<PurpleSyncChatTelegram> readers=Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<PurpleSyncTelegramTransport> scans=Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<Integer,Wire> wires=new ConcurrentHashMap<>();
    private final AtomicInteger sequence=new AtomicInteger();
    private final ConcurrentHashMap<Runnable,Runnable> delays=new ConcurrentHashMap<>();
    private final class Wire {
        volatile int nativeToken;
        volatile boolean cancelled,finished;
        void attach(int token){nativeToken=token;if(cancelled&&token>0)ConnectionsManager.getInstance(origin().slot).cancelRequest(token,true);}
        void cancel(){cancelled=true;AndroidUtilities.runOnUIThread(()->{if(nativeToken>0)ConnectionsManager.getInstance(origin().slot).cancelRequest(nativeToken,true);});}
    }
    PurpleSyncChatSetupEnvironment(PurpleSyncChat.Holder holder,BooleanSupplier foreground,BooleanSupplier admitted) {
        this(holder,foreground,admitted,false);
    }
    PurpleSyncChatSetupEnvironment(PurpleSyncChat.Holder holder,BooleanSupplier foreground,BooleanSupplier admitted,boolean provision) {
        this.holder=holder;this.foreground=foreground;this.admitted=admitted;
        journalPath=new File(holder.env.context.files.syncDirectory,provision?"sync-chat-move-create.json":"sync-chat-setup.json");
        journal=provision?moveProvisionJournal(PurpleSyncChatSetup.provisionFile(journalPath),origin(),authority(),holder.env.context.files.syncDirectory):PurpleSyncChatSetup.file(journalPath);
        receipts=new PurpleSyncChatSetupReceipts(origin(),PurpleSyncChatSetup.authority(authority()),PurpleSyncChatSetupReceipts.file(holder.env.context.files.syncDirectory));
        transport=new PurpleSyncChatSetupTransport(new PurpleSyncChatSetupTransport.Port(){
            public boolean current(PurpleSyncChatSetup.Command command){return setup!=null&&setup.commandCurrent(command)&&PurpleSyncChatSetupEnvironment.this.current();}
            public void dispatch(Runnable task){PurpleSyncChatSetupEnvironment.this.dispatch(task);}
            public int send(TLObject request,PurpleSyncChatTelegram.Reply done){return wire(request,done);}
            public void cancel(int token){Wire wire=wires.remove(token);if(wire!=null)wire.cancel();}
            public void delay(Runnable task,long millis){PurpleSyncChatSetupEnvironment.this.delay(task,millis);}
            public void cancelDelay(Runnable task){PurpleSyncChatSetupEnvironment.this.cancelDelay(task);}
            public long nowSeconds(){return PurpleSyncChatSetupEnvironment.this.nowSeconds();}
            public void resumeEffect(PurpleSyncChatSetup.Command command,Consumer<Boolean> ready){renew(command,ready);}
            public boolean waiting(PurpleSyncChatSetup.Command command,long retryAt){return setup.waiting(command,retryAt);}
            public boolean retain(PurpleSyncChatSetup.Command command,TLObject request,TLRPC.Updates updates){
                if(command.effect!=PurpleSyncChatSetup.Effect.Create&&command.effect!=PurpleSyncChatSetup.Effect.Mark)return true;
                return receipts.retain(command,request,updates,()->receiptCurrent(command));
            }
        });
    }
    static PurpleSyncChatSetup.JournalIO moveProvisionJournal(PurpleSyncChatSetup.JournalIO disk,PurpleSyncChatOrigin origin,String authority,File root){
        return new PurpleSyncChatSetup.JournalIO(){
            private byte[] retired;
            public byte[] read()throws Exception {
                byte[] raw=disk.read();retired=null;
                if(raw==null)return null;
                PurpleSyncChatSetup.Journal old=PurpleSyncChatSetup.Journal.parse(raw,origin,PurpleSyncChatSetup.authority(authority));
                if(old.step!=PurpleSyncChatSetup.Step.Provisioned)return raw;
                File carryPath=new File(root,"carry.json");
                PurpleAccountFileSnapshot before=PurpleAccountFileSnapshot.read(carryPath,4194304);
                if(before.status!=PurpleAccountFileSnapshot.Status.Present)return raw;
                org.json.JSONObject wrapper=new org.json.JSONObject(new String(before.bytes,java.nio.charset.StandardCharsets.UTF_8));
                if(!old.operation.replace("-","").equals(wrapper.getJSONObject("journal").getString("operation"))
                        ||!PurpleSyncChatMoveStore.completed(root,origin.accountKey())||!before.matches(carryPath))return raw;
                retired=raw;return null;
            }
            public boolean save(byte[] expected,byte[] next,BooleanSupplier current){
                byte[] actual=expected==null?retired:expected;
                boolean saved=disk.save(actual,next,current);if(saved)retired=null;return saved;
            }
        };
    }
    public void controller(PurpleSyncChatSetup value){setup=value;}
    public PurpleSyncChatOrigin origin(){return holder.env.origin;}
    public String authority(){return holder.env.authority;}
    public boolean current(){return admitted.getAsBoolean()&&holder.env.context.current();}
    public boolean foreground(){return foreground.getAsBoolean();}
    public PurpleSyncChatService.Binding binding(){return holder.env.binding();}
    public boolean acquire(Object next){
        if(!current()||!foreground()||owner!=null||!holder.env.current())return false;
        Object capability=new Object();long epoch=acquisitionEpoch,run=generation;
        PurpleSyncOperations.Capability previous=acquisition;
        PurpleSyncOperations.Capability token=PurpleSyncOperations.acquire(holder.env.operationKey(),capability,()->current()&&foreground());
        if(token==null)return false;
        boolean installed=false;
        try {
            if(owner!=null||epoch!=acquisitionEpoch||run!=generation||previous!=acquisition||!current()||!foreground()||!holder.env.current())return false;
            owner=next;lease=capability;acquisition=token;++acquisitionEpoch;installed=true;return true;
        } finally {if(!installed){token.revoke();PurpleSyncOperations.end(token.key,capability,token);}}
    }
    public boolean owned(Object expected){Object capability=lease;return owner==expected&&capability!=null&&PurpleSyncOperations.owned(holder.env.operationKey(),capability);}
    public void release(Object expected){if(owner==expected){
        Object capability=lease;PurpleSyncOperations.Capability token=acquisition;
        if(token!=null)token.revoke();
        try {owner=null;lease=null;acquisition=null;++acquisitionEpoch;}
        finally {if(token!=null)PurpleSyncOperations.end(token.key,capability,token);}
    }}
    public void dispatch(Runnable task){PurpleSyncChat.queue().postRunnable(task);}
    public void delay(Runnable task,long millis){Runnable timer=()->{delays.remove(task);dispatch(task);};delays.put(task,timer);holder.env.ui(()->{if(delays.get(task)==timer)AndroidUtilities.runOnUIThread(timer,millis);});}
    public void cancelDelay(Runnable task){Runnable timer=delays.remove(task);if(timer!=null)holder.env.ui(()->AndroidUtilities.cancelRunOnUIThread(timer));}
    public PurpleSyncChatSetup.JournalIO journal(){return journal;}
    public long randomId(){return PurpleSyncAutoUpload.randomId();}
    public long nowSeconds(){return System.currentTimeMillis()/1000;}
    private boolean live(long run){return run==generation&&current()&&foreground()&&owner!=null&&owned(owner);}
    private <T> PurpleSyncChatSetup.Reply<T> reply(T value){return new PurpleSyncChatSetup.Reply<>(origin(),value!=null?PurpleSyncChatSetup.Error.None:PurpleSyncChatSetup.Error.Unknown,value);}
    public void resolve(Object borrowed,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatScreen.Frame>> done){
        long run=generation;Object capability=lease;
        holder.env.ui(()->{
            if(!live(run)||!owned(borrowed)||lease!=capability)return;
            holder.service.checkBeforePost("config",new PurpleSyncChatService.Borrowed(holder.env.operationKey(),capability,()->live(run)&&lease==capability&&owned(borrowed),
                    value->dispatch(()->{if(live(run)&&lease==capability&&setup!=null)setup.readWaiting(value);})),
                    ignored->dispatch(()->{if(live(run))done.accept(reply(holder.service.screen()));}));
        });
    }
    private PurpleSyncChatTelegram reader(long run){
        PurpleSyncChatTelegram reader=new PurpleSyncChatTelegram(origin(),PurpleSyncChatTelegram.platform(origin(),()->live(run)));
        reader.transientWait(value->dispatch(()->{if(live(run)&&setup!=null)setup.readWaiting(value);}));
        readers.add(reader);return reader;
    }
    private void close(PurpleSyncChatTelegram reader){readers.remove(reader);holder.env.ui(reader::cancel);}
    public void info(Peer chat,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatClient.Info>> done){
        long run=generation;PurpleSyncChatTelegram reader=reader(run);
        reader.dispatch(()->reader.info(chat,value->dispatch(()->{close(reader);if(live(run))done.accept(reply(value));})));
    }
    public void inventory(Peer chat,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncInventory>> done){
        long run=generation;
        info(chat,value->{
            if(!live(run)||value.error!=PurpleSyncChatSetup.Error.None||value.value==null||!value.value.readable()){done.accept(reply(null));return;}
            PurpleSyncClient client=PurpleSyncTelegramClient.create(value.value,()->live(run));
            client.transientWait(wait->dispatch(()->{if(live(run)&&setup!=null)setup.readWaiting(wait);}));
            PurpleSyncTelegramTransport scan=new PurpleSyncTelegramTransport(client);scans.add(scan);
            scan.check(null,result->dispatch(()->{
                scans.remove(scan);scan.cancel();if(!live(run))return;
                done.accept(reply(result.status==PurpleSyncTransport.CheckStatus.Finished?result.inventory:null));
            }));
        });
    }
    public void exact(Cache mark,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.ReadBack>> done){
        long run=generation;PurpleSyncChatTelegram reader=reader(run);
        reader.dispatch(()->reader.readBack(mark,value->dispatch(()->{close(reader);if(live(run))done.accept(reply(value));})));
    }
    public void create(Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Created>> done){
        transport.write(setup.command(),PurpleSyncChatSetupRequests.create(),value->done.accept(new PurpleSyncChatSetup.Reply<>(origin(),value.error,PurpleSyncChatSetupRequests.created(value.value),value.retryAfterSeconds)));
    }
    public void reconcileCreate(String operation,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Created>> done){
        try{done.accept(reply(receipts.created(operation)));}catch(Exception|LinkageError error){done.accept(reply(null));}
    }
    public void applyUpdates(TLRPC.Updates updates,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){
        long run=generation;PurpleSyncChatSetupNative.updates(origin(),updates,()->live(run),value->dispatch(()->{if(live(run))done.accept(value);}));
    }
    private void withPeer(Peer chat,Consumer<TLRPC.InputPeer> done){
        long run=generation;holder.env.ui(()->{
            TLRPC.InputPeer input=live(run)?PurpleSyncChatTelegram.platform(origin(),()->live(run)).input(chat):null;
            dispatch(()->{if(live(run))done.accept(input);});
        });
    }
    public void post(PurpleSyncChatClient.Info info,long random,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Posted>> done){
        withPeer(info.chat,input->transport.write(setup.command(),PurpleSyncChatSetupRequests.mark(info,input,random),
                value->done.accept(new PurpleSyncChatSetup.Reply<>(origin(),value.error,PurpleSyncChatSetupRequests.posted(info,value.value,random),value.retryAfterSeconds))));
    }
    public void reconcileMark(Peer chat,long random,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Posted>> done){
        info(chat,value->{
            try{done.accept(reply(value.error==PurpleSyncChatSetup.Error.None?receipts.mark(setup.journal().operation,chat,random,value.value):null));}
            catch(Exception|LinkageError error){done.accept(reply(null));}
        });
    }
    public boolean commit(Cache mark,PurpleSyncChatClient.Info info,PurpleSyncChatCache.ReadersSnapshot baseline,boolean warning,BooleanSupplier guard){
        if(mark==null||info==null||!mark.chat.equals(info.chat)||!guard.getAsBoolean()||!holder.env.current())return false;
        PurpleSyncChatService.Binding binding=binding();
        if(!binding.ok||binding.bound)return false;
        PurpleSyncChatCache.Loaded opening=PurpleSyncChatCache.load(holder.env.cache,origin(),authority());
        if(!opening.ok||opening.data!=null&&(!opening.data.location.equals(mark)||opening.data.pendingMove()))return false;
        Set<String> warned=new HashSet<>(opening.data!=null?opening.data.warned:Collections.emptySet());warned.add("config");
        PurpleSyncChatCache.Data data=new PurpleSyncChatCache.Data(mark,info.title,baseline,warned,
                opening.data!=null?opening.data.journal:new org.json.JSONObject(),warning||opening.data!=null&&opening.data.newReaders);
        return PurpleSyncChatCache.save(holder.env.cache,opening,origin(),authority(),data,()->guard.getAsBoolean()&&current()&&holder.env.current());
    }
    public void pin(Cache mark,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){
        withPeer(mark.chat,input->transport.write(setup.command(),PurpleSyncChatSetupRequests.pin(mark,input),value->decoration(value,done)));
    }
    public void mute(Peer chat,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){
        withPeer(chat,input->transport.write(setup.command(),PurpleSyncChatSetupRequests.mute(chat,input),value->{
            if(value.error!=PurpleSyncChatSetup.Error.None||!(value.value instanceof TLRPC.TL_boolTrue)){done.accept(new PurpleSyncChatSetup.Reply<>(origin(),value.error,null));return;}
            long run=generation;
            PurpleSyncChatSetupNative.Notifications notifications=PurpleSyncChatSetupNative.notifications(origin(),()->live(run));
            boolean saved=live(run)&&notifications.preferences(origin().slot,PurpleSyncChatIO.dialogId(chat,origin().userId));
            holder.env.ui(()->{
                boolean applied=saved&&PurpleSyncChatSetupNative.muteLocal(origin(),chat,new PurpleSyncChatSetupNative.Notifications(){
                    public boolean current(PurpleSyncChatOrigin expected){return notifications.current(expected);}
                    public boolean preferences(int account,long dialog){return saved&&live(run);}
                    public void remove(int account,long dialog){notifications.remove(account,dialog);}
                    public void flags(int account,long dialog){notifications.flags(account,dialog);}
                    public void dialog(int account,long dialog){notifications.dialog(account,dialog);}
                    public void changed(int account){notifications.changed(account);}
                });
                dispatch(()->{if(live(run))done.accept(reply(applied?Boolean.TRUE:null));});
            });
        }));
    }
    public void archive(Peer chat,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){
        withPeer(chat,input->transport.write(setup.command(),PurpleSyncChatSetupRequests.archive(chat,input),value->decoration(value,done)));
    }
    private void decoration(PurpleSyncChatSetup.Reply<TLObject> value,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){
        if(value.error!=PurpleSyncChatSetup.Error.None||!(value.value instanceof TLRPC.Updates)){done.accept(new PurpleSyncChatSetup.Reply<>(origin(),value.error,null));return;}
        applyUpdates((TLRPC.Updates)value.value,done);
    }
    private void renew(PurpleSyncChatSetup.Command command,Consumer<Boolean> ready){
        if(!setup.commandCurrent(command)||!setup.prepare(command)){ready.accept(false);return;}
        PurpleSyncChatSetup.ResumeProof proof=new PurpleSyncChatSetup.ResumeProof();
        resolve(owner,resolved->{
            if(!setup.commandCurrent(command)||resolved.error!=PurpleSyncChatSetup.Error.None){ready.accept(false);return;}
            proof.resolution=resolved.value;
            if(command.effect==PurpleSyncChatSetup.Effect.Create){ready.accept(setup.renew(command,proof));return;}
            info(command.chat,first->{
                if(!setup.commandCurrent(command)||first.error!=PurpleSyncChatSetup.Error.None){ready.accept(false);return;}proof.first=first.value;
                if(command.effect==PurpleSyncChatSetup.Effect.Mark)inventory(command.chat,inventory->{
                    if(!setup.commandCurrent(command)||inventory.error!=PurpleSyncChatSetup.Error.None){ready.accept(false);return;}proof.inventory=inventory.value;
                    info(command.chat,second->{proof.second=second.value;ready.accept(setup.commandCurrent(command)&&second.error==PurpleSyncChatSetup.Error.None&&setup.renew(command,proof));});
                });
                else exact(command.mark,mark->{proof.mark=mark.value;ready.accept(setup.commandCurrent(command)&&mark.error==PurpleSyncChatSetup.Error.None&&setup.renew(command,proof));});
            });
        });
    }
    private boolean receiptCurrent(PurpleSyncChatSetup.Command command){
        if(!holder.env.context.current()||!holder.env.current())return false;
        try{
            byte[] raw=journal.read();if(raw==null)return false;
            PurpleSyncChatSetup.Journal stored=PurpleSyncChatSetup.Journal.parse(raw,origin(),PurpleSyncChatSetup.authority(authority()));
            return command.origin.equals(origin())&&command.operation.equals(stored.operation)&&command.random==stored.random
                    &&(command.effect==PurpleSyncChatSetup.Effect.Create?stored.step==PurpleSyncChatSetup.Step.CreateStarted:Objects.equals(command.chat,stored.chat)&&stored.step==PurpleSyncChatSetup.Step.MarkStarted);
        }catch(Exception|LinkageError error){return false;}
    }
    private int wire(TLObject request,PurpleSyncChatTelegram.Reply done){
        int id=sequence.incrementAndGet();Wire wire=new Wire();wires.put(id,wire);long run=generation;
        holder.env.ui(()->{
            if(wire.cancelled||!live(run)){wires.remove(id);dispatch(()->done.accept(null,null));return;}
            try {
            int token=ConnectionsManager.getInstance(origin().slot).sendRequest(request,(response,error)->{
                byte[] raw=null;try{if(response!=null)raw=PurpleSyncChatSetupReceipts.bytes(response);}catch(Exception|LinkageError ignored){}
                byte[] captured=raw;TLRPC.TL_error failure=null;
                if(error!=null){failure=new TLRPC.TL_error();failure.code=error.code;failure.text=error.text;}
                TLRPC.TL_error capturedError=failure;wire.finished=true;wires.remove(id);
                dispatch(()->{
                    TLObject owned=null;
                    if(captured!=null){SerializedData data=new SerializedData(captured);try{owned=request.deserializeResponse(data,data.readInt32(true),true);if(data.remaining()!=0)owned=null;}catch(RuntimeException ignored){}finally{data.cleanup();}}
                    done.accept(owned,capturedError);
                });
            },ConnectionsManager.RequestFlagFailOnServerErrors);
            wire.attach(token);
            }catch(RuntimeException|LinkageError error){wires.remove(id);dispatch(()->done.accept(null,null));}
        });
        return id;
    }
    public void cancel(){
        ++generation;transport.cancel();for(Wire wire:wires.values())wire.cancel();wires.clear();
        for(Runnable task:new ArrayList<>(delays.keySet()))cancelDelay(task);
        for(PurpleSyncChatTelegram reader:readers)holder.env.ui(reader::cancel);readers.clear();
        for(PurpleSyncTelegramTransport scan:scans)scan.cancel();scans.clear();
        Object captured=lease;holder.env.ui(()->{if(captured!=null)holder.service.cancelBorrowed(captured);});
    }
}
