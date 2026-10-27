package org.telegram.messenger.purple;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.lang.management.*;
public final class PurpleSyncChatAdmissionLockIntegrationCases {
 static void probe(File parent,boolean scoped)throws Exception{
  File root=Files.createTempDirectory(parent.toPath(),"lock-order-").toFile();
  PurpleSyncChatManualIntegrationCases.Port p=new PurpleSyncChatManualIntegrationCases.Port(root,PurpleSyncChatRequestCases.CHANNEL);
  PurpleSyncChatManual manual=new PurpleSyncChatManual(p,()->p.foreground);PurpleSyncChatQueuedMutationIntegrationCases.Env env=new PurpleSyncChatQueuedMutationIntegrationCases.Env(manual,p);
  PurpleSyncRunner.Session base=new PurpleSyncRunner.Session(1,202,env);Object owner=new Object();String key=p.key();
  PurpleSyncOperations.Capability capability=PurpleSyncOperations.acquire(key,owner,base::available);
  if(capability==null)throw new AssertionError("operation admission");
  PurpleSyncRunner.Session applying=new PurpleSyncRunner.Session(1,202,env,()->capability.current()&&base.available());
  PurpleSyncSettingsFile.Contents[] input={null};p.worker(()->input[0]=env.readSettings());
  PurpleSyncMutation mutation=scoped?input[0].mutation.scoped(applying::writable):input[0].mutation;
  CountDownLatch accountLocks=new CountDownLatch(1),operationsLock=new CountDownLatch(1),finished=new CountDownLatch(2);
  Thread ui=new Thread(()->{
   synchronized(PurpleAccountOwner.class){synchronized(PurpleAccountFiles.class){synchronized(PurpleAccountSettings.class){
    accountLocks.countDown();try{if(!operationsLock.await(3,TimeUnit.SECONDS))throw new AssertionError("operation lock not reached");}catch(InterruptedException e){throw new AssertionError(e);}
    if(!mutation.current())throw new AssertionError("publication admission unexpectedly refused");
   }}}finished.countDown();
  },"probe-editor-publication");
  Thread reader=new Thread(()->{
   try{if(!accountLocks.await(3,TimeUnit.SECONDS))throw new AssertionError("editor not reached");}catch(InterruptedException e){throw new AssertionError(e);}
   synchronized(PurpleSyncOperations.class){operationsLock.countDown();PurpleSyncOperations.busy(key);}finished.countDown();
  },"probe-operation-status");
  ui.setDaemon(true);reader.setDaemon(true);ui.start();reader.start();boolean completed=finished.await(3,TimeUnit.SECONDS);
  ThreadMXBean bean=ManagementFactory.getThreadMXBean();long[] ids=bean.findDeadlockedThreads();boolean cycle=false;
  if(ids!=null){for(ThreadInfo info:bean.getThreadInfo(ids,true,true))if(info.getThreadId()==ui.getId()||info.getThreadId()==reader.getId()){cycle=true;System.out.println(info);}}
  System.out.println("ADMISSION_LOCK scoped="+scoped+" completed="+completed+" deadlocked="+cycle);
  if(!completed||cycle)throw new AssertionError("publication must complete without deadlock");
  if(completed)PurpleSyncOperations.end(key,owner,capability);
  PurpleModernSyncCases.check(!capability.current(),"completed owner revokes exact RAM token");
 }
 static void revocation(){
  String key="admission-token-control";Object first=new Object(),second=new Object();boolean[] live={true};
  PurpleSyncOperations.Capability old=PurpleSyncOperations.acquire(key,first,()->live[0]);
  PurpleModernSyncCases.check(old!=null&&old.current(),"first owner has RAM authority");
  PurpleModernSyncCases.check(PurpleSyncOperations.acquire(key,second,()->true)==null,"live owner excludes another owner");
  PurpleSyncOperations.end(key,second,old);PurpleModernSyncCases.check(old.current(),"wrong owner cannot revoke token");
  live[0]=false;PurpleModernSyncCases.check(!PurpleSyncOperations.busy(key)&&!old.current(),"account retirement revokes RAM token");
  PurpleSyncOperations.Capability next=PurpleSyncOperations.acquire(key,second,()->true);
  PurpleModernSyncCases.check(next!=null&&next.current(),"retirement permits fresh owner");
  PurpleSyncOperations.end(key,first,old);PurpleModernSyncCases.check(next.current()&&!old.current(),"old owner end cannot revoke replacement");
  next.revoke();PurpleModernSyncCases.check(!next.current()&&!PurpleSyncOperations.busy(key),"immediate RAM revocation retires registry owner");
  PurpleSyncOperations.Capability last=PurpleSyncOperations.acquire(key,first,()->true);
  PurpleModernSyncCases.check(last!=null&&last.current()&&last!=old,"same owner object still gets fresh token");
  PurpleSyncOperations.end(key,second,next);PurpleModernSyncCases.check(last.current(),"stale replacement cannot retire fresh owner");
  PurpleSyncOperations.end(key,first,last);PurpleModernSyncCases.check(!last.current()&&!PurpleSyncOperations.busy(key),"final owner release revokes token");
 }
 static void callbacks(){
  String key="admission-callback-control";Object first=new Object(),second=new Object();int[] idle={0};
  PurpleSyncOperations.Capability token=PurpleSyncOperations.acquire(key,first,()->{
   PurpleModernSyncCases.check(!Thread.holdsLock(PurpleSyncOperations.class),"liveness outside registry monitor");return true;
  });
  PurpleSyncOperations.whenIdle(key,second,()->{PurpleModernSyncCases.check(!Thread.holdsLock(PurpleSyncOperations.class),"release callback outside registry monitor");idle[0]++;});
  PurpleModernSyncCases.check(PurpleSyncOperations.busy(key),"live callback control owns registry");
  token.revoke();PurpleModernSyncCases.check(!PurpleSyncOperations.busy(key)&&idle[0]==1,"revocation drains idle exactly once");
  PurpleSyncOperations.end(key,first,token);PurpleModernSyncCases.check(idle[0]==1,"late release does not repeat idle callback");
  final PurpleSyncOperations.Capability[] replacement={null};
  PurpleSyncOperations.Capability[] initial={null};
  PurpleSyncOperations.Capability old=initial[0]=PurpleSyncOperations.acquire(key,first,()->{
   PurpleModernSyncCases.check(!Thread.holdsLock(PurpleSyncOperations.class),"replacement callback outside monitor");
   PurpleSyncOperations.end(key,first,initial[0]);replacement[0]=PurpleSyncOperations.acquire(key,second,()->true);return false;
  });
  PurpleModernSyncCases.check(PurpleSyncOperations.busy(key)&&!old.current()&&replacement[0]!=null&&replacement[0].current(),"revalidate owner and token after liveness replaces ownership");
  PurpleSyncOperations.end(key,first,old);PurpleModernSyncCases.check(replacement[0].current(),"old failed liveness cannot retire replacement");
  PurpleSyncOperations.end(key,second,replacement[0]);
  PurpleSyncOperations.whenIdle(key,first,()->PurpleModernSyncCases.check(!Thread.holdsLock(PurpleSyncOperations.class),"immediate idle callback outside monitor"));
 }
 static void run(File root)throws Exception{
  int before=PurpleModernSyncCases.checks;probe(root,false);probe(root,true);revocation();callbacks();
  System.out.println("ADMISSION_LOCK_REGRESSION_CHECKS="+(PurpleModernSyncCases.checks-before));
 }
 public static void main(String[] args)throws Exception{PurpleCore.ensureLoaded();File root=new File(args[0]);if(args.length>1)probe(root,args[1].equals("scoped"));else run(root);}
}
