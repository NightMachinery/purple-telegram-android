package org.telegram.messenger.purple;

import java.util.*;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleRpcRetryCases {
    static void policy(){
        for(String prefix:new String[]{"FLOOD_WAIT_","FLOOD_PREMIUM_WAIT_"})for(int seconds:new int[]{0,1,8,30,31,90}){
            PurpleRpcRetry.Budget budget=new PurpleRpcRetry.Budget(1000,60000);
            PurpleRpcRetry.Transient wait=budget.failed(2000,420,prefix+seconds,false);
            boolean shortWait=seconds<=30;
            check(wait.kind==PurpleRpcRetry.Kind.Flood&&wait.shortRetry==shortWait,"flood classification "+prefix+seconds);
            check(wait.retryAt==2000+(shortWait?seconds*1000+1000:seconds*1000),"flood retryAt "+seconds);
            check(budget.deadline()==61000+(shortWait?seconds*1000+1000:0),"deadline extends by wait only");
            budget.resume(wait.retryAt,wait);
            check(!budget.failed(wait.retryAt,420,prefix+8,false).shortRetry||!shortWait,"at most one short retry per request");
        }
        for(long remaining:new long[]{8999,9000,9001}){
            PurpleRpcRetry.Budget budget=new PurpleRpcRetry.Budget(0,remaining);
            PurpleRpcRetry.Transient wait=budget.failed(0,420,"FLOOD_WAIT_8",false);
            check(wait.shortRetry==(remaining>=9000),"remaining deadline boundary "+remaining);
        }
        for(int code:new int[]{500,501,502,503,504,599,-1,-1000}){
            PurpleRpcRetry.Budget budget=new PurpleRpcRetry.Budget(1000,30000);
            for(long delay:new long[]{5000,10000,20000,40000,60000,60000}){
                PurpleRpcRetry.Transient wait=budget.failed(2000,code,"ERR",false);
                check(!wait.shortRetry&&wait.retryAt==2000+delay,"bounded backoff "+code+"/"+delay);
                check(wait.kind==(code<0?PurpleRpcRetry.Kind.Transport:PurpleRpcRetry.Kind.Server),"transient kind");
                budget.resume(2000+delay,wait);check(budget.deadline()==32000+delay,"caller reschedule fresh bounded network deadline");
            }
        }
        PurpleRpcRetry.Budget budget=new PurpleRpcRetry.Budget(1000,10000);
        check(budget.failed(12000,-504,"REQUEST_TIMEOUT",true).kind==PurpleRpcRetry.Kind.Timeout,"own timeout distinct");
        for(String type:new String[]{"CHANNEL_PRIVATE","USER_ID_INVALID","PEER_ID_INVALID","FLOOD_WAIT_-1","FLOOD_WAIT_8garbage","FLOOD_PREMIUM_WAIT_","MESSAGE_ID_INVALID"})check(budget.failed(12000,400,type,false)==null,"permanent unchanged "+type);
        check(budget.failed(12000,400,"INTERNAL",false).kind==PurpleRpcRetry.Kind.Server,"internal text");
        check(budget.failed(12000,420,"FLOOD_WAIT_99999999999999999999999999999999999999",false).retryAt==Long.MAX_VALUE,"overflow flood saturates");
    }
    static final class Request extends TLObject {int freed;@Override public void freeResources(){if(!disableFree)freed++;}}
    static final class Port implements PurpleRpcRequest.Port {
        boolean active=true,immediate,throwObserver;long clock=1000;int sends,cancels;
        final Map<Integer,PurpleRpcRequest.Reply> replies=new LinkedHashMap<>();
        final Map<Runnable,Long> timers=new LinkedHashMap<>();
        final List<PurpleRpcRetry.Transient> waits=new ArrayList<>();
        final List<TLObject> objects=new ArrayList<>();
        public boolean current(){return active;}public long now(){return clock;}public void dispatch(Runnable task){task.run();}
        public void delay(Runnable task,long millis){timers.put(task,PurpleRpcRetry.add(clock,millis));}public void cancelDelay(Runnable task){timers.remove(task);}
        public int send(TLObject request,PurpleRpcRequest.Reply reply){objects.add(request);check(request.disableFree,"native retry retains request resources");replies.put(++sends,reply);if(immediate)reply.accept(new TLObject(),null);return sends;}
        public void cancel(int token){cancels++;}public void waiting(PurpleRpcRetry.Transient wait){waits.add(wait);if(throwObserver)throw new IllegalStateException("observer");}
        void error(int token,int code,String text){TLRPC.TL_error error=new TLRPC.TL_error();error.code=code;error.text=text;replies.get(token).accept(null,error);}
        void through(long next){clock=next;for(Map.Entry<Runnable,Long> timer:new ArrayList<>(timers.entrySet()))if(timer.getValue()<=clock&&timers.remove(timer.getKey())!=null)timer.getKey().run();}
    }
    static void lifecycle(){
        Port port=new Port();PurpleRpcRequest.Stream stream=new PurpleRpcRequest.Stream();Request request=new Request();int[] replies={0},ended={0};
        PurpleRpcRequest call=new PurpleRpcRequest(stream,port,request,30000,(r,e)->replies[0]++,r->ended[0]++);call.start();
        check(call.id<0,"owned read tokens cannot collide with native write tokens");port.error(1,420,"FLOOD_WAIT_8");
        check(replies[0]==0&&ended[0]==0&&stream.pendingRetries()==1&&port.timers.size()==1,"short flood not final null and one timer");
        port.through(9999);check(port.sends==1,"not before X+1");port.through(10000);check(port.sends==2&&port.objects.get(0)==port.objects.get(1),"same request retries once");
        port.error(2,420,"FLOOD_WAIT_8");check(!port.waits.get(1).shortRetry&&stream.pendingRetries()==1,"second flood distinct caller reschedule");
        port.through(18000);check(port.sends==3,"caller resumes after retryAt");port.replies.get(1).accept(new TLObject(),null);check(replies[0]==0,"late first answer ignored");
        port.replies.get(3).accept(new TLObject(),null);check(replies[0]==1&&ended[0]==1&&request.freed==1&&port.timers.isEmpty(),"single completion frees exactly once");
        port.replies.get(3).accept(new TLObject(),null);check(replies[0]==1,"duplicate answer ignored");
        for(String type:new String[]{"FLOOD_WAIT_90","FLOOD_PREMIUM_WAIT_90","INTERNAL","TRANSPORT"}){
            Port p=new Port();PurpleRpcRequest.Stream s=new PurpleRpcRequest.Stream();Request first=new Request(),second=new Request();int[] delivered={0};
            PurpleRpcRequest a=new PurpleRpcRequest(s,p,first,30000,(r,e)->delivered[0]++,r->{}),b=new PurpleRpcRequest(s,p,second,30000,(r,e)->delivered[0]++,r->{});a.start();b.start();
            p.error(1,type.equals("INTERNAL")?503:type.equals("TRANSPORT")?-1:420,type);
            check(p.sends==1&&s.pendingRetries()==1&&delivered[0]==0,"one pending retry per stream "+type);
            p.active=false;a.checkCurrent();b.checkCurrent();check(s.empty()&&s.pendingRetries()==0&&p.timers.isEmpty()&&delivered[0]==0&&first.freed==1&&second.freed==1,"logout/slot reuse cancels old stream "+type);
        }
        Port p=new Port();Request own=new Request();PurpleRpcRequest.Stream s=new PurpleRpcRequest.Stream();int[] delivered={0};PurpleRpcRequest a=new PurpleRpcRequest(s,p,own,30000,(r,e)->delivered[0]++,r->{});a.start();p.through(31000);
        check(p.waits.get(0).kind==PurpleRpcRetry.Kind.Timeout&&delivered[0]==0&&p.cancels==1&&s.pendingRetries()==1,"own network timeout transient cancels old token");a.cancel();check(p.timers.isEmpty()&&own.freed==1,"cancel waiting request");
        p=new Port();own=new Request();own.disableFree=true;s=new PurpleRpcRequest.Stream();a=new PurpleRpcRequest(s,p,own,30000,(r,e)->{check(e!=null&&e.text.equals("CHANNEL_PRIVATE"),"permanent delivered unchanged");delivered[0]++;},r->{});a.start();p.error(1,400,"CHANNEL_PRIVATE");check(delivered[0]==1&&own.disableFree&&own.freed==0,"borrowed request ownership preserved");
        p=new Port();p.immediate=true;own=new Request();a=new PurpleRpcRequest(new PurpleRpcRequest.Stream(),p,own,30000,(r,e)->delivered[0]++,r->{});a.start();check(delivered[0]==2&&own.freed==1&&p.timers.isEmpty(),"synchronous callback lifecycle");
    }
    static void observer(){
        Port p=new Port();p.throwObserver=true;Request request=new Request();int[] delivered={0};
        PurpleRpcRequest.Stream stream=new PurpleRpcRequest.Stream();PurpleRpcRequest call=new PurpleRpcRequest(stream,p,request,30000,(r,e)->delivered[0]++,r->{});call.start();p.error(1,420,"FLOOD_WAIT_8");
        check(stream.pendingRetries()==1&&delivered[0]==0,"observer failure does not erase retry");p.through(10000);p.replies.get(2).accept(new TLObject(),null);check(delivered[0]==1,"retry survives throwing observer");
    }
    static TLRPC.TL_users_userFull full(){PurpleSyncChatFacts.Input input=input(SELF);TLRPC.TL_users_userFull full=new TLRPC.TL_users_userFull();full.full_user=input.userFull;full.users.add(input.user);return full;}
    static void client(){
        for(int seconds:new int[]{8,90}){
            Native port=new Native();PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,port);List<PurpleSyncChatClient.Info> replies=new ArrayList<>();List<PurpleRpcRetry.Transient> waits=new ArrayList<>();client.transientWait(waits::add);client.info(SELF,replies::add);
            check(port.requests.get(0) instanceof TLRPC.TL_users_getFullUser,"fresh Self uses full-info");TLRPC.TL_error flood=new TLRPC.TL_error();flood.code=420;flood.text="FLOOD_WAIT_"+seconds;port.replies.get(1).accept(null,flood);
            check(replies.isEmpty()&&waits.size()==1&&waits.get(0).retryAt==port.clock+(seconds+(seconds<=30?1:0))*1000L,"real TL Self flood never final Unknown "+seconds);
            Runnable retry=port.timers.keySet().iterator().next();port.timers.remove(retry);port.clock=waits.get(0).retryAt;retry.run();port.reply(2,full());check(replies.size()==1&&replies.get(0).readable(),"fresh Self recovered after flood");client.cancel();
        }
        PurpleSyncChatModelCases.World world=new PurpleSyncChatModelCases.World();world.peers.put(SELF,new PurpleSyncChatClient.Info(ORIGIN,SELF,null,PurpleSyncChatClient.Error.Unknown,"",null,false));
        PurpleSyncChatDiscovery model=new PurpleSyncChatDiscovery(ORIGIN,null,true,SELF,Collections.emptyList(),"config");model.start(1000);
        world.drain(model,1000);
        check(model.status().phase==PurpleSyncChatDiscovery.Phase.Paused&&model.status().reason.equals("UnknownFacts"),"baseline flood-to-null mapping reproduces Paused/UnknownFacts");
        System.out.println("FLOOD_BASELINE=Paused/UnknownFacts; FIXED=transient/retryAt then readable");
    }
    static void service(){
        for(int seconds:new int[]{8,90})for(boolean splitClock:new boolean[]{false,true}){
            PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));
            byte[] cache=env.file.bytes.clone();Native nativePort=new Native();if(splitClock){nativePort.clock=1800000000000L;env.rpcClock=nativePort.clock;}PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,nativePort);env.injectedFactory=()->new PurpleSyncChatTelegram(ORIGIN,nativePort);
            PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> results=new ArrayList<>();service.ensureResolved(true,"config",null,results::add);
            int reply=1;
            while(reply<=nativePort.sent&&!(nativePort.requests.get(reply-1) instanceof TLRPC.TL_users_getFullUser)){
                check(reply<30,"service reaches fresh Self full info");nativePort.reply(reply++,messages(mark(SELF,7,50)));
            }
            check(reply<=nativePort.sent,"fresh Self request pending");TLRPC.TL_error flood=new TLRPC.TL_error();flood.code=420;flood.text="FLOOD_WAIT_"+seconds;nativePort.replies.get(reply).accept(null,flood);
            check(results.isEmpty()&&service.status().phase==PurpleSyncChatDiscovery.Phase.Searching&&service.status().reason.equals("TelegramWait")&&service.status().postingHeld,"production service flood stays Searching/TelegramWait "+seconds);
            check(Arrays.equals(cache,env.file.bytes)&&env.file.writes==0,"flood preserves exact cache bytes");
            List<PurpleSyncChatDiscovery.Status> forced=new ArrayList<>();int sent=nativePort.sent;service.ensureResolved(true,"config",null,forced::add);
            check(forced.isEmpty()&&nativePort.sent==sent,"forced resolution queues without a final transient completion or cooldown bypass");
            check(service.status().retryAt==env.clock+(seconds+(seconds<=30?1:0))*1000L,"RPC wall deadline rebased to service elapsed clock");
            Runnable retry=nativePort.timers.keySet().iterator().next();nativePort.timers.remove(retry);long wait=service.status().retryAt-env.clock;nativePort.clock+=wait;env.clock+=wait;if(splitClock)env.rpcClock=nativePort.clock;retry.run();reply=nativePort.sent;
            while((results.isEmpty()||forced.isEmpty())&&reply<=nativePort.sent){
                check(reply<60,"service recovery bounded");TLObject request=nativePort.requests.get(reply-1);nativePort.reply(reply++,request instanceof TLRPC.TL_users_getFullUser?full():messages(mark(SELF,7,50)));
            }
            check(results.size()==1&&results.get(0).phase==PurpleSyncChatDiscovery.Phase.Use&&forced.size()==1&&forced.get(0).phase==PurpleSyncChatDiscovery.Phase.Use,"production service and queued caller recover to Use after flood "+seconds+" split="+splitClock);service.close(false);
            check(nativePort.timers.isEmpty()&&!PurpleSyncOperations.busy(env.key),"service close releases timers and account lease");
        }
    }
    static void stalledSplitClock(){
        PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.seed(PurpleSyncChatModelCases.cache(SELF,7,50));
        Native port=new Native();port.clock=1800000000000L;env.rpcClock=port.clock;env.injectedFactory=()->new PurpleSyncChatTelegram(ORIGIN,port);
        PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> results=new ArrayList<>();service.ensureResolved(true,"config",null,results::add);
        int reply=1;while(!(port.requests.get(reply-1) instanceof TLRPC.TL_users_getFullUser)){check(reply<30,"stalled split-clock reaches full info");port.reply(reply++,messages(mark(SELF,7,50)));}
        TLRPC.TL_error flood=new TLRPC.TL_error();flood.code=420;flood.text="FLOOD_WAIT_90";port.replies.get(reply).accept(null,flood);
        check(service.status().retryAt==91000&&env.timers.values().contains(211000L),"wall deadline converted to elapsed cooldown and watchdog");
        List<PurpleSyncChatDiscovery.Status> forced=new ArrayList<>();service.ensureResolved(true,"config",null,forced::add);check(forced.isEmpty(),"split-clock force comparison retains caller");
        Runnable retry=port.timers.keySet().iterator().next();port.timers.remove(retry);port.clock+=90000;env.clock=91000;env.rpcClock=port.clock;env.heldWorker=true;retry.run();reply=port.sent;
        while(env.tasks.isEmpty()&&reply<=port.sent){check(reply<60,"split-clock read recovery bounded");TLObject request=port.requests.get(reply-1);port.reply(reply++,request instanceof TLRPC.TL_users_getFullUser?full():messages(mark(SELF,7,50)));}
        check(!env.tasks.isEmpty()&&port.timers.isEmpty()&&results.isEmpty(),"RPC timer ends before stalled local cache continuation");
        port.clock=1800000209999L;env.rpcClock=port.clock;env.through(210999);check(results.isEmpty()&&service.status().retryAt==91000,"non-RPC phase bounded at expected elapsed time");
        port.clock=1800000210000L;env.rpcClock=port.clock;env.through(211000);check(results.isEmpty()&&service.status().retryAt==216000&&env.timers.values().contains(216000L),"stalled non-RPC watchdog restarts at elapsed deadline");
        check(env.presentation!=null&&env.presentation.retryAt==1800000215000L,"UI retry deadline retains wall clock independently");
        check(PurpleSyncOperations.owned(env.key,service),"stalled local restart retains original account capability");
        env.tasks.remove().run();check(results.isEmpty(),"stale disk continuation cannot complete restarted run");
        env.heldWorker=false;port.clock=1800000215000L;env.rpcClock=port.clock;env.through(216000);reply=port.sent;
        while((results.isEmpty()||forced.isEmpty())&&reply<=port.sent){check(reply<100,"split-clock eventual recovery bounded");TLObject request=port.requests.get(reply-1);port.reply(reply++,request instanceof TLRPC.TL_users_getFullUser?full():messages(mark(SELF,7,50)));}
        check(results.size()==1&&forced.size()==1&&results.get(0).phase==PurpleSyncChatDiscovery.Phase.Use&&forced.get(0).phase==PurpleSyncChatDiscovery.Phase.Use,"split-clock stalled local phase preserves both eventual completions");service.close(false);
        check(env.timers.isEmpty()&&!PurpleSyncOperations.busy(env.key),"split-clock close releases own timer/lease");
    }
    static void run(){int before=checks;policy();lifecycle();observer();client();service();stalledSplitClock();System.out.println("RPC_RETRY_CHECKS="+(checks-before));}
}
