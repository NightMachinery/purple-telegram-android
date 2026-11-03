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
        /** The chat to read and post in while phase is Use, otherwise null. */
        public final Peer chat;
        /** A Magic Purple Sync problem that pauses sync, and whether it still has to be shown once. */
        public final PurpleSyncMagicCore.Problem problem;
        public final boolean showProblem;
        Status(PurpleSyncChatDiscovery.Status status) {
            phase=Phase.valueOf(status.phase.name());resolution=status.resolution;reason=status.reason;
            newReaders=status.newReaders;complete=status.complete;postingHeld=status.postingHeld;retryAt=status.retryAt;
            chat=phase==Phase.Use&&resolution.action==Action.Use?resolution.readChat:null;problem=PurpleSyncMagicCore.Problem.None;showProblem=false;
        }
        Status(PurpleSyncMagicChat.Result result) {
            boolean use=result.kind==PurpleSyncMagicChat.Result.Kind.Use;
            phase=use?Phase.Use:Phase.Paused;resolution=resolve(new ResolveInput(1));
            reason=use?"Use":result.kind==PurpleSyncMagicChat.Result.Kind.Problem?result.problem.code():result.kind==PurpleSyncMagicChat.Result.Kind.Wait?"Waiting":"AccountUnavailable";
            newReaders=false;complete=use;postingHeld=!use;retryAt=0;
            chat=use?result.chat:null;problem=result.problem;showProblem=result.show;
        }
        public boolean selfReadable() { return complete&&phase==Phase.Use&&Peer.self().equals(chat); }
        public boolean selfPostable() { return selfReadable()&&!postingHeld; }
        /** Sync may read {@code expected}: the lookup bound it. */
        public boolean readable(Peer expected) { return complete&&phase==Phase.Use&&chat!=null&&chat.equals(expected); }
        public boolean postable(Peer expected) { return readable(expected)&&!postingHeld; }
    }
    static final class Holder {
        final Platform env;
        final PurpleSyncChatService service;
        final PurpleSyncChatFlows flows=new PurpleSyncChatFlows();
        final PurpleSyncChatStartup startup;
        final PurpleSyncMagicChat magic;
        Holder(Platform env) { this.env=env;service=new PurpleSyncChatService(env);startup=new PurpleSyncChatStartup(this);magic=new PurpleSyncMagicChat(new PurpleSyncMagicTelegram(env)); }
        void close(boolean logout){try{magic.close();}finally{try{startup.close();}finally{try{flows.close();}finally{service.close(logout);}}}}
    }
    private static final java.util.concurrent.ConcurrentHashMap<Integer,Holder> accounts=new java.util.concurrent.ConcurrentHashMap<>();
    private static DispatchQueue queue;
    static synchronized DispatchQueue queue(){if(queue==null)queue=new DispatchQueue("purpleSyncChat");return queue;}
    private static Holder account(int slot) {
        if(slot<0||slot>=UserConfig.MAX_ACCOUNT_COUNT)return null;
        Holder holder=accounts.get(slot);
        if(holder!=null&&!holder.env.current()){
            accounts.remove(slot,holder);holder.close(!holder.env.identityCurrent());holder=accounts.get(slot);
        }
        if(holder==null){
            try{Platform env=new Platform(slot);if(!env.current())return null;holder=new Holder(env);accounts.put(slot,holder);}
            catch(Exception|LinkageError error){return null;}
        }
        return holder;
    }
    /** Finds or creates the Magic Purple Sync chat once the account's chat list is up; never searches. */
    public static void startSession(int slot) { startSession(slot,null); }
    /** {@code done}, when given, gets the startup lookup's status on the UI thread, to show a problem once. */
    public static void startSession(int slot,Consumer<Status> done) {
        AndroidUtilities.runOnUIThread(()->{
            Holder holder=account(slot);if(holder==null)return;
            holder.magic.lookup(PurpleSyncMagicChat.Trigger.startup,false,done==null?null:result->AndroidUtilities.runOnUIThread(()->{if(holder.env.current())done.accept(new Status(result));}));
        });
    }
    public static void ensureResolved(int slot,boolean force,Consumer<Status> done) {
        lookup(slot,force?PurpleSyncMagicChat.Trigger.manual:PurpleSyncMagicChat.Trigger.screen,force,()->true,done);
    }
    public static void checkBeforePost(int slot,String stream,Consumer<Status> done) {
        lookup(slot,PurpleSyncMagicChat.Trigger.post,true,()->true,done);
    }
    private static void lookup(int slot,PurpleSyncMagicChat.Trigger trigger,boolean fresh,BooleanSupplier current,Consumer<Status> done) {
        AndroidUtilities.runOnUIThread(()->{
            Holder holder=account(slot);if(holder==null){done.accept(unavailable());return;}
            holder.magic.lookup(trigger,fresh,result->AndroidUtilities.runOnUIThread(()->{if(current.getAsBoolean())done.accept(new Status(result));}));
        });
    }
    /** The problem in the last status was shown to the user; it is not shown again until it changes. */
    public static void problemShown(int slot){Holder holder=accounts.get(slot);if(holder!=null)holder.magic.shown();}
    /** The chat the last lookup bound, or null while it waits, has a problem or has not run. */
    static Peer boundChat(int slot) {
        Holder holder=accounts.get(slot);
        if(holder==null||!holder.env.current())return null;
        PurpleSyncMagicChat.Result last=holder.magic.last();
        return last.kind==PurpleSyncMagicChat.Result.Kind.Use?last.chat:null;
    }
    /**
     * A record client for the bound supergroup {@code chat}, built from the local chat without a request: the
     * lookup verified within a day that the user created it and is its only member and admin. Null when the chat
     * is not a joined supergroup in the local cache.
     */
    static PurpleSyncClient boundClient(int slot,Peer chat,BooleanSupplier current) {
        Holder holder=accounts.get(slot);
        if(holder==null||!holder.env.current()||chat==null||chat.kind!=PeerKind.Channel)return null;
        PurpleSyncMagicCore.Candidate local=PurpleSyncChatFacts.magic(org.telegram.messenger.MessagesController.getInstance(slot).getChat(chat.id),null);
        if(local==null||!local.member||!local.megagroup||local.gigagroup)return null;
        PurpleSyncChatOrigin origin=holder.env.origin;
        PurpleSyncChatFacts.MappedPeer peer=new PurpleSyncChatFacts.MappedPeer(origin,chat,Kind.Supergroup,Truth.True,Truth.True,
                local.facts,java.util.Collections.singleton(origin.userId));
        PurpleSyncChatClient.Info info=new PurpleSyncChatClient.Info(origin,chat,peer,PurpleSyncChatClient.Error.None,local.title,peer.memberIds,true);
        return PurpleSyncTelegramClient.create(info,()->current.getAsBoolean()&&holder.env.current());
    }
    /**
     * Raises space_mismatch when the bound chat holds sync records, none of them in this device's sync space:
     * another device set up sync there first, so joining its space is the user's choice.
     */
    static void checkSpace(int slot,Peer chat,PurpleSyncInventory inventory,String space) {
        Holder holder=accounts.get(slot);
        if(holder==null||chat==null||Peer.self().equals(chat)||inventory==null||!chat.equals(inventory.chat)
                ||!inventory.scanComplete||space==null||space.isEmpty())return;
        java.util.Set<String> spaces=PurpleSyncMagicManual.spaces(inventory);
        if(spaces!=null&&!spaces.isEmpty()&&!spaces.contains(space))holder.magic.raise(PurpleSyncMagicCore.Problem.SpaceMismatch);
    }
    /** Whether the last lookup recorded a problem that pauses automatic sync. */
    static boolean chatProblem(int slot) {
        Holder holder=accounts.get(slot);
        return holder!=null&&holder.magic.last().kind==PurpleSyncMagicChat.Result.Kind.Problem;
    }
    /** The Sync chat screen's view of the account, from the last lookup, without a request. */
    public static PurpleSyncMagicScreen.Frame magicFrame(int slot) {
        Holder holder=accounts.get(slot);
        return holder!=null&&holder.env.current()?PurpleSyncMagicScreen.frame(holder.magic.last(),holder.magic.binding()):PurpleSyncMagicScreen.frame(null,null);
    }
    /** A manual check: clears a recorded problem and looks the chat up afresh. */
    public static void magicCheck(int slot,BooleanSupplier current,Consumer<PurpleSyncMagicScreen.Frame> done) {
        magicAction(slot,current,done,(holder,reply)->holder.magic.lookup(PurpleSyncMagicChat.Trigger.manual,true,reply));
    }
    /** Whether the user may pick {@code chat} (Saved Messages, or a supergroup the user is in alone), from local data. */
    public static PurpleSyncMagicScreen.Pick magicPick(int slot,Peer chat) {
        if(chat==null||Peer.self().equals(chat))return PurpleSyncMagicScreen.pick(chat,null);
        org.telegram.messenger.MessagesController messages=org.telegram.messenger.MessagesController.getInstance(slot);
        org.telegram.tgnet.TLRPC.Chat local=chat.kind==PeerKind.Channel?messages.getChat(chat.id):null;
        PurpleSyncMagicCore.Candidate candidate=local!=null?PurpleSyncChatFacts.magic(local,messages.getChatFull(chat.id)):null;
        if(candidate==null&&local!=null)candidate=PurpleSyncChatFacts.magic(local,null);
        return PurpleSyncMagicScreen.pick(chat,candidate);
    }
    public static void magicChoose(int slot,Peer chat,BooleanSupplier current,Consumer<PurpleSyncMagicScreen.Frame> done) {
        if(magicPick(slot,chat)!=PurpleSyncMagicScreen.Pick.Allowed){done.accept(magicFrame(slot));return;}
        magicAction(slot,current,done,(holder,reply)->holder.magic.choose(chat,reply));
    }
    public static void magicUseDefault(int slot,BooleanSupplier current,Consumer<PurpleSyncMagicScreen.Frame> done) {
        magicAction(slot,current,done,(holder,reply)->holder.magic.useDefault(reply));
    }
    public static void magicList(int slot,boolean listed,BooleanSupplier current,Consumer<Boolean> done) {
        AndroidUtilities.runOnUIThread(()->{
            Holder holder=account(slot);if(holder==null){done.accept(false);return;}
            holder.magic.list(listed,ok->AndroidUtilities.runOnUIThread(()->{if(current.getAsBoolean())done.accept(ok);if(ok)org.telegram.messenger.NotificationCenter.getInstance(slot).postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload);}));
        });
    }
    /**
     * Join for space_mismatch: this device's own sync state for the bound chat is set aside (nothing is deleted)
     * and the problem cleared, so the next check on the Sync screen offers to join the records already there.
     * {@code done} gets false when the state could not be set aside, for example while a sync runs.
     */
    public static void magicJoin(int slot,BooleanSupplier current,Consumer<Boolean> done) {
        AndroidUtilities.runOnUIThread(()->{
            Holder holder=account(slot);
            PurpleSyncMagicChat.Result last=holder!=null?holder.magic.last():null;
            if(last==null||last.kind!=PurpleSyncMagicChat.Result.Kind.Problem||last.problem!=PurpleSyncMagicCore.Problem.SpaceMismatch||last.chat==null){done.accept(false);return;}
            Peer chat=last.chat;
            queue().postRunnable(()->{
                boolean ok=false;
                try(PurpleAccountSyncStore store=PurpleAccountSyncStore.following(holder.env.context,chat)){
                    ok=holder.env.current()&&store.open(true,slot,PurpleDevice.id()).status==PurpleAccountSyncStore.Status.Ready
                            &&chat.equals(store.chat())&&store.setAside().status==PurpleAccountSyncStore.Status.Uninitialized;
                }catch(Exception|LinkageError ignored){ok=false;}
                PurpleDiagLog.event("sync_chat","migration",PurpleDiagLogStore.code("step","joined"),PurpleDiagLogStore.code("outcome",ok?"ok":"error"));
                boolean setAside=ok;
                if(!setAside){AndroidUtilities.runOnUIThread(()->{if(current.getAsBoolean())done.accept(false);});return;}
                holder.magic.joined(result->AndroidUtilities.runOnUIThread(()->{if(current.getAsBoolean())done.accept(true);}));
            });
        });
    }
    /** The magic chat Purple's chat lists hide for this account, or null (the user picked a chat or chose to see it). */
    public static Peer hiddenChat(int slot) {
        Holder holder=accounts.get(slot);
        return holder!=null?holder.magic.hidden():null;
    }
    private interface MagicCall { void start(Holder holder,Consumer<PurpleSyncMagicChat.Result> reply); }
    private static void magicAction(int slot,BooleanSupplier current,Consumer<PurpleSyncMagicScreen.Frame> done,MagicCall call) {
        AndroidUtilities.runOnUIThread(()->{
            Holder holder=account(slot);if(holder==null){done.accept(PurpleSyncMagicScreen.frame(null,null));return;}
            call.start(holder,result->AndroidUtilities.runOnUIThread(()->{if(current.getAsBoolean())done.accept(PurpleSyncMagicScreen.frame(result,holder.magic.binding()));}));
        });
    }
    public static Status status(int slot) { Holder holder=accounts.get(slot);return holder!=null&&holder.env.current()?new Status(holder.magic.last()):unavailable(); }
    public static PurpleSyncChatScreen.Control screen(int slot) { return screen(slot,()->true); }
    public static PurpleSyncChatScreen.Control screen(int slot,BooleanSupplier foreground) {
        Holder holder=account(slot);
        return holder!=null?new PurpleSyncChatScreen.Control(new ScreenPort(holder,foreground)):null;
    }
    public static PurpleSyncChatSetupFlow setup(int slot,java.util.function.BooleanSupplier foreground,Consumer<PurpleSyncChatSetupFlow.State> changed) {
        Holder holder=accounts.get(slot);
        return holder!=null&&holder.env.context.current()?new PurpleSyncChatSetupFlow(holder,foreground,changed):null;
    }
    public static void manualRunner(int slot,BooleanSupplier foreground,Consumer<PurpleSyncRunner> done) {
        AndroidUtilities.runOnUIThread(()->{
            Holder holder=accounts.get(slot);
            if(holder==null||!holder.env.context.current()){done.accept(null);return;}
            holder.env.worker(()->{
                boolean ready=holder.env.current();
                holder.env.ui(()->{
                    if(!foreground.getAsBoolean()||!holder.env.context.current()){done.accept(null);return;}
                    PurpleSyncMagicManual route=ready?new PurpleSyncMagicManual(holder,foreground):null;
                    done.accept(route!=null?new PurpleSyncRunner(slot,holder.env.origin.userId,route,route,
                            task->queue().postRunnable(task),AndroidUtilities::runOnUIThread):null);
                });
            });
        });
    }
    private static final class ScreenPort implements PurpleSyncChatScreen.Port {
        final Holder holder;
        PurpleSyncChatClient reader;
        volatile Object owner;
        volatile PurpleSyncOperations.Capability acquisition;
        volatile boolean closed;
        volatile long acquisitionEpoch;
        final BooleanSupplier foreground;
        ScreenPort(Holder holder,BooleanSupplier foreground) { this.holder=holder;this.foreground=foreground; }
        public String authority(){return holder.env.authority;}
        public PurpleSyncChatOrigin origin() { return holder.env.origin; }
        public boolean current() { return !closed&&foreground.getAsBoolean()&&holder.env.current(); }
        public boolean begin(Object owner) {
            if(!current()||this.owner!=null)return false;
            long epoch=acquisitionEpoch;PurpleSyncOperations.Capability previous=acquisition;
            PurpleSyncOperations.Capability token=PurpleSyncOperations.acquire(holder.env.operationKey(),owner,this::current);
            if(token==null)return false;
            boolean installed=false;
            try {
                if(closed||this.owner!=null||epoch!=acquisitionEpoch||previous!=acquisition||!current())return false;
                this.owner=owner;acquisition=token;++acquisitionEpoch;installed=true;return true;
            } finally {if(!installed){token.revoke();PurpleSyncOperations.end(token.key,owner,token);}}
        }
        public void end(Object owner) {
            if(this.owner!=owner)return;
            PurpleSyncOperations.Capability token=acquisition;
            if(token!=null)token.revoke();
            try {this.owner=null;acquisition=null;++acquisitionEpoch;}
            finally {if(token!=null)PurpleSyncOperations.end(token.key,owner,token);}
        }
        public void resolve(Object owner,Consumer<PurpleSyncChatScreen.Frame> done) {
            PurpleSyncChatService.Borrowed borrowed=new PurpleSyncChatService.Borrowed(holder.env.operationKey(),owner,this::current);
            holder.service.ensureResolved(true,"config",borrowed,ignored->{if(current())done.accept(holder.service.screen());});
        }
        public void resolve(Object owner,String stream,Consumer<PurpleSyncChatScreen.Frame> done){
            PurpleSyncChatService.Borrowed borrowed=new PurpleSyncChatService.Borrowed(holder.env.operationKey(),owner,this::current);
            holder.service.ensureResolved(true,stream,borrowed,ignored->{if(current())done.accept(holder.service.screen());});
        }
        public void pause(PurpleSyncChatScreen.Frame frame,boolean paused,Consumer<Boolean> done){
            Object captured=owner;
            holder.env.worker(()->{
                boolean saved=false;
                try {
                if(current()&&captured!=null&&captured==owner&&PurpleSyncOperations.owned(holder.env.operationKey(),captured)){
                    PurpleSyncChatService.Binding binding=holder.env.binding();
                    if(binding.same(frame.binding)&&binding.stateStamp.equals(frame.binding.stateStamp))
                        saved=PurpleSyncChatCache.pause(holder.env.cache,frame.opening,origin(),holder.env.authority,paused,()->current()&&captured==owner&&PurpleSyncOperations.owned(holder.env.operationKey(),captured));
                }
                }catch(RuntimeException|LinkageError error){saved=false;}
                boolean result=saved;holder.env.ui(()->{if(current()&&captured==owner)done.accept(result);});
            });
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
                            &&binding.same(frame.binding)
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
    public static PurpleSyncChatMarkActions removeMark(int slot,PurpleSyncChatMarkActions.Row row,BooleanSupplier foreground,Consumer<PurpleSyncChatMarkActions.State> changed){
        Holder holder=accounts.get(slot);
        boolean current=holder!=null&&foreground.getAsBoolean()&&holder.env.context.current();
        if(!PurpleSyncChatMarkActions.ACTIVATED){
            changed.accept(PurpleSyncChatMarkActions.production(row,holder==null?null:holder.env.origin,holder==null?null:holder.env.authority,current));return null;
        }
        if(!current)return null;
        PurpleSyncChatMarkActions action=new PurpleSyncChatMarkActions(new MarkPort(holder,foreground),changed);action.remove(row);return action;
    }
    private static final class MarkPort implements PurpleSyncChatMarkActions.Port {
        final Holder holder;
        final ScreenPort screen;
        PurpleSyncChatTelegram client,admissionReader;
        long admissionEpoch;
        MarkPort(Holder holder,BooleanSupplier foreground){this.holder=holder;screen=new ScreenPort(holder,foreground);}
        public PurpleSyncChatOrigin origin(){return screen.origin();}
        public String authority(){return screen.authority();}
        public boolean current(){return screen.current();}
        public boolean begin(Object owner){return screen.begin(owner);}
        public boolean owned(Object owner){return screen.owner==owner&&screen.acquisition!=null&&screen.acquisition.current();}
        public void end(Object owner){screen.end(owner);}
        public void resolve(Object owner,Consumer<PurpleSyncChatScreen.Frame> done){screen.resolve(owner,done);}
        public void referenced(PurpleSyncChatMarkActions.Row row,Consumer<Boolean> done){
            holder.env.worker(()->{
                boolean blocked=!current()||PurpleSyncChatMoveStore.markReferenced(holder.env.context.files.syncDirectory,holder.env.context.origin.key,row);
                holder.env.ui(()->{if(current())done.accept(blocked);});
            });
        }
        PurpleSyncChatTelegram client(){
            if(client==null){
                Object owner=screen.owner;PurpleSyncOperations.Capability token=screen.acquisition;
                client=new PurpleSyncChatTelegram(origin(),PurpleSyncChatTelegram.platform(origin(),()->valid(owner,token)));
            }
            return client;
        }
        public void exact(PurpleSyncChatMarkActions.Row row,Consumer<PurpleSyncChatClient.Exact> done){client().exact(row.location,done);}
        public void delete(PurpleSyncChatMarkActions.Row row,Consumer<org.telegram.tgnet.TLObject> done){client().removeMark(row.location,(request,grant)->admit(row,request,grant),done);}
        boolean valid(Object owner,PurpleSyncOperations.Capability token){
            return owner!=null&&token!=null&&token==screen.acquisition&&owner==screen.owner&&token.current()&&current();
        }
        <T> Consumer<T> admitted(long epoch,Object owner,PurpleSyncOperations.Capability token,Consumer<Boolean> grant,Consumer<T> next){
            return value->{
                if(epoch!=admissionEpoch||!valid(owner,token)){grant.accept(false);return;}
                try{next.accept(value);}catch(RuntimeException|LinkageError error){grant.accept(false);}
            };
        }
        void admit(PurpleSyncChatMarkActions.Row row,org.telegram.tgnet.TLObject request,Consumer<Boolean> grant){
            long epoch=++admissionEpoch;Object owner=screen.owner;PurpleSyncOperations.Capability token=screen.acquisition;
            try{
                if(!valid(owner,token)||!PurpleSyncChatMarkActions.requestMatches(row,request)){grant.accept(false);return;}
                if(admissionReader!=null){admissionReader.cancel();admissionReader=null;}
                holder.service.cancelBorrowed(owner);
                screen.resolve(owner,admitted(epoch,owner,token,grant,frame->{
                    if(frame==null||!frame.status.complete||frame.status.postingHeld){grant.accept(false);return;}
                    referenced(row,admitted(epoch,owner,token,grant,blocked->{
                        if(!Boolean.FALSE.equals(blocked)){grant.accept(false);return;}
                        PurpleSyncChatTelegram reader=new PurpleSyncChatTelegram(origin(),PurpleSyncChatTelegram.platform(origin(),()->epoch==admissionEpoch&&valid(owner,token)));
                        admissionReader=reader;
                        reader.exact(row.location,admitted(epoch,owner,token,grant,exact->{
                            grant.accept(PurpleSyncChatMarkActions.requestMatches(row,request)&&PurpleSyncChatMarkActions.authorized(row,frame,exact)&&valid(owner,token));
                        }));
                    }));
                }));
            }catch(RuntimeException|LinkageError error){grant.accept(false);}
        }
        public void apply(PurpleSyncChatMarkActions.Row row,org.telegram.tgnet.TLObject updates){
            org.telegram.messenger.PurpleSyncChatMarkUpdates.apply(origin().slot,row.location.chat,updates,holder.env::current);
        }
        public void cancel(Object owner){
            ++admissionEpoch;
            try{if(client!=null){client.cancel();client=null;}}
            finally{try{if(admissionReader!=null){admissionReader.cancel();admissionReader=null;}}finally{holder.service.cancelBorrowed(owner);}}
        }
    }
    public static PurpleSyncChatFlows.Handle change(int slot,PurpleSyncChatScreen.ChangeSelection selection,BooleanSupplier foreground,Consumer<PurpleSyncChatChange.State> changed) {
        if(!PurpleSyncChatChange.ACTIVATED){changed.accept(new PurpleSyncChatChange.State("Held","QualificationHeld",false));return null;}
        Holder holder=accounts.get(slot);
        if(holder==null||selection==null){changed.accept(new PurpleSyncChatChange.State("Held","OpeningChanged",false));return null;}
        return holder.flows.change(PurpleSyncChatChange.ACTIVATED,PurpleSyncChatMoveCleanup.ACTIVATED,
                factory(holder,foreground),selection,changed);
    }
    public static PurpleSyncChatFlows.Handle followFill(int slot,PurpleSyncChatMoveConsent.Selection selection,BooleanSupplier foreground,Consumer<PurpleSyncChatMoveConsent.State> changed){
        if(!PurpleSyncChatMoveConsent.ACTIVATED){changed.accept(new PurpleSyncChatMoveConsent.State("Held","QualificationHeld",false));return null;}
        Holder holder=accounts.get(slot);
        if(holder==null||selection==null){changed.accept(new PurpleSyncChatMoveConsent.State("Held","OpeningChanged",false));return null;}
        return holder.flows.consent(PurpleSyncChatMoveConsent.ACTIVATED,PurpleSyncChatMoveCleanup.ACTIVATED,
                factory(holder,foreground),selection,changed);
    }
    public static PurpleSyncChatFlows.Handle recover(int slot,PurpleSyncChatScreen.Frame opening,PurpleSyncChatMoveRecovery.ActionChoice choice,
            PurpleSyncChatScreen.ChangeSelection selection,BooleanSupplier foreground,Consumer<PurpleSyncChatChange.State> changed){
        if(!PurpleSyncChatMoveRecovery.ACTIVATED){changed.accept(PurpleSyncChatMoveRecovery.production());return null;}
        Holder holder=accounts.get(slot);
        if(holder==null){changed.accept(new PurpleSyncChatChange.State("Held","OpeningChanged",false));return null;}
        return holder.flows.recover(PurpleSyncChatMoveRecovery.ACTIVATED,PurpleSyncChatMoveCleanup.ACTIVATED,
                factory(holder,foreground),opening,choice,selection,changed);
    }
    private static PurpleSyncChatFlows.Factory factory(Holder holder,BooleanSupplier foreground){
        return new PurpleSyncChatFlows.Factory(){
            public PurpleSyncChatMoveRecovery.Port change(Consumer<PurpleSyncChatChange.State> changed){return new PurpleSyncChatFlowPort(holder,foreground,changed,null);}
            public PurpleSyncChatMoveConsent.Port consent(Consumer<PurpleSyncChatMoveConsent.State> changed){return new PurpleSyncChatFlowPort(holder,foreground,null,changed);}
        };
    }
    public static Runnable observeMove(int slot,BooleanSupplier foreground,Consumer<PurpleSyncChatChange.State> changed){
        Holder holder=account(slot);return holder!=null?holder.startup.observe(foreground,changed):()->{};
    }
    public static boolean moveRetryAvailable(int slot){Holder holder=accounts.get(slot);return holder!=null&&holder.startup.retryAvailable();}
    public static void retryMove(int slot){Holder holder=accounts.get(slot);if(holder!=null)holder.startup.retry();}
    static PurpleSyncChatMove move(PurpleSyncChatMove.Port injected) {
        return injected==null?null:new PurpleSyncChatMove(injected);
    }
    static void quiesceCarry(PurpleSyncChatOrigin origin,Runnable done) {
        AndroidUtilities.runOnUIThread(()->{
            Holder holder=accounts.get(origin.slot);
            if(holder!=null&&holder.env.origin.equals(origin))holder.service.quiesceCarry();
            PurpleSyncAuto.quiesceCarry(origin,done);
        });
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
            holder.magic.lookup(beforePost?PurpleSyncMagicChat.Trigger.post:PurpleSyncMagicChat.Trigger.auto,beforePost,
                    result->AndroidUtilities.runOnUIThread(()->{if(current.getAsBoolean())done.accept(lockCurrent.getAsBoolean()?new Status(result):unavailable());}));
        });
    }
    static void cancelOperation(int slot,Object owner){AndroidUtilities.runOnUIThread(()->{Holder holder=accounts.get(slot);if(holder!=null)holder.service.cancelBorrowed(owner);});}
    public static void identityChanged(int slot) {
        PurpleRpcRequests.identityChanged(slot);
        Holder retired=accounts.get(slot);
        AndroidUtilities.runOnUIThread(()->{if(retired!=null){accounts.remove(slot,retired);retired.close(true);}});
    }
    private static Status unavailable(){return new Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Paused,resolve(new ResolveInput(1)),"AccountUnavailable",false,true,0));}

    static final class Platform implements PurpleSyncChatService.Env {
        final PurpleSyncFileContext context;
        final PurpleSyncChatOrigin origin;
        final String authority;
        final PurpleSyncChatCache.IO cache;
        volatile PurpleSyncTelegramTransport inventory;
        volatile PurpleSyncChatClient inventoryFacts;
        Consumer<PurpleRpcRetry.Transient> waiting=ignored->{};
        volatile boolean setupPostingHeld;
        public boolean setupPostingHeld(){return setupPostingHeld;}
        public void transientWait(Consumer<PurpleRpcRetry.Transient> observer){waiting=observer;}
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
        public long rpcNow(){return System.currentTimeMillis();}
        public void presentation(PurpleRpcRetry.Transient value){PurpleRpcRequests.waiting(origin.slot,this,value);}
        public void ui(Runnable task){AndroidUtilities.runOnUIThread(task);}
        public void worker(Runnable task){queue().postRunnable(task);}
        public void delay(Runnable task,long millis){AndroidUtilities.runOnUIThread(task,millis);}
        public void cancelDelay(Runnable task){AndroidUtilities.cancelRunOnUIThread(task);}
        public PurpleSyncChatCache.IO cache(){return cache;}
        public PurpleSyncChatService.Binding binding(){
            setupPostingHeld=PurpleSyncChatSetupFlow.postingHeld(new File(context.files.syncDirectory,"sync-chat-setup.json"));
            if(!current())return new PurpleSyncChatService.Binding(false,false,Peer.self());
            File file=new File(context.files.syncDirectory,"state.json");PurpleAccountFileSnapshot state=PurpleAccountFileSnapshot.read(file);
            if(state.status==PurpleAccountFileSnapshot.Status.Missing)return new PurpleSyncChatService.Binding(true,false,Peer.self(),"","missing");
            if(state.status!=PurpleAccountFileSnapshot.Status.Present)return new PurpleSyncChatService.Binding(false,false,Peer.self());
            PurpleAccountSyncCore.Result inspected=PurpleAccountSyncCore.inspectState(state.bytes);
            PurpleAccountSyncCore.Result binding=PurpleAccountSyncCore.checkAccountBinding(origin.slot,state.bytes);
            if(!"Valid".equals(inspected.status)||!"None".equals(inspected.error)||!"Valid".equals(binding.status)||!"None".equals(binding.error)
                ||!state.matches(file)||!current())return new PurpleSyncChatService.Binding(false,false,Peer.self());
            if(!"Bound".equals(binding.verdict)&&!"Unbound".equals(binding.verdict))return new PurpleSyncChatService.Binding(false,false,Peer.self());
            try{JSONObject value=new JSONObject(new String(state.bytes,StandardCharsets.UTF_8));Peer chat=Peer.parse(value.optString("chat","self"),origin.userId);
                String identity=value.getString("install")+":"+value.getString("space")+":"+value.optString("binding_token","")+":"+value.optInt("version");
                return new PurpleSyncChatService.Binding(chat!=null,"Bound".equals(binding.verdict),chat!=null?chat:Peer.self(),identity,PurpleSyncRetainedFiles.hash(state.bytes));}
            catch(Exception error){return new PurpleSyncChatService.Binding(false,false,Peer.self());}
        }
        public PurpleSyncChatClient client(){PurpleSyncChatClient client=new PurpleSyncChatTelegram(origin,PurpleSyncChatTelegram.platform(origin,this::current));client.transientWait(waiting);return client;}
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
                scoped.transientWait(waiting);
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
