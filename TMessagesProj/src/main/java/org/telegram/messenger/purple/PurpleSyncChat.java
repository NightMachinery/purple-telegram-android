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
        public final String reason;
        public final boolean complete, postingHeld, newReaders;
        public final long retryAt;
        /** The chat to read and post in while phase is Use, otherwise null. */
        public final Peer chat;
        /** A Magic Purple Sync problem that pauses sync, and whether it still has to be shown once. */
        public final PurpleSyncMagicCore.Problem problem;
        public final boolean showProblem;
        Status(PurpleSyncMagicChat.Result result) {
            boolean use=result.kind==PurpleSyncMagicChat.Result.Kind.Use;
            phase=use?Phase.Use:Phase.Paused;
            reason=use?"Use":result.kind==PurpleSyncMagicChat.Result.Kind.Problem?result.problem.code():result.kind==PurpleSyncMagicChat.Result.Kind.Wait?"Waiting":"AccountUnavailable";
            newReaders=false;complete=use;postingHeld=!use;retryAt=0;
            chat=use?result.chat:null;problem=result.problem;showProblem=result.show;
        }
        private Status() { phase=Phase.Paused; reason="AccountUnavailable"; newReaders=false; complete=false; postingHeld=true; retryAt=0; chat=null; problem=PurpleSyncMagicCore.Problem.None; showProblem=false; }
        public boolean selfReadable() { return complete&&phase==Phase.Use&&Peer.self().equals(chat); }
        public boolean selfPostable() { return selfReadable()&&!postingHeld; }
        /** Sync may read {@code expected}: the lookup bound it. */
        public boolean readable(Peer expected) { return complete&&phase==Phase.Use&&chat!=null&&chat.equals(expected); }
        public boolean postable(Peer expected) { return readable(expected)&&!postingHeld; }
    }
    static final class Holder {
        final Platform env;
        final PurpleSyncMagicChat magic;
        /** The chat the lists were last rebuilt to hide; UI thread only. */
        Peer listsHide;
        Holder(Platform env) { this.env=env;magic=new PurpleSyncMagicChat(new PurpleSyncMagicTelegram(env)); }
        void close(boolean logout){magic.close();}
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
            holder.magic.lookup(PurpleSyncMagicChat.Trigger.startup,false,result->AndroidUtilities.runOnUIThread(()->{refreshLists(slot,holder);if(done!=null&&holder.env.current())done.accept(new Status(result));}));
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
            holder.magic.lookup(trigger,fresh,result->AndroidUtilities.runOnUIThread(()->{refreshLists(slot,holder);if(current.getAsBoolean())done.accept(new Status(result));}));
        });
    }
    /** The problem in the last status was shown to the user; it is not shown again until it changes. */
    /** Unix seconds before which a sync request for any of {@code methods} must not be sent, from the binding; 0 for none. */
    static long requestWait(int slot,String... methods){Holder holder=account(slot);return holder!=null?holder.magic.waitUntil(methods):0;}
    /** Keeps a flood wait of a sync request in the binding, so it is honoured after a restart too. */
    static void recordRequestWait(int slot,String method,long retryAt){Holder holder=account(slot);if(holder!=null)holder.magic.recordWait(method,retryAt);}
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
     * Whether the bound chat holds sync records, none of them in this device's sync space {@code space}: another
     * device set up sync there first. Automatic sync then runs first use ({@link PurpleSyncFirstUse}), which joins
     * or asks once, instead of pausing on a space_mismatch problem.
     */
    static boolean spaceMismatch(Peer chat,PurpleSyncInventory inventory,String space) {
        if(chat==null||Peer.self().equals(chat)||inventory==null||!chat.equals(inventory.chat)
                ||!inventory.scanComplete||space==null||space.isEmpty())return false;
        java.util.Set<String> spaces=PurpleSyncMagicManual.spaces(inventory);
        if(spaces==null||spaces.isEmpty())return false;
        if(spaces.size()==1||!spaces.contains(space))return !spaces.contains(space);
        // WHY: two fresh devices can each publish into an empty chat. Core selects one space for everyone, and the other
        // device moves to it at its next check, or the two stay apart for good. The selected space ignores the local file.
        PurpleSyncCore.FirstUse selected=PurpleSyncCore.firstUse(inventory,new PurpleSyncSettingsFile.Contents(PurpleSyncSettingsFile.Status.Absent,null),PurpleSyncCore.FirstUseChoice.None);
        return selected.isValid()&&selected.available&&!selected.space.isEmpty()&&!space.equals(selected.space);
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
            holder.magic.list(listed,ok->AndroidUtilities.runOnUIThread(()->{refreshLists(slot,holder);if(current.getAsBoolean())done.accept(ok);}));
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
    /** Rebuilds the chat lists when the chat they hide changed, so a new or shown sync chat leaves or returns at once. */
    private static void refreshLists(int slot,Holder holder) {
        Peer hide=holder.magic.hidden();
        if(java.util.Objects.equals(hide,holder.listsHide))return;
        holder.listsHide=hide;
        org.telegram.messenger.MessagesController.getInstance(slot).sortDialogs(null);
        org.telegram.messenger.NotificationCenter.getInstance(slot).postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload);
    }
    private interface MagicCall { void start(Holder holder,Consumer<PurpleSyncMagicChat.Result> reply); }
    private static void magicAction(int slot,BooleanSupplier current,Consumer<PurpleSyncMagicScreen.Frame> done,MagicCall call) {
        AndroidUtilities.runOnUIThread(()->{
            Holder holder=account(slot);if(holder==null){done.accept(PurpleSyncMagicScreen.frame(null,null));return;}
            call.start(holder,result->AndroidUtilities.runOnUIThread(()->{refreshLists(slot,holder);if(current.getAsBoolean())done.accept(PurpleSyncMagicScreen.frame(result,holder.magic.binding()));}));
        });
    }
    public static Status status(int slot) { Holder holder=accounts.get(slot);return holder!=null&&holder.env.current()?new Status(holder.magic.last()):unavailable(); }
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
    public static void identityChanged(int slot) {
        PurpleRpcRequests.identityChanged(slot);
        Holder retired=accounts.get(slot);
        AndroidUtilities.runOnUIThread(()->{if(retired!=null){accounts.remove(slot,retired);retired.close(true);}});
    }
    private static Status unavailable(){return new Status();}

    static final class Platform {
        final PurpleSyncFileContext context;
        final PurpleSyncChatOrigin origin;
        final String authority;
        Platform(int slot) throws Exception {
            context=new PurpleSyncFileContext(slot);
            if(context.origin==null||context.files==null)throw new IllegalStateException("sync chat context");
            authority=PurpleSyncChatOrigin.authority(PurpleArchiveLogin.context(slot));
            if(authority==null)throw new IllegalStateException("sync chat authority");
            origin=new PurpleSyncChatOrigin(slot,context.origin.key.startsWith("1-")?1:0,context.origin.userId,UUID.randomUUID().toString());
        }
        boolean identityCurrent(){return context.origin!=null&&context.origin.current();}
        public boolean current(){
            if(!context.current())return false;
            try{return authority.equals(PurpleSyncChatOrigin.authority(PurpleArchiveLogin.context(origin.slot)));}
            catch(Exception|LinkageError error){return false;}
        }
        public void ui(Runnable task){AndroidUtilities.runOnUIThread(task);}
        public void worker(Runnable task){queue().postRunnable(task);}
    }
    private PurpleSyncChat(){ }
}
