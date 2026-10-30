package org.telegram.messenger.purple;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatMoveConsent implements AutoCloseable {
    public static final boolean ACTIVATED=false;
    public static final class Offer {
        final PurpleSyncChatOrigin origin;
        final String authority,stateStamp,bindingIdentity;
        final PurpleSyncChatScreen.Control owner;
        final PurpleSyncChatScreen.Candidate candidate;
        final Cache old,winner;
        final byte[] cacheBytes;
        public final Action action;
        public final Peer source,target;
        public final String stream,title;
        public final boolean requiresReaders;
        Offer(PurpleSyncChatOrigin origin,String authority,PurpleSyncChatScreen.Control owner,PurpleSyncChatScreen.Frame frame,String stream,PurpleSyncChatScreen.Candidate candidate){
            this.origin=origin;this.authority=authority;this.owner=owner;this.candidate=candidate;this.stream=stream;
            action=frame.status.resolution.action;old=frame.status.resolution.cache;winner=frame.status.resolution.winner;
            source=action==Action.Follow?old.chat:frame.status.resolution.source;target=winner.chat;title=candidate.title;
            requiresReaders=candidate.eligibility.readers.warningRequired;
            stateStamp=frame.binding.stateStamp;bindingIdentity=frame.binding.identity;
            cacheBytes=frame.opening.bytes==null?null:frame.opening.bytes.clone();
        }
        boolean matches(PurpleSyncChatScreen.Frame frame){
            return frame!=null&&origin.equals(frame.origin)&&frame.canFollowFill()&&action==frame.status.resolution.action
                    &&Objects.equals(old,frame.status.resolution.cache)&&winner.equals(frame.status.resolution.winner)
                    &&(action!=Action.Fill||source.equals(frame.status.resolution.source))
                    &&stateStamp.equals(frame.binding.stateStamp)&&bindingIdentity.equals(frame.binding.identity)&&Arrays.equals(cacheBytes,frame.opening.bytes);
        }
        boolean current(PurpleSyncChatOrigin origin,String authority){return owner.current()&&this.origin.equals(origin)&&this.authority.equals(authority);}
    }
    public static final class Selection {
        final Offer offer;
        private Selection(Offer offer){this.offer=offer;}
        static Selection accepted(Offer offer){return new Selection(offer);}
        boolean current(PurpleSyncChatOrigin origin,String authority){return offer.current(origin,authority);}
    }
    public static final class State {
        public final String phase,reason;
        public final boolean committed;
        public final PurpleSyncChatMoveCleanup.Progress cleanup;
        State(String phase,String reason,boolean committed){this(phase,reason,committed,null);}
        State(String phase,String reason,boolean committed,PurpleSyncChatMoveCleanup.Progress cleanup){this.phase=phase;this.reason=reason;this.committed=committed;this.cleanup=cleanup;}
    }
    public static String disclosure(Offer offer,PurpleSyncChatScreen.Text text){
        String title=offer.target.kind==PeerKind.Self?text.get("Saved"):offer.title;
        String value=text.get(offer.action==Action.Follow?"FollowAbout":"FillAbout",title)+"\n\n"+text.get(offer.stream.equals("config")?"Cloud":"LibraryCloud",title);
        if(offer.stream.equals("library"))value+="\n\n"+text.get("Cloud",title)+"\n"+text.get("SettingsReaders");
        if(offer.requiresReaders){
            Readers readers=offer.candidate.eligibility.readers;
            value+="\n\n"+text.get(readers.botOwner?"BotReader":readers.privateRecipient?"PrivateReader":"MembersReader",title,readers.otherMembers==null?text.get("UnknownCount"):readers.otherMembers.toString());
            value+="\n"+text.get(offer.stream.equals("config")?"SettingsReaders":"LibraryReaders")+"\n"+text.get("ReaderConsequences");
        }
        return value;
    }
    static State production(Selection selection,PurpleSyncChatOrigin origin,String authority,BooleanSupplier current){
        return new State("Held",selection!=null&&selection.current(origin,authority)&&current.getAsBoolean()?"QualificationHeld":"OpeningChanged",false);
    }
    interface Port {
        PurpleSyncChatOrigin origin();
        String authority();
        boolean current();
        boolean foreground();
        void worker(Runnable task);
        void resolve(String stream,Consumer<PurpleSyncChatScreen.Frame> done);
        void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done);
        JSONObject begin(Selection selection)throws Exception;
        PurpleSyncChatMove.Port carry();
        void cancel();
        void status(State state);
    }
    private final Port port;
    private boolean cleanupEnabled=true;
    private final Selection selection;
    private final PurpleSyncChatOrigin origin;
    private final String authority;
    private volatile boolean closed;
    private boolean started;
    private long ticket;
    private PurpleSyncChatMove move;
    PurpleSyncChatMoveConsent(Port port,Selection selection){this.port=port;this.selection=selection;origin=port.origin();authority=port.authority();}
    void cleanupEnabled(boolean enabled){cleanupEnabled=enabled;}
    void retry(){port.worker(()->guard(()->{if(move!=null)move.retry();else{started=false;start();}}));}
    private boolean live(){return !closed&&port.current()&&port.foreground()&&origin.equals(port.origin())&&authority.equals(port.authority())&&selection.current(origin,authority);}
    private interface Work{void run()throws Exception;}
    private void guard(Work work){try{PurpleAccountFileSnapshot.worker();if(live())work.run();}catch(Exception|LinkageError e){hold("FollowUnavailable");}}
    private boolean publish(State state){if(!live())return false;try{port.status(state);}catch(RuntimeException|LinkageError e){close();return false;}return live();}
    private void hold(String reason){publish(new State("Held",reason,false));}
    void start(){
        if(started||closed)return;started=true;port.worker(()->guard(()->{
            if(!publish(new State("Checking","",false)))return;long run=++ticket;
            port.resolve(selection.offer.stream,frame->port.worker(()->guard(()->{
                if(run!=ticket)return;++ticket;
                if(!selection.offer.matches(frame)){hold("OpeningChanged");return;}
                inspect();
            })));
        }));
    }
    private void inspect(){
        long run=++ticket;port.inspect(selection.offer.target,info->port.worker(()->guard(()->{
            if(run!=ticket)return;++ticket;
            if(!selection.offer.candidate.same(new PurpleSyncChatScreen.Candidate(origin,selection.offer.target,info))){hold("ReaderConsentChanged");return;}
            JSONObject begin=port.begin(selection);if(!live()||begin==null){hold("OpeningChanged");return;}
            Offer offer=selection.offer;
            begin.put("operation",java.util.UUID.randomUUID().toString().replace("-","")).put("account",origin.accountKey()).put("source",offer.source.token()).put("target",offer.target.token())
                    .put("mode",offer.action.name()).put("openingStateStamp",offer.stateStamp).put("consentStream",offer.stream).put("readerConsentAccepted",true).put("operationConfirmed",true);
            if(offer.cacheBytes==null)begin.put("openingCacheAbsent",true);else begin.put("openingCacheBytes",PurpleSyncRetainedBases.encode(offer.cacheBytes));
            if(!publish(new State("Carrying","",false)))return;
            PurpleSyncChatMove.Port delegate=port.carry();if(!live()||delegate==null){hold("CarryUnavailable");return;}
            if(!origin.equals(delegate.origin())||!authority.equals(delegate.authority())){hold("AccountChanged");return;}
            move=new PurpleSyncChatMove(new PurpleSyncChatMove.Port(){
                public PurpleSyncChatOrigin origin(){return delegate.origin();}public String authority(){return delegate.authority();}
                public String operationKey(){return delegate.operationKey();}public boolean current(){return live()&&delegate.current();}public boolean foreground(){return live()&&delegate.foreground();}
                public void worker(Runnable task){delegate.worker(task);}public void quiesce(Runnable done){delegate.quiesce(done);}
                public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception{return delegate.open(()->live()&&current.getAsBoolean(),interrupted);}
                public void dispatch(PurpleSyncChatMove.Command command,Consumer<PurpleSyncChatMove.Reply> done){
                    if(!live())return;delegate.dispatch(command,reply->{
                        if(!live())return;
                        if(command.action.equals("Fresh")&&command.journal()==null){
                            if(reply==null||reply.fresh==null){done.accept(null);return;}
                            if(!offer.candidate.same(new PurpleSyncChatScreen.Candidate(origin,offer.target,reply.fresh.target))){hold("ReaderConsentChanged");move.close();return;}
                            Resolution actual=resolve(reply.fresh.resolution);
                            if(actual.action!=offer.action||!Objects.equals(actual.cache,offer.old)||!Objects.equals(actual.winner,offer.winner)||offer.action==Action.Fill&&!offer.source.equals(actual.source)){hold("OpeningChanged");move.close();return;}
                            port.resolve(offer.stream,frame->port.worker(()->guard(()->{
                                if(!offer.matches(frame)){hold("OpeningChanged");move.close();return;}done.accept(reply);
                            })));
                        }else done.accept(reply);
                    });
                }
                public void cancel(PurpleSyncChatMove.Command command){delegate.cancel(command);}
                public void status(PurpleSyncChatMove.Status status){publish(new State(status.terminal?"Complete":PurpleSyncChatChange.held(status)?"Held":"Carrying",status.reason,status.committed!=null,status.cleanup));}
            },cleanupEnabled);
            if(live())move.start(begin,info);
        })));
    }
    @Override public void close(){if(closed)return;closed=true;++ticket;port.worker(()->{try{if(move!=null)move.close();}finally{try{port.cancel();}catch(RuntimeException|LinkageError ignored){}}});}
}
