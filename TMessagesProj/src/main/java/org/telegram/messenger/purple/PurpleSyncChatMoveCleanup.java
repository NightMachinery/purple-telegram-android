package org.telegram.messenger.purple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatMoveCleanup implements AutoCloseable {
    static final boolean ACTIVATED=false;
    public static final class Progress {
        public final boolean pending,skipped,canAct,uncopiedDestination;
        private final PurpleSyncChatMove owner;
        private final long run;
        Progress(PurpleSyncChatMove owner,long run,boolean pending,boolean skipped,boolean canAct,boolean uncopied){
            this.owner=owner;this.run=run;this.pending=pending;this.skipped=skipped;this.canAct=canAct;uncopiedDestination=uncopied;
        }
        public void retry(){if(pending&&canAct)owner.cleanupAction(run,false);}
        public void skip(boolean confirmed){if(confirmed&&pending&&canAct)owner.cleanupAction(run,true);}
    }
    static boolean uncopied(JSONObject plan,JSONObject inventory)throws Exception {
        java.util.Set<String> carried=new java.util.HashSet<>();JSONArray required=plan.getJSONArray("required");
        for(int i=0;i<required.length();++i)carried.add(required.getString(i));
        if(!plan.optString("own_record_hash").isEmpty())carried.add(plan.getString("own_record_hash"));
        JSONArray records=core("select",json("inventory",inventory)).fields.getJSONArray("records");
        for(int i=0;i<records.length();++i){
            JSONObject record=records.getJSONObject(i),header=record.optJSONObject("header");
            if(header!=null&&header.optString("install").equals(plan.getString("owner_install"))
                    &&header.optString("device").equals(plan.getString("owner_device"))&&header.optString("stream").equals("config")
                    &&!carried.contains(record.getJSONObject("reference").getString("hash")))return true;
        }
        return false;
    }
    static final class Item {
        final String kind,key,hash;
        final Peer chat;
        final int id;
        final long date;
        private final JSONObject reference;
        private Item(String kind,Peer chat,int id,long date,String hash,JSONObject reference){
            this.kind=kind;this.chat=chat;this.id=id;this.date=date;this.hash=hash;this.reference=reference;
            key=hash((kind+":"+id+":"+hash).getBytes(StandardCharsets.UTF_8));
        }
    }
    static final class Read {
        final Item item;
        private final PurpleSyncChatMoveCleanup owner;
        private final Object epoch,generation;
        private final long revision,afterScan,afterRecord;
        private boolean consumed;
        private Read(PurpleSyncChatMoveCleanup owner,Item item)throws Exception {
            this.owner=owner;this.item=item;epoch=owner.store.permitEpoch();revision=owner.journal().getLong("revision");
            generation=owner.store.invalidateSlot("cleanup:"+item.key);afterScan=PurpleSyncScanner.readSequence();
            afterRecord=PurpleSyncChatMoveIO.RecordReceipt.observation();
        }
    }
    static final class Permit {
        final Item item;
        final PurpleSyncChatOrigin origin;
        final long renewal;
        private final PurpleSyncChatMoveCleanup owner;
        private final Object epoch,generation;
        private final long revision;
        private final TLRPC.InputPeer peer;
        private final AtomicBoolean claimed=new AtomicBoolean();
        private Permit(Read read,TLRPC.InputPeer peer,long renewal){
            owner=read.owner;item=read.item;epoch=read.epoch;generation=read.generation;revision=read.revision;
            origin=owner.origin;this.peer=PurpleSyncChatMoveRequests.peer(peer);this.renewal=renewal;
        }
        boolean operationCurrent(){return !owner.closed&&owner.current.getAsBoolean()&&owner.store.permitCurrent(epoch);}
        boolean current(){return operationCurrent()&&owner.store.slotCurrent("cleanup:"+item.key,generation);}
        TLObject request(){return current()?PurpleSyncChatMoveRequests.delete(item.chat,peer,item.id):null;}
        boolean claim(TLObject request){return current()&&matches(request)&&claimed.compareAndSet(false,true);}
        private boolean matches(TLObject request){
            if(item.chat.kind==PeerKind.Channel){
                if(!(request instanceof TLRPC.TL_channels_deleteMessages))return false;
                TLRPC.TL_channels_deleteMessages value=(TLRPC.TL_channels_deleteMessages)request;
                TLRPC.InputChannel expected=PurpleSyncChatRequests.channel(peer);
                return expected!=null&&value.channel!=null&&value.channel.channel_id==expected.channel_id
                        &&value.channel.access_hash==expected.access_hash&&value.id.size()==1&&value.id.get(0)==item.id;
            }
            if(!(request instanceof TLRPC.TL_messages_deleteMessages))return false;
            TLRPC.TL_messages_deleteMessages value=(TLRPC.TL_messages_deleteMessages)request;
            return value.revoke&&value.id.size()==1&&value.id.get(0)==item.id;
        }
    }
    interface WritePort extends PurpleRpcRequest.Port {
        void renew(Permit previous,Consumer<Permit> done);
        void apply(PurpleSyncChatOrigin origin,TLObject response);
    }
    static final class Effect implements AutoCloseable {
        private Permit permit;
        private final PurpleRpcRequest request;
        private boolean closed,completed,renewing;
        private long ticket,retireAt;
        Effect(Permit opening,WritePort port,PurpleRpcRequest.Reply done){
            if(opening==null||!opening.current())throw new IllegalArgumentException("StaleCleanupPermit");
            permit=opening;TLObject payload=opening.request();
            request=new PurpleRpcRequest(new PurpleRpcRequest.Stream(),new PurpleRpcRequest.Port(){
                public long now(){return port.now();}
                public boolean current(){return !closed&&port.current()&&opening.operationCurrent()&&(retireAt==0||port.now()<retireAt);}
                public void dispatch(Runnable task){port.dispatch(task);}
                public void admit(TLObject outgoing,Consumer<Boolean> grant){
                    Permit previous=permit;long run=++ticket;renewing=true;
                    port.renew(previous,next->{
                        if(closed||retireAt!=0||run!=ticket)return;++ticket;renewing=false;
                        boolean valid=successor(previous,next)&&next.matches(outgoing);
                        if(valid)permit=next;
                        grant.accept(valid);
                    });
                }
                public int send(TLObject outgoing,PurpleRpcRequest.Reply reply){
                    if(!permit.claim(outgoing))throw new IllegalStateException("StaleCleanupPermit");
                    return port.send(outgoing,reply);
                }
                public void cancel(int token){port.cancel(token);}
                public void delay(Runnable task,long millis){port.delay(task,millis);}
                public void cancelDelay(Runnable task){port.cancelDelay(task);}
                public void waiting(PurpleRpcRetry.Transient value){
                    if(value.kind==PurpleRpcRetry.Kind.Timeout&&renewing&&retireAt==0)retireAt=value.retryAt;
                    port.waiting(value);
                }
            },payload,60000,(response,error)->finish(opening,port,done,response,error),ended->{
                if(ended.abandoned()){
                    TLRPC.TL_error error=new TLRPC.TL_error();error.code=-506;error.text="CLEANUP_RETIRED";
                    finish(opening,port,done,null,error);
                }
            });
        }
        private void finish(Permit opening,WritePort port,PurpleRpcRequest.Reply done,TLObject response,TLRPC.TL_error error){
            if(completed)return;completed=true;closed=true;++ticket;
            try{if(error==null&&response!=null)port.apply(opening.origin,response);}
            catch(RuntimeException failure){response=null;}
            done.accept(response,error);
        }
        void start(){if(!closed)request.start();}
        @Override public void close(){closed=true;++ticket;request.cancel();}
    }
    static boolean successor(Permit previous,Permit next){
        return previous!=null&&next!=null&&next.owner==previous.owner&&next.epoch==previous.epoch
                &&next.revision==previous.revision&&next.item.key.equals(previous.item.key)&&next.origin.equals(previous.origin)
                &&next.renewal>previous.renewal&&next.current();
    }
    private final PurpleSyncChatMoveStore store;
    private final PurpleSyncChatOrigin origin;
    private final BooleanSupplier current;
    private final List<Item> items=new ArrayList<>();
    private boolean closed;
    private long renewal;
    PurpleSyncChatMoveCleanup(PurpleSyncChatMoveStore store,PurpleSyncChatOrigin origin,BooleanSupplier current){this.store=store;this.origin=origin;this.current=current;}
    private void check()throws IOException {PurpleAccountFileSnapshot.worker();require(!closed&&current.getAsBoolean(),"CleanupCanceled");store.witnessCleanup(origin);}
    private JSONObject journal()throws Exception{return new JSONObject(new String(store.journalBytes(),StandardCharsets.UTF_8));}
    private JSONObject scope()throws Exception {JSONObject j=journal();return json("account",origin.accountKey(),"source",j.getString("source"),"target",j.getString("target"),"journal",blob(store.journalBytes()));}
    private JSONObject verified(PurpleSyncChatMoveIO.Fresh fresh,PurpleSyncInventory destination,JSONObject playlist)throws Exception {
        check();JSONObject j=journal();Peer source=Peer.parse(j.getString("source"),origin.userId),target=Peer.parse(j.getString("target"),origin.userId);
        require(fresh!=null&&origin.equals(fresh.origin)&&store.loginAuthority().equals(fresh.loginAuthority)&&fresh.fullResolution
                &&fresh.source!=null&&fresh.target!=null&&origin.equals(fresh.source.origin)&&origin.equals(fresh.target.origin)
                &&source.equals(fresh.source.chat)&&target.equals(fresh.target.chat),"CleanupScopeChanged");
        require(fresh.target.readable()&&fresh.target.eligibility().allowed(),"CleanupRightsChanged");
        JSONObject inventory=PurpleSyncChatMoveIO.inventory(destination,origin,target);
        JSONObject request=scope();request.remove("journal");request.put("plan",blob(j.getJSONObject("plan").toString().getBytes(StandardCharsets.UTF_8)))
                .put("destination",inventory).put("playlist",playlist);
        require(core("verify",request).status.equals("Ready"),"CleanupDestinationUnverified");
        return inventory;
    }
    void prepare(PurpleSyncChatMoveIO.Fresh fresh,PurpleSyncInventory destination,JSONObject playlist)throws Exception {
        JSONObject inventory=verified(fresh,destination,playlist),j=journal();
        JSONObject request=scope();request.remove("journal");request.put("plan",blob(j.getJSONObject("plan").toString().getBytes(StandardCharsets.UTF_8)))
                .put("destination",inventory).put("playlist",playlist);
        JSONArray refs=core("cleanupCandidates",request).fields.getJSONArray("references");items.clear();
        JSONArray frozen=j.getJSONObject("plan").getJSONArray("cleanup_candidates");
        for(int i=0;i<refs.length();++i){
            JSONObject ref=refs.getJSONObject(i);long date=0;
            for(int k=0;k<frozen.length();++k){JSONObject candidate=frozen.getJSONObject(k),r=candidate.getJSONObject("reference");
                if(r.getInt("message_id")==ref.getInt("id")&&r.getString("hash").equals(ref.getString("hash")))date=candidate.getLong("date");}
            require(date>0,"InvalidCleanupCandidate");items.add(new Item("record",Peer.parse(ref.getString("chat"),origin.userId),ref.getInt("id"),date,ref.getString("hash"),ref));
        }
        JSONObject mark=j.optJSONObject("old_mark");
        if(j.optBoolean("remove_old_mark_authorized")&&mark!=null&&mark.getInt("id")>0){
            require(j.getInt("mode")==0,"OldMarkNotAuthorized");Peer chat=Peer.parse(mark.getString("chat"),origin.userId);
            String identity="mark:"+chat.token()+":"+mark.getInt("id")+":"+mark.getLong("date");
            items.add(new Item("mark",chat,mark.getInt("id"),mark.getLong("date"),hash(identity.getBytes(StandardCharsets.UTF_8)),null));
        }
    }
    Item next()throws Exception {
        check();JSONObject entries=store.cleanup().getJSONObject("entries");
        for(Item item:items){JSONObject entry=entries.optJSONObject(item.key);if(entry==null||!entry.getString("state").equals("absent"))return item;}
        return null;
    }
    Read read(Item item)throws Exception {check();require(items.stream().anyMatch(v->v.key.equals(item.key)),"CleanupScopeChanged");return new Read(this,item);}
    Permit inspect(Read read,PurpleSyncChatMoveIO.Fresh fresh,PurpleSyncInventory destination,JSONObject playlist,
            PurpleSyncChatIO route,TLObject response,PurpleSyncChatMoveIO.RecordReceipt record)throws Exception {
        JSONObject inventory=verified(fresh,destination,playlist),j=journal();
        require(read!=null&&read.owner==this&&!read.consumed&&store.permitCurrent(read.epoch)
                &&read.revision==j.getLong("revision")&&read.generation==store.slotGeneration("cleanup:"+read.item.key)
                &&destination.readSequence()>read.afterScan,"StaleCleanupRead");
        Item item=read.item;require(route!=null&&origin.equals(route.origin)&&item.chat.equals(route.chat),"WrongCleanupChat");
        PurpleSyncClient.Lookup lookup=route.lookup(response,null,item.id,m->new PurpleSyncClient.Message(m.id,true,false,false,"",null,route.chat,0,0,0,null));
        if(lookup.status==PurpleSyncClient.LookupStatus.Missing){
            TLRPC.Message empty=((TLRPC.messages_Messages)response).messages.get(0);
            require((empty.flags&1)!=0&&empty.peer_id!=null,"UnscopedCleanupAbsence");
            save(item,"absent",null);read.consumed=true;store.invalidateSlot("cleanup:"+item.key);return null;
        }
        require(lookup.status==PurpleSyncClient.LookupStatus.Found&&fresh.source.readable()&&fresh.source.eligibility().allowed()
                &&PurpleSyncChatRequests.matches(item.chat,fresh.sourcePeer),"CleanupExactRequired");
        JSONObject request=scope();request.put("destination",inventory).put("playlist",playlist);byte[] body;
        if(item.kind.equals("record")){
            require(record!=null&&record.observation>read.afterRecord&&origin.equals(record.origin)&&item.chat.equals(record.chat)
                    &&record.id==item.id&&record.date==item.date&&record.hash.equals(item.hash)&&record.family.equals("Settings")
                    &&Arrays.equals(record.response(),PurpleSyncChatMoveRequests.snapshot(response)),"CleanupRecordChanged");
            request.put("message",record.message());body=record.bytes();
        }else{
            TLRPC.Message message=((TLRPC.messages_Messages)response).messages.get(0);
            require(message.getClass()==TLRPC.TL_message.class&&message.date==item.date&&route.ownership(message)==Ownership.Own
                    &&(message.media==null||message.media.getClass()==TLRPC.TL_messageMediaEmpty.class)&&parseMark(message.message,false,Ownership.Own).status==MarkStatus.Supported,"CleanupMarkChanged");
            request.put("mark",json("outcome","Valid","account",origin.accountKey(),"chat",item.chat.token(),"id",Integer.toString(item.id),
                    "date",Long.toString(item.date),"text",message.message,"hasMedia",false,"ownership","Own"));body=message.message.getBytes(StandardCharsets.UTF_8);
        }
        require(core(item.kind.equals("record")?"checkDeletion":"checkOldMarkDeletion",request).fields.optBoolean("allowed"),"CleanupNotAuthorized");
        save(item,"issued",body);read.consumed=true;
        return new Permit(read,fresh.sourcePeer,++renewal);
    }
    private void save(Item item,String state,byte[] body)throws Exception {
        check();JSONObject ledger=store.cleanup(),entries=ledger.getJSONObject("entries"),previous=entries.optJSONObject(item.key);
        if(previous!=null){
            byte[] before=PurpleSyncRetainedBases.decode(previous.getString("body"));
            if(body!=null&&before.length!=0)require(Arrays.equals(before,body),"CleanupRecordChanged");
            if(body==null)body=before;
        }
        if(body==null)body=new byte[0];
        entries.put(item.key,json("kind",item.kind,"id",item.id,"date",Long.toString(item.date),"hash",item.hash,"state",state,
                "body",blob(body),"bodyHash",hash(body)));
        store.cleanup(ledger);
    }
    JSONArray deleted()throws Exception {
        check();JSONArray result=new JSONArray();JSONObject entries=store.cleanup().getJSONObject("entries");
        for(Item item:items)if(item.reference!=null&&entries.has(item.key)&&entries.getJSONObject(item.key).getString("state").equals("absent"))result.put(new JSONObject(item.reference.toString()));
        return result;
    }
    static JSONArray persistedDeleted(PurpleSyncChatMoveStore store)throws Exception {
        JSONObject j=new JSONObject(new String(store.journalBytes(),StandardCharsets.UTF_8)),entries=store.cleanup().getJSONObject("entries");
        JSONArray result=new JSONArray();java.util.Iterator<String> keys=entries.keys();
        while(keys.hasNext()){
            JSONObject entry=entries.getJSONObject(keys.next());
            if(entry.getString("state").equals("absent"))result.put(json("account",store.accountKey(),"chat",j.getString("source"),
                    "id",Integer.toString(entry.getInt("id")),"hash",entry.getString("kind").equals("record")?entry.getString("hash"):""));
        }
        return result;
    }
    JSONObject oldMarkAbsent()throws Exception {
        check();JSONObject entries=store.cleanup().getJSONObject("entries");
        for(Item item:items)if(item.kind.equals("mark")&&entries.has(item.key)&&entries.getJSONObject(item.key).getString("state").equals("absent"))
            return json("outcome","Absent","account",origin.accountKey(),"chat",item.chat.token(),"id",Integer.toString(item.id),"date",Long.toString(item.date),"text","","hasMedia",false,"ownership","Unknown");
        return null;
    }
    @Override public void close(){closed=true;for(Item item:items)store.invalidateSlot("cleanup:"+item.key);items.clear();}
    private static void require(boolean value,String reason)throws IOException {if(!value)throw new IOException(reason);}
    private static String blob(byte[] value){return PurpleSyncRetainedBases.encode(value);}
    private static String hash(byte[] value){return PurpleSyncRetainedFiles.hash(value);}
    private static JSONObject json(Object... pairs){return PurpleSyncChatCore.json(pairs);}
    private static PurpleSyncChatMoveCore.Result core(String op,JSONObject value){return PurpleSyncChatMoveCore.request(op,value.toString().getBytes(StandardCharsets.UTF_8));}
}
