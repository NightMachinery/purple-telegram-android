package org.telegram.messenger.purple;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveB3aCases {
    static int checks;
    static PurpleArchive.Worker worker;
    static void check(boolean value, String message) { ++checks; if (!value) throw new AssertionError(message); }
    static <T> T on(Callable<T> action) throws Exception { return worker.queue.submit(action).get(40, TimeUnit.SECONDS); }
    interface Action { void run() throws Exception; }
    static void refuses(Action action, String text) throws Exception { boolean refused = false; try { action.run(); } catch (Exception e) { refused = true; } check(refused, text); }
    static void await(java.util.function.BooleanSupplier predicate, String message) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!predicate.getAsBoolean() && System.nanoTime() < until) {
            ArrayList<Runnable> pending; synchronized (AndroidUtilities.class) { pending = new ArrayList<>(AndroidUtilities.pending); AndroidUtilities.pending.clear(); }
            for (Runnable task : pending) task.run(); Thread.sleep(5);
        }
        check(predicate.getAsBoolean(), message);
    }
    static TLRPC.Message message(int id, String text, long bytes) {
        TLRPC.TL_message m = new TLRPC.TL_message(); m.id = id; m.message = text; m.date = 100; m.edit_date = 100; m.dialog_id = 55;
        m.peer_id = new TLRPC.TL_peerUser(); m.peer_id.user_id = 55; m.from_id = new TLRPC.TL_peerUser(); m.from_id.user_id = 44; m.flags = 256 | 32768;
        if (bytes >= 0) {
            TLRPC.TL_document document = new TLRPC.TL_document(); document.id = 9000 + id; document.size = bytes; document.mime_type = "application/octet-stream";
            document.file_reference = new byte[]{1}; document.dc_id = 2; document.date = 100;
            TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument(); media.document = document; media.flags = 1; m.media = media; m.flags |= 512;
        }
        return m;
    }
    static JSONObject key(TLRPC.Message m) throws Exception { return PurpleArchiveNormalize.key("0-11", m); }
    static PurpleArchiveCapture.Input input(TLRPC.Message m) throws Exception { return new PurpleArchiveCapture.Input(key(m).toString(), PurpleArchiveNormalize.body(m).toString(), PurpleArchiveOrigin.baseline(), false); }
    static JSONObject capture(TLRPC.Message m) throws Exception {
        PurpleArchiveSequence.capture(worker.store, input(m), () -> true);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(m), null)) { return new JSONObject(slice.facts.toString()); }
    }
    static JSONObject facts(TLRPC.Message m) throws Exception { try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(m), null)) { return slice.facts; } }
    static PurpleArchiveStaging stage(TLRPC.Message m, AtomicBoolean active) throws Exception {
        capture(m); JSONObject source;
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(m), null)) { source = PurpleArchiveStaging.source(worker.store, slice, "document_" + (9000 + m.id)); }
        return PurpleArchiveStaging.begin(worker.store, source, m.media.document.size, active::get);
    }
    static byte[] bytes(int length) { byte[] result = new byte[length]; new java.util.Random(1234).nextBytes(result); return result; }
    static void add(PurpleArchiveStaging staging, byte[] bytes) throws Exception { for (int i = 0; i < bytes.length; i += 65536) staging.piece(i, Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + 65536))); staging.admitConsistency(hash(bytes)); }
    static String hash(byte[] bytes) throws Exception { StringBuilder result = new StringBuilder(); for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.US, "%02x", b & 255)); return result.toString(); }
    static void canonical(PurpleArchiveStaging staging, byte[] bytes) throws Exception {
        PurpleArchiveStore store = worker.store; String hash = hash(bytes);
        String token = PurpleArchiveCore.call("blobToken", store.arguments().put("blob", hash), store.crypto()).getString("token");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); long count = bytes.length == 0 ? 1 : (bytes.length - 1) / 1048576 + 1;
        for (long i = 0; i < count; ++i) {
            byte[] frame = store.directory.child("blobs", true).child(token, true).read("chunk-" + i, 1048576 + 4608).bytes;
            check(frame != null && !Arrays.equals(frame, bytes), "canonical frame exists and is encrypted");
            byte[] plain = PurpleArchiveCore.mediaNative(store.binding.account, store.binding.store, store.binding.key, token, bytes.length, i, frame, false, store.crypto());
            check(plain != null, "canonical frame authenticates with the original account key"); out.write(plain); Arrays.fill(plain, (byte) 0);
        }
        check(Arrays.equals(bytes, out.toByteArray()), "canonical bytes match acquired plaintext exactly");
    }
    static void staging() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true); byte[] data = bytes(262144 + 17); TLRPC.Message m = message(2000, "staged", data.length);
        PurpleArchiveStaging stage = stage(m, active); String originalVersion = stage.binding.getJSONObject("source").getString("version");
        check(!stage.finish(), "partial stream is never complete");
        stage.piece(262144, Arrays.copyOfRange(data, 262144, data.length)); stage.piece(0, Arrays.copyOfRange(data, 0, 65536));
        String checkpoint = stage.call("stagingInspect", new JSONObject().put("header", stage.head)).getString("checkpoint");
        stage.piece(0, Arrays.copyOfRange(data, 0, 65536));
        check(checkpoint.equals(stage.call("stagingInspect", new JSONObject().put("header", stage.head)).getString("checkpoint")), "duplicate bytes make no new checkpoint");
        byte[] changed = Arrays.copyOfRange(data, 0, 65536); changed[0] ^= 1; refuses(() -> stage.piece(0, changed), "overlapping contradictory plaintext refuses");
        PurpleArchiveStaging recovered = PurpleArchiveStaging.reopen(worker.store, stage.id, active::get);
        check(recovered.binding.getJSONObject("source").getString("version").equals(originalVersion), "reopen retains the encrypted original version binding");
        JSONObject inspection = recovered.call("stagingInspect", new JSONObject().put("header", recovered.head));
        File cache = recovered.directory.file("cache-" + inspection.getString("checkpoint")); Files.write(cache.toPath(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PurpleArchiveStaging.Page repaired;
        for (;;) { try { repaired = recovered.page(0); break; } catch (PurpleArchiveStaging.CacheRebuilding pending) { } }
        check(repaired.value != null, "tampered untrusted index rebuilds from authenticated checkpoints");
        for (int i = 65536; i < 262144; i += 65536) recovered.piece(i, Arrays.copyOfRange(data, i, i + 65536));
        m.message = "new caption"; ++m.edit_date; JSONObject newer = capture(m);
        check(!newer.getString("head").equals(originalVersion), "a newer body has a distinct version before old media finishes");
        recovered.admitConsistency(hash(data)); check(recovered.finish(), "exact historical version completes after a newer head"); canonical(recovered, data);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(m), null)) {
            JSONObject version = PurpleArchiveCore.call("query", worker.store.arguments().put("query", "version").put("key", key(m)).put("version", originalVersion), worker.store.crypto());
            PurpleArchiveSqlite.Result found = slice.read(version); JSONObject old = slice.add(3, version.getString("id"), 0, found.values[0][0].bytes).getJSONObject("value").getJSONObject("snapshot");
            check(old.getJSONArray("media").getJSONObject(0).getInt("state") == 2, "only the original version is Complete");
            check(slice.head.getJSONArray("media").getJSONObject(0).getInt("state") == 0, "new head is not credited with historical bytes");
        }
        TLRPC.Message other = message(2001, "dedup", data.length); PurpleArchiveStaging dedup = stage(other, active); add(dedup, data); check(dedup.finish(), "existing canonical ciphertext is validated and deduplicated"); canonical(dedup, data);
        TLRPC.Message empty = message(2002, "zero", 0); PurpleArchiveStaging zero = stage(empty, active); zero.admitConsistency(hash(new byte[0])); check(zero.finish(), "known zero-length source publishes one authenticated empty chunk"); canonical(zero, new byte[0]);
        TLRPC.Message cut = message(2004, "pre-publication cut", 71); PurpleArchiveStaging interrupted = stage(cut, active);
        byte[] beforeHeader = interrupted.headSnapshot.bytes.clone(); interrupted.piece(0, bytes(71));
        interrupted.directory.publish("head", interrupted.headSnapshot, beforeHeader);
        PurpleArchiveStaging replay = PurpleArchiveStaging.reopen(worker.store, interrupted.id, active::get);
        replay.piece(0, bytes(71)); replay.admitConsistency(hash(bytes(71))); check(replay.finish(), "orphan image/page/checkpoint before head publication replays idempotently despite new AEAD nonces"); canonical(replay, bytes(71));
        TLRPC.Message coalesced = message(2005, "resume declaration", 97); PurpleArchiveStaging first = stage(coalesced, active);
        PurpleArchiveStaging resumed = stage(coalesced, active); check(first.id.equals(resumed.id), "new observation resumes the original staged job, never a conflicting replacement");
        resumed.piece(0, bytes(97)); resumed.admitConsistency(hash(bytes(97))); check(resumed.finish(), "resumed original declaration completes");
        PurpleArchiveStaging revoked = stage(message(2003, "revoked", 3), active); active.set(false);
        refuses(() -> revoked.piece(0, new byte[]{1,2,3}), "revoked source cannot publish bytes"); active.set(true);
        PurpleArchiveViewerModel.selectionChanged(1); refuses(() -> revoked.piece(0, new byte[]{1,2,3}), "selection change permanently revokes an old staging capability");
    }
    static void lifecycle() throws Exception {
        TLRPC.Message plain = message(2100, "body only", -1); String body = PurpleArchiveNormalize.body(plain).toString();
        PurpleArchiveLifecycle.apply(worker.store, key(plain).toString(), body, true, true, () -> true);
        check(facts(plain).getBoolean("deleted"), "whole-message body-only TTL commits targeted expiry"); String order = facts(plain).getString("order");
        PurpleArchiveLifecycle.apply(worker.store, key(plain).toString(), body, true, true, () -> true);
        check(facts(plain).getString("order").equals(order), "duplicate body-only expiry is NoChange");
        TLRPC.Message fresh = message(2101, "old", -1); String old = PurpleArchiveNormalize.body(fresh).toString(); capture(fresh); fresh.message = "new"; capture(fresh);
        refuses(() -> PurpleArchiveLifecycle.apply(worker.store, key(fresh).toString(), old, true, true, () -> true), "changed durable head refuses stale body preimage");
        check(!facts(fresh).getBoolean("deleted"), "stale lifecycle leaves new head intact");
        TLRPC.Message ordinary = message(2102, "ordinary read", -1); capture(ordinary);
        refuses(() -> PurpleArchiveLifecycle.apply(worker.store, key(ordinary).toString(), PurpleArchiveNormalize.body(ordinary).toString(), false, false, () -> true), "body-only consumption never guesses whole-message expiry");
        TLRPC.Message media = message(2103, "ttl media", 7); media.media.ttl_seconds = 8;
        PurpleArchiveLifecycle.apply(worker.store, key(media).toString(), PurpleArchiveNormalize.body(media).toString(), true, false, () -> true);
        check(facts(media).getBoolean("deleted"), "media-bearing TTL uses scoped lifecycle with its epoch");
        TLRPC.Message consumed = message(2104, "view once", 7); consumed.media.ttl_seconds = Integer.MAX_VALUE;
        PurpleArchiveLifecycle.apply(worker.store, key(consumed).toString(), PurpleArchiveNormalize.body(consumed).toString(), false, false, () -> true);
        check(facts(consumed).getBoolean("deleted"), "destructive view-once consumption is recorded");
    }
    static void pendingLifecycle() throws Exception {
        TLRPC.Message viewed = message(2110, "opened original", 11); viewed.media.ttl_seconds = Integer.MAX_VALUE;
        PurpleArchiveLifecycle.Event opening = new PurpleArchiveLifecycle.Event(PurpleAccountFiles.capture(0), false, false);
        JSONObject value = PurpleArchiveLifecycle.pendingValue(worker.store, input(viewed), opening, 55, viewed.id, () -> true);
        check(!facts(viewed).getBoolean("deleted"), "opening captures the original preimage without consuming live media");
        PurpleArchiveLifecycle.Event bound = new PurpleArchiveLifecycle.Event(opening.identity, false, false, opening.selection, true,
            value.getJSONObject("source").toString(), value.getString("order"), value.getInt("lifetime"));
        PurpleArchiveLifecycle.applyBound(worker.store, bound, () -> true);
        check(facts(viewed).getBoolean("deleted"), "close consumes exactly the original opened view-once version");
        String order = facts(viewed).getString("order"); PurpleArchiveLifecycle.applyBound(worker.store, bound, () -> true);
        check(facts(viewed).getString("order").equals(order), "replayed original close is NoChange");
        TLRPC.Message changed = message(2111, "opened before edit", 11); changed.media.ttl_seconds = Integer.MAX_VALUE;
        JSONObject original = PurpleArchiveLifecycle.pendingValue(worker.store, input(changed), opening, 55, changed.id, () -> true);
        PurpleArchiveLifecycle.Event stale = new PurpleArchiveLifecycle.Event(opening.identity, false, false, opening.selection, true,
            original.getJSONObject("source").toString(), original.getString("order"), original.getInt("lifetime"));
        changed.message = "edited while open"; ++changed.edit_date; JSONObject fresh = capture(changed);
        refuses(() -> PurpleArchiveLifecycle.applyBound(worker.store, stale, () -> true), "old close never retargets a newly edited version");
        check(!facts(changed).getBoolean("deleted") && facts(changed).getString("head").equals(fresh.getString("head")), "new edit survives stale close");
        JSONObject invalid = new JSONObject(original.toString()); invalid.getJSONObject("source").put("revision", "18446744073709551614");
        PurpleArchiveLifecycle.Event wrongRevision = new PurpleArchiveLifecycle.Event(opening.identity, false, false, opening.selection, true,
            invalid.getJSONObject("source").toString(), invalid.getString("order"), invalid.getInt("lifetime"));
        refuses(() -> PurpleArchiveLifecycle.applyBound(worker.store, wrongRevision, () -> true), "changed original revision refuses");
    }
    static void bodyFences() throws Exception {
        TLRPC.Message m = message(2120, "exact body fences", -1); capture(m);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(m), null)) {
            JSONObject f = slice.facts;
            JSONObject event = new JSONObject().put("origin", 4).put("key", key(m)).put("id", f.getString("head") + ":lifecycle")
                .put("order", PurpleArchiveBinding.next(f.getString("order"))).put("accountGeneration", f.getString("accountGeneration"))
                .put("chatGeneration", f.getString("chatGeneration")).put("targetVersion", f.getString("head"))
                .put("targetRevision", f.getString("revisionId")).put("expectedContentHash", f.getString("hash"))
                .put("observedAt", "101").put("serverTime", "0");
            String[] fields = {"accountGeneration", "chatGeneration", "targetVersion", "targetRevision", "expectedContentHash"};
            for (String field : fields) {
                JSONObject wrong = new JSONObject(event.toString()).put(field, field.endsWith("Generation") ? "18446744073709551614" : field.equals("expectedContentHash") ? hash(new byte[]{9}) : "different");
                JSONObject plan = PurpleArchiveCore.call("capture", worker.store.arguments().put("rows", slice.rows).put("event", wrong), worker.store.crypto());
                check(!plan.getString("status").equals("Ready") && plan.getJSONArray("statements").length() == 0, "targeted body TTL refuses changed " + field);
            }
        }
        check(!facts(m).getBoolean("deleted"), "all refused body plans preserve the original live payload");
        TLRPC.Message pending = message(2121, "removal fence", -1); String body = PurpleArchiveNormalize.body(pending).toString(); capture(pending);
        PurpleArchiveFiles.Snapshot old = worker.store.paths.metadata.read("removing.json", 65536);
        worker.store.paths.metadata.publish("removing.json", old, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try { refuses(() -> PurpleArchiveLifecycle.apply(worker.store, key(pending).toString(), body, true, true, () -> true), "pending removal refuses body TTL before commit"); }
        finally { worker.store.paths.metadata.remove("removing.json", false); }
        check(!facts(pending).getBoolean("deleted"), "pending removal never changes stored payload");
    }
    static void qualities() throws Exception {
        TLRPC.Message m = message(2130, "photo qualities", -1); TLRPC.TL_photo photo = new TLRPC.TL_photo(); photo.id = 8080; photo.file_reference = new byte[]{1};
        TLRPC.TL_photoCachedSize tiny = new TLRPC.TL_photoCachedSize(); tiny.type = "s"; tiny.w = tiny.h = 8; tiny.bytes = bytes(31);
        TLRPC.TL_photoSize large = new TLRPC.TL_photoSize(); large.type = "x"; large.w = large.h = 800; large.size = 4096;
        photo.sizes.add(tiny); photo.sizes.add(large); TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto(); media.photo = photo; media.flags = 1; m.media = media; m.flags |= 512;
        PurpleArchiveCapture.Input input = input(m); PurpleArchiveSequence.capture(worker.store, input, () -> true); PurpleArchiveInline.capture(worker.store, input, () -> true);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(m), null)) {
            JSONArray refs = slice.head.getJSONArray("media"); check(refs.length() == 2, "photo qualities are independent retained references");
            check(refs.getJSONObject(0).getString("identity").equals("photo_8080_s") && refs.getJSONObject(0).getInt("state") == 2, "inline cached quality is retained as Complete");
            check(refs.getJSONObject(1).getString("identity").equals("photo_8080_x") && refs.getJSONObject(1).getInt("state") == 0, "thumbnail never credits the unacquired larger quality");
        }
    }
    static void pendingPublic() throws Exception {
        TLRPC.Message m = message(2140, "pending public", 17); m.media.ttl_seconds = Integer.MAX_VALUE;
        byte[] raw = PurpleArchiveNormalize.bytes(m);
        org.telegram.messenger.MessagesStorage storage = new org.telegram.messenger.MessagesStorage(new org.telegram.SQLite.SQLiteDatabase((sql,args) -> new Object[][]{{55L, raw, 0}}));
        PurpleArchiveLifecycle.pending(0, storage, 991, 55, m.id, false); storage.getStorageQueue().drain();
        check(!on(() -> facts(m).getBoolean("deleted")), "public opening hook leaves the message live");
        PurpleArchiveLifecycle.revoke(0); PurpleArchiveLifecycle.replay(0, storage, 991, 55, m.id); storage.getStorageQueue().drain();
        check(on(() -> facts(m).getBoolean("deleted")), "public replay authenticates the durable original cause after memory loss");
        TLRPC.Message changed = message(2141, "public before edit", 17); changed.media.ttl_seconds = Integer.MAX_VALUE;
        byte[] pre = PurpleArchiveNormalize.bytes(changed);
        org.telegram.messenger.MessagesStorage other = new org.telegram.messenger.MessagesStorage(new org.telegram.SQLite.SQLiteDatabase((sql,args) -> new Object[][]{{55L, pre, 0}}));
        PurpleArchiveLifecycle.pending(0, other, 992, 55, changed.id, false); other.getStorageQueue().drain();
        changed.message = "public newer edit"; ++changed.edit_date; on(() -> capture(changed));
        PurpleArchiveLifecycle.revoke(0); PurpleArchiveLifecycle.replay(0, other, 992, 55, changed.id); other.getStorageQueue().drain();
        check(!on(() -> facts(changed).getBoolean("deleted")), "public durable stale replay refuses rather than retargeting");
        TLRPC.Message unknown = message(2142, "unknown replay", 17); unknown.media.ttl_seconds = Integer.MAX_VALUE;
        byte[] unknownRaw = PurpleArchiveNormalize.bytes(unknown);
        org.telegram.messenger.MessagesStorage unknownStorage = new org.telegram.messenger.MessagesStorage(new org.telegram.SQLite.SQLiteDatabase((sql,args) -> new Object[][]{{55L, unknownRaw, 0}}));
        PurpleArchiveLifecycle.pending(0, unknownStorage, 993, 55, unknown.id, false); unknownStorage.getStorageQueue().drain();
        PurpleArchiveLifecycle.replay(0, unknownStorage, 994, 55, unknown.id); unknownStorage.getStorageQueue().drain();
        check(!on(() -> facts(unknown).getBoolean("deleted")), "unknown replay cannot borrow a different pending task's in-memory cause");
        ArrayList<Integer> unknownIds = new ArrayList<>(); unknownIds.add(unknown.id);
        unknownStorage.getStorageQueue().postRunnable(() -> PurpleArchiveLifecycle.beforeMediaEmpty(0, unknownStorage.getDatabase(), 55, unknownIds)); unknownStorage.getStorageQueue().drain();
        check(!on(() -> facts(unknown).getBoolean("deleted")), "the following destructive seam also refuses an unadmitted replay cause");
        PurpleArchiveLifecycle.replay(0, unknownStorage, 993, 55, unknown.id); unknownStorage.getStorageQueue().drain();
        check(on(() -> facts(unknown).getBoolean("deleted")), "a subsequent authenticated exact-task replay still consumes its original version");
        TLRPC.Message switched = message(2143, "queued replay selection", 17); switched.media.ttl_seconds = Integer.MAX_VALUE;
        byte[] switchedRaw = PurpleArchiveNormalize.bytes(switched);
        org.telegram.messenger.MessagesStorage switchedStorage = new org.telegram.messenger.MessagesStorage(new org.telegram.SQLite.SQLiteDatabase((sql,args) -> new Object[][]{{55L, switchedRaw, 0}}));
        PurpleArchiveLifecycle.pending(0, switchedStorage, 995, 55, switched.id, false); switchedStorage.getStorageQueue().drain();
        PurpleArchiveLifecycle.replay(0, switchedStorage, 995, 55, switched.id); PurpleArchiveViewerModel.selectionChanged(1); switchedStorage.getStorageQueue().drain();
        check(!on(() -> facts(switched).getBoolean("deleted")), "queued replay retains its opening selection fence and cannot acquire a later selection");

    }
    static int sources() throws Exception { java.lang.reflect.Field f = PurpleArchiveAcquiredSource.class.getDeclaredField("count"); f.setAccessible(true); return f.getInt(null); }
    static int buffered() throws Exception {
        java.lang.reflect.Field f = PurpleArchiveAcquiredSource.class.getDeclaredField("buffered"); f.setAccessible(true);
        synchronized (PurpleArchiveAcquiredSource.class) { return ((java.util.Map<String,Integer>) f.get(null)).getOrDefault("0-11", 0); }
    }
    static org.telegram.messenger.MessagesStorage cache(TLRPC.Message m) throws Exception {
        byte[] raw = PurpleArchiveNormalize.bytes(m);
        org.telegram.messenger.MessagesStorage storage = new org.telegram.messenger.MessagesStorage(new org.telegram.SQLite.SQLiteDatabase((sql,args) -> new Object[][]{{raw, m.random_id == 0 ? m.id : m.random_id, m.ttl}}));
        org.telegram.messenger.MessagesStorage.install(0, storage); return storage;
    }
    static org.telegram.messenger.FileLoadOperation bind(TLRPC.Message m) throws Exception {
        org.telegram.messenger.FileLoadOperation operation = new org.telegram.messenger.FileLoadOperation();
        PurpleArchiveAcquiredSource.bind(0, operation, new org.telegram.messenger.MessageObject(0,m), m.media.document, null, 0); return operation;
    }
    static boolean complete(TLRPC.Message m, String identity) throws Exception {
        return on(() -> { try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(m), null)) {
            if (slice.head == null) return false;
            for (int i=0;i<slice.head.getJSONArray("media").length();++i) { JSONObject ref=slice.head.getJSONArray("media").getJSONObject(i); if(ref.getString("identity").equals(identity))return ref.getInt("state")==2; }
            return false;
        } });
    }
    static void acquired(File root) throws Exception {
        byte[] data=bytes(262144+19); TLRPC.Message m=message(2200,"stream",data.length); org.telegram.messenger.MessagesStorage storage=cache(m);
        org.telegram.messenger.FileLoadOperation operation=bind(m);
        byte[] padded=new byte[data.length+3]; System.arraycopy(data,0,padded,3,data.length); ByteBuffer buffer=ByteBuffer.wrap(padded);buffer.position(3);
        PurpleArchiveAcquiredSource.bytes(operation,0,buffer,data.length,true);
        check(buffer.position()==3&&buffer.limit()==padded.length,"observer preserves producer ByteBuffer position and limit");
        check(buffered()==data.length,"pre-binding bytes are bounded and retained until the storage owner resolves identity");
        storage.getStorageQueue().drain(); await(()->{try{return sources()==0;}catch(Exception e){throw new RuntimeException(e);}},"stream finalization drains its original binding");
        check(complete(m,"document_11200"),"full streaming plaintext completes its exact version"); check(buffered()==0,"stream completion releases plaintext backlog");
        TLRPC.Message rejected=message(2201,"unverified",64);storage=cache(rejected);operation=bind(rejected);
        PurpleArchiveAcquiredSource.bytes(operation,0,ByteBuffer.wrap(bytes(64)),64,false);storage.getStorageQueue().drain();on(()->null);
        check(sources()==0&&buffered()==0,"unverified bytes cannot be retained or credited");
        TLRPC.Message cached=message(2202,"encrypted cached",data.length); storage=cache(cached); operation=bind(cached);
        byte[] encryptionKey=bytes(32),iv=bytes(16); Arrays.fill(iv,12,16,(byte)0); javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(encryptionKey,"AES"),new javax.crypto.spec.IvParameterSpec(iv));
        File file=new File(root,"already-downloaded.enc");Files.write(file.toPath(),cipher.doFinal(data));
        PurpleArchiveAcquiredSource.complete(operation,file,true,encryptionKey,iv);storage.getStorageQueue().drain();
        await(()->{try{return sources()==0;}catch(Exception e){throw new RuntimeException(e);}},"cached encrypted file copy completes off the producer thread");
        check(complete(cached,"document_11202"),"local ciphertext is decrypted before archive completion");check(Arrays.equals(encryptionKey,bytes(32)),"producer encryption key is never modified");
        TLRPC.Message fast=message(2207,"existing encrypted cache",data.length);storage=cache(fast);operation=bind(fast);
        File existing=new File(root,"existing.enc");cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(encryptionKey,"AES"),new javax.crypto.spec.IvParameterSpec(iv));Files.write(existing.toPath(),cipher.doFinal(data));
        byte[] keyFile=new byte[48];System.arraycopy(encryptionKey,0,keyFile,0,32);System.arraycopy(iv,0,keyFile,32,16);Files.write(new File(ApplicationLoader.files,"existing.enc.key").toPath(),keyFile);
        PurpleArchiveAcquiredSource.complete(operation,existing,true,null,null);storage.getStorageQueue().drain();
        await(()->{try{return sources()==0;}catch(Exception e){throw new RuntimeException(e);}},"existing encrypted cache reads its bounded existing key on the worker");
        check(complete(fast,"document_11207"),"encrypted-cache fast path retains verified plaintext without another download");
        TLRPC.Message empty=message(2203,"empty cache",0);storage=cache(empty);operation=bind(empty);File zero=new File(root,"empty-cache");Files.write(zero.toPath(),new byte[0]);
        PurpleArchiveAcquiredSource.complete(operation,zero,false,null,null);storage.getStorageQueue().drain();await(()->{try{return sources()==0;}catch(Exception e){throw new RuntimeException(e);}},"known completed empty cache drains");
        check(complete(empty,"document_11203"),"zero length requires and retains the actual completed file");
        TLRPC.Message removed=message(2204,"unlinked cache",10);storage=cache(removed);operation=bind(removed);File gone=new File(root,"gone-cache");Files.write(gone.toPath(),bytes(10));Files.delete(gone.toPath());
        PurpleArchiveAcquiredSource.complete(operation,gone,false,null,null);storage.getStorageQueue().drain();await(()->{try{return sources()==0;}catch(Exception e){throw new RuntimeException(e);}},"missing cache releases work");
        check(!complete(removed,"document_11204"),"unavailable file never becomes Complete");
        TLRPC.Message unknown=message(2208,"unknown until file completion",0);storage=cache(unknown);operation=bind(unknown);
        PurpleArchiveAcquiredSource.bytes(operation,0,ByteBuffer.wrap(bytes(97)),97,true);
        check(buffered()==0,"unknown-length partial stream records a gap without inventing another spool protocol");
        File known=new File(root,"unknown-completed");Files.write(known.toPath(),bytes(97));PurpleArchiveAcquiredSource.complete(operation,known,false,null,null);storage.getStorageQueue().drain();
        await(()->{try{return sources()==0;}catch(Exception e){throw new RuntimeException(e);}},"formerly unknown length is admitted from a real completed descriptor");
        check(complete(unknown,"document_11208"),"actual completed-file length permits fixed-size core staging");
        TLRPC.Message pressure=message(2205,"pressure",6*1024*1024);storage=cache(pressure);operation=bind(pressure);
        for(int i=0;i<5;++i)PurpleArchiveAcquiredSource.bytes(operation,i*1048576L,ByteBuffer.wrap(bytes(1048576)),1048576,true);
        check(sources()==0&&buffered()==0,"4MiB budget exhaustion cancels and releases every queued plaintext buffer");storage.getStorageQueue().drain();
        TLRPC.Message switched=message(2206,"switch",64);storage=cache(switched);operation=bind(switched);PurpleArchiveAcquiredSource.bytes(operation,0,ByteBuffer.wrap(bytes(64)),64,true);
        PurpleArchiveViewerModel.selectionChanged(1);check(sources()==0&&buffered()==0,"account selection revokes pending sources and buffers immediately");storage.getStorageQueue().drain();on(()->null);
        check(!complete(switched,"document_11206"),"queued old-account bytes cannot publish after selection changes");
    }
    static PurpleArchiveAcquiredSource.Entry cachedEntry(TLRPC.Message m, File file) throws Exception {
        PurpleArchiveAcquiredSource.Entry entry = new PurpleArchiveAcquiredSource.Entry(new org.telegram.messenger.FileLoadOperation(), PurpleAccountFiles.capture(0),
            new org.telegram.messenger.MessageObject(0, m), "document_" + m.media.document.id, "", m.media.document.size);
        entry.keyText = key(m).toString(); entry.bodyText = PurpleArchiveNormalize.body(m).toString();
        entry.completion = new PurpleArchiveAcquiredSource.Completion(file, false, null, null); entry.initialize(worker); return entry;
    }
    static void fileBoundaries(File root) throws Exception {
        byte[] data = bytes(70001); TLRPC.Message m = message(2210, "opened then unlinked", data.length);
        File file = new File(root, "open-unlink"); Files.write(file.toPath(), data);
        PurpleArchiveAcquiredSource.Entry entry = cachedEntry(m, file);
        try {
            entry.openFile(); Files.delete(file.toPath());
            for (int i = 0; i < 12 && !entry.complete; ++i) entry.fileStep();
            check(entry.complete && entry.staging.finish(), "already-open regular inode survives producer unlink and completes exact bytes"); canonical(entry.staging, data);
        } finally { entry.close(); }
        TLRPC.Message changed = message(2211, "changed after first pass", data.length); File changing = new File(root, "changing-cache"); Files.write(changing.toPath(), data);
        PurpleArchiveAcquiredSource.Entry mutable = cachedEntry(changed, changing);
        try {
            for (int i = 0; i < 6 && mutable.pass == 0; ++i) mutable.fileStep();
            byte[] altered = data.clone(); altered[0] ^= 1; Files.write(changing.toPath(), altered);
            refuses(() -> { for (int i = 0; i < 6 && !mutable.complete; ++i) mutable.fileStep(); }, "source changed between passes never completes");
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(changed), null)) {
                check(slice.head.getJSONArray("media").getJSONObject(0).getInt("state") != 2, "changed file retains only incomplete staged coverage");
            }
        } finally { mutable.close(); }
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root = new File(args[1]); check(root.mkdir(), "exclusive B3a fixture root"); ApplicationLoader.files = new File(root, "files"); check(ApplicationLoader.files.mkdir(), "app files");
        UserConfig.getInstance(0).active = true; UserConfig.getInstance(0).user = 11; PurpleArchiveLogin.source = slot -> "11111111111111111111111111111111";
        PurpleArchive.refresh(0, () -> {}); await(() -> PurpleArchive.status(0).state.equals("Ready"), "real account worker ready"); worker = PurpleArchive.existing(PurpleAccountFiles.capture(0));
        on(() -> { staging(); return null; });
        if (args.length < 3 || !args[2].equals("staging-only")) on(() -> { lifecycle(); pendingLifecycle(); bodyFences(); qualities(); return null; });
        if (args.length < 3) { pendingPublic(); acquired(root); on(() -> { fileBoundaries(root); return null; }); }
        on(() -> { worker.store.drainReaders(); worker.store.close(); worker.store = null; return null; }); worker.queue.shutdown();
        System.out.println("ARCHIVE_B3A_CHECKS=" + checks);
    }
}
