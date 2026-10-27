package org.telegram.messenger.purple;
import java.util.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatSetupCases.*;
final class PurpleSyncChatSetupReviewCases {
 static PurpleSyncChatScreen.Selection consent(Env env,Peer chat){
  PurpleSyncChatScreenCases.Port port=new PurpleSyncChatScreenCases.Port();port.info=env.facts(chat);
  PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(port);
  PurpleSyncChatScreen.Selection[] selected={null};
  control.accept(new PurpleSyncChatScreen.Candidate(ORIGIN,chat,port.info),true,true,s->selected[0]=s);
  control.close();if(selected[0]==null)throw new AssertionError("actual Screen.Control did not accept fresh consent");return selected[0];
 }
 static void initialReaders(){
  Env env=new Env();env.readersChange=true;
  PurpleSyncChatSetup first=new PurpleSyncChatSetup(env,selection(null,true),p->{});first.start();env.drain();
  System.out.println("INITIAL_READERS first="+first.phase()+"/"+first.reason()+" created="+env.creates+" posts="+env.posts+" step="+env.step()+" baselineEmpty="+env.journalState().readers.isEmpty());first.close();
  Env resumed=new Env(env.file,env.server);resumed.readersChange=true;
  PurpleSyncChatSetup second=new PurpleSyncChatSetup(resumed,consent(resumed,CHANNEL),p->{});second.start();resumed.drain();
  check(second.phase()==PurpleSyncChatSetup.Phase.Complete&&resumed.posts==1&&!resumed.journalState().readers.isEmpty(),"fresh matching consent establishes missing created-channel baseline");
  System.out.println("INITIAL_READERS freshConsent="+second.phase()+"/"+second.reason()+" posts="+resumed.posts+" baselineEmpty="+resumed.journalState().readers.isEmpty());second.close();
 }
 static void normalReconsent(){
  Env env=new Env();env.inventoryMode="reader-change";
  PurpleSyncChatSetup first=new PurpleSyncChatSetup(env,selection(null,true),p->{});first.start();env.drain();first.close();
  Env resumed=new Env(env.file,env.server);resumed.readersChange=true;
  PurpleSyncChatSetup second=new PurpleSyncChatSetup(resumed,consent(resumed,CHANNEL),p->{});second.start();resumed.drain();
  if(second.phase()!=PurpleSyncChatSetup.Phase.Complete)throw new AssertionError("existing baseline re-consent control failed");
  System.out.println("CONTROL existing-baseline fresh consent completes");second.close();
 }
 static void cancelAndEmpty(){
  Env env=new Env();PurpleSyncChatSetup empty=new PurpleSyncChatSetup(env,null,p->{});empty.start();env.drain();
  if(empty.busy()||env.owner!=null||empty.journal()!=null)throw new AssertionError("empty resume retained scope");
  empty.close();PurpleSyncChatSetup fresh=new PurpleSyncChatSetup(env,selection(SELF,false),p->{});fresh.start();env.drain();
  if(fresh.phase()!=PurpleSyncChatSetup.Phase.Complete)throw new AssertionError("fresh setup after empty resume blocked");fresh.close();
  for(String phase:new String[]{"resolve","create","post","pin"}){
   Env held=new Env();held.hold=phase;PurpleSyncChatSetup setup=new PurpleSyncChatSetup(held,selection(null,true),p->{});setup.start();held.drain();setup.close();
   Object other=new Object();PurpleSyncOperations.Capability otherToken=PurpleSyncOperations.acquire(held.key,other,()->true);if(otherToken==null)throw new AssertionError("cancel retained lease "+phase);
   PurpleSyncOperations.end(held.key,other,otherToken);
  }
  System.out.println("CONTROL empty resume admits fresh choice; cancelled runs release account lease");
 }
 static void lateCreate(){
  PurpleSyncChatSetupTransportCases.Run run=new PurpleSyncChatSetupTransportCases.Run(null,true);run.start();
  PurpleSyncChatSetupTransportCases.Sent issued=run.last();run.close();run.success();
  try{if(run.receipts.created(issued.command.operation)==null)throw new AssertionError("late receipt missing");}catch(Exception e){throw new AssertionError(e);}
  if(run.setup.phase()!=PurpleSyncChatSetup.Phase.Cancelled||run.sent.size()!=1)throw new AssertionError("late response advanced cancelled executor");
  System.out.println("CONTROL late original Create receipt retained; no further effect");
 }
 static void duplicateFlood(){
  PurpleSyncChatSetupTransportCases.Run run=new PurpleSyncChatSetupTransportCases.Run(null,true);run.start();
  run.error(420,"FLOOD_WAIT_8");run.error(420,"FLOOD_WAIT_8");
  List<Runnable> due=new ArrayList<>(run.timers.keySet());run.env.clock+=8;
  for(Runnable r:due){run.timers.remove(r);r.run();run.env.drain();}
  System.out.println("DUPLICATE_FLOOD retryTimers="+due.size()+" createRequests="+run.sent.size());run.close();
 }
 static void staleWatchdog(){
  PurpleSyncChatSetupTransportCases.Run run=new PurpleSyncChatSetupTransportCases.Run(null,true);run.start();
  Runnable old=run.env.timers.iterator().next();
  org.telegram.tgnet.TLRPC.TL_error error=new org.telegram.tgnet.TLRPC.TL_error();error.code=420;error.text="FLOOD_WAIT_90";
  run.env.clock+=29;run.last().done.accept(null,error);
  run.env.clock+=1;run.env.timers.remove(old);old.run();
  run.env.drain();
  check(run.setup.live()&&run.setup.reason().equals("TelegramWait")&&run.timers.size()==1,"queued same-command watchdog cannot end a rearmed wait");
  check(run.env.timers.size()==1,"current extended watchdog remains owned after stale delivery");
  System.out.println("STALE_WATCHDOG phase="+run.setup.phase()+" reason="+run.setup.reason()+" live="+run.setup.live()+" deferred="+run.env.journalState().deferred+" retryTimers="+run.timers.size()+" owner="+run.env.owner);
  run.close();
 }
 static void orphanWatchdog(){
  PurpleSyncChatSetupTransportCases.Run run=new PurpleSyncChatSetupTransportCases.Run(null,true);
  run.env.hold="resolve";run.start();
  Runnable resolvedTimeout=run.env.timers.iterator().next();
  run.env.clock+=29;run.env.hold="";run.env.pending.run();
  run.env.clock+=1;
  run.env.timers.remove(resolvedTimeout);resolvedTimeout.run();run.env.drain();
  check(run.setup.live()&&run.env.timers.size()==1,"queued prior-step watchdog cannot clear the current timer reference");
  Set<Runnable> createTimers=new HashSet<>(run.env.timers);
  System.out.println("ORPHAN_WATCHDOG createTimers="+createTimers.size()+" live="+run.setup.live());
  run.env.clock+=1;run.error(420,"FLOOD_WAIT_90");
  createTimers.retainAll(run.env.timers);
  check(createTimers.isEmpty(),"rearm cancels the current prior deadline without leaving an orphan");
  System.out.println("ORPHAN_WATCHDOG survivingOldCreateTimers="+createTimers.size()+" liveAfterEarlyFlood="+run.setup.live());
  run.env.clock+=29;
  System.out.println("ORPHAN_WATCHDOG now="+run.env.clock+" floodDeadline="+run.env.journalState().retryAt);
  for(Runnable r:createTimers){run.env.timers.remove(r);r.run();run.env.drain();}
  check(run.setup.live()&&run.setup.reason().equals("TelegramWait"),"old step timer never terminates a valid extended flood wait");
  System.out.println("ORPHAN_WATCHDOG phase="+run.setup.phase()+" reason="+run.setup.reason()+" live="+run.setup.live());
  run.close();
 }
 static void absentBaselineRefusals(){
  for(String control:new String[]{"absent","other-target","stale-candidate","rights","unknown"}){
   Env env=new Env();env.readersChange=true;PurpleSyncChatSetup first=new PurpleSyncChatSetup(env,selection(null,true),p->{});first.start();env.drain();
   check(first.phase()==PurpleSyncChatSetup.Phase.Held&&env.creates==1&&env.posts==0&&env.journalState().readers.isEmpty(),"created channel starts held without undisclosed baseline");first.close();
   Env resumed=new Env(env.file,env.server);resumed.readersChange=!control.equals("stale-candidate");
   PurpleSyncChatScreen.Selection choice=control.equals("absent")?null:consent(resumed,control.equals("other-target")?USER:CHANNEL);
   resumed.readersChange=true;resumed.badRights=control.equals("rights");resumed.unknownReaders=control.equals("unknown");
   PurpleSyncChatSetup second=new PurpleSyncChatSetup(resumed,choice,p->{});second.start();resumed.drain();
   check(second.phase()==PurpleSyncChatSetup.Phase.Held&&resumed.posts==0&&resumed.journalState().readers.isEmpty(),"missing baseline refuses "+control);second.close();
  }
 }
 static void currentTimeout(){
  PurpleSyncChatSetupTransportCases.Run run=new PurpleSyncChatSetupTransportCases.Run(null,true);run.start();
  Runnable current=run.env.timers.iterator().next();run.env.timers.remove(current);current.run();run.env.drain();
  check(run.setup.phase()==PurpleSyncChatSetup.Phase.Held&&run.setup.reason().equals("RequestTimeout")&&run.env.owner==null,"current watchdog still times out and releases its lease");run.close();
 }
 static void run(java.io.File root)throws Exception{
  int before=checks;initialReaders();normalReconsent();absentBaselineRefusals();cancelAndEmpty();lateCreate();staleWatchdog();orphanWatchdog();currentTimeout();
  System.out.println("Q2A_REVIEW_REGRESSION_CHECKS="+(checks-before));
 }
}
