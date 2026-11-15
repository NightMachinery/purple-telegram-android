package org.telegram.messenger.purple;
import java.io.File;
import java.security.Security;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;
public final class PurpleArchiveReleasePressureCases {
 private static boolean ownerBlocked(PurpleArchive.Worker owner,CountDownLatch entered,CountDownLatch release,java.util.concurrent.atomic.AtomicBoolean blocked){
  return entered.getCount()==0&&release.getCount()==1&&blocked.get()&&!owner.queue.isShutdown()&&!owner.queue.isTerminating()&&owner.queue.getActiveCount()==1;
 }
 private static boolean full(PurpleArchive.Worker owner,CountDownLatch entered,CountDownLatch release,java.util.concurrent.atomic.AtomicBoolean blocked){
  return ownerBlocked(owner,entered,release,blocked)&&owner.queue.getQueue().size()==64&&owner.queue.getQueue().remainingCapacity()==0;
 }
 private static void fillToCapacity(PurpleArchive.Worker owner,CountDownLatch entered,CountDownLatch release,java.util.concurrent.atomic.AtomicBoolean blocked){
  check(ownerBlocked(owner,entered,release,blocked),"fill owner remains blocked and executor running");
  for(int attempt=0;attempt<64&&owner.queue.getQueue().remainingCapacity()>0;++attempt){
   try{owner.queue.execute(()->{});}
   catch(java.util.concurrent.RejectedExecutionException rejected){
    if(!full(owner,entered,release,blocked))throw rejected;
    break;
   }
  }
  check(full(owner,entered,release,blocked),"fill reaches exact64 while owner remains blocked");
 }
 public static void main(String[] args)throws Exception{
  System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());File root=new File(args[1]);check(root.mkdir(),"exclusive release-pressure fixture");ApplicationLoader.files=new File(root,"files");check(ApplicationLoader.files.mkdir(),"release fixture app files");ApplicationLoader.applicationContext=new android.content.Context();
  UserConfig.selectedAccount=0;UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=slot->"11111111111111111111111111111111";PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"release fixture Ready");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
  CountDownLatch pressureEntered=new CountDownLatch(1),pressureRelease=new CountDownLatch(1);
  java.util.concurrent.atomic.AtomicBoolean pressureBlocked=new java.util.concurrent.atomic.AtomicBoolean();
  worker.queue.execute(()->{pressureBlocked.set(true);pressureEntered.countDown();try{pressureRelease.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{pressureBlocked.set(false);}});
  try{check(pressureEntered.await(5,TimeUnit.SECONDS),"housekeeping pressure owner blocked");}catch(Exception|Error failure){pressureRelease.countDown();throw failure;}
  java.util.concurrent.atomic.AtomicBoolean dropped=new java.util.concurrent.atomic.AtomicBoolean(),controlled=new java.util.concurrent.atomic.AtomicBoolean();
  PurpleArchive.Status admitted=worker.status;
  try{
   fillToCapacity(worker,pressureEntered,pressureRelease,pressureBlocked);
   check(worker.queue.getQueue().size()==64,"account queue remains bounded at 64");
   worker.submit(()->dropped.set(true));
   check(PurpleArchive.status(0).state.equals("Ready"),"rejected housekeeping preserves Ready");
   for(String state:new String[]{"Opening","StorageUnavailable","Maintenance","Removal pending","Closed"}){
    worker.maintenanceState(state,"fixture");PurpleArchive.Status before=worker.status;
    worker.submit(()->dropped.set(true));
    check(worker.status==before,"rejection preserves exact "+state+" status");
   }
   worker.status=admitted;
   worker.control(()->controlled.set(true));
   PurpleArchive.captureGap(PurpleAccountFiles.capture(0),"capture_timeout");
   await(()->!worker.gapScheduled.get(),"gap publication settles under pressure");
   check(PurpleArchive.status(0).gap,"pressure retains truthful capture gap");
   check(PurpleArchive.status(0).state.equals("Ready"),"gap flush rejection cannot poison readiness");
  }finally{pressureRelease.countDown();}
  await(controlled::get,"control obligation survives queue pressure");
  check(!dropped.get(),"rejected best-effort work never executes");
  check(PurpleArchive.status(0).state.equals("Ready"),"queue drain needs no Refresh");
  java.util.concurrent.atomic.AtomicBoolean captureRan=new java.util.concurrent.atomic.AtomicBoolean();
  PurpleArchive.runCapture(PurpleAccountFiles.capture(0),0,(owner,active)->{check(active.getAsBoolean(),"post-pressure capture authority remains current");captureRan.set(true);},System.nanoTime()+TimeUnit.SECONDS.toNanos(40));
  check(captureRan.get(),"production capture resumes after overflow and drain without Refresh");
  PurpleAccountFiles.Identity stale=PurpleAccountFiles.capture(0);PurpleArchiveStore admittedStore=worker.store;
  UserConfig.getInstance(0).active=false;
  try{
   on(()->{worker.open(stale);return null;});
   check(worker.store==admittedStore,"stale identity cannot replace store");
   java.util.concurrent.atomic.AtomicBoolean staleCapture=new java.util.concurrent.atomic.AtomicBoolean();
   PurpleArchive.runCapture(stale,0,(owner,active)->staleCapture.set(true),System.nanoTime()+TimeUnit.SECONDS.toNanos(40));
   check(!staleCapture.get(),"signed-out stale identity cannot recapture");
  }finally{UserConfig.getInstance(0).active=true;}
  PurpleArchive.Worker retired=new PurpleArchive.Worker("0-99");retired.retire();PurpleArchive.Status retiredStatus=retired.status;
  retired.submit(()->dropped.set(true));
  check(retired.status==retiredStatus&&!dropped.get(),"retired submit preserves status and refuses work");
  org.telegram.tgnet.TLRPC.Message message=message(4200,"release pressure",17);PurpleArchiveB3bCases.retain(message,bytes(17));PurpleArchiveMediaAccess.Capability cap=PurpleArchiveB3bCases.open(message);check(cap!=null,"release fixture capability Ready");
  CountDownLatch entered=new CountDownLatch(1),unlock=new CountDownLatch(1);worker.queue.execute(()->{entered.countDown();try{unlock.await();}catch(InterruptedException e){throw new RuntimeException(e);}});check(entered.await(5,TimeUnit.SECONDS),"release owner is blocked");
  try{for(int i=0;i<1000;++i)cap.close();System.out.println("RELEASE_QUEUE_SIZE="+worker.queue.getQueue().size());check(worker.queue.getQueue().size()<=2,"repeated close queues at most one release plus an earlier validation");}finally{unlock.countDown();}
  await(()->cap.released,"release completes when owner resumes");on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();System.out.println("ARCHIVE_RELEASE_PRESSURE_CHECKS="+checks);
 }
}
