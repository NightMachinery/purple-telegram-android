package org.telegram.messenger.purple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatMoveIO implements AutoCloseable {
    static final class RecordReceipt {
        final PurpleSyncChatOrigin origin;
        final Peer chat;
        final int id, date;
        final long documentId, editDate;
        final String hash, family;
        private final byte[] response, body;
        private RecordReceipt(PurpleSyncChatIO route,TLRPC.Message message,byte[] response,byte[] body,String family) {
            origin=route.origin;chat=route.chat;id=message.id;date=message.date;
            documentId=message.media.document.id;editDate=message.edit_date;this.family=family;
            this.response=response.clone();this.body=body.clone();hash=hash(body);
        }
        byte[] bytes() { return body.clone(); }
        byte[] response() { return response.clone(); }
        private static TLRPC.Message exact(PurpleSyncClient.Lookup lookup) {
            if (lookup==null || lookup.route==null || lookup.status!=PurpleSyncClient.LookupStatus.Found) return null;
            TLRPC.messages_Messages page=PurpleSyncChatMoveRequests.messages(lookup.response);
            if (page==null || lookup.route.lookup(page,null,lookup.message.id,m->lookup.message).status!=PurpleSyncClient.LookupStatus.Found) return null;
            TLRPC.Message message=page.messages.get(0);
            return message.getClass()==TLRPC.TL_message.class && message.date>0
                    && lookup.route.ownership(message)==Ownership.Own ? message : null;
        }
        static RecordReceipt seal(PurpleSyncClient.Lookup opening,PurpleSyncClient.Lookup finalRead,byte[] bytes) {
            try {
                TLRPC.Message a=exact(opening), b=exact(finalRead);
                if (a==null || b==null || bytes==null || bytes.length==0 || bytes.length>PurpleSyncReader.MAX_RECORD_BYTES
                        || !opening.route.origin.equals(finalRead.route.origin) || !opening.route.chat.equals(finalRead.route.chat)
                        || a.date!=b.date || a.id!=b.id || a.edit_date!=b.edit_date
                        || !PurpleSyncChatIO.same(opening.message,finalRead.message)
                        || a.media==null || b.media==null || a.media.getClass()!=TLRPC.TL_messageMediaDocument.class
                        || b.media.getClass()!=TLRPC.TL_messageMediaDocument.class || a.media.document==null || b.media.document==null
                        || a.media.document.getClass()!=TLRPC.TL_document.class || b.media.document.getClass()!=TLRPC.TL_document.class
                        || a.media.document.id!=b.media.document.id || a.media.document.size!=bytes.length || b.media.document.size!=bytes.length
                        || !java.util.Objects.equals(a.message,b.message)) return null;
                String first=name(a.media.document),last=name(b.media.document);
                if (!java.util.Objects.equals(first,last) || !PurpleSyncPost.CAPTION.equals(b.message)) return null;
                String family=PurpleSyncPost.RECORD_FILE_NAME.equals(last)?"Settings":PurpleSyncPost.PLAYLISTS_RECORD_FILE_NAME.equals(last)?"Playlists":null;
                if (family==null) return null;
                RecordReceipt result=new RecordReceipt(finalRead.route,b,finalRead.response,bytes,family);
                return "Ready".equals(core("message",result.message()).status)?result:null;
            } catch (Exception error) { return null; }
        }
        private static String name(TLRPC.Document document) {
            for (TLRPC.DocumentAttribute a:document.attributes) if(a.getClass()==TLRPC.TL_documentAttributeFilename.class)return a.file_name;
            return null;
        }
        JSONObject message() {
            return json("account",origin.accountKey(),"chat",chat.token(),"id",Integer.toString(id),"date",Integer.toString(date),
                    "ownership","Own","family",family,"bytes",blob(body));
        }
    }
    static JSONObject inventory(PurpleSyncInventory inventory,PurpleSyncChatOrigin origin,Peer chat) throws IOException {
        try {
            require(inventory!=null && inventory.scanComplete && inventory.ownedScan() && origin!=null && origin.equals(inventory.origin)
                    && inventory.accountUserId==origin.userId && chat.equals(inventory.chat),"IncompleteInventory");
            JSONArray messages=new JSONArray();Set<Integer> ids=new HashSet<>();
            for(int i=0;i<inventory.count();++i) {
                RecordReceipt receipt=inventory.receipt(i);
                require(receipt!=null && receipt.id==inventory.ids[i] && ids.add(receipt.id) && origin.equals(receipt.origin)
                        && chat.equals(receipt.chat) && inventory.transport[i]==PurpleSyncInventory.FETCHED
                        && receipt.documentId==inventory.documentIds[i] && receipt.editDate==inventory.editDates[i]
                        && Arrays.equals(receipt.body,inventory.bytes[i]),"UnsealedInventory");
                messages.put(receipt.message());
            }
            JSONObject result=json("account",origin.accountKey(),"chat",chat.token(),"complete",true,"messages",messages);
            require("Ready".equals(core("select",json("inventory",result)).status),"InvalidInventory");return result;
        } catch (Exception error) { throw failure(error); }
    }
    static final class Fresh {
        final PurpleSyncChatOrigin origin;
        final String loginAuthority;
        final PurpleSyncChatClient.Info source,target;
        final TLRPC.InputPeer sourcePeer,targetPeer;
        final ResolveInput resolution;
        final boolean fullResolution;
        final DirectRead oldRead;
        Fresh(PurpleSyncChatOrigin origin,String loginAuthority,PurpleSyncChatClient.Info source,TLRPC.InputPeer sourcePeer,
                PurpleSyncChatClient.Info target,TLRPC.InputPeer targetPeer,ResolveInput resolution,boolean fullResolution,DirectRead oldRead) {
            this.origin=origin;this.loginAuthority=loginAuthority;this.source=source;this.target=target;
            this.sourcePeer=PurpleSyncChatMoveRequests.peer(sourcePeer);this.targetPeer=PurpleSyncChatMoveRequests.peer(targetPeer);
            this.resolution=resolution;this.fullResolution=fullResolution;this.oldRead=oldRead;
        }
    }
    static final class Permit {
        final PurpleSyncChatOrigin origin;
        final Peer source,target;
        final String operation,kind,hash,key;
        final long revision,randomId,fileId,renewal;
        final int sourceId;
        private final PurpleSyncChatMoveIO owner;
        private final Object epoch;
        private final AtomicBoolean live;
        private final TLRPC.InputPeer from,to;
        private final byte[] body;
        private final String text;
        private final PurpleSyncChatFacts.MappedPeer targetFacts;
        private Permit(PurpleSyncChatMoveIO owner,Fresh fresh,JSONObject journal,JSONObject slot,String key,long renewal,byte[] body,AtomicBoolean live) throws Exception {
            this.owner=owner;origin=fresh.origin;source=Peer.parse(journal.getString("source"),origin.userId);target=Peer.parse(journal.getString("target"),origin.userId);
            operation=journal.getString("operation");revision=journal.getLong("revision");this.key=key;kind=slot.getString("kind");hash=slot.getString("hash");
            randomId=unsigned(slot.getString("random_id"));fileId=Long.parseUnsignedLong(slot.getString("file_id"));sourceId=slot.getInt("source_id");this.renewal=renewal;
            from=PurpleSyncChatMoveRequests.peer(fresh.sourcePeer);to=PurpleSyncChatMoveRequests.peer(fresh.targetPeer);
            epoch=owner.store.permitEpoch();this.live=live;this.body=body.clone();text=journal.getString("mark_text");
            targetFacts=new PurpleSyncChatFacts.MappedPeer(origin,target,fresh.target.peer.kind,fresh.target.peer.creator,fresh.target.peer.soleAdmin,null,null);
        }
        boolean current() {
            if(!live.get() || owner.closed || !owner.store.permitCurrent(epoch))return false;
            boolean allowed=owner.current.getAsBoolean();
            return allowed&&live.get()&&!owner.closed&&owner.store.permitCurrent(epoch);
        }
        byte[] payload() { return body.clone(); }
        String markText() { return text; }
        TLRPC.InputPeer sourcePeer() { return PurpleSyncChatMoveRequests.peer(from); }
        TLRPC.InputPeer targetPeer() { return PurpleSyncChatMoveRequests.peer(to); }
    }
    static final class WriteResponse {
        final TLObject response;
        final String error;
        final int code;
        WriteResponse(TLObject response,String error,int code) { this.response=response;this.error=error;this.code=code; }
    }
    interface WritePort {
        long now();
        long wall();
        PurpleRpcRetry.Transient cooldown();
        void renew(Permit previous,java.util.function.Consumer<Permit> done);
        int send(Permit permit,TLObject request,java.util.function.Consumer<WriteResponse> done);
        void updates(PurpleSyncChatOrigin origin,TLRPC.Updates updates);
        void cancel(int token);
        void delay(Runnable task,long millis);
        void cancelDelay(Runnable task);
        void waiting(PurpleRpcRetry.Transient waiting);
    }
    static final class Effect implements AutoCloseable {
        private final WritePort port;
        private final PurpleSyncChatPostEffect effect;
        private final TLRPC.TL_inputFile file;
        private Permit permit;
        private boolean closed,started;
        private long renewalTicket;
        Effect(Permit opening,TLRPC.TL_inputFile file,WritePort port,java.util.function.Consumer<PurpleSyncAutoUpload.Result> done) {
            if(opening==null || !opening.current())throw new IllegalArgumentException("StalePermit");
            permit=opening;this.port=port;this.file=file;
            effect=new PurpleSyncChatPostEffect(new PurpleSyncChatPostEffect.Port() {
                public long now(){return port.now();}
                public long wall(){return port.wall();}
                public PurpleRpcRetry.Transient cooldown(){return port.cooldown();}
                public void renew(java.util.function.Consumer<Boolean> ready) {
                    Permit previous=permit;long ticket=++renewalTicket;
                    port.renew(previous,next->{
                        if(closed || ticket!=renewalTicket)return;
                        ++renewalTicket;
                        boolean accepted=successor(previous,next);
                        if(accepted)permit=next;
                        ready.accept(accepted);
                    });
                }
                public int send(java.util.function.Consumer<PurpleSyncAutoUpload.Result> callback) {
                    Permit issued=permit;
                    TLObject request=PurpleSyncChatMoveRequests.effect(issued,Effect.this.file);
                    if(request==null){callback.accept(new PurpleSyncAutoUpload.Result(0,"StalePermit"));return 0;}
                    return port.send(issued,request,response->{
                        boolean updatesApplied=true;
                        try { if(response!=null && response.error==null && response.response instanceof TLRPC.Updates)port.updates(issued.origin,(TLRPC.Updates)response.response); }
                        catch(RuntimeException error) { updatesApplied=false; }
                        int id=response!=null&&response.error==null&&updatesApplied
                                ?PurpleSyncChatUpload.receipt(new PurpleSyncChatIO(issued.origin,issued.target,issued.targetFacts),response.response,issued.randomId,0):0;
                        callback.accept(new PurpleSyncAutoUpload.Result(id,id>0?null:response!=null?response.error:"OutcomeUnknown",response!=null?response.code:0));
                    });
                }
                public void cancel(int token){port.cancel(token);}
                public void delay(Runnable task,long millis){port.delay(task,millis);}
                public void cancelDelay(Runnable task){port.cancelDelay(task);}
                public void waiting(PurpleRpcRetry.Transient value){port.waiting(value);}
            },()->!closed&&permit.current(),result->{closed=true;++renewalTicket;done.accept(result);});
        }
        void start() { if(started)return;started=true;effect.start(false); }
        @Override public void close() { closed=true;++renewalTicket;effect.cancel(); }
    }
    static boolean successor(Permit previous,Permit next) {
        return previous!=null&&next!=null&&next.owner==previous.owner&&next.epoch==previous.epoch&&next.revision==previous.revision
                &&next.current()&&next.renewal>previous.renewal
                &&next.origin.equals(previous.origin)&&next.operation.equals(previous.operation)&&next.key.equals(previous.key)
                &&next.source.equals(previous.source)&&next.target.equals(previous.target)&&next.hash.equals(previous.hash)
                &&next.randomId==previous.randomId&&next.fileId==previous.fileId;
    }
    private final PurpleSyncChatMoveStore store;
    private final PurpleSyncChatOrigin origin;
    private final BooleanSupplier current;
    private final Thread worker;
    private final Object ownerToken=new Object();
    private final Map<String,Permit> permits=new HashMap<>();
    private volatile boolean closed;
    PurpleSyncChatMoveIO(PurpleSyncChatMoveStore store,PurpleSyncChatOrigin origin,BooleanSupplier current) throws IOException {
        this.store=store;this.origin=origin;this.current=current;worker=Thread.currentThread();check();
        require(origin!=null && store.accountKey().equals(origin.accountKey()),"AccountUnbound");
    }
    private void check() throws IOException {
        PurpleAccountFileSnapshot.worker();require(Thread.currentThread()==worker && !closed,"AccountUnbound");
        boolean allowed=current.getAsBoolean();require(allowed&&!closed&&Thread.currentThread()==worker,"AccountUnbound");
    }
    private JSONObject journal() throws Exception {
        check();byte[] value=store.journalBytes();require(value!=null,"MissingJournal");
        JSONObject j=new JSONObject(new String(value,StandardCharsets.UTF_8));
        require(j.getInt("version")==2 && store.journalLoginAuthority().equals(j.getString("login_authority")),"AccountUnbound");
        require(j.getInt("mode")!=0&&j.getInt("mode")!=3 || PurpleSyncChatSetupRequests.MARK.equals(j.getString("mark_text")),"ChangedMarkText");return j;
    }
    private JSONObject scope(JSONObject j) throws Exception {
        return json("account",origin.accountKey(),"source",j.getString("source"),"target",j.getString("target"),"journal",blob(store.journalBytes()));
    }
    private void fresh(JSONObject j,Fresh fresh) throws Exception {
        require(fresh!=null && origin.equals(fresh.origin) && store.loginAuthority().equals(fresh.loginAuthority)
                && fresh.fullResolution && fresh.oldRead!=null && fresh.oldRead!=DirectRead.Unknown && fresh.oldRead!=DirectRead.Migrated
                && fresh.source!=null && fresh.target!=null && origin.equals(fresh.source.origin) && origin.equals(fresh.target.origin)
                && j.getString("source").equals(fresh.source.chat.token()) && j.getString("target").equals(fresh.target.chat.token())
                && fresh.target.readable() && fresh.target.eligibility().allowed()
                && PurpleSyncChatRequests.matches(fresh.target.chat,fresh.targetPeer)
                && fresh.resolution!=null && fresh.resolution.selfUserId==origin.userId,"FreshScopeMismatch");
        Resolution r=PurpleSyncChatCore.resolve(fresh.resolution);
        require(r.action.name().equals(core("parse",scope(j)).fields.getString("expectedAction")) && r.action!=Action.Invalid && r.action!=Action.NewerMark && r.action!=Action.VerifyMark && r.action!=Action.ReadWinnerInventory
                && cacheEquals(j.optJSONObject("expected_winner"),r.winner) && cacheEquals(j.optJSONObject("old_mark"),r.cache),"ResolutionChanged");
        JSONObject input=scope(j);input.put("eligibility",PurpleSyncChatCore.eligibilityJson(fresh.target.eligibility()));
        require(!"Hold".equals(core("readers",input).fields.getString("verdict")),"ReadersUnknown");
    }
    void reserve(Fresh fresh,PurpleSyncChatClient.Info accepted,SecureRandom entropy) throws IOException {
        try {
            JSONObject j=journal();fresh(j,fresh);JSONObject t=store.transport();
            if(t.length()==0) {
                require(accepted!=null && origin.equals(accepted.origin) && fresh.target.chat.equals(accepted.chat)
                        && accepted.peer!=null && accepted.peer.kind==fresh.target.peer.kind && accepted.eligibility().allowed()
                        && PurpleSyncChatCore.eligibilityJson(accepted.eligibility()).toString().equals(PurpleSyncChatCore.eligibilityJson(fresh.target.eligibility()).toString())
                        && accepted.readersKnown && fresh.target.readersKnown && java.util.Objects.equals(accepted.members,fresh.target.members),"ReaderConsentChanged");
                JSONObject input=scope(j);input.put("eligibility",PurpleSyncChatCore.eligibilityJson(fresh.target.eligibility()));
                require("Proceed".equals(core("readers",input).fields.getString("verdict")),"ReaderConsentChanged");
                JSONArray members=new JSONArray();if(accepted.members!=null)for(Long id:new java.util.TreeSet<>(accepted.members))members.put(Long.toString(id));
                t=json("version",1,"entries",new JSONObject(),"readers",json("members",members,
                        "other_members",accepted.eligibility().readers.otherMembers==null?"":accepted.eligibility().readers.otherMembers.toString(),
                        "other_admins",accepted.eligibility().readers.otherAdmins==null?"":accepted.eligibility().readers.otherAdmins.toString(),
                        "warning",accepted.eligibility().readers.warningRequired),"reader_warning",false);
            }
            JSONObject entries=t.getJSONObject("entries");JSONObject plan=j.optJSONObject("plan");
            if(plan!=null) {
                JSONArray forwards=plan.getJSONArray("forwards");
                for(int i=0;i<forwards.length();++i) {JSONObject ref=forwards.getJSONObject(i);reserveSlot(entries,"forward",ref.getInt("message_id"),ref.getString("hash"),0,entropy);}
                String own=plan.getString("own_record");if(!own.isEmpty())reserveSlot(entries,"own",0,hash(own.getBytes(StandardCharsets.UTF_8)),unsigned(j.getString("own_request")),entropy);
            }
            if(j.getInt("mode")==0 || j.getInt("mode")==3)reserveSlot(entries,"mark",0,hash(j.getString("mark_text").getBytes(StandardCharsets.UTF_8)),unsigned(j.getString("mark_request")),entropy);
            capacity(t);store.transport(t,false);
        } catch(Exception error) { close();throw failure(error); }
    }
    private void reserveSlot(JSONObject entries,String kind,int id,String hash,long requested,SecureRandom entropy) throws Exception {
        String key=key(kind,id,hash);if(entries.has(key)) { if(requested!=0)require(unsigned(entries.getJSONObject(key).getString("random_id"))==requested,"IdentityChanged");return; }
        Set<Long> used=new HashSet<>();Iterator<String> names=entries.keys();while(names.hasNext())used.add(unsigned(entries.getJSONObject(names.next()).getString("random_id")));
        long random=requested;
        if(random==0)for(int attempt=0;attempt<128&&(random==0||used.contains(random));++attempt)random=entropy.nextLong();
        require(random!=0&&!used.contains(random),"IdentityCollision");
        long file=0;if(kind.equals("own"))for(int attempt=0;attempt<128&&file==0;++attempt)file=entropy.nextLong();
        require(!kind.equals("own")||file!=0,"IdentityCollision");
        entries.put(key,json("kind",kind,"hash",hash,"source_id",id,"random_id",Long.toUnsignedString(random),"file_id",Long.toUnsignedString(file),"state","prepared"));
    }
    private void capacity(JSONObject transport) throws Exception {
        JSONObject maximum=new JSONObject(transport.toString()),entries=maximum.getJSONObject("entries");Iterator<String> keys=entries.keys();
        while(keys.hasNext()) {JSONObject value=entries.getJSONObject(keys.next());value.put("ack_id",Integer.MAX_VALUE).put("state","verified");}
        maximum.put("reader_warning",false);store.transport(maximum,true);
    }
    Permit authorize(String kind,int sourceId,String hash,Fresh fresh,RecordReceipt source) throws IOException {
        return authorize(kind,sourceId,hash,fresh,source,null);
    }
    Permit renew(Permit previous,Fresh fresh,RecordReceipt source) throws IOException {
        require(previous!=null&&previous.owner==this&&previous.current(),"StalePermit");
        return authorize(previous.kind,previous.sourceId,previous.hash,fresh,source,previous);
    }
    private Permit authorize(String kind,int sourceId,String hash,Fresh fresh,RecordReceipt source,Permit previous) throws IOException {
        try {
            JSONObject j=journal();fresh(j,fresh);JSONObject t=store.transport(),entries=t.getJSONObject("entries");String key=key(kind,sourceId,hash);
            require(entries.has(key),"IdentityNotReserved");JSONObject slot=entries.getJSONObject(key);
            require(required(j).equals(keys(entries)),"IncompleteReservation");
            require(kind.equals("mark")?j.getBoolean("mark_attempted")&&!j.getBoolean("mark_verified")&&core("next",scope(j)).fields.getString("nextAction").equals("ReadMark")
                    :j.getBoolean("delivery_attempted")&&core("next",scope(j)).fields.getString("nextAction").equals("VerifyDestination"),"IntentNotDurable");
            require(previous!=null?permits.get(key)==previous:slot.getString("state").equals("prepared"),"ReconciliationRequired");
            byte[] body=new byte[0];
            if(kind.equals("forward")) {
                require(source!=null&&origin.equals(source.origin)&&fresh.source.chat.equals(source.chat)&&source.id==sourceId&&source.hash.equals(hash)
                        && fresh.source.readable() && PurpleSyncChatRequests.matches(fresh.source.chat,fresh.sourcePeer)
                        && fresh.source.eligibility().refusal!=Refusal.Protected,"ChangedSource");
                require(selectedDate(j,sourceId,hash)==source.date,"ChangedSource");body=source.bytes();
            } else if(kind.equals("own")) { body=j.getJSONObject("plan").getString("own_record").getBytes(StandardCharsets.UTF_8);require(hash(body).equals(hash),"ChangedPayload"); }
            else require(kind.equals("mark")&&hash(j.getString("mark_text").getBytes(StandardCharsets.UTF_8)).equals(hash),"ChangedPayload");
            readerWarning(t,fresh.target);capacity(t);
            require(store.claimSlot(key,ownerToken),"SlotBusy");
            slot.put("state","issued");store.transport(t,false);check();
            AtomicBoolean live=new AtomicBoolean(true);Permit permit=new Permit(this,fresh,j,slot,key,previous==null?1:previous.renewal+1,body,live);
            if(previous!=null)previous.live.set(false);permits.put(key,permit);return permit;
        } catch(Exception error) { close();throw failure(error); }
    }
    private Set<String> required(JSONObject j) throws Exception {
        Set<String> result=new HashSet<>();JSONObject plan=j.optJSONObject("plan");
        if(plan!=null) {
            JSONArray refs=plan.getJSONArray("forwards");for(int i=0;i<refs.length();++i){JSONObject r=refs.getJSONObject(i);result.add(key("forward",r.getInt("message_id"),r.getString("hash")));}
            if(!plan.getString("own_record").isEmpty())result.add(key("own",0,hash(plan.getString("own_record").getBytes(StandardCharsets.UTF_8))));
        }
        if(j.getInt("mode")==0||j.getInt("mode")==3)result.add(key("mark",0,hash(j.getString("mark_text").getBytes(StandardCharsets.UTF_8))));return result;
    }
    private static Set<String> keys(JSONObject value) {Set<String> result=new HashSet<>();Iterator<String> it=value.keys();while(it.hasNext())result.add(it.next());return result;}
    private static long selectedDate(JSONObject j,int id,String hash) throws Exception {
        JSONArray selected=j.getJSONObject("plan").getJSONArray("selected");for(int i=0;i<selected.length();++i){JSONObject r=selected.getJSONObject(i);if(r.getJSONObject("reference").getInt("message_id")==id&&r.getJSONObject("reference").getString("hash").equals(hash))return r.getLong("date");}return 0;
    }
    private void readerWarning(JSONObject t,PurpleSyncChatClient.Info fresh) throws Exception {
        require(fresh.readersKnown,"ReadersUnknown");JSONObject baseline=t.getJSONObject("readers");JSONArray original=baseline.getJSONArray("members");
        Set<Long> old=new HashSet<>();for(int i=0;i<original.length();++i)old.add(Long.parseLong(original.getString(i)));
        if(fresh.members!=null&&!old.containsAll(fresh.members))t.put("reader_warning",true);
        JSONObject input=scope(journal());input.put("eligibility",PurpleSyncChatCore.eligibilityJson(fresh.eligibility()));
        if("WarnAndProceed".equals(core("readers",input).fields.getString("verdict")))t.put("reader_warning",true);
    }
    void recordReceipt(Permit permit,TLObject response) throws IOException {
        check();require(permit!=null&&permit.owner==this&&permits.get(permit.key)==permit&&permit.current(),"StalePermit");
        try {
            JSONObject t=store.transport(),slot=t.getJSONObject("entries").getJSONObject(permit.key);
            PurpleSyncChatIO route=new PurpleSyncChatIO(origin,permit.target,permit.targetFacts);
            int id=PurpleSyncChatUpload.receipt(route,response,permit.randomId,0);
            if(id>0)slot.put("ack_id",id);slot.put("state","unknown");store.transport(t,false);
            permit.live.set(false);permits.remove(permit.key);store.releaseSlot(permit.key,ownerToken);
        } catch(Exception error) { close();throw failure(error); }
    }
    static final class ReadTicket {
        private final PurpleSyncChatMoveIO owner;
        private final Object epoch,generation;
        private final String key,state;
        private final long after;
        private boolean consumed;
        private ReadTicket(PurpleSyncChatMoveIO owner,String key,String state) {
            this.owner=owner;this.key=key;this.state=state;epoch=owner.store.permitEpoch();generation=owner.store.slotGeneration(key);after=PurpleSyncScanner.readSequence();
        }
    }
    ReadTicket beginReconcile(String key,Fresh fresh) throws IOException {
        try {
            JSONObject j=journal();fresh(j,fresh);require(!store.slotLive(key),"SlotBusy");
            JSONObject slot=store.transport().getJSONObject("entries").getJSONObject(key);
            require(!slot.getString("kind").equals("mark"),"MarkNeedsExactRead");
            String state=slot.getString("state");require(state.equals("issued")||state.equals("unknown"),"ReconciliationRequired");
            return new ReadTicket(this,key,state);
        } catch(Exception error) { throw failure(error); }
    }
    void reconcile(ReadTicket ticket,PurpleSyncInventory destination,Fresh fresh) throws IOException {
        check();require(ticket!=null&&ticket.owner==this&&!ticket.consumed&&store.permitCurrent(ticket.epoch)&&ticket.generation==store.slotGeneration(ticket.key),"StaleRead");
        require(destination!=null&&destination.readSequence()>ticket.after,"StaleRead");
        try {
            JSONObject j=journal();fresh(j,fresh);Peer target=Peer.parse(j.getString("target"),origin.userId);JSONObject verified=inventory(destination,origin,target);
            require(!store.slotLive(ticket.key),"SlotBusy");JSONObject t=store.transport(),slot=t.getJSONObject("entries").getJSONObject(ticket.key);
            require(slot.getString("state").equals(ticket.state)&&ticket.generation==store.slotGeneration(ticket.key),"StaleRead");boolean found=false;
            JSONArray messages=verified.getJSONArray("messages");for(int i=0;i<messages.length();++i)if(hash(PurpleSyncRetainedBases.decode(messages.getJSONObject(i).getString("bytes"))).equals(slot.getString("hash")))found=true;
            slot.put("state",found?"verified":"prepared");store.transport(t,false);ticket.consumed=true;
        } catch(Exception error) { throw failure(error); }
    }
    JSONObject markReadback(String key,PurpleSyncChatIO route,TLObject response) throws IOException {
        try {
            JSONObject j=journal();JSONObject slot=store.transport().getJSONObject("entries").getJSONObject(key);
            require(slot.getString("kind").equals("mark")&&slot.has("ack_id")&&origin.equals(route.origin)&&route.chat.token().equals(j.getString("target")),"MarkNeedsExactRead");
            TLRPC.messages_Messages page=response instanceof TLRPC.messages_Messages?(TLRPC.messages_Messages)response:null;
            int id=slot.getInt("ack_id");require(page!=null&&page.messages.size()==1,"UnknownMark");TLRPC.Message m=page.messages.get(0);
            require(route.lookup(response,null,id,ignored->new PurpleSyncClient.Message(id,true,false,false,"",null,route.chat,0,0,0,null)).status==PurpleSyncClient.LookupStatus.Found
                    && m.getClass()==TLRPC.TL_message.class&&m.date>0&&route.ownership(m)==Ownership.Own&&m.media==null
                    && m.message.equals(j.getString("mark_text")),"UnknownMark");
            return json("outcome","Valid","account",origin.accountKey(),"chat",route.chat.token(),"id",Integer.toString(id),"date",Integer.toString(m.date),"text",m.message,"hasMedia",false,"ownership","Own");
        } catch(Exception error) { throw failure(error); }
    }
    @Override public void close() {
        closed=true;for(Permit permit:permits.values()){permit.live.set(false);store.releaseSlot(permit.key,ownerToken);}permits.clear();
    }
    private static boolean cacheEquals(JSONObject expected,Cache value) throws Exception {return expected==null?value==null:value!=null&&expected.getString("chat").equals(value.chat.token())&&expected.getInt("id")==value.markId&&expected.getLong("date")==value.markDate;}
    static String key(String kind,int id,String hash) {return hash((kind+":"+id+":"+hash).getBytes(StandardCharsets.UTF_8));}
    private static long unsigned(String value) throws IOException {try{long result=Long.parseUnsignedLong(value);require(result!=0&&Long.toUnsignedString(result).equals(value),"InvalidIdentity");return result;}catch(NumberFormatException e){throw new IOException("InvalidIdentity",e);}}
    private static String hash(byte[] value) {return PurpleSyncRetainedFiles.hash(value);}
    private static String blob(byte[] value) {return PurpleSyncRetainedBases.encode(value);}
    private static JSONObject json(Object... pairs) {return PurpleSyncChatCore.json(pairs);}
    private static PurpleSyncChatMoveCore.Result core(String op,JSONObject input) {return PurpleSyncChatMoveCore.request(op,input.toString().getBytes(StandardCharsets.UTF_8));}
    private static void require(boolean value,String message) throws IOException {if(!value)throw new IOException(message);}
    private static IOException failure(Exception error) {return error instanceof IOException?(IOException)error:new IOException("InvalidState",error);}
}
