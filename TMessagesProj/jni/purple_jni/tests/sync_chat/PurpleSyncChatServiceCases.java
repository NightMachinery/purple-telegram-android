package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatModelCases.cache;

final class PurpleSyncChatServiceCases {
    static final String AUTH=PurpleSyncChatCache.authority("11111111111111111111111111111111");
    static final class Memory implements PurpleSyncChatCache.IO {
        byte[] bytes;int writes,reads,removes;boolean refuse,throwRead;
        public byte[] read(){++reads;if(throwRead)throw new IllegalStateException("injected");return bytes!=null?bytes.clone():null;}
        public boolean write(byte[] expected,byte[] next){if(refuse||!Arrays.equals(bytes,expected))return false;bytes=next.clone();++writes;return true;}
        public boolean remove(byte[] expected){if(!Arrays.equals(expected,bytes))return false;bytes=null;++removes;return true;}
        void seed(PurpleSyncChatCache.Data data){check(PurpleSyncChatCache.save(this,new PurpleSyncChatCache.Loaded(true,null,null),ORIGIN,AUTH,data,()->true),"seed synthetic private cache");writes=0;reads=0;}
    }
    static final class Env implements PurpleSyncChatService.Env {
        boolean active=true,heldClient,heldWorker,heldInventory,throwWorker,throwBinding,throwClient;
        PurpleRpcRetry.Transient presentation;public void presentation(PurpleRpcRetry.Transient value){presentation=value;}
        long clock=1000,rpcClock;public long rpcNow(){return rpcClock!=0?rpcClock:clock;}final String key="synthetic-service:"+UUID.randomUUID();
        final Memory file=new Memory();final PurpleSyncChatModelCases.World world=new PurpleSyncChatModelCases.World();
        final Map<Runnable,Long> timers=new LinkedHashMap<>();final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        PurpleSyncChatService.Binding binding=new PurpleSyncChatService.Binding(true,false,SELF);
        PurpleSyncChatClient injectedClient;java.util.function.Supplier<PurpleSyncChatClient> injectedFactory;
        Consumer<RecordPresence> inventoryDone;
        Runnable pending;int clients,cancelled,inventories;String authority=AUTH;
        public PurpleSyncChatOrigin origin(){return ORIGIN;}public String authority(){return authority;}public String operationKey(){return key;}public boolean current(){return active;}boolean setupHeld;public boolean setupPostingHeld(){return setupHeld;}public long now(){return clock;}
        public void ui(Runnable task){task.run();}public void worker(Runnable task){if(throwWorker)throw new IllegalStateException("injected");if(heldWorker)tasks.add(task);else task.run();}
        public void delay(Runnable task,long millis){timers.put(task,clock+millis);}public void cancelDelay(Runnable task){timers.remove(task);}public PurpleSyncChatCache.IO cache(){return file;}
        public PurpleSyncChatService.Binding binding(){if(throwBinding)throw new IllegalStateException("injected");return binding;}
        public PurpleSyncChatClient client(){if(throwClient)throw new IllegalStateException("injected");++clients;if(injectedFactory!=null)return injectedFactory.get();if(injectedClient!=null)return injectedClient;return new PurpleSyncChatClient(){
            boolean closed;
            public PurpleSyncChatOrigin origin(){return ORIGIN;}public boolean current(){return active&&!closed;}public void dispatch(Runnable task){task.run();}
            void reply(PurpleSyncChatDiscovery.Command command,Consumer<PurpleSyncChatDiscovery.Reply> done){Runnable work=()->{if(current())done.accept(world.reply(command));};if(heldClient)pending=work;else work.run();}
            public void search(PurpleSyncChatRequests.Search search,PurpleSyncChatRequests.Cursor cursor,Peer target,Consumer<PurpleSyncChatRequests.Page> done){reply(new PurpleSyncChatDiscovery.Command(ORIGIN,1,1,PurpleSyncChatDiscovery.Step.Search,search,cursor,target,null),reply->done.accept(reply.success?reply.page:null));}
            public void info(Peer peer,Consumer<Info> done){reply(new PurpleSyncChatDiscovery.Command(ORIGIN,1,1,PurpleSyncChatDiscovery.Step.PeerInfo,null,null,peer,null),reply->done.accept(reply.info));}
            public void exact(Cache location,Consumer<Exact> done){reply(new PurpleSyncChatDiscovery.Command(ORIGIN,1,1,PurpleSyncChatDiscovery.Step.ExactMark,null,null,location.chat,location),reply->done.accept(reply.exact));}
            public void hint(Consumer<SettingsHint> done){done.accept(world.hint);}public List<Peer> fallback(){return Collections.emptyList();}public void cancel(){closed=true;++cancelled;}
        };}
        public void inventory(Peer peer,BooleanSupplier current,Consumer<RecordPresence> done){++inventories;if(heldInventory){inventoryDone=done;return;}if(current.getAsBoolean())done.accept(world.records.getOrDefault(peer,RecordPresence.Unknown));}public void cancelInventory(){Consumer<RecordPresence> done=inventoryDone;inventoryDone=null;if(done!=null)done.accept(RecordPresence.Unknown);}
        void through(long next){clock=next;for(Map.Entry<Runnable,Long> timer:new ArrayList<>(timers.entrySet()))if(timer.getValue()<=clock&&timers.remove(timer.getKey())!=null)timer.getKey().run();}
    }
    static void cacheCases(File root)throws Exception {
        Memory io=new Memory();PurpleSyncChatCache.Data original=cache(SELF,7,50);io.seed(original);
        PurpleSyncChatCache.Loaded loaded=PurpleSyncChatCache.load(io,ORIGIN,AUTH);check(loaded.ok&&loaded.data.location.equals(original.location),"cache account/login roundtrip");
        PurpleSyncChatOrigin nextLogin=new PurpleSyncChatOrigin(0,0,11,"new-ram-login");check(PurpleSyncChatCache.load(io,nextLogin,AUTH).data!=null,"RAM UUID does not invalidate persisted authority");
        PurpleSyncChatCache.Loaded old=PurpleSyncChatCache.load(io,ORIGIN,PurpleSyncChatCache.authority("22222222222222222222222222222222"));check(old.ok&&old.data==null&&old.bytes!=null,"different persistent login is cache miss with retained CAS bytes");
        check(!AUTH.equals("11111111111111111111111111111111")&&PurpleSyncChatCache.authority("invalid")==null,"purpose domain authority admission");
        check(!PurpleSyncChatCache.load(io,new PurpleSyncChatOrigin(0,1,11,"other-backend"),AUTH).ok,"backend cache mismatch");check(!PurpleSyncChatCache.load(io,new PurpleSyncChatOrigin(0,0,22,"other-user"),AUTH).ok,"account cache mismatch");
        byte[] before=io.bytes.clone();io.bytes=bytes("{}");check(!PurpleSyncChatCache.save(io,loaded,ORIGIN,AUTH,original,()->true)&&Arrays.equals(io.bytes,bytes("{}")),"cache stale CAS cannot replace concurrent writer");io.bytes=before;
        check(!PurpleSyncChatCache.save(io,loaded,ORIGIN,AUTH,original,()->false),"cache stale origin refuses write");
        for(String field:new String[]{"version","markId","newReaders","chat","markDate"}){JSONObject bad=new JSONObject(new String(before,StandardCharsets.UTF_8));bad.put(field,field.equals("chat")?"user:11":field.equals("markDate")?"01":"wrong-type");io.bytes=bytes(bad.toString());check(!PurpleSyncChatCache.load(io,ORIGIN,AUTH).ok,"strict cache schema "+field);}
        JSONObject wrappedVersion=new JSONObject(new String(before,StandardCharsets.UTF_8));wrappedVersion.put("version",4294967297L);io.bytes=bytes(wrappedVersion.toString());check(!PurpleSyncChatCache.load(io,ORIGIN,AUTH).ok,"version cannot truncate to supported schema");
        io.bytes=new byte[]{(byte)0xff};check(!PurpleSyncChatCache.load(io,ORIGIN,AUTH).ok,"invalid UTF8 cache");io.bytes=new byte[65537];check(!PurpleSyncChatCache.load(io,ORIGIN,AUTH).ok,"cache size bound");
        PurpleSyncChatCache.Data pending=new PurpleSyncChatCache.Data(original.location,"original",original.readers,original.warned,json("future",true),false);io.bytes=null;io.seed(pending);check(PurpleSyncChatCache.load(io,ORIGIN,AUTH).data.pendingMove(),"unknown journal retained and held");
        PurpleSyncChatFacts.Input channel=input(CHANNEL);PurpleSyncChatClient.Info first=info(CHANNEL,channel);PurpleSyncChatCache.Data baseline=new PurpleSyncChatCache.Data(new Cache(CHANNEL,7,50),"old",PurpleSyncChatCache.ReadersSnapshot.from(first),Collections.singleton("config"),new JSONObject(),false);
        channel.groupFull.participants_count=2;PurpleSyncChatFacts.MappedPeer mapped=PurpleSyncChatFacts.peer(ORIGIN,channel);PurpleSyncChatClient.Info extra=new PurpleSyncChatClient.Info(ORIGIN,CHANNEL,mapped,PurpleSyncChatClient.Error.None,"new",new HashSet<>(Arrays.asList(11L,22L)),true);
        PurpleSyncChatCache.Data changed=baseline.next(baseline.location,extra);check(changed.newReaders&&changed.readers==baseline.readers,"count increase preserves warning baseline");
        PurpleSyncChatClient.Info swapped=new PurpleSyncChatClient.Info(ORIGIN,CHANNEL,mapped,PurpleSyncChatClient.Error.None,"new",new HashSet<>(Arrays.asList(11L,33L)),true);
        PurpleSyncChatCache.Data second=new PurpleSyncChatCache.Data(baseline.location,"",PurpleSyncChatCache.ReadersSnapshot.from(extra),baseline.warned,new JSONObject(),false);
        check(second.next(second.location,swapped).newReaders,"same count member replacement holds");check(changed.next(changed.location,first).newReaders,"reader-change hold survives restart and reversion");
        org.telegram.messenger.ApplicationLoader.files=root;File purple=new File(root,"purple");check(purple.mkdir(),"private files fixture");File file=new File(purple,"sync-chat/0-11.json");PurpleSyncChatCache.IO real=PurpleSyncChatCache.file(file);
        check(PurpleSyncChatCache.save(real,new PurpleSyncChatCache.Loaded(true,null,null),ORIGIN,AUTH,original,()->true),"real account cache atomic create");loaded=PurpleSyncChatCache.load(real,ORIGIN,AUTH);check(loaded.ok&&loaded.data!=null,"real file cache read");
        Set<java.nio.file.attribute.PosixFilePermission> mode=Files.getPosixFilePermissions(file.toPath());check(mode.equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),"cache file private mode");
        Files.write(file.toPath(),bytes("changed"));check(!real.write(loaded.bytes,before)&&!real.remove(loaded.bytes),"real file CAS and remove keep changed bytes");
        File link=new File(purple,"cache-link");Files.createSymbolicLink(link.toPath(),file.getParentFile().toPath());check(!PurpleSyncChatCache.load(PurpleSyncChatCache.file(new File(link,"0-11.json")),ORIGIN,AUTH).ok,"cache symlink parent refused");
    }
    static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    static void serviceCases(){
        Env env=new Env();env.file.seed(cache(SELF,7,50));env.world.add(mark(SELF,7,50));PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> delivered=new ArrayList<>();
        service.startSession();check(service.status().phase==PurpleSyncChatDiscovery.Phase.Use&&!PurpleSyncOperations.busy(env.key),"startup resolves under owned lock then releases");int clients=env.clients;
        service.startSession();check(env.clients==clients,"StartSession idempotent");service.ensureResolved(false,"config",null,delivered::add);check(env.clients==clients&&delivered.size()==1,"EnsureResolved15min cached gate");
        service.checkBeforePost("config",null,delivered::add);check(env.clients==clients+1&&env.world.exacts>=2,"CheckBeforePost always fresh");
        service.ensureResolved(false,"library",null,delivered::add);check(env.clients==clients+2,"per-stream EnsureResolved gate");env.clock+=15*60*1000L;service.ensureResolved(false,"library",null,delivered::add);check(env.clients==clients+3,"EnsureResolved refresh after15min");service.close(false);
        env=new Env();env.file.seed(cache(SELF,7,50));env.world.add(mark(SELF,7,50));service=new PurpleSyncChatService(env);service.ensureResolved(false,"config",null,ignored->{});clients=env.clients;
        env.clock+=10*60*1000L;service.ensureResolved(false,"config",null,ignored->{});check(env.clients==clients,"cached Ensure does not extend refresh epoch");env.clock+=10*60*1000L;service.ensureResolved(false,"config",null,ignored->{});check(env.clients==clients+1,"repeated Ensure still refreshes from last actual resolution");service.close(false);
        env=new Env();env.file.seed(cache(SELF,7,50));env.world.add(mark(SELF,7,50));service=new PurpleSyncChatService(env);service.ensureResolved(false,"config",null,ignored->{});clients=env.clients;
        env.binding=new PurpleSyncChatService.Binding(true,true,CHANNEL);delivered.clear();service.ensureResolved(false,"config",null,delivered::add);check(env.clients==clients+1&&delivered.get(0).postingHeld&&delivered.get(0).reason.equals("BoundChatMismatch"),"cached15min gate revalidates fresh state binding before self-only I/O");service.close(false);
        env=new Env();env.file.seed(cache(SELF,7,50));env.world.add(mark(SELF,7,50));service=new PurpleSyncChatService(env);service.ensureResolved(false,"config",null,ignored->{});clients=env.clients;
        PurpleSyncChatCache.Loaded externallyLoaded=PurpleSyncChatCache.load(env.file,ORIGIN,AUTH);check(PurpleSyncChatCache.save(env.file,externallyLoaded,ORIGIN,AUTH,cache(CHANNEL,8,60),()->true),"external synthetic cache update");delivered.clear();service.ensureResolved(false,"config",null,delivered::add);check(env.clients==clients+1&&delivered.get(0).postingHeld,"cached15min gate revalidates fresh cache bytes");service.close(false);
        for(boolean borrowed:new boolean[]{false,true}){
            env=new Env();env.file.seed(cache(SELF,7,50));env.world.add(mark(SELF,7,50));service=new PurpleSyncChatService(env);
            service.ensureResolved(true,"config",null,ignored->{});long searches=env.world.searches(PurpleSyncChatRequests.Search.Global);
            env.heldWorker=true;delivered.clear();Object owner=new Object();List<PurpleSyncAutoCore.Outcome> post=new ArrayList<>(),retire=new ArrayList<>();
            PurpleSyncChatService.Borrowed loan=null;
            PurpleSyncOperations.Capability ownerToken=null;if(borrowed){ownerToken=PurpleSyncOperations.acquire(env.key,owner,()->true);check(ownerToken!=null,"watchdog borrower owns account");loan=new PurpleSyncChatService.Borrowed(env.key,owner,()->true);}
            service.checkBeforePost("config",loan,result->{delivered.add(result);PurpleSyncChat.Status value=new PurpleSyncChat.Status(result);post.add(value.selfPostable()?PurpleSyncAutoCore.Outcome.Sent:PurpleSyncAutoCore.Outcome.OutcomeUnknown);retire.add(value.selfPostable()?PurpleSyncAutoCore.Outcome.Deleted:PurpleSyncAutoCore.Outcome.DeletionRefused);});
            check(PurpleSyncOperations.owned(env.key,borrowed?owner:service),"service retains original capability during local open");
            Runnable late=env.tasks.remove();env.through(121000);
            check(delivered.isEmpty()&&post.isEmpty()&&retire.isEmpty()&&service.status().phase==PurpleSyncChatDiscovery.Phase.Searching&&service.status().reason.equals("TelegramWait")&&service.status().retryAt==126000,"local watchdog presents wait without final post/retirement outcome");
            check(env.presentation!=null&&env.presentation.retryAt==126000,"local-open wait presentation independent of completion");
            List<PurpleSyncChatDiscovery.Status> queued=new ArrayList<>();service.checkBeforePost("config",loan,queued::add);check(queued.isEmpty(),"new borrowed/before-post gate queues during local retry");
            late.run();check(delivered.isEmpty(),"late timed-out local worker fenced");
            env.heldWorker=false;env.through(126000);check(delivered.size()==1&&queued.size()==1&&new PurpleSyncChat.Status(delivered.get(0)).selfPostable(),"original and queued before-post completions receive eventual validated result");
            check(post.equals(Collections.singletonList(PurpleSyncAutoCore.Outcome.Sent))&&retire.equals(Collections.singletonList(PurpleSyncAutoCore.Outcome.Deleted)),"transient local open never becomes OutcomeUnknown or DeletionRefused");
            check(env.world.searches(PurpleSyncChatRequests.Search.Global)>searches,"watchdog restart renews full discovery rather than cached receipt");
            check(borrowed?PurpleSyncOperations.owned(env.key,owner):!PurpleSyncOperations.busy(env.key),"watchdog retains borrowed owner and releases only its own lease");
            service.close(false);if(borrowed)PurpleSyncOperations.end(env.key,owner,ownerToken);
        }

        env=new Env();env.heldInventory=true;service=new PurpleSyncChatService(env);delivered.clear();service.ensureResolved(true,"config",null,delivered::add);
        check(env.inventoryDone!=null,"uncovered inventory continuation held after RPC");env.through(121000);
        check(delivered.isEmpty()&&service.status().phase==PurpleSyncChatDiscovery.Phase.Searching,"watchdog fences synchronous cancellation reply before restarting");
        env.heldInventory=false;env.through(126000);check(delivered.size()==1&&delivered.get(0).phase==PurpleSyncChatDiscovery.Phase.Unset,"original caller resumes after reentrant inventory cancellation");service.close(false);
        for(String failure:new String[]{"worker","binding","client","cache"}){
            env=new Env();env.throwWorker=failure.equals("worker");env.throwBinding=failure.equals("binding");env.throwClient=failure.equals("client");env.file.throwRead=failure.equals("cache");service=new PurpleSyncChatService(env);delivered.clear();service.ensureResolved(true,"config",null,delivered::add);check(delivered.size()==1&&delivered.get(0).postingHeld&&!PurpleSyncOperations.busy(env.key),"setup exception cleanup "+failure);service.close(false);
        }
        env=new Env();env.file.seed(cache(SELF,7,50));env.world.add(mark(SELF,7,50));env.file.refuse=true;service=new PurpleSyncChatService(env);delivered.clear();service.checkBeforePost("config",null,delivered::add);check(delivered.size()==1&&delivered.get(0).reason.equals("CacheWriteUnavailable")&&!PurpleSyncOperations.busy(env.key),"cache write failure holds and releases");service.close(false);
        env=new Env();env.file.seed(cache(SELF,7,50));env.heldWorker=true;service=new PurpleSyncChatService(env);delivered.clear();service.ensureResolved(true,"config",null,delivered::add);env.active=false;env.tasks.remove().run();check(!PurpleSyncOperations.busy(env.key)&&env.file.writes==0&&delivered.isEmpty(),"login lost before deferred cache read cannot write or deliver stale completion");service.close(false);
        env=new Env();Object owner=new Object();PurpleSyncOperations.Capability ownerToken=PurpleSyncOperations.acquire(env.key,owner,()->true);check(ownerToken!=null,"external runner lock admitted");service=new PurpleSyncChatService(env);service.ensureResolved(true,"config",null,ignored->{});check(env.clients==0,"own discovery waits for runner lock");
        PurpleSyncChatService.Borrowed borrowed=new PurpleSyncChatService.Borrowed(env.key,owner,()->true);delivered.clear();service.ensureResolved(true,"config",borrowed,delivered::add);check(delivered.size()==1&&PurpleSyncOperations.owned(env.key,owner),"borrowed request bypasses queued own start without releasing caller lock");service.close(false);PurpleSyncOperations.end(env.key,owner,ownerToken);check(!PurpleSyncOperations.busy(env.key),"external caller retains release responsibility");
        env=new Env();owner=new Object();ownerToken=PurpleSyncOperations.acquire(env.key,owner,()->true);env.heldClient=true;service=new PurpleSyncChatService(env);delivered.clear();service.ensureResolved(true,"config",new PurpleSyncChatService.Borrowed(env.key,owner,()->true),delivered::add);service.cancelBorrowed(owner);env.pending.run();check(delivered.isEmpty()&&PurpleSyncOperations.owned(env.key,owner),"borrowed cancellation drops callback without releasing caller");service.close(false);PurpleSyncOperations.end(env.key,owner,ownerToken);
        env=new Env();service=new PurpleSyncChatService(env);delivered.clear();service.ensureResolved(true,"config",new PurpleSyncChatService.Borrowed(env.key,new Object(),()->true),delivered::add);check(delivered.size()==1&&delivered.get(0).reason.equals("OperationChanged")&&env.clients==0,"borrowed owner must really own same account lock");service.close(false);
        env=new Env();env.file.seed(cache(SELF,7,50));service=new PurpleSyncChatService(env);delivered.clear();service.ensureResolved(true,"config",null,delivered::add);check(service.status().phase==PurpleSyncChatDiscovery.Phase.Searching,"service Searching initial");
        for(long delay:new long[]{30000,120000,600000}){check(env.timers.size()==1&&env.timers.values().iterator().next()==env.clock+delay,"service retry scheduled from core "+delay);env.through(env.clock+delay);check(!PurpleSyncOperations.busy(env.key),"retry resolves inside fresh lock and releases");}check(service.status().phase==PurpleSyncChatDiscovery.Phase.Lost&&env.timers.isEmpty(),"service retries stop Lost");service.close(false);
        env=new Env();env.file.seed(cache(SELF,7,50));env.world.add(mark(SELF,7,50));service=new PurpleSyncChatService(env);final PurpleSyncChatService closing=service;service.ensureResolved(true,"config",null,status->closing.close(false));check(!PurpleSyncOperations.busy(env.key)&&env.timers.isEmpty(),"completion may synchronously destroy service");
        env=new Env();env.file.seed(cache(SELF,7,50));env.world.add(mark(SELF,7,50));service=new PurpleSyncChatService(env);final Env logoutEnv=env;final Object waiter=new Object();PurpleSyncOperations.whenIdle(env.key,waiter,()->{});
        env.heldClient=true;delivered.clear();service.ensureResolved(true,"config",null,delivered::add);PurpleSyncOperations.whenIdle(env.key,waiter,()->{logoutEnv.active=false;});env.heldClient=false;env.pending.run();check(delivered.isEmpty(),"logout during lock-release notification suppresses completion");service.close(false);
        env=new Env();env.file.seed(cache(SELF,7,50));env.authority=PurpleSyncChatCache.authority("22222222222222222222222222222222");service=new PurpleSyncChatService(env);service.close(true);check(env.file.removes==0&&env.file.bytes!=null,"old authority logout cannot delete newer login cache");
        env=new Env();env.file.seed(cache(SELF,7,50));service=new PurpleSyncChatService(env);service.close(true);check(env.file.removes==1&&env.file.bytes==null,"matching authority logout removes own cache");
    }
    static void carryQuiescence(){
        Env env=new Env();env.heldWorker=true;PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> active=new ArrayList<>(),queued=new ArrayList<>();
        service.ensureResolved(true,"config",null,active::add);service.ensureResolved(true,"library",null,queued::add);
        PurpleSyncOperations.CarryLease lease=PurpleSyncOperations.carry(env.key,new Object());check(lease!=null,"carry reserves account before discovery cancellation");lease.quiesce();
        check(lease.quiescent()&&!PurpleSyncOperations.busy(env.key),"service releases discovery capability before carry Begin");
        check(active.size()==1&&queued.size()==1&&queued.get(0).reason.equals("CarryPending"),"active and queued requests receive held outcome");
        while(!env.tasks.isEmpty())env.tasks.removeFirst().run();check(env.clients==0&&env.file.writes==0,"late pre-quiescence worker cannot create client or cache");
        lease.close();service.close(false);
        Env reentrant=new Env();reentrant.heldWorker=true;PurpleSyncChatService next=new PurpleSyncChatService(reentrant);int[] delivered={0};
        next.ensureResolved(true,"config",null,ignored->{++delivered[0];next.ensureResolved(true,"config",null,value->{});});
        next.ensureResolved(true,"library",null,ignored->{throw new IllegalStateException("queued observer");});
        PurpleSyncOperations.CarryLease held=PurpleSyncOperations.carry(reentrant.key,new Object());held.quiesce();
        check(delivered[0]==1&&held.quiescent(),"quiescence drains captured queue despite reentry and throwing observer");held.close();next.close(false);
    }
    static void run(File root)throws Exception{cacheCases(root);serviceCases();carryQuiescence();}
}
