package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

final class PurpleSyncChatQueuedMutationIntegrationCases {
 static final class Queue implements PurpleSyncRunner.Poster {
  final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
  java.util.function.Consumer<Runnable> execute=Runnable::run;
  public void post(Runnable task){tasks.add(task);}
  void drain(){int n=1000;while(!tasks.isEmpty()){if(--n<=0)throw new AssertionError("queue loop");execute.accept(tasks.remove());}}
 }
 static final class Env extends PurpleSyncRunner.Platform {
  Runnable beforeReplace=()->{};final PurpleSyncChatManual m;final PurpleSyncChatManualIntegrationCases.Port p;
  Env(PurpleSyncChatManual m,PurpleSyncChatManualIntegrationCases.Port p){super(p.context);this.m=m;this.p=p;}
  public void resolveChat(boolean b,Object owner,String key,java.util.function.BooleanSupplier live,java.util.function.Consumer<Boolean> done){m.resolveChat(b,owner,key,live,done);}
  public void cancelChat(Object owner){m.cancelChat(owner);}
  public PurpleSyncChatScreen.Candidate recipient(){return m.recipient();}
  public boolean consent(PurpleSyncInventory inventory){return m.consent(inventory);}
  public void prepareApply(Object owner,String key,java.util.function.BooleanSupplier live,java.util.function.Consumer<Boolean> done){m.prepareApply(owner,key,live,done);}
  public PurpleAccountSyncCore.Result initialize(int account,String install,String device,String space){return m.initialize(account,install,device,space);}
  public boolean workerEffects(){return m.workerEffects();}
  public void checkEffects(java.util.function.BooleanSupplier effects,java.util.function.Consumer<Boolean> done){m.checkEffects(effects,done);}
  public PurpleSyncSettingsFile.Contents readSettings(){return m.readSettings();}
  public PurpleAccountSyncStore store(){return m.store();}
  public boolean requiresRecovery(){return m.requiresRecovery();}
  public PurpleSyncRecoveryModel.Offer recovery(PurpleSyncInventory inventory,PurpleSyncSettingsFile.Contents local){return m.recovery(inventory,local);}
  public String device(){return m.device();}
  public PurpleSyncSettingsFile.WriteResult replaceSettings(PurpleSyncSettingsFile.Contents expected,byte[] bytes,String reason,boolean imported){beforeReplace.run();return p.files.env(1).replaceSettings(expected,bytes,reason,imported);}
 }

 static PurpleSyncRunner.Check review(PurpleSyncRunner runner,Queue queue){
  PurpleSyncRunner.CheckOutcome[] done={null};check(runner.check(null,v->done[0]=v)==PurpleSyncRunner.Start.Started,"queued review starts");queue.drain();
  check(done[0]!=null&&done[0].status==PurpleSyncRunner.CheckStatus.Finished,"queued review completes");return done[0].check;
 }
 static PurpleSyncRunner.PostTicket initialize(PurpleSyncRunner runner,Queue queue){
  PurpleSyncRunner.Check shown=review(runner,queue);PurpleSyncRunner.ApplyOutcome[] done={null};
  check(runner.apply(shown,null,v->done[0]=v)==PurpleSyncRunner.Start.Started,"queued initialize starts");queue.drain();
  check(done[0]!=null&&done[0].result.status==PurpleSyncCore.ApplyStatus.Applied&&done[0].post!=null,"queued initialize completes");return done[0].post;
 }
 static void boundApply(File root,String cancel)throws Exception{
  PurpleSyncChatManualIntegrationCases.Port p=new PurpleSyncChatManualIntegrationCases.Port(root,PurpleSyncChatRequestCases.CHANNEL);
  Queue queue=new Queue();queue.execute=p::worker;PurpleSyncChatManual manual=new PurpleSyncChatManual(p,()->p.foreground);Env env=new Env(manual,p);
  PurpleSyncRunner runner=new PurpleSyncRunner(1,202,manual,env,queue,p::ui);PurpleSyncRunner.PostTicket ticket=initialize(runner,queue);
  runner.publish(ticket,v->{});queue.drain();check(p.posts==1,"normal initialization ticket survives ended apply admission");
  File stateFile=new File(p.context.files.syncDirectory,"state.json");
  PurpleAccountSyncCore.Result state=PurpleAccountSyncCore.inspectState(Files.readAllBytes(stateFile.toPath()));
  String remoteInstall=install(2);if(remoteInstall.equals(state.install))remoteInstall=install(3);
  PurpleAccountSyncCore.Result remote=PurpleAccountSyncCore.buildConfigRecord(REMOTE,new byte[][]{p.posted},state.space,remoteInstall,"fixture-remote","Desktop","synthetic",1,1800000001);
  check(remote.isValid(),"canonical remote child");p.records.add(remote.record);
  PurpleSyncRunner.Check shown=review(runner,queue);PurpleSyncCore.Choice choice=remote(shown);check(choice.impactReady,"reviewed remote choice");
  Map<File,byte[]> old=PurpleSyncRecoveryCases.files(p.context.files.syncDirectory);
  byte[] stateBefore=Files.readAllBytes(stateFile.toPath());int historyBefore=p.files.env(1).history().list().size();
  final boolean[] delivered={false};
  if(cancel.equals("boundary"))env.beforeReplace=()->{p.foreground=false;runner.suspend();};
  check(runner.apply(shown,choice.key,v->delivered[0]=true)==PurpleSyncRunner.Start.Started&&!queue.tasks.isEmpty(),"bound apply queued before suspend");
  if(cancel.equals("queued")){p.foreground=false;runner.suspend();}
  queue.drain();boolean normal=cancel.equals("none");
  check(Arrays.equals(Files.readAllBytes(p.files.settings(1).toPath()),normal?REMOTE:OWN),"queued apply settings "+cancel);
  check(delivered[0]==normal,"queued apply presentation "+cancel);
  if(!normal){
   check(Arrays.equals(stateBefore,Files.readAllBytes(stateFile.toPath())),"cancelled apply cannot advance ledger "+cancel);
   PurpleSyncRecoveryCases.retained(old);
   if(cancel.equals("queued"))check(p.files.env(1).history().list().size()==historyBefore,"queued cancellation adds no history");
   check(shown.local.mutation.effectCurrent(),"operation admission never poisons source snapshot for later ticket");
  }
  check(!PurpleSyncOperations.busy(p.key()),"bound apply releases ownership "+cancel);
  p.foreground=true;env.beforeReplace=()->{};review(runner,queue);runner.close();queue.drain();
  check(!PurpleSyncOperations.busy(p.key()),"new operation after apply cancellation releases");p.files.unchangedA();
 }
 static void queuedConfirm(File root,String order)throws Exception{
  PurpleSyncChatManualIntegrationCases.Port p=new PurpleSyncChatManualIntegrationCases.Port(root,PurpleSyncChatRequestCases.CHANNEL);
  Queue queue=new Queue();queue.execute=p::worker;PurpleSyncChatManual manual=new PurpleSyncChatManual(p,()->p.foreground);
  PurpleSyncRunner runner=new PurpleSyncRunner(1,202,manual,manual,queue,p::ui);PurpleSyncRunner.PostTicket ticket=initialize(runner,queue);
  p.holdPost=true;runner.publish(ticket,v->{});queue.drain();File pending=new File(p.context.files.syncDirectory,"pending/config.json");
  check(p.posts==1&&p.postedCallback!=null&&pending.isFile(),"exact post leaves durable pending");
  byte[] pendingBefore=Files.readAllBytes(pending.toPath());File state=new File(p.context.files.syncDirectory,"state.json");byte[] stateBefore=Files.readAllBytes(state.toPath());
  p.postedCallback.onPostDone(new PurpleSyncTransport.PostResult(PurpleSyncTransport.PostStatus.Confirmed,21,p.posted,p.target,p.origin));
  check(!queue.tasks.isEmpty(),"exact confirmed receipt queued before suspend");boolean cancel=!order.equals("none");
  if(cancel){p.foreground=false;runner.suspend();check(!PurpleSyncOperations.busy(p.key()),"abandon immediately releases capability");
   if(order.equals("close-first"))p.worker(queue.tasks.removeLast());
  }
  queue.drain();
  check(pending.exists()==cancel,"queued confirmation pending "+order);
  PurpleAccountSyncCore.Result inspected=PurpleAccountSyncCore.inspectState(Files.readAllBytes(state.toPath()));
  check(inspected.confirmedSeq==(cancel?0:1),"queued confirmation ledger "+order);
  if(cancel){
   check(Arrays.equals(pendingBefore,Files.readAllBytes(pending.toPath()))&&Arrays.equals(stateBefore,Files.readAllBytes(state.toPath())),"cancelled receipt keeps exact pending and state "+order);
   p.foreground=true;PurpleSyncRunner.Check shown=review(runner,queue);PurpleSyncPublisher.Result[] done={null};
   runner.publish(shown,PurpleSyncCore.PostRequest.finishSending(),v->done[0]=v);queue.drain();
   check(done[0]!=null&&done[0].status==PurpleSyncCore.PublishStatus.Confirmed&&p.posts==1&&!pending.exists(),"fresh operation reconciles exact pending without new send "+order);
  }
  runner.close();queue.drain();check(!PurpleSyncOperations.busy(p.key()),"confirmation completion releases ownership");p.files.unchangedA();
 }
 static void queuedStage(File root)throws Exception{
  PurpleSyncChatManualIntegrationCases.Port p=new PurpleSyncChatManualIntegrationCases.Port(root,PurpleSyncChatRequestCases.CHANNEL);
  Queue queue=new Queue();queue.execute=p::worker;PurpleSyncChatManual manual=new PurpleSyncChatManual(p,()->p.foreground);
  PurpleSyncRunner runner=new PurpleSyncRunner(1,202,manual,manual,queue,p::ui);PurpleSyncRunner.PostTicket ticket=initialize(runner,queue);
  Map<File,byte[]> before=PurpleSyncRecoveryCases.files(p.context.files.syncDirectory);
  runner.publish(ticket,v->{throw new AssertionError("abandoned stage presentation");});check(!queue.tasks.isEmpty(),"publisher start queued before suspend");
  p.foreground=false;runner.suspend();queue.drain();PurpleSyncRecoveryCases.retained(before);
  check(p.posts==0&&!new File(p.context.files.syncDirectory,"pending/config.json").exists()&&!PurpleSyncOperations.busy(p.key()),"abandoned queued start never stages or sends");
  runner.close();queue.drain();
 }
 static void historyBoundary(File root)throws Exception{
  File dir=new File(root,"history-admission");check(dir.mkdir(),"history boundary fixture");boolean[] live={true};
  PurpleSyncHistory.Core core=new PurpleSyncHistory.Core(){
   public String fingerprint(byte[] bytes){live[0]=false;return PurpleSyncCore.settingsFingerprint(bytes);}
   public boolean isVersionKey(String key){return PurpleSyncCore.isConfigVersionKey(key);}
  };
  PurpleSyncHistory history=new PurpleSyncHistory(dir,core,System::currentTimeMillis).require(()->live[0]);
  check(history.save(OWN,PurpleSyncHistory.Reason.BeforeUpdate,"fixture","",null)==null,"history retires between entry and mutation");
  check(dir.list().length==0,"retired history admission publishes nothing");
 }
 static void run(File root)throws Exception{
  int before=checks;
  for(String cancel:new String[]{"none","queued","boundary"})boundApply(root,cancel);
  for(String order:new String[]{"none","receipt-first","close-first"})queuedConfirm(root,order);
  queuedStage(root);historyBoundary(root);
  System.out.println("QUEUED_MUTATION_REGRESSION_CHECKS="+(checks-before));
 }
}
