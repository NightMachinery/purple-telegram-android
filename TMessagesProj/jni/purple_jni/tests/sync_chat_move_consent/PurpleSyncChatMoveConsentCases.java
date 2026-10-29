package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.json.*;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

public final class PurpleSyncChatMoveConsentCases {
    static int checks,sequence;
    static void must(boolean value,String why){++checks;if(!value)throw new AssertionError(why);}
    static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;List<String> keys=new ArrayList<>(o.keySet());Collections.sort(keys);List<String> fields=new ArrayList<>();for(String key:keys)fields.add(JSONObject.quote(key)+":"+canonical(o.get(key)));return "{"+String.join(",",fields)+"}";}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;List<String> items=new ArrayList<>();for(int i=0;i<a.length();++i)items.add(canonical(a.get(i)));return "["+String.join(",",items)+"]";}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    static PurpleSyncChatClient.Info information(PurpleSyncChatOrigin origin,Peer chat,int members,boolean protectedContent){
        PurpleSyncChatClient.Info template=PurpleSyncChatMoveIOCases.info(chat,members,protectedContent);
        Set<Long> ids=new HashSet<>();ids.add(origin.userId);for(int i=0;i<members;++i)ids.add(888L+i);
        PurpleSyncChatFacts.MappedPeer mapped=new PurpleSyncChatFacts.MappedPeer(origin,chat,template.peer.kind,Truth.True,Truth.True,template.peer.facts,ids);
        return new PurpleSyncChatClient.Info(origin,chat,mapped,PurpleSyncChatClient.Error.None,"consent target",ids,true);
    }
    static final class CloudClient implements PurpleSyncClient {
        final PurpleSyncChatIO route;final List<TLRPC.Message> records;final Map<Integer,byte[]> bodies;
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();final File directory;
        CloudClient(Peer chat,List<TLRPC.Message> records,Map<Integer,byte[]> bodies,PurpleSyncChatOrigin origin)throws Exception {
            PurpleSyncChatClient.Info info=information(origin,chat,0,false);route=new PurpleSyncChatIO(origin,chat,info.peer);this.records=new ArrayList<>(records);this.records.sort((a,b)->Integer.compare(b.id,a.id));this.bodies=bodies;directory=new File(root,"cloud-read-"+(++sequence));PurpleModernSyncCases.directory(directory);
        }
        public long userId(){return route.origin.userId;}public Peer chat(){return route.chat;}public PurpleSyncChatOrigin origin(){return route.origin;}public boolean isActive(){return true;}
        public void runOnMain(Runnable r){queue.add(r);}public void runOnWorker(Runnable r){queue.add(r);}public void log(String s){}
        public int requestHistory(int offset,int limit,Reply<History> done){TLRPC.messages_Messages response=page(chat(),offset==0?records.toArray(new TLRPC.Message[0]):new TLRPC.Message[0]);response.count=records.size();done.onReply(route.history(response,null,PurpleSyncChatMoveIOCases::meta));return 1;}
        public int requestMessage(int id,Reply<Lookup> done){TLRPC.Message m=records.stream().filter(v->v.id==id).findFirst().orElse(null);done.onReply(m==null?Lookup.failed("Unknown"):route.lookup(page(chat(),m),null,id,PurpleSyncChatMoveIOCases::meta));return 2;}
        public void cancelRequest(int id){}public Object download(Message m,Reply<File> done){try{File f=new File(directory,"record-"+m.id);Files.write(f.toPath(),bodies.get(m.id));done.onReply(f);}catch(Exception e){throw new RuntimeException(e);}return null;}
        public void cancelDownload(Object token){}public void localCopies(Reply<List<LocalCopy>> done){done.onReply(Collections.emptyList());}
        public File filesDirectory(){return root;}public File cacheDirectory(){return root;}public long nowMillis(){return 1;}
        public void send(File f,String c,String m,Receipt r){throw new AssertionError("no live send");}
        PurpleSyncInventory inventory(){PurpleSyncTransport.CheckResult[] out={null};new PurpleSyncTelegramTransport(new PurpleSyncChatRecordClient(this,m->route.ownership((TLRPC.Message)m.remote))).check(null,r->out[0]=r);int cap=10000;while(!queue.isEmpty()){must(--cap>0,"bounded cloud scanner");queue.removeFirst().run();}must(out[0]!=null,"complete cloud callback");return out[0].inventory;}
    }
    static final class Carry implements PurpleSyncChatMove.Port {
        final PurpleSyncChatMoveStoreCases.Fixture files;
        final Peer target;
        final String mode;
        PurpleSyncChatClient.Info targetInfo;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        final List<PurpleSyncChatMove.Command> commands=new ArrayList<>();
        final List<PurpleSyncChatMove.Status> statuses=new ArrayList<>();
        final List<TLRPC.Message> oldRecords=new ArrayList<>(),newRecords=new ArrayList<>();
        final Map<Integer,byte[]> oldBodies=new HashMap<>(),newBodies=new HashMap<>();
        PurpleSyncChatMove move;
        PurpleSyncChatMoveStore opened,injectedStore;
        String authority=AUTH,access="Readable",stop="",stopCore="",dropKind="",faultAction="";
        boolean libraryPending,fakePlaylist,removeOld,duplicate,omitBase;
        String playlistStatus="Verified";
        DirectRead oldReadOverride;
        boolean foreground=true,live=true,ownWinner,dropEffect,stopAfterMark,badMark,badDestination,closeObserver,throwCancel,omitPendingInventory;
        int effects,markId=90,reads,quiesces,cancels;
        int fault;
        Consumer<PurpleSyncChatMove.Reply> deferred;
        PurpleSyncChatMove.Command deferredCommand;
        final PurpleSyncChatOrigin origin;
        final Peer source;
        Carry(String mode,boolean pending,Peer source,PurpleSyncChatOrigin origin)throws Exception {
            boolean own=false;Peer chosen=null;this.origin=origin;this.source=source;
            this.mode=mode;
            JSONObject plan=copy(rows.get(own?28:43).getJSONObject("input"));target=chosen!=null?chosen:mode.equals("RepairMark")?source:Peer.parse(plan.getString("target"),777);
            files=new PurpleSyncChatMoveStoreCases.Fixture(root,"executor-"+(++sequence),mode.equals("Fill")?null:unblob(pending?rows.get(120).getJSONObject("input").getString("state"):plan.getString("state")));
            if(source.kind==PeerKind.Self&&!mode.equals("Fill")) {
                JSONObject state=json(Files.readAllBytes(new File(files.root,"state.json").toPath()));state.put("version",1);state.remove("chat");
                PurpleModernSyncCases.write(new File(files.root,"state.json"),bytes(canonical(state)));
            }
            if(pending)PurpleModernSyncCases.write(new File(files.root,"pending/config.json"),unblob(rows.get(121).getJSONObject("input").getJSONObject("event").getString("stagedRecord")));
            access=own?"Protected":"Readable";
            oldRecords.add(doc(source,2,1001,base));oldBodies.put(2,base);
            if(mode.equals("Follow")||mode.equals("Fill")){TLRPC.TL_message mark=doc(target,markId,2000,base);mark.media=null;mark.flags&=~(1<<9);mark.message=PurpleSyncChatSetupRequests.MARK;newRecords.add(mark);}
        }
        Carry(Carry previous){mode=previous.mode;files=previous.files;source=previous.source;origin=previous.origin;target=previous.target;access=previous.access;targetInfo=previous.targetInfo;oldRecords.addAll(previous.oldRecords);newRecords.addAll(previous.newRecords);oldBodies.putAll(previous.oldBodies);newBodies.putAll(previous.newBodies);ownWinner=previous.ownWinner;}
        JSONObject begin()throws Exception{return copy(beginning).put("operation",String.format(Locale.ROOT,"%032x",sequence)).put("account",origin.accountKey()).put("source",source.token()).put("target",target.token()).put("mode",mode).put("removeOldMarkAuthorized",removeOld).put("preferenceToken",blob(files.binding.getBytes(java.nio.charset.StandardCharsets.UTF_8))).put("markText",PurpleSyncChatSetupRequests.MARK).put("loginAuthority",authority.substring(0,32));}
        public PurpleSyncChatOrigin origin(){return origin;}public String authority(){return authority;}
        public String operationKey(){return "executor:"+files.root.getName();}
        public boolean current(){return live;}public boolean foreground(){return foreground;}
        public void worker(Runnable task){tasks.addLast(task);}
        public void quiesce(Runnable done){++quiesces;tasks.addLast(done);}
        public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception {
            if(injectedStore!=null){opened=injectedStore;return opened;}
            opened=new PurpleSyncChatMoveStore(files.root,files.owner(),origin.slot,origin.accountKey(),files.binding,authority,files.device,files.cache,current);
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
            PurpleSyncChatClient.Info source=info(this.source,0,access.equals("Protected")),dest=targetInfo!=null?targetInfo:info(target,0,false);
            ResolveInput r=new ResolveInput(origin.userId);r.settingsBound=true;r.settingsChat=this.source;r.cache=new Cache(this.source,1,1001);r.cacheRead=DirectRead.Valid;r.cachedEligibility=source.eligibility();r.settingsHint=SettingsHint.Found;
            if(ownWinner)r.marks=Arrays.asList(new Mark(new Cache(target,markId,2000),MarkStatus.Supported,dest.eligibility(),RecordPresence.Present,true,true),new Mark(r.cache,MarkStatus.Supported,source.eligibility(),RecordPresence.Present,true,true));
            else r.marks=Collections.singletonList(new Mark(r.cache,MarkStatus.Supported,source.eligibility(),RecordPresence.Present,true,true));
            if(mode.equals("Follow")||mode.equals("Fill")){
                r.marks=Arrays.asList(new Mark(new Cache(target,markId,2000),MarkStatus.Supported,dest.eligibility(),newBodies.isEmpty()?RecordPresence.Empty:RecordPresence.Present,true,true),new Mark(new Cache(this.source,1,1001),MarkStatus.Supported,source.eligibility(),RecordPresence.Present,true,true));
                if(mode.equals("Fill")){r.cache=null;r.settingsBound=false;}
            }
            if(mode.equals("RepairMark")&&!ownWinner){r.cacheRead=DirectRead.MarkGone;r.marks=Collections.emptyList();}
            return new PurpleSyncChatMoveIO.Fresh(origin,authority,source,input(this.source),dest,input(target),r,true,oldReadOverride!=null?oldReadOverride:mode.equals("RepairMark")&&!ownWinner?DirectRead.MarkGone:access.equals("ChatGone")?DirectRead.ChatGone:DirectRead.Valid);
        }
        PurpleSyncInventory inventory(boolean source)throws Exception {
            ++reads;List<TLRPC.Message> records=source?oldRecords:newRecords;Map<Integer,byte[]> bodies=source?oldBodies:newBodies;
            List<TLRPC.Message> settings=new ArrayList<>();for(TLRPC.Message m:records)if(m.media!=null)settings.add(m);
            byte[] body=settings.isEmpty()?base:bodies.get(settings.get(0).id);
            if(!source&&badDestination)settings.clear();
            return new CloudClient(source?this.source:target,settings,bodies,origin).inventory();
        }
        PurpleSyncChatMove.Reply reply()throws Exception {PurpleSyncChatMove.Reply r=new PurpleSyncChatMove.Reply();r.fresh=fresh();r.sourceAccess=access;r.libraryPending=libraryPending;if(fakePlaylist)r.playlist=obj("status",playlistStatus,"account",origin.accountKey(),"source",source.token(),"target",target.token(),"planStamp","fake","destinationStamp","fake");return r;}
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
                        TLRPC.TL_message record=doc(pending?source:target,id,1500,permit.payload());(pending?oldRecords:newRecords).add(record);(pending?oldBodies:newBodies).put(id,permit.payload());
                    }
                    r.response=ack(permit,permit.kind.equals("mark")?markId:permit.kind.equals("pending")?22:55);
                    if(dropEffect||permit.kind.equals(dropKind)){deferred=done;deferredCommand=command;return;}break;
                default:throw new AssertionError(command.action);
                }
                done.accept(r);if(duplicate)done.accept(r);
            }catch(Exception e){throw new RuntimeException(e);}
        }
        PurpleSyncChatClient.Info info(Peer chat,int members,boolean protectedContent){return information(origin,chat,members,protectedContent);}
        PurpleSyncChatIO route(Peer chat){PurpleSyncChatClient.Info i=info(chat,0,false);return new PurpleSyncChatIO(origin,chat,i.peer);}
        TLRPC.TL_message doc(Peer chat,int id,int date,byte[] body){TLRPC.TL_message m=PurpleSyncChatMoveIOCases.doc(chat,id,date,body);if(chat.kind==PeerKind.Self)((TLRPC.TL_peerUser)m.peer_id).user_id=origin.userId;m.from_id=new TLRPC.TL_peerUser();m.from_id.user_id=origin.userId;return m;}
        void start()throws Exception{move=new PurpleSyncChatMove(this);move.start(begin(),info(target,0,false));drain();}
        void resume()throws Exception{move=new PurpleSyncChatMove(this);move.resume(info(target,0,false));drain();}
        void discard()throws Exception{move=new PurpleSyncChatMove(this);move.discardInterrupted(true);drain();}
        void drain(){int cap=10000;while(!tasks.isEmpty()){must(--cap>0,"executor queue is bounded");tasks.removeFirst().run();}}
        void close(){if(move!=null)move.close();drain();}
        boolean done(){return statuses.stream().anyMatch(s->s.terminal);}
        String reason(){return statuses.isEmpty()?"none":statuses.get(statuses.size()-1).reason;}
        JSONObject diskJournal()throws Exception{return json(Files.readAllBytes(new File(files.root,"carry.json").toPath())).getJSONObject("journal");}
    }
    static final class Screen implements PurpleSyncChatScreen.Port {
        final Carry carry;
        PurpleSyncChatScreen.Frame frame;
        PurpleSyncChatClient.Info target;
        PurpleSyncChatOrigin origin;
        String authority=AUTH;
        boolean current=true,held,defer;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        Screen(Carry carry)throws Exception{this.carry=carry;origin=carry.origin;target=carry.info(carry.target,0,false);refresh();}
        void refresh()throws Exception{
            PurpleSyncChatMoveIO.Fresh fresh=carry.fresh();byte[] state=carry.mode.equals("Fill")?null:Files.readAllBytes(new File(carry.files.root,"state.json").toPath());
            PurpleSyncChatCache.Loaded opening=PurpleSyncChatCache.load(PurpleSyncChatCache.file(carry.files.cache),origin,authority);
            frame=new PurpleSyncChatScreen.Frame(origin,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Paused,PurpleSyncChatCore.resolve(fresh.resolution),"FollowRequired",true,true,0)),
                    new PurpleSyncChatService.Binding(true,state!=null,state==null?null:carry.source,"binding",state==null?"missing":hash(state)),opening,fresh.source,Collections.emptyList());
        }
        public PurpleSyncChatOrigin origin(){return origin;}public String authority(){return authority;}public boolean current(){return current;}
        public boolean begin(Object owner){if(held)return false;held=true;return true;}public void end(Object owner){held=false;}public void cancel(Object owner){}
        void deliver(Runnable task){if(defer)tasks.add(task);else task.run();}
        public void resolve(Object owner,Consumer<PurpleSyncChatScreen.Frame> done){deliver(()->done.accept(frame));}
        public void inspect(Peer chat,Consumer<PurpleSyncChatClient.Info> done){deliver(()->done.accept(target));}
        public void remember(PurpleSyncChatScreen.Frame f,PurpleSyncChatScreen.Candidate c,Consumer<Boolean> done){throw new AssertionError("Follow consent writes no ordinary cache");}
        void drain(){while(!tasks.isEmpty())tasks.remove().run();}
    }
    static final class Flow implements PurpleSyncChatMoveConsent.Port {
        final Carry carry;final Screen screen;final PurpleSyncChatScreen.Control control;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();final List<PurpleSyncChatMoveConsent.State> states=new ArrayList<>();
        Runnable onCarrying;
        PurpleSyncChatMove.Port delegate;
        PurpleSyncChatMoveConsent flow;boolean live=true,foreground=true,closeObserver,throwObserver,throwCancel;int executors,cancels;
        Flow(String mode,boolean pending,Peer source,PurpleSyncChatOrigin origin)throws Exception{carry=new Carry(mode,pending,source,origin);screen=new Screen(carry);control=new PurpleSyncChatScreen.Control(screen);}
        Flow(String mode)throws Exception{this(mode,false,O,ORIGIN);}
        public PurpleSyncChatOrigin origin(){return screen.origin;}public String authority(){return screen.authority;}public boolean current(){return live;}public boolean foreground(){return foreground;}
        public void worker(Runnable task){tasks.add(task);}public void resolve(String stream,Consumer<PurpleSyncChatScreen.Frame> done){worker(()->done.accept(screen.frame));}
        public void inspect(Peer chat,Consumer<PurpleSyncChatClient.Info> done){worker(()->done.accept(screen.target));}
        public JSONObject begin(PurpleSyncChatMoveConsent.Selection selection)throws Exception{return carry.begin();}
        public PurpleSyncChatMove.Port carry(){++executors;return delegate!=null?delegate:carry;}public void cancel(){++cancels;if(throwCancel)throw new IllegalStateException("cancel");}
        public void status(PurpleSyncChatMoveConsent.State state){states.add(state);if(state.phase.equals("Carrying")&&onCarrying!=null)onCarrying.run();if(closeObserver&&state.phase.equals("Carrying"))flow.close();if(throwObserver)throw new IllegalStateException("observer");}
        PurpleSyncChatMoveConsent.Offer offer(String stream){PurpleSyncChatMoveConsent.Offer[] result={null};control.offerFollowFill(stream,x->result[0]=x);return result[0];}
        PurpleSyncChatMoveConsent.Selection accept(PurpleSyncChatMoveConsent.Offer offer,boolean readers){PurpleSyncChatMoveConsent.Selection[] result={null};control.acceptFollowFill(offer,readers,x->result[0]=x);return result[0];}
        void start(String stream){PurpleSyncChatMoveConsent.Selection selection=accept(offer(stream),true);must(selection!=null,"fresh consent accepted");flow=new PurpleSyncChatMoveConsent(this,selection);flow.start();drain();}
        void drain(){int cap=20000;while(!tasks.isEmpty()||!carry.tasks.isEmpty()){must(--cap>0,"bounded consent queue");if(!tasks.isEmpty())tasks.remove().run();else carry.tasks.remove().run();}}
        void close(){if(flow!=null)flow.close();drain();control.close();}
        String reason(){return states.isEmpty()?"":states.get(states.size()-1).reason;}
    }
    static void consent()throws Exception{
        for(String mode:new String[]{"Follow","Fill"})for(String stream:new String[]{"config","library"})for(boolean readers:new boolean[]{false,true}){
            Flow f=new Flow(mode);f.screen.target=f.carry.info(f.carry.target,readers?2:0,false);f.carry.targetInfo=f.screen.target;
            android.os.Looper.mainThread=Thread.currentThread();
            PurpleSyncChatMoveConsent.Offer offer=f.offer(stream);must(offer!=null&&offer.action.name().equals(mode)&&offer.requiresReaders==readers,"complete resolver offers exact mode and readers");
            must(f.accept(offer,false)!=null==!readers,"no invented reader question; decline other-reader grant");
            if(readers)must(f.accept(offer,true)!=null,"explicit per-stream grant accepted");
            String text=PurpleSyncChatMoveConsent.disclosure(offer,(key,args)->key+Arrays.toString(args));
            must(text.contains(mode+"About")&&text.contains(stream.equals("config")?"Cloud":"LibraryCloud"),"typed Follow/first-post disclosure");
            if(stream.equals("library"))must(text.contains("Cloud")&&text.contains("SettingsReaders"),"library carry explicitly discloses settings payload too");
            must(!PurpleSyncChatMoveConsent.ACTIVATED&&PurpleSyncChatMoveConsent.production(f.accept(offer,true),ORIGIN,AUTH,()->true).reason.equals("QualificationHeld")&&f.executors==0,"production constant OFF no worker/executor/intent");
            android.os.Looper.mainThread=null;must(!new File(f.carry.files.root,"carry.json").exists(),"offer/accept no durable intent");f.close();
        }
        for(String change:new String[]{"state","cache","winner","readers","rights","account","login","foreground","closed"}){
            Flow f=new Flow("Follow");PurpleSyncChatMoveConsent.Offer offer=f.offer("config");must(offer!=null,"opening offer");
            PurpleSyncChatScreen.Frame old=f.screen.frame;
            if(change.equals("state"))f.screen.frame=new PurpleSyncChatScreen.Frame(ORIGIN,old.status,new PurpleSyncChatService.Binding(true,true,O,"binding","changed"),old.opening,old.current.info,old.refused);
            if(change.equals("cache"))f.screen.frame=new PurpleSyncChatScreen.Frame(ORIGIN,old.status,old.binding,new PurpleSyncChatCache.Loaded(true,new byte[]{1},null),old.current.info,old.refused);
            if(change.equals("winner")){f.carry.markId++;f.screen.refresh();}
            if(change.equals("readers"))f.screen.target=f.carry.info(f.carry.target,1,false);
            if(change.equals("rights"))f.screen.target=f.carry.info(f.carry.target,0,true);
            if(change.equals("account"))f.screen.origin=new PurpleSyncChatOrigin(1,1,888,"other");
            if(change.equals("login"))f.screen.authority="b".repeat(64);
            if(change.equals("foreground"))f.screen.current=false;if(change.equals("closed"))f.control.close();
            must(f.accept(offer,true)==null,"stale or departed consent cannot be accepted: "+change);f.close();
        }
        Flow background=new Flow("Follow");background.screen.defer=true;int[] dialogs={0};background.control.offerFollowFill("config",o->{if(o!=null)++dialogs[0];});background.screen.current=false;background.screen.drain();must(dialogs[0]==0&&!background.screen.held,"no background dialog after queued discovery");background.close();
    }
    static void flows()throws Exception{
        for(String mode:new String[]{"Follow","Fill"})for(String stream:new String[]{"config","library"}){
            Flow f=new Flow(mode);f.start(stream);must(f.states.stream().anyMatch(x->x.committed),"actual Carry committed "+mode+" "+stream+" "+f.reason());
            must(f.carry.effects==1&&f.carry.commands.stream().noneMatch(c->c.permit!=null&&c.permit.kind.equals("mark")),"existing winner mark no duplicate mark");
            if(mode.equals("Fill"))must(!new File(f.carry.files.root,"state.json").exists(),"Fill never creates binding/state");
            PurpleSyncChatCache.Loaded cache=PurpleSyncChatCache.load(PurpleSyncChatCache.file(f.carry.files.cache),ORIGIN,AUTH);
            must(cache.ok&&cache.data.location.chat.equals(f.carry.target)&&cache.data.accepted("config")&&cache.data.accepted(stream),"cache commits disclosed streams to own account");f.close();
        }
        Flow implicit=new Flow("Follow",false,Peer.self(),ORIGIN);implicit.start("config");must(implicit.states.stream().anyMatch(x->x.committed),"implicit-v1 Self Follow completes actual Carry "+implicit.reason());implicit.close();
        Flow pending=new Flow("Follow",true,O,ORIGIN);pending.start("config");must(pending.states.stream().anyMatch(x->x.committed)&&pending.carry.commands.stream().anyMatch(c->c.permit!=null&&c.permit.kind.equals("pending")),"offline queued O post reconciled before Follow "+pending.reason());pending.close();
        Flow unreadable=new Flow("Fill");unreadable.carry.access="ChatGone";unreadable.carry.oldRecords.clear();unreadable.screen.refresh();unreadable.start("config");must(unreadable.carry.effects==0&&!unreadable.states.stream().anyMatch(x->x.committed),"missing Fill source cannot recover empty");unreadable.close();
        Flow a=new Flow("Fill");Flow b=new Flow("Fill",false,O,new PurpleSyncChatOrigin(1,1,777,"second-account"));a.start("config");b.start("config");
        must(a.states.stream().anyMatch(x->x.committed)&&b.states.stream().anyMatch(x->x.committed),"two accounts commit through own Carry/store "+a.reason()+" "+b.reason());
        must(!a.carry.files.root.equals(b.carry.files.root)&&!a.carry.files.cache.equals(b.carry.files.cache),"distinct account move and cache files");
        must(PurpleSyncChatCache.load(PurpleSyncChatCache.file(a.carry.files.cache),b.origin(),AUTH).data==null&&PurpleSyncChatCache.load(PurpleSyncChatCache.file(b.carry.files.cache),a.origin(),AUTH).data==null,"account cache cannot cross-admit");a.close();b.close();
    }
    static PurpleSyncChatClient.Info changed(Carry carry,String change){
        PurpleSyncChatClient.Info info=carry.info(carry.target,change.equals("count")?3:change.equals("departure")?1:2,false);
        Set<Long> members=info.members;
        if(change.equals("swap"))members=new HashSet<>(Arrays.asList(carry.origin.userId,888L,999L));
        if(change.equals("list"))members=null;
        if(change.equals("unknown"))info.peer.facts.otherMembers=null;
        if(change.equals("rights"))info.peer.facts.canSendFiles=false;
        return new PurpleSyncChatClient.Info(carry.origin,carry.target,info.peer,info.error,"changed",members,!change.equals("unavailable"));
    }
    static void readers()throws Exception{
        for(String mode:new String[]{"Follow","Fill"})for(boolean after:new boolean[]{false,true})for(String change:new String[]{"count","swap","departure","list","unknown","rights","unavailable"}){
            Flow f=new Flow(mode);f.screen.target=f.carry.info(f.carry.target,2,false);f.carry.targetInfo=f.screen.target;f.carry.stop=after?"Effect":"Source";f.start("config");
            must(f.carry.deferredCommand!=null&&f.carry.effects==0,"stop at exact reader boundary before effect");
            JSONObject original=json(Files.readAllBytes(new File(f.carry.files.root,"carry.json").toPath()));
            JSONObject snapshot=original.getJSONObject("transport").getJSONObject("readers").getJSONObject("snapshot");
            must(snapshot.getString("otherMembers").equals("2"),"accepted original baseline before first N intent");
            must(original.getJSONObject("journal").getBoolean("delivery_attempted")==after,"test spans durable first delivery intent");
            Carry next=new Carry(f.carry);next.targetInfo=changed(next,change);f.close();next.resume();
            boolean hold=change.equals("list")||change.equals("unknown")||change.equals("rights")||change.equals("unavailable")||!after&&(change.equals("count")||change.equals("swap"));
            must(next.done()==!hold,"C6 boundary "+mode+" "+change+" afterIntent="+after+" reason="+next.reason());
            if(hold)must(next.effects==0,"held readers never dispatch");
            else {
                PurpleSyncChatCache.Loaded cache=PurpleSyncChatCache.load(PurpleSyncChatCache.file(next.files.cache),next.origin,AUTH);
                must(cache.ok&&cache.data!=null&&cache.data.readers.otherMembers==2L,"restart and commit preserve original reader count");
                must(cache.data.readers.memberHashes.equals(PurpleSyncChatCache.ReadersSnapshot.from(f.screen.target).memberHashes),"same-count membership baseline never replaced");
                must(cache.data.newReaders==(change.equals("count")||change.equals("swap")),"known additions warn, departures do not");
                PurpleSyncChatCache.Loaded reloaded=PurpleSyncChatCache.load(PurpleSyncChatCache.file(next.files.cache),next.origin,AUTH);
                must(reloaded.data.newReaders==cache.data.newReaders,"sticky warning survives another cache reopen");
                if(cache.data.newReaders){PurpleSyncChatCache.Data returned=cache.data.next(cache.data.location,f.screen.target);must(returned.newReaders,"warning remains after readers return to original");}
            }
            next.close();
        }
        Flow missing=new Flow("Follow");missing.carry.stop="Fresh";missing.start("config");must(missing.carry.opened.journalBytes()==null,"before Begin no journal");missing.close();
        Flow baseline=new Flow("Follow");baseline.carry.stop="Source";baseline.start("config");byte[] frozen=Files.readAllBytes(new File(baseline.carry.files.root,"carry.json").toPath());
        JSONObject transport=baseline.carry.opened.transport(),replacement=new JSONObject(transport.toString());replacement.getJSONObject("readers").getJSONObject("snapshot").put("otherMembers","90");
        boolean rejected=false;try{baseline.carry.opened.transport(replacement,false);}catch(java.io.IOException expected){rejected=true;}
        must(rejected&&Arrays.equals(frozen,Files.readAllBytes(new File(baseline.carry.files.root,"carry.json").toPath())) ,"native wrapper refuses original baseline replacement");baseline.close();
    }
    static void finalDisclosure()throws Exception{
        for(boolean mutate:new boolean[]{false,true}){
            Flow f=new Flow("Follow");f.carry.targetInfo=f.screen.target;f.onCarrying=()->{if(mutate)f.screen.target.peer.facts.otherMembers=1L;else f.carry.targetInfo=f.carry.info(f.carry.target,1,false);};f.start("config");
            must(f.reason().equals("ReaderConsentChanged")&&f.carry.effects==0&&!new File(f.carry.files.root,"carry.json").exists(),"immutable shown facts checked at final Begin: "+mutate);f.close();
        }
    }
    static void lifecycle()throws Exception{
        for(String boundary:new String[]{"background","origin","login","owner","observer","throw"}){
            Flow f=new Flow("Follow");PurpleSyncChatMoveConsent.Selection selection=f.accept(f.offer("config"),true);f.flow=new PurpleSyncChatMoveConsent(f,selection);
            if(boundary.equals("background"))f.foreground=false;if(boundary.equals("origin"))f.screen.origin=new PurpleSyncChatOrigin(0,0,888,"other");if(boundary.equals("login"))f.screen.authority="b".repeat(64);if(boundary.equals("owner"))f.control.close();if(boundary.equals("observer"))f.closeObserver=true;if(boundary.equals("throw"))f.throwObserver=true;
            f.flow.start();f.drain();must(f.executors==0&&f.carry.effects==0&&!new File(f.carry.files.root,"carry.json").exists(),"revoked or reentrant callback cannot begin: "+boundary);f.close();
        }
        Flow f=new Flow("Follow");f.carry.stop="Source";f.start("config");must(PurpleSyncOperations.carryHeld(f.carry.operationKey()),"actual Carry lease held at cancellation boundary");f.throwCancel=true;
        boolean threw=false;try{f.close();}catch(IllegalStateException expected){threw=true;}must(!threw&&f.cancels>0&&!PurpleSyncOperations.carryHeld(f.carry.operationKey()),"throwing outer cleanup is contained and releases exact Carry lease");
        f.throwCancel=false;f.close();Carry again=new Carry(f.carry);again.resume();must(again.done(),"new operation can resume after throwing cleanup "+again.reason());again.close();
    }
    static void restartWarning()throws Exception{
        for(String mode:new String[]{"Follow","Fill"}){
            Flow f=new Flow(mode);f.carry.stop="Effect";f.start("library");Carry middle=new Carry(f.carry);middle.targetInfo=middle.info(middle.target,1,false);middle.stop="Destination";f.close();middle.resume();
            must(!middle.done()&&middle.opened.transport().getBoolean("reader_warning"),"warning durable before next read/write");String baseline=canonical(middle.opened.transport().getJSONObject("readers").getJSONObject("snapshot"));
            Carry reopened=new Carry(middle);reopened.targetInfo=reopened.info(reopened.target,0,false);middle.close();reopened.resume();
            must(reopened.done(),"warned restart continues without asking again "+reopened.reason());PurpleSyncChatCache.Loaded cache=PurpleSyncChatCache.load(PurpleSyncChatCache.file(reopened.files.cache),reopened.origin,AUTH);
            must(cache.data.newReaders&&cache.data.accepted("config")&&cache.data.accepted("library")&&canonical(cache.data.readers.json()).equals(baseline),"warning, original baseline and disclosed streams survive two restarts");reopened.close();
        }
    }
    static void missingBaseline()throws Exception{
        Carry f=new Carry("Follow",false,O,ORIGIN);PurpleSyncChatMoveStore store=f.open(()->true,false);
        JSONObject resolution=obj("action","Follow","cache",obj("chat",O.token(),"id","1","date","1001"),"winner",obj("chat",f.target.token(),"id",Integer.toString(f.markId),"date","2000"));
        JSONObject begin=f.begin().put("resolution",resolution).put("oldRead","Valid").put("sourceAccess","Readable").put("readerConsentAccepted",true);
        must(store.begin(begin).status.equals("Valid")&&store.transport().length()==0,"durable Begin before full snapshot models crash gap");store.close();f.resume();
        must(f.reason().equals("ReadersBaselineMissing")&&f.effects==0,"restart cannot invent full baseline after crash gap");f.move.retry();f.drain();must(f.reason().equals("ReadersBaselineMissing")&&f.effects==0,"explicit generic retry cannot replace missing original consent");f.close();
    }
    static void accountFences()throws Exception{
        PurpleSyncChatOrigin other=new PurpleSyncChatOrigin(1,1,777,"second-account");
        Flow a=new Flow("Fill"),b=new Flow("Fill",false,O,other);a.delegate=b.carry;a.start("config");
        must(a.reason().equals("AccountChanged")&&a.carry.effects==0&&!new File(b.carry.files.root,"carry.json").exists(),"wrong-account delegate refused before opening store");a.close();b.close();
        a=new Flow("Fill");b=new Flow("Fill",false,O,other);a.carry.injectedStore=b.carry.open(()->true,false);a.start("config");
        must(a.reason().equals("AccountUnbound")&&a.carry.effects==0&&!new File(b.carry.files.root,"carry.json").exists(),"wrong-account store refused before Begin or cache commit");a.close();b.close();
    }
    static void serviceAdmission(){
        for(boolean fill:new boolean[]{false,true})for(String stream:new String[]{"config","library"}){
            PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();Peer self=Peer.self(),target=Peer.of(PeerKind.Channel,44);
            if(!fill){env.file.seed(PurpleSyncChatModelCases.cache(self,7,50));env.binding=new PurpleSyncChatService.Binding(true,true,self);}
            env.world.add(PurpleSyncChatRequestCases.mark(self,7,50));env.world.add(PurpleSyncChatRequestCases.mark(target,8,60));env.world.records.put(target,RecordPresence.Empty);env.world.records.put(self,RecordPresence.Present);
            byte[] before=env.file.bytes==null?null:env.file.bytes.clone();PurpleSyncChatService service=new PurpleSyncChatService(env);List<PurpleSyncChatDiscovery.Status> statuses=new ArrayList<>();service.checkBeforePost(stream,null,statuses::add);
            must(statuses.size()==1&&statuses.get(0).complete&&statuses.get(0).resolution.action==(fill?Action.Fill:Action.Follow),"actual service resolves complete Follow/Fill "+fill);
            must(statuses.get(0).postingHeld&&statuses.get(0).phase==PurpleSyncChatDiscovery.Phase.Paused&&!new PurpleSyncChat.Status(statuses.get(0)).selfReadable(),"ordinary reads/posts blocked until Carry completes");
            must(Arrays.equals(before,env.file.bytes)&&env.file.writes==0,"apparently empty N never admitted before Carry");service.close(false);
        }
    }
    static void baselineBeforeReservation()throws Exception{
        for(String mode:new String[]{"Follow","Fill"}){
            Carry f=new Carry(mode,false,O,ORIGIN);f.stopCore="Deliver";f.start();
            JSONObject transport=json(Files.readAllBytes(new File(f.files.root,"carry.json").toPath())).getJSONObject("transport");
            must(transport.getJSONObject("readers").has("snapshot")&&transport.getJSONObject("entries").length()==0,"reader consent persisted before send identities: "+mode);
            Carry next=new Carry(f);f.close();next.resume();
            must(next.done()&&next.effects==1,"resume reserves identities after early reader baseline: "+mode+" "+next.reason());next.close();
        }
    }
    static void pausedCarry()throws Exception{
        Flow f=new Flow("Fill");f.carry.stop="Source";f.start("config");Carry next=new Carry(f.carry);f.close();PurpleSyncChatCache.IO cache=PurpleSyncChatCache.file(f.carry.files.cache);
        must(PurpleSyncChatCache.pause(cache,PurpleSyncChatCache.load(cache,ORIGIN,AUTH),ORIGIN,AUTH,true,()->true),"foreground local Pause after stopping Carry");next.resume();
        must(next.reason().equals("LocalPause")&&next.effects==0&&PurpleSyncChatCache.load(cache,ORIGIN,AUTH).paused,"reopened Carry cannot bypass local Pause");next.close();
        must(PurpleSyncChatCache.pause(cache,PurpleSyncChatCache.load(cache,ORIGIN,AUTH),ORIGIN,AUTH,false,()->true),"explicit local Resume before Carry");Carry resumed=new Carry(next);resumed.resume();must(resumed.done(),"Carry continues after explicit local Resume "+resumed.reason());resumed.close();
    }
    static void pause()throws Exception{
        File first=new File(root,"pause-a.json"),second=new File(root,"pause-b.json");PurpleSyncChatCache.IO a=PurpleSyncChatCache.file(first),b=PurpleSyncChatCache.file(second);
        PurpleSyncChatOrigin other=new PurpleSyncChatOrigin(1,1,777,"other-account");
        must(PurpleSyncChatCache.pause(a,PurpleSyncChatCache.load(a,ORIGIN,AUTH),ORIGIN,AUTH,true,()->true),"pause-only absent cache persisted");
        PurpleSyncChatCache.Loaded paused=PurpleSyncChatCache.load(a,ORIGIN,AUTH);must(paused.ok&&paused.paused&&paused.data==null&&!new File(root,"state.json").exists(),"pause never invents location/binding");
        must(!PurpleSyncChatCache.load(b,other,AUTH).paused,"pause stays local to one account/device");
        PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();env.file.bytes=PurpleSyncChatCore.json("version",1,"account",env.origin().accountKey(),"authority",env.authority(),"pauseOnly",true,"paused",true).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PurpleSyncChatService service=new PurpleSyncChatService(env);for(String stream:new String[]{"config","library"}){List<PurpleSyncChatDiscovery.Status> out=new ArrayList<>();service.checkBeforePost(stream,null,out::add);must(out.size()==1&&out.get(0).postingHeld&&out.get(0).reason.equals("LocalPause")&&env.clients==0,"pause blocks every stream before transport: "+stream);}service.close(false);
        PurpleSyncChatCache.Loaded admitted=PurpleSyncChatCache.load(a,ORIGIN,AUTH);byte[] saved=admitted.bytes.clone();Files.write(first.toPath(),bytes("changed"));must(!PurpleSyncChatCache.pause(a,admitted,ORIGIN,AUTH,false,()->true)&&Arrays.equals(a.read(),bytes("changed")),"pause/resume exact-byte CAS refuses concurrent update");PurpleModernSyncCases.write(first,saved);
        must(!PurpleSyncChatCache.pause(a,paused,ORIGIN,AUTH,false,()->false),"stale owner cannot resume");
        must(PurpleSyncChatCache.pause(a,paused,ORIGIN,AUTH,false,()->true)&&a.read()==null,"explicit foreground resume removes pause-only cache");
        android.os.Looper.mainThread=Thread.currentThread();try{must(!PurpleSyncChatCache.pause(a,new PurpleSyncChatCache.Loaded(true,null,null),ORIGIN,AUTH,true,()->true),"pause disk path rejects GUI thread");}finally{android.os.Looper.mainThread=null;}
    }
    public static void main(String[] args)throws Exception{
        rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));
        for(JSONObject row:rows)if(row.getString("op").equals("begin")&&row.getJSONObject("expected").getString("status").equals("Valid")){beginning=row.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        PurpleModernSyncCases.directory(new File(args[1]));PurpleModernSyncCases.Frame bootstrap=new PurpleModernSyncCases.Frame(new File(args[1]),"consent",true);root=bootstrap.root;
        consent();flows();readers();finalDisclosure();lifecycle();restartWarning();missingBaseline();accountFences();serviceAdmission();baselineBeforeReservation();pausedCarry();pause();System.out.println("MOVE_CONSENT_CHECKS="+checks);
    }
}
