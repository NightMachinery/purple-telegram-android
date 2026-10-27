package org.telegram.messenger.purple;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.ORIGIN;

final class PurpleSyncChatLogoutIntegrationCases {
 static int checks;
 static void need(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
 static void cleanup(String phase,boolean throwing,boolean foreign,boolean deferred,boolean logout)throws Exception {
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.heldClient=true;
  env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));env.world.add(mark(SELF,7,50));
  AtomicBoolean armed=new AtomicBoolean(),entered=new AtomicBoolean();AtomicInteger deliveries=new AtomicInteger(),workers=new AtomicInteger();
  PurpleSyncChatService[] service=new PurpleSyncChatService[1];PurpleSyncChatClient delegate=env.client();
  env.injectedClient=(PurpleSyncChatClient)Proxy.newProxyInstance(PurpleSyncChatClient.class.getClassLoader(),new Class<?>[]{PurpleSyncChatClient.class},(proxy,method,args)->{
   if(armed.get()&&method.getName().equals("cancel")&&entered.compareAndSet(false,true)){
    service[0].close(false);service[0].close(logout);service[0].close(logout);
    need(env.file.removes==0&&workers.get()==0,"logout defers until outer cleanup unwinds");
    if(throwing)throw new IllegalStateException("logout cancellation fixture");
   }
   try{return method.invoke(delegate,args);}catch(InvocationTargetException e){throw e.getCause();}
  });
  PurpleSyncChatService.Env wrapped=(PurpleSyncChatService.Env)Proxy.newProxyInstance(PurpleSyncChatService.Env.class.getClassLoader(),new Class<?>[]{PurpleSyncChatService.Env.class},(proxy,method,args)->{
   if(armed.get()&&method.getName().equals("worker"))workers.incrementAndGet();
   try{return method.invoke(env,args);}catch(InvocationTargetException e){throw e.getCause();}
  });
  service[0]=new PurpleSyncChatService(wrapped);service[0].ensureResolved(true,"config",null,v->deliveries.incrementAndGet());
  need(PurpleSyncOperations.busy(env.key),"discovery owns captured token");
  if(foreign)env.file.bytes=PurpleSyncChatCache.encode(ORIGIN,PurpleSyncChatCache.authority("22222222222222222222222222222222"),PurpleSyncChatModelCases.cache(SELF,7,50));
  byte[] before=env.file.bytes.clone();env.heldWorker=deferred;armed.set(true);boolean threw=false;
  try {
   if(phase.equals("finish")) {
    PurpleSyncChatDiscovery.Status status=service[0].status();
    PurpleSyncChatDiscovery.Status next=new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Searching,status.resolution,"fixture",false,true,env.clock+10000);
    PurpleSyncChatCleanupIntegrationCases.invoke(service[0],"finish",new Class<?>[]{PurpleSyncChatDiscovery.Status.class,long.class,boolean.class},next,PurpleSyncChatCleanupIntegrationCases.run(service[0]),false);
   }else PurpleSyncChatCleanupIntegrationCases.invoke(service[0],"abandon",new Class<?>[]{long.class},PurpleSyncChatCleanupIntegrationCases.run(service[0]));
  }catch(IllegalStateException expected){threw=true;}
  need(entered.get()&&threw==throwing,"actual cancellation callback reenters close");
  need(deliveries.get()==0,"closed request suppresses completion");
  need(!PurpleSyncOperations.busy(env.key),"exact token released despite logout and cleanup throw");
  need(env.timers.isEmpty(),"pending close drains watchdog and newly scheduled retry");
  need(workers.get()==(logout?1:0),"logout worker dispatched exactly once");
  if(deferred&&logout)need(Arrays.equals(before,env.file.bytes),"logout remains worker owned");
  while(!env.tasks.isEmpty())env.tasks.removeFirst().run();
  need(logout&&!foreign?env.file.bytes==null:Arrays.equals(before,env.file.bytes),"matching logout removes cache, foreign authority and close(false) retain it");
  need(env.file.removes==(logout&&!foreign?1:0),"cache removal count");
  service[0].close(logout);service[0].close(false);
  need(workers.get()==(logout?1:0),"later close does not redispatch logout");
  Object owner=new Object();PurpleSyncOperations.Capability fresh=PurpleSyncOperations.acquire(env.key,owner,()->true);
  need(fresh!=null&&fresh.current(),"fresh operation after reentrant logout");PurpleSyncOperations.end(env.key,owner,fresh);
 }
 static void deferredReplacement() {
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));
  PurpleSyncChatService service=new PurpleSyncChatService(env);env.heldWorker=true;service.close(true);
  env.file.bytes=PurpleSyncChatCache.encode(ORIGIN,PurpleSyncChatCache.authority("22222222222222222222222222222222"),PurpleSyncChatModelCases.cache(SELF,8,60));byte[] replacement=env.file.bytes.clone();
  while(!env.tasks.isEmpty())env.tasks.removeFirst().run();need(Arrays.equals(replacement,env.file.bytes)&&env.file.removes==0,"deferred logout cannot delete replacement authority");
 }
 static void queue(String phase,boolean throwing)throws Exception {
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.heldClient=true;
  env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));env.world.add(mark(SELF,7,50));
  AtomicBoolean entered=new AtomicBoolean();AtomicInteger first=new AtomicInteger(),second=new AtomicInteger();PurpleSyncChatService[] service=new PurpleSyncChatService[1];
  PurpleSyncChatService.Env wrapped=(PurpleSyncChatService.Env)Proxy.newProxyInstance(PurpleSyncChatService.Env.class.getClassLoader(),new Class<?>[]{PurpleSyncChatService.Env.class},(proxy,method,args)->{
   if(method.getName().equals("cancelInventory")&&entered.compareAndSet(false,true)){
    env.heldClient=false;service[0].ensureResolved(true,"config",null,status->{need(status.complete,"queued request resolves after cleanup");second.incrementAndGet();});
   }
   try{return method.invoke(env,args);}catch(InvocationTargetException e){throw e.getCause();}
  });
  service[0]=new PurpleSyncChatService(wrapped);service[0].ensureResolved(true,"config",null,status->{first.incrementAndGet();if(throwing)throw new IllegalStateException("completion fixture");});
  boolean threw=false;
  try {
   if(phase.equals("finish"))PurpleSyncChatCleanupIntegrationCases.invoke(service[0],"finish",new Class<?>[]{PurpleSyncChatDiscovery.Status.class,long.class,boolean.class},service[0].status(),PurpleSyncChatCleanupIntegrationCases.run(service[0]),false);
   else PurpleSyncChatCleanupIntegrationCases.invoke(service[0],"abandon",new Class<?>[]{long.class},PurpleSyncChatCleanupIntegrationCases.run(service[0]));
  }catch(IllegalStateException expected){threw=true;}
  need(first.get()==1&&second.get()==1&&threw==throwing,"completion exception cannot strand reentrant queued request "+phase);
  need(!PurpleSyncOperations.busy(env.key),"reentrant queued request releases its own token");service[0].close(false);
 }
 static void run()throws Exception {
  for(String phase:new String[]{"finish","abandon"})for(boolean throwing:new boolean[]{false,true})for(boolean foreign:new boolean[]{false,true})for(boolean deferred:new boolean[]{false,true})for(boolean logout:new boolean[]{false,true})cleanup(phase,throwing,foreign,deferred,logout);
  for(String phase:new String[]{"finish","abandon"})for(boolean throwing:new boolean[]{false,true})queue(phase,throwing);
  deferredReplacement();System.out.println("LOGOUT_CLEANUP_REGRESSION_CHECKS="+checks);
 }
}
