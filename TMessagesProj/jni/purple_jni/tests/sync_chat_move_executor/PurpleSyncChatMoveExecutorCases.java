package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.json.*;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

public final class PurpleSyncChatMoveExecutorCases {
    static int checks,sequence;
    static File parent;
    static void must(boolean value,String reason){++checks;if(!value)throw new AssertionError(reason);}
    static final class CloudClient implements PurpleSyncClient {
        final PurpleSyncChatIO route;final List<TLRPC.Message> records;final Map<Integer,byte[]> bodies;
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();final File directory;
        CloudClient(Peer chat,List<TLRPC.Message> records,Map<Integer,byte[]> bodies)throws Exception {
            route=route(chat);this.records=new ArrayList<>(records);this.records.sort((a,b)->Integer.compare(b.id,a.id));this.bodies=bodies;directory=new File(root,"cloud-read-"+(++sequence));PurpleModernSyncCases.directory(directory);
        }
        public long userId(){return 777;}public Peer chat(){return route.chat;}public PurpleSyncChatOrigin origin(){return ORIGIN;}public boolean isActive(){return true;}
        public void runOnMain(Runnable r){queue.add(r);}public void runOnWorker(Runnable r){queue.add(r);}public void log(String s){}
        public int requestHistory(int offset,int limit,Reply<History> done){TLRPC.messages_Messages response=page(chat(),offset==0?records.toArray(new TLRPC.Message[0]):new TLRPC.Message[0]);response.count=records.size();done.onReply(route.history(response,null,PurpleSyncChatMoveIOCases::meta));return 1;}
        public int requestMessage(int id,Reply<Lookup> done){TLRPC.Message m=records.stream().filter(v->v.id==id).findFirst().orElse(null);done.onReply(m==null?Lookup.failed("Unknown"):route.lookup(page(chat(),m),null,id,PurpleSyncChatMoveIOCases::meta));return 2;}
        public void cancelRequest(int id){}public Object download(Message m,Reply<File> done){try{File f=new File(directory,"record-"+m.id);Files.write(f.toPath(),bodies.get(m.id));done.onReply(f);}catch(Exception e){throw new RuntimeException(e);}return null;}
        public void cancelDownload(Object token){}public void localCopies(Reply<List<LocalCopy>> done){done.onReply(Collections.emptyList());}
        public File filesDirectory(){return root;}public File cacheDirectory(){return root;}public long nowMillis(){return 1;}
        public void send(File f,String c,String m,Receipt r){throw new AssertionError("no live send");}
        PurpleSyncInventory inventory(){PurpleSyncTransport.CheckResult[] out={null};new PurpleSyncTelegramTransport(new PurpleSyncChatRecordClient(this,m->route.ownership((TLRPC.Message)m.remote))).check(null,r->out[0]=r);int cap=10000;while(!queue.isEmpty()){must(--cap>0,"bounded cloud scanner");queue.removeFirst().run();}must(out[0]!=null,"complete cloud callback");return out[0].inventory;}
    }
    static final class Frame implements PurpleSyncChatMove.Port {
        final PurpleSyncChatMoveStoreCases.Fixture files;
        final Peer target;
        final String mode;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        final List<PurpleSyncChatMove.Command> commands=new ArrayList<>();
        final List<PurpleSyncChatMove.Status> statuses=new ArrayList<>();
        final List<TLRPC.Message> oldRecords=new ArrayList<>(),newRecords=new ArrayList<>();
        final Map<Integer,byte[]> oldBodies=new HashMap<>(),newBodies=new HashMap<>();
        PurpleSyncChatMove move;
        PurpleSyncChatMoveStore opened;
        String authority=AUTH,access="Readable",stop="",stopCore="",dropKind="",faultAction="";
        boolean libraryPending,fakePlaylist,removeOld,duplicate,omitBase;
        String playlistStatus="Verified";
        DirectRead oldReadOverride;
        boolean foreground=true,live=true,ownWinner,dropEffect,stopAfterMark,badMark,badDestination,closeObserver,throwCancel,omitPendingInventory;
        int effects,markId=90,reads,quiesces,cancels;
        int fault;
        Consumer<PurpleSyncChatMove.Reply> deferred;
        PurpleSyncChatMove.Command deferredCommand;
        Frame(boolean own)throws Exception {this(own,false);}
        Frame(boolean own,boolean pending)throws Exception {this(own,pending,"Switch");}
        Frame(boolean own,boolean pending,String mode)throws Exception {
            this.mode=mode;
            JSONObject plan=copy(rows.get(own?28:43).getJSONObject("input"));target=mode.equals("RepairMark")?O:Peer.parse(plan.getString("target"),777);
            files=new PurpleSyncChatMoveStoreCases.Fixture(root,"executor-"+(++sequence),mode.equals("Fill")?null:unblob(pending?rows.get(120).getJSONObject("input").getString("state"):plan.getString("state")));
            if(pending)PurpleModernSyncCases.write(new File(files.root,"pending/config.json"),unblob(rows.get(121).getJSONObject("input").getJSONObject("event").getString("stagedRecord")));
            access=own?"Protected":"Readable";
            oldRecords.add(doc(O,2,1001,base));oldBodies.put(2,base);
            if(mode.equals("Follow")||mode.equals("Fill")){TLRPC.TL_message mark=doc(target,markId,2000,base);mark.media=null;mark.flags&=~(1<<9);mark.message=PurpleSyncChatSetupRequests.MARK;newRecords.add(mark);}
        }
        Frame(Frame original){mode=original.mode;files=original.files;target=original.target;access=original.access;oldRecords.addAll(original.oldRecords);newRecords.addAll(original.newRecords);oldBodies.putAll(original.oldBodies);newBodies.putAll(original.newBodies);ownWinner=original.ownWinner;}
        JSONObject begin()throws Exception{return copy(beginning).put("operation",String.format(Locale.ROOT,"%032x",sequence)).put("target",target.token()).put("mode",mode).put("removeOldMarkAuthorized",removeOld).put("preferenceToken",blob(files.binding.getBytes(java.nio.charset.StandardCharsets.UTF_8))).put("markText",PurpleSyncChatSetupRequests.MARK).put("loginAuthority",authority.substring(0,32));}
        public PurpleSyncChatOrigin origin(){return ORIGIN;}public String authority(){return authority;}
        public String operationKey(){return "executor:"+files.root.getName();}
        public boolean current(){return live;}public boolean foreground(){return foreground;}
        public void worker(Runnable task){tasks.addLast(task);}
        public void quiesce(Runnable done){++quiesces;tasks.addLast(done);}
        public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception {
            opened=new PurpleSyncChatMoveStore(files.root,files.owner(),0,"0-777",files.binding,authority,files.device,files.cache,current);
            if(interrupted)opened.openInterrupted();else opened.open();
            if(opened.journalBytes()==null&&!omitBase)PurpleSyncRetainedFiles.pin(files.root,base);
            return opened;
        }
        public void cancel(PurpleSyncChatMove.Command command){++cancels;if(throwCancel)throw new IllegalStateException("cancel throws");}
        public void status(PurpleSyncChatMove.Status status){
            statuses.add(status);
            if(opened!=null)opened.fault(status.action.equals(faultAction)?fault:0,(status.action.equals("CommitState")||status.action.equals("WritePendingState"))?new File(files.root,"state.json"):(status.action.equals("RetireOwnStage")||status.action.equals("RetirePendingStage"))?new File(files.root,"pending/config.json"):null);
            if(status.reason.equals("Running")&&(closeObserver||status.action.equals(stopCore)))move.close();
        }
        PurpleSyncChatMoveIO.Fresh fresh()throws Exception {
            PurpleSyncChatClient.Info source=info(O,0,access.equals("Protected")),dest=info(target,0,false);
            ResolveInput r=new ResolveInput(777);r.settingsBound=true;r.settingsChat=O;r.cache=new Cache(O,1,1001);r.cacheRead=DirectRead.Valid;r.cachedEligibility=source.eligibility();r.settingsHint=SettingsHint.Found;
            if(ownWinner)r.marks=Arrays.asList(new Mark(new Cache(target,markId,2000),MarkStatus.Supported,dest.eligibility(),RecordPresence.Present,true,true),new Mark(r.cache,MarkStatus.Supported,source.eligibility(),RecordPresence.Present,true,true));
            else r.marks=Collections.singletonList(new Mark(r.cache,MarkStatus.Supported,source.eligibility(),RecordPresence.Present,true,true));
            if(mode.equals("Follow")||mode.equals("Fill")){
                r.marks=Arrays.asList(new Mark(new Cache(target,markId,2000),MarkStatus.Supported,dest.eligibility(),newBodies.isEmpty()?RecordPresence.Empty:RecordPresence.Present,true,true),new Mark(new Cache(O,1,1001),MarkStatus.Supported,source.eligibility(),RecordPresence.Present,true,true));
                if(mode.equals("Fill")){r.cache=null;r.settingsBound=false;}
            }
            if(mode.equals("RepairMark")&&!ownWinner){r.cacheRead=DirectRead.MarkGone;r.marks=Collections.emptyList();}
            return new PurpleSyncChatMoveIO.Fresh(ORIGIN,authority,source,input(O),dest,input(target),r,true,oldReadOverride!=null?oldReadOverride:mode.equals("RepairMark")&&!ownWinner?DirectRead.MarkGone:access.equals("ChatGone")?DirectRead.ChatGone:DirectRead.Valid);
        }
        PurpleSyncInventory inventory(boolean source)throws Exception {
            ++reads;List<TLRPC.Message> records=source?oldRecords:newRecords;Map<Integer,byte[]> bodies=source?oldBodies:newBodies;
            List<TLRPC.Message> settings=new ArrayList<>();for(TLRPC.Message m:records)if(m.media!=null)settings.add(m);
            byte[] body=settings.isEmpty()?base:bodies.get(settings.get(0).id);
            if(!source&&badDestination)settings.clear();
            return new CloudClient(source?O:target,settings,bodies).inventory();
        }
        PurpleSyncChatMove.Reply reply()throws Exception {PurpleSyncChatMove.Reply r=new PurpleSyncChatMove.Reply();r.fresh=fresh();r.sourceAccess=access;r.libraryPending=libraryPending;if(fakePlaylist)r.playlist=obj("status",playlistStatus,"account","0-777","source",O.token(),"target",target.token(),"planStamp","fake","destinationStamp","fake");return r;}
        public void dispatch(PurpleSyncChatMove.Command command,Consumer<PurpleSyncChatMove.Reply> done){
            try {
                commands.add(command);
                if(command.action.equals(stop)||stopAfterMark&&ownWinner&&command.action.equals("Fresh")){deferred=done;deferredCommand=command;return;}
                PurpleSyncChatMove.Reply r=reply();
                switch(command.action){
                case "Fresh":break;
                case "Source":r.source=inventory(true);r.destination=inventory(false);for(int i=0;i<r.source.count();++i)r.sourceRecords.add(r.source.receipt(i));break;
                case "Pending":r.source=access.equals("ChatGone")||omitPendingInventory?null:inventory(true);r.sendIntent="NotIssued";break;
                case "Destination":r.destination=inventory(false);break;
                case "Cleanup":r.destination=inventory(false);break;
                case "Mark":
                    TLRPC.Message mark=newRecords.stream().filter(m->m.id==command.messageId).findFirst().orElse(null);
                    r.markRoute=route(target);r.response=page(target,badMark?new TLRPC.Message[0]:mark==null?new TLRPC.Message[0]:new TLRPC.Message[]{mark});break;
                case "Effect":
                    PurpleSyncChatMoveIO.Permit permit=command.permit;must(permit.current(),"effect has current sealed admission");++effects;
                    if(permit.kind.equals("mark")){
                        TLRPC.TL_message posted=doc(target,markId,2000,base);posted.media=null;posted.flags&=~(1<<9);posted.message=PurpleSyncChatSetupRequests.MARK;newRecords.add(posted);ownWinner=true;
                    }else{
                        boolean pending=permit.kind.equals("pending");int id=pending?22:55;
                        TLRPC.TL_message record=doc(pending?O:target,id,1500,permit.payload());(pending?oldRecords:newRecords).add(record);(pending?oldBodies:newBodies).put(id,permit.payload());
                    }
                    r.response=ack(permit,permit.kind.equals("mark")?markId:permit.kind.equals("pending")?22:55);
                    if(dropEffect||permit.kind.equals(dropKind)){deferred=done;deferredCommand=command;return;}break;
                default:throw new AssertionError(command.action);
                }
                done.accept(r);if(duplicate)done.accept(r);
            }catch(Exception e){throw new RuntimeException(e);}
        }
        void start()throws Exception{move=new PurpleSyncChatMove(this);move.start(begin(),info(target,0,false));drain();}
        void resume()throws Exception{move=new PurpleSyncChatMove(this);move.resume(info(target,0,false));drain();}
        void discard()throws Exception{move=new PurpleSyncChatMove(this);move.discardInterrupted(true);drain();}
        void drain(){int cap=10000;while(!tasks.isEmpty()){must(--cap>0,"executor queue is bounded");tasks.removeFirst().run();}}
        void close(){if(move!=null)move.close();drain();}
        boolean done(){return statuses.stream().anyMatch(s->s.terminal);}
        String reason(){return statuses.isEmpty()?"none":statuses.get(statuses.size()-1).reason;}
        JSONObject diskJournal()throws Exception{return json(Files.readAllBytes(new File(files.root,"carry.json").toPath())).getJSONObject("journal");}
    }
    static void modes()throws Exception {
        for(String mode:new String[]{"Follow","Fill","RepairMark"}){
            Frame f=new Frame(false,false,mode);f.start();must(f.done(),"core mode completes "+mode+" "+f.reason());
            must(f.effects==1,"existing mark modes do not post another mark; repair posts only mark "+mode);
            must(!f.commands.stream().anyMatch(c->c.permit!=null&&(mode.equals("RepairMark")?!c.permit.kind.equals("mark"):c.permit.kind.equals("mark"))),"mode-specific effect authority "+mode);
            if(mode.equals("Fill"))must(!new File(f.files.root,"state.json").exists(),"no-state Fill preserves state absence");
            if(mode.equals("RepairMark"))must(Arrays.equals(Files.readAllBytes(new File(f.files.root,"state.json").toPath()),PurpleSyncChatMoveStoreCases.initial),"RepairMark preserves exact state");f.close();
        }
        Frame missing=new Frame(false,false,"Fill");missing.access="ChatGone";missing.oldRecords.clear();missing.start();must(!missing.done()&&missing.effects==0,"unreadable Fill holds without empty recovery");missing.close();
    }
    static void playlistHolds()throws Exception {
        for(String status:new String[]{"Missing","Incomplete","Failed","Verified"}){
            Frame f=new Frame(false);f.libraryPending=true;f.playlistStatus=status;f.fakePlaylist=!status.equals("Missing");f.start();must(!f.done()&&f.effects==0,"library pending or unsealed "+status+" cannot dispatch settings");f.close();
        }
        for(boolean target:new boolean[]{false,true}){
            Frame f=new Frame(false);byte[] body=unblob(rows.get(962).getJSONObject("input").getJSONObject("oldInventory").getJSONArray("messages").getJSONObject(1).getString("bytes"));
            TLRPC.TL_message playlist=doc(target?f.target:O,3,1200,body);playlist.media.document.attributes.get(0).file_name=PurpleSyncPost.PLAYLISTS_RECORD_FILE_NAME;
            (target?f.newRecords:f.oldRecords).add(playlist);(target?f.newBodies:f.oldBodies).put(3,body);f.fakePlaylist=true;f.start();
            must(!f.done()&&f.effects==0&&f.reason().equals("PlaylistRequired"),"observed "+(target?"N":"O")+" playlist holds before settings delivery "+f.reason());f.close();
        }
    }
    static void happy()throws Exception {
        for(boolean own:new boolean[]{false,true}){
            Frame f=new Frame(own);f.start();must(f.done(),"core-driven happy move "+own+" "+f.reason());
            must(f.diskJournal().getBoolean("state_committed")&&f.diskJournal().getBoolean("cache_completed"),"state/cache durable before terminal");
            must(f.effects==2,"one record and one mark effect");must(!PurpleSyncOperations.carryHeld(f.operationKey()),"terminal releases RAM carry lease");
            must(PurpleSyncChatMoveStore.completed(f.files.root,null),"terminal evidence admits ordinary startup");f.close();
        }
    }
    static void observerAndForeground()throws Exception {
        Frame observer=new Frame(false);observer.closeObserver=true;observer.start();must(observer.effects==0,"observer close before first action prevents writes");
        must(!PurpleSyncOperations.carryHeld(observer.operationKey()),"observer close releases exact lease");
        Frame f=new Frame(false);f.stop="Effect";f.start();must(f.deferredCommand!=null,"effect callback held");
        f.foreground=false;must(!f.deferredCommand.permit.current(),"foreground departure revokes effect admission");f.close();
        must(f.effects==0&&!PurpleSyncOperations.carryHeld(f.operationKey()),"foreground cancellation drains without effects");
    }
    static void effectCrash()throws Exception {
        Frame f=new Frame(false);f.dropEffect=true;f.start();must(f.effects==1&&f.deferredCommand!=null,"record effect happened before acknowledgement");f.close();
        Frame restart=new Frame(f);restart.resume();must(restart.done(),"unknown send reconciles real cloud after reopen "+restart.reason());must(restart.effects==1,"no duplicate record, only mark remains");restart.close();
    }
    static void ownMarkWinnerRestart()throws Exception {
        Frame f=new Frame(false);f.stopAfterMark=true;f.start();must(f.ownWinner&&f.deferred!=null,"matched mark acknowledgement saved before restart");f.close();
        Frame restart=new Frame(f);restart.resume();must(restart.done(),"own acknowledged winner does not trap startup "+restart.reason());must(restart.effects==0,"own winner recovery is read/local completion only");restart.close();
    }
    static void failedReads()throws Exception {
        Frame f=new Frame(false);f.badMark=true;f.start();must(!f.done()&&f.reason().equals("UnknownMark"),"bad mark readback holds exact request "+f.reason());f.close();
        Frame bad=new Frame(false);bad.badDestination=true;bad.start();must(!bad.done(),"complete N absence cannot commit missing record");must(!bad.diskJournal().getBoolean("state_committed"),"bad N never commits state");bad.close();
    }
    static void quiescence()throws Exception {
        Frame f=new Frame(false);Object writer=new Object();int[] retired={0};PurpleSyncOperations.Capability[] cap={null};
        cap[0]=PurpleSyncOperations.acquire(f.operationKey(),writer,()->true,()->{++retired[0];PurpleSyncOperations.end(f.operationKey(),writer,cap[0]);});
        f.stop="Fresh";f.start();must(retired[0]==1&&f.quiesces==1,"existing account publisher quiesced before Begin");
        must(PurpleSyncOperations.acquire(f.operationKey(),new Object(),()->true)==null,"carry reservation refuses queued ordinary publisher");f.close();
        PurpleSyncOperations.Capability next=PurpleSyncOperations.acquire(f.operationKey(),writer,()->true);must(next!=null,"close releases carry reservation");PurpleSyncOperations.end(f.operationKey(),writer,next);
    }
    static void pendingCases()throws Exception {
        Frame readable=new Frame(false,true);readable.start();must(readable.done(),"readable pending O finishes through immutable send/readback "+readable.reason());
        must(readable.commands.stream().anyMatch(c->c.permit!=null&&c.permit.kind.equals("pending")),"pending flow used separate frozen O permit");readable.close();
        Frame protectedPending=new Frame(true,true);protectedPending.stop="Source";protectedPending.start();
        must(protectedPending.effects==0,"protected pending never posts into O");
        must(!protectedPending.diskJournal().getString("deferred_record").isEmpty(),"complete protected O absence retains exact deferred bytes");protectedPending.close();
        Frame issued=new Frame(true,true);issued.omitPendingInventory=true;issued.start();
        must(issued.reason().equals("IncompleteInventory")&&issued.diskJournal().getInt("phase")==0,"NotIssued cannot weaken imported uncertain pending without O read");issued.close();
        Frame restart=new Frame(issued);restart.omitPendingInventory=true;restart.resume();
        must(restart.reason().equals("IncompleteInventory")&&restart.diskJournal().getInt("phase")==0,"issued floor survives restart and contradictory NotIssued");restart.close();
        Frame gone=new Frame(true,true);gone.access="ChatGone";gone.oldRecords.clear();gone.oldBodies.clear();gone.start();
        must(gone.done(),"affirmative pending ChatGone retains local stage and completes own-base move "+gone.reason());gone.close();
    }
    static void deferredDiscardCases()throws Exception {
        for(String action:new String[]{"CommitState","RetireOwnStage"}) {
            Frame f=new Frame(true,true);f.faultAction=action;f.fault=5;f.start();
            must(!f.done()&&f.reason().equals("DurabilityFailed"),"failure at committed deferred-stage window "+action+" "+f.reason());
            JSONObject journal=f.diskJournal();must(!journal.getString("target_pending_record").isEmpty(),"exact target pending bytes retained before state write");
            f.close();Frame next=new Frame(f);next.authority="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";next.discard();
            must(next.done(),"new-login Discard recovers deferred stage "+action+" "+next.reason());
            byte[] stage=Files.readAllBytes(new File(next.files.root,"pending/config.json").toPath());
            must(Arrays.equals(stage,bytes(journal.getString("target_pending_record"))),"Discard materializes exact already-reserved target bytes");
            must(next.effects==0&&next.diskJournal().getInt("phase")==17,"Discard is terminal abandonment with unresolved identities retained");next.close();
        }
    }
    static void cleanupGone()throws Exception {
        Frame f=new Frame(false);f.removeOld=true;f.stop="Cleanup";f.start();must(f.diskJournal().getBoolean("state_committed"),"cleanup follows committed N");f.close();
        Frame gone=new Frame(f);gone.access="ChatGone";gone.resume();must(gone.done()&&gone.diskJournal().getBoolean("cleanup_chat_gone"),"scoped definite R3 ChatGone reaches core cleanup transition");gone.close();
    }
    static void actionRestarts()throws Exception {
        for(String action:new String[]{"ResolveAgain","Plan","StageOwn","Deliver","VerifyDestination","PostMark","ReadMark","PrepareCache","CommitState","RetireOwnStage","CompleteCache","Cleanup"}) {
            Frame f=new Frame(true);f.stopCore=action;f.start();must(!f.done(),"restart boundary reached "+action+" "+f.reason());f.close();
            Frame next=new Frame(f);next.resume();must(next.done(),"real-file reopen completes action "+action+" "+next.reason());next.close();
        }
    }
    static void durabilityRestarts()throws Exception {
        for(String action:new String[]{"StageOwn","Deliver","PostMark","PrepareCache","CommitState","RetireOwnStage","CompleteCache"})for(int point:new int[]{1,5}) {
            Frame f=new Frame(true);f.faultAction=action;f.fault=point;f.start();
            if(action.equals("RetireOwnStage")&&point==1){must(f.done(),"remove has no write-start fault");f.close();continue;}
            must(!f.done()&&f.reason().equals("DurabilityFailed"),"real durability refusal "+action+"/"+point+" "+f.reason());f.close();
            Frame next=new Frame(f);next.resume();must(next.done(),"truthful reopened barriers recover "+action+"/"+point+" "+next.reason());next.close();
        }
    }
    static void lifecycleCallbacks()throws Exception {
        for(String action:new String[]{"Fresh","Source","Pending","Effect","Destination","Mark","Cleanup"}){
            Frame f=new Frame(false,action.equals("Pending"));f.stop=action;f.start();must(f.deferred!=null,"callback boundary reached "+action);int effects=f.effects;
            Consumer<PurpleSyncChatMove.Reply> old=f.deferred;f.close();old.accept(f.reply());old.accept(f.reply());f.drain();
            must(f.effects==effects&&!PurpleSyncOperations.carryHeld(f.operationKey()),"late duplicate callback cannot revive closed owner "+action);
        }
        Frame duplicate=new Frame(false);duplicate.duplicate=true;duplicate.start();must(duplicate.done()&&duplicate.effects==2,"duplicate replies cannot issue twice or clear a new command");duplicate.close();
        Frame thrown=new Frame(false);thrown.stop="Effect";thrown.throwCancel=true;thrown.start();
        try{thrown.close();throw new AssertionError("throwing cancellation control");}catch(IllegalStateException expected){must(expected.getMessage().equals("cancel throws"),"expected cleanup exception");}
        must(!PurpleSyncOperations.carryHeld(thrown.operationKey()),"throwing cancellation releases carry lease and file lock");
        Object writer=new Object();PurpleSyncOperations.Capability cap=PurpleSyncOperations.acquire(thrown.operationKey(),writer,()->true);must(cap!=null,"fresh operation admitted after throwing cleanup");PurpleSyncOperations.end(thrown.operationKey(),writer,cap);
    }
    static void cleanupControls()throws Exception {
        for(String access:new String[]{"Unknown","Protected","Readable","ChatGone"}){
            Frame f=new Frame(false);f.removeOld=true;f.stop="Cleanup";f.start();f.close();
            Frame next=new Frame(f);next.access=access;next.oldReadOverride=access.equals("Readable")?DirectRead.MarkGone:access.equals("ChatGone")?DirectRead.Valid:null;next.resume();
            must(!next.done()&&!next.diskJournal().optBoolean("cleanup_chat_gone"),"uncertain/protected/mark-only or contradictory cleanup is held "+access);next.close();
        }
    }
    static void lockOrder()throws Exception {
        final Object outside=new Object(),owner=new Object();String key="carry-lock-order";java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),go=new java.util.concurrent.CountDownLatch(1);
        PurpleSyncOperations.Capability[] cap={null};cap[0]=PurpleSyncOperations.acquire(key,owner,()->true,()->{entered.countDown();try{go.await();}catch(InterruptedException e){throw new AssertionError(e);}synchronized(outside){PurpleSyncOperations.end(key,owner,cap[0]);}});
        PurpleSyncOperations.CarryLease held=PurpleSyncOperations.carry(key,new Object());
        Thread a=new Thread(held::quiesce,"carry-quiesce");Thread b=new Thread(()->{try{entered.await();synchronized(outside){go.countDown();PurpleSyncOperations.carryHeld(key);}}catch(InterruptedException e){throw new AssertionError(e);}},"carry-monitor-order");
        a.setDaemon(true);b.setDaemon(true);a.start();b.start();a.join(3000);b.join(3000);
        must(!a.isAlive()&&!b.isAlive()&&java.lang.management.ManagementFactory.getThreadMXBean().findDeadlockedThreads()==null,"quiescence callbacks never execute under global operation monitor");must(held.quiescent(),"publisher drained outside monitor");held.close();
        String reentrant="carry-reentrant-idle";PurpleSyncOperations.CarryLease lease=PurpleSyncOperations.carry(reentrant,new Object());PurpleSyncOperations.Capability[] replacement={null};Object w=new Object();
        PurpleSyncOperations.whenIdle(reentrant,w,()->replacement[0]=PurpleSyncOperations.acquire(reentrant,w,()->true));lease.close();must(replacement[0]!=null,"idle callback reenters only after carry released");PurpleSyncOperations.end(reentrant,w,replacement[0]);
    }
    static void terminalAdmission()throws Exception {
        Frame f=new Frame(true);f.start();must(f.done(),"terminal prerequisite");
        org.telegram.messenger.UserConfig.getInstance(0).user=777;
        org.telegram.messenger.MessagesController.getMainSettings(0).edit().putString("purple_sync_binding_777",f.files.binding).commit();
        try(PurpleAccountSyncStore ordinary=new PurpleAccountSyncStore(f.files.root,PurpleAccountSyncStore.NATIVE,f.target)){
            must(ordinary.open(true,0,f.files.device).status==PurpleAccountSyncStore.Status.Ready,"actual ordinary N startup after terminal carry");
        }
        JSONObject state=json(Files.readAllBytes(new File(f.files.root,"state.json").toPath()));
        must(PurpleSyncRetainedFiles.records(f.files.root).length!=0,"ordinary retained base survives carry completion");
        f.authority=String.join("",Collections.nCopies(64,"b"));
        try(PurpleSyncChatMoveStore store=f.open(()->true,false)){
            JSONObject next=f.begin().put("operation","fedcba98765432100123456789abcdef").put("source",f.target.token()).put("target",O.token())
                    .put("resolution",obj("action","Use","cache",obj("chat",f.target.token(),"id",Integer.toString(f.markId),"date","2000"),"winner",obj("chat",f.target.token(),"id",Integer.toString(f.markId),"date","2000")))
                    .put("eligibility",eligibilityJson(info(O,0,false).eligibility()));
            must(store.begin(next).status.equals("Valid"),"new-login next Begin archives exact terminal wrapper before replacing it");
            File[] history=new File(f.files.root,"carry-history").listFiles();must(history!=null&&history.length==1,"one retained terminal wrapper");
            must(history[0].getName().equals(hash(Files.readAllBytes(history[0].toPath()))+".json"),"history name binds immutable wrapper bytes");
        }
        must(!PurpleSyncChatMoveStore.completed(f.files.root,"0-777"),"new nonterminal operation still holds admission");f.close();
        Frame invalid=new Frame(false);invalid.start();File extra=new File(invalid.files.root,"carry-objects/record-"+String.join("",Collections.nCopies(64,"0"))+".json");PurpleModernSyncCases.write(extra,base);
        must(!PurpleSyncChatMoveStore.completed(invalid.files.root,"0-777"),"unreferenced object hash mismatch refuses ordinary admission");
        try(PurpleAccountSyncStore ordinary=new PurpleAccountSyncStore(invalid.files.root,PurpleAccountSyncStore.NATIVE,invalid.target)){must(ordinary.open(true,0,invalid.files.device).status==PurpleAccountSyncStore.Status.CarryPending,"ordinary startup does not bypass invalid retained evidence");}invalid.close();
        Frame fill=new Frame(false,false,"Fill");fill.start();must(fill.done(),"no-state terminal prerequisite");JSONObject initialized=json(PurpleSyncChatMoveStoreCases.initial).put("chat",fill.target.token());PurpleModernSyncCases.write(new File(fill.files.root,"state.json"),bytes(initialized.toString()));
        must(PurpleSyncChatMoveStore.completed(fill.files.root,"0-777"),"terminal no-state Fill remains integrity-valid after ordinary state initialization");fill.close();
        Frame badHistory=new Frame(false);badHistory.start();byte[] malformed=bytes("{}");PurpleModernSyncCases.write(new File(badHistory.files.root,"carry-history/"+hash(malformed)+".json"),malformed);
        must(!PurpleSyncChatMoveStore.completed(badHistory.files.root,"0-777"),"hash-matching malformed history still refuses admission");badHistory.close();
    }
    static void precommitDiscard()throws Exception {
        Frame f=new Frame(true,true);f.faultAction="CommitState";f.fault=1;f.start();must(!f.done()&&f.reason().equals("DurabilityFailed"),"precommit fault prerequisite");
        byte[] state=Files.readAllBytes(new File(f.files.root,"state.json").toPath()),stage=Files.readAllBytes(new File(f.files.root,"pending/config.json").toPath());
        f.close();Frame next=new Frame(f);next.authority=String.join("",Collections.nCopies(64,"b"));next.discard();must(next.done(),"precommit explicit Discard completes");
        must(Arrays.equals(state,Files.readAllBytes(new File(f.files.root,"state.json").toPath()))&&Arrays.equals(stage,Files.readAllBytes(new File(f.files.root,"pending/config.json").toPath())),"precommit Discard preserves exact original state and stage");next.close();
    }
    static void pendingRestarts()throws Exception {
        for(String action:new String[]{"ResolvePending","WritePendingState","RetirePendingStage"}){
            Frame f=new Frame(true,true);f.stopCore=action;f.start();must(!f.done(),"pending action boundary "+action);f.close();Frame next=new Frame(f);next.resume();must(next.done(),"pending reopen "+action+" "+next.reason());next.close();
        }
        for(String action:new String[]{"WritePendingState","RetirePendingStage"})for(int point:new int[]{1,5}){
            Frame f=new Frame(true,true);f.faultAction=action;f.fault=point;f.start();
            if(action.equals("RetirePendingStage")&&point==1){must(f.done(),"remove has no write-start fault");f.close();continue;}
            must(!f.done()&&f.reason().equals("DurabilityFailed"),"pending exact filesystem fault "+action+"/"+point+" "+f.reason());f.close();Frame next=new Frame(f);next.resume();must(next.done(),"pending durable reopen "+action+"/"+point+" "+next.reason());next.close();
        }
    }
    static void mergeCache()throws Exception {
        Frame f=new Frame(false);PurpleSyncChatCache.Data original=new PurpleSyncChatCache.Data(new Cache(O,1,1001),"retained",PurpleSyncChatCache.ReadersSnapshot.from(info(O,0,false)),Collections.singleton("library"),new JSONObject(),true);
        PurpleModernSyncCases.write(f.files.cache,PurpleSyncChatCache.encode(ORIGIN,AUTH,original));f.start();must(f.done(),"cache merge prerequisite");
        byte[] saved=Files.readAllBytes(f.files.cache.toPath());PurpleSyncChatCache.Loaded loaded=PurpleSyncChatCache.load(new PurpleSyncChatCache.IO(){public byte[] read(){return saved;}public boolean write(byte[] a,byte[] b){return false;}public boolean remove(byte[] a){return false;}},ORIGIN,AUTH);
        must(loaded.ok&&loaded.data.warned.containsAll(Arrays.asList("library","config"))&&loaded.data.newReaders,"complete cache unions disclosures and preserves sticky warning");f.close();
    }
    static void uncertaintyControls()throws Exception {
        Frame mark=new Frame(false);mark.dropKind="mark";mark.start();must(mark.ownWinner&&mark.deferred!=null,"mark effect happened without retained ack");mark.close();
        Frame restart=new Frame(mark);restart.resume();must(!restart.done()&&restart.effects==0&&restart.diskJournal().getBoolean("mark_attempted"),"lost mark acknowledgement holds frozen intent without blind repost");restart.close();
        Frame pending=new Frame(false,true);pending.dropKind="pending";pending.start();must(pending.effects==1,"pending O effect before ack");pending.close();
        Frame protectedRestart=new Frame(pending);protectedRestart.access="Protected";protectedRestart.resume();must(protectedRestart.done()&&!protectedRestart.commands.stream().anyMatch(c->c.permit!=null&&c.permit.kind.equals("pending")),"issued pending found under protection is reconciled without another O send");protectedRestart.close();
        Frame missing=new Frame(true);missing.omitBase=true;missing.start();must(!missing.done()&&missing.effects==0&&missing.reason().equals("MissingLocalVersion"),"missing exact own base never fabricates a version "+missing.reason());missing.close();
    }
    static void ownClaim()throws Exception {
        org.telegram.messenger.UserConfig.getInstance(0).user=777;
        try(Harness h=new Harness(true)) {
            h.reserve();h.intent();PurpleSyncChatMoveIO.Permit p=h.authorize("own");UploadPort first=new UploadPort(h),second=new UploadPort(h);
            PurpleSyncAutoUpload a=new PurpleSyncAutoUpload(p,()->true,first,r->{});a.start();
            PurpleSyncChatMoveIO.Permit renewed=h.io.renew(p,h.fresh(),null);
            boolean refused=false;try{new PurpleSyncAutoUpload(renewed,()->true,second,r->{});}catch(IllegalArgumentException expected){refused=true;}
            must(refused&&first.parts==1&&second.parts==0,"actual uploader constructors consume one shared claim through renewal");a.cancel();first.drain();
        }
    }
    static void pendingProofs()throws Exception {
        Frame f=new Frame(false,true);f.stop="Pending";f.start();
        PurpleSyncChatMoveIO a=new PurpleSyncChatMoveIO(f.opened,ORIGIN,()->true),b=new PurpleSyncChatMoveIO(f.opened,ORIGIN,()->true);
        PurpleSyncChatMoveIO.ReadTicket first=a.beginPendingRead(f.fresh()),other=b.beginPendingRead(f.fresh());
        PurpleSyncInventory scan=f.inventory(true);PurpleSyncChatMoveIO.PendingProof proof=a.consumePendingRead(first,scan,f.fresh());
        try{b.consumePendingRead(other,f.inventory(true),f.fresh());throw new AssertionError("shared pending ticket replay");}catch(java.io.IOException expected){must(expected.getMessage().equals("StaleRead"),"pending proofs share slot generation across wrappers");}
        PurpleSyncChatMoveIO.Permit issued=a.pendingPermit(f.fresh(),null,proof);
        must(issued.current(),"one consumed fresh absence admits pending permit");
        PurpleSyncChatMoveIO.Permit renewed=a.renew(issued,f.fresh(),null);
        must(!issued.current()&&renewed.current()&&issued.randomId==renewed.randomId,"pending renewal preserves exact active attempt");
        a.pendingReceipt(renewed);
        try{a.pendingPermit(f.fresh(),null,proof);throw new AssertionError("pending proof consumed twice");}catch(java.io.IOException expected){must(expected.getMessage().equals("StaleRead"),"pending absence proof consumed once");}
        a.close();b.close();f.close();
        Frame reopened=new Frame(f);reopened.stop="Pending";reopened.resume();
        PurpleSyncChatMoveIO next=new PurpleSyncChatMoveIO(reopened.opened,ORIGIN,()->true);PurpleSyncChatMoveIO.ReadTicket ticket=next.beginPendingRead(reopened.fresh());
        try{next.consumePendingRead(ticket,scan,reopened.fresh());throw new AssertionError("pre-open pending scan");}catch(java.io.IOException expected){must(expected.getMessage().equals("StaleRead"),"reopened pending proof requires post-open scan");}
        next.close();reopened.close();
    }
    public static void main(String[] args)throws Exception {
        rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));for(JSONObject row:rows)if(row.getString("op").equals("begin")&&row.getJSONObject("expected").getString("status").equals("Valid")){beginning=row.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        parent=new File(args[1]);PurpleModernSyncCases.directory(parent);PurpleModernSyncCases.Frame setup=new PurpleModernSyncCases.Frame(parent,"routing",true);root=setup.root;
        PurpleSyncChatPendingChronologyCases.run();pendingProofs();happy();modes();playlistHolds();observerAndForeground();effectCrash();ownMarkWinnerRestart();failedReads();quiescence();pendingCases();precommitDiscard();deferredDiscardCases();cleanupGone();cleanupControls();lifecycleCallbacks();lockOrder();actionRestarts();durabilityRestarts();pendingRestarts();mergeCache();uncertaintyControls();ownClaim();terminalAdmission();
        System.out.println("CARRY_EXECUTOR_CHECKS="+(checks+PurpleSyncChatMoveIOCases.checks));
    }
}
