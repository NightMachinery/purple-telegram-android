package org.telegram.messenger.purple;

import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatCleanupIntegrationCases {
 static int checks;
 static void need(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
 static Object invoke(Object value,String name,Class<?>[] types,Object... args)throws Exception{
  Method method=value.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
  try{return method.invoke(value,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException)throw (RuntimeException)e.getCause();throw e;}
 }
 static long run(PurpleSyncChatService service)throws Exception{Field f=PurpleSyncChatService.class.getDeclaredField("generation");f.setAccessible(true);return f.getLong(service);}
 static void cleanup(String phase,String fault)throws Exception{
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.heldClient=true;
  env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));env.world.add(mark(SELF,7,50));
  AtomicBoolean armed=new AtomicBoolean(),spent=new AtomicBoolean();AtomicInteger failures=new AtomicInteger();
  PurpleSyncChatClient delegate=env.client();
  env.injectedClient=(PurpleSyncChatClient)Proxy.newProxyInstance(PurpleSyncChatClient.class.getClassLoader(),new Class<?>[]{PurpleSyncChatClient.class},(proxy,method,args)->{
   if(armed.get()&&fault.equals("cancel")&&method.getName().equals("cancel")&&spent.compareAndSet(false,true)){failures.incrementAndGet();throw new IllegalStateException("cleanup fixture");}
   try{return method.invoke(delegate,args);}catch(InvocationTargetException e){throw e.getCause();}
  });
  PurpleSyncChatService.Env wrapped=(PurpleSyncChatService.Env)Proxy.newProxyInstance(PurpleSyncChatService.Env.class.getClassLoader(),new Class<?>[]{PurpleSyncChatService.Env.class},(proxy,method,args)->{
   if(armed.get()&&method.getName().equals(fault)&&spent.compareAndSet(false,true)){failures.incrementAndGet();throw new IllegalStateException("cleanup fixture");}
   try{return method.invoke(env,args);}catch(InvocationTargetException e){throw e.getCause();}
  });
  PurpleSyncChatService service=new PurpleSyncChatService(wrapped);service.ensureResolved(true,"config",null,v->{});
  need(PurpleSyncOperations.busy(env.key),"held discovery owns scope before "+phase+" "+fault);armed.set(true);
  try{
   if(phase.equals("finish")){
    PurpleSyncChatDiscovery.Status current=service.status();boolean retry=fault.equals("delay");
    PurpleSyncChatDiscovery.Status status=new PurpleSyncChatDiscovery.Status(retry?PurpleSyncChatDiscovery.Phase.Searching:PurpleSyncChatDiscovery.Phase.Paused,current.resolution,"fixture",false,true,retry?env.clock+10000:0);
    invoke(service,"finish",new Class<?>[]{PurpleSyncChatDiscovery.Status.class,long.class,boolean.class},status,run(service),false);
   }else if(phase.equals("abandon"))invoke(service,"abandon",new Class<?>[]{long.class},run(service));
   else service.close(false);
  }catch(RuntimeException expected){}
  need(fault.equals("none")?failures.get()==0:failures.get()==1,"cleanup injection reached "+phase+" "+fault);
  need(!PurpleSyncOperations.busy(env.key),"finally releases before recovery close "+phase+" "+fault);
  try{service.close(false);}catch(RuntimeException expected){}
  Object owner=new Object();PurpleSyncOperations.Capability fresh=PurpleSyncOperations.acquire(env.key,owner,()->true);
  need(fresh!=null&&fresh.current(),"fresh acquisition after cleanup throw "+phase+" "+fault);
  PurpleSyncOperations.end(env.key,owner,fresh);
 }
 static void retirement(boolean workerFailure,boolean closeDuringRetirement)throws Exception{
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.throwWorker=workerFailure;
  env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));env.world.add(mark(SELF,7,50));
  AtomicBoolean live=new AtomicBoolean(true);Object blocker=new Object();PurpleSyncOperations.Capability token=PurpleSyncOperations.acquire(env.key,blocker,live::get);
  PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> done=new ArrayList<>();
  if(closeDuringRetirement)PurpleSyncOperations.whenIdle(env.key,new Object(),()->service.close(false));
  service.ensureResolved(true,"config",null,done::add);need(done.isEmpty(),"discovery waits behind blocker");live.set(false);
  service.ensureResolved(true,"config",null,done::add);service.close(false);
  need(closeDuringRetirement?done.isEmpty():done.size()==2,"retirement reentry completion "+workerFailure+" "+closeDuringRetirement);
  need(!PurpleSyncOperations.busy(env.key),"retirement reentry releases scope");
  Object owner=new Object();PurpleSyncOperations.Capability fresh=PurpleSyncOperations.acquire(env.key,owner,()->true);
  need(fresh!=null,"reentrant close or drained queue permits fresh acquisition");PurpleSyncOperations.end(env.key,owner,fresh);PurpleSyncOperations.end(env.key,blocker,token);
 }
 static void runner(File root)throws Exception{
  PurpleSyncChatManualIntegrationCases.Port p=new PurpleSyncChatManualIntegrationCases.Port(root,PurpleSyncChatRequestCases.CHANNEL);
  PurpleSyncChatManual manual=new PurpleSyncChatManual(p,()->p.foreground);
  PurpleSyncRunner runner=new PurpleSyncRunner(1,202,manual,manual,p::worker,p::ui);
  Object blocker=new Object();AtomicBoolean live=new AtomicBoolean(true);PurpleSyncOperations.Capability token=PurpleSyncOperations.acquire(p.key(),blocker,live::get);
  PurpleSyncOperations.whenIdle(p.key(),new Object(),runner::close);live.set(false);
  need(Boolean.FALSE.equals(invoke(runner,"begin",new Class<?>[0])),"Runner rejects acquisition after reentrant close");
  need(!PurpleSyncOperations.busy(p.key()),"Runner rejected acquisition is released");PurpleSyncOperations.end(p.key(),blocker,token);
 }
 static void run(File root)throws Exception{
  for(String phase:new String[]{"finish","abandon","close"})for(String fault:new String[]{"none","cancel","cancelInventory","cancelDelay"})cleanup(phase,fault);
  cleanup("finish","delay");
  for(boolean worker:new boolean[]{false,true})for(boolean close:new boolean[]{false,true})retirement(worker,close);
  runner(root);System.out.println("CLEANUP_ACQUIRE_REGRESSION_CHECKS="+checks);
 }
}
