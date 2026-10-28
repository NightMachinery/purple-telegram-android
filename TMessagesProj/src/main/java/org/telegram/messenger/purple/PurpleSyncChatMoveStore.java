package org.telegram.messenger.purple;

import androidx.annotation.Keep;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

@Keep
public final class PurpleSyncChatMoveStore implements AutoCloseable {
    private static final int LIMIT = 4194304;
    private final File root, stateFile, journalFile, stageFile, cacheFile;
    private final PurpleAccountSyncStore owner;
    private final String account, authority, device;
    private final byte[] binding;
    private final int slot;
    private final BooleanSupplier current;
    private boolean production, interrupted, newEnvelope;
    private String envelopeAuthority;
    private Thread thread;
    private PurpleAccountFileSnapshot.Durable state, journal;
    private JSONObject metadata, objects = new JSONObject(), identities = new JSONObject();
    private byte[] canonical;
    private long objectBytes;
    private int fault;
    private File faultFile;
    private int faultFor(File path){return faultFile==null||faultFile.equals(path)?fault:0;}
    private volatile Object permitEpoch = new Object();
    private final java.util.Map<String,Object> liveSlots = new java.util.HashMap<>();
    private final java.util.Map<String,Object> slotGenerations = new java.util.HashMap<>();
    Object slotGeneration(String key) { return slotGenerations.computeIfAbsent(key,ignored->new Object()); }
    Object invalidateSlot(String key) {Object generation=new Object();slotGenerations.put(key,generation);return generation;}
    Object permitEpoch() { return permitEpoch; }
    boolean permitCurrent(Object epoch) { return thread != null && epoch == permitEpoch; }
    boolean claimSlot(String key,Object token) { if (liveSlots.containsKey(key)) return liveSlots.get(key) == token; liveSlots.put(key,token); return true; }
    void releaseSlot(String key,Object token) { if (liveSlots.get(key) == token) liveSlots.remove(key); }
    boolean slotLive(String key) { return liveSlots.containsKey(key); }
    String loginAuthority() { return authority; }
    String journalLoginAuthority() { return authority.substring(0,32); }
    private boolean newCleanupSettings;
    boolean cleanupSettings() throws IOException {unchanged();return metadata!=null&&metadata.optBoolean("cleanupSettings");}
    String accountKey() { return account; }
    boolean terminal() { return metadata!=null&&!metadata.optBoolean("holdsWrites",true); }
    JSONObject transport() throws IOException {
        unchanged();
        try { return metadata == null || !metadata.has("transport") ? new JSONObject() : new JSONObject(metadata.getJSONObject("transport").toString()); }
        catch (Exception e) { throw failure(e); }
    }
    void transport(JSONObject value,boolean checkOnly) throws IOException {
        try {
            unchanged(); if (canonical == null) throw new IOException("InvalidTransition");
            JSONObject result = codec("wrap", json("wrapper",blob(journal.bytes),"journal",blob(canonical),
                    "objects",objects,"send_identities",identities,"transport",value));
            if (!checkOnly) {
                journal = write(journalFile,journal,unblob(result,"bytes"),LIMIT); metadata=result;
                Iterator<String> keys=value.getJSONObject("entries").keys();
                while(keys.hasNext())slotGenerations.put(keys.next(),new Object());
            }
        } catch (Exception e) { close(); throw failure(e); }
    }


    PurpleSyncChatMoveStore(PurpleSyncFileContext context, String device) throws Exception {
        this(context.files.syncDirectory, new PurpleAccountSyncStore(context), context.origin.slot,
                context.origin.key, PurpleAccountBinding.read(context.origin.slot).token,
                PurpleSyncChatCache.authority(PurpleArchiveLogin.context(context.origin.slot)), device,
                new File(PurpleAccountFiles.root(), "sync-chat/" + context.origin.key + ".json"),
                () -> context.current());
        production = true;
    }
    PurpleSyncChatMoveStore(File root, PurpleAccountSyncStore owner, int slot, String account,
            String binding, String authority, String device, File cacheFile, BooleanSupplier current) {
        this.root = root; this.owner = owner; this.slot = slot; this.account = account;
        this.binding = binding == null ? new byte[0] : binding.getBytes(StandardCharsets.UTF_8);
        this.authority = authority; envelopeAuthority=authority; this.device = device; this.cacheFile = cacheFile; this.current = current;
        stateFile = new File(root, "state.json"); journalFile = new File(root, "carry.json");
        stageFile = new File(root, "pending/config.json");
    }
    private static JSONObject inspectRetained(byte[] wrapper,String account,byte[] binding)throws Exception {
        JSONObject raw=new JSONObject(new String(wrapper,StandardCharsets.UTF_8));
        JSONObject retained=raw.getJSONObject("journal");
        if(raw.optString("binding_sha256").equals(hash(new byte[0]))&&retained.getInt("mode")==2&&retained.getString("state_hash").isEmpty())binding=new byte[0];
        JSONObject request=json("account",account,"authority",raw.getString("login_authority"),"binding",blob(binding),"wrapper",blob(wrapper));
        JSONObject result=new JSONObject(new String(codecNative("inspect",bytes(request)),StandardCharsets.UTF_8));
        if(!result.optString("status").equals("Ready"))throw new IOException(result.optString("status","InvalidState"));
        return result;
    }
    private static JSONObject allObjects(File root)throws Exception {
        File directory=new File(root,"carry-objects");JSONObject found=new JSONObject();
        if(PurpleAccountFileSnapshot.directory(directory)==0)return found;
        String identity=PurpleAccountFileSnapshot.durableDirectoryIdentity(directory);String[] names=directory.list();
        if(identity.equals("missing")||names==null||names.length>1024)throw new IOException("InvalidRetention");
        long total=0;
        for(String name:names) {
            if(!name.matches("(state|record|text)-[0-9a-f]{64}\\.json"))throw new IOException("AmbiguousFiles");
            String kind=name.substring(0,name.indexOf('-')),hash=name.substring(name.indexOf('-')+1,name.length()-5);
            int limit=kind.equals("state")?LIMIT:262144;File path=new File(directory,name);
            PurpleAccountFileSnapshot.Durable object=PurpleAccountFileSnapshot.durable("read",path,null,null,limit,0);
            if(object.bytes==null||!hash.equals(hash(object.bytes))||found.has(hash))throw new IOException("InvalidState");
            total+=object.bytes.length;if(total>67108864)throw new IOException("RetentionFull");
            found.put(hash,kind);PurpleAccountFileSnapshot.durable("barrier",path,object,null,limit,0);
        }
        if(!identity.equals(PurpleAccountFileSnapshot.durableDirectoryIdentity(directory)))throw new IOException("Changed");
        return found;
    }
    private static void references(JSONObject wrapper,JSONObject found)throws Exception {
        JSONObject needed=wrapper.getJSONObject("objects");Iterator<String> keys=needed.keys();
        while(keys.hasNext()){String hash=keys.next();if(!needed.getString(hash).equals(found.optString(hash)))throw new IOException("InvalidState");}
    }
    private static long histories(File root,String account,byte[] binding,JSONObject found)throws Exception {
        File directory=new File(root,"carry-history");if(PurpleAccountFileSnapshot.directory(directory)==0)return 0;
        String identity=PurpleAccountFileSnapshot.durableDirectoryIdentity(directory);String[] names=directory.list();
        if(identity.equals("missing")||names==null||names.length>256)throw new IOException("InvalidRetention");long total=0;
        for(String name:names) {
            if(!name.matches("[0-9a-f]{64}\\.json"))throw new IOException("AmbiguousFiles");File path=new File(directory,name);
            PurpleAccountFileSnapshot.Durable file=PurpleAccountFileSnapshot.durable("read",path,null,null,LIMIT,0);
            if(file.bytes==null||!name.equals(hash(file.bytes)+".json"))throw new IOException("InvalidRetention");
            total+=file.bytes.length;if(total>67108864)throw new IOException("RetentionFull");
            JSONObject inspected=inspectRetained(file.bytes,account,binding);
            if(inspected.optBoolean("holdsWrites",true))throw new IOException("InvalidRetention");references(inspected,found);
            PurpleAccountFileSnapshot.durable("barrier",path,file,null,LIMIT,0);
        }
        if(!identity.equals(PurpleAccountFileSnapshot.durableDirectoryIdentity(directory)))throw new IOException("Changed");return total;
    }
    static boolean completed(File root,String expectedAccount) {
        try {
            PurpleAccountFileSnapshot.worker();PurpleCore.ensureLoaded();File path=new File(root,"carry.json");
            PurpleAccountFileSnapshot.Durable file=PurpleAccountFileSnapshot.durable("read",path,null,null,LIMIT,0);
            if(file.bytes==null)return false;
            JSONObject raw=new JSONObject(new String(file.bytes,StandardCharsets.UTF_8));String account=raw.getString("account");
            if(expectedAccount!=null&&!expectedAccount.equals(account))return false;
            byte[] state=PurpleAccountFileSnapshot.durable("read",new File(root,"state.json"),null,null,LIMIT,0).bytes;
            byte[] binding=(state==null?"":new JSONObject(new String(state,StandardCharsets.UTF_8)).getString("binding_token")).getBytes(StandardCharsets.UTF_8);
            JSONObject inspected=inspectRetained(file.bytes,account,binding);
            if(inspected.optBoolean("holdsWrites",true))return false;
            JSONObject found=allObjects(root);references(inspected,found);histories(root,account,binding,found);
            File cache=new File(root,"carry-cache.json");PurpleAccountFileSnapshot.Durable saved=PurpleAccountFileSnapshot.durable("read",cache,null,null,65536,0);
            if(saved.bytes!=null){JSONObject value=new JSONObject(new String(saved.bytes,StandardCharsets.UTF_8));if(value.getInt("version")!=1||!account.equals(value.getString("account")))return false;PurpleAccountFileSnapshot.durable("barrier",cache,saved,null,65536,0);}
            PurpleAccountFileSnapshot.durable("barrier",path,file,null,LIMIT,0);return true;
        }catch(Exception|LinkageError e){return false;}
    }
    static boolean artifacts(File root) {
        if (PurpleAccountFileSnapshot.directory(root) == 0) return false;
        String[] names = root.list();
        if (names == null) return true;
        for (String name : names) if (name.startsWith("carry") || name.startsWith(".carry-")) return true;
        return false;
    }
    void openInterrupted() throws IOException { interrupted=true;open(); }
    void open() throws IOException {
        PurpleAccountFileSnapshot.worker();
        if (thread != null) throw new IOException("InvalidTransition");
        thread = Thread.currentThread();
        try {
            requireCurrent();
            PurpleAccountSyncStore.Result locked = owner.openForCarry(slot, device);
            if (locked.status != PurpleAccountSyncStore.Status.Ready) throw new IOException(locked.status.name());
            if (!owner.carryLockCurrent()) throw new IOException("Changed");
            state = read(stateFile, LIMIT); journal = read(journalFile, LIMIT);
            knownPaths();
            if (state.bytes != null) codec("state", json("bytes", blob(state.bytes)));
            if (journal.bytes == null) {
                if (PurpleAccountFileSnapshot.directory(new File(root, "carry-objects")) != 0
                        || PurpleAccountFileSnapshot.read(new File(root, "carry-cache.json"), 65536).status != PurpleAccountFileSnapshot.Status.Missing)
                    throw new IOException("OrphanOperation");
                requireCurrent();
                return;
            }
            JSONObject raw=new JSONObject(new String(journal.bytes,StandardCharsets.UTF_8));envelopeAuthority=raw.getString("login_authority");
            if(!envelopeAuthority.matches("[0-9a-f]{64}")||interrupted&&envelopeAuthority.equals(authority))throw new IOException("InvalidTransition");
            metadata=inspectRetained(journal.bytes,account,binding);
            if(!interrupted&&!envelopeAuthority.equals(authority)&&metadata.optBoolean("holdsWrites",true))throw new IOException("AccountUnbound");
            canonical = unblob(metadata, "journal"); objects = metadata.getJSONObject("objects"); identities = metadata.getJSONObject("send_identities");
            validateObjects();
            barrier(journalFile, journal, LIMIT);
            requireCurrent();
        } catch (Exception e) { close(); throw failure(e); }
    }
    private void requireCurrent() throws IOException {
        PurpleAccountFileSnapshot.worker();
        Object openingEpoch=permitEpoch;
        if (thread != Thread.currentThread() || !current.getAsBoolean() || thread != Thread.currentThread()
                || openingEpoch!=permitEpoch) throw new IOException("AccountUnbound");
        if (production) {
            try { if (!authority.equals(PurpleSyncChatCache.authority(PurpleArchiveLogin.context(slot)))) throw new IOException("AccountUnbound"); }
            catch (Exception e) { throw new IOException("AccountUnbound", e); }
        }
    }
    private void unchanged() throws IOException {
        requireCurrent();
        if (!owner.carryLockCurrent() || state == null || journal == null
                || !state.same(read(stateFile, LIMIT)) || !journal.same(read(journalFile, LIMIT))) throw new IOException("Changed");
        knownPaths();
    }
    private void knownPaths() throws IOException {
        String[] entries = root.list(); if (entries == null) throw new IOException("AmbiguousFiles");
        for (String name : entries) {
            File path = new File(root, name);
            if (name.equals("pending")) {
                if (PurpleAccountFileSnapshot.directory(path) != 1 || !path.getCanonicalFile().equals(path.getAbsoluteFile())
                        || PurpleAccountFileSnapshot.durableDirectoryIdentity(path).equals("missing")) throw new IOException("AmbiguousFiles");
                String[] pending = path.list(); if (pending == null) throw new IOException("AmbiguousFiles");
                for (String item : pending) { if (!item.equals("config.json")) throw new IOException("AmbiguousFiles"); read(new File(path, item), 262144); }
            } else if (name.equals("carry-objects") || name.equals("retained-bases") || name.equals("carry-history")) {
                if (PurpleAccountFileSnapshot.directory(path) != 1 || !path.getCanonicalFile().equals(path.getAbsoluteFile())
                        || PurpleAccountFileSnapshot.durableDirectoryIdentity(path).equals("missing")) throw new IOException("AmbiguousFiles");
            } else if (name.equals("lock")) {
                PurpleAccountFileSnapshot.durable("lock", path, null, null, LIMIT, 0);
            } else if (Arrays.asList("state.json", "auto.json", "carry.json", "carry-cache.json", "sync-chat-setup.json", "sync-chat-setup-create.json", "sync-chat-setup-mark.json", "sync-chat-move-create.json").contains(name)) read(path, LIMIT);
            else throw new IOException("AmbiguousFiles");
        }
        if (!PurpleSyncRetainedFiles.valid(root)) throw new IOException("InvalidRetention");
    }
    private PurpleAccountFileSnapshot.Durable read(File path, int limit) throws IOException {
        return PurpleAccountFileSnapshot.durable("read", path, null, null, limit, 0);
    }
    private PurpleAccountFileSnapshot.Durable barrier(File path, PurpleAccountFileSnapshot.Durable value, int limit) throws IOException {
        return PurpleAccountFileSnapshot.durable("barrier", path, value, null, limit, faultFor(path));
    }
    private PurpleAccountFileSnapshot.Durable write(File path, PurpleAccountFileSnapshot.Durable expected, byte[] bytes, int limit) throws IOException {
        requireCurrent();
        PurpleAccountFileSnapshot.Durable written = PurpleAccountFileSnapshot.durable("write", path, expected, bytes, limit, faultFor(path));
        requireCurrent();
        return written;
    }
    private JSONObject codec(String operation, JSONObject input) throws IOException {
        try {
            input.put("account", account).put("binding", blob(binding)).put("authority", envelopeAuthority);
            PurpleCore.ensureLoaded();
            byte[] response = codecNative(operation, bytes(input));
            if (response == null) throw new IOException("InvalidState");
            JSONObject value = new JSONObject(new String(response, StandardCharsets.UTF_8));
            if (!"Ready".equals(value.getString("status"))) throw new IOException(value.getString("status"));
            return value;
        } catch (Exception e) { throw failure(e); }
    }
    private void validateObjects() throws Exception {
        JSONObject found=allObjects(root);references(metadata,found);histories(root,account,binding,found);
        objectBytes=0;Iterator<String> keys=found.keys();
        while(keys.hasNext()){String hash=keys.next(),kind=found.getString(hash);objectBytes+=read(new File(root,"carry-objects/"+kind+"-"+hash+".json"),kind.equals("state")?LIMIT:262144).bytes.length;}
        objects=found;
    }
    private String storeObject(String kind, byte[] bytes) throws Exception {
        unchanged();
        int limit = kind.equals("state") ? LIMIT : 262144;
        if (bytes == null || bytes.length > limit) throw new IOException("RetentionFull");
        String hash = hash(bytes);
        if (objects.has(hash) && !kind.equals(objects.getString(hash))) throw new IOException("InvalidState");
        if (!objects.has(hash) && (objects.length() == 1024 || objectBytes + bytes.length > 67108864)) throw new IOException("RetentionFull");
        File directory = new File(root, "carry-objects"); PurpleAccountFileSnapshot.durableDirectory(directory);
        File path = new File(directory, kind + "-" + hash + ".json");
        PurpleAccountFileSnapshot.Durable opening = read(path, limit);
        if (objects.has(hash) && opening.bytes == null || opening.bytes != null && !Arrays.equals(opening.bytes, bytes)) throw new IOException("Changed");
        if (opening.bytes == null) write(path, opening, bytes, limit); else barrier(path, opening, limit);
        if (!objects.has(hash)) objectBytes += bytes.length;
        objects.put(hash, kind); return hash;
    }
    private byte[] object(String kind, String hash) throws Exception {
        if (!hash.matches("[0-9a-f]{64}") || !kind.equals(objects.optString(hash))) throw new IOException("InvalidState");
        byte[] value = read(new File(root, "carry-objects/" + kind + "-" + hash + ".json"), kind.equals("state") ? LIMIT : 262144).bytes;
        if (value == null || !hash.equals(hash(value))) throw new IOException("Changed"); return value;
    }
    private byte[][] baseCandidates() throws Exception {
        ArrayList<byte[]> values = new ArrayList<>();
        Iterator<String> keys = objects.keys(); while (keys.hasNext()) { String h = keys.next(); if ("record".equals(objects.getString(h))) values.add(object("record", h)); }
        return PurpleSyncRetainedFiles.combine(values.toArray(new byte[0][]), PurpleSyncRetainedFiles.records(root));
    }
    String prepareState(byte[] image) throws IOException { return prepareState(image, new byte[0][]); }
    private String prepareState(byte[] image, byte[][] candidates) throws IOException {
        try {
            unchanged(); codec("state", json("bytes", blob(image)));
            PurpleSyncRetainedBases.Result base = PurpleSyncRetainedBases.request("find", image, null, PurpleSyncRetainedFiles.combine(candidates, baseCandidates()));
            if (!base.ready()) throw new IOException(base.status);
            if (base.record.length != 0) {storeObject("record", base.record);PurpleSyncRetainedFiles.pin(root,base.record);}
            return storeObject("state", image);
        } catch (Exception e) { close(); throw failure(e); }
    }
    void retainBase(byte[] verifiedRecord) throws IOException {
        try { unchanged(); storeObject("record", verifiedRecord); } catch (Exception e) { close(); throw failure(e); }
    }
    byte[] ownBase() throws IOException {
        try {
            unchanged(); if (state.bytes == null) throw new IOException("MissingLocalVersion");
            PurpleSyncRetainedBases.Result result = PurpleSyncRetainedBases.request("find", state.bytes, null, baseCandidates());
            if (!result.ready()) throw new IOException(result.status); return result.record.clone();
        } catch (Exception e) { close(); throw failure(e); }
    }
    private JSONObject scoped(byte[] value) throws Exception {
        JSONObject j = new JSONObject(new String(value, StandardCharsets.UTF_8));
        return json("account", account, "source", j.getString("source"), "target", j.getString("target"), "journal", blob(value), "currentLogin",journalLoginAuthority());
    }
    private PurpleSyncChatMoveCore.Result core(String op, JSONObject input) { return PurpleSyncChatMoveCore.request(op, bytes(input)); }
    private JSONObject event(String kind) throws Exception {
        JSONObject input = scoped(canonical); input.put("kind", kind);
        return core("event", input).fields.getJSONObject("event");
    }
    private PurpleSyncChatMoveCore.Result persist(PurpleSyncChatMoveCore.Result next) throws Exception {
        unchanged(); byte[] value = unblob(next.fields, "canonical");
        if (value.length == 0) return next;
        JSONObject request=json("wrapper",blob(newEnvelope?null:journal.bytes),"journal",blob(value),"objects",objects,"send_identities",identities);
        if(newEnvelope||journal.bytes==null)request.put("cleanup_settings",newCleanupSettings);
        JSONObject wrapper=codec("wrap",request);
        byte[] encoded = unblob(wrapper, "bytes");
        journal = write(journalFile, journal, encoded, LIMIT); metadata = wrapper; canonical = value; newEnvelope=false;permitEpoch = new Object(); liveSlots.clear();slotGenerations.clear();
        return next;
    }
    PurpleSyncChatMoveCore.Result begin(JSONObject input) throws IOException {
        try {
            unchanged();
            if(canonical!=null) {
                if(metadata.optBoolean("holdsWrites",true))throw new IOException("InvalidTransition");
                File directory=new File(root,"carry-history");PurpleAccountFileSnapshot.durableDirectory(directory);
                String[] retained=directory.list();if(retained==null||retained.length>=256||histories(root,account,binding,objects)+journal.bytes.length>67108864)throw new IOException("RetentionFull");
                File archive=new File(directory,hash(journal.bytes)+".json");PurpleAccountFileSnapshot.Durable old=read(archive,LIMIT);
                if(old.bytes==null)write(archive,old,journal.bytes,LIMIT);else if(!Arrays.equals(old.bytes,journal.bytes))throw new IOException("Changed");else barrier(archive,old,LIMIT);
                canonical=null;identities=new JSONObject();newEnvelope=true;
                envelopeAuthority=authority;
            } else if(journal.bytes!=null)throw new IOException("InvalidTransition");
            if(interrupted)throw new IOException("AccountUnbound");
            if (!account.equals(input.getString("account")) || !Arrays.equals(binding, unblob(input, "preferenceToken"))) throw new IOException("AccountUnbound");
            if (state.bytes != null) prepareState(state.bytes);
            byte[] openingStage=pendingBytes();
            if(openingStage.length!=0)storeObject("record",openingStage);
            barrier(stateFile, state, LIMIT);
            input = new JSONObject(input.toString()); input.put("state", state.bytes == null ? JSONObject.NULL : blob(state.bytes));
            input.put("stateAbsentConfirmed", state.bytes == null);
            if (production) input.put("loginAuthority",journalLoginAuthority());
            newCleanupSettings=input.optBoolean("cleanupSettings");
            String[] pending = new File(root, "pending").list();
            input.put("otherStagesPresent", input.optBoolean("otherStagesPresent") || pending != null && pending.length != 0
                    && (state.bytes == null || PurpleAccountSyncCore.inspectState(state.bytes).pendingSeq == 0));
            return persist(core("begin", input));
        } catch (Exception e) { close(); throw failure(e); }
    }
    private PurpleSyncChatMoveCore.Result apply(JSONObject e) throws Exception {
        JSONObject input = scoped(canonical); input.put("event", e); return persist(core("advance", input));
    }
    PurpleSyncChatMoveCore.Result advance(JSONObject input) throws IOException {
        try {
            unchanged(); if (canonical == null || interrupted) throw new IOException("InvalidTransition");
            JSONObject e = new JSONObject(input.toString()); String kind = e.getString("kind");
            if (Arrays.asList("StateObserved", "OwnStagePersisted", "StageRetired").contains(kind)) throw new IOException("InvalidTransition");
            e.put("objectsDurable", false).put("state", state.bytes == null ? JSONObject.NULL : blob(state.bytes)).put("stateAbsentConfirmed", state.bytes == null);
            if ("Success".equals(e.getString("outcome"))) {
                if (kind.equals("PlanPrepared")) {
                    JSONObject plan = new JSONObject(new String(unblob(e, "plan"), StandardCharsets.UTF_8));
                    byte[] own = plan.getString("own_record").getBytes(StandardCharsets.UTF_8);
                    if (own.length != 0) {
                        JSONObject request = scoped(canonical); request.remove("journal"); request.put("plan", e.getString("plan")).put("state", blob(state.bytes));
                        PurpleSyncChatMoveCore.Result prepared = core("prepareStage", request);
                        if (!"Ready".equals(prepared.status)) throw new IOException("InvalidTransition");
                        storeObject("record", own); prepareState(unblob(prepared.fields, "state"));
                    }
                    e.put("objectsDurable", true);
                } else if (kind.equals("PendingPrepared")) {
                    byte[] record = unblob(e, "stagedRecord");
                    if (!Arrays.equals(record, read(stageFile, 262144).bytes)) throw new IOException("PendingMismatch");
                    JSONObject request = new JSONObject(e.toString()); request.put("account", account);
                    PurpleSyncChatMoveCore.Result prepared;
                    if ("Protected".equals(e.optString("pendingAccess"))) prepared=core("protectedPending",request);
                    else if (e.has("pendingReadback")) { request.put("readback", e.getJSONObject("pendingReadback")); prepared = core("finishPending", request); }
                    else prepared = core("abandonPending", request);
                    if (!"Ready".equals(prepared.status)) throw new IOException("InvalidTransition");
                    storeObject("record", record);
                    if (e.has("retainedLocalText")) storeObject("text", unblob(e, "retainedLocalText"));
                    e.put("preparedImageHash", prepareState(unblob(prepared.fields, "state"))).put("objectsDurable", true);
                } else if (kind.equals("DestinationRead")) {
                    JSONObject j = new JSONObject(new String(canonical, StandardCharsets.UTF_8));
                    JSONObject request = scoped(canonical);
                    request.put("state", state.bytes == null ? JSONObject.NULL : blob(state.bytes));
                    request.put("destination", e.getJSONObject("destination")); if (e.has("playlist")) request.put("playlist", e.getJSONObject("playlist"));
                    PurpleSyncChatMoveCore.Result prepared = core("prepareJournalCommit", request);
                    if ("Ready".equals(prepared.status)) {
                        byte[] image = unblob(prepared.fields, "canonical");
                        ArrayList<byte[]> records = new ArrayList<>();
                        org.json.JSONArray messages = e.getJSONObject("destination").getJSONArray("messages");
                        for (int i = 0; i < messages.length(); ++i) records.add(unblob(messages.getJSONObject(i), "bytes"));
                        if (image.length != 0) prepareState(image, records.toArray(new byte[0][]));
                        byte[] target=unblob(prepared.fields,"targetPendingRecord");
                        if(target.length!=0)storeObject("record",target);
                        e.put("targetPendingRecord",blob(target)).put("preparedImageHash", prepared.fields.getString("stateHash")).put("objectsDurable", true);
                    }
                } else if (kind.equals("CachePrepared") || kind.equals("CacheCompleted")) {
                    witnessCache(e.getJSONObject("cache"), kind.equals("CacheCompleted"));
                    if(kind.equals("CacheCompleted")){byte[] target=targetPending();if(target.length!=0){installTargetPending();e.put("stagedRecord",blob(target)).put("targetPendingStageDurable",true);}}
                    e.put("objectsDurable", true);
                }
            }
            return apply(e);
        } catch (Exception e) { close(); throw failure(e); }
    }
    PurpleSyncChatMoveCore.Result resume() throws IOException {
        try {
            unchanged(); if(interrupted)throw new IOException("AccountUnbound");barrier(stateFile, state, LIMIT);
            JSONObject input = scoped(canonical); input.put("state", state.bytes == null ? JSONObject.NULL : blob(state.bytes));
            input.put("stateAbsentConfirmed", state.bytes == null).put("stateDurableConfirmed", true);
            return persist(core("resume", input));
        } catch (Exception e) { close(); throw failure(e); }
    }
    PurpleSyncChatMoveCore.Result commitPreparedState() throws IOException {
        try {
            unchanged(); String action = core("next", scoped(canonical)).fields.getString("nextAction");
            if (!action.equals("WritePendingState") && !action.equals("CommitState") && !action.equals("StageOwn")) throw new IOException("InvalidTransition");
            String hash = metadata.getString("writeHash");
            if (action.equals("StageOwn")) {
                PurpleAccountFileSnapshot.durableDirectory(stageFile.getParentFile());
                byte[] record = object("record", metadata.getString("stageHash"));
                PurpleAccountFileSnapshot.Durable opening = read(stageFile, 262144);
                if (opening.bytes != null && !Arrays.equals(opening.bytes, record)) throw new IOException("OrphanStage");
                if (opening.bytes == null) write(stageFile, opening, record, 262144); else barrier(stageFile, opening, 262144);
            }
            if (hash.isEmpty()) { if (state.bytes != null) throw new IOException("Changed"); barrier(stateFile, state, LIMIT); }
            else { byte[] image = object("state", hash); prepareState(image); state = write(stateFile, state, image, LIMIT); }
            JSONObject e = event(action.equals("StageOwn") ? "OwnStagePersisted" : "StateObserved");
            e.put("outcome", "Success").put("objectsDurable", true).put("state", state.bytes == null ? JSONObject.NULL : blob(state.bytes)).put("stateAbsentConfirmed", state.bytes == null);
            if (action.equals("StageOwn")) e.put("stagedRecord", blob(read(stageFile, 262144).bytes));
            return apply(e);
        } catch (Exception e) { close(); throw failure(e); }
    }
    private byte[] targetPending() throws Exception {
        JSONObject j=new JSONObject(new String(canonical,StandardCharsets.UTF_8));
        return j.optString("target_pending_record").getBytes(StandardCharsets.UTF_8);
    }
    private void installTargetPending() throws Exception {
        byte[] target=targetPending();if(target.length==0)return;
        JSONObject j=new JSONObject(new String(canonical,StandardCharsets.UTF_8));
        if(!j.getBoolean("state_committed") && !hash(state.bytes).equals(j.optString("next_state_hash")))throw new IOException("InvalidTransition");
        if(!hash(state.bytes).equals(j.getString("next_state_hash")) || !objects.has(hash(target))
                || !Arrays.equals(target,object("record",hash(target))))throw new IOException("PendingMismatch");
        PurpleAccountFileSnapshot.durableDirectory(stageFile.getParentFile());
        PurpleAccountFileSnapshot.Durable old=read(stageFile,262144);
        if(old.bytes!=null&&!Arrays.equals(old.bytes,target)) {
            String obsolete=j.has("plan")?hash(j.getJSONObject("plan").optString("own_record").getBytes(StandardCharsets.UTF_8)):"";
            if(!hash(old.bytes).equals(obsolete))throw new IOException("PendingMismatch");
            PurpleAccountFileSnapshot.durable("remove",stageFile,old,null,262144,faultFor(stageFile));old=read(stageFile,262144);
        }
        if(old.bytes==null)write(stageFile,old,target,262144);else barrier(stageFile,old,262144);
    }
    PurpleSyncChatMoveCore.Result retireStage() throws IOException {
        try {
            unchanged(); if(interrupted)throw new IOException("AccountUnbound");
            String hash = metadata.getString("retireHash"); if (hash.isEmpty()) throw new IOException("InvalidTransition");
            barrier(stateFile, state, LIMIT); if (state.bytes != null) prepareState(state.bytes);
            byte[] target=targetPending();PurpleAccountFileSnapshot.Durable staged = read(stageFile, 262144);
            if(staged.bytes!=null&&!Arrays.equals(staged.bytes,target)) {
                if(!hash.equals(hash(staged.bytes)))throw new IOException("PendingMismatch");
                PurpleAccountFileSnapshot.durable("remove",stageFile,staged,null,262144,faultFor(stageFile));
            } else barrier(stageFile,staged,262144);
            if(target.length!=0)installTargetPending();
            String[] pending=stageFile.getParentFile().list();
            if(pending==null || pending.length!=(target.length==0?0:1))throw new IOException("OrphanStage");
            JSONObject e = event("StageRetired"); e.put("outcome", "Success").put("retiredStageHash", hash).put("noPendingFiles",target.length==0)
                    .put("state",state.bytes==null?JSONObject.NULL:blob(state.bytes)).put("stateAbsentConfirmed",state.bytes==null)
                    .put("stagedRecord",blob(target)).put("targetPendingStageDurable",target.length!=0).put("objectsDurable",true);
            return apply(e);
        } catch (Exception e) { close(); throw failure(e); }
    }
    byte[] pendingBytes() throws IOException {try{unchanged();if(PurpleAccountFileSnapshot.directory(stageFile.getParentFile())==0)return new byte[0];byte[] value=read(stageFile,262144).bytes;return value==null?new byte[0]:value.clone();}catch(Exception e){throw failure(e);}}
    String pendingIntent(byte[] record,String observed) throws IOException {
        try {
            unchanged();String key="pending-issued:"+hash(record);
            if(identities.has(key))return "Issued";
            if(!"NotIssued".equals(observed))sendIdentity(key,new SecureRandom());
            return identities.has(key)?"Issued":"NotIssued";
        }catch(Exception e){throw failure(e);}
    }
    PurpleSyncChatMoveCore.Result discardInterrupted(boolean confirmed) throws IOException {
        try {
            unchanged();if(!interrupted||!confirmed)throw new IOException("InvalidTransition");
            barrier(stateFile,state,LIMIT);JSONObject j=new JSONObject(new String(canonical,StandardCharsets.UTF_8));
            if(state.bytes!=null&&hash(state.bytes).equals(j.optString("next_state_hash")))installTargetPending();
            JSONObject request=scoped(canonical);request.put("explicitlyConfirmed",true).put("state",state.bytes==null?JSONObject.NULL:blob(state.bytes))
                    .put("stateAbsentConfirmed",state.bytes==null).put("stagedRecord",blob(pendingBytes())).put("objectsDurable",true);
            return persist(core("abandonInterrupted",request));
        }catch(Exception e){close();throw failure(e);}
    }
    void writeCache(PurpleSyncChatOrigin origin,PurpleSyncChatClient.Info accepted,boolean complete) throws IOException {
        try {
            unchanged();JSONObject j=new JSONObject(new String(canonical,StandardCharsets.UTF_8)),mark=j.getJSONObject("target_mark");
            File path=complete?cacheFile:new File(root,"carry-cache.json");
            PurpleAccountFileSnapshot.Durable old=read(path,65536),admitted=read(cacheFile,65536);
            PurpleSyncChatCache.Loaded previous=PurpleSyncChatCache.load(new PurpleSyncChatCache.IO(){
                public byte[] read(){return admitted.bytes;}public boolean write(byte[] a,byte[] b){return false;}public boolean remove(byte[] a){return false;}
            },origin,authority);
            if(!previous.ok)throw new IOException("InvalidState");
            PurpleSyncChatCache.ReadersSnapshot readers=PurpleSyncChatCache.ReadersSnapshot.from(accepted);
            java.util.Set<String> warned=new java.util.HashSet<>();boolean warning=transport().optBoolean("reader_warning");
            if(previous.data!=null){warned.addAll(previous.data.warned);warning|=previous.data.newReaders;if(previous.data.location.chat.token().equals(mark.getString("chat"))&&previous.data.readers!=null)readers=previous.data.readers;}
            warned.add("config");
            PurpleSyncChatCache.Data data=new PurpleSyncChatCache.Data(new PurpleSyncChatCore.Cache(PurpleSyncChatCore.Peer.parse(mark.getString("chat"),origin.userId),mark.getInt("id"),mark.getLong("date")),
                    accepted.title,readers,warned,complete?new JSONObject():json("operation",j.getString("operation")),warning);
            write(path,old,PurpleSyncChatCache.encode(origin,authority,data),65536);
        }catch(Exception e){close();throw failure(e);}
    }
    long sendIdentity(String request, SecureRandom random) throws IOException {
        try {
            unchanged(); if (canonical == null || request == null || request.isEmpty() || request.length() > 256) throw new IOException("InvalidTransition");
            if (identities.has(request)) return Long.parseLong(identities.getString(request));
            if (identities.length() >= 4096) throw new IOException("RetentionFull");
            long value = 0; boolean used = true;
            for (int attempt = 0; attempt < 128 && used; ++attempt) {
                value = random.nextLong(); used = value == 0;
                Iterator<String> keys = identities.keys();
                while (keys.hasNext()) if (Long.toString(value).equals(identities.getString(keys.next()))) used = true;
            }
            if (used) throw new IOException("InvalidTransition");
            identities.put(request, Long.toString(value)); persist(core("parse", scoped(canonical))); return value;
        } catch (Exception e) { close(); throw failure(e); }
    }
    private void witnessCache(JSONObject receipt, boolean complete) throws Exception {
        JSONObject j = new JSONObject(new String(canonical, StandardCharsets.UTF_8));
        JSONObject mark = j.getJSONObject("target_mark"), supplied = receipt.getJSONObject("mark");
        if (!account.equals(receipt.getString("account")) || !j.getString("operation").equals(receipt.getString("operation"))
                || !mark.getString("chat").equals(supplied.getString("chat")) || mark.getInt("id") != Integer.parseInt(supplied.getString("id"))
                || mark.getLong("date") != Long.parseLong(supplied.getString("date")) || receipt.getBoolean("complete") != complete) throw new IOException("InvalidTransition");
        File path = complete ? cacheFile : new File(root, "carry-cache.json");
        PurpleAccountFileSnapshot.Durable cache = read(path, 65536);
        if (cache.bytes == null) throw new IOException("InvalidState");
        PurpleSyncChatOrigin origin = new PurpleSyncChatOrigin(slot, Integer.parseInt(account.substring(0, 1)),
                Long.parseLong(account.substring(2)), authority);
        PurpleSyncChatCache.Loaded loaded = PurpleSyncChatCache.load(new PurpleSyncChatCache.IO() {
            public byte[] read() { return cache.bytes.clone(); }
            public boolean write(byte[] expected, byte[] next) { return false; }
            public boolean remove(byte[] expected) { return false; }
        }, origin, authority);
        if (!loaded.ok || loaded.data == null) throw new IOException("InvalidState");
        JSONObject value = new JSONObject(new String(cache.bytes, StandardCharsets.UTF_8));
        if (value.getInt("version") != 1 || !account.equals(value.getString("account")) || !authority.equals(value.getString("authority"))
                || !mark.getString("chat").equals(value.getString("chat")) || mark.getInt("id") != value.getInt("markId")
                || mark.getLong("date") != Long.parseLong(value.getString("markDate"))) throw new IOException("InvalidState");
        JSONObject operation = value.getJSONObject("journal");
        if (complete ? operation.length() != 0 : !j.getString("operation").equals(operation.getString("operation"))) throw new IOException("InvalidState");
        barrier(path, cache, 65536);
    }
    byte[] journalBytes() { return canonical == null ? null : canonical.clone(); }
    byte[] openingCacheBytes() throws IOException {unchanged();PurpleAccountFileSnapshot.Durable value=read(cacheFile,65536);barrier(cacheFile,value,65536);return value.bytes==null?null:value.bytes.clone();}
    byte[] stateBytes() { return state == null || state.bytes == null ? null : state.bytes.clone(); }
    void fault(int point) { fault = point;faultFile=null; }
    void fault(int point,File path){fault=point;faultFile=path;}
    @Override public void close() { permitEpoch = new Object(); liveSlots.clear();slotGenerations.clear(); owner.close(); thread = null; state = null; journal = null; canonical = null; metadata = null; objects = new JSONObject(); identities = new JSONObject(); objectBytes = 0; }
    private static byte[] bytes(JSONObject input) { return input.toString().getBytes(StandardCharsets.UTF_8); }
    private static String blob(byte[] value) { return PurpleSyncRetainedBases.encode(value); }
    private static byte[] unblob(JSONObject value, String key) throws Exception { return PurpleSyncRetainedBases.decode(value.getString(key)); }
    private static JSONObject json(Object... pairs) { return PurpleSyncChatCore.json(pairs); }
    private static String hash(byte[] value) { return PurpleSyncRetainedFiles.hash(value); }
    private static IOException failure(Exception error) { return error instanceof IOException ? (IOException)error : new IOException("InvalidState", error); }
    @Keep private static native byte[] codecNative(String operation, byte[] input);
}
