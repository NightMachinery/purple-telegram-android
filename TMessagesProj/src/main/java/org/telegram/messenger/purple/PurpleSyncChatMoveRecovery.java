package org.telegram.messenger.purple;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatMoveRecovery implements AutoCloseable {
    public static final boolean ACTIVATED=false;
    public enum ActionChoice { Create, Choose, RepairMark }
    interface Port extends PurpleSyncChatChange.Port {
        JSONObject repair(PurpleSyncChatScreen.Frame opening,PurpleSyncChatClient.Info info)throws Exception;
    }
    public static boolean recovery(PurpleSyncChatScreen.Frame frame){
        return frame!=null&&frame.status!=null&&(frame.status.resolution.action==Action.Searching||frame.status.resolution.action==Action.Lost);
    }
    static String held(PurpleSyncChatScreen.Frame frame){
        if(frame==null||!frame.status.complete)return "Searching";
        if(!recovery(frame)||!frame.canChange())return "OpeningChanged";
        if(frame.locallyPaused())return "LocalPause";
        return "";
    }
    public static String repairHeld(PurpleSyncChatScreen.Frame frame){
        String held=held(frame);if(!held.isEmpty())return held;
        if(frame.status.resolution.cache.markId<=0)return "MarkNotProvenGone";
        if(frame.oldRead!=DirectRead.MarkGone)return frame.oldRead==DirectRead.ChatGone?"ChatGone":"MarkNotProvenGone";
        if(frame.current==null||!frame.current.allowed)return frame.current==null?"ChatUnreadable":frame.current.reason;
        if(!frame.current.chat.equals(frame.status.resolution.cache.chat))return "WrongChat";
        if(frame.opening.data.readers==null||!frame.opening.data.accepted("config"))return "ReaderConsent";
        return readersHeld(frame.opening.data.readers,frame.current.info);
    }
    private static String readersHeld(PurpleSyncChatCache.ReadersSnapshot original,PurpleSyncChatClient.Info info){
        PurpleSyncChatCache.ReadersSnapshot actual=PurpleSyncChatCache.ReadersSnapshot.from(info);
        if(actual==null||original.kind!=actual.kind||original.memberHashes!=null&&actual.memberHashes==null)return "ReadersUnknown";
        if(original.creator&&!actual.creator||original.admin&&!actual.admin)return "ReaderRightsChanged";
        return "";
    }
    public static List<ActionChoice> actions(PurpleSyncChatScreen.Frame frame){return actions(frame,ACTIVATED);}
    static List<ActionChoice> actions(PurpleSyncChatScreen.Frame frame,boolean activated){
        if(!activated||!held(frame).isEmpty())return Collections.emptyList();
        return repairHeld(frame).isEmpty()?Arrays.asList(ActionChoice.Create,ActionChoice.Choose,ActionChoice.RepairMark):Arrays.asList(ActionChoice.Create,ActionChoice.Choose);
    }
    static PurpleSyncChatChange.State production(){return new PurpleSyncChatChange.State("Held","QualificationHeld",false);}
    private final Port port;
    private boolean cleanupEnabled=true;
    private final PurpleSyncChatScreen.Frame opening;
    private final PurpleSyncChatScreen.ChangeSelection selection;
    private final ActionChoice choice;
    private final PurpleSyncChatOrigin origin;
    private final String authority;
    private boolean started,busy;
    private volatile boolean closed;
    private long ticket;
    private PurpleSyncChatChange change;
    private PurpleSyncChatMove move;
    PurpleSyncChatMoveRecovery(Port port,PurpleSyncChatScreen.Frame opening,ActionChoice choice,PurpleSyncChatScreen.ChangeSelection selection){
        this.port=port;this.opening=opening;this.choice=choice;this.selection=selection;origin=port.origin();authority=port.authority();
    }
    void cleanupEnabled(boolean enabled){cleanupEnabled=enabled;}
    private boolean live(){return !closed&&port.current()&&port.foreground()&&origin.equals(port.origin())&&authority.equals(port.authority());}
    private interface Work{void run()throws Exception;}
    private void guard(Work work){try{PurpleAccountFileSnapshot.worker();if(live())work.run();else close();}catch(Exception|LinkageError e){busy=false;hold(e.getMessage()==null?"RecoveryUnavailable":e.getMessage());}}
    private boolean status(PurpleSyncChatChange.State state){
        if(!live())return false;try{port.status(state);}catch(RuntimeException|LinkageError e){close();return false;}return live();
    }
    private void hold(String reason){status(new PurpleSyncChatChange.State("Held",reason,false));}
    private boolean matches(PurpleSyncChatScreen.Frame frame){
        return held(frame).isEmpty()&&opening!=null&&origin.equals(frame.origin)&&origin.equals(opening.origin)
                &&opening.binding.same(frame.binding)&&opening.binding.stateStamp.equals(frame.binding.stateStamp)
                &&Arrays.equals(opening.opening.bytes,frame.opening.bytes)&&opening.status.resolution.action==frame.status.resolution.action
                &&Objects.equals(opening.status.resolution.cache,frame.status.resolution.cache)&&Objects.equals(opening.status.resolution.winner,frame.status.resolution.winner)
                &&opening.oldRead==frame.oldRead;
    }
    void start(){if(started||closed)return;started=true;port.worker(()->guard(this::fresh));}
    void retry(){port.worker(()->guard(()->{if(change!=null)change.retry();else if(move!=null)move.retry();else if(!busy)fresh();}));}
    private void fresh(){
        if(busy||change!=null||move!=null)return;
        if(!authority.matches("[0-9a-f]{64}")){hold("AccountUnbound");return;}
        String reason=held(opening);if(!reason.isEmpty()){hold(reason);return;}
        busy=true;long run=++ticket;
        if(!status(new PurpleSyncChatChange.State("Checking","",false)))return;
        port.resolve(frame->port.worker(()->guard(()->{
            if(run!=ticket)return;++ticket;busy=false;
            if(!matches(frame)){hold(frame!=null&&!frame.status.complete?"Searching":"OpeningChanged");return;}
            if(choice!=ActionChoice.RepairMark){
                if(selection==null||!selection.current(origin,authority)||!selection.offer.matches(frame)
                        ||selection.offer.createChannel!=(choice==ActionChoice.Create)){hold("OpeningChanged");return;}
                change=new PurpleSyncChatChange(port,selection,this::live);change.cleanupEnabled(cleanupEnabled);change.start();return;
            }
            reasonRepair(frame);
        })));
    }
    private void reasonRepair(PurpleSyncChatScreen.Frame frame)throws Exception {
        String reason=repairHeld(frame);if(!reason.isEmpty()){hold(reason);return;}
        long run=++ticket;busy=true;
        port.inspect(frame.status.resolution.cache.chat,info->port.worker(()->guard(()->{
            if(run!=ticket)return;++ticket;busy=false;
            PurpleSyncChatScreen.Candidate candidate=new PurpleSyncChatScreen.Candidate(origin,frame.status.resolution.cache.chat,info);
            if(!candidate.allowed){hold(candidate.reason);return;}
            String readers=readersHeld(frame.opening.data.readers,info);if(!readers.isEmpty()){hold(readers);return;}
            if(frame.opening.data.readers.newReaders(PurpleSyncChatCache.ReadersSnapshot.from(info))&&!status(new PurpleSyncChatChange.State("Warning","NewReaders",false)))return;
            JSONObject begin=port.repair(frame,info);if(!live())return;if(begin==null){hold("OpeningChanged");return;}
            Peer chat=frame.status.resolution.cache.chat;
            begin.put("operation",java.util.UUID.randomUUID().toString().replace("-","")).put("account",origin.accountKey())
                    .put("source",chat.token()).put("target",chat.token()).put("mode","RepairMark").put("readerConsentAccepted",true)
                    .put("operationConfirmed",true).put("cleanupSettings",false).put("removeOldMarkAuthorized",false)
                    .put("openingCacheBytes",PurpleSyncRetainedBases.encode(frame.opening.bytes)).put("openingStateStamp",frame.binding.stateStamp)
                    .put("markText",PurpleSyncChatSetupRequests.MARK).put("repairReaders",frame.opening.data.readers.json());
            PurpleSyncChatMove.Port delegate=port.carry();if(!live())return;if(delegate==null){hold("CarryUnavailable");return;}
            move=new PurpleSyncChatMove(new PurpleSyncChatMove.Port(){
                public PurpleSyncChatOrigin origin(){return delegate.origin();}public String authority(){return delegate.authority();}
                public String operationKey(){return delegate.operationKey();}public boolean current(){return live()&&delegate.current();}
                public boolean foreground(){return live()&&delegate.foreground();}public void worker(Runnable r){delegate.worker(r);}
                public void quiesce(Runnable r){delegate.quiesce(r);}
                public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception{return delegate.open(()->live()&&current.getAsBoolean(),interrupted);}
                public void dispatch(PurpleSyncChatMove.Command command,Consumer<PurpleSyncChatMove.Reply> done){
                    if(!live())return;
                    delegate.dispatch(command,reply->{
                        if(!live())return;
                        if(command.journal()==null){
                            if(reply==null||reply.fresh==null||reply.fresh.oldRead!=DirectRead.MarkGone||!reply.sourceAccess.equals("Readable")){hold("MarkNotProvenGone");move.close();return;}
                            Resolution r=resolve(reply.fresh.resolution);
                            if(r.action!=frame.status.resolution.action||!Objects.equals(r.cache,frame.status.resolution.cache)||r.winner!=null){hold("OpeningChanged");move.close();return;}
                        }
                        done.accept(reply);
                    });
                }
                public void cancel(PurpleSyncChatMove.Command c){delegate.cancel(c);}
                public void status(PurpleSyncChatMove.Status s){statusRecovery(s);}
            },cleanupEnabled);
            move.start(begin,info);
        })));
    }
    private void statusRecovery(PurpleSyncChatMove.Status s){status(new PurpleSyncChatChange.State(s.terminal?"Complete":PurpleSyncChatChange.held(s)?"Held":"Carrying",s.reason,s.committed!=null,s.cleanup));}
    @Override public void close(){
        if(closed)return;closed=true;++ticket;
        try{if(change!=null)change.close();}finally{try{if(move!=null)move.close();}finally{if(change==null)port.worker(port::cancel);}}
    }
}
