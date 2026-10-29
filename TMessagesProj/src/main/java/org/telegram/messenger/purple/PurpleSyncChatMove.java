package org.telegram.messenger.purple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatMove implements AutoCloseable {
    interface Port {
        PurpleSyncChatOrigin origin();
        String authority();
        String operationKey();
        boolean current();
        boolean foreground();
        void worker(Runnable task);
        void quiesce(Runnable done);
        PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted) throws Exception;
        void dispatch(Command command,Consumer<Reply> done);
        void cancel(Command command);
        void status(Status status);
    }
    static final class Committed {
        final PurpleSyncChatOrigin origin;
        final String authority,operation;
        final Cache mark;
        private Committed(PurpleSyncChatOrigin origin,String authority,String operation,Cache mark){this.origin=origin;this.authority=authority;this.operation=operation;this.mark=mark;}
    }
    static final class Status {
        final String action,reason;
        final boolean terminal;
        final Committed committed;
        Status(String action,String reason,boolean terminal){this(action,reason,terminal,null);}
        private Status(String action,String reason,boolean terminal,Committed committed){this.action=action;this.reason=reason;this.terminal=terminal;this.committed=committed;}
    }
    static final class Command {
        final String action,operation,account,source,target;
        final long run,ticket,revision;
        final PurpleSyncChatOrigin origin;
        final String authority;
        final PurpleSyncChatMoveIO.Permit permit;
        private PurpleSyncChatMoveIO.Permit currentPermit;
        final int messageId;
        private final byte[] journal;
        private final PurpleSyncChatMoveIO.ReadTicket read,pendingRead;
        private Command(PurpleSyncChatMove owner,String action,PurpleSyncChatMoveIO.Permit permit,int id,PurpleSyncChatMoveIO.ReadTicket read)throws Exception {
            this.action=action;this.permit=permit;currentPermit=permit;messageId=id;this.read=read;
            pendingRead=action.equals("Pending")?owner.io.beginPendingRead(owner.fresh):null;
            origin=owner.origin;authority=owner.authority;account=origin.accountKey();run=owner.run;ticket=++owner.tickets;
            journal=owner.store==null?null:owner.store.journalBytes();JSONObject j=journal==null?owner.opening:parse(journal);
            operation=j.getString("operation");source=j.getString("source");target=j.getString("target");revision=j.optLong("revision",0);
        }
        byte[] journal(){return journal==null?null:journal.clone();}
    }
    static final class Reply {
        PurpleSyncChatMoveIO.Fresh fresh;
        PurpleSyncInventory source,destination;
        PurpleSyncChatIO markRoute;
        TLObject response;
        List<PurpleSyncChatMoveIO.RecordReceipt> sourceRecords=new ArrayList<>();
        String sourceAccess="Unknown",sendIntent="Unknown";
        JSONObject playlist;
        PlaylistReceipt verifiedPlaylist;
        boolean libraryPending;
    }
    static final class PlaylistReceipt {
        private final JSONObject proof;
        private PlaylistReceipt(JSONObject proof)throws Exception {this.proof=copy(proof);}
    }
    private final Port port;
    private final PurpleSyncChatOrigin origin;
    private final String authority;
    private final Object owner=new Object();
    private final SecureRandom entropy=new SecureRandom();
    private PurpleSyncOperations.CarryLease lease;
    private PurpleSyncChatMoveStore store;
    private PurpleSyncChatMoveIO io;
    private JSONObject opening;
    private PurpleSyncChatClient.Info accepted;
    private PurpleSyncChatMoveIO.Fresh fresh;
    private List<PurpleSyncChatMoveIO.RecordReceipt> sourceRecords=new ArrayList<>();
    private final java.util.Set<String> dispatched=new java.util.HashSet<>();
    private JSONObject destination,playlist;
    private volatile boolean closed;
    private boolean started,interrupted,discardConfirmed;
    private Thread worker;
    private volatile long run,tickets;
    private volatile Command active;
    PurpleSyncChatMove(Port port){this.port=port;origin=port.origin();authority=port.authority();}
    void start(JSONObject begin,PurpleSyncChatClient.Info accepted){
        if(started||closed)return;started=true;
        try{opening=copy(begin);this.accepted=accepted;}catch(Exception e){close();return;}
        port.worker(()->guard(()->open(false,false)));
    }
    void resume(PurpleSyncChatClient.Info accepted){
        if(started||closed)return;started=true;this.accepted=accepted;
        port.worker(()->guard(()->open(true,false)));
    }
    void discardInterrupted(boolean confirmed){
        if(started||closed||!confirmed)return;started=true;interrupted=true;discardConfirmed=true;
        port.worker(()->guard(()->open(true,true)));
    }
    private boolean live(){
        if(closed||origin==null||authority==null||!authority.matches("[0-9a-f]{64}"))return false;
        boolean ok=port.current()&&port.foreground();
        return ok&&!closed&&(lease==null||lease.current());
    }
    private void require()throws IOException {
        PurpleAccountFileSnapshot.worker();
        if(worker!=Thread.currentThread()||!live())throw new IOException("AccountUnavailable");
    }
    private interface Work{void run()throws Exception;}
    private void guard(Work work){
        if(worker==null)worker=Thread.currentThread();
        try{require();work.run();}catch(Exception|LinkageError e){hold(e.getMessage()==null?"CarryUnavailable":e.getMessage());}
    }
    private void hold(String reason){
        if(closed)return;String action=actionQuiet();Command old=active;active=null;++run;++tickets;
        try{if(old!=null)port.cancel(old);}finally{
            try{if(io!=null)io.close();}finally{try{if(store!=null)store.close();}finally{
                io=null;store=null;PurpleSyncOperations.CarryLease held=lease;lease=null;if(held!=null)held.close();
            }}
        }
        try{port.status(new Status(action,reason,false));}finally{if(!live())close();}
    }
    private String actionQuiet(){try{return store==null||store.journalBytes()==null?"Open":next();}catch(Exception e){return "Open";}}
    private boolean publish(String reason,boolean terminal)throws Exception {
        require();long before=run;port.status(new Status(store==null||store.journalBytes()==null?"Open":next(),reason,terminal,committed()));
        if(before!=run||!live())return false;require();return true;
    }
    private Committed committed()throws Exception {
        if(store==null||store.journalBytes()==null)return null;JSONObject j=journal();
        if(!j.optBoolean("destination_verified")||!j.optBoolean("mark_verified")||!j.optBoolean("state_committed")||!j.optBoolean("cache_completed")
                ||!(store.stateBytes()==null?j.getInt("mode")==2&&j.optString("next_state_hash").isEmpty():PurpleSyncRetainedFiles.hash(store.stateBytes()).equals(j.optString("next_state_hash"))))return null;
        JSONObject mark=j.getJSONObject("target_mark");
        return new Committed(origin,authority,j.getString("operation"),new Cache(Peer.parse(mark.getString("chat"),origin.userId),mark.getInt("id"),mark.getLong("date")));
    }
    private void open(boolean resume,boolean foreign)throws Exception {
        require();if(lease!=null)return;
        lease=PurpleSyncOperations.carry(port.operationKey(),owner);
        if(lease==null){hold("AccountBusy");return;}
        ++run;long captured=run;
        lease.quiesce();if(!live()||captured!=run)return;
        port.quiesce(()->port.worker(()->guard(()->{
            if(captured!=run||store!=null)return;
            if(!lease.quiescent()){hold("PublishersNotQuiescent");return;}
            store=port.open(this::live,foreign);require();
            if(!origin.accountKey().equals(store.accountKey())||!authority.equals(store.loginAuthority()))throw new IOException("AccountUnbound");
            if(!foreign)store.checkNotPaused(origin);
            if(opening!=null&&opening.has("openingStateStamp")){
                String actual=store.stateBytes()==null?"missing":PurpleSyncRetainedFiles.hash(store.stateBytes());
                if(!actual.equals(opening.getString("openingStateStamp")))throw new IOException("OpeningChanged");
            }
            if(opening!=null&&opening.optBoolean("openingCacheAbsent")&&store.openingCacheBytes()!=null)throw new IOException("OpeningChanged");
            if(opening!=null&&opening.has("openingCacheBytes")&&!java.util.Arrays.equals(store.openingCacheBytes(),PurpleSyncRetainedBases.decode(opening.getString("openingCacheBytes"))))throw new IOException("OpeningChanged");
            if(foreign){if(discardConfirmed)transition(store.discardInterrupted(true));return;}
            if(resume||store.journalBytes()!=null&&!store.terminal()){
                if(store.journalBytes()==null){hold("MissingJournal");return;}
                if(store.terminal()){finish();return;}
                transitionOnly(store.resume());
                if(accepted==null){hold("TargetFactsRequired");return;}
                io=new PurpleSyncChatMoveIO(store,origin,this::live);
                if(store.transport().optJSONObject("readers")==null||!store.transport().getJSONObject("readers").has("snapshot")){hold("ReadersBaselineMissing");return;}
                request("Fresh",null,0,null);
            } else request("Fresh",null,0,null);
        })));
    }
    void retry(){port.worker(()->guard(()->{if(active!=null)return;dispatched.clear();if(store==null){PurpleSyncOperations.CarryLease old=lease;lease=null;if(old!=null)old.close();open(opening==null,interrupted);}else request("Fresh",null,0,null);}));}
    private void request(String action,PurpleSyncChatMoveIO.Permit permit,int id,PurpleSyncChatMoveIO.ReadTicket read)throws Exception {
        require();if(active!=null)throw new IOException("RequestInFlight");
        if(action.equals("Effect")&&!dispatched.add(permit.key)){hold("Retry");return;}
        Command command=new Command(this,action,permit,id,read);active=command;
        if(!publish(action,false)||active!=command){if(!live())close();return;}
        port.dispatch(command,reply->port.worker(()->guard(()->complete(command,reply))));
    }
    void renew(Command command,PurpleSyncChatMoveIO.Fresh facts,PurpleSyncChatMoveIO.RecordReceipt source,Consumer<PurpleSyncChatMoveIO.Permit> done){
        port.worker(()->guard(()->{
            if(!matches(command)||!command.action.equals("Effect"))return;
            require();PurpleSyncChatMoveIO.Permit next=io.renew(command.currentPermit,facts,source);
            if(!matches(command)||!live())return;command.currentPermit=next;done.accept(next);
        }));
    }
    private boolean matches(Command command){
        return !closed&&active==command&&command.run==run&&command.ticket==tickets&&origin.equals(command.origin)&&authority.equals(command.authority);
    }
    private void complete(Command command,Reply reply)throws Exception {
        if(!matches(command))return;require();active=null;
        if(reply==null)throw new IOException("OutcomeUnknown");
        if(store.journalBytes()!=null) {
            JSONObject j=journal();if(!command.operation.equals(j.getString("operation"))||command.revision!=j.getLong("revision"))return;
        }
        switch(command.action){
        case "Fresh":
            fresh=reply.fresh;checkFresh();
            if(store.journalBytes()==null||store.terminal()&&opening!=null){
                if(accepted==null||!new PurpleSyncChatScreen.Candidate(origin,fresh.target.chat,accepted).same(new PurpleSyncChatScreen.Candidate(origin,fresh.target.chat,fresh.target)))throw new IOException("ReaderConsentChanged");
                JSONObject begin=copy(opening);begin.put("loginAuthority",authority.substring(0,32)).put("resolution",resolution(fresh.resolution))
                        .put("eligibility",eligibilityJson(accepted.eligibility())).put("oldRead",fresh.oldRead.name()).put("sourceAccess",reply.sourceAccess);
                transitionOnly(store.begin(begin));byte[] pending=store.pendingBytes();if(pending.length!=0)store.pendingIntent(pending,"Unknown");
                io=new PurpleSyncChatMoveIO(store,origin,this::live);
                io.acceptReaders(fresh,accepted,begin.optString("consentStream","config"));
            }else io.reconciliationFresh(fresh);
            drive();break;
        case "Source":
            sourceRecords=new ArrayList<>(reply.sourceRecords);preparePlan(reply);break;
        case "Pending":pending(command,reply);break;
        case "Effect":
            if(command.currentPermit.kind.equals("pending"))io.pendingReceipt(command.currentPermit);else io.recordReceipt(command.currentPermit,reply.response);
            request("Fresh",null,0,null);break;
        case "Destination":
            checkReplyFresh(reply);destination=PurpleSyncChatMoveIO.inventory(reply.destination,origin,target());
            if(command.read!=null)io.reconcile(command.read,reply.destination,fresh);
            playlist=playlist(reply,destination);
            transition(event("DestinationRead",json("destination",destination,"playlist",playlist)));break;
        case "Mark":
            checkReplyFresh(reply);JSONObject mark=journal().getInt("mode")==1||journal().getInt("mode")==2
                    ?io.existingMarkReadback(reply.markRoute,reply.response):io.markReadback(markKey(),reply.markRoute,reply.response);
            transition(event("MarkRead",json("mark",mark)));break;
        case "Cleanup":
            checkReplyFresh(reply);
            if(reply.sourceAccess.equals("ChatGone")&&fresh.oldRead==DirectRead.ChatGone) {
                transition(event("CleanupChatGone",json("noRequestInFlight",true,"mark",json("outcome","ChatGone","account",origin.accountKey(),"chat",source().token(),"id","0","date","0","text","","hasMedia",false,"ownership","Unknown"))));
            } else {
                destination=PurpleSyncChatMoveIO.inventory(reply.destination,origin,target());playlist=playlist(reply,destination);
                JSONObject candidates=scope();candidates.remove("journal");candidates.put("plan",blob(bytes(journal().getJSONObject("plan")))).put("destination",destination).put("playlist",playlist);
                PurpleSyncChatMoveCore.Result result=core("cleanupCandidates",candidates);
                if(!"Ready".equals(result.status)||result.fields.getJSONArray("references").length()!=0||journal().optBoolean("remove_old_mark_authorized")){hold("CleanupPolicyNotEnabled");return;}
                transition(event("CleanupCompleted",json("destination",destination,"playlist",playlist,"deleted",new JSONArray())));
            }
            break;
        default:throw new IOException("InvalidTransition");
        }
    }
    private void checkFresh()throws Exception {
        require();if(fresh==null||!origin.equals(fresh.origin)||!authority.equals(fresh.loginAuthority)||!fresh.fullResolution||fresh.resolution==null
                ||fresh.source==null||fresh.target==null||!origin.equals(fresh.source.origin)||!origin.equals(fresh.target.origin)
                ||!fresh.source.chat.token().equals(store.journalBytes()==null?opening.getString("source"):journal().getString("source"))
                ||!fresh.target.chat.token().equals(store.journalBytes()==null?opening.getString("target"):journal().getString("target")))throw new IOException("FreshScopeMismatch");
    }
    private void checkReplyFresh(Reply reply)throws Exception {fresh=reply.fresh;checkFresh();io.reconciliationFresh(fresh);}
    private void drive()throws Exception {
        require();if(!publish("Running",false))return;
        switch(next()){
        case "None":finish();break;
        case "ResolvePending":request("Pending",null,0,null);break;
        case "WritePendingState":case "CommitState":case "StageOwn":transition(store.commitPreparedState());break;
        case "RetirePendingStage":case "RetireOwnStage":transition(store.retireStage());break;
        case "ResolveAgain":transition(event("Resolution",json("resolution",resolution(fresh.resolution))));break;
        case "Plan":request("Source",null,0,null);break;
        case "Deliver":
            io.reserve(fresh,accepted,entropy);transitionOnly(event("DeliveryPrepared",new JSONObject()));deliverOrRead();break;
        case "VerifyDestination":deliverOrRead();break;
        case "PostMark":
            io.reserve(fresh,accepted,entropy);transitionOnly(event("MarkPostPrepared",new JSONObject()));deliverMark();break;
        case "ReadMark":deliverMark();break;
        case "PrepareCache":case "CompleteCache":
            boolean complete=next().equals("CompleteCache");store.writeCache(origin,accepted,complete);
            JSONObject mark=journal().getJSONObject("target_mark");
            transition(event(complete?"CacheCompleted":"CachePrepared",json("cache",json("account",origin.accountKey(),"operation",journal().getString("operation"),"mark",json("chat",mark.getString("chat"),"id",Integer.toString(mark.getInt("id")),"date",Long.toString(mark.getLong("date"))),"complete",complete))));break;
        case "Cleanup":request("Cleanup",null,0,null);break;
        default:throw new IOException("InvalidTransition");
        }
    }
    private void deliverOrRead()throws Exception {
        if(journal().getJSONObject("plan").optBoolean("playlist_required")){hold("PlaylistRequired");return;}
        if(!journal().optBoolean("delivery_attempted")){request("Destination",null,0,null);return;}
        if(store.transport().getJSONObject("entries").length()==0)io.reserve(fresh,accepted,entropy);
        JSONObject entries=store.transport().getJSONObject("entries");JSONArray missing=journal().getJSONArray("missing");
        for(String key:sortedKeys(entries)){
            JSONObject slot=entries.getJSONObject(key);String kind=slot.getString("kind");if(kind.equals("mark")||!contains(missing,slot.getString("hash")))continue;
            if(slot.getString("state").equals("prepared")){
                PurpleSyncChatMoveIO.RecordReceipt record=null;
                if(kind.equals("forward"))for(PurpleSyncChatMoveIO.RecordReceipt candidate:sourceRecords)if(candidate.id==slot.getInt("source_id")&&candidate.hash.equals(slot.getString("hash")))record=candidate;
                if(kind.equals("forward")&&record==null){request("Source",null,0,null);return;}
                request("Effect",io.authorize(kind,slot.getInt("source_id"),slot.getString("hash"),fresh,record),0,null);return;
            }
            if(slot.getString("state").equals("issued")||slot.getString("state").equals("unknown")){request("Destination",null,0,io.beginReconcile(key,fresh));return;}
        }
        request("Destination",null,0,null);
    }
    private void deliverMark()throws Exception {
        JSONObject j=journal();
        if(j.getInt("mode")==1||j.getInt("mode")==2){request("Mark",null,j.getJSONObject("target_mark").getInt("id"),null);return;}
        if(store.transport().getJSONObject("entries").length()==0)io.reserve(fresh,accepted,entropy);
        JSONObject slot=store.transport().getJSONObject("entries").optJSONObject(markKey());
        if(slot==null){hold("MarkReadbackRequired");return;}
        if(slot.getString("state").equals("prepared")){request("Effect",io.authorize("mark",0,slot.getString("hash"),fresh,null),0,null);return;}
        if(!slot.has("ack_id")){hold("MarkNeedsMatchedRequest");return;}
        request("Mark",null,slot.getInt("ack_id"),null);
    }
    private void pending(Command command,Reply reply)throws Exception {
        checkReplyFresh(reply);byte[] record=store.pendingBytes();if(record.length==0)throw new IOException("PendingMismatch");
        String intent=store.pendingIntent(record,reply.sendIntent);
        PurpleSyncChatMoveIO.PendingProof proof=reply.sourceAccess.equals("ChatGone")?null:io.consumePendingRead(command.pendingRead,reply.source,fresh);
        PurpleSyncChatMoveIO.RecordReceipt found=null;
        for(int i=0;reply.source!=null&&i<reply.source.count();++i){PurpleSyncChatMoveIO.RecordReceipt receipt=reply.source.receipt(i);if(receipt!=null&&Arrays.equals(receipt.bytes(),record))found=receipt;}
        JSONObject data=json("stagedRecord",blob(record),"retainedLocalText",core("ownVersion",json("record",blob(record))).fields.getJSONObject("ownVersion").getString("text"),"oldRead",fresh.oldRead.name());
        if(reply.sourceAccess.equals("Protected"))data.put("pendingAccess","Protected").put("pendingSendIntent",intent).put("pendingInventory",io.pendingInventory(proof));
        else if(found!=null){io.pendingInventory(proof);data.put("pendingReadback",found.message());}
        else if(reply.sourceAccess.equals("ChatGone")&&fresh.oldRead==DirectRead.ChatGone){}
        else if(reply.sourceAccess.equals("Readable")&&fresh.source.readable()&&fresh.source.eligibility().allowed()) {request("Effect",io.pendingPermit(fresh,null,proof),0,null);return;}
        else {hold("PendingReadbackRequired");return;}
        transition(event("PendingPrepared",data));
    }
    private void preparePlan(Reply reply)throws Exception {
        checkReplyFresh(reply);
        if(!next().equals("Plan")){drive();return;}
        JSONObject old=reply.sourceAccess.equals("ChatGone")?json("account",origin.accountKey(),"chat",source().token(),"complete",false,"messages",new JSONArray()):PurpleSyncChatMoveIO.inventory(reply.source,origin,source()),dest=PurpleSyncChatMoveIO.inventory(reply.destination,origin,target());
        JSONObject input=scope();input.remove("journal");JSONObject j=journal();
        input.put("mode",new String[]{"Switch","Follow","Fill","RepairMark"}[j.getInt("mode")]).put("access",reply.sourceAccess)
                .put("state",store.stateBytes()==null?JSONObject.NULL:blob(store.stateBytes())).put("preferenceToken",openingToken())
                .put("otherStagesPresent",false).put("eligibility",eligibilityJson(fresh.target.eligibility())).put("readerConsentAccepted",true)
                .put("oldInventory",old).put("newInventory",dest).put("cleanup",store.cleanupSettings()?"AllSettings":"None").put("cleanupAuthorized",store.cleanupSettings())
                .put("playlistsInUse",playlistUse(reply,old,dest));
        if(j.optJSONObject("target_mark")!=null)input.put("winner",cache(j.getJSONObject("target_mark")));
        if(store.stateBytes()!=null){byte[] own=store.ownBase();if(own.length!=0)input.put("ownVersion",core("ownVersion",json("record",blob(own))).fields.getJSONObject("ownVersion"));}
        PurpleSyncChatMoveCore.Result planned=core("plan",input);if(!planned.status.equals("Ready")){hold(planned.status);return;}
        if(parse(PurpleSyncRetainedBases.decode(planned.fields.getString("plan"))).optBoolean("playlist_required")){hold("PlaylistRequired");return;}
        transition(event("PlanPrepared",json("plan",planned.fields.getString("plan"))));
    }
    private boolean playlistUse(Reply reply,JSONObject old,JSONObject dest)throws Exception {
        if(reply.libraryPending)return true;
        if(store.stateBytes()!=null){JSONObject library=parse(store.stateBytes()).getJSONObject("streams").getJSONObject("library");if(library.getLong("seq")!=0||library.getLong("pending_seq")!=0)return true;}
        for(JSONObject inventory:new JSONObject[]{old,dest}){JSONArray messages=inventory.getJSONArray("messages");for(int i=0;i<messages.length();++i)if(messages.getJSONObject(i).getString("family").equals("Playlists"))return true;}
        return false;
    }
    private JSONObject playlist(Reply reply,JSONObject dest)throws Exception {
        JSONObject plan=journal().getJSONObject("plan");boolean used=plan.optBoolean("playlist_required")||reply.libraryPending;
        JSONArray messages=dest.getJSONArray("messages");for(int i=0;i<messages.length();++i)used|=messages.getJSONObject(i).getString("family").equals("Playlists");
        if(!used)return json("status","NotInUse","account",origin.accountKey(),"source",source().token(),"target",target().token(),"planStamp","","destinationStamp","");
        if(reply.verifiedPlaylist==null)throw new IOException("PlaylistRequired");
        JSONObject proof=copy(reply.verifiedPlaylist.proof);if(!proof.optString("status").equals("Verified")||!origin.accountKey().equals(proof.getString("account"))||!source().token().equals(proof.getString("source"))||!target().token().equals(proof.getString("target")))throw new IOException("PlaylistRequired");
        JSONObject verification=scope();verification.remove("journal");verification.put("plan",blob(bytes(plan))).put("destination",dest).put("playlist",proof);
        if(!core("verify",verification).status.equals("Ready"))throw new IOException("PlaylistRequired");
        return proof;
    }
    private String openingToken()throws Exception{return store.stateBytes()==null?blob(new byte[0]):blob(parse(store.stateBytes()).getString("binding_token").getBytes(StandardCharsets.UTF_8));}
    private PurpleSyncChatMoveCore.Result event(String kind,JSONObject payload)throws Exception {
        JSONObject request=scope();request.put("kind",kind);JSONObject e=core("event",request).fields.getJSONObject("event");
        for(Iterator<String> keys=payload.keys();keys.hasNext();){String key=keys.next();e.put(key,payload.get(key));}e.put("outcome","Success");return store.advance(e);
    }
    private void transitionOnly(PurpleSyncChatMoveCore.Result result)throws Exception {
        require();if(!result.fields.has("canonical")||PurpleSyncRetainedBases.decode(result.fields.getString("canonical")).length==0)throw new IOException(result.status);
        if(!result.status.equals("Valid")&&!(result.status.equals("Retry")&&next().equals("Deliver")&&retryReady()))throw new IOException(result.status);
    }
    private boolean retryReady()throws Exception {
        JSONObject entries=store.transport().optJSONObject("entries");if(entries==null||entries.length()==0)return true;
        JSONArray missing=journal().getJSONArray("missing");for(String key:sortedKeys(entries)){JSONObject slot=entries.getJSONObject(key);if(slot.getString("state").equals("prepared")&&contains(missing,slot.getString("hash")))return true;}return false;
    }
    private void transition(PurpleSyncChatMoveCore.Result result)throws Exception {transitionOnly(result);if(store.terminal())finish();else request("Fresh",null,0,null);}
    private void finish()throws Exception {if(!store.terminal())throw new IOException("CarryHeld");try{publish("Done",true);}finally{close();}}
    void skipCleanup(boolean confirmed){port.worker(()->guard(()->{if(!confirmed||active!=null||store==null)return;transition(event("SkipCleanup",json("skipCleanupConfirmed",true,"noRequestInFlight",true)));}));}
    @Override public void close(){
        if(closed)return;closed=true;++run;++tickets;Command old=active;active=null;
        port.worker(()->{
            try{if(old!=null)port.cancel(old);}finally{try{if(io!=null)io.close();}finally{try{if(store!=null)store.close();}finally{PurpleSyncOperations.CarryLease held=lease;lease=null;if(held!=null)held.close();}}}
        });
    }
    private String next()throws Exception{return core("next",scope()).fields.getString("nextAction");}
    private JSONObject journal()throws Exception{return parse(store.journalBytes());}
    private JSONObject scope()throws Exception {JSONObject j=journal();return json("account",origin.accountKey(),"source",j.getString("source"),"target",j.getString("target"),"journal",blob(store.journalBytes()));}
    private Peer source()throws Exception{return Peer.parse(journal().getString("source"),origin.userId);}
    private Peer target()throws Exception{return Peer.parse(journal().getString("target"),origin.userId);}
    private String markKey()throws Exception{return PurpleSyncChatMoveIO.key("mark",0,PurpleSyncRetainedFiles.hash(journal().getString("mark_text").getBytes(StandardCharsets.UTF_8)));}
    private static JSONObject resolution(ResolveInput input){Resolution r=resolve(input);JSONObject out=json("action",r.action.name());if(r.cache!=null)put(out,"cache",cache(r.cache));if(r.winner!=null)put(out,"winner",cache(r.winner));if(r.source!=null)put(out,"source",r.source.token());return out;}
    private static JSONObject cache(Cache c){return json("chat",c.chat.token(),"id",Integer.toString(c.markId),"date",Long.toString(c.markDate));}
    private static JSONObject cache(JSONObject c)throws Exception{return json("chat",c.getString("chat"),"id",Integer.toString(c.getInt("id")),"date",Long.toString(c.getLong("date")));}
    private static List<String> sortedKeys(JSONObject value){List<String> keys=new ArrayList<>();Iterator<String> it=value.keys();while(it.hasNext())keys.add(it.next());java.util.Collections.sort(keys);return keys;}
    private static boolean contains(JSONArray values,String value)throws Exception{for(int i=0;i<values.length();++i)if(values.getString(i).equals(value))return true;return false;}
    private static JSONObject parse(byte[] value)throws Exception{return new JSONObject(new String(value,StandardCharsets.UTF_8));}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static byte[] bytes(JSONObject value){return value.toString().getBytes(StandardCharsets.UTF_8);}
    private static String blob(byte[] value){return PurpleSyncRetainedBases.encode(value);}
    private static JSONObject json(Object... pairs){return PurpleSyncChatCore.json(pairs);}
    private static PurpleSyncChatMoveCore.Result core(String operation,JSONObject input){return PurpleSyncChatMoveCore.request(operation,bytes(input));}
}
