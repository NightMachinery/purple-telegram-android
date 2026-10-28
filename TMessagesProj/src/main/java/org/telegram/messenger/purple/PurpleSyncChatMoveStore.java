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
    private boolean production;
    private Thread thread;
    private PurpleAccountFileSnapshot.Durable state, journal;
    private JSONObject metadata, objects = new JSONObject(), identities = new JSONObject();
    private byte[] canonical;
    private long objectBytes;
    private int fault;

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
        this.authority = authority; this.device = device; this.cacheFile = cacheFile; this.current = current;
        stateFile = new File(root, "state.json"); journalFile = new File(root, "carry.json");
        stageFile = new File(root, "pending/config.json");
    }
    static boolean artifacts(File root) {
        if (PurpleAccountFileSnapshot.directory(root) == 0) return false;
        String[] names = root.list();
        if (names == null) return true;
        for (String name : names) if (name.startsWith("carry") || name.startsWith(".carry-")) return true;
        return false;
    }
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
            metadata = codec("inspect", json("wrapper", blob(journal.bytes)));
            canonical = unblob(metadata, "journal"); objects = metadata.getJSONObject("objects"); identities = metadata.getJSONObject("send_identities");
            validateObjects();
            barrier(journalFile, journal, LIMIT);
            requireCurrent();
        } catch (Exception e) { close(); throw failure(e); }
    }
    private void requireCurrent() throws IOException {
        PurpleAccountFileSnapshot.worker();
        if (thread != Thread.currentThread() || !current.getAsBoolean()) throw new IOException("AccountUnbound");
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
            } else if (name.equals("carry-objects") || name.equals("retained-bases")) {
                if (PurpleAccountFileSnapshot.directory(path) != 1 || !path.getCanonicalFile().equals(path.getAbsoluteFile())
                        || PurpleAccountFileSnapshot.durableDirectoryIdentity(path).equals("missing")) throw new IOException("AmbiguousFiles");
            } else if (name.equals("lock")) {
                PurpleAccountFileSnapshot.durable("lock", path, null, null, LIMIT, 0);
            } else if (Arrays.asList("state.json", "auto.json", "carry.json", "carry-cache.json", "sync-chat-setup.json", "sync-chat-setup-create.json", "sync-chat-setup-mark.json").contains(name)) read(path, LIMIT);
            else throw new IOException("AmbiguousFiles");
        }
        if (!PurpleSyncRetainedFiles.valid(root)) throw new IOException("InvalidRetention");
    }
    private PurpleAccountFileSnapshot.Durable read(File path, int limit) throws IOException {
        return PurpleAccountFileSnapshot.durable("read", path, null, null, limit, 0);
    }
    private PurpleAccountFileSnapshot.Durable barrier(File path, PurpleAccountFileSnapshot.Durable value, int limit) throws IOException {
        return PurpleAccountFileSnapshot.durable("barrier", path, value, null, limit, fault);
    }
    private PurpleAccountFileSnapshot.Durable write(File path, PurpleAccountFileSnapshot.Durable expected, byte[] bytes, int limit) throws IOException {
        requireCurrent();
        PurpleAccountFileSnapshot.Durable written = PurpleAccountFileSnapshot.durable("write", path, expected, bytes, limit, fault);
        requireCurrent();
        return written;
    }
    private JSONObject codec(String operation, JSONObject input) throws IOException {
        try {
            input.put("account", account).put("binding", blob(binding)).put("authority", authority);
            PurpleCore.ensureLoaded();
            byte[] response = codecNative(operation, bytes(input));
            if (response == null) throw new IOException("InvalidState");
            JSONObject value = new JSONObject(new String(response, StandardCharsets.UTF_8));
            if (!"Ready".equals(value.getString("status"))) throw new IOException(value.getString("status"));
            return value;
        } catch (Exception e) { throw failure(e); }
    }
    private void validateObjects() throws Exception {
        File directory = new File(root, "carry-objects");
        String[] names = directory.list();
        if (names == null) { if (objects.length() != 0 || PurpleAccountFileSnapshot.directory(directory) != 0) throw new IOException("InvalidState"); return; }
        String identity = PurpleAccountFileSnapshot.durableDirectoryIdentity(directory);
        if (identity.equals("missing")) throw new IOException("Changed");
        if (names.length > 1024) throw new IOException("RetentionFull");
        JSONObject found = new JSONObject(); objectBytes = 0;
        for (String name : names) {
            if (!name.matches("(state|record|text)-[0-9a-f]{64}\\.json")) throw new IOException("AmbiguousFiles");
            String kind = name.substring(0, name.indexOf('-')), hash = name.substring(name.indexOf('-') + 1, name.length() - 5);
            byte[] value = read(new File(directory, name), kind.equals("state") ? LIMIT : 262144).bytes;
            if (value == null || !hash.equals(hash(value)) || found.has(hash)) throw new IOException("InvalidState");
            found.put(hash, kind); objectBytes += value.length;
            if (objectBytes > 67108864) throw new IOException("RetentionFull");
            barrier(new File(directory, name), read(new File(directory, name), kind.equals("state") ? LIMIT : 262144), kind.equals("state") ? LIMIT : 262144);
        }
        Iterator<String> keys = objects.keys();
        while (keys.hasNext()) { String hash = keys.next(); if (!objects.getString(hash).equals(found.optString(hash))) throw new IOException("InvalidState"); }
        if (!identity.equals(PurpleAccountFileSnapshot.durableDirectoryIdentity(directory))) throw new IOException("Changed");
        objects = found;
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
            if (base.record.length != 0) storeObject("record", base.record);
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
        return json("account", account, "source", j.getString("source"), "target", j.getString("target"), "journal", blob(value));
    }
    private PurpleSyncChatMoveCore.Result core(String op, JSONObject input) { return PurpleSyncChatMoveCore.request(op, bytes(input)); }
    private JSONObject event(String kind) throws Exception {
        JSONObject input = scoped(canonical); input.put("kind", kind);
        return core("event", input).fields.getJSONObject("event");
    }
    private PurpleSyncChatMoveCore.Result persist(PurpleSyncChatMoveCore.Result next) throws Exception {
        unchanged(); byte[] value = unblob(next.fields, "canonical");
        if (value.length == 0) return next;
        JSONObject wrapper = codec("wrap", json("wrapper", blob(journal.bytes), "journal", blob(value), "objects", objects, "send_identities", identities));
        byte[] encoded = unblob(wrapper, "bytes");
        journal = write(journalFile, journal, encoded, LIMIT); metadata = wrapper; canonical = value;
        return next;
    }
    PurpleSyncChatMoveCore.Result begin(JSONObject input) throws IOException {
        try {
            unchanged(); if (canonical != null || journal.bytes != null) throw new IOException("InvalidTransition");
            if (!account.equals(input.getString("account")) || !Arrays.equals(binding, unblob(input, "preferenceToken"))) throw new IOException("AccountUnbound");
            if (state.bytes != null) prepareState(state.bytes);
            barrier(stateFile, state, LIMIT);
            input = new JSONObject(input.toString()); input.put("state", state.bytes == null ? JSONObject.NULL : blob(state.bytes));
            input.put("stateAbsentConfirmed", state.bytes == null);
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
            unchanged(); if (canonical == null) throw new IOException("InvalidTransition");
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
                    if (e.has("pendingReadback")) { request.put("readback", e.getJSONObject("pendingReadback")); prepared = core("finishPending", request); }
                    else prepared = core("abandonPending", request);
                    if (!"Ready".equals(prepared.status)) throw new IOException("InvalidTransition");
                    storeObject("record", record);
                    if (e.has("retainedLocalText")) storeObject("text", unblob(e, "retainedLocalText"));
                    e.put("preparedImageHash", prepareState(unblob(prepared.fields, "state"))).put("objectsDurable", true);
                } else if (kind.equals("DestinationRead")) {
                    JSONObject j = new JSONObject(new String(canonical, StandardCharsets.UTF_8));
                    JSONObject request = scoped(canonical); request.remove("journal");
                    request.put("plan", blob(bytes(j.getJSONObject("plan")))).put("state", state.bytes == null ? JSONObject.NULL : blob(state.bytes));
                    request.put("destination", e.getJSONObject("destination")); if (e.has("playlist")) request.put("playlist", e.getJSONObject("playlist"));
                    PurpleSyncChatMoveCore.Result prepared = core("prepareCommit", request);
                    if ("Ready".equals(prepared.status)) {
                        byte[] image = unblob(prepared.fields, "canonical");
                        ArrayList<byte[]> records = new ArrayList<>();
                        org.json.JSONArray messages = e.getJSONObject("destination").getJSONArray("messages");
                        for (int i = 0; i < messages.length(); ++i) records.add(unblob(messages.getJSONObject(i), "bytes"));
                        if (image.length != 0) prepareState(image, records.toArray(new byte[0][]));
                        e.put("preparedImageHash", prepared.fields.getString("stateHash")).put("objectsDurable", true);
                    }
                } else if (kind.equals("CachePrepared") || kind.equals("CacheCompleted")) {
                    witnessCache(e.getJSONObject("cache"), kind.equals("CacheCompleted")); e.put("objectsDurable", true);
                }
            }
            return apply(e);
        } catch (Exception e) { close(); throw failure(e); }
    }
    PurpleSyncChatMoveCore.Result resume() throws IOException {
        try {
            unchanged(); barrier(stateFile, state, LIMIT);
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
    PurpleSyncChatMoveCore.Result retireStage() throws IOException {
        try {
            unchanged(); String hash = metadata.getString("retireHash"); if (hash.isEmpty()) throw new IOException("InvalidTransition");
            barrier(stateFile, state, LIMIT); if (state.bytes != null) prepareState(state.bytes);
            PurpleAccountFileSnapshot.Durable staged = read(stageFile, 262144);
            if (staged.bytes != null && !hash.equals(hash(staged.bytes))) throw new IOException("PendingMismatch");
            if (staged.bytes == null) barrier(stageFile, staged, 262144);
            else PurpleAccountFileSnapshot.durable("remove", stageFile, staged, null, 262144, fault);
            String[] pending = stageFile.getParentFile().list(); if (pending == null || pending.length != 0) throw new IOException("OrphanStage");
            JSONObject e = event("StageRetired"); e.put("outcome", "Success").put("retiredStageHash", hash).put("noPendingFiles", true);
            return apply(e);
        } catch (Exception e) { close(); throw failure(e); }
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
    byte[] stateBytes() { return state == null || state.bytes == null ? null : state.bytes.clone(); }
    void fault(int point) { fault = point; }
    @Override public void close() { owner.close(); thread = null; state = null; journal = null; canonical = null; metadata = null; objects = new JSONObject(); identities = new JSONObject(); objectBytes = 0; }
    private static byte[] bytes(JSONObject input) { return input.toString().getBytes(StandardCharsets.UTF_8); }
    private static String blob(byte[] value) { return PurpleSyncRetainedBases.encode(value); }
    private static byte[] unblob(JSONObject value, String key) throws Exception { return PurpleSyncRetainedBases.decode(value.getString(key)); }
    private static JSONObject json(Object... pairs) { return PurpleSyncChatCore.json(pairs); }
    private static String hash(byte[] value) { return PurpleSyncRetainedFiles.hash(value); }
    private static IOException failure(Exception error) { return error instanceof IOException ? (IOException)error : new IOException("InvalidState", error); }
    @Keep private static native byte[] codecNative(String operation, byte[] input);
}
