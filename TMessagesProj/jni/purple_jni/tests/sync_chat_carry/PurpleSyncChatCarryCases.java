package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.json.*;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_update;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;
import org.telegram.messenger.purple.PurpleSyncChatMoveExecutorCases.CloudClient;
import org.telegram.messenger.purple.PurpleSyncChatMoveExecutorCases.Frame;

/** The production carry dispatcher driven by a real PurpleSyncChatMove, with every Telegram reply injected below the Env seam. */
public final class PurpleSyncChatCarryCases {
    static int checks;
    static void must(boolean value,String reason){++checks;if(!value)throw new AssertionError(reason);}
    static final String MARK=PurpleSyncChatSetupRequests.MARK;
    static TLRPC.TL_message mark(Peer chat,int id,int date){TLRPC.TL_message m=doc(chat,id,date,base);m.media=null;m.flags&=~(1<<9);m.message=MARK;return m;}
    static boolean isMark(TLRPC.Message m){return m.media==null&&MARK.equals(m.message);}
    static Peer chat(TLRPC.InputPeer p){
        return p instanceof TLRPC.TL_inputPeerSelf?Peer.self():p instanceof TLRPC.TL_inputPeerChat?Peer.of(PeerKind.Chat,p.chat_id)
                :p instanceof TLRPC.TL_inputPeerChannel?Peer.of(PeerKind.Channel,p.channel_id):p instanceof TLRPC.TL_inputPeerUser?Peer.of(PeerKind.User,p.user_id):null;
    }
    static TLRPC.TL_error error(String text){TLRPC.TL_error e=new TLRPC.TL_error();e.code=text.startsWith("FLOOD_WAIT_")?420:400;e.text=text;return e;}

    /** One fake account for a Frame: discovery, scans, exact reads and writes all answer from the Frame's records. */
    static class Net implements PurpleSyncChatCarry.Env {
        final Frame f;
        final TLRPC.TL_message oldMark=mark(O,1,1001);
        boolean live=true,oldMarkPresent=true,holdWrites,failTargetInfo,failScans,failExact,holdReads,partialExact,failDownloads,failAfterPeers;
        String writeError,writeErrorKind="";
        PurpleSyncChatCache.Data cache;
        PurpleSyncChatService.Binding binding=new PurpleSyncChatService.Binding(true,true,O);
        final List<TLObject> requests=new ArrayList<>();
        final Map<Integer,Runnable> held=new LinkedHashMap<>();
        final List<Runnable> heldReads=new ArrayList<>();
        final Set<Integer> cancelled=new HashSet<>();
        final Map<Long,byte[]> uploads=new HashMap<>();
        final List<CloudClient> clouds=new ArrayList<>();
        final List<Runnable> timers=new ArrayList<>();
        final List<TLObject> applied=new ArrayList<>();
        final List<TLRPC.TL_error> failures=new ArrayList<>();
        final Map<Integer,Peer> deleted=new HashMap<>();
        TLRPC.TL_message rival;
        boolean rivalAtRead,hideOwn,wrongMarkDate,ownMarkGone;
        Runnable onMark,onRelease;
        Consumer<PurpleSyncChatMove.Command> onDispatch;
        /** Runs onRelease once when called from inside a carry Run's release, to place close or cancel exactly there. */
        void releasing(){
            if(onRelease!=null&&Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(x->x.getClassName().endsWith("PurpleSyncChatCarry$Run")&&x.getMethodName().equals("release"))){Runnable hook=onRelease;onRelease=null;hook.run();}
        }
        final List<TLRPC.Message> extra=new ArrayList<>();
        int token,readerCancels,scans,infos;
        long clock=1000;
        Net(Frame f){this.f=f;cache=new PurpleSyncChatCache.Data(new Cache(O,1,1001),"original",PurpleSyncChatCache.ReadersSnapshot.from(info(O,0,false)),Collections.singleton("config"),new JSONObject(),false);}
        List<TLRPC.Message> all(){List<TLRPC.Message> out=new ArrayList<>(f.oldRecords);out.addAll(f.newRecords);out.addAll(extra);if(oldMarkPresent)out.add(oldMark);return out;}
        List<TLRPC.Message> marks(){List<TLRPC.Message> out=new ArrayList<>();for(TLRPC.Message m:all())if(isMark(m)&&!(hideOwn&&m.id==f.markId&&PurpleSyncChatFacts.token(m.peer_id,777).equals(f.target)))out.add(m);out.sort((a,b)->Integer.compare(b.date,a.date));return out;}
        PurpleSyncChatClient.Info infoOf(Peer p){
            if(p.equals(f.target)){if(failTargetInfo)return null;return f.targetInfo!=null?f.targetInfo:info(p,0,false);}
            return info(p,0,p.equals(O)&&f.access.equals("Protected"));
        }
        long count(Class<?> type){return requests.stream().filter(type::isInstance).count();}
        void drain(){
            int cap=20000;
            while(true){
                Runnable next=null;
                if(!f.tasks.isEmpty())next=f.tasks.removeFirst();
                else for(CloudClient c:clouds)if(!c.queue.isEmpty()){next=c.queue.removeFirst();break;}
                if(next==null)return;
                must(--cap>0,"carry queues are bounded");next.run();
            }
        }
        void fire(long millis){List<Runnable> due=new ArrayList<>(timers);timers.clear();clock+=millis;for(Runnable t:due)t.run();drain();}

        public PurpleSyncChatOrigin origin(){return ORIGIN;}
        public String authority(){return f.authority;}
        public String operationKey(){return f.operationKey();}
        public boolean current(){return live;}
        public void worker(Runnable task){f.tasks.addLast(task);}
        public void quiesce(Runnable done){f.quiesce(done);}
        public PurpleSyncChatMoveStore store()throws Exception{return new PurpleSyncChatMoveStore(f.files.root,f.files.owner(),0,"0-777",f.files.binding,f.authority,f.files.device,f.files.cache,()->live);}
        public PurpleSyncChatCache.Loaded cache(){return new PurpleSyncChatCache.Loaded(true,null,cache);}
        public PurpleSyncChatService.Binding binding(){return binding;}
        public PurpleSyncChatClient reads(BooleanSupplier current){return new Reads(current);}
        public PurpleSyncChatTelegram.Platform platform(BooleanSupplier current){return new Native(current);}
        public PurpleSyncClient scanner(PurpleSyncChatClient.Info chat,BooleanSupplier current){
            ++scans;
            try{
                List<TLRPC.Message> settings=new ArrayList<>();Map<Integer,byte[]> bodies=new HashMap<>(f.oldBodies);bodies.putAll(f.newBodies);
                for(TLRPC.Message m:chat.chat.equals(O)?f.oldRecords:chat.chat.equals(f.target)?f.newRecords:Collections.<TLRPC.Message>emptyList())if(m.media!=null)settings.add(m);
                CloudClient cloud=new CloudClient(chat.chat,failScans?Collections.emptyList():settings,bodies);clouds.add(cloud);
                PurpleSyncChatIO route=cloud.route;
                PurpleSyncClient client=new PurpleSyncChatRecordClient(cloud,m->route.ownership((TLRPC.Message)m.remote));
                return failScans?new Broken(client):failDownloads?new Partial(client):client;
            }catch(Exception e){throw new RuntimeException(e);}
        }
        public int send(TLObject request,PurpleSyncChatTelegram.Reply done){
            requests.add(request);int id=++token;String kind=request.getClass().getSimpleName();
            Runnable answer=()->{
                if(cancelled.contains(id))return;
                if(writeError!=null&&(writeErrorKind.isEmpty()||kind.contains(writeErrorKind))){String text=writeError;writeError=null;f.tasks.addLast(()->done.accept(null,error(text)));return;}
                TLObject response=write(request);f.tasks.addLast(()->done.accept(response,null));
            };
            if(holdWrites)held.put(id,answer);else answer.run();
            return id;
        }
        public int upload(long file,byte[] bytes,Consumer<PurpleSyncAutoUpload.Result> done){
            int id=++token;uploads.put(file,bytes.clone());f.tasks.addLast(()->{if(!cancelled.contains(id))done.accept(new PurpleSyncAutoUpload.Result(1,null));});return id;
        }
        public void cancel(int value){cancelled.add(value);held.remove(value);releasing();}
        public void apply(Peer chat,TLObject response){applied.add(response);}
        public long now(){return clock;}
        public long wall(){return 1700000000000L+clock;}
        public PurpleRpcRetry.Transient cooldown(){return null;}
        public void failed(TLRPC.TL_error value){failures.add(value);}
        public void waiting(Object owner,PurpleRpcRetry.Transient value){}
        public void delay(Runnable task,long millis){timers.add(task);}
        public void cancelDelay(Runnable task){timers.remove(task);}

        /** Applies one write to the Frame's records and returns Telegram's answer. */
        TLObject write(TLObject request){
            if(request instanceof TLRPC.TL_messages_forwardMessages){
                TLRPC.TL_messages_forwardMessages r=(TLRPC.TL_messages_forwardMessages)request;int source=r.id.get(0);
                byte[] body=f.oldBodies.get(source);TLRPC.TL_message m=doc(chat(r.to_peer),55,1500,body);f.newRecords.add(m);f.newBodies.put(55,body);++f.effects;
                return ack(chat(r.to_peer),r.random_id.get(0),m);
            }
            if(request instanceof TLRPC.TL_messages_sendMessage){
                TLRPC.TL_messages_sendMessage r=(TLRPC.TL_messages_sendMessage)request;TLRPC.TL_message m=mark(chat(r.peer),f.markId,2000);f.newRecords.add(m);f.ownWinner=true;++f.effects;if(rival!=null&&!rivalAtRead)extra.add(rival);
                return ack(chat(r.peer),r.random_id,m);
            }
            if(request instanceof TLRPC.TL_messages_sendMedia){
                TLRPC.TL_messages_sendMedia r=(TLRPC.TL_messages_sendMedia)request;Peer to=chat(r.peer);boolean pending=to.equals(O);int id=pending?22:55;
                byte[] body=uploads.get(((TLRPC.TL_inputMediaUploadedDocument)r.media).file.id);TLRPC.TL_message m=doc(to,id,1500,body);
                (pending?f.oldRecords:f.newRecords).add(m);(pending?f.oldBodies:f.newBodies).put(id,body);++f.effects;
                return ack(to,r.random_id,m);
            }
            if(request instanceof TLRPC.TL_messages_deleteMessages||request instanceof TLRPC.TL_channels_deleteMessages){
                ArrayList<Integer> ids=request instanceof TLRPC.TL_messages_deleteMessages?((TLRPC.TL_messages_deleteMessages)request).id:((TLRPC.TL_channels_deleteMessages)request).id;
                int id=ids.get(0);deleted.put(id,O);f.oldRecords.removeIf(m->m.id==id);f.oldBodies.remove(id);if(id==oldMark.id)oldMarkPresent=false;
                TLRPC.TL_messages_affectedMessages a=new TLRPC.TL_messages_affectedMessages();a.pts=10;a.pts_count=1;return a;
            }
            throw new AssertionError("unexpected write "+request.getClass().getSimpleName());
        }
        static TLRPC.Updates ack(Peer to,long random,TLRPC.TL_message m){
            TLRPC.TL_updates result=new TLRPC.TL_updates();TL_update.TL_updateMessageID map=new TL_update.TL_updateMessageID();map.random_id=random;map.id=m.id;result.updates.add(map);
            if(to.kind==PeerKind.Channel){TL_update.TL_updateNewChannelMessage u=new TL_update.TL_updateNewChannelMessage();u.message=m;result.updates.add(u);}
            else{TL_update.TL_updateNewMessage u=new TL_update.TL_updateNewMessage();u.message=m;result.updates.add(u);}
            return result;
        }

        /** Discovery and fresh-info reads, answered from the Frame's marks and records. */
        final class Reads implements PurpleSyncChatClient {
            final BooleanSupplier current;
            Reads(BooleanSupplier current){this.current=current;}
            public PurpleSyncChatOrigin origin(){return ORIGIN;}
            public boolean current(){return live&&current.getAsBoolean();}
            public void dispatch(Runnable task){releasing();f.tasks.addLast(task);}
            void answer(Runnable task){if(holdReads)heldReads.add(task);else f.tasks.addLast(task);}
            public void search(PurpleSyncChatRequests.Search kind,PurpleSyncChatRequests.Cursor cursor,Peer target,Consumer<PurpleSyncChatRequests.Page> done){
                List<TLRPC.Message> hits=new ArrayList<>();
                for(TLRPC.Message m:marks()){Peer at=PurpleSyncChatFacts.token(m.peer_id,777);
                    if(kind==PurpleSyncChatRequests.Search.Global||kind==PurpleSyncChatRequests.Search.Self&&at.kind==PeerKind.Self
                            ||kind==PurpleSyncChatRequests.Search.Broadcasts&&at.kind==PeerKind.Channel||kind==PurpleSyncChatRequests.Search.Dialog&&at.equals(target))hits.add(m);}
                TLRPC.TL_messages_messages page=new TLRPC.TL_messages_messages();page.messages.addAll(cursor.id==0?hits:Collections.emptyList());
                answer(()->done.accept(PurpleSyncChatRequests.page(ORIGIN,page,kind,target)));
            }
            public void info(Peer target,Consumer<Info> done){++infos;Info value=infoOf(target);answer(()->done.accept(value));}
            public void exact(Cache location,Consumer<Exact> done){
                TLRPC.Message found=null;for(TLRPC.Message m:marks())if(m.id==location.markId&&PurpleSyncChatFacts.token(m.peer_id,777).equals(location.chat))found=m;
                Exact value=new Exact(found==null?DirectRead.MarkGone:DirectRead.Valid,found,infoOf(location.chat));answer(()->done.accept(value));
            }
            public void hint(Consumer<SettingsHint> done){answer(()->done.accept(SettingsHint.Absent));}
            public List<Peer> fallback(){return Collections.emptyList();}
            public void cancel(){++readerCancels;}
        }
        /** Exact reads by id; full-info requests fail, which the dispatcher never relies on. */
        final class Native implements PurpleSyncChatTelegram.Platform {
            final BooleanSupplier current;
            Native(BooleanSupplier current){this.current=current;}
            public boolean current(){return live&&current.getAsBoolean();}
            public void dispatch(Runnable task){f.tasks.addLast(task);}
            public int send(TLObject request,PurpleSyncChatTelegram.Reply done){
                requests.add(request);int id=++token;
                if(request instanceof TLRPC.TL_messages_getMessages||request instanceof TLRPC.TL_channels_getMessages){
                    int wanted=request instanceof TLRPC.TL_messages_getMessages?((TLRPC.TL_messages_getMessages)request).id.get(0):((TLRPC.TL_channels_getMessages)request).id.get(0);
                    if(failExact){f.tasks.addLast(()->{if(!cancelled.contains(id))done.accept(null,error("TIMEOUT"));});return id;}
                    TLRPC.Message hit=null;for(TLRPC.Message m:all())if(m.id==wanted)hit=m;
                    if(ownMarkGone&&wanted==f.markId)hit=null;
                    TLRPC.TL_messages_messages page=new TLRPC.TL_messages_messages();if(partialExact&&wanted==f.markId)page.inexact=true;
                    if(hit!=null){if(wrongMarkDate&&wanted==f.markId)hit=mark(f.target,wanted,hit.date+1);page.messages.add(hit);}else{TLRPC.TL_messageEmpty empty=new TLRPC.TL_messageEmpty();empty.id=wanted;Peer gone=deleted.get(wanted);if(gone!=null){empty.flags=1;empty.peer_id=peer(gone);}page.messages.add(empty);}
                    f.tasks.addLast(()->{if(!cancelled.contains(id))done.accept(page,null);});return id;
                }
                f.tasks.addLast(()->{if(!cancelled.contains(id))done.accept(null,error("CHAT_ADMIN_REQUIRED"));});return id;
            }
            public void cancel(int value){cancelled.add(value);}
            public void delay(Runnable task,long millis){timers.add(task);}
            public void cancelDelay(Runnable task){timers.remove(task);}
            public TLRPC.InputPeer input(Peer peer){if(failAfterPeers)failDownloads=true;return PurpleSyncChatMoveIOCases.input(peer);}
            public void peers(List<TLRPC.User> users,List<TLRPC.Chat> chats){}
            public List<Peer> fallback(){return Collections.emptyList();}
        }
    }
    /** A scan client whose history reads fail, so its inventory never completes. */
    static final class Broken implements PurpleSyncClient {
        final PurpleSyncClient inner;
        Broken(PurpleSyncClient inner){this.inner=inner;}
        public long userId(){return inner.userId();}public Peer chat(){return inner.chat();}public PurpleSyncChatOrigin origin(){return inner.origin();}public boolean isActive(){return inner.isActive();}
        public void runOnMain(Runnable r){inner.runOnMain(r);}public void runOnWorker(Runnable r){inner.runOnWorker(r);}public void log(String s){}
        public int requestHistory(int offset,int limit,Reply<History> done){done.onReply(null);return 1;}
        public int requestMessage(int id,Reply<Lookup> done){done.onReply(Lookup.failed("Unknown"));return 2;}
        public void cancelRequest(int id){}public Object download(Message m,Reply<File> done){done.onReply(null);return null;}
        public void cancelDownload(Object token){}public void localCopies(Reply<List<LocalCopy>> done){inner.localCopies(done);}
        public File filesDirectory(){return inner.filesDirectory();}public File cacheDirectory(){return inner.cacheDirectory();}public long nowMillis(){return 1;}
        public void send(File f,String c,String m,Receipt r){throw new AssertionError("no live send");}
    }
    /** A scan client whose history reads succeed but whose record downloads fail, so rows stay unfetched in a complete history. */
    static final class Partial implements PurpleSyncClient {
        final PurpleSyncClient inner;
        Partial(PurpleSyncClient inner){this.inner=inner;}
        public long userId(){return inner.userId();}public Peer chat(){return inner.chat();}public PurpleSyncChatOrigin origin(){return inner.origin();}public boolean isActive(){return inner.isActive();}
        public void runOnMain(Runnable r){inner.runOnMain(r);}public void runOnWorker(Runnable r){inner.runOnWorker(r);}public void log(String s){}
        public int requestHistory(int offset,int limit,Reply<History> done){return inner.requestHistory(offset,limit,done);}
        public int requestMessage(int id,Reply<Lookup> done){return inner.requestMessage(id,done);}
        public void cancelRequest(int id){}public Object download(Message m,Reply<File> done){done.onReply(null);return null;}
        public void cancelDownload(Object token){}public void localCopies(Reply<List<LocalCopy>> done){inner.localCopies(done);}
        public File filesDirectory(){return inner.filesDirectory();}public File cacheDirectory(){return inner.cacheDirectory();}public long nowMillis(){return 1;}
        public void send(File f,String c,String m,Receipt r){throw new AssertionError("no live send");}
    }
    /** A Frame whose Port is the real carry dispatcher over a Net. */
    static final class Run {
        final Frame f;
        final Net net;
        final PurpleSyncChatCarry carry;
        final List<PurpleSyncChatMove.Command> commands=new ArrayList<>();
        final List<String> answeredActions=new ArrayList<>();
        final List<PurpleSyncChatMove.Reply> answers=new ArrayList<>();
        boolean fenced,delivering;
        int late;
        PurpleSyncChatMove.Command cancelled;
        BiConsumer<PurpleSyncChatMove.Command,PurpleSyncChatMove.Reply> inDone;
        Consumer<PurpleSyncChatMove.Status> inStatus;
        Run(Frame f)throws Exception {this(f,null);}
        Run(Frame f,Net shared)throws Exception {
            this.f=f;net=shared!=null?shared:new Net(f);
            PurpleSyncRetainedFiles.pin(f.files.root,base);
            carry=new PurpleSyncChatCarry(net,()->f.live,()->f.foreground,s->{f.status(s);if(inStatus!=null)inStatus.accept(s);});
        }
        PurpleSyncChatMove.Port port(){
            return new PurpleSyncChatMove.Port(){
                public PurpleSyncChatOrigin origin(){return carry.origin();}public String authority(){return carry.authority();}public String operationKey(){return carry.operationKey();}
                public boolean current(){return carry.current();}public boolean foreground(){return carry.foreground();}public void worker(Runnable t){carry.worker(t);}public void quiesce(Runnable d){carry.quiesce(d);}
                public PurpleSyncChatMoveStore open(BooleanSupplier c,boolean i)throws Exception{f.opened=carry.open(c,i);return f.opened;}
                public void dispatch(PurpleSyncChatMove.Command c,Consumer<PurpleSyncChatMove.Reply> d){commands.add(c);if(net.onDispatch!=null)net.onDispatch.accept(c);if(c.action.equals("Mark")&&net.onMark!=null){Runnable h=net.onMark;net.onMark=null;h.run();}if(net.rival!=null&&net.rivalAtRead&&c.action.equals("Mark")&&!net.extra.contains(net.rival))net.extra.add(net.rival);
                    carry.dispatch(c,reply->{
                        if(fenced&&(cancelled==null||cancelled==c))++late;answeredActions.add(c.action);answers.add(reply);
                        delivering=true;try{if(inDone!=null)inDone.accept(c,reply);d.accept(reply);}finally{delivering=false;}
                    });}
                public void cancel(PurpleSyncChatMove.Command c){carry.cancel(c);}public void status(PurpleSyncChatMove.Status s){carry.status(s);}
            };
        }
        boolean cleanupSettings;
        Run start()throws Exception {f.move=new PurpleSyncChatMove(port());f.move.start(f.begin().put("cleanupSettings",cleanupSettings),info(f.target,0,false));net.drain();return this;}
        Run resume()throws Exception {f.move=new PurpleSyncChatMove(port());f.move.resume(info(f.target,0,false));net.drain();return this;}
        String trace(){StringBuilder b=new StringBuilder();for(PurpleSyncChatMove.Command c:commands)b.append(c.action).append(c.permit!=null?":"+c.permit.kind:"").append(' ');b.append('|');for(TLObject t:net.requests)b.append(t.getClass().getSimpleName()).append(' ');b.append('|');for(PurpleSyncChatMove.Status s:f.statuses)b.append(s.action).append('/').append(s.reason).append(' ');return b.toString();}
        boolean issued(String action){return commands.stream().anyMatch(c->c.action.equals(action));}
        boolean answered(String action){return answeredActions.contains(action);}
        PurpleSyncChatMove.Reply answer(String action){for(int i=answeredActions.size()-1;i>=0;--i)if(answeredActions.get(i).equals(action))return answers.get(i);return null;}
        void close(){carry.close();if(f.move!=null)f.move.close();net.drain();}
    }

    static void happy()throws Exception {
        for(boolean own:new boolean[]{false,true}){
            Run r=new Run(new Frame(own)).start();
            must(r.f.done(),"carry completes a core-driven move "+own+" "+r.f.reason()+" "+r.trace());
            must(r.f.diskJournal().getBoolean("state_committed")&&r.f.diskJournal().getBoolean("cache_completed"),"state and cache durable "+own);
            for(String action:new String[]{"Fresh","Source","Effect","Destination","Mark"})must(r.issued(action),"real move issued "+action+" "+own);
            must(r.net.count(TLRPC.TL_messages_sendMessage.class)==1,"one mark write "+own);
            must(r.net.count(own?TLRPC.TL_messages_sendMedia.class:TLRPC.TL_messages_forwardMessages.class)==1,"one record write "+own);
            must(r.net.count(own?TLRPC.TL_messages_forwardMessages.class:TLRPC.TL_messages_sendMedia.class)==0,"no other record write "+own);
            must(own==!r.net.uploads.isEmpty(),"own record uploads its sealed bytes once "+own);
            if(own)must(Arrays.equals(r.net.uploads.values().iterator().next(),r.f.newBodies.get(55)),"own upload carries the permit bytes");
            must(r.net.applied.size()==2,"each write's updates reach the app "+own);
            must(r.net.count(TLRPC.TL_messages_getMessages.class)>=1,"mark verified by an exact read "+own);
            must(!PurpleSyncOperations.carryHeld(r.f.operationKey()),"terminal releases the carry lease "+own);
            r.close();
        }
    }
    static void pending()throws Exception {
        Run r=new Run(new Frame(false,true)).start();
        must(r.f.done(),"readable pending finishes through the carry "+r.f.reason());must(r.issued("Pending"),"pending read issued");
        must(r.commands.stream().anyMatch(c->c.permit!=null&&c.permit.kind.equals("pending")),"pending permit dispatched");
        must(r.f.oldRecords.stream().anyMatch(m->m.id==22),"pending record written into the old chat through the upload path");r.close();
        Run held=new Run(new Frame(true,true)).start();
        must(held.f.oldRecords.stream().noneMatch(m->m.id==22)&&held.net.requests.stream().noneMatch(t->t instanceof TLRPC.TL_messages_sendMedia&&O.equals(chat(((TLRPC.TL_messages_sendMedia)t).peer))),"protected pending never posts into the old chat "+held.f.reason());held.close();
    }
    static void cleanup()throws Exception {
        Frame f=new Frame(false);f.removeOld=true;Run r=new Run(f);r.cleanupSettings=true;r.start();
        must(r.issued("Cleanup")&&r.issued("CleanupRead")&&r.issued("CleanupDelete"),"cleanup reads and deletes through the carry "+f.reason()+" "+r.commands.stream().map(c->c.action).collect(java.util.stream.Collectors.toList()));
        must(f.done(),"cleanup completes "+f.reason());
        must(r.net.count(TLRPC.TL_messages_deleteMessages.class)>=1&&!r.net.oldMarkPresent,"old mark deleted by exact id");
        must(f.oldRecords.stream().noneMatch(m->m.id==2),"copied old record deleted "+r.trace());r.close();
    }
    static void unknownReplies()throws Exception {
        Run info=new Run(new Frame(false));info.net.failTargetInfo=true;info.start();
        must(!info.f.done()&&info.f.reason().equals("OutcomeUnknown")&&info.net.count(TLRPC.TL_messages_forwardMessages.class)==0,"missing fresh info is Unknown, not a write "+info.f.reason());info.close();
        Run lost=new Run(new Frame(false));lost.net.failScans=true;lost.start();
        must(!lost.f.done()&&lost.net.count(TLRPC.TL_messages_forwardMessages.class)==0,"incomplete inventory never writes "+lost.f.reason());lost.close();
        Run exact=new Run(new Frame(false));exact.net.failExact=true;exact.start();
        must(!exact.f.done()&&exact.f.reason().equals("OutcomeUnknown")&&exact.net.count(TLRPC.TL_messages_sendMessage.class)==1,"failed mark read-back holds Unknown after the one mark write "+exact.f.reason());exact.close();
    }
    static void partialWrites()throws Exception {
        Run refused=new Run(new Frame(false));refused.net.writeError="CHAT_WRITE_FORBIDDEN";refused.net.writeErrorKind="forward";refused.start();
        must(!refused.f.done()&&refused.net.count(TLRPC.TL_messages_forwardMessages.class)==1,"refused forward is sent once and never retried blindly "+refused.f.reason());
        must(refused.net.failures.size()==1,"write failure reaches the shared write gate");refused.close();
        Run own=new Run(new Frame(true));own.net.writeError="MEDIA_EMPTY";own.net.writeErrorKind="sendMedia";own.start();
        must(!own.f.done()&&own.net.count(TLRPC.TL_messages_sendMedia.class)==1,"refused own upload send holds "+own.f.reason());own.close();
        for(boolean ownWrite:new boolean[]{false,true}){
            Run held=new Run(new Frame(ownWrite));held.net.holdWrites=true;held.start();
            must(held.net.held.size()==1&&!held.f.done(),"write in flight "+ownWrite);int before=held.f.statuses.size();
            held.carry.close();held.net.drain();must(held.net.held.isEmpty()&&held.net.cancelled.size()>=1,"close cancels the in-flight write "+ownWrite);
            must(held.f.statuses.size()==before,"no reply after close "+ownWrite);held.close();
        }
    }
    static void floodRetry()throws Exception {
        for(boolean own:new boolean[]{false,true}){
            Run r=new Run(new Frame(own));r.net.writeError="FLOOD_WAIT_3";r.net.writeErrorKind=own?"sendMedia":"forward";r.start();
            Class<?> type=own?TLRPC.TL_messages_sendMedia.class:TLRPC.TL_messages_forwardMessages.class;
            must(!r.f.done()&&r.net.timers.size()==1&&r.net.count(type)==1,"flood wait schedules one retry "+own+" "+r.f.reason());
            r.net.fire(4000);
            must(r.f.done(),"retry after flood completes "+own+" "+r.f.reason());
            must(r.net.count(type)==2,"exactly one resend "+own);must(r.net.uploads.size()==(own?1:0),"the resend reuses the one upload "+own);
            r.close();
        }
    }
    static void lifecycle()throws Exception {
        Frame f=new Frame(false);Run r=new Run(f);
        must(r.carry.origin().equals(ORIGIN)&&r.carry.authority().equals(f.authority)&&r.carry.operationKey().equals(f.operationKey()),"port identity from the Env");
        f.foreground=false;must(!r.carry.foreground(),"foreground from the factory supplier");f.foreground=true;
        r.net.live=false;must(!r.carry.current(),"account loss ends current");
        try{r.carry.open(()->true,false);must(false,"open refused");}catch(java.io.IOException e){must(e.getMessage().equals("AccountUnavailable"),"open refuses a lost account");}
        r.net.live=true;f.live=false;must(!r.carry.current(),"factory current ends current");f.live=true;
        PurpleSyncChatMoveStore store=r.carry.open(()->true,false);must(store!=null,"open returns an opened store");store.close();
        try{r.carry.open(()->false,false);must(false,"stale open refused");}catch(java.io.IOException e){must(e.getMessage().equals("AccountUnavailable"),"open refuses a stale opener");}
        int[] seen={0};PurpleSyncChatCarry quiet=new PurpleSyncChatCarry(r.net,()->true,()->true,null);quiet.status(null);
        r.carry.close();r.carry.close();must(!r.carry.current(),"close ends current and is idempotent");
        PurpleSyncChatCarry other=new PurpleSyncChatCarry(r.net,()->true,()->true,s->++seen[0]);other.freshFrame("config",frame->++seen[0]);other.close();r.net.drain();
        must(seen[0]==0,"no callback after close");
        Run late=new Run(new Frame(false));late.net.holdWrites=true;late.start();PurpleSyncChatMove.Command effect=late.commands.get(late.commands.size()-1);
        must(effect.action.equals("Effect"),"effect in flight");late.carry.cancel(effect);must(late.net.held.isEmpty(),"cancel(command) cancels its write");late.close();
    }
    static void frames()throws Exception {
        Frame f=new Frame(false);Net net=new Net(f);PurpleSyncChatCarry carry=new PurpleSyncChatCarry(net,()->true,()->true,null);
        List<PurpleSyncChatScreen.Frame> out=new ArrayList<>();carry.freshFrame("config",out::add);net.drain();
        must(out.size()==1&&out.get(0)!=null,"fresh frame delivered");PurpleSyncChatScreen.Frame frame=out.get(0);
        must(frame.status.complete,"full discovery completes");must(O.equals(frame.status.resolution.readChat),"frame resolves the cached chat");
        PurpleSyncChatCache.Data pendingData=new PurpleSyncChatCache.Data(net.cache.location,"old",net.cache.readers,net.cache.warned,json("pending",true),false);net.cache=pendingData;
        out.clear();carry.freshFrame("config",out::add);net.drain();
        must(out.size()==1&&out.get(0)!=null&&out.get(0).status.complete,"pending journal does not pause the read-only pass");
        must(out.get(0).opening.data==pendingData,"frame keeps the opening cache exactly as read");
        net.failTargetInfo=false;net.live=false;out.clear();carry.freshFrame("config",out::add);net.drain();must(out.size()==1&&out.get(0)==null,"lost account yields null");net.live=true;
        out.clear();carry.freshFrame("other",out::add);net.drain();must(out.size()==1&&out.get(0)==null,"unknown stream yields null");
        net.holdReads=true;out.clear();carry.freshFrame("config",out::add);net.drain();carry.close();for(Runnable t:net.heldReads)t.run();net.drain();
        must(out.isEmpty()&&net.readerCancels>0,"close cancels a pass and suppresses its answer");
    }
    /** D-M2B5-13: a live own mark that a fresh resolution shows lost to another winner never commits or deletes in the old chat. */
    static void losingOwnMark()throws Exception {
        for(String place:new String[]{"same-chat","other-chat","older-control"})for(boolean atRead:new boolean[]{true,false}){
            Frame f=new Frame(false);f.removeOld=true;Run r=new Run(f);r.cleanupSettings=true;String name=place+(atRead?" at ReadMark":" after the mark write");
            Peer at=place.equals("other-chat")?Peer.of(PeerKind.Channel,999):f.target;
            r.net.rival=mark(at,91,place.equals("older-control")?1500:2500);r.net.rivalAtRead=atRead;r.start();
            must(r.net.count(TLRPC.TL_messages_sendMessage.class)==1&&f.newRecords.stream().anyMatch(m->m.id==f.markId&&isMark(m)),"own mark 90 posted "+name);
            if(atRead)must(r.issued("Mark")&&(place.equals("older-control")||r.commands.get(r.commands.size()-1).action.equals("Mark")),"uninterrupted ReadMark is the deciding read "+name);
            if(place.equals("older-control")){must(f.done(),"an older rival leaves the own mark the winner "+name+" "+f.reason());r.close();continue;}
            must(!f.done()&&f.reason().equals("ResolutionChanged"),"fresh rival winner holds the move "+name+" "+f.reason()+" "+r.trace());
            must(!f.diskJournal().optBoolean("state_committed")&&!f.diskJournal().optBoolean("cache_completed"),"no commitment "+name);
            must(r.net.count(TLRPC.TL_messages_deleteMessages.class)==0&&f.oldRecords.stream().anyMatch(m->m.id==2)&&r.net.oldMarkPresent,"no old-chat deletion "+name);
            r.close();
        }
    }
    /** D-M2B5-13: MarkRead needs a fresh winner equal to the read-back mark's chat, id and date; the opening winner cannot stand in. */
    static void unprovenOwnMark()throws Exception {
        for(String scenario:new String[]{"opening winner","date mismatch"}){
            Frame f=new Frame(false);f.removeOld=true;Run r=new Run(f);r.cleanupSettings=true;
            r.net.onMark=()->{if(scenario.equals("opening winner"))r.net.hideOwn=true;else r.net.wrongMarkDate=true;};r.start();
            must(r.issued("Mark")&&r.commands.get(r.commands.size()-1).action.equals("Mark"),"uninterrupted ReadMark is the deciding read "+scenario);
            must(!f.done()&&f.reason().equals("ResolutionChanged"),"unproven own mark holds "+scenario+" "+f.reason());
            must(!f.diskJournal().optBoolean("state_committed")&&!f.diskJournal().optBoolean("cache_completed"),"no commitment "+scenario);
            must(r.net.count(TLRPC.TL_messages_deleteMessages.class)==0&&f.oldRecords.stream().anyMatch(m->m.id==2)&&r.net.oldMarkPresent,"no old-chat deletion "+scenario);
            r.net.hideOwn=false;r.net.wrongMarkDate=false;f.move.retry();r.net.drain();
            must(f.done(),"a retry completes once the own mark is the proven winner "+scenario+" "+f.reason());r.close();
        }
    }
    /** One lifecycle: close or cancel from inside a run's own release suppresses its hand-over, so nothing is delivered after either returns. */
    static void fences()throws Exception {
        for(String how:new String[]{"close","cancel"}){
            Frame f=new Frame(false);Run r=new Run(f);
            r.net.onDispatch=c->{if(c.action.equals("Effect")&&r.net.onRelease==null&&!r.fenced)r.net.onRelease=()->{if(how.equals("close"))r.carry.close();else r.carry.cancel(c);r.fenced=true;};};r.start();
            must(r.fenced&&r.late==0,"no reply after "+how+" returns from a release hook "+r.late+" "+r.trace());
            must(!f.done(),"the fenced move does not advance "+how+" "+f.reason());r.close();
        }
        Frame f=new Frame(false);Net net=new Net(f);PurpleSyncChatCarry carry=new PurpleSyncChatCarry(net,()->true,()->true,null);
        List<PurpleSyncChatScreen.Frame> out=new ArrayList<>();boolean[] fenced={false};
        net.onRelease=()->{carry.close();fenced[0]=true;};carry.freshFrame("config",out::add);net.drain();
        must(fenced[0]&&out.isEmpty(),"no frame after close returns from a pass's release hook");
        // A5M closes from its controllers' cancel(), inside a done or status callback on the worker. A done callback runs inside the
        // hand-over, so the wait must be reentrant there; the controller posts its own work, so status follows the hand-over.
        for(String place:new String[]{"done","status"})for(String how:new String[]{"close","cancel"}){
            String name=how+" inside "+place;boolean[] inside={false},returned={false};Run[] run={null};Throwable[] failure={null};
            Thread t=new Thread(()->{try{
                Run r=new Run(new Frame(false));run[0]=r;
                Runnable hook=()->{
                    if(inside[0]||(place.equals("done")?!r.delivering:r.answers.isEmpty()))return;inside[0]=true;
                    if(how.equals("close"))r.carry.close();else{r.cancelled=r.commands.get(r.commands.size()-1);r.carry.cancel(r.cancelled);}
                    r.fenced=true;returned[0]=true;
                };
                if(place.equals("done"))r.inDone=(c,reply)->{if(c.action.equals("Source"))hook.run();};else r.inStatus=s->hook.run();
                r.start();
            }catch(Throwable e){failure[0]=e;}});
            t.setDaemon(true);t.start();t.join(20000);
            must(!t.isAlive(),"close and cancel inside a delivery return promptly "+name);
            if(failure[0]!=null)throw new AssertionError(name,failure[0]);
            Run r=run[0];
            must(inside[0]&&returned[0]&&r.late==0,"nothing is delivered after "+name+" returns "+r.late+" "+r.trace());
            if(how.equals("cancel")&&place.equals("done"))must(r.f.done(),"cancelling the command being delivered leaves the carry working "+r.f.reason());
            r.close();
        }
        Frame g=new Frame(false);Net gnet=new Net(g);PurpleSyncChatCarry passCarry=new PurpleSyncChatCarry(gnet,()->true,()->true,null);
        List<PurpleSyncChatScreen.Frame> frames=new ArrayList<>();boolean[] frameReturned={false};
        Thread t=new Thread(()->{passCarry.freshFrame("config",frame->{frames.add(frame);passCarry.close();frameReturned[0]=true;});gnet.drain();passCarry.freshFrame("config",frames::add);gnet.drain();});
        t.setDaemon(true);t.start();t.join(20000);
        must(!t.isAlive()&&frameReturned[0]&&frames.size()==1,"close inside a frame callback returns promptly and nothing follows");
    }
    /** A failed or partial read delivers the null Unknown reply; an affirmative exact absence stays a real response. */
    static void failedReads()throws Exception {
        for(String action:new String[]{"Source","Destination","Mark","CleanupRead"}){
            Frame f=new Frame(false);f.removeOld=true;Run r=new Run(f);r.cleanupSettings=true;
            r.net.onDispatch=c->{if(c.action.equals(action)){r.net.onDispatch=null;if(action.equals("Mark"))r.net.failExact=true;else r.net.failScans=true;}};r.start();
            must(r.answered(action)&&r.answer(action)==null,"a failed read in "+action+" replies null "+r.trace());
            must(!f.done()&&f.reason().equals("OutcomeUnknown"),"a null read reply holds Unknown "+action+" "+f.reason());
            must(r.net.count(TLRPC.TL_messages_deleteMessages.class)==0&&f.oldRecords.stream().anyMatch(m->m.id==2),"no old-chat deletion after a failed read "+action);r.close();
        }
        Frame f=new Frame(false);Run r=new Run(f);
        r.net.onDispatch=c->{if(c.action.equals("Mark")){r.net.onDispatch=null;r.net.hideOwn=true;r.net.ownMarkGone=true;r.net.deleted.put(f.markId,f.target);}};r.start();
        PurpleSyncChatMove.Reply absent=r.answer("Mark");
        must(absent!=null&&absent.response instanceof TLRPC.messages_Messages&&((TLRPC.messages_Messages)absent.response).messages.get(0) instanceof TLRPC.TL_messageEmpty,"an absent own mark is a response, not the null reply");
        must(!f.done()&&!f.reason().equals("OutcomeUnknown")&&!f.diskJournal().optBoolean("state_committed"),"an absent own mark holds without commitment "+f.reason());r.close();
    }
    /** Partial non-null reads are Unknown too: an inexact or unscoped exact page, and a complete history whose candidate rows failed to download. */
    static void partialReads()throws Exception {
        for(String scenario:new String[]{"inexact Mark","unscoped Mark","partial Source scan"}){
            String action=scenario.endsWith("scan")?"Source":"Mark";
            Frame f=new Frame(false);f.removeOld=true;Run r=new Run(f);r.cleanupSettings=true;
            r.net.onDispatch=c->{
                if(!c.action.equals(action))return;r.net.onDispatch=null;
                if(scenario.startsWith("inexact"))r.net.partialExact=true;else if(scenario.startsWith("unscoped")){r.net.hideOwn=true;r.net.ownMarkGone=true;}else r.net.failAfterPeers=true;
            };r.start();
            must(r.answered(action)&&r.answer(action)==null,"a partial read replies null "+scenario+" "+r.trace());
            must(!f.done()&&f.reason().equals("OutcomeUnknown")&&!f.diskJournal().optBoolean("state_committed"),"a partial read holds Unknown without commitment "+scenario+" "+f.reason());
            must(r.net.count(TLRPC.TL_messages_deleteMessages.class)==0&&f.oldRecords.stream().anyMatch(m->m.id==2)
                    &&(!action.equals("Source")||r.net.count(TLRPC.TL_messages_forwardMessages.class)==0),"no write after a partial read "+scenario);r.close();
        }
    }
    /** Production's env view, read at every dispatch: the binding from state.json, as Holder.env.binding derives it (with the fixture's binding token in place of the app preference), and the admitted cache from its file. */
    static void diskEnv(Run r,Consumer<PurpleSyncChatMove.Command> also){
        r.net.onDispatch=c->{
            try{r.net.binding=diskBinding(r.f);r.net.cache=diskCache(r.f);}catch(Exception e){throw new RuntimeException(e);}
            if(also!=null)also.accept(c);
        };
    }
    static PurpleSyncChatService.Binding diskBinding(Frame f)throws Exception {
        File file=new File(f.files.root,"state.json");
        if(!file.exists())return new PurpleSyncChatService.Binding(true,false,Peer.self(),"","missing");
        byte[] bytes=Files.readAllBytes(file.toPath());JSONObject value=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        Peer chat=Peer.parse(value.optString("chat","self"),777);
        String identity=value.getString("install")+":"+value.getString("space")+":"+value.optString("binding_token","")+":"+value.optInt("version");
        return new PurpleSyncChatService.Binding(chat!=null,"Bound".equals(PurpleAccountSyncCore.checkAccountBinding(bytes,f.files.binding).verdict),chat!=null?chat:Peer.self(),identity,PurpleSyncRetainedFiles.hash(bytes));
    }
    static PurpleSyncChatCache.Data diskCache(Frame f){
        PurpleSyncChatCache.Loaded loaded=PurpleSyncChatCache.load(new PurpleSyncChatCache.IO(){
            public byte[] read()throws Exception{return f.files.cache.exists()?Files.readAllBytes(f.files.cache.toPath()):null;}
            public boolean write(byte[] a,byte[] b){return false;}public boolean remove(byte[] a){return false;}
        },ORIGIN,f.authority);
        must(loaded.ok&&!loaded.paused,"the admitted cache loads from disk");return loaded.data;
    }
    static JSONObject journal(PurpleSyncChatMove.Command c)throws Exception{byte[] j=c.journal();return j==null?null:new JSONObject(new String(j,java.nio.charset.StandardCharsets.UTF_8));}
    static boolean inWindow(PurpleSyncChatMove.Command c)throws Exception{JSONObject j=journal(c);return j!=null&&j.optBoolean("state_committed")&&!j.optBoolean("cache_completed");}
    /** A Run whose env reads state.json and the admitted cache from disk, starting from an admitted cache at O (none for Fill; for RepairMark its mark is gone). */
    static Run diskRun(String mode,Consumer<PurpleSyncChatMove.Command> also)throws Exception {
        Frame f=new Frame(false,false,mode);Run r=new Run(f);r.net.oldMarkPresent=!mode.equals("RepairMark");
        if(!mode.equals("Fill"))PurpleModernSyncCases.write(f.files.cache,PurpleSyncChatCache.encode(ORIGIN,f.authority,r.net.cache));
        r.net.binding=diskBinding(f);r.net.cache=diskCache(f);diskEnv(r,also);return r;
    }
    /**
     * The window between CommitState and CompleteCache, with the env read from disk. A cross-chat move's Fresh there sees state.json
     * already bound to the target while the admitted cache still names the source; Follow and an ordinary move must still get the
     * real resolution, complete the cache and reach Done. Fill (no state, no cache) and a same-chat RepairMark never see that
     * mismatch. A move stopped in the window resumes to Done. A mismatch the command's journal does not own (a freshFrame, a foreign
     * state stamp, a third chat) still pauses.
     */
    static void committedWindow()throws Exception {
        for(String mode:new String[]{"Switch","Follow","Fill","RepairMark"}){
            List<PurpleSyncChatMove.Command> window=new ArrayList<>();List<PurpleSyncChatService.Binding> bound=new ArrayList<>();List<PurpleSyncChatCache.Data> cached=new ArrayList<>();
            List<String> frames=new ArrayList<>();Run[] run=new Run[1];
            run[0]=diskRun(mode,c->{
                try{if(!c.action.equals("Fresh")||!inWindow(c))return;}catch(Exception e){throw new RuntimeException(e);}
                window.add(c);bound.add(run[0].net.binding);cached.add(run[0].net.cache);
                if(window.size()==1)run[0].carry.freshFrame("config",frame->frames.add(frame==null?"null":frame.status.reason));
            });
            Run r=run[0];Frame f=r.f;List<PurpleSyncChatMove.Reply> replies=new ArrayList<>();r.inDone=(c,reply)->{if(window.contains(c))replies.add(reply);};
            r.start();
            must(f.done(),"the move reaches Done through the committed window "+mode+" "+f.reason()+" "+r.trace());
            must(!window.isEmpty()&&replies.size()==window.size()&&replies.stream().allMatch(x->x!=null&&x.fresh!=null&&x.fresh.fullResolution),"Fresh in the window returns the real resolution "+mode+" "+window.size()+"/"+replies.size());
            for(PurpleSyncChatMove.Reply x:replies){Resolution res=resolve(x.fresh.resolution);must(res.winner!=null&&res.winner.chat.equals(f.target),"the window resolution names the own target mark "+mode);}
            if(mode.equals("Switch")||mode.equals("Follow")){
                must(bound.stream().allMatch(b->b.bound&&b.chat.equals(f.target))&&cached.stream().allMatch(d->d!=null&&d.location.chat.equals(O))&&!f.target.equals(O),"the case sat in the cross-chat window "+mode+" "+bound.stream().map(b->b.bound+":"+b.chat.token()+":"+b.stateStamp.length()).collect(java.util.stream.Collectors.toList())+" "+cached.stream().map(d->d==null?"none":d.location.chat.token()).collect(java.util.stream.Collectors.toList())+" target "+f.target.token());
                must(frames.equals(Collections.singletonList("BoundChatMismatch")),"a freshFrame in the same window owns no journal and still pauses "+mode+" "+frames);
            }else if(mode.equals("Fill"))must(bound.stream().allMatch(b->!b.bound)&&cached.stream().allMatch(d->d==null),"Fill has no state binding and no cache in the window");
            else must(f.target.equals(O)&&bound.stream().allMatch(b->b.bound&&b.chat.equals(O))&&cached.stream().allMatch(d->d!=null&&d.location.chat.equals(O)),"same-chat RepairMark has no mismatch in the window");
            JSONObject j=f.diskJournal();must(j.getBoolean("state_committed")&&j.getBoolean("cache_completed"),"CompleteCache ran "+mode);
            must(diskCache(f).location.chat.equals(f.target)&&diskBinding(f).chat.equals(mode.equals("Fill")?Peer.self():f.target),"state and admitted cache agree on the target after Done "+mode);r.close();
        }
        Run[] run=new Run[1];
        run[0]=diskRun("Follow",c->{try{if(c.action.equals("Fresh")&&inWindow(c)){PurpleSyncChatService.Binding b=run[0].net.binding;run[0].net.binding=new PurpleSyncChatService.Binding(true,true,b.chat,b.identity,"foreign"+b.stateStamp);}}catch(Exception e){throw new RuntimeException(e);}});
        Run foreign=run[0];foreign.start();
        must(!foreign.f.done()&&foreign.f.reason().equals("OutcomeUnknown")&&foreign.f.diskJournal().getBoolean("state_committed")&&!foreign.f.diskJournal().getBoolean("cache_completed"),
                "a target binding whose state is not the journal's next state still pauses and holds Unknown "+foreign.f.reason());
        must(diskCache(foreign.f).location.chat.equals(O),"the cache stays at the source while held");foreign.close();
        Run[] stopped=new Run[1];
        stopped[0]=diskRun("Switch",c->{try{if(c.action.equals("Fresh")&&inWindow(c))stopped[0].f.live=false;}catch(Exception e){throw new RuntimeException(e);}});
        stopped[0].start();Frame cut=stopped[0].f;
        must(!cut.done()&&cut.diskJournal().getBoolean("state_committed")&&!cut.diskJournal().getBoolean("cache_completed"),"a move stopped in the window keeps state committed and the cache pending "+cut.reason());stopped[0].close();
        Frame again=new Frame(cut);Run restart=new Run(again);restart.net.binding=diskBinding(again);restart.net.cache=diskCache(again);diskEnv(restart,null);restart.resume();
        must(again.done()&&again.diskJournal().getBoolean("cache_completed")&&diskCache(again).location.chat.equals(again.target),"resume inside the window completes the cache and reaches Done "+again.reason()+" "+restart.trace());restart.close();
        Peer third=Peer.of(PeerKind.Channel,4242);
        Run stranger=new Run(new Frame(false));stranger.net.binding=new PurpleSyncChatService.Binding(true,true,third,"stranger","stranger");stranger.start();
        must(!stranger.f.done()&&!(new File(stranger.f.files.root,"carry.json").exists()&&stranger.f.diskJournal().optBoolean("state_committed"))&&stranger.net.count(TLRPC.TL_messages_sendMessage.class)==0&&stranger.net.count(TLRPC.TL_messages_forwardMessages.class)==0,
                "a state bound to a third chat holds before any write "+stranger.f.reason());stranger.close();
        Run idle=new Run(new Frame(false));idle.net.binding=new PurpleSyncChatService.Binding(true,true,idle.f.target,"idle","idle");List<String> reasons=new ArrayList<>();
        idle.carry.freshFrame("config",frame->reasons.add(frame==null?"null":frame.status.reason));idle.net.drain();
        must(reasons.equals(Collections.singletonList("BoundChatMismatch")),"a freshFrame with state bound away from the cache pauses "+reasons);idle.close();
    }
    /**
     * The window's winner is pinned to the journal's frozen target_mark. One change on the first committed, not completed
     * Fresh: the own mark left out of discovery, the own mark removed, its date changed, or a newer rival in the target.
     * Switch and Follow both hold ResolutionChanged before CompleteCache, keep the admitted cache at the source, send no
     * second mark and delete nothing.
     */
    static void windowWinner()throws Exception {
        for(String mode:new String[]{"Switch","Follow"})for(String change:new String[]{"excluded","removed","date","rival"}){
            Run[] run=new Run[1];boolean[] changed={false};
            run[0]=diskRun(mode,c->{
                try{if(changed[0]||!c.action.equals("Fresh")||!inWindow(c))return;}catch(Exception e){throw new RuntimeException(e);}
                changed[0]=true;Frame f=run[0].f;
                if(change.equals("excluded"))run[0].net.hideOwn=true;
                else if(change.equals("removed"))f.newRecords.removeIf(m->isMark(m)&&m.id==f.markId);
                else if(change.equals("date")){for(TLRPC.Message m:f.newRecords)if(isMark(m)&&m.id==f.markId)m.date++;}
                else run[0].net.extra.add(mark(f.target,91,2500));
            });
            Run r=run[0];Frame f=r.f;if(mode.equals("Switch")){f.removeOld=true;r.cleanupSettings=true;}
            r.start();JSONObject j=f.diskJournal();
            must(changed[0],"the change landed in the committed window "+mode+" "+change);
            must(!f.done()&&f.reason().equals("ResolutionChanged")&&j.getBoolean("state_committed")&&!j.getBoolean("cache_completed"),
                    "a window winner other than the frozen target mark holds before CompleteCache "+mode+" "+change+" "+f.reason()+" "+r.trace());
            Cache admitted=diskCache(f).location;
            must(admitted.chat.equals(O)&&admitted.markId==1&&admitted.markDate==1001,"the admitted cache still names the source mark "+mode+" "+change);
            must(r.net.count(TLRPC.TL_messages_sendMessage.class)==(mode.equals("Switch")?1:0)&&r.net.deleted.isEmpty()&&f.oldRecords.stream().anyMatch(m->m.id==2),
                    "no second mark and no deletion "+mode+" "+change);r.close();
        }
    }
    public static void main(String[] args)throws Exception {
        rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));for(JSONObject row:rows)if(row.getString("op").equals("begin")&&row.getJSONObject("expected").getString("status").equals("Valid")){beginning=row.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        File parent=new File(args[1]);PurpleModernSyncCases.directory(parent);PurpleSyncChatMoveExecutorCases.parent=parent;PurpleModernSyncCases.Frame setup=new PurpleModernSyncCases.Frame(parent,"routing",true);root=setup.root;
        org.telegram.messenger.UserConfig.getInstance(0).user=777;
        lifecycle();frames();happy();pending();cleanup();unknownReplies();partialWrites();floodRetry();losingOwnMark();unprovenOwnMark();fences();failedReads();partialReads();committedWindow();windowWinner();
        System.out.println("CARRY_DISPATCH_CHECKS="+checks);
    }
}
