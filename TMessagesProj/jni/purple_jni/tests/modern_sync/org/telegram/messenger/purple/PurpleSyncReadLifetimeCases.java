package org.telegram.messenger.purple;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;
import org.telegram.tgnet.TLObject;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

final class PurpleSyncReadLifetimeCases {
    static final class Run {
        final PurpleSyncManagedReadCases.Wire wire=new PurpleSyncManagedReadCases.Wire();
        final PurpleSyncManagedReads reads=new PurpleSyncManagedReads(()->wire.clock);
        final PurpleRpcRequest.Stream stream=new PurpleRpcRequest.Stream();
        final Map<Integer,PurpleRpcRequest> requests=new LinkedHashMap<>();
        final List<Object> replies=new ArrayList<>();
        final byte[] record;
        final File file;
        final PurpleSyncClient client;
        final PurpleSyncAutoNetwork network;
        final PurpleSyncClient.Message candidate;
        boolean active=true,holdDownload;
        int cancelReads,writes,downloads;
        Run()throws Exception {
            record=PurpleAccountSyncCore.buildConfigRecord(OWN,new byte[0][],PurpleAccountSyncCore.formatTimeOrderedSpaceId(1800000000000L).id,install(2),device(2),"Android","fixture",1,1700000000L).record;
            check(PurpleSyncPost.validRecord(record),"exact lifetime canonical record");
            file=File.createTempFile("owned-exact-",".json");Files.write(file.toPath(),record);
            candidate=new PurpleSyncClient.Message(7,true,false,true,PurpleSyncPost.CAPTION,PurpleSyncPost.RECORD_FILE_NAME,true,100,record.length,0,new Object());
            client=(PurpleSyncClient)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PurpleSyncClient.class},(proxy,m,args)->{
                switch(m.getName()) {
                    case "isActive":return true;case "userId":return 11L;case "chat":return PurpleSyncChatCore.Peer.self();case "dialogId":return 11L;case "origin":return new PurpleSyncChatOrigin(0,0,11,"exact-lifetime");
                    case "nowMillis":return wire.clock;case "managedReads":return reads.state();case "transientRetryAt":return wire.wait!=null&&wire.wait.retryAt>wire.clock?wire.wait.retryAt:0L;
                    case "runOnMain":case "runOnWorker":((Runnable)args[0]).run();return null;
                    case "hasQueuedSync":return false;case "localCopies":((PurpleSyncClient.Reply)args[0]).onReply(Collections.emptyList());return null;
                    case "requestHistory":((PurpleSyncClient.Reply<PurpleSyncClient.History>)args[2]).onReply(PurpleSyncClient.History.page(Collections.emptyList(),0));return 0;
                    case "requestMessage":
                        Runnable retired=reads.begin();PurpleSyncClient.Reply<PurpleSyncClient.Lookup> done=(PurpleSyncClient.Reply<PurpleSyncClient.Lookup>)args[1];
                        PurpleRpcRequest request=new PurpleRpcRequest(stream,wire,new TLObject(),30000,(o,e)->done.onReply(e==null?PurpleSyncClient.Lookup.found(candidate):PurpleSyncClient.Lookup.failed("permanent")),q->retired.run());
                        requests.put(request.id,request);request.start();return request.id;
                    case "cancelRequest":cancelReads++;PurpleRpcRequest cancelled=requests.get((Integer)args[0]);if(cancelled!=null)cancelled.cancel();return null;
                    case "download":downloads++;if(!holdDownload)((PurpleSyncClient.Reply<File>)args[1]).onReply(file);return new Object();case "cancelDownload":return null;
                    case "contains":return args[0]==candidate;case "log":return null;
                    case "deleteOwn":case "edit":case "send":writes++;throw new AssertionError("unexpected write after retired read");
                }
                throw new AssertionError("Unexpected "+m.getName());
            });
            network=new PurpleSyncAutoNetwork(client,"scope",()->active);
        }
        void start(String route) {
            if(route.equals("retire"))network.retire(7,record,()->active,replies::add);
            else if(route.equals("edit"))network.send(true,7,record,record,()->active,replies::add);
            else if(route.equals("check"))network.check(new int[]{7},()->active,replies::add);
            else network.exact(7,()->active,replies::add);
        }
        void reply(){wire.replies.get(wire.sends).accept(new TLObject(),null);}
        void stop(){active=false;network.cancel();}
    }
    static void run()throws Exception {
        int before=checks;
        for(String route:new String[]{"retire","edit","check","verification"})for(int phase:new int[]{1,2}) {
            Run run=new Run();run.start(route);
            if(phase==2)run.reply();
            check(run.wire.sends==phase&&run.reads.state().active,"owned exact phase "+route+phase);
            PurpleRpcRequest.Reply stale=run.wire.replies.get(phase);
            run.stop();long revision=run.reads.state().revision;
            check(!run.reads.state().active&&run.stream.empty()&&run.cancelReads>0,"cancel retires exact wire and ownership "+route+phase);
            run.network.cancel();stale.accept(new TLObject(),null);
            check(run.reads.state().revision==revision&&run.replies.isEmpty()&&run.writes==0,"late exact callback is fenced and retires once "+route+phase);
            run.active=true;List<PurpleSyncAutoEngine.Observed> fresh=new ArrayList<>();PurpleSyncAutoNetwork next=new PurpleSyncAutoNetwork(run.client,"reenabled",()->run.active);
            next.check(new int[0],()->true,fresh::add);
            check(fresh.size()==1&&fresh.get(0).inventory.scanComplete,"disable/re-enable progresses after exact cancellation "+route+phase);
            int sends=run.wire.sends;run.wire.through(3600000);
            check(run.wire.sends==sends&&!run.reads.state().active,"retired exact cannot retry an hour later "+route+phase);next.cancel();
        }
        Run isolated=new Run();isolated.holdDownload=true;
        isolated.client.requestMessage(7,ignored->{});
        Set<Runnable> previous=new HashSet<>(org.telegram.messenger.AndroidUtilities.delayed.keySet());isolated.start("retire");
        Runnable timer=null;for(Runnable task:org.telegram.messenger.AndroidUtilities.delayed.keySet())if(!previous.contains(task))timer=task;
        check(timer!=null,"isolated pending timer captured");
        isolated.wire.clock=300000;timer.run();
        check(isolated.replies.isEmpty(),"owned queued exact still protects its scope");
        isolated.stop();check(isolated.reads.state().active,"cancelling one scope preserves unrelated logical read");
        for(PurpleRpcRequest request:isolated.requests.values())request.cancel();
        check(!isolated.reads.state().active&&isolated.stream.empty(),"unrelated owner explicitly retires itself");
        Run uncovered=new Run();uncovered.holdDownload=true;
        Set<Runnable> oldTimers=new HashSet<>(org.telegram.messenger.AndroidUtilities.delayed.keySet());uncovered.start("verification");uncovered.reply();
        Runnable localTimer=null;for(Runnable task:org.telegram.messenger.AndroidUtilities.delayed.keySet())if(!oldTimers.contains(task))localTimer=task;
        check(uncovered.downloads==1&&!uncovered.reads.state().active,"owned metadata enters uncovered download");
        uncovered.client.requestMessage(8,ignored->{});check(uncovered.reads.state().active,"unrelated logical read stays active");
        uncovered.wire.clock=300000;localTimer.run();
        check(uncovered.replies.size()==1&&uncovered.reads.state().active,"unrelated read cannot extend another Pending watchdog");
        for(PurpleRpcRequest request:uncovered.requests.values())request.cancel();uncovered.stop();
        Run synchronous=new Run();PurpleSyncReadScope scope=new PurpleSyncReadScope(synchronous.client,()->true);
        scope.requestHistory(0,100,page->{scope.cancel();check(page.error==null&&page.messages.isEmpty(),"synchronous completion may cancel scope");});
        check(!scope.managedReads().active,"synchronous token return does not leak ownership");
        final PurpleSyncReadScope[] delayedScope=new PurpleSyncReadScope[1];final int[] cancelled={0};
        PurpleSyncClient late=(PurpleSyncClient)Proxy.newProxyInstance(PurpleSyncReadLifetimeCases.class.getClassLoader(),new Class[]{PurpleSyncClient.class},(proxy,m,args)->{
            if(m.getName().equals("nowMillis"))return 0L;
            if(m.getName().equals("isActive"))return true;
            if(m.getName().equals("requestMessage")){delayedScope[0].cancel();return -99;}
            if(m.getName().equals("cancelRequest")){check((Integer)args[0]==-99,"late token identity preserved");cancelled[0]++;return null;}
            throw new AssertionError(m.getName());
        });
        delayedScope[0]=new PurpleSyncReadScope(late,()->true);delayedScope[0].requestMessage(7,ignored->{throw new AssertionError("cancelled read callback");});
        check(cancelled[0]==1&&!delayedScope[0].managedReads().active,"cancellation during dispatch cancels late token exactly once");
        System.out.println("READ_SCOPE_LIFETIME_CHECKS="+(checks-before));
    }
}
