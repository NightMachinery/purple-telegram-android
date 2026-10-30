package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatStartup implements AutoCloseable {
    interface Port {
        boolean enabled();
        boolean current();
        void worker(Runnable task);
        Peer pending()throws Exception;
        void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done);
        PurpleSyncChatMove.Port carry(Consumer<PurpleSyncChatMove.Status> changed);
        void ordinary();
        void cancel();
    }
    private final Port port;
    private final PurpleSyncChatFlows flows;
    private final boolean cleanup;
    private volatile Consumer<PurpleSyncChatChange.State> observer;
    private volatile Object watch;
    private volatile BooleanSupplier foreground=()->false;
    private volatile PurpleSyncChatChange.State state;
    private volatile PurpleSyncChatFlows.Handle move;
    private volatile boolean closed;
    private boolean started;
    private volatile long generation;
    PurpleSyncChatStartup(PurpleSyncChat.Holder holder){
        this(new Port(){
            private PurpleSyncChatFlowPort reader;
            private PurpleSyncChatCarry carry;
            public boolean enabled(){return PurpleSyncChatChange.ACTIVATED||PurpleSyncChatMoveConsent.ACTIVATED||PurpleSyncChatMoveRecovery.ACTIVATED||PurpleSyncChatMoveCleanup.ACTIVATED;}
            public boolean current(){return holder.env.current();}
            public void worker(Runnable task){holder.env.worker(task);}
            public Peer pending()throws Exception {
                PurpleAccountFileSnapshot.worker();
                if(!PurpleSyncChatMoveStore.artifacts(holder.env.context.files.syncDirectory)){
                    PurpleSyncChatCache.Loaded cached=PurpleSyncChatCache.load(holder.env.cache,holder.env.origin,holder.env.authority);
                    if(!cached.ok||cached.data!=null&&cached.data.pendingMove())throw new IllegalStateException("MissingJournal");
                    return null;
                }
                try(PurpleSyncChatMoveStore store=new PurpleSyncChatMoveStore(holder.env.context,PurpleDevice.id())){
                    store.open();if(store.terminal())return null;
                    byte[] journal=store.journalBytes();if(journal==null)throw new IllegalStateException("MissingJournal");
                    store.checkNotPaused(holder.env.origin);
                    JSONObject value=new JSONObject(new String(journal,StandardCharsets.UTF_8));
                    if(!resumeEnabled(journal,holder.env.origin,holder.env.authority,PurpleSyncChatChange.ACTIVATED,
                            PurpleSyncChatMoveConsent.ACTIVATED,PurpleSyncChatMoveRecovery.ACTIVATED,PurpleSyncChatMoveCleanup.ACTIVATED))
                        throw new IllegalStateException("PendingFeatureDisabled");
                    Peer target=Peer.parse(value.getString("target"),holder.env.origin.userId);
                    if(target==null)throw new IllegalStateException("InvalidTarget");return target;
                }
            }
            public void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done){
                reader=new PurpleSyncChatFlowPort(holder,()->true,null,null);reader.inspect(target,done);
            }
            public PurpleSyncChatMove.Port carry(Consumer<PurpleSyncChatMove.Status> changed){
                carry=PurpleSyncChatCarry.production(holder,holder.env::current,()->true,changed);return carry;
            }
            public void ordinary(){holder.env.ui(()->{if(current()){holder.service.startSession();if(enabled())PurpleSyncAuto.sessionReady(holder.env.origin.slot);}});}
            public void cancel(){try{if(reader!=null)reader.cancel();}finally{if(carry!=null)carry.close();}}
        },new PurpleSyncChatFlows(),PurpleSyncChatMoveCleanup.ACTIVATED);
    }
    PurpleSyncChatStartup(Port port,PurpleSyncChatFlows flows,boolean cleanup){this.port=port;this.flows=flows;this.cleanup=cleanup;}
    static boolean resumeEnabled(byte[] canonical,PurpleSyncChatOrigin origin,String authority,boolean change,boolean consent,boolean recovery,boolean cleanup)throws Exception {
        JSONObject journal=new JSONObject(new String(canonical,StandardCharsets.UTF_8));
        int mode=journal.getInt("mode");
        if(mode==0&&(change||recovery)||(mode==1||mode==2)&&consent||mode==3&&recovery)return true;
        if(!cleanup)return false;
        JSONObject scope=json("account",origin.accountKey(),"source",journal.getString("source"),"target",journal.getString("target"),
                "journal",PurpleSyncRetainedBases.encode(canonical),"currentLogin",authority.substring(0,32));
        PurpleSyncChatMoveCore.Result next=PurpleSyncChatMoveCore.request("next",scope.toString().getBytes(StandardCharsets.UTF_8));
        return "Cleanup".equals(next.fields.optString("nextAction"));
    }
    private boolean live(long run){return !closed&&run==generation&&port.current();}
    void start(){if(started||closed)return;started=true;if(!port.enabled()){port.ordinary();return;}attempt();}
    private void attempt(){
        long run=++generation;
        port.worker(()->{
            if(!live(run))return;
            try{
                Peer target=port.pending();if(!live(run))return;
                if(target==null){port.ordinary();return;}
                publish(new PurpleSyncChatChange.State("Checking","PendingMove",false));
                port.inspect(target,info->port.worker(()->{
                    if(!live(run))return;
                    if(info==null||!target.equals(info.chat)||!info.readable()){publish(new PurpleSyncChatChange.State("Held","TargetFactsRequired",false));return;}
                    move=flows.resume(port.carry(status->{
                        if(!live(run))return;
                        publish(new PurpleSyncChatChange.State(status.terminal?"Complete":PurpleSyncChatChange.held(status)?"Held":"Carrying",status.reason,status.committed!=null,status.cleanup));
                        if(status.terminal){port.cancel();port.ordinary();}
                    }),info,cleanup);
                }));
            }catch(Exception|LinkageError error){if(live(run)){publish(new PurpleSyncChatChange.State("Held",error.getMessage()==null?"ResumeUnavailable":error.getMessage(),false));}}
        });
    }
    boolean retryAvailable(){PurpleSyncChatFlows.Handle active=move;return !closed&&active!=null&&active.current();}
    void retry(){port.worker(()->{if(closed||!port.enabled())return;++generation;try{if(move!=null)move.close();}finally{move=null;port.cancel();attempt();}});}
    Runnable observe(BooleanSupplier foreground,Consumer<PurpleSyncChatChange.State> observer){Object key=new Object();watch=key;this.foreground=foreground;this.observer=observer;if(state!=null)publish(state);start();return ()->{if(watch==key)detach();};}
    private void publish(PurpleSyncChatChange.State value){
        state=value;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{
            if(!closed&&state==value&&observer!=null&&foreground.getAsBoolean()){Object key=watch;try{observer.accept(value);}catch(RuntimeException|LinkageError error){if(watch==key)detach();}}
        });
    }
    void detach(){watch=null;observer=null;foreground=()->false;}
    @Override public void close(){if(closed)return;closed=true;++generation;detach();try{if(move!=null)move.close();}finally{try{flows.close();}finally{port.cancel();}}}
}
