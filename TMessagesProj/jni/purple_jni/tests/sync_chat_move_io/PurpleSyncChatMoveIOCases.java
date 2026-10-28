package org.telegram.messenger.purple;

import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_update;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatMoveIOCases {
    static int checks, serial;
    static final PurpleSyncChatOrigin ORIGIN=new PurpleSyncChatOrigin(0,0,777,"carry-io-run");
    static final Peer O=Peer.of(PeerKind.Chat,10);
    static final String AUTH=PurpleSyncChatMoveStoreCases.AUTH;
    static List<JSONObject> rows;
    static JSONObject beginning;
    static byte[] base;
    static File root;
    interface Attempt {void run() throws Exception;}
    static void check(boolean value,String why){++checks;if(!value)throw new AssertionError(why);}
    static void refuses(String expected,Attempt action)throws Exception {try{action.run();throw new AssertionError("expected "+expected);}catch(IOException e){check(expected.equals(e.getMessage()),expected+" got "+e);}}
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static String blob(byte[] b){return PurpleSyncRetainedBases.encode(b);}
    static byte[] unblob(String b){return PurpleSyncRetainedBases.decode(b);}
    static JSONObject copy(JSONObject j)throws Exception{return new JSONObject(j.toString());}
    static JSONObject json(byte[] b)throws Exception{return new JSONObject(new String(b,StandardCharsets.UTF_8));}
    static JSONObject obj(Object... a){return PurpleSyncChatCore.json(a);}
    static PurpleSyncChatMoveCore.Result core(String op,JSONObject j){return PurpleSyncChatMoveCore.request(op,bytes(j.toString()));}
    static String hash(byte[] b){return PurpleSyncRetainedFiles.hash(b);}
    static TLRPC.Peer peer(Peer p){TLRPC.Peer v=p.kind==PeerKind.Channel?new TLRPC.TL_peerChannel():p.kind==PeerKind.Chat?new TLRPC.TL_peerChat():new TLRPC.TL_peerUser();if(p.kind==PeerKind.Channel)v.channel_id=p.id;else if(p.kind==PeerKind.Chat)v.chat_id=p.id;else v.user_id=p.kind==PeerKind.Self?777:p.id;return v;}
    static TLRPC.InputPeer input(Peer p){TLRPC.InputPeer v=p.kind==PeerKind.Self?new TLRPC.TL_inputPeerSelf():p.kind==PeerKind.Channel?new TLRPC.TL_inputPeerChannel():p.kind==PeerKind.Chat?new TLRPC.TL_inputPeerChat():new TLRPC.TL_inputPeerUser();v.access_hash=88;if(p.kind==PeerKind.Channel)v.channel_id=p.id;else if(p.kind==PeerKind.Chat)v.chat_id=p.id;else if(p.kind==PeerKind.User)v.user_id=p.id;return v;}
    static PurpleSyncChatClient.Info info(Peer p,int members,boolean protectedContent){
        ChatFacts facts=new ChatFacts();facts.kind=p.kind==PeerKind.Self?Kind.Self:p.kind==PeerKind.User?Kind.Private:p.kind==PeerKind.Chat?Kind.BasicGroup:Kind.Supergroup;
        facts.canSendFiles=true;facts.otherMembers=(long)members;facts.otherAdmins=0L;facts.protectedContent=protectedContent;
        Set<Long> ids=new HashSet<>();ids.add(777L);for(int i=0;i<members;++i)ids.add(888L+i);
        PurpleSyncChatFacts.MappedPeer mapped=new PurpleSyncChatFacts.MappedPeer(ORIGIN,p,facts.kind,Truth.True,Truth.True,facts,ids);
        return new PurpleSyncChatClient.Info(ORIGIN,p,mapped,PurpleSyncChatClient.Error.None,"synthetic",ids,true);
    }
    static PurpleSyncChatIO route(Peer p){return route(info(p,0,false));}
    static PurpleSyncChatIO route(PurpleSyncChatClient.Info i){return new PurpleSyncChatIO(i.origin,i.chat,i.peer);}
    static TLRPC.TL_message doc(Peer p,int id,int date,byte[] body){
        TLRPC.TL_message m=new TLRPC.TL_message();m.id=id;m.date=date;m.peer_id=peer(p);m.from_id=peer(Peer.self());m.out=true;m.message=PurpleSyncPost.CAPTION;m.flags=(1<<8)|(1<<9);
        m.media=new TLRPC.TL_messageMediaDocument();m.media.flags=1;TLRPC.TL_document d=new TLRPC.TL_document();m.media.document=d;
        d.id=1000+id;d.access_hash=99;d.file_reference=new byte[0];d.date=date;d.mime_type=PurpleSyncPost.MIME;d.size=body.length;d.dc_id=1;
        TLRPC.TL_documentAttributeFilename n=new TLRPC.TL_documentAttributeFilename();n.file_name=PurpleSyncPost.RECORD_FILE_NAME;d.attributes.add(n);return m;
    }
    static PurpleSyncClient.Message meta(TLRPC.Message m){TLRPC.Document d=m.media!=null?m.media.document:null;String name=null;if(d!=null)for(TLRPC.DocumentAttribute a:d.attributes)if(a instanceof TLRPC.TL_documentAttributeFilename){name=a.file_name;break;}return new PurpleSyncClient.Message(m.id,m.getClass()==TLRPC.TL_message.class,m.fwd_from!=null,d!=null,m.message,name,PurpleSyncChatFacts.token(m.peer_id,777),d!=null?d.id:0,d!=null?d.size:0,m.edit_date,m);}
    static TLRPC.messages_Messages page(Peer p,TLRPC.Message... m){TLRPC.messages_Messages r=p.kind==PeerKind.Channel?new TLRPC.TL_messages_channelMessages():new TLRPC.TL_messages_messages();r.count=m.length;r.messages.addAll(Arrays.asList(m));return r;}
    static PurpleSyncChatMoveIO.RecordReceipt receipt(PurpleSyncChatIO r,TLRPC.Message a,TLRPC.Message b,byte[] body){return PurpleSyncChatMoveIO.RecordReceipt.seal(r.lookup(page(r.chat,a),null,a.id,PurpleSyncChatMoveIOCases::meta),r.lookup(page(r.chat,b),null,b.id,PurpleSyncChatMoveIOCases::meta),body);}
    static PurpleSyncChatMoveIO.RecordReceipt receipt(Peer p,int id,int date,byte[] body){return receipt(route(p),doc(p,id,date,body),doc(p,id,date,body),body);}
    static final class ReadClient implements PurpleSyncClient {
        final PurpleSyncChatIO route;final File body;final ArrayDeque<Runnable> queue=new ArrayDeque<>();final List<TLRPC.Message> records;
        int reads;String change="";boolean active=true;boolean historyFailure;
        ReadClient(Peer p,byte[] bytes,List<TLRPC.Message> records)throws Exception{route=route(p);this.records=records;body=new File(root,"download-"+(++serial));Files.write(body.toPath(),bytes);}
        public long userId(){return ORIGIN.userId;}public Peer chat(){return route.chat;}public PurpleSyncChatOrigin origin(){return ORIGIN;}public boolean isActive(){return active;}
        public void runOnMain(Runnable r){queue.add(r);}public void runOnWorker(Runnable r){queue.add(r);}public void log(String s){}
        public int requestHistory(int offset,int limit,Reply<History> done){TLRPC.messages_Messages response=page(chat(),offset==0?records.toArray(new TLRPC.Message[0]):new TLRPC.Message[0]);response.count=records.size();done.onReply(historyFailure?History.failed("Unknown"):route.history(response,null,PurpleSyncChatMoveIOCases::meta));return 1;}
        public int requestMessage(int id,Reply<Lookup> done){++reads;TLRPC.Message m=records.stream().filter(v->v.id==id).findFirst().orElse(null);if(m==null){done.onReply(Lookup.failed("Unknown"));return reads;}if(reads%2==0&&change.equals("date")){TLRPC.messages_Messages clone=PurpleSyncChatMoveRequests.messages(PurpleSyncChatMoveRequests.snapshot(page(chat(),m)));m=clone.messages.get(0);m.date++;}done.onReply(route.lookup(page(chat(),m),null,id,PurpleSyncChatMoveIOCases::meta));return reads;}
        public void cancelRequest(int token){}public Object download(Message m,Reply<File> done){done.onReply(body);return null;}public void cancelDownload(Object d){}
        public void localCopies(Reply<List<LocalCopy>> done){done.onReply(Collections.emptyList());}public File filesDirectory(){return root;}public File cacheDirectory(){return root;}public long nowMillis(){return 1;}
        public void send(File f,String c,String m,Receipt r){throw new AssertionError("no live send");}
        PurpleSyncInventory inventory(){PurpleSyncTransport.CheckResult[] out={null};PurpleSyncClient owned=new PurpleSyncChatRecordClient(this,m->route.ownership((TLRPC.Message)m.remote));new PurpleSyncTelegramTransport(owned).check(null,r->out[0]=r);int cap=10000;while(!queue.isEmpty()){check(--cap>0,"bounded reader queue");queue.removeFirst().run();}check(out[0]!=null,"inventory callback");return out[0].inventory;}
    }
    static final class Harness implements AutoCloseable {
        final PurpleSyncChatMoveStoreCases.Fixture files;final PurpleSyncChatMoveStore store;PurpleSyncChatMoveIO io;final Peer target;final boolean own;
        JSONObject plan;int members;boolean protectedSource;
        Harness(boolean own)throws Exception{
            this.own=own;JSONObject planned=copy(rows.get(own?28:43).getJSONObject("input"));target=Peer.parse(planned.getString("target"),777);protectedSource=own;
            files=new PurpleSyncChatMoveStoreCases.Fixture(root,"operation-"+(++serial),unblob(planned.getString("state")));store=files.open();store.retainBase(base);
            JSONObject start=copy(beginning).put("operation",String.format(Locale.ROOT,"%032x",serial)).put("target",target.token()).put("markText",PurpleSyncChatSetupRequests.MARK).put("loginAuthority",AUTH.substring(0,32)).put("oldRead","Valid").put("sourceAccess",own?"Protected":"Readable");
            PurpleSyncChatMoveCore.Result started=store.begin(start);check(started.status.equals("Valid"),"v2 begin persisted "+started.status+" "+started.fields);check(json(store.journalBytes()).getInt("version")==2,"core v2 login/baseline journal");
            step("Resolution",obj("resolution",beginning.getJSONObject("resolution")));
            PurpleSyncChatMoveCore.Result result=core("plan",planned);check(result.status.equals("Ready"),"core planned carry");plan=json(unblob(result.fields.getString("plan")));
            step("PlanPrepared",obj("plan",result.fields.getString("plan")));
            if(own)check(store.commitPreparedState().status.equals("Valid"),"own exact stage/state reserved");
            io=new PurpleSyncChatMoveIO(store,ORIGIN,files.current::get);
        }
        JSONObject scoped()throws Exception{JSONObject j=json(store.journalBytes());return obj("account",ORIGIN.accountKey(),"source",j.getString("source"),"target",j.getString("target"),"journal",blob(store.journalBytes()));}
        void step(String kind,JSONObject payload)throws Exception{JSONObject e=core("event",scoped().put("kind",kind)).fields.getJSONObject("event");for(String k:payload.keySet())e.put(k,payload.get(k));e.put("outcome","Success");check(store.advance(e).fields.optString("canonical").length()>0,"persist nonempty "+kind);}
        PurpleSyncChatMoveIO.Fresh fresh(){
            PurpleSyncChatClient.Info source=info(O,0,protectedSource),dest=info(target,members,false);
            ResolveInput r=new ResolveInput(777);r.settingsBound=true;r.settingsChat=O;r.cache=new Cache(O,1,1001);r.cacheRead=DirectRead.Valid;r.cachedEligibility=source.eligibility();
            r.marks=Collections.singletonList(new Mark(r.cache,MarkStatus.Supported,source.eligibility(),RecordPresence.Present,true,true));
            return new PurpleSyncChatMoveIO.Fresh(ORIGIN,AUTH,source,input(O),dest,input(target),r,true,DirectRead.Valid);
        }
        void reserve()throws Exception{io.reserve(fresh(),info(target,0,false),new SecureRandom(){long n=100000;public long nextLong(){return ++n;}});}
        void intent()throws Exception{step("DeliveryPrepared",new JSONObject());}
        JSONObject slot(String kind)throws Exception{JSONObject entries=store.transport().getJSONObject("entries");for(String k:entries.keySet())if(entries.getJSONObject(k).getString("kind").equals(kind))return entries.getJSONObject(k);throw new AssertionError("missing "+kind);}
        String key(String kind)throws Exception{JSONObject s=slot(kind);return PurpleSyncChatMoveIO.key(kind,s.getInt("source_id"),s.getString("hash"));}
        PurpleSyncChatMoveIO.RecordReceipt source()throws Exception{JSONObject s=slot("forward");JSONArray records=plan.getJSONArray("selected");int date=0;for(int i=0;i<records.length();++i)if(records.getJSONObject(i).getJSONObject("reference").getInt("message_id")==s.getInt("source_id"))date=records.getJSONObject(i).getInt("date");return receipt(O,s.getInt("source_id"),date,base);}
        PurpleSyncChatMoveIO.Permit authorize(String kind)throws Exception{JSONObject s=slot(kind);return io.authorize(kind,s.getInt("source_id"),s.getString("hash"),fresh(),kind.equals("forward")?source():null);}
        @Override public void close(){if(io!=null)io.close();store.close();}
    }
    static void wireCases()throws Exception{
        Peer[] peers={Peer.self(),Peer.of(PeerKind.User,20),Peer.of(PeerKind.Chat,20),Peer.of(PeerKind.Channel,20)};
        for(Peer from:peers)for(Peer to:peers){TLRPC.TL_messages_forwardMessages r=PurpleSyncChatMoveRequests.forward(from,input(from),to,input(to),7,Long.MIN_VALUE);check(r!=null,"typed forward built");SerializedData d=new SerializedData(PurpleSyncChatMoveRequests.snapshot(r));check(d.readInt32(true)==TLRPC.TL_messages_forwardMessages.constructor,"real forward constructor");int flags=d.readInt32(true);check((flags&(1<<5))!=0&&(flags&(1<<11))!=0&&(flags&(1<<12))==0,"silent drop_author keep captions");check(PurpleSyncChatRequests.matches(from,TLRPC.InputPeer.TLdeserialize(d,d.readInt32(true),true)),"from typed peer");check(d.readInt32(true)==0x1cb5c415&&d.readInt32(true)==1&&d.readInt32(true)==7,"one exact forward id");check(d.readInt32(true)==0x1cb5c415&&d.readInt32(true)==1&&d.readInt64(true)==Long.MIN_VALUE,"full width persisted random");check(PurpleSyncChatRequests.matches(to,TLRPC.InputPeer.TLdeserialize(d,d.readInt32(true),true))&&d.remaining()==0,"target typed peer and no hidden flags");d.cleanup();}
        for(Peer p:peers){TLObject exact=PurpleSyncChatMoveRequests.exact(p,input(p),7),delete=PurpleSyncChatMoveRequests.delete(p,input(p),7);check((exact instanceof TLRPC.TL_channels_getMessages)==(p.kind==PeerKind.Channel),"exact channel constructor");SerializedData d=new SerializedData(PurpleSyncChatMoveRequests.snapshot(delete));check(d.readInt32(true)==(p.kind==PeerKind.Channel?TLRPC.TL_channels_deleteMessages.constructor:TLRPC.TL_messages_deleteMessages.constructor),"exact delete constructor");if(p.kind==PeerKind.Channel)check(TLRPC.InputChannel.TLdeserialize(d,d.readInt32(true),true).channel_id==p.id,"channel delete identity");else check((d.readInt32(true)&1)!=0,"ordinary delete revoke");check(d.readInt32(true)==0x1cb5c415&&d.readInt32(true)==1&&d.readInt32(true)==7&&d.remaining()==0,"one id only, no whole chat delete");d.cleanup();check(PurpleSyncChatMoveRequests.mark(p,input(p),PurpleSyncChatSetupRequests.MARK,3).silent,"canonical silent mark");}
        check(!PurpleSyncChatMoveRequests.cleanupEnabled(),"cleanup stays inactive");check(PurpleSyncChatMoveRequests.forward(O,input(Peer.of(PeerKind.Channel,10)),O,input(O),7,3)==null,"same numeric wrong type refuses");check(PurpleSyncChatMoveRequests.mark(O,input(O),"changed\n#purplechatsync v1",3)==null,"noncanonical mark refuses");
    }
    static void readCases()throws Exception{
        for(Peer p:new Peer[]{Peer.self(),Peer.of(PeerKind.User,20),O,Peer.of(PeerKind.Channel,10)}){
            TLRPC.TL_message a=doc(p,7,1001,base),b=doc(p,7,1001,base);PurpleSyncChatMoveIO.RecordReceipt r=receipt(route(p),a,b,base);check(r!=null&&Arrays.equals(r.bytes(),base),"actual TL response and exact bytes sealed");byte[] returned=r.bytes();returned[0]^=1;check(Arrays.equals(r.bytes(),base),"immutable body");a.date++;b.date++;check(r.date==1001,"owned native response snapshot");
            for(String changed:new String[]{"date","edit","doc","length","name","caption","forward","via","ttl","peer"}){a=doc(p,7,1001,base);b=doc(p,7,1001,base);switch(changed){case "date":b.date++;break;case "edit":b.edit_date=1;break;case "doc":b.media.document.id++;break;case "length":b.media.document.size++;break;case "name":b.media.document.attributes.get(0).file_name="other";break;case "caption":b.message="other";break;case "forward":b.fwd_from=new TLRPC.TL_messageFwdHeader();b.flags|=1<<2;break;case "via":b.via_bot_id=1;b.flags|=1<<11;break;case "ttl":b.ttl_period=1;b.flags|=1<<25;break;case "peer":b.peer_id=peer(Peer.of(PeerKind.User,999));break;}check(receipt(route(p),a,b,base)==null,"fresh metadata C0 mismatch "+changed);}
            ReadClient client=new ReadClient(p,base,Collections.singletonList(doc(p,7,1001,base)));PurpleSyncInventory inv=client.inventory();check(inv.receipt(0)!=null&&PurpleSyncChatMoveIO.inventory(inv,ORIGIN,p).getJSONArray("messages").length()==1,"actual scanner/reader sealed inventory");inv.bytes[0][0]^=1;refuses("UnsealedInventory",()->PurpleSyncChatMoveIO.inventory(inv,ORIGIN,p));
            ReadClient changed=new ReadClient(p,base,Collections.singletonList(doc(p,7,1001,base)));changed.change="date";PurpleSyncInventory bad=changed.inventory();refuses("UnsealedInventory",()->PurpleSyncChatMoveIO.inventory(bad,ORIGIN,p));
            ReadClient empty=new ReadClient(p,base,Collections.emptyList());check(PurpleSyncChatMoveIO.inventory(empty.inventory(),ORIGIN,p).getJSONArray("messages").length()==0,"native complete Empty");
            refuses("IncompleteInventory",()->PurpleSyncChatMoveIO.inventory(new PurpleSyncInventory.Builder(777,p,ORIGIN).build(true),ORIGIN,p));
        }
        byte[] newer=bytes(json(base).put("purple_sync",2).toString());check(receipt(O,7,1001,newer)!=null,"newer-major record retains exact raw body");
    }
    static void identityCases()throws Exception{
        try(Harness h=new Harness(false)){h.reserve();JSONObject before=h.store.transport();h.reserve();check(before.toString().equals(h.store.transport().toString()),"reservation identity stable");JSONObject slot=h.slot("forward");refuses("IntentNotDurable",()->h.authorize("forward"));}
        try(Harness h=new Harness(false)){h.reserve();h.intent();PurpleSyncChatMoveIO.Permit p=h.authorize("forward");check(p.current(),"live permit after durable intent");Looper.mainThread=Thread.currentThread();try{check(p.current()&&PurpleSyncChatMoveRequests.effect(p,null)!=null,"GUI permit checks and builders do no store IO");}finally{Looper.mainThread=null;}
            PurpleSyncChatMoveIO.Permit next=h.io.renew(p,h.fresh(),h.source());check(next.current()&&!p.current()&&next.randomId==p.randomId&&next.renewal>p.renewal,"renewal stable identity and newer generation");refuses("StalePermit",()->h.io.recordReceipt(p,null));check(next.current(),"stale acknowledgement cannot revoke renewed permit");h.files.current.set(false);check(!next.current(),"logout/current loss revokes RAM capability");}
        try(Harness h=new Harness(false)){h.reserve();h.intent();PurpleSyncChatMoveIO.Permit p=h.authorize("forward");h.io.recordReceipt(p,null);check(!p.current()&&h.slot("forward").getString("state").equals("unknown"),"unknown outcome keeps evidence");String key=h.key("forward");ReadClient oldRead=new ReadClient(h.target,base,Collections.emptyList());PurpleSyncInventory old=oldRead.inventory();PurpleSyncChatMoveIO.ReadTicket ticket=h.io.beginReconcile(key,h.fresh());refuses("StaleRead",()->h.io.reconcile(ticket,old,h.fresh()));ReadClient fresh=new ReadClient(h.target,base,Collections.emptyList());h.io.reconcile(ticket,fresh.inventory(),h.fresh());check(h.slot("forward").getString("state").equals("prepared"),"complete post-ticket absence permits stable retry");PurpleSyncChatMoveIO.Permit retry=h.authorize("forward");check(retry.randomId==p.randomId,"retry never rotates random id");refuses("StaleRead",()->h.io.reconcile(ticket,old,h.fresh()));check(retry.current(),"stale reconciliation cannot revoke current effect");}
        try(Harness h=new Harness(false)){h.reserve();h.intent();PurpleSyncChatMoveIO.Permit p=h.authorize("forward");h.io.recordReceipt(p,null);String key=h.key("forward");PurpleSyncChatMoveIO.ReadTicket t=h.io.beginReconcile(key,h.fresh());ReadClient read=new ReadClient(h.target,base,Collections.singletonList(doc(h.target,55,1005,base)));h.io.reconcile(t,read.inventory(),h.fresh());check(h.slot("forward").getString("state").equals("verified"),"exact hash complete inventory confirms uncertain forward");}
        try(Harness h=new Harness(false)){h.reserve();h.intent();h.members=1;PurpleSyncChatMoveIO.Permit p=h.authorize("forward");check(p.current()&&h.store.transport().getBoolean("reader_warning"),"known reader addition sticky warning");h.members=0;h.io.renew(p,h.fresh(),h.source());check(h.store.transport().getBoolean("reader_warning"),"reader baseline/warning never overwritten");}
        try(Harness h=new Harness(true)){h.reserve();h.intent();PurpleSyncChatMoveIO.Permit p=h.authorize("own");check(p.current()&&p.fileId!=0&&Arrays.equals(p.payload(),bytes(h.plan.getString("own_record"))),"protected O retained own payload and persistent file id");}
    }
    static TLRPC.Updates ack(PurpleSyncChatMoveIO.Permit p,int id) {
        TLRPC.TL_updates result=new TLRPC.TL_updates();TL_update.TL_updateMessageID map=new TL_update.TL_updateMessageID();map.random_id=p.randomId;map.id=id;result.updates.add(map);
        TLRPC.TL_message m=doc(p.target,id,2000,base);
        if(p.kind.equals("mark")){m.message=PurpleSyncChatSetupRequests.MARK;m.media=null;m.flags&=~(1<<9);}
        if(p.target.kind==PeerKind.Channel){TL_update.TL_updateNewChannelMessage u=new TL_update.TL_updateNewChannelMessage();u.message=m;result.updates.add(u);}else{TL_update.TL_updateNewMessage u=new TL_update.TL_updateNewMessage();u.message=m;result.updates.add(u);}return result;
    }
    static void receiptHints()throws Exception {
        for(Peer p:new Peer[]{Peer.self(),Peer.of(PeerKind.User,20),Peer.of(PeerKind.Chat,20),Peer.of(PeerKind.Channel,20)}) {
            PurpleSyncChatIO route=route(p);long random=Long.MIN_VALUE;
            TLRPC.TL_updates updates=new TLRPC.TL_updates();TL_update.TL_updateMessageID map=new TL_update.TL_updateMessageID();map.id=55;map.random_id=random;updates.updates.add(map);
            TLRPC.Message message=doc(p,55,1001,base);TLRPC.Update row;
            if(p.kind==PeerKind.Channel){TL_update.TL_updateNewChannelMessage u=new TL_update.TL_updateNewChannelMessage();u.message=message;row=u;}else{TL_update.TL_updateNewMessage u=new TL_update.TL_updateNewMessage();u.message=message;row=u;}updates.updates.add(row);
            check(PurpleSyncChatUpload.receipt(route,updates,random,0)==55,"ordinary/channel strict Updates hint");updates.updates.add(map);check(PurpleSyncChatUpload.receipt(route,updates,random,0)==55,"duplicate identical mapping remains one hint");
            TL_update.TL_updateMessageID conflict=new TL_update.TL_updateMessageID();conflict.id=56;conflict.random_id=random;updates.updates.add(conflict);check(PurpleSyncChatUpload.receipt(route,updates,random,0)==0,"contradictory mapping unknown");updates.updates.remove(conflict);
            check(PurpleSyncChatUpload.receipt(route,updates,random+1,0)==0,"foreign random mapping unknown");
            TLRPC.Peer original=message.peer_id;message.peer_id=peer(Peer.of(p.kind==PeerKind.Channel?PeerKind.Chat:PeerKind.Channel,20));check(PurpleSyncChatUpload.receipt(route,updates,random,0)==0,"equal-number wrong typed target unknown");message.peer_id=original;
            message.via_bot_id=7;check(PurpleSyncChatUpload.receipt(route,updates,random,0)==0,"receipt reapplies C0");message.via_bot_id=0;
            TLRPC.TL_updatesCombined combined=new TLRPC.TL_updatesCombined();combined.updates.addAll(updates.updates);check(PurpleSyncChatUpload.receipt(route,combined,random,0)==55,"combined Updates hint");
            TLRPC.TL_updateShortSentMessage shortReply=new TLRPC.TL_updateShortSentMessage();shortReply.id=55;shortReply.date=1001;shortReply.out=true;check((PurpleSyncChatUpload.receipt(route,shortReply,random,0)==55)==(p.kind!=PeerKind.Channel),"short reply only ordinary target");
            check(PurpleSyncChatUpload.receipt(route,new TLRPC.TL_updatesTooLong(),random,0)==0,"unknown transport keeps evidence");
        }
    }
    static void scopeCases()throws Exception {
        for(String changed:new String[]{"account","login","target","read","readers","rights","winner"})try(Harness h=new Harness(false)) {
            h.reserve();h.intent();PurpleSyncChatMoveIO.Fresh good=h.fresh();PurpleSyncChatOrigin origin=good.origin;String login=good.loginAuthority;TLRPC.InputPeer target=good.targetPeer;DirectRead read=good.oldRead;PurpleSyncChatClient.Info info=good.target;
            switch(changed){case "account":origin=new PurpleSyncChatOrigin(1,1,777,"foreign");break;case "login":login="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";break;case "target":target=input(Peer.of(PeerKind.Channel,good.target.chat.id));break;case "read":read=DirectRead.Unknown;break;case "readers":info=new PurpleSyncChatClient.Info(ORIGIN,info.chat,info.peer,info.error,"unknown readers",null,false);break;case "rights":info.peer.facts.canSendFiles=false;break;case "winner":good.resolution.marks=Collections.emptyList();good.resolution.cacheRead=DirectRead.MarkGone;break;}
            PurpleSyncChatMoveIO.Fresh bad=new PurpleSyncChatMoveIO.Fresh(origin,login,good.source,good.sourcePeer,info,target,good.resolution,true,read);JSONObject slot=h.slot("forward");
            refuses(changed.equals("readers")?"ReadersUnknown":changed.equals("winner")?"ResolutionChanged":"FreshScopeMismatch",()->h.io.authorize("forward",slot.getInt("source_id"),slot.getString("hash"),bad,h.source()));
            check(h.slot("forward").getString("state").equals("prepared"),"scope refusal before effect "+changed);
        }
        try(Harness h=new Harness(false)) {
            h.io.close();java.util.concurrent.atomic.AtomicBoolean retire=new java.util.concurrent.atomic.AtomicBoolean();h.io=new PurpleSyncChatMoveIO(h.store,ORIGIN,()->{if(retire.get())h.store.close();return true;});h.reserve();h.intent();PurpleSyncChatMoveIO.Permit p=h.authorize("forward");retire.set(true);check(!p.current(),"reentrant store close during RAM predicate cannot preserve permit");
        }
    }
    static void restartAndMarkCases()throws Exception {
        Harness h=new Harness(false);h.reserve();h.intent();PurpleSyncChatMoveIO.Permit first=h.authorize("forward");long random=first.randomId;String key=h.key("forward");PurpleSyncChatMoveIO.RecordReceipt originalSource=h.source();h.close();
        try(PurpleSyncChatMoveStore reopened=h.files.open()) {
            PurpleSyncChatMoveIO io=new PurpleSyncChatMoveIO(reopened,ORIGIN,h.files.current::get);JSONObject slot=reopened.transport().getJSONObject("entries").getJSONObject(key);
            PurpleSyncChatMoveIO blocked=io;refuses("ReconciliationRequired",()->blocked.authorize("forward",slot.getInt("source_id"),slot.getString("hash"),h.fresh(),originalSource));
            io=new PurpleSyncChatMoveIO(reopened,ORIGIN,h.files.current::get);PurpleSyncChatMoveIO current=io;
            PurpleSyncChatMoveIO.ReadTicket ticket=io.beginReconcile(key,h.fresh());ReadClient read=new ReadClient(h.target,base,Collections.emptyList());io.reconcile(ticket,read.inventory(),h.fresh());
            PurpleSyncChatMoveIO.Permit retry=io.authorize("forward",slot.getInt("source_id"),slot.getString("hash"),h.fresh(),originalSource);check(retry.current()&&retry.randomId==random,"issued restart holds then exact absence retries same identity");
            h.io.close();check(retry.current(),"old wrapper close cannot revoke new wrapper permit");refuses("SlotBusy",()->current.beginReconcile(key,h.fresh()));check(retry.current(),"active slot cannot be reconciled through another wrapper");io.close();
        }
        try(Harness mark=new Harness(false)) {
            mark.reserve();mark.intent();ReadClient read=new ReadClient(mark.target,base,Collections.singletonList(doc(mark.target,55,1500,base)));
            mark.step("DestinationRead",obj("destination",PurpleSyncChatMoveIO.inventory(read.inventory(),ORIGIN,mark.target)));
            mark.step("MarkPostPrepared",new JSONObject());PurpleSyncChatMoveIO.Permit permit=mark.authorize("mark");String slot=mark.key("mark");
            TLRPC.TL_message exact=doc(mark.target,70,2000,base);exact.message=PurpleSyncChatSetupRequests.MARK;exact.media=null;exact.flags&=~(1<<9);
            refuses("MarkNeedsExactRead",()->mark.io.markReadback(slot,route(mark.target),page(mark.target,exact)));
            mark.io.recordReceipt(permit,ack(permit,70));check(mark.slot("mark").getInt("ack_id")==70,"request-bound mark acknowledgement persisted");
            check(mark.io.markReadback(slot,route(mark.target),page(mark.target,exact)).getString("outcome").equals("Valid"),"exact owned mark text/date/id receipt");
            refuses("UnknownMark",()->mark.io.markReadback(slot,route(mark.target),page(mark.target)));
            exact.date=0;refuses("UnknownMark",()->mark.io.markReadback(slot,route(mark.target),page(mark.target,exact)));
            exact.date=2000;exact.message="other";refuses("UnknownMark",()->mark.io.markReadback(slot,route(mark.target),page(mark.target,exact)));
        }
        try(Harness fault=new Harness(false)){fault.store.fault(1);refuses("DurabilityFailed",fault::reserve);check(!new String(Files.readAllBytes(new File(fault.files.root,"carry.json").toPath()),StandardCharsets.UTF_8).contains("transport_sha256"),"no transport identity exposed after failed durable reservation");}
        try(Harness near=new Harness(false)) {
            near.reserve();near.intent();long size=new File(near.files.root,"carry.json").length();JSONObject t=near.store.transport(),future=copy(t);
            JSONObject entries=future.getJSONObject("entries");for(String entryKey:entries.keySet())entries.getJSONObject(entryKey).put("ack_id",Integer.MAX_VALUE).put("state","verified");future.put("reader_warning",false);
            int growth=bytes(future.toString()).length-bytes(t.toString()).length;char[] pad=new char[(int)(4194304-size-growth-8)];Arrays.fill(pad,'x');t.put("pad",new String(pad));near.store.transport(t,false);
            byte[] before=Files.readAllBytes(new File(near.files.root,"carry.json").toPath());check(before.length+growth==4194305,"one-byte future receipt boundary");
            refuses("RetentionFull",()->near.authorize("forward"));check(near.store.journalBytes()==null,"capacity failure revokes store before any permit");check(Arrays.equals(before,Files.readAllBytes(new File(near.files.root,"carry.json").toPath())),"near-limit unknown extension and exact journal retained");
        }
    }
    static final class WritePort implements PurpleSyncChatMoveIO.WritePort {
        long now=1000;int sends,updates,cancels;PurpleRpcRetry.Transient cooldown;
        final List<PurpleSyncChatMoveIO.Permit> previous=new ArrayList<>(),issued=new ArrayList<>();final List<Consumer<PurpleSyncChatMoveIO.Permit>> renew=new ArrayList<>();final List<Consumer<PurpleSyncChatMoveIO.WriteResponse>> replies=new ArrayList<>();
        final LinkedHashMap<Runnable,Long> timers=new LinkedHashMap<>();
        public long now(){return now;}public long wall(){return now;}public PurpleRpcRetry.Transient cooldown(){return cooldown;}
        public void renew(PurpleSyncChatMoveIO.Permit p,Consumer<PurpleSyncChatMoveIO.Permit> done){previous.add(p);renew.add(done);}
        public int send(PurpleSyncChatMoveIO.Permit p,TLObject request,Consumer<PurpleSyncChatMoveIO.WriteResponse> done){check(p.origin.equals(ORIGIN)&&request instanceof TLRPC.TL_messages_forwardMessages,"captured account and typed request dispatched");++sends;issued.add(p);replies.add(done);return sends;}
        public void updates(PurpleSyncChatOrigin o,TLRPC.Updates u){check(o.equals(ORIGIN),"late Updates retain captured account");++updates;}
        public void cancel(int token){++cancels;}public void delay(Runnable task,long millis){timers.put(task,millis);}public void cancelDelay(Runnable task){timers.remove(task);}public void waiting(PurpleRpcRetry.Transient value){}
    }
    static void effectCases()throws Exception {
        try(Harness h=new Harness(false)){h.reserve();h.intent();PurpleSyncChatMoveIO.Permit opening=h.authorize("forward");WritePort port=new WritePort();int[] done={0};PurpleSyncChatMoveIO.Effect effect=new PurpleSyncChatMoveIO.Effect(opening,null,port,r->done[0]++);effect.start();effect.start();check(port.renew.size()==1,"single effect renewal");PurpleSyncChatMoveIO.Permit next=h.io.renew(opening,h.fresh(),h.source());port.renew.get(0).accept(next);check(port.sends==1,"renewed effect dispatched once");port.renew.get(0).accept(opening);check(port.sends==1,"duplicate old renewal cannot dispatch");effect.close();port.replies.get(0).accept(new PurpleSyncChatMoveIO.WriteResponse(ack(next,77),null,0));check(port.updates==1&&done[0]==0&&port.timers.isEmpty(),"late updates applied after cancel without stale success");}
        try(Harness h=new Harness(false)){h.reserve();h.intent();PurpleSyncChatMoveIO.Permit opening=h.authorize("forward");WritePort port=new WritePort();int[] done={0};PurpleSyncChatMoveIO.Effect effect=new PurpleSyncChatMoveIO.Effect(opening,null,port,r->{check(r.id==77,"renewed receipt hint");done[0]++;});effect.start();PurpleSyncChatMoveIO.Permit p=h.io.renew(opening,h.fresh(),h.source());port.renew.get(0).accept(p);Runnable staleTimeout=port.timers.keySet().iterator().next();port.cooldown=new PurpleRpcRetry.Transient(PurpleRpcRetry.Kind.Flood,port.now+5000,false);port.replies.get(0).accept(new PurpleSyncChatMoveIO.WriteResponse(null,"FLOOD_WAIT_2",420));check(port.timers.size()==1&&port.timers.values().iterator().next()==5000,"combined short flood and longer stream cooldown");Runnable retry=port.timers.keySet().iterator().next();port.timers.remove(retry);port.now+=5000;port.cooldown=null;retry.run();check(port.renew.size()==2,"deferred send renews through worker again");PurpleSyncChatMoveIO.Permit newer=h.io.renew(p,h.fresh(),h.source());port.renew.get(1).accept(newer);staleTimeout.run();check(port.sends==2&&done[0]==0,"stale timer cannot cancel newer dispatch");port.replies.get(1).accept(new PurpleSyncChatMoveIO.WriteResponse(ack(newer,77),null,0));port.replies.get(1).accept(new PurpleSyncChatMoveIO.WriteResponse(ack(newer,77),null,0));check(done[0]==1&&port.updates==2,"duplicate completion cannot finish twice, Updates still processed");}
    }
    static class UploadPort implements PurpleSyncAutoUpload.Port {
        final Harness h;final ArrayDeque<Runnable> queue=new ArrayDeque<>();int parts,sends,cancelReads;long file;byte[] body;boolean opening,throwCleanup;PurpleSyncClient.Reply<Boolean> uploaded;
        UploadPort(Harness h){this.h=h;}
        public void worker(Runnable r){queue.add(r);}public int upload(long id,byte[] b,PurpleSyncClient.Reply<Boolean> done){++parts;file=id;body=b.clone();uploaded=done;return 17;}
        public void exact(int id,PurpleSyncClient.Reply<byte[]> done){throw new AssertionError("carry must not edit ordinary head");}
        public int send(int id,TLRPC.TL_inputFile file,PurpleSyncClient.Reply<PurpleSyncAutoUpload.Result> done){throw new AssertionError("ordinary send cannot consume carry permit");}
        public int sendCarry(PurpleSyncChatMoveIO.Permit p,TLRPC.TL_inputFile f,PurpleSyncClient.Reply<PurpleSyncAutoUpload.Result> done){check(f.id==p.fileId&&f.parts==1&&PurpleSyncChatMoveRequests.effect(p,f) instanceof TLRPC.TL_messages_sendMedia,"own upload uses persisted file/random and typed route");++sends;done.onReply(new PurpleSyncAutoUpload.Result(55,null));return 18;}
        public void beforePost(PurpleSyncClient.Reply<Boolean> done){throw new AssertionError("ordinary admission cannot bypass carry authority");}
        public void renewCarry(PurpleSyncChatMoveIO.Permit p,PurpleSyncClient.Reply<PurpleSyncChatMoveIO.Permit> done){try{done.onReply(opening?p:h.io.renew(p,h.fresh(),null));}catch(Exception error){throw new RuntimeException(error);}}
        public void cancel(int token){}public void cancelReads(){++cancelReads;if(throwCleanup)throw new IllegalStateException("synthetic cleanup failure");}
        void drain(){int cap=1000;while(!queue.isEmpty()){check(--cap>0,"bounded upload queue");queue.removeFirst().run();}}
    }
    static void uploadCases()throws Exception {
        UserConfig.getInstance(0).user=777;
        for(int mode=0;mode<4;++mode)try(Harness h=new Harness(true)){h.reserve();h.intent();PurpleSyncChatMoveIO.Permit p=h.authorize("own");UploadPort port=new UploadPort(h);port.opening=mode==1;port.throwCleanup=mode==3;int[] completed={0};PurpleSyncAutoUpload.Result[] result={null};PurpleSyncAutoUpload upload=new PurpleSyncAutoUpload(p,()->true,port,r->{result[0]=r;completed[0]++;});upload.start();upload.start();check(port.parts==1&&port.file==p.fileId&&Arrays.equals(port.body,p.payload()),"sealed own body and durable upload identity");if(mode==2)upload.cancel();try{port.uploaded.onReply(true);port.drain();}catch(IllegalStateException failure){check(mode==3&&failure.getMessage().equals("synthetic cleanup failure"),"cleanup failure control");}check(completed[0]==1&&port.cancelReads==1&&!PurpleSyncAutoEdits.busy(0),"upload releases once including throwing cleanup/cancel");check((result[0].id==55)==(mode==0||mode==3),"late/opening/cancel cannot confirm upload");check(port.sends==((mode==0||mode==3)?1:0),"only freshly renewed own permit sends");}
    }
    static void overlappingReconciliationCase(boolean crossWrapper)throws Exception {
        try(Harness h=new Harness(false)) {
            h.reserve();h.intent();PurpleSyncChatMoveIO.Permit first=h.authorize("forward");h.io.recordReceipt(first,null);
            String key=h.key("forward");
            PurpleSyncChatMoveIO.ReadTicket a=h.io.beginReconcile(key,h.fresh()),b=h.io.beginReconcile(key,h.fresh());
            PurpleSyncInventory oldAbsence=new ReadClient(h.target,base,Collections.emptyList()).inventory();
            h.io.reconcile(a,oldAbsence,h.fresh());
            PurpleSyncChatMoveIO issuer=crossWrapper?new PurpleSyncChatMoveIO(h.store,ORIGIN,h.files.current::get):h.io;
            try {
                JSONObject slot=h.slot("forward");
                PurpleSyncChatMoveIO.Permit second=issuer.authorize("forward",slot.getInt("source_id"),slot.getString("hash"),h.fresh(),h.source());
                check(second.randomId==first.randomId,"ABA retry preserves random identity");issuer.recordReceipt(second,null);
                refuses("StaleRead",()->h.io.reconcile(b,oldAbsence,h.fresh()));
                check(h.slot("forward").getString("state").equals("unknown"),"old absence cannot reopen later uncertain outcome");
                PurpleSyncChatMoveIO.ReadTicket fresh=h.io.beginReconcile(key,h.fresh());
                h.io.reconcile(fresh,new ReadClient(h.target,base,Collections.emptyList()).inventory(),h.fresh());
                check(h.slot("forward").getString("state").equals("prepared"),"post-outcome inventory is a valid control");
            }finally{if(crossWrapper)issuer.close();}
        }
    }
    static void renewalRevisionCase(boolean upload,boolean epochOnly)throws Exception {
        try(Harness h=new Harness(upload)) {
            h.reserve();h.intent();String kind=upload?"own":"forward";
            PurpleSyncChatMoveIO.Permit opening=h.authorize(kind);
            WritePort writes=new WritePort();List<PurpleSyncClient.Reply<PurpleSyncChatMoveIO.Permit>> renewals=new ArrayList<>();
            UploadPort uploads=new UploadPort(h){
                @Override public void renewCarry(PurpleSyncChatMoveIO.Permit p,PurpleSyncClient.Reply<PurpleSyncChatMoveIO.Permit> done){renewals.add(done);}
            };
            int[] completed={0};PurpleSyncChatMoveIO.Effect effect=null;PurpleSyncAutoUpload uploader=null;
            if(upload){UserConfig.getInstance(0).user=777;uploader=new PurpleSyncAutoUpload(opening,()->true,uploads,r->completed[0]++);uploader.start();uploads.uploaded.onReply(true);uploads.drain();check(renewals.size()==1,"uploader waits at worker renewal");}
            else {effect=new PurpleSyncChatMoveIO.Effect(opening,null,writes,r->completed[0]++);effect.start();check(writes.renew.size()==1,"effect waits at renewal");}
            if(epochOnly)h.store.sendIdentity("new-epoch-same-revision",new SecureRandom());else h.step("DeliveryResult",new JSONObject());
            check(!opening.current(),"opening capability revoked before delayed renewal");
            PurpleSyncChatMoveIO.ReadTicket ticket=h.io.beginReconcile(h.key(kind),h.fresh());
            h.io.reconcile(ticket,new ReadClient(h.target,base,Collections.emptyList()).inventory(),h.fresh());
            PurpleSyncChatMoveIO.Permit current=h.authorize(kind),newer=h.io.renew(current,h.fresh(),upload?null:h.source());
            check(newer.current()&&newer.renewal>opening.renewal,"replacement is legitimate strictly newer capability");
            check(epochOnly?newer.revision==opening.revision:newer.revision!=opening.revision,"isolated epoch/revision schedule");
            if(upload){renewals.get(0).onReply(newer);uploads.drain();check(uploads.sends==0&&completed[0]==1,"old uploader refuses foreign execution renewal");uploader.cancel();}
            else {writes.renew.get(0).accept(newer);check(writes.sends==0,"old effect refuses foreign execution renewal");effect.close();}
            check(newer.current(),"rejected stale callback leaves replacement capability intact");
        }
    }
    static void fixRoundOneCases()throws Exception {
        overlappingReconciliationCase(false);overlappingReconciliationCase(true);
        for(boolean upload:new boolean[]{false,true})for(boolean epochOnly:new boolean[]{false,true})renewalRevisionCase(upload,epochOnly);
    }
    public static void main(String[] args)throws Exception{
        rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));for(JSONObject r:rows)if(r.getString("op").equals("begin")&&r.getJSONObject("expected").getString("status").equals("Valid")){beginning=r.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        File parent=new File(args[1]);PurpleModernSyncCases.directory(parent);PurpleModernSyncCases.Frame frame=new PurpleModernSyncCases.Frame(parent,"routing",true);root=frame.root;
        if(args.length>2){if(args[2].equals("aba"))overlappingReconciliationCase(false);else renewalRevisionCase(args[2].equals("upload"),args[2].equals("epoch"));System.out.println("CARRY_IO_CHECKS="+checks);return;}
        fixRoundOneCases();wireCases();readCases();identityCases();receiptHints();scopeCases();restartAndMarkCases();effectCases();uploadCases();System.out.println("CARRY_IO_CHECKS="+checks);
    }
}
