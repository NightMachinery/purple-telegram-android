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
        State(String phase,String reason,boolean committed){this.phase=phase;this.reason=reason;this.committed=committed;}
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
    private final PurpleSyncChatScreen.ChangeSelection selection;
    private final PurpleSyncChatOrigin origin;
    private final String authority;
    private volatile boolean closed;
    private boolean started,created;
    private enum Decoration { NotStarted, Waiting, Held, Complete }
    private Decoration decoration=Decoration.NotStarted;
    private int decorationStep;
    private long ticket;
    private Peer target;
    private String operation;
    private PurpleSyncChatSetup provision;
    private PurpleSyncChatMove move;
    private PurpleSyncChatMove.Committed commit;
    PurpleSyncChatChange(Port injected,PurpleSyncChatScreen.ChangeSelection selection){
        port=injected;this.selection=selection;origin=injected.origin();authority=injected.authority();target=selection.offer.target;
    }
    private boolean live(){return !closed&&port.current()&&port.foreground()&&selection.current(origin,authority)&&origin.equals(port.origin())&&authority.equals(port.authority());}
    static State production(PurpleSyncChatScreen.ChangeSelection selection,PurpleSyncChatOrigin origin,String authority,BooleanSupplier current){
        return new State("Held",selection!=null&&selection.current(origin,authority)&&current.getAsBoolean()?"QualificationHeld":"OpeningChanged",false);
    }
    void start(){if(started||closed)return;started=true;port.worker(()->guard(()->fresh(selection.offer.createChannel)));}
    private interface Work{void run()throws Exception;}
    private void guard(Work task){try{PurpleAccountFileSnapshot.worker();if(live())task.run();}catch(Exception|LinkageError e){hold("ChangeUnavailable");}}
    private boolean publish(String phase,String reason,boolean committed){
        if(!live())return false;
        try{port.status(new State(phase,reason,committed));}catch(RuntimeException|LinkageError e){close();return false;}
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
        });
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
                    .put("openingCacheBytes",PurpleSyncRetainedBases.encode(selection.offer.cacheBytes)).put("openingStateStamp",selection.offer.stateStamp).put("mode","Switch").put("readerConsentAccepted",true).put("operationConfirmed",true).put("cleanupSettings",selection.cleanupSettings);
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
            });
            if(live())move.start(begin,info);
        })));
    }
    private void changed(PurpleSyncChatMove.Status status){
        if(!live())return;
        PurpleSyncChatMove.Committed proof=status.committed;
        if(proof!=null&&origin.equals(proof.origin)&&authority.equals(proof.authority)&&operation.equals(proof.operation)&&target.equals(proof.mark.chat))commit=proof;
        if(created&&commit!=null){
            if(decoration==Decoration.NotStarted){
                decoration=Decoration.Waiting;
                if(publish("Carrying",status.reason,true))port.worker(()->guard(this::decorate));
            }
            return;
        }
        publish(status.terminal?"Complete":"Carrying",status.reason,commit!=null);
    }
    private void decorate(){
        if(!live()||commit==null||!created||decoration!=Decoration.Waiting)return;
        if(decorationStep==3){decoration=Decoration.Complete;publish("Complete","",true);return;}
        String effect=new String[]{"Pin","Mute","Archive"}[decorationStep];
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
        ++ticket;decoration=Decoration.Held;hold(effect+"Unknown");
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
