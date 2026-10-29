package org.telegram.messenger.purple;
import java.util.*;
import java.util.function.*;
import org.json.*;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveCleanupCases.*;
public final class PurpleSyncChatCleanupReviewCases {
 static int checks,failures;
 static void check(boolean value,String name){++checks;if(!value){++failures;System.out.println("REVIEWER_FAILURE: "+name);}}
 static void decorationProgress()throws Exception{
  for(String effect:new String[]{"Pin","Mute","Archive",""}){
   PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();f.failDecoration=effect;f.start(true,true);
   PurpleSyncChatChange.State last=f.states.get(f.states.size()-1);
   boolean actionable=last.cleanup!=null&&last.cleanup.pending&&last.cleanup.canAct;
   System.out.println("DECORATION="+effect+" LAST="+last.phase+":"+last.reason+" CLEANUP="+(last.cleanup==null?"null":last.cleanup.pending+":"+last.cleanup.canAct));
   check(f.carry.diskJournal().getBoolean("cache_completed"),"decoration/cleanup safe commit "+effect);
   check(!PurpleSyncChatMoveStore.completed(f.carry.files.root,"0-777"),"cleanup still holds writes "+effect);
   check(actionable,"cleanup Retry/Skip visible independently of failed "+effect);
   if(!effect.isEmpty()){
    check(last.phase.equals("Held")&&last.reason.equals(effect+"Unknown"),"decoration failure retained "+effect);
    int steps=Arrays.asList("Pin","Mute","Archive").indexOf(effect)+1;
    check(f.decorations==steps,"progress never retries decoration implicitly "+effect);
    last.cleanup.skip(true);f.drain();PurpleSyncChatChange.State skipped=f.states.get(f.states.size()-1);
    check(f.decorations==steps&&skipped.phase.equals("Held")&&skipped.reason.equals(effect+"Unknown"),"Skip leaves decoration failure and completed steps intact "+effect);
    check(f.carry.diskJournal().getInt("phase")==16&&skipped.cleanup.skipped&&PurpleSyncChatMoveStore.completed(f.carry.files.root,ORIGIN.accountKey())&&!PurpleSyncOperations.carryHeld(f.carry.operationKey()),"Held decoration Skip really releases cleanup hold "+effect);
   }
   f.close();
  }
 }
 static void tickets()throws Exception{
  try(Flow q=new Flow()){
   q.stop="CleanupRead";q.start();PurpleSyncChatMoveCleanup h=new PurpleSyncChatMoveCleanup(q.f.opened,ORIGIN,()->true);
   h.prepare(q.f.fresh(),q.f.inventory(false),noPlaylist(q));PurpleSyncChatMoveCleanup.Item item=h.next();
   PurpleSyncChatMoveCleanup.Read a=h.read(item);PurpleSyncChatMove.Reply old=q.read(q.deferred);
   PurpleSyncChatMoveCleanup.Read b=h.read(item);PurpleSyncChatMove.Reply fresh=q.read(q.deferred);
   boolean rejected=false;try{h.inspect(a,old.fresh,old.destination,noPlaylist(q),old.markRoute,old.response,old.sourceRecords.get(0));}catch(java.io.IOException e){rejected=e.getMessage().equals("StaleCleanupRead");}
   check(rejected,"older ticket cannot cross a newer read generation");
   PurpleSyncChatMoveCleanup.Permit permit=h.inspect(b,fresh.fresh,fresh.destination,noPlaylist(q),fresh.markRoute,fresh.response,fresh.sourceRecords.get(0));check(permit.current(),"new generation positive read still admitted");
   h.close();check(!permit.current(),"helper close revokes newest generation");
  }
 }
 static void absenceControls()throws Exception{
  for(String shape:new String[]{"wrongPeer","danglingPeer","wrongId","zero","peerless","inexact","multi"})try(Flow q=new Flow()){
   q.stop="CleanupRead";q.start();PurpleSyncChatMoveCleanup h=new PurpleSyncChatMoveCleanup(q.f.opened,ORIGIN,()->true);h.prepare(q.f.fresh(),q.f.inventory(false),noPlaylist(q));
   PurpleSyncChatMoveCleanup.Item item=h.next();PurpleSyncChatMoveCleanup.Read ticket=h.read(item);PurpleSyncChatMove.Reply r=q.read(q.deferred);
   TLRPC.TL_messageEmpty e=new TLRPC.TL_messageEmpty();e.id=item.id;e.flags=1;e.peer_id=peer(O);
   if(shape.equals("wrongPeer"))e.peer_id=peer(Peer.of(PeerKind.Channel,10));
   if(shape.equals("danglingPeer"))e.peer_id=null;
   if(shape.equals("wrongId"))e.id++;
   if(shape.equals("peerless")){e.flags=0;e.peer_id=null;}
   TLRPC.messages_Messages response=(TLRPC.messages_Messages)page(O,shape.equals("zero")?new TLRPC.Message[0]:shape.equals("multi")?new TLRPC.Message[]{e,e}:new TLRPC.Message[]{e});
   if(shape.equals("inexact"))response.inexact=true;
   boolean rejected=false;try{h.inspect(ticket,r.fresh,r.destination,noPlaylist(q),r.markRoute,response,null);}catch(java.io.IOException ex){rejected=true;}
   check(rejected,"cleanup absence refuses "+shape);check(q.f.opened.cleanup().getJSONObject("entries").length()==0,"uncertain absence persists no result "+shape);h.close();
  }
 }
 static void durabilityProgress()throws Exception{
  for(int point:new int[]{1,5})try(Flow q=new Flow()){
   q.stop="CleanupRead";q.start();q.f.opened.fault(point,new java.io.File(q.f.files.root,"carry.json"));
   q.done.accept(q.read(q.deferred));q.f.drain();PurpleSyncChatMove.Status last=q.f.statuses.get(q.f.statuses.size()-1);
   System.out.println("INTENT_FAULT="+point+" ACTION="+last.action+" REASON="+last.reason+" COMMITTED="+(last.committed!=null)+" PROGRESS="+(last.cleanup==null?"null":last.cleanup.pending+":"+last.cleanup.canAct));
   check(!q.f.done()&&q.deleted.isEmpty(),"failed intent issues no deletion "+point);
   check(last.cleanup!=null&&last.cleanup.pending&&last.cleanup.canAct,"failed intent retains cleanup Retry/Skip "+point);
   check(last.action.equals("Cleanup")&&last.committed!=null,"intent failure retains phase and committed knowledge "+point);
  }
  try(Flow q=new Flow()){
   q.dropDelete=true;q.start();q.close();
   try(Flow next=new Flow(q)){
    next.stop="CleanupRead";next.resume();next.f.opened.fault(1,new java.io.File(next.f.files.root,"carry.json"));
    next.done.accept(next.read(next.deferred));next.f.drain();PurpleSyncChatMove.Status last=next.f.statuses.get(next.f.statuses.size()-1);
    System.out.println("RESULT_FAULT ACTION="+last.action+" REASON="+last.reason+" PROGRESS="+(last.cleanup==null?"null":last.cleanup.pending+":"+last.cleanup.canAct));
    check(!next.f.done()&&next.deleted.isEmpty(),"failed result barrier holds without duplicate deletion");
    check(last.cleanup!=null&&last.cleanup.pending&&last.cleanup.canAct,"failed result retains cleanup Retry/Skip");
    check(last.action.equals("Cleanup")&&last.committed!=null,"result failure retains committed knowledge");
   }
  }
 }
 static void slowAdmission()throws Exception{
  try(Flow q=new Flow()){
   q.stop="CleanupRead";q.start();PurpleSyncChatMoveCleanup h=new PurpleSyncChatMoveCleanup(q.f.opened,ORIGIN,()->true);h.prepare(q.f.fresh(),q.f.inventory(false),noPlaylist(q));PurpleSyncChatMoveCleanup.Item item=h.next();
   PurpleSyncChatMoveCleanup.Permit initial=admit(q,h,item);
   class Writes implements PurpleSyncChatMoveCleanup.WritePort {
    long clock=1;int sends;PurpleSyncChatMoveCleanup.Read read;Consumer<PurpleSyncChatMoveCleanup.Permit> grant;
    List<Runnable> timers=new ArrayList<>();
    public boolean current(){return true;}public long now(){return clock;}public void dispatch(Runnable task){task.run();}
    public void delay(Runnable task,long millis){timers.add(task);}public void cancelDelay(Runnable task){timers.remove(task);}public void cancel(int id){}
    public int send(TLObject outgoing,PurpleRpcRequest.Reply done){sends++;return 8;}
    public void apply(PurpleSyncChatOrigin origin,TLObject response){}
    public void renew(PurpleSyncChatMoveCleanup.Permit before,Consumer<PurpleSyncChatMoveCleanup.Permit> done){try{read=h.read(item);grant=done;}catch(Exception e){throw new RuntimeException(e);}}
   }
   Writes writes=new Writes();int[] completed={0};PurpleSyncChatMoveCleanup.Effect effect=new PurpleSyncChatMoveCleanup.Effect(initial,writes,(response,error)->completed[0]++);effect.start();
   check(!initial.current()&&writes.sends==0,"renewal invalidates opening permit while worker read waits");
   writes.clock=60001;writes.timers.get(0).run();writes.clock=65001;writes.timers.get(0).run();
   java.lang.reflect.Field field=PurpleSyncChatMoveCleanup.Effect.class.getDeclaredField("request");field.setAccessible(true);PurpleRpcRequest rpc=(PurpleRpcRequest)field.get(effect);
   PurpleSyncChatMove.Reply reply=q.read(q.deferred);PurpleSyncChatMoveCleanup.Permit late=h.inspect(writes.read,reply.fresh,reply.destination,noPlaylist(q),reply.markRoute,reply.response,reply.sourceRecords.get(0));writes.grant.accept(late);
   System.out.println("SLOW_ADMISSION ABANDONED="+rpc.abandoned()+" COMPLETED="+completed[0]+" SENDS="+writes.sends+" TIMERS="+writes.timers.size());
   check(completed[0]==1||!rpc.abandoned(),"slow current admission cannot disappear without completion");
   check(writes.sends==0,"late grant does not send after abandoned request");effect.close();h.close();
  }
 }
 static void slowExecutorAdmission()throws Exception{
  try(Flow q=new Flow()){
   q.stop="CleanupDelete";q.start();PurpleSyncChatMove.Command command=q.deferred;
   check(command!=null&&command.cleanupPermit.current(),"executor cleanup delete command captured");
   class Writes implements PurpleSyncChatMoveCleanup.WritePort{
    long clock=1;int sends;Consumer<PurpleSyncChatMoveCleanup.Permit> grant;PurpleSyncChatMoveCleanup.Read read;
    final List<Runnable> timers=new ArrayList<>();
    public boolean current(){return q.f.live;}public long now(){return clock;}public void dispatch(Runnable r){r.run();}
    public void delay(Runnable r,long ms){timers.add(r);}public void cancelDelay(Runnable r){timers.remove(r);}public void cancel(int id){}
    public int send(TLObject request,PurpleRpcRequest.Reply done){++sends;return 9;}public void apply(PurpleSyncChatOrigin o,TLObject response){}
    public void renew(PurpleSyncChatMoveCleanup.Permit before,Consumer<PurpleSyncChatMoveCleanup.Permit> done){grant=done;q.f.move.beginCleanupRead(command,value->read=value);}
   }
   Writes writes=new Writes();int[] completed={0};
   PurpleSyncChatMoveCleanup.Effect effect=new PurpleSyncChatMoveCleanup.Effect(command.cleanupPermit,writes,(response,error)->{
    ++completed[0];try{PurpleSyncChatMove.Reply reply=q.f.reply();reply.response=response;q.done.accept(reply);}catch(Exception e){throw new RuntimeException(e);}
   });effect.start();q.f.drain();
   check(writes.read!=null&&!command.cleanupPermit.current(),"worker renewal supersedes prior attempt while command stays live");
   writes.clock=60001;writes.timers.get(0).run();writes.clock=65001;writes.timers.get(0).run();q.stop="";q.f.drain();
   PurpleSyncChatMove.Status held=q.f.statuses.get(q.f.statuses.size()-1);
   check(completed[0]==1&&writes.sends==0&&q.deleted.isEmpty(),"timed out renewal retires once with no delete");
   check(held.cleanup!=null&&held.cleanup.pending&&held.cleanup.canAct&&held.committed!=null,"retired executor command restores actionable pending progress");
   writes.grant.accept(command.cleanupPermit);q.f.drain();check(writes.sends==0&&completed[0]==1,"late grant cannot revive retired executor command");
   effect.close();check(completed[0]==1,"explicit close after retirement cannot complete twice");
   held.cleanup.skip(true);q.f.drain();check(q.f.done()&&q.deleted.isEmpty(),"Skip works after slow admission retirement");
  }
 }
 static void resultRestart()throws Exception{
  try(Flow q=new Flow()){
   q.dropDelete=true;q.start();q.close();
   try(Flow n=new Flow(q)){
    n.stop="CleanupRead";n.resume();check(n.deferred!=null,"restart reaches exact reconcile");
    PurpleSyncChatMove.Reply absent=n.read(n.deferred);n.stop="Cleanup";n.done.accept(absent);n.f.drain();
    check(n.f.opened.cleanup().getJSONObject("entries").toString().contains("absent"),"absence durably saved before terminal core event");n.close();
    try(Flow again=new Flow(n)){again.resume();check(again.f.done()&&again.deleted.isEmpty(),"restart after saved result completes without another delete");}
   }
  }
 }
 public static void main(String[] args)throws Exception{
  PurpleCore.ensureLoaded();PurpleSyncChatMoveCleanupCases.main(args);decorationProgress();tickets();absenceControls();resultRestart();durabilityProgress();slowAdmission();slowExecutorAdmission();
  System.out.println("CLEANUP_REVIEW_CHECKS="+checks);System.out.println("CLEANUP_REVIEW_FAILURES="+failures);if(failures!=0)throw new AssertionError("reviewer failures="+failures);
 }
}
