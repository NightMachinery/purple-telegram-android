package org.telegram.messenger.purple;

import java.lang.reflect.Proxy;
import java.util.*;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

final class PurpleSyncManagedReadCases {
    static final class Wire implements PurpleRpcRequest.Port {
        long clock;int sends,cancelled;
        final Map<Integer,PurpleRpcRequest.Reply> replies=new LinkedHashMap<>();
        final Map<Runnable,Long> timers=new LinkedHashMap<>();
        PurpleRpcRetry.Transient wait;
        public boolean current(){return true;}public long now(){return clock;}public void dispatch(Runnable task){task.run();}
        public void delay(Runnable task,long millis){timers.put(task,PurpleRpcRetry.add(clock,millis));}public void cancelDelay(Runnable task){timers.remove(task);}
        public int send(TLObject request,PurpleRpcRequest.Reply done){replies.put(++sends,done);return sends;}
        public void cancel(int token){cancelled++;}public void waiting(PurpleRpcRetry.Transient value){wait=value;}
        void through(long next){clock=next;for(Map.Entry<Runnable,Long> timer:new ArrayList<>(timers.entrySet()))if(timer.getValue()<=clock&&timers.remove(timer.getKey())!=null)timer.getKey().run();}
    }
    static final class Run {
        final Wire wire=new Wire();
        final PurpleSyncManagedReads reads=new PurpleSyncManagedReads(()->wire.clock);
        final PurpleRpcRequest.Stream stream=new PurpleRpcRequest.Stream();
        final List<PurpleSyncAutoEngine.Observed> results=new ArrayList<>();
        final PurpleSyncChatOrigin origin=new PurpleSyncChatOrigin(0,0,11,"managed-read-test");
        final PurpleSyncClient.Message candidate=new PurpleSyncClient.Message(7,true,false,true,PurpleSyncPost.CAPTION,PurpleSyncPost.RECORD_FILE_NAME,true,100,64,0,new Object());
        PurpleRpcRequest request;
        final PurpleSyncClient wrapped;
        final PurpleSyncAutoNetwork network;
        final Runnable outer;
        int downloads,cancelDownloads;
        Run(){
            PurpleSyncClient client=(PurpleSyncClient)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PurpleSyncClient.class},(proxy,method,args)->{
                switch(method.getName()){
                    case "isActive":return true;case "userId":return 11L;case "chat":return PurpleSyncChatCore.Peer.self();case "origin":return origin;case "dialogId":return 11L;case "nowMillis":return wire.clock;
                    case "runOnWorker":case "runOnMain":((Runnable)args[0]).run();return null;
                    case "hasQueuedSync":return false;case "managedReads":return reads.state();
                    case "transientRetryAt":return wire.wait!=null&&wire.wait.retryAt>wire.clock?wire.wait.retryAt:0L;
                    case "localCopies":((PurpleSyncClient.Reply)args[0]).onReply(Collections.emptyList());return null;
                    case "requestHistory":
                        if((Integer)args[0]>0){((PurpleSyncClient.Reply<PurpleSyncClient.History>)args[2]).onReply(PurpleSyncClient.History.page(Collections.emptyList(),1));return 0;}
                        Runnable retired=reads.begin();PurpleSyncClient.Reply<PurpleSyncClient.History> done=(PurpleSyncClient.Reply<PurpleSyncClient.History>)args[2];
                        request=new PurpleRpcRequest(stream,wire,new TLObject(),30000,(response,error)->done.onReply(error==null?PurpleSyncClient.History.page(Collections.singletonList(candidate),1):PurpleSyncClient.History.failed("permanent")),ended->retired.run());request.start();return request.id;
                    case "requestMessage":((PurpleSyncClient.Reply<PurpleSyncClient.Lookup>)args[1]).onReply(PurpleSyncClient.Lookup.found(candidate));return 2;
                    case "contains":return args[0]!=null&&PurpleSyncChatCore.Peer.self().equals(((PurpleSyncClient.Message)args[0]).chat);
                    case "cancelRequest":if(request!=null)request.cancel();return null;
                    case "download":downloads++;return new Object();case "cancelDownload":cancelDownloads++;return null;
                    case "log":return null;
                }
                throw new AssertionError("Unexpected endpoint "+method.getName());
            });
            wrapped=new PurpleSyncAutoClient(new PurpleSyncChatRecordClient(client,message->PurpleSyncChatCore.Ownership.Own),()->true);
            Set<Runnable> before=new HashSet<>(org.telegram.messenger.AndroidUtilities.delayed.keySet());
            network=new PurpleSyncAutoNetwork(wrapped,"managed",()->true);network.check(new int[0],()->true,results::add);
            List<Runnable> added=new ArrayList<>();for(Runnable task:org.telegram.messenger.AndroidUtilities.delayed.keySet())if(!before.contains(task))added.add(task);
            check(added.size()==1,"one aggregate timer per operation");outer=added.get(0);
        }
        void advance(long at){for(long next:new long[]{30000,35000,65000,75000,105000,125000,155000,195000,225000,285000,315000})if(next<=at&&next>=wire.clock)wire.through(next);wire.clock=at;}
        void expire(){org.telegram.messenger.AndroidUtilities.delayed.remove(outer);outer.run();}
        void close(){network.cancel();check(stream.empty()&&!wrapped.managedReads().active,"cancel retires logical read and wrapper ownership");check(!org.telegram.messenger.AndroidUtilities.delayed.containsKey(outer),"cancel removes aggregate retry timer");}
    }
    static void run()throws Exception{
        int before=checks;
        PurpleSyncReadLifetimeCases.run();
        Wire clock=new Wire();PurpleSyncManagedReads tracker=new PurpleSyncManagedReads(()->clock.clock);Runnable first=tracker.begin(),second=tracker.begin();first.run();first.run();check(tracker.state().active&&tracker.state().revision==1,"read ownership counts queued logical reads and retires once");clock.clock=9;second.run();check(!tracker.state().active&&tracker.state().revision==2&&tracker.state().completedAt==9,"final read retirement timestamps local phase");
        for(long at:new long[]{225000,284999,285000,300000,315000}){
            Run run=new Run();run.advance(at);run.expire();
            check(run.results.isEmpty()&&run.wrapped.managedReads().active&&!run.stream.empty(),"aggregate never finalizes managed wait/attempt at "+at);
            check(org.telegram.messenger.AndroidUtilities.delayed.containsKey(run.outer),"aggregate reschedules while managed at "+at);run.close();
        }
        Run boundary=new Run();boundary.advance(300000);boundary.wire.clock=315000;boundary.expire();check(boundary.results.isEmpty(),"aggregate-first order at same network deadline stays transient");boundary.wire.through(315000);check(boundary.wire.wait.kind==PurpleRpcRetry.Kind.Timeout,"own timeout then schedules typed transient");boundary.close();
        Run local=new Run();local.advance(300000);local.expire();check(local.wire.sends==6&&local.results.isEmpty(),"reviewer six-attempt/five-timeout reproduction stays pending");
        local.wire.clock=300001;local.wire.replies.get(6).accept(new TLObject(),null);
        check(!local.wrapped.managedReads().active&&local.stream.empty()&&local.downloads==1&&local.results.isEmpty(),"metadata retires before a separately owned download phase");
        local.expire();check(local.results.isEmpty()&&org.telegram.messenger.AndroidUtilities.delayed.get(local.outer)==300000L,"uncovered download gets its own bounded interval after managed metadata");
        local.wire.clock=600000;local.expire();check(local.results.isEmpty(),"local phase not expired one millisecond early");local.wire.clock=600001;local.expire();
        check(local.results.size()==1&&local.results.get(0)==null&&local.cancelDownloads==1,"uncovered download remains bounded and cancels owned load");local.close();
        Run permanent=new Run();TLRPC.TL_error error=new TLRPC.TL_error();error.code=400;error.text="CHANNEL_PRIVATE";permanent.wire.replies.get(1).accept(null,error);
        check(permanent.results.size()==1&&permanent.results.get(0)!=null&&!permanent.results.get(0).inventory.scanComplete&&!permanent.wrapped.managedReads().active,"permanent read retains original failure mapping");permanent.close();
        System.out.println("MANAGED_READ_WATCHDOG_CHECKS="+(checks-before));
    }
}
