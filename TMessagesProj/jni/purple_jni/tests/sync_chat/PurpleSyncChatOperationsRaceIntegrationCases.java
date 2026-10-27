package org.telegram.messenger.purple;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class PurpleSyncChatOperationsRaceIntegrationCases {
 static final AtomicInteger checks=new AtomicInteger();
 static void need(boolean ok,String why){if(!ok)throw new AssertionError(why);checks.incrementAndGet();}
 static void waitFor(CountDownLatch latch){try{if(!latch.await(4,TimeUnit.SECONDS))throw new AssertionError("latch timeout");}catch(InterruptedException e){throw new AssertionError(e);}}
 static Thread start(Runnable work,AtomicReference<Throwable> error){Thread t=new Thread(()->{try{work.run();}catch(Throwable e){error.compareAndSet(null,e);}},"registry-race");t.setDaemon(true);t.start();return t;}
 static void join(Thread t,AtomicReference<Throwable> error)throws Exception{t.join(5000);need(!t.isAlive(),"thread must complete");if(error.get()!=null)throw new AssertionError(error.get());}
 static void replacement(boolean same)throws Exception{
  String key="replacement-"+same;Object old=new Object(),next=same?old:new Object();AtomicBoolean armed=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger wakes=new AtomicInteger();
  PurpleSyncOperations.Capability first=PurpleSyncOperations.acquire(key,old,()->{need(!Thread.holdsLock(PurpleSyncOperations.class),"supplier outside monitor");if(armed.get()){entered.countDown();waitFor(release);return false;}return true;});
  PurpleSyncOperations.whenIdle(key,new Object(),wakes::incrementAndGet);armed.set(true);AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean result=new AtomicBoolean();
  Thread t=start(()->result.set(PurpleSyncOperations.busy(key)),error);waitFor(entered);PurpleSyncOperations.end(key,old,first);PurpleSyncOperations.Capability fresh=PurpleSyncOperations.acquire(key,next,()->true);release.countDown();join(t,error);
  need(result.get()&&fresh!=null&&fresh.current()&&!first.current()&&wakes.get()==1,"old liveness cannot retire replacement token");PurpleSyncOperations.end(key,next,fresh);System.out.println("PASS blocked liveness with replacement sameOwner="+same);
 }
 static void retireEndRace()throws Exception{
  String key="retire-end-race";Object owner=new Object();AtomicBoolean armed=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger wakes=new AtomicInteger();
  PurpleSyncOperations.Capability token=PurpleSyncOperations.acquire(key,owner,()->{need(!Thread.holdsLock(PurpleSyncOperations.class),"supplier outside monitor");if(armed.get()){entered.countDown();waitFor(release);return false;}return true;});
  PurpleSyncOperations.whenIdle(key,new Object(),()->{need(!Thread.holdsLock(PurpleSyncOperations.class),"idle outside monitor");wakes.incrementAndGet();});armed.set(true);AtomicReference<Throwable> error=new AtomicReference<>();
  Thread a=start(()->PurpleSyncOperations.busy(key),error),b=start(()->PurpleSyncOperations.owned(key,owner),error);waitFor(entered);PurpleSyncOperations.end(key,owner,token);release.countDown();join(a,error);join(b,error);
  PurpleSyncOperations.end(key,owner,token);need(!token.current()&&wakes.get()==1&&!PurpleSyncOperations.busy(key),"retirement and end wake once");System.out.println("PASS simultaneous failed liveness and end");
 }
 static void waiterRaces()throws Exception{
  for(int i=0;i<100;i++){
   String key="waiter-race-"+i;Object owner=new Object();PurpleSyncOperations.Capability token=PurpleSyncOperations.acquire(key,owner,()->true);AtomicInteger calls=new AtomicInteger();CountDownLatch go=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
   Thread a=start(()->{waitFor(go);PurpleSyncOperations.whenIdle(key,new Object(),()->{need(!Thread.holdsLock(PurpleSyncOperations.class),"racing callback outside monitor");calls.incrementAndGet();});},error);
   Thread b=start(()->{waitFor(go);PurpleSyncOperations.end(key,owner,token);},error);go.countDown();join(a,error);join(b,error);need(calls.get()==1&&!PurpleSyncOperations.busy(key),"registration/end exactly once");
  }System.out.println("PASS 100 waiter registration/end races");
 }
 static void acquisitions()throws Exception{
  for(int i=0;i<100;i++){
   String key="acquire-race-"+i;Object aOwner=new Object(),bOwner=new Object();PurpleSyncOperations.Capability[] cap={null,null};CountDownLatch go=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
   Thread a=start(()->{waitFor(go);cap[0]=PurpleSyncOperations.acquire(key,aOwner,()->true);},error),b=start(()->{waitFor(go);cap[1]=PurpleSyncOperations.acquire(key,bOwner,()->true);},error);go.countDown();join(a,error);join(b,error);
   need((cap[0]==null)!=(cap[1]==null),"exactly one acquisition");Object winner=cap[0]!=null?aOwner:bOwner;PurpleSyncOperations.end(key,winner,cap[0]!=null?cap[0]:cap[1]);need(!PurpleSyncOperations.busy(key),"winner releases");
  }System.out.println("PASS 100 competing acquisition races");
 }
 static void callbackReplacement(){
  String key="callback-replacement";Object first=new Object(),next=new Object();AtomicInteger oldWake=new AtomicInteger(),nextWake=new AtomicInteger();PurpleSyncOperations.Capability[] fresh={null};
  PurpleSyncOperations.Capability old=PurpleSyncOperations.acquire(key,first,()->true);PurpleSyncOperations.whenIdle(key,new Object(),()->{
   need(!Thread.holdsLock(PurpleSyncOperations.class),"reentrant idle outside monitor");oldWake.incrementAndGet();fresh[0]=PurpleSyncOperations.acquire(key,next,()->true);PurpleSyncOperations.whenIdle(key,new Object(),nextWake::incrementAndGet);
  });old.revoke();need(PurpleSyncOperations.busy(key)&&fresh[0]!=null&&fresh[0].current()&&oldWake.get()==1&&nextWake.get()==0,"query revalidates callback replacement");
  PurpleSyncOperations.end(key,first,old);need(fresh[0].current()&&nextWake.get()==0,"late different owner cannot end replacement");PurpleSyncOperations.end(key,next,fresh[0]);need(nextWake.get()==1,"replacement wakes own waiter once");System.out.println("PASS callback replacement and late different-owner end");
 }
 static void lateSameOwner(){
  String key="same-owner-late-end";Object owner=new Object();AtomicInteger oldWake=new AtomicInteger(),nextWake=new AtomicInteger();PurpleSyncOperations.Capability[] fresh={null};
  PurpleSyncOperations.Capability old=PurpleSyncOperations.acquire(key,owner,()->true);PurpleSyncOperations.whenIdle(key,new Object(),()->{
   oldWake.incrementAndGet();fresh[0]=PurpleSyncOperations.acquire(key,owner,()->true);PurpleSyncOperations.whenIdle(key,new Object(),nextWake::incrementAndGet);
  });old.revoke();need(PurpleSyncOperations.busy(key)&&fresh[0]!=null&&fresh[0].current()&&oldWake.get()==1&&nextWake.get()==0,"retirement starts same-owner new acquisition");
  PurpleSyncOperations.end(key,owner,old);
  System.out.println("LATE_SAME_OWNER_END oldRevoked="+!old.current()+" replacementCurrent="+fresh[0].current()+" oldWake="+oldWake.get()+" replacementWake="+nextWake.get());
  need(fresh[0].current()&&nextWake.get()==0,"late cleanup of old acquisition must not retire its replacement");PurpleSyncOperations.end(key,owner,fresh[0]);
 }
 static void chainedLateEnds(){
  String key="same-owner-chained-late-end";Object owner=new Object();AtomicInteger aWake=new AtomicInteger(),bWake=new AtomicInteger(),cWake=new AtomicInteger();PurpleSyncOperations.Capability[] tokens=new PurpleSyncOperations.Capability[3];
  tokens[0]=PurpleSyncOperations.acquire(key,owner,()->true);
  PurpleSyncOperations.whenIdle(key,new Object(),()->{
   need(!Thread.holdsLock(PurpleSyncOperations.class),"A retirement callback outside monitor");aWake.incrementAndGet();tokens[1]=PurpleSyncOperations.acquire(key,owner,()->true);
   PurpleSyncOperations.whenIdle(key,new Object(),()->{
    need(!Thread.holdsLock(PurpleSyncOperations.class),"B retirement callback outside monitor");bWake.incrementAndGet();tokens[2]=PurpleSyncOperations.acquire(key,owner,()->true);PurpleSyncOperations.whenIdle(key,new Object(),cWake::incrementAndGet);
   });
  });
  tokens[0].revoke();need(PurpleSyncOperations.busy(key)&&tokens[1]!=null&&tokens[1].current()&&aWake.get()==1&&bWake.get()==0,"A retirement acquires B for retained owner");
  PurpleSyncOperations.end(key,owner,tokens[0]);need(tokens[1].current()&&bWake.get()==0,"late A leaves B and its waiter intact");
  tokens[1].revoke();need(PurpleSyncOperations.busy(key)&&tokens[2]!=null&&tokens[2].current()&&bWake.get()==1&&cWake.get()==0,"B retirement acquires C for retained owner");
  PurpleSyncOperations.end(key,owner,tokens[0]);PurpleSyncOperations.end(key,owner,tokens[1]);
  need(tokens[2].current()&&PurpleSyncOperations.busy(key)&&cWake.get()==0,"two late ends must not revoke C or wake its waiter");
  PurpleSyncOperations.end(key,owner,tokens[2]);need(!tokens[2].current()&&!PurpleSyncOperations.busy(key)&&cWake.get()==1,"C releases only with its own token exactly once");
  PurpleSyncOperations.end(key,owner,tokens[0]);PurpleSyncOperations.end(key,owner,tokens[1]);need(cWake.get()==1,"further late ends do not repeat C wakeup");
  System.out.println("CHAINED_LATE_ENDS cSurvived=true aWake="+aWake.get()+" bWake="+bWake.get()+" cWake="+cWake.get());
 }
 static void run(java.io.File root)throws Exception{main(new String[0]);}
 public static void main(String[] args)throws Exception{
  if(args.length>0&&args[0].equals("late-same-owner")){lateSameOwner();return;}
  if(args.length>0&&args[0].equals("chain")){chainedLateEnds();return;}
  replacement(false);replacement(true);retireEndRace();waiterRaces();acquisitions();callbackReplacement();lateSameOwner();chainedLateEnds();System.out.println("OPERATIONS_ACQUISITION_RACE_CHECKS="+checks);
 }
}
