package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.json.*;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

public final class PurpleSyncChatMoveRaceCases {
    static int checks,serial,windows,races,conservation,sameChatLosers;
    static long seed;
    static Random random;
    static String context="setup";
    static void must(boolean value,String why){++checks;if(!value)throw new AssertionError("seed="+seed+" case="+context+": "+why);}
    static final Peer N1=Peer.of(PeerKind.Channel,20),N2=Peer.of(PeerKind.Channel,30);
    static final class Cloud {
        final Map<String,List<TLRPC.Message>> records=new TreeMap<>();
        final Map<String,Map<Integer,byte[]>> bodies=new TreeMap<>();
        final Map<String,Integer> ids=new TreeMap<>();
        final Map<String,Integer> requests=new HashMap<>();
        final List<String> effects=new ArrayList<>(),attempts=new ArrayList<>();
        final Set<String> gone=new HashSet<>(),forwardable=new HashSet<>();
        final Map<String,String> published=new LinkedHashMap<>();
        final Map<String,Set<String>> hidden=new HashMap<>();
        int markDate=2000,recordDate=1500,deletedSettings;
        Cloud(){
            mark(O,1,1001);TLRPC.TL_message ordinary=doc(O,10,1000,base);ordinary.media=null;ordinary.message="unrelated sentinel";records(O).add(ordinary);
            TLRPC.TL_message legacy=doc(O,11,1000,base);legacy.media.document.attributes.get(0).file_name="settings.toml";legacy.message="legacy sentinel";records(O).add(legacy);
        }
        List<TLRPC.Message> records(Peer p){return records.computeIfAbsent(p.token(),k->new ArrayList<>());}
        Map<Integer,byte[]> bodies(Peer p){return bodies.computeIfAbsent(p.token(),k->new TreeMap<>());}
        TLRPC.Message find(Peer p,int id){return records(p).stream().filter(m->m.id==id).findFirst().orElse(null);}
        void add(Peer p,int id,int date,byte[] body){records(p).add(doc(p,id,date,body));bodies(p).put(id,body.clone());published.putIfAbsent(hash(body),p.token());}
        boolean holds(Peer p,String h){for(byte[] body:bodies(p).values())if(hash(body).equals(h))return true;return false;}
        void conserved(Peer bound){
            ++conservation;
            for(Map.Entry<String,String> e:published.entrySet())must(holds(Peer.parse(e.getValue(),777),e.getKey())||holds(bound,e.getKey()),"settings record "+e.getKey().substring(0,12)+" survives in "+bound.token()+" or its original "+e.getValue());
        }
        void mark(Peer p,int id,int date){TLRPC.TL_message m=doc(p,id,date,base);m.media=null;m.flags&=~(1<<9);m.message=PurpleSyncChatSetupRequests.MARK;records(p).add(m);}
        int next(Peer p){int id=ids.getOrDefault(p.token(),100)+1;ids.put(p.token(),id);return id;}
        PurpleSyncInventory inventory(Peer p)throws Exception {
            Set<String> lagging=hidden.getOrDefault(p.token(),Collections.emptySet());
            List<TLRPC.Message> settings=new ArrayList<>();for(TLRPC.Message m:records(p)){byte[] body=bodies(p).get(m.id);if(m.media!=null&&(body==null||!lagging.contains(hash(body))))settings.add(m);}
            return new PurpleSyncChatMoveExecutorCases.CloudClient(p,settings,bodies(p)).inventory();
        }
        ResolveInput resolution(Peer source){
            ResolveInput r=new ResolveInput(777);r.settingsBound=true;r.settingsChat=source;r.cache=new Cache(source,1,1001);for(TLRPC.Message m:records(source))if(m.media==null&&PurpleSyncChatSetupRequests.MARK.equals(m.message))r.cache=new Cache(source,m.id,m.date);r.cacheRead=DirectRead.Valid;r.cachedEligibility=info(source,0,false).eligibility();r.settingsHint=SettingsHint.Found;
            List<Mark> marks=new ArrayList<>();
            for(List<TLRPC.Message> list:records.values())for(TLRPC.Message m:list)if(m.media==null&&PurpleSyncChatSetupRequests.MARK.equals(m.message)){
                Peer p=PurpleSyncChatFacts.token(m.peer_id,777);
                marks.add(new Mark(new Cache(p,m.id,m.date),MarkStatus.Supported,info(p,0,false).eligibility(),bodies(p).isEmpty()?RecordPresence.Empty:RecordPresence.Present,true,true));
            }
            Collections.shuffle(marks,random);r.marks=marks;return r;
        }
        int effect(PurpleSyncChatMoveIO.Permit permit,int date)throws Exception {
            must(permit.current(),"current sealed write admission");
            Peer target=permit.target;
            String key=target.token()+":"+permit.randomId;
            attempts.add(key);if(requests.containsKey(key))return requests.get(key);
            int id=next(target);requests.put(key,id);effects.add(permit.kind+":"+target.token()+":"+id);
            if(permit.kind.equals("mark"))mark(target,id,date);else add(target,id,recordDate,permit.payload());
            return id;
        }
        void delete(PurpleSyncChatMove.Command c){
            must(c.cleanupPermit!=null&&c.cleanupPermit.current(),"current cleanup admission");
            TLObject request=c.cleanupPermit.request();must(c.cleanupPermit.claim(request),"single delete claimed");must(!c.cleanupPermit.claim(request),"duplicate delete claim fenced");
            Peer p=c.cleanupItem.chat;effects.add("delete:"+p.token()+":"+c.messageId);if(bodies(p).containsKey(c.messageId))++deletedSettings;records(p).removeIf(m->m.id==c.messageId);bodies(p).remove(c.messageId);
        }
    }
    static final class Install implements PurpleSyncChatMove.Port,AutoCloseable {
        final Cloud cloud;
        final PurpleSyncChatMoveStoreCases.Fixture files;
        final Peer source,target;
        final byte[] own;
        final String mode;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        final List<PurpleSyncChatMove.Status> statuses=new ArrayList<>();
        final List<Runnable> late=new ArrayList<>();
        PurpleSyncChatMove move;
        PurpleSyncChatMoveStore opened;
        String phase="",window="",lastAction="",access="Readable",barrier="";
        Runnable parked;
        boolean crashed,duplicates,durablePending,cleanupEnabled=true;
        int deletes,markDate;
        final byte[] queued;
        Install(Cloud cloud,int identity,Peer source,Peer target,String mode)throws Exception {this(cloud,identity,source,target,mode,false);}
        Install(Cloud cloud,int identity,Peer source,Peer target,String mode,boolean edited)throws Exception {
            this.cloud=cloud;this.source=source;this.target=target;this.mode=mode;
            String device="test-"+(char)('a'+identity);
            String install=new String[]{"in-mfqwcylbmfqwcylbmfqwcylbme","in-mjrgeytcmjrgeytcmjrgeytcmi","in-mnrwgy3dmnrwgy3dmnrwgy3dmm"}[identity];
            String originalInstall=json(base).getJSONObject("writer").getString("install");
            own=bytes(new String(base,java.nio.charset.StandardCharsets.UTF_8).replace("test-a",device).replace(originalInstall,install));
            byte[] original=unblob(rows.get(edited?120:43).getJSONObject("input").getString("state"));
            String state=new String(original,java.nio.charset.StandardCharsets.UTF_8).replace("test-a",device).replace(originalInstall,install).replace(hash(base),hash(own)).replace("chat:10",source.token()).replace("\"message_id\":2","\"message_id\":"+(2+identity));
            if(edited){byte[] stage=unblob(rows.get(121).getJSONObject("input").getJSONObject("event").getString("stagedRecord"));queued=bytes(new String(stage,java.nio.charset.StandardCharsets.UTF_8).replace("test-a",device).replace(originalInstall,install));state=state.replace(hash(stage),hash(queued));}
            else queued=null;
            files=new PurpleSyncChatMoveStoreCases.Fixture(root,"race-"+(++serial),bytes(state));
            if(edited)PurpleModernSyncCases.write(new File(files.root,"pending/config.json"),queued);
            must(PurpleAccountSyncStore.NATIVE.inspectState(bytes(state)).valid(),"distinct install state accepted by native core");
            if(cloud.find(source,2+identity)==null)cloud.add(source,2+identity,1001,own);
        }
        Install(Install previous){this(previous,previous.source,previous.target,previous.mode);}
        Install(Install previous,Peer source,Peer target,String mode){cloud=previous.cloud;files=previous.files;this.source=source;this.target=target;own=previous.own;queued=previous.queued;this.mode=mode;duplicates=previous.duplicates;access=previous.access;cleanupEnabled=previous.cleanupEnabled;markDate=previous.markDate;}
        public PurpleSyncChatOrigin origin(){return ORIGIN;}
        public String authority(){return AUTH;}
        public String operationKey(){return "race:"+files.root.getName();}
        public boolean current(){return true;}
        public boolean foreground(){return true;}
        public void worker(Runnable task){tasks.addLast(task);}
        public void quiesce(Runnable task){tasks.addLast(task);}
        public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception {
            opened=new PurpleSyncChatMoveStore(files.root,files.owner(),0,"0-777",files.binding,AUTH,files.device,files.cache,current);
            opened.open();if(opened.journalBytes()==null)PurpleSyncRetainedFiles.pin(files.root,own);return opened;
        }
        public void cancel(PurpleSyncChatMove.Command c){}
        public void status(PurpleSyncChatMove.Status s){
            statuses.add(s);lastAction=s.action;
            if(s.reason.equals("Running")&&s.action.equals("CommitState")&&phase.equals("CommitState")){
                if(window.equals("before"))crash();
                if(window.equals("effect"))opened.fault(5,new File(files.root,"state.json"));
            }
        }
        PurpleSyncChatMove.Reply reply()throws Exception {
            PurpleSyncChatMove.Reply r=new PurpleSyncChatMove.Reply();r.sourceAccess=access;
            PurpleSyncChatClient.Info sourceInfo=info(source,0,access.equals("Protected")&&!cloud.forwardable.contains(source.token()));
            if((access.equals("Unknown")||access.equals("ChatGone"))&&!cloud.gone.contains(source.token()))sourceInfo=new PurpleSyncChatClient.Info(ORIGIN,source,null,PurpleSyncChatClient.Error.Unknown,"synthetic unreadable",Collections.emptySet(),false);
            r.fresh=new PurpleSyncChatMoveIO.Fresh(ORIGIN,AUTH,sourceInfo,input(source),info(target,0,false),input(target),cloud.resolution(source),true,cloud.gone.contains(source.token())?DirectRead.ChatGone:DirectRead.Valid);
            return r;
        }
        String label(PurpleSyncChatMove.Command c){
            if(c.action.equals("Effect"))return c.permit.kind.equals("mark")?"PostMark":c.permit.kind.equals("pending")?"ResolvePending":"Deliver";
            if(c.action.equals("Destination")&&lastAction.equals("VerifyDestination"))return "VerifyDestination";
            if(c.action.equals("Mark"))return "ReadMark";
            if(c.action.equals("CleanupDelete"))return "Cleanup";
            return "";
        }
        void crash(){if(!crashed){crashed=true;move.close();}}
        public void dispatch(PurpleSyncChatMove.Command c,Consumer<PurpleSyncChatMove.Reply> done){
            try {
                if(barrier.equals(label(c))&&!barrier.isEmpty()){parked=()->dispatch(c,done);return;}
                if(!crashed&&window.equals("durable")){
                    boolean localCommit=phase.equals("CommitState")&&c.journal()!=null&&json(c.journal()).getBoolean("state_committed");
                    boolean cleanupDurable=false;
                    if(phase.equals("Cleanup")&&durablePending){JSONObject entries=opened.cleanup().getJSONObject("entries");for(String key:entries.keySet())cleanupDurable|=entries.getJSONObject(key).getString("state").equals("absent");}
                    if(localCommit||cleanupDurable||durablePending&&!phase.equals("Cleanup")&&c.action.equals("Fresh")){crash();return;}
                }
                if(!crashed&&window.equals("before")&&phase.equals(label(c))){crash();return;}
                PurpleSyncChatMove.Reply r=reply();
                switch(c.action){
                case "Fresh":break;
                case "Source":r.source=cloud.inventory(source);r.destination=cloud.inventory(target);for(int i=0;i<r.source.count();++i)r.sourceRecords.add(r.source.receipt(i));break;
                case "Pending":r.source=cloud.inventory(source);r.sendIntent="NotIssued";break;
                case "Destination":r.destination=cloud.inventory(target);break;
                case "Effect":r.response=ack(c.permit,cloud.effect(c.permit,markDate!=0?markDate:cloud.markDate));r.fresh=reply().fresh;break;
                case "Mark":r.markRoute=route(target);TLRPC.Message mark=cloud.find(target,c.messageId);r.response=page(target,mark==null?new TLRPC.Message[0]:new TLRPC.Message[]{mark});break;
                case "Cleanup":r.destination=cloud.inventory(target);break;
                case "CleanupRead":
                    r.destination=cloud.inventory(target);r.markRoute=route(source);TLRPC.Message m=cloud.find(source,c.messageId);
                    if(m==null){TLRPC.TL_messageEmpty empty=new TLRPC.TL_messageEmpty();empty.id=c.messageId;empty.flags=1;empty.peer_id=peer(source);r.response=page(source,empty);}
                    else{r.response=page(source,m);byte[] body=cloud.bodies(source).get(m.id);if(body!=null){PurpleSyncChatMoveIO.RecordReceipt exact=receipt(route(source),m,m,body);if(exact!=null)r.sourceRecords.add(exact);}}break;
                case "CleanupDelete":cloud.delete(c);++deletes;break;
                default:throw new AssertionError(c.action);
                }
                if(!crashed&&window.equals("effect")&&phase.equals(label(c))){late.add(()->done.accept(r));crash();return;}
                if(!crashed&&window.equals("durable")&&phase.equals(label(c)))durablePending=true;
                done.accept(r);
                if(duplicates){done.accept(r);late.add(()->done.accept(r));}
            }catch(Exception e){throw new RuntimeException(e);}
        }
        void start()throws Exception {
            int operation=++serial;
            JSONObject begin=copy(beginning).put("source",source.token()).put("target",target.token()).put("mode",mode).put("operation",String.format(Locale.ROOT,"%032x",operation))
                    .put("markRequestId",Long.toString(1000000L+operation)).put("ownRequestId",Long.toString(2000000L+operation)).put("preferenceToken",blob(files.binding.getBytes(java.nio.charset.StandardCharsets.UTF_8))).put("markText",PurpleSyncChatSetupRequests.MARK).put("loginAuthority",AUTH.substring(0,32)).put("cleanupSettings",cleanupEnabled);
            move=new PurpleSyncChatMove(this);move.start(begin,info(target,0,false));
        }
        void resume(){move=new PurpleSyncChatMove(this);move.resume(info(target,0,false));}
        boolean done(){return statuses.stream().anyMatch(s->s.terminal);}
        String reason(){return statuses.isEmpty()?"none":statuses.get(statuses.size()-1).reason;}
        JSONObject journal()throws Exception{return json(Files.readAllBytes(new File(files.root,"carry.json").toPath())).getJSONObject("journal");}
        int markAck()throws Exception {
            JSONObject entries=json(Files.readAllBytes(new File(files.root,"carry.json").toPath())).getJSONObject("transport").getJSONObject("entries");
            for(String key:entries.keySet())if(entries.getJSONObject(key).getString("kind").equals("mark"))return entries.getJSONObject(key).optInt("ack_id");
            return 0;
        }
        Peer bound()throws Exception{return Peer.parse(json(Files.readAllBytes(new File(files.root,"state.json").toPath())).getString("chat"),777);}
        @Override public void close(){if(move!=null)move.close();}
    }
    static void pump(Install... installs){
        int budget=30000;
        while(true){List<Install> ready=new ArrayList<>();for(Install i:installs)if(!i.tasks.isEmpty())ready.add(i);if(ready.isEmpty())break;must(--budget>0,"bounded interleaving");Install i=ready.get(random.nextInt(ready.size()));i.tasks.removeFirst().run();}
    }
    static void stale(Install i){Collections.shuffle(i.late,random);for(Runnable r:i.late)r.run();pump(i);}
    static Install[] cohort(Cloud cloud)throws Exception {return new Install[]{new Install(cloud,0,O,N1,"Switch"),new Install(cloud,1,O,N1,"Switch"),new Install(cloud,2,O,N2,"Switch")};}
    static void ledger(Install i)throws Exception {
        JSONObject s=json(Files.readAllBytes(new File(i.files.root,"state.json").toPath()));
        JSONArray own=s.optJSONArray("own_messages");if(own==null)return;
        for(int k=0;k<own.length();++k){JSONObject entry=own.getJSONObject(k);int id=entry.getInt("message_id");Peer chat=Peer.parse(s.getString("chat"),777);byte[] body=i.cloud.bodies(chat).get(id);
            must(body!=null&&hash(body).equals(entry.getString("record_sha256")),"ledger points to verified bytes in bound chat");
            JSONObject record=json(body);must(record.getJSONObject("writer").getString("install").equals(s.getString("install")),"ledger contains only own install");
            must(record.getJSONObject("writer").getString("device").equals(s.getString("created_device")),"ledger contains only own device");
        }
    }
    static void crashMatrix()throws Exception {
        for(String phase:new String[]{"Deliver","VerifyDestination","PostMark","ReadMark","CommitState","Cleanup"})for(String window:new String[]{"before","effect","durable"}){
            context=phase+"/"+window;System.out.println("CRASH_WINDOW="+context);Cloud cloud=new Cloud();Install[] group=cohort(cloud);Install first=group[0];first.phase=phase;first.window=window;
            first.start();pump(first);
            must(first.crashed||phase.equals("CommitState")&&window.equals("effect")&&first.reason().equals("DurabilityFailed"),"crash boundary reached "+first.reason());
            first.close();pump(first);byte[] disk=Files.readAllBytes(new File(first.files.root,"carry.json").toPath());
            Install reopened=new Install(first);must(reopened.files.root.equals(first.files.root),"same literal durable directory");reopened.resume();pump(reopened);int effectsBeforeStale=cloud.effects.size();stale(first);
            must(cloud.effects.size()==effectsBeforeStale,"closed-owner reordered callback adds no cloud effects");
            JSONObject oldEntries=json(disk).getJSONObject("transport").getJSONObject("entries"),newEntries=json(Files.readAllBytes(new File(first.files.root,"carry.json").toPath())).getJSONObject("transport").getJSONObject("entries");
            for(String key:oldEntries.keySet())for(String field:new String[]{"random_id","file_id","hash","source_id"})must(oldEntries.getJSONObject(key).get(field).equals(newEntries.getJSONObject(key).get(field)),"restart preserves reserved request and record identities");
            must(disk.length>0&&reopened.opened!=null,"restart opens actual journal from disk");
            if(phase.equals("PostMark")&&!window.equals("durable")){
                must(!reopened.done()&&(reopened.reason().equals("MarkNeedsMatchedRequest")||reopened.reason().equals("ResolutionChanged")),"unacknowledged mark holds rather than blind resend "+reopened.reason());
                must(cloud.bodies(O).size()==3,"uncertain mark preserves all O records");
            }else must(reopened.done(),"durable reopen completes "+reopened.reason());
            must(new HashSet<>(cloud.attempts).size()==cloud.attempts.size(),"no blind wire resend hidden by server request deduplication");
            long marks=cloud.effects.stream().filter(s->s.startsWith("mark:")).count();must(marks==(phase.equals("PostMark")&&window.equals("before")?0:1),"no blind mark resend after restart");
            for(Map<Integer,byte[]> bodies:cloud.bodies.values()){
                Set<String> hashes=new HashSet<>();for(byte[] body:bodies.values())must(hashes.add(hash(body)),"no blind duplicate record send");
            }
            must(cloud.find(O,10)!=null&&cloud.find(O,11)!=null,"unrelated and legacy O messages survive every restart");
            if(reopened.done())must(cloud.deletedSettings>0,"completed cleanup deleted old settings, so conservation is exercised");
            cloud.conserved(reopened.bound());ledger(reopened);reopened.close();pump(reopened);++windows;
        }
    }
    static void simultaneous()throws Exception {
        // Two installs race into one chat at the same second, the third into the other chat. The seed picks
        // the pair's chat and the odd install out; the pair's chat always wins (by chat order at the same
        // second, or the odd install's earlier second), so one pair member is always a same-chat loser.
        context="simultaneous";Cloud cloud=new Cloud();
        boolean pairOnN1=(seed&1)==0;int single=(int)Math.floorMod(seed,3L);Peer pair=pairOnN1?N1:N2,other=pairOnN1?N2:N1;
        Install[] group=new Install[3];for(int k=0;k<3;++k){group[k]=new Install(cloud,k,O,k==single?other:pair,"Switch");if(k==single)group[k].markDate=pairOnN1?1999:2000;}
        for(Install i:group){i.duplicates=true;i.barrier="PostMark";i.start();}pump(group);
        for(Install i:group){must(i.parked!=null,"all three installs reach mark intent before any mark effect: "+i.reason()+" "+i.lastAction);i.barrier="";i.phase="ReadMark";i.window="before";i.tasks.add(i.parked);}pump(group);
        must(cloud.effects.stream().filter(s->s.startsWith("mark:")).count()==3,"three distinct marks posted");
        must(new HashSet<>(cloud.attempts).size()==cloud.attempts.size(),"racing installs use distinct request ids");
        Install[] reopened=new Install[group.length];for(int k=0;k<group.length;++k){group[k].close();reopened[k]=new Install(group[k]);reopened[k].resume();}pump(reopened);
        Resolution resolution=resolve(cloud.resolution(O));must(resolution.winner!=null&&resolution.winner.chat.equals(pair),"shared full resolution selects the pair's chat");
        System.out.println("CARRY_RACE_WINNER="+resolution.winner.chat.token()+":"+resolution.winner.markId+" single="+single);
        int finished=0,losers=0;
        for(int k=0;k<reopened.length;++k){Install i=reopened[k];stale(group[k]);JSONObject j=i.journal();
            if(i.target.equals(resolution.winner.chat)&&i.markAck()!=resolution.winner.markId)++losers;
            if(i.done()){++finished;must(i.target.equals(resolution.winner.chat)&&j.getJSONObject("target_mark").getInt("id")==resolution.winner.markId,"losing mark never reports Done");ledger(i);}else{must(!j.getBoolean("state_committed"),"losing install cannot commit");must(i.deletes==0,"losing mark cannot erase O");must(j.getString("source").equals(O.token())&&j.getString("target").equals(i.target.token())&&j.getBoolean("mark_attempted"),"loser retains frozen outward intent");}i.close();}pump(reopened);
        must(losers==1,"one install's mark loses inside the winning chat");sameChatLosers+=losers;
        must(finished>0,"at least one racing install completes");cloud.conserved(resolution.winner.chat);++races;
    }
    // O to N1 by one install, N1 to N2 by another, while a third install sits offline on O with a queued
    // edit. Its Follow releases the edit into O after both marks (a late own post with new bytes), so it
    // must carry those bytes to N2 before retiring the O copy.
    static Install chained(Cloud cloud)throws Exception {
        Install first=new Install(cloud,0,O,N1,"Switch"),second=new Install(cloud,1,N1,N2,"Switch"),offline=new Install(cloud,2,O,N2,"Follow",true);
        first.cleanupEnabled=false;first.start();pump(first);
        must(first.done(),"first install moves O to N1 "+first.reason());ledger(first);first.close();pump(first);
        cloud.markDate=3000;second.cleanupEnabled=false;second.start();pump(second);
        must(second.done(),"second install moves N1 to N2 "+second.reason());ledger(second);second.close();pump(second);
        must(!cloud.published.containsKey(hash(offline.queued)),"queued edit is new bytes, absent from every chat");
        cloud.recordDate=3500;return offline;
    }
    static void chain()throws Exception {
        context="O-N1-N2-offline-follow";Cloud cloud=new Cloud();Install offline=chained(cloud);String edit=hash(offline.queued);
        Install late=new Install(offline);late.duplicates=true;late.start();pump(late);stale(late);
        must(late.done(),"offline install follows current N2 with its late O publication "+late.reason());ledger(late);
        must(O.token().equals(cloud.published.get(edit)),"queued edit was first published into O, after both marks");
        must(cloud.holds(N2,edit),"Follow carried the late own record's new bytes into N2");
        must(!cloud.holds(O,edit),"verified own late O post retired only after Follow carried it");
        must(cloud.find(O,2)!=null,"another install's O record is preserved by Follow");
        cloud.conserved(N2);late.close();pump(late);++races;
    }
    static void laggingFollow()throws Exception {
        context="O-N1-N2-offline-follow-lagging-N2";Cloud cloud=new Cloud();Install offline=chained(cloud);String edit=hash(offline.queued);
        cloud.hidden.computeIfAbsent(N2.token(),k->new HashSet<>()).add(edit);
        int deleted=cloud.deletedSettings;Install late=new Install(offline);late.start();pump(late);
        must(!late.done()&&!late.journal().getBoolean("state_committed"),"Follow holds before commit while N2 cannot show the carried record "+late.reason());
        must(O.token().equals(cloud.published.get(edit))&&cloud.holds(O,edit),"late own O post kept while N2's readback lacks it");
        must(cloud.deletedSettings==deleted&&cloud.effects.stream().noneMatch(s->s.startsWith("delete:")),"no cleanup deletion without a verified N2 copy");
        cloud.conserved(N2);late.close();pump(late);++races;
    }
    static void planGone()throws Exception {
        for(boolean affirmative:new boolean[]{false,true}){
            context=affirmative?"plan-affirmative-ChatGone":"plan-uncorroborated-ChatGone";Cloud cloud=new Cloud();Install first=cohort(cloud)[0];
            first.access="ChatGone";if(affirmative)cloud.gone.add(O.token());first.start();pump(first);
            if(!affirmative)must(!first.done()&&cloud.attempts.isEmpty(),"ChatGone label without a ChatGone direct read holds at Plan before any effect "+first.reason());
            else{must(first.done()&&first.journal().getBoolean("cleanup_chat_gone"),"affirmative ChatGone at Plan completes with the own record "+first.reason());ledger(first);}
            must(cloud.deletedSettings==0&&cloud.bodies(O).size()==3,"O keeps every settings record");
            cloud.conserved(first.bound());first.close();pump(first);++races;
        }
    }
    static void goneControls()throws Exception {
        for(String evidence:new String[]{"Unknown","Protected","ChatGone"}){
            context="nonaffirmative-"+evidence;Cloud cloud=new Cloud();Install[] group=cohort(cloud);Install first=group[0];first.phase="Cleanup";first.window="before";first.start();pump(first);must(first.crashed,"committed cleanup checkpoint");
            int effects=cloud.effects.size();Install refused=new Install(first);refused.access=evidence;refused.resume();pump(refused);
            must(!refused.done()&&cloud.bodies(O).size()==3,"nonaffirmative evidence retains O and holds "+refused.reason());
            must(cloud.effects.size()==effects,"nonaffirmative evidence causes no delete");
            refused.close();pump(refused);cloud.gone.add(O.token());Install affirmative=new Install(first);affirmative.access="ChatGone";affirmative.resume();pump(affirmative);
            must(affirmative.done()&&affirmative.journal().getBoolean("cleanup_chat_gone"),"affirmative scoped ChatGone completes cleanup "+affirmative.reason());
            must(cloud.effects.size()==effects,"ChatGone does not synthesize delete requests");cloud.conserved(N1);affirmative.close();pump(affirmative);++races;
        }
    }
    /**
     * Protected at Plan, against a Readable control: a label the direct read agrees with, and one it contradicts (O actually
     * forwardable). Both Protected cases carry only this install's own record and leave O untouched, so a wrong label loses,
     * duplicates, misdirects and deletes nothing; the other installs' records stay in O.
     */
    static void planProtected()throws Exception {
        for(String label:new String[]{"Readable","Protected","contradicted"}){
            context="plan-protected-"+label;Cloud cloud=new Cloud();Install first=cohort(cloud)[0];
            first.access=label.equals("Readable")?"Readable":"Protected";if(label.equals("contradicted"))cloud.forwardable.add(O.token());first.start();pump(first);
            must(first.done(),"Plan with "+label+" completes "+first.reason());ledger(first);
            long forwards=cloud.effects.stream().filter(s->s.startsWith("forward:")).count(),deletes=cloud.effects.stream().filter(s->s.startsWith("delete:")).count();
            if(label.equals("Readable"))must(forwards>0&&deletes>0&&cloud.deletedSettings>0,"the Readable control forwards and cleans O "+cloud.effects);
            else{
                must(forwards==0&&deletes==0&&cloud.deletedSettings==0&&cloud.bodies(O).size()==3,"a Protected plan forwards nothing and keeps every O record "+label+" "+cloud.effects);
                must(cloud.effects.stream().filter(s->s.startsWith("own:")).count()==1&&cloud.bodies(N1).size()==1,"only this install's own record reaches N1, once "+label+" "+cloud.bodies(N1).keySet());
            }
            cloud.conserved(first.bound());first.close();pump(first);++races;
        }
    }
    public static void main(String[] args)throws Exception {
        seed=Long.parseLong(args[2]);random=new Random(seed);System.out.println("CARRY_RACE_SEED="+seed);
        rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));
        for(JSONObject row:rows)if(row.getString("op").equals("begin")&&row.getJSONObject("expected").getString("status").equals("Valid")){beginning=row.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));
        PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        File parent=new File(args[1]);PurpleModernSyncCases.directory(parent);root=new PurpleModernSyncCases.Frame(parent,"routing",true).root;
        must(!PurpleSyncChatChange.ACTIVATED&&!PurpleSyncChatMoveCleanup.ACTIVATED,"production remains OFF");
        if(args.length<4||args[3].equals("matrix")||args[3].equals("all"))crashMatrix();
        if(args.length<4||args[3].equals("races")||args[3].equals("all")){simultaneous();chain();laggingFollow();planGone();goneControls();planProtected();}
        System.out.println("CARRY_RACE_WINDOWS="+windows);System.out.println("CARRY_RACE_SCENARIOS="+races);System.out.println("CARRY_RACE_SAME_CHAT_LOSERS="+sameChatLosers);
        System.out.println("CARRY_RACE_CONSERVATION="+conservation);System.out.println("CARRY_RACE_CHECKS="+checks);
    }
}
