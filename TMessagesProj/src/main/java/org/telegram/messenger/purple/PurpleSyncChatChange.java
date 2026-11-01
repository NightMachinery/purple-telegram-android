package org.telegram.messenger.purple;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatChange implements AutoCloseable {
    public static final boolean ACTIVATED=false;
    public static final class State {
        public final String phase,reason;
        public final boolean committed;
        public final PurpleSyncChatMoveCleanup.Progress cleanup;
        /** MarkRecoveryWaiting: the wall-clock time the next mark check is due; 0 otherwise. */
        public final long recheckAt;
        /** FinishedForFollow: the winner sync follows next, or null when none was named. */
        public final Cache supersededBy;
        public State(String phase,String reason,boolean committed){this(phase,reason,committed,null);}
        public State(String phase,String reason,boolean committed,PurpleSyncChatMoveCleanup.Progress cleanup){this(phase,reason,committed,cleanup,0,null);}
        State(String phase,String reason,boolean committed,PurpleSyncChatMoveCleanup.Progress cleanup,long recheckAt,Cache supersededBy){
            this.phase=phase;this.reason=reason;this.committed=committed;this.cleanup=cleanup;this.recheckAt=recheckAt;this.supersededBy=supersededBy;
        }
    }
    /**
     * The controller state for a move status. FinishedForFollow is its own terminal phase, never Complete: the
     * settings went to N, no Committed receipt exists, and sync follows the winner through the separate Follow offer
     * (D-M2B5-13). The waiting time and the winner travel with the state.
     */
    static State state(PurpleSyncChatMove.Status status,boolean committed){
        String phase=status.terminal?(status.reason.equals("FinishedForFollow")?"FinishedForFollow":"Complete"):held(status)?"Held":"Carrying";
        return new State(phase,status.reason,committed,status.cleanup,status.recheckAt,status.supersededBy);
    }
    interface Port {
        PurpleSyncChatOrigin origin();
        String authority();
        boolean current();
        boolean foreground();
        void worker(Runnable task);
        void resolve(Consumer<PurpleSyncChatScreen.Frame> done);
        void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done);
        PurpleSyncChatSetup.Env provision();
        JSONObject begin(PurpleSyncChatScreen.ChangeSelection selection,PurpleSyncChatClient.Info target)throws Exception;
        PurpleSyncChatMove.Port carry();
        void decorate(String effect,PurpleSyncChatMove.Committed proof,Consumer<Boolean> done);
        void cancel();
        void status(State state);
    }
    private final Port port;
    private boolean cleanupEnabled=true;
    private final PurpleSyncChatScreen.ChangeSelection selection;
    private final BooleanSupplier parentCurrent;
    private final PurpleSyncChatOrigin origin;
    private final String authority;
    private volatile boolean closed;
    private boolean started,created;
    private enum Decoration { NotStarted, Waiting, Held, Complete }
    private Decoration decoration=Decoration.NotStarted;
    private int decorationStep;
    private String decorationPhase="Carrying",decorationReason="";
    private long ticket;
    private Peer target;
    private String operation;
    private PurpleSyncChatSetup provision;
    private PurpleSyncChatMove move;
    private PurpleSyncChatMove.Committed commit;
    private PurpleSyncChatMoveCleanup.Progress cleanupProgress;
    PurpleSyncChatChange(Port injected,PurpleSyncChatScreen.ChangeSelection selection){this(injected,selection,()->true);}
    PurpleSyncChatChange(Port injected,PurpleSyncChatScreen.ChangeSelection selection,BooleanSupplier parentCurrent){
        this.parentCurrent=parentCurrent;port=injected;this.selection=selection;origin=injected.origin();authority=injected.authority();target=selection.offer.target;
    }
    void cleanupEnabled(boolean enabled){cleanupEnabled=enabled;}
    void retry(){port.worker(()->guard(()->{if(decoration==Decoration.Held)retryDecorations();else if(move!=null)move.retry();else if(provision!=null)provision.tryAgain();else fresh(selection.offer.createChannel);}));}
    private boolean live(){return !closed&&parentCurrent.getAsBoolean()&&port.current()&&port.foreground()&&selection.current(origin,authority)&&origin.equals(port.origin())&&authority.equals(port.authority());}
    static State production(PurpleSyncChatScreen.ChangeSelection selection,PurpleSyncChatOrigin origin,String authority,BooleanSupplier current){
        return new State("Held",selection!=null&&selection.current(origin,authority)&&current.getAsBoolean()?"QualificationHeld":"OpeningChanged",false);
    }
    void start(){if(started||closed)return;started=true;port.worker(()->guard(()->fresh(selection.offer.createChannel)));}
    private interface Work{void run()throws Exception;}
    private void guard(Work task){try{PurpleAccountFileSnapshot.worker();if(live())task.run();}catch(Exception|LinkageError e){hold("ChangeUnavailable");}}
    private boolean publish(String phase,String reason,boolean committed){return publish(new State(phase,reason,committed,cleanupProgress));}
    private boolean publish(State state){
        if(!live())return false;
        try{port.status(state);}catch(RuntimeException|LinkageError e){close();return false;}
        return live();
    }
    private void hold(String reason){publish("Held",reason,commit!=null);}
    private void fresh(boolean create)throws Exception {
        if(!publish("Checking","",false))return;long run=++ticket;
        port.resolve(frame->port.worker(()->guard(()->{
            if(run!=ticket)return;++ticket;
            if(!selection.offer.matches(frame)){hold("OpeningChanged");return;}
            if(create){provision();return;}
            inspect();
        })));
    }
    private void provision(){
        if(!publish("Provisioning","",false))return;
        PurpleSyncChatSetup.Env env=port.provision();if(!live()||env==null){hold("ProvisionUnavailable");return;}
        provision=PurpleSyncChatSetup.provision(env,selection,phase->{
            if(!live())return;
            if(phase==PurpleSyncChatSetup.Phase.Provisioned){
                PurpleSyncChatSetup.Journal journal=provision.journal();
                if(journal==null||!journal.purpose.equals("MoveProvision")||journal.step!=PurpleSyncChatSetup.Step.Provisioned||!journal.created||journal.mark!=null){hold("ProvisionUnproven");return;}
                created=true;target=journal.chat;operation=journal.operation.replace("-","");
                port.worker(()->guard(()->fresh(false)));
            }else if(phase==PurpleSyncChatSetup.Phase.Held||phase==PurpleSyncChatSetup.Phase.Limit)hold(provision.reason());
        },this::live);
        if(live())provision.start();
    }
    private void inspect(){
        long run=++ticket;
        port.inspect(target,info->port.worker(()->guard(()->{
            if(run!=ticket)return;++ticket;
            PurpleSyncChatScreen.Candidate candidate=new PurpleSyncChatScreen.Candidate(origin,target,info);
            if(!candidate.allowed||created&&(candidate.eligibility.readers.warningRequired||info.peer.kind!=Kind.Broadcast||!info.peer.facts.creator||!info.peer.facts.soleAdmin)
                    ||!created&&!selection.offer.candidate.same(candidate)){hold("TargetChanged");return;}
            JSONObject begin=port.begin(selection,info);if(!live()||begin==null){hold("OpeningChanged");return;}
            operation=operation==null?java.util.UUID.randomUUID().toString().replace("-",""):operation;
            begin.put("operation",operation).put("account",origin.accountKey()).put("source",selection.offer.source.token()).put("target",target.token())
                    .put("openingCacheBytes",PurpleSyncRetainedBases.encode(selection.offer.cacheBytes)).put("openingStateStamp",selection.offer.stateStamp).put("mode","Switch").put("readerConsentAccepted",true).put("operationConfirmed",true).put("cleanupSettings",selection.cleanupSettings).put("removeOldMarkAuthorized",selection.removeOldMark);
            if(!publish("Carrying","",false))return;
            PurpleSyncChatMove.Port delegate=port.carry();if(!live()||delegate==null){hold("CarryUnavailable");return;}
            move=new PurpleSyncChatMove(new PurpleSyncChatMove.Port(){
                public PurpleSyncChatOrigin origin(){return delegate.origin();}public String authority(){return delegate.authority();}
                public String operationKey(){return delegate.operationKey();}public boolean current(){return live()&&delegate.current();}public boolean foreground(){return live()&&delegate.foreground();}
                public void worker(Runnable task){delegate.worker(task);}public void quiesce(Runnable done){delegate.quiesce(done);}
                public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception{return delegate.open(()->live()&&current.getAsBoolean(),interrupted);}
                public void dispatch(PurpleSyncChatMove.Command command,Consumer<PurpleSyncChatMove.Reply> done){
                    if(live())delegate.dispatch(command,reply->{
                        if(!live())return;
                        if(command.action.equals("Fresh")&&command.journal()==null){
                            if(reply==null||reply.fresh==null){done.accept(null);return;}
                            Resolution actual=resolve(reply.fresh.resolution);
                            if(!java.util.Objects.equals(selection.offer.winner,actual.winner)||!java.util.Objects.equals(selection.offer.location,actual.cache)
                                    ||actual.cache==null||!selection.offer.source.equals(actual.cache.chat)||selection.offer.action!=actual.action){hold("OpeningChanged");move.close();return;}
                        }
                        if(command.action.equals("Fresh")&&command.journal()==null){
                            port.resolve(frame->port.worker(()->guard(()->{
                                if(!selection.offer.matches(frame)){hold("OpeningChanged");move.close();return;}
                                if(live())done.accept(reply);
                            })));
                        }else done.accept(reply);
                    });
                }
                public void cancel(PurpleSyncChatMove.Command command){delegate.cancel(command);}
                public void status(PurpleSyncChatMove.Status status){changed(status);}
                public long wall(){return delegate.wall();}
            },cleanupEnabled);
            if(live())move.start(begin,info);
        })));
    }
    private void changed(PurpleSyncChatMove.Status status){
        if(!live())return;
        cleanupProgress=status.cleanup;
        PurpleSyncChatMove.Committed proof=status.committed;
        if(proof!=null&&origin.equals(proof.origin)&&authority.equals(proof.authority)&&operation.equals(proof.operation)&&target.equals(proof.mark.chat))commit=proof;
        if(created&&commit!=null){
            if(decoration==Decoration.NotStarted){
                decoration=Decoration.Waiting;
                if(publish("Carrying",status.reason,true))port.worker(()->guard(this::decorate));
            }
            if(decoration==Decoration.Complete)publish(status.terminal?"Complete":"Carrying",status.reason,true);
            else if(decoration==Decoration.Held)publish("Held",decorationReason,true);
            else if(decoration==Decoration.Waiting)publish(decorationPhase,"",true);
            return;
        }
        publish(state(status,commit!=null));
    }
    static boolean held(PurpleSyncChatMove.Status state){return !state.terminal&&state.cleanup!=null&&state.cleanup.canAct&&!state.reason.isEmpty()&&!state.reason.equals("Running")&&!state.reason.equals("MovedCleanupPending");}
    private void decorate(){
        if(!live()||commit==null||!created||decoration!=Decoration.Waiting)return;
        if(decorationStep==3){decoration=Decoration.Complete;publish("Complete","",true);return;}
        String effect=new String[]{"Pin","Mute","Archive"}[decorationStep];decorationPhase=effect;
        if(!publish(effect,"",true))return;long run=++ticket;
        try{
            port.decorate(effect,commit,ok->port.worker(()->guard(()->{
                if(run!=ticket||decoration!=Decoration.Waiting)return;++ticket;
                if(!Boolean.TRUE.equals(ok)){decorationFailed(effect);return;}
                ++decorationStep;decorate();
            })));
        }catch(RuntimeException|LinkageError e){if(run==ticket&&decoration==Decoration.Waiting)decorationFailed(effect);}
    }
    private void decorationFailed(String effect){
        ++ticket;decoration=Decoration.Held;decorationReason=effect+"Unknown";hold(decorationReason);
    }
    void retryDecorations(){
        port.worker(()->guard(()->{
            if(decoration!=Decoration.Held||commit==null||!created)return;
            decoration=Decoration.Waiting;decorate();
        }));
    }
    @Override public void close(){
        if(closed)return;closed=true;++ticket;
        port.worker(()->{try{if(provision!=null)provision.close();}finally{try{if(move!=null)move.close();}finally{port.cancel();}}});
    }
}
