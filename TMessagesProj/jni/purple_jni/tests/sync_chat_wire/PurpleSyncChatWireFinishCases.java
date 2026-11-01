package org.telegram.messenger.purple;

import java.io.File;
import java.util.*;
import java.util.function.*;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatCarryCases.*;
import org.telegram.messenger.purple.PurpleSyncChatMoveExecutorCases.Frame;

/**
 * FinishThenFollow on the screen, through the real Change, Move, carry dispatcher and core with the switches injected ON
 * (the production constants stay OFF): the status line and actions after a losing mark and after an uncertain mark
 * attempt, the 30/120/600 checks on Retry, the overdue check at startup resume, and the separate Follow that needs its
 * own consent. No timer runs a check yet: the foreground cadence is a follow-up.
 */
final class PurpleSyncChatWireFinishCases {
    static int checks;
    static void must(boolean ok,String reason){++checks;if(!ok)throw new AssertionError(reason);}
    static final Map<String,String> strings=new HashMap<>();
    static String text(String key,Object... args){String value=strings.get("PurpleSyncChat"+key);must(value!=null,"string "+key);return args.length==0?value:String.format(Locale.ROOT,value,args);}
    /** Disk-backed binding and cache; an uncertain attempt answers the mark send with an error. */
    static final class FlowNet extends Net {
        boolean uncertain;
        FlowNet(Frame f)throws Exception{super(f);if(!f.files.cache.exists())PurpleModernSyncCases.write(f.files.cache,PurpleSyncChatCache.encode(ORIGIN,f.authority,cache));}
        @Override public PurpleSyncChatService.Binding binding(){try{return diskBinding(f);}catch(Exception e){throw new RuntimeException(e);}}
        @Override public PurpleSyncChatCache.Loaded cache(){return PurpleSyncChatCache.load(PurpleSyncChatCache.file(f.files.cache),ORIGIN,f.authority);}
        @Override public int send(TLObject request,PurpleSyncChatTelegram.Reply done){
            if(uncertain&&request instanceof TLRPC.TL_messages_sendMessage){requests.add(request);int id=++token;f.tasks.addLast(()->done.accept(null,error("WIRE_UNCERTAIN")));return id;}
            return super.send(request,done);
        }
        @Override TLObject write(TLObject request){
            if(request instanceof TLRPC.TL_messages_forwardMessages){
                TLRPC.TL_messages_forwardMessages q=(TLRPC.TL_messages_forwardMessages)request;Peer from=chat(q.from_peer),to=chat(q.to_peer);int source=q.id.get(0);
                byte[] body=(from.equals(O)?f.oldBodies:f.newBodies).get(source);must(body!=null,"exact forwarded source body");
                List<TLRPC.Message> records=to.equals(O)?f.oldRecords:f.newRecords;Map<Integer,byte[]> bodies=to.equals(O)?f.oldBodies:f.newBodies;int id=55;while(bodies.containsKey(id))++id;
                TLRPC.TL_message m=doc(to,id,1500,body);records.add(m);bodies.put(id,body);++f.effects;return ack(to,q.random_id.get(0),m);
            }
            if(request instanceof TLRPC.TL_messages_deleteMessages||request instanceof TLRPC.TL_channels_deleteMessages){
                List<Integer> ids=request instanceof TLRPC.TL_messages_deleteMessages?((TLRPC.TL_messages_deleteMessages)request).id:((TLRPC.TL_channels_deleteMessages)request).id;
                int id=ids.get(0);boolean newer=f.newRecords.stream().anyMatch(m->m.id==id);Peer at=newer?f.target:O;deleted.put(id,at);(newer?f.newRecords:f.oldRecords).removeIf(m->m.id==id);(newer?f.newBodies:f.oldBodies).remove(id);if(at.equals(O)&&id==oldMark.id)oldMarkPresent=false;
                TLRPC.TL_messages_affectedMessages answer=new TLRPC.TL_messages_affectedMessages();answer.pts=10;answer.pts_count=1;return answer;
            }
            return super.write(request);
        }
    }
    /** The run's dispatcher port with the frame's clock as wall time; {@code also} sees each status. */
    static PurpleSyncChatMove.Port clockPort(Run run,Frame frame,Consumer<PurpleSyncChatMove.Status> also){
        PurpleSyncChatMove.Port d=run.port();return new PurpleSyncChatMove.Port(){
            public PurpleSyncChatOrigin origin(){return d.origin();}public String authority(){return d.authority();}public String operationKey(){return d.operationKey();}public boolean current(){return d.current();}public boolean foreground(){return d.foreground();}
            public long wall(){return frame.clock;}public void worker(Runnable r){d.worker(r);}public void quiesce(Runnable r){d.quiesce(r);}public PurpleSyncChatMoveStore open(BooleanSupplier c,boolean i)throws Exception{return d.open(c,i);}
            public void dispatch(PurpleSyncChatMove.Command c,Consumer<PurpleSyncChatMove.Reply> done){d.dispatch(c,done);}public void cancel(PurpleSyncChatMove.Command c){d.cancel(c);}
            public void status(PurpleSyncChatMove.Status st){d.status(st);if(also!=null)also.accept(st);}
        };
    }
    static void pump(PurpleSyncChatChangeCases.Flow f,Run r){for(int i=0;i<1000;++i){f.drain();r.net.drain();if(f.tasks.isEmpty()&&f.carry.tasks.isEmpty()&&r.net.clouds.stream().allMatch(c->c.queue.isEmpty()))return;}throw new AssertionError("combined worker bound");}
    static void ui(){
        android.os.Looper.mainThread=Thread.currentThread();
        try{while(!org.telegram.messenger.AndroidUtilities.pending.isEmpty())org.telegram.messenger.AndroidUtilities.pending.remove(0).run();}
        finally{android.os.Looper.mainThread=null;}
    }
    static PurpleSyncChatFlows.Factory factory(PurpleSyncChatChangeCases.Flow f,Run run,PurpleSyncChatMove.Port movePort){return new PurpleSyncChatFlows.Factory(){
        public PurpleSyncChatMoveConsent.Port consent(Consumer<PurpleSyncChatMoveConsent.State> changed){throw new AssertionError("Change only");}
        public PurpleSyncChatMoveRecovery.Port change(Consumer<PurpleSyncChatChange.State> changed){return new PurpleSyncChatMoveRecovery.Port(){
            public PurpleSyncChatOrigin origin(){return f.origin();}public String authority(){return f.authority();}public boolean current(){return f.current();}public boolean foreground(){return f.foreground();}public void worker(Runnable task){f.worker(task);}
            public void resolve(Consumer<PurpleSyncChatScreen.Frame> done){run.carry.freshFrame("config",done);}public void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done){f.inspect(target,done);}public PurpleSyncChatSetup.Env provision(){return f.provision();}
            public JSONObject begin(PurpleSyncChatScreen.ChangeSelection selected,PurpleSyncChatClient.Info info)throws Exception{return f.begin(selected,info);}public JSONObject repair(PurpleSyncChatScreen.Frame frame,PurpleSyncChatClient.Info info){throw new AssertionError("not Repair");}
            public PurpleSyncChatMove.Port carry(){return movePort;}public void decorate(String effect,PurpleSyncChatMove.Committed proof,Consumer<Boolean> done){f.decorate(effect,proof,done);}public void cancel(){run.carry.close();f.cancel();}public void status(PurpleSyncChatChange.State state){f.status(state);changed.accept(state);}
        };}
    };}
    /** A process start after the given wall time: the real Startup resumes the persisted journal through a fresh dispatcher. */
    static final class Boot implements PurpleSyncChatStartup.Port {
        final Frame f;final FlowNet net;final Run run;final ArrayDeque<Runnable> tasks=new ArrayDeque<>();final List<PurpleSyncChatChange.State> seen=new ArrayList<>();
        final PurpleSyncChatStartup startup;int ordinary;
        Boot(Frame from,long clock)throws Exception{
            f=new Frame(from);f.clock=clock;f.targetInfo=from.targetInfo;net=new FlowNet(f);net.uncertain=true;run=new Run(f,net);
            startup=new PurpleSyncChatStartup(this,new PurpleSyncChatFlows(),false);startup.observe(()->true,seen::add);drain();
        }
        public boolean enabled(){return true;}public boolean current(){return f.live;}public void worker(Runnable task){tasks.add(task);}
        public Peer pending(){return f.target;}public void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done){tasks.add(()->done.accept(net.infoOf(target)));}
        public PurpleSyncChatMove.Port carry(Consumer<PurpleSyncChatMove.Status> changed){return clockPort(run,f,changed);}
        public void ordinary(){++ordinary;}public void cancel(){}
        void drain(){
            for(int i=0;i<1000;++i){
                while(!tasks.isEmpty())tasks.remove().run();
                while(!f.tasks.isEmpty())f.tasks.remove().run();
                net.drain();ui();
                if(tasks.isEmpty()&&f.tasks.isEmpty()&&net.clouds.stream().allMatch(c->c.queue.isEmpty()))return;
            }
            throw new AssertionError("startup worker bound");
        }
        PurpleSyncChatChange.State last(){return seen.get(seen.size()-1);}
        void close(){startup.close();drain();run.close();}
    }
    static PurpleSyncChatChange.State last(List<PurpleSyncChatChange.State> seen){return seen.get(seen.size()-1);}
    static int recoveryChecks(Frame f)throws Exception{return f.diskTransport().getJSONObject("mark_recovery").getInt("checks");}
    static long recoveryDue(Frame f)throws Exception{return Long.parseLong(f.diskTransport().getJSONObject("mark_recovery").getString("next_at"));}
    /** The screen while a mark check waits: Held, its own line with the persisted due time, and Retry. */
    static void waiting(PurpleSyncChatChange.State st,boolean live,long due,String step){
        must(st.phase.equals("Held")&&st.reason.equals("MarkRecoveryWaiting")&&st.recheckAt==due,"waiting carries the persisted due time "+step+" "+st.phase+" "+st.reason+" "+st.recheckAt+" "+due);
        must(PurpleSyncChatMoveView.line(st).equals("MoveRecoveryWaiting")&&PurpleSyncChatMoveView.retry(true,st,live)&&!PurpleSyncChatMoveView.terminal(st),"waiting shows its line and Retry "+step);
        System.out.println("WIRE_FINISH_UI step="+step+" phase="+st.phase+" reason="+st.reason+" recheckAt="+st.recheckAt+" retry=true");
    }
    /** The screen after FinishThenFollow: its own phase and line naming Follow, no Retry, and a reload that shows the Follow offer. */
    static void finished(PurpleSyncChatChange.State st,boolean live,Cache winner,String step){
        must(st.phase.equals("FinishedForFollow")&&st.reason.equals("FinishedForFollow")&&!st.committed,"FinishedForFollow keeps its own state "+step+" "+st.phase+" "+st.reason);
        must(st.supersededBy!=null&&st.supersededBy.chat.equals(winner.chat)&&st.supersededBy.markId==winner.markId&&st.supersededBy.markDate==winner.markDate,"state names the winner Follow goes to "+step);
        String line=PurpleSyncChatMoveView.line(st),shown=text(line);
        must(line.equals("MoveFinishedForFollow")&&!shown.equals(text("ChangeComplete"))&&shown.contains("Follow"),"line points to Follow, not to a changed sync chat "+step);
        must(PurpleSyncChatMoveView.terminal(st)&&!PurpleSyncChatMoveView.retry(true,st,live),"terminal reloads the frame and offers no Retry "+step);
        System.out.println("WIRE_FINISH_UI step="+step+" phase="+st.phase+" winner="+(st.supersededBy.chat.equals(O)?"O":"other")+"/"+st.supersededBy.markId+" retry=false line="+line);
    }
    static void lines(){
        Cache w=new Cache(O,91,2500);
        must(PurpleSyncChatMoveView.line(new PurpleSyncChatChange.State("FinishedForFollow","FinishedForFollow",false,null,0,w)).equals("MoveFinishedForFollow"),"named winner: Follow line");
        must(PurpleSyncChatMoveView.line(new PurpleSyncChatChange.State("FinishedForFollow","FinishedForFollow",false,null,0,null)).equals("MoveFinishedSearching"),"no winner: searching line");
        must(PurpleSyncChatMoveView.line(new PurpleSyncChatChange.State("Held","MarkRecoveryWaiting",false,null,0,null)).equals("MoveHeld"),"waiting without a due time is plain Held");
        must(PurpleSyncChatMoveView.line(new PurpleSyncChatChange.State("Held","MarkRecoveryWaiting",false,null,5,null)).equals("MoveRecoveryWaiting"),"waiting with a due time");
        must(PurpleSyncChatMoveView.line(new PurpleSyncChatChange.State("Held","TargetFactsRequired",false)).equals("MoveHeld"),"other holds keep the paused line");
        must(PurpleSyncChatMoveView.line(new PurpleSyncChatChange.State("Complete","Done",true)).equals("ChangeComplete"),"a committed move keeps its line");
        must(PurpleSyncChatMoveView.line(new PurpleSyncChatChange.State("Carrying","Mark",false)).equals("MoveWorking"),"carrying");
        must(!PurpleSyncChatMoveView.terminal(null)&&!PurpleSyncChatMoveView.terminal(new PurpleSyncChatChange.State("Held","MarkRecoveryWaiting",false)),"held is not terminal");
        for(String key:new String[]{"MoveFinishedForFollow","MoveFinishedSearching","MoveHeld","ChangeComplete","MoveWorking"})text(key);
        String waiting=text("MoveRecoveryWaiting","12:30");must(waiting.contains("12:30")&&waiting.contains("Retry"),"waiting text names the due time and Retry");
        must(!text("MoveFinishedSearching").equals(text("ChangeComplete")),"no-winner text does not claim a changed sync chat");
    }
    static void separateFollow(Frame original,TLRPC.Message winner,String oldOperation)throws Exception{
        Frame f=new Frame(original);FlowNet net=new FlowNet(f);if(winner!=null)net.extra.add(winner);Run run=new Run(f,net);
        PurpleSyncChatScreen.Frame frame=PurpleSyncChatWireRenewalCases.frame(run);must(frame.canFollowFill()&&frame.status.resolution.action==Action.Follow,"the reloaded frame offers the separate Follow");
        PurpleSyncChatChangeCases.Screen screen=new PurpleSyncChatChangeCases.Screen();screen.frame=frame;screen.target=net.infoOf(frame.status.resolution.winner.chat);
        PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(screen,false);PurpleSyncChatMoveConsent.Offer[] offered={null};control.offerFollowFill("config",o->offered[0]=o);must(offered[0]!=null,"fresh Follow offer");
        must(f.diskJournal().getString("operation").equals(oldOperation)&&net.count(TLRPC.TL_messages_sendMessage.class)==0&&net.deleted.isEmpty(),"the offer alone starts no operation and sends nothing");
        must(!PurpleSyncChatMoveConsent.disclosure(offered[0],PurpleSyncChatWireFinishCases::text).isEmpty(),"the Follow offer shows its disclosure");
        PurpleSyncChatMoveConsent.Selection[] selected={null};control.acceptFollowFill(offered[0],true,v->selected[0]=v);must(selected[0]!=null,"explicit Follow consent");
        List<PurpleSyncChatMoveConsent.State> states=new ArrayList<>();PurpleSyncChatFlows owner=new PurpleSyncChatFlows();PurpleSyncChatMove.Port port=clockPort(run,f,null);
        PurpleSyncChatFlows.Factory factory=new PurpleSyncChatFlows.Factory(){
            public PurpleSyncChatMoveRecovery.Port change(Consumer<PurpleSyncChatChange.State> c){throw new AssertionError("Follow only");}
            public PurpleSyncChatMoveConsent.Port consent(Consumer<PurpleSyncChatMoveConsent.State> changed){return new PurpleSyncChatMoveConsent.Port(){
                public PurpleSyncChatOrigin origin(){return ORIGIN;}public String authority(){return f.authority;}public boolean current(){return f.live;}public boolean foreground(){return f.foreground;}public void worker(Runnable task){f.worker(task);}
                public void resolve(String stream,Consumer<PurpleSyncChatScreen.Frame> done){run.carry.freshFrame(stream,done);}public void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done){f.worker(()->done.accept(net.infoOf(target)));}
                public JSONObject begin(PurpleSyncChatMoveConsent.Selection sel)throws Exception{return PurpleSyncChatFlowPort.beginning(f.files.binding);}public PurpleSyncChatMove.Port carry(){return port;}public void cancel(){run.carry.close();}public void status(PurpleSyncChatMoveConsent.State st){changed.accept(st);}
            };}
        };
        owner.consent(true,false,factory,selected[0],states::add);net.drain();
        // With cleanup held, this base stops a committed Follow before its late-post cleanup (Retry or Skip); the flip's captured admission finishes it Done.
        PurpleSyncChatMoveConsent.State end=states.get(states.size()-1);must(end.committed&&(end.phase.equals("Complete")||end.reason.equals("CleanupDisabled")),"the consented Follow commits "+end.phase+" "+end.reason+run.trace());
        JSONObject j=f.diskJournal();must(j.getInt("mode")==1&&!j.getString("operation").equals(oldOperation),"Follow is its own operation");
        must(net.count(TLRPC.TL_messages_sendMessage.class)==0&&net.deleted.isEmpty()&&f.oldRecords.stream().anyMatch(m->m.id==2),"Follow sends no mark and deletes nothing");
        System.out.println("WIRE_FINISH_FOLLOW phase="+end.phase+" reason="+end.reason+" newOperation=true marks=0 deletes=0");
        owner.close();control.close();run.close();
    }
    static void scenario(boolean uncertain)throws Exception{
        PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();FlowNet net=new FlowNet(f.carry);net.uncertain=uncertain;
        TLRPC.TL_message w=uncertain?null:mark(O,91,2500);if(w!=null){net.rival=w;net.rivalAtRead=true;}
        Run run=new Run(f.carry,net);f.screen.frame=PurpleSyncChatWireRenewalCases.frame(run);f.screen.target=net.infoOf(f.carry.target);
        PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(f.screen,false);PurpleSyncChatScreen.ChangeOffer[] offered={null};control.offerChange(f.carry.target,false,o->offered[0]=o);must(offered[0]!=null,"real Change offer");
        PurpleSyncChatScreen.ChangeSelection[] selected={null};control.acceptChange(offered[0],true,true,false,false,o->selected[0]=o);must(selected[0]!=null,"real Change selection");
        List<PurpleSyncChatChange.State> seen=new ArrayList<>();PurpleSyncChatFlows owner=new PurpleSyncChatFlows();
        PurpleSyncChatFlows.Handle handle=owner.change(true,false,factory(f,run,clockPort(run,f.carry,null)),selected[0],seen::add);pump(f,run);
        Frame state=f.carry;String operation=f.carry.diskJournal().getString("operation");PurpleSyncChatChange.State end;FlowNet marks=net;Boot boot=null;
        if(uncertain){
            if(!last(seen).reason.equals("MarkRecoveryWaiting")){handle.retry();pump(f,run);}
            for(long wait:new long[]{30_000,120_000}){
                long due=recoveryDue(f.carry);int count=recoveryChecks(f.carry);must(due==f.carry.clock+wait,"exact persisted cadence "+wait);waiting(last(seen),handle.current(),due,"waiting-"+wait);
                f.carry.clock=due-1;handle.retry();pump(f,run);
                must(recoveryChecks(f.carry)==count&&recoveryDue(f.carry)==due,"Retry before the due time neither counts nor moves the due time "+wait);waiting(last(seen),handle.current(),due,"early-retry-"+wait);
                int commands=run.commands.size();f.carry.clock=due;pump(f,run);
                must(run.commands.size()==commands&&recoveryChecks(f.carry)==count,"nothing runs at the due time without Retry (the timer is a follow-up) "+wait);
                handle.retry();pump(f,run);
                must(recoveryChecks(f.carry)==count+1&&net.count(TLRPC.TL_messages_sendMessage.class)==1,"Retry at the due time runs the check, with no second mark "+wait);
            }
            long due=recoveryDue(f.carry);int count=recoveryChecks(f.carry);must(due==f.carry.clock+600_000,"exact persisted cadence 600000");waiting(last(seen),handle.current(),due,"waiting-600000");
            owner.close();pump(f,run);control.close();run.close();
            Boot early=new Boot(f.carry,due-1);
            must(recoveryChecks(early.f)==count&&recoveryDue(early.f)==due&&early.ordinary==0,"a restart before the due time neither resets nor skips the cadence");waiting(early.last(),true,due,"restart-before-due");
            early.close();
            boot=new Boot(early.f,due+5_000);state=boot.f;marks=boot.net;
            must(boot.ordinary==1&&boot.net.count(TLRPC.TL_messages_sendMessage.class)==0,"startup after the due time runs the overdue check, then ordinary sync");
            end=boot.last();finished(end,true,new Cache(O,net.oldMark.id,net.oldMark.date),"startup-overdue");
            must(boot.seen.stream().noneMatch(s->s.phase.equals("Complete")),"startup never shows the move as completed");
        }else{
            end=last(seen);finished(end,handle.current(),new Cache(O,91,2500),"losing-mark");
            must(seen.stream().noneMatch(s->s.phase.equals("Complete")),"Change never shows the move as completed");
        }
        JSONObject j=state.diskJournal();must(j.getInt("phase")==18&&j.optBoolean("state_committed")&&j.optBoolean("cache_completed"),"FinishThenFollow reaches its terminal "+end.reason);
        must(net.count(TLRPC.TL_messages_sendMessage.class)==1&&net.deleted.isEmpty()&&marks.deleted.isEmpty()&&state.oldRecords.stream().anyMatch(m->m.id==2),"one mark, no deletion, O keeps its settings");
        must(f.carry.statuses.stream().noneMatch(st->st.terminal&&st.committed!=null)&&state.statuses.stream().noneMatch(st->st.terminal&&st.committed!=null),"no Committed receipt");
        if(boot!=null)boot.close();else{owner.close();pump(f,run);control.close();run.close();}
        separateFollow(state,w,operation);f.close();
    }
    static void run(File strings)throws Exception{
        org.w3c.dom.NodeList list=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings).getElementsByTagName("string");
        for(int i=0;i<list.getLength();++i){org.w3c.dom.Element e=(org.w3c.dom.Element)list.item(i);PurpleSyncChatWireFinishCases.strings.put(e.getAttribute("name"),e.getTextContent());}
        must(!PurpleSyncChatChange.ACTIVATED&&!PurpleSyncChatMoveConsent.ACTIVATED,"production switches stay OFF; the cases inject them ON");
        ui();lines();scenario(false);scenario(true);System.out.println("WIRE_FINISH_CHECKS="+checks);
    }
}
