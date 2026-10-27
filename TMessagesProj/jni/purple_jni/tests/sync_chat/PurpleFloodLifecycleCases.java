package org.telegram.messenger.purple;
import java.util.*;
import java.lang.reflect.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import org.telegram.tgnet.*;
public final class PurpleFloodLifecycleCases {
 static int failures,passes;
 static void invariant(boolean ok,String label){if(ok){passes++;System.out.println("PASS "+label);}else{failures++;System.out.println("FAIL "+label);}}
 static void need(boolean ok,String label){if(!ok)throw new AssertionError("Fixture: "+label);}
 static void localOpen(boolean borrowed){
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));env.world.add(mark(SELF,7,50));env.heldWorker=true;
  Object owner=new Object();PurpleSyncOperations.Capability ownerToken=borrowed?PurpleSyncOperations.acquire(env.key,owner,()->true):null;if(borrowed)need(ownerToken!=null,"borrowed lock");
  PurpleSyncChatService.Borrowed cap=borrowed?new PurpleSyncChatService.Borrowed(env.key,owner,()->true):null;
  PurpleSyncChatService service=new PurpleSyncChatService(env);
  List<PurpleSyncChatDiscovery.Status> first=new ArrayList<>(),forced=new ArrayList<>(),retire=new ArrayList<>();
  service.checkBeforePost("config",cap,first::add);service.ensureResolved(true,"config",cap,forced::add);service.checkBeforePost("config",cap,retire::add);
  env.through(121000);
  invariant(first.isEmpty()&&forced.isEmpty()&&retire.isEmpty(),"local timeout retains post/retire/forced callbacks borrowed="+borrowed);
  invariant(PurpleSyncOperations.owned(env.key,borrowed?owner:service),"original capability retained borrowed="+borrowed);
  env.tasks.remove().run();need(first.isEmpty(),"late old worker ignored");env.heldWorker=false;env.through(126000);
  invariant(first.size()==1&&forced.size()==1&&retire.size()==1&&first.get(0).complete&&forced.get(0).complete&&retire.get(0).complete&&new PurpleSyncChat.Status(first.get(0)).selfPostable()&&new PurpleSyncChat.Status(retire.get(0)).selfPostable(),"original and queued gates receive final fresh approval borrowed="+borrowed);
  invariant(borrowed?PurpleSyncOperations.owned(env.key,owner):!PurpleSyncOperations.busy(env.key),"lease release responsibility preserved borrowed="+borrowed);
  service.close(false);if(borrowed)PurpleSyncOperations.end(env.key,owner,ownerToken);
 }
 static void splitClock(){
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));
  Native wire=new Native();wire.clock=1800000000000L;env.rpcClock=wire.clock;env.injectedFactory=()->new PurpleSyncChatTelegram(ORIGIN,wire);
  PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> first=new ArrayList<>(),forced=new ArrayList<>();service.ensureResolved(true,"config",null,first::add);
  int reply=1;while(!(wire.requests.get(reply-1) instanceof TLRPC.TL_users_getFullUser)){need(reply<30,"full info reached");wire.reply(reply++,messages(mark(SELF,7,50)));}
  TLRPC.TL_error e=new TLRPC.TL_error();e.code=420;e.text="FLOOD_WAIT_90";wire.replies.get(reply).accept(null,e);
  invariant(service.status().retryAt==91000&&env.timers.values().contains(211000L),"split-clock cooldown and watchdog converted");service.ensureResolved(true,"config",null,forced::add);
  Runnable retry=wire.timers.keySet().iterator().next();wire.timers.remove(retry);wire.clock+=90000;env.clock=91000;env.rpcClock=wire.clock;env.heldWorker=true;retry.run();reply=wire.sent;
  while(env.tasks.isEmpty()&&reply<=wire.sent){need(reply<60,"read recovery bounded");TLObject request=wire.requests.get(reply-1);wire.reply(reply++,request instanceof TLRPC.TL_users_getFullUser?PurpleRpcRetryCases.full():messages(mark(SELF,7,50)));}
  need(!env.tasks.isEmpty()&&wire.timers.isEmpty(),"stalled non-RPC phase after request timer gone");wire.clock=1800000210000L;env.rpcClock=wire.clock;env.through(211000);
  invariant(first.isEmpty()&&forced.isEmpty()&&service.status().retryAt==216000&&env.timers.values().contains(216000L),"stalled non-RPC phase restarts with original callbacks");
  invariant(env.presentation.retryAt==1800000215000L,"presentation retains wall-clock date");env.tasks.remove().run();env.heldWorker=false;wire.clock+=5000;env.rpcClock=wire.clock;env.through(216000);reply=wire.sent;
  while((first.isEmpty()||forced.isEmpty())&&reply<=wire.sent){need(reply<100,"eventual split recovery bounded");TLObject request=wire.requests.get(reply-1);wire.reply(reply++,request instanceof TLRPC.TL_users_getFullUser?PurpleRpcRetryCases.full():messages(mark(SELF,7,50)));}
  invariant(first.size()==1&&forced.size()==1&&new PurpleSyncChat.Status(first.get(0)).selfPostable()&&new PurpleSyncChat.Status(forced.get(0)).selfPostable(),"split-clock original and forced caller eventually succeed");service.close(false);
 }
 static void cancelOrClose(boolean logout){
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));env.world.add(mark(SELF,7,50));env.heldWorker=true;
  Object old=new Object();PurpleSyncOperations.Capability oldToken=PurpleSyncOperations.acquire(env.key,old,()->true);need(oldToken!=null,"old owner acquired");PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> stale=new ArrayList<>();service.checkBeforePost("config",new PurpleSyncChatService.Borrowed(env.key,old,()->true),stale::add);env.through(121000);
  if(logout)service.close(false);else service.cancelBorrowed(old);PurpleSyncOperations.end(env.key,old,oldToken);env.heldWorker=false;
  if(logout)service=new PurpleSyncChatService(env);
  Object fresh=new Object();PurpleSyncOperations.Capability freshToken=PurpleSyncOperations.acquire(env.key,fresh,()->true);need(freshToken!=null,"fresh owner acquired");List<PurpleSyncChatDiscovery.Status> done=new ArrayList<>();service.checkBeforePost("config",new PurpleSyncChatService.Borrowed(env.key,fresh,()->true),done::add);
  env.tasks.remove().run();invariant(stale.isEmpty()&&done.size()==1&&new PurpleSyncChat.Status(done.get(0)).selfPostable(),"explicit "+(logout?"close/new-holder":"borrowed cancel")+" clears restart and allows fresh scope");service.close(false);PurpleSyncOperations.end(env.key,fresh,freshToken);
 }
 static void overdueForceRetained(){
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));env.world.add(mark(SELF,7,50));env.heldWorker=true;
  Object owner=new Object();PurpleSyncOperations.Capability ownerToken=PurpleSyncOperations.acquire(env.key,owner,()->true);need(ownerToken!=null,"overdue owner acquired");
  PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> first=new ArrayList<>(),forced=new ArrayList<>();
  service.checkBeforePost("config",new PurpleSyncChatService.Borrowed(env.key,owner,()->true),result->{first.add(result);PurpleSyncOperations.end(env.key,owner,ownerToken);});
  env.through(121000);env.clock=126001;service.ensureResolved(true,"config",null,forced::add);
  invariant(first.isEmpty()&&forced.isEmpty()&&PurpleSyncOperations.owned(env.key,owner),"overdue force preserves valid retained borrowed capability before retry delivery");
  env.tasks.remove().run();env.heldWorker=false;env.through(126001);
  invariant(first.size()==1&&forced.size()==1&&new PurpleSyncChat.Status(forced.get(0)).selfPostable(),"overdue force completes after retained owner releases original gate");service.close(false);
 }
 static void expiredCapability(boolean watchdog){
  PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));Native wire=new Native();env.injectedFactory=()->new PurpleSyncChatTelegram(ORIGIN,wire);
  boolean[] live={true};Object old=new Object();PurpleSyncOperations.Capability oldToken=PurpleSyncOperations.acquire(env.key,old,()->live[0]);need(oldToken!=null,"old owner acquired");PurpleSyncChatService service=new PurpleSyncChatService(env);
  service.checkBeforePost("config",new PurpleSyncChatService.Borrowed(env.key,old,()->live[0]),s->{});
  int reply=1;while(!(wire.requests.get(reply-1) instanceof TLRPC.TL_users_getFullUser)){need(reply<30,"full info reached");wire.reply(reply++,messages(mark(SELF,7,50)));}
  TLRPC.TL_error e=new TLRPC.TL_error();e.code=420;e.text="FLOOD_WAIT_90";wire.replies.get(reply).accept(null,e);
  live[0]=false;PurpleSyncOperations.end(env.key,old,oldToken);if(watchdog)env.through(211001);else env.clock=211001;
  Object fresh=new Object();PurpleSyncOperations.Capability freshToken=PurpleSyncOperations.acquire(env.key,fresh,()->true);need(freshToken!=null,"new operation may acquire released old capability");int before=env.clients;List<PurpleSyncChatDiscovery.Status> results=new ArrayList<>();service.ensureResolved(true,"config",new PurpleSyncChatService.Borrowed(env.key,fresh,()->true),results::add);
  System.out.println("EXPIRED_CAPABILITY elapsed="+env.clock+" oldRetryAt="+service.status().retryAt+" clientsBefore="+before+" clientsAfter="+env.clients+" serviceTimers="+env.timers.size()+" newCallbacks="+results.size());
  invariant(env.clients>before,"retired capability cannot strand overdue forced caller");service.close(false);PurpleSyncOperations.end(env.key,fresh,freshToken);
 }
 public static void main(String[] args)throws Exception{PurpleCore.ensureLoaded();localOpen(false);localOpen(true);splitClock();cancelOrClose(false);cancelOrClose(true);overdueForceRetained();expiredCapability(true);expiredCapability(false);System.out.println("RECHECK_INDEPENDENT passed="+passes+" invariantFailures="+failures);if(failures!=0)throw new AssertionError("lifecycle regression");}
}
