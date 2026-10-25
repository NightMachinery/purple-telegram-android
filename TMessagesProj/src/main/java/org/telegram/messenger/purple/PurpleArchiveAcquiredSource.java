package org.telegram.messenger.purple;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.FileLoadOperation;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveAcquiredSource {
    static final int PIECE = 65536, BUDGET = 4 * 1024 * 1024;
    private static final IdentityHashMap<FileLoadOperation, ArrayList<Entry>> entries = new IdentityHashMap<>();
    private static final HashMap<String, Integer> buffered = new HashMap<>();
    private static int count;
    static final class Chunk {
        final long offset;
        final byte[] bytes;
        Chunk(long offset, byte[] bytes) { this.offset = offset; this.bytes = bytes; }
    }
    static final class Completion {
        final File file, keyFile;
        final boolean encrypted;
        byte[] key, iv;
        Completion(File file, boolean encrypted, byte[] key, byte[] iv) {
            if (file == null || encrypted && (key != null || iv != null) && (key == null || key.length != 32 || iv == null || iv.length != 16))
                throw new IllegalArgumentException("Archive cache key unavailable");
            this.file = file; this.encrypted = encrypted;
            this.key = encrypted && key != null ? key.clone() : null; this.iv = encrypted && iv != null ? iv.clone() : null;
            keyFile = encrypted && key == null ? new File(org.telegram.messenger.FileLoader.getInternalCacheDir(), file.getName() + ".key") : null;
        }
        void loadKey() throws Exception {
            if (!encrypted || key != null) return;
            android.system.StructStat before = android.system.Os.lstat(keyFile.getAbsolutePath());
            if (!android.system.OsConstants.S_ISREG(before.st_mode) || before.st_size != 48) throw new PurpleArchiveStore.Failure("Archive cache key file refused");
            byte[] material = new byte[48];
            try (RandomAccessFile input = new RandomAccessFile(keyFile, "r")) {
                android.system.StructStat opened = android.system.Os.fstat(input.getFD());
                if (before.st_dev != opened.st_dev || before.st_ino != opened.st_ino || opened.st_size != 48) throw new PurpleArchiveStore.Failure("Archive cache key changed");
                input.readFully(material); android.system.StructStat after = android.system.Os.fstat(input.getFD());
                if (after.st_size != 48 || after.st_mtime != opened.st_mtime || after.st_ctime != opened.st_ctime) throw new PurpleArchiveStore.Failure("Archive cache key changed");
                key = Arrays.copyOfRange(material, 0, 32); iv = Arrays.copyOfRange(material, 32, 48);
            } finally { Arrays.fill(material, (byte) 0); }
        }
        void clear() { if (key != null) Arrays.fill(key, (byte) 0); if (iv != null) Arrays.fill(iv, (byte) 0); }
    }
    static final class Entry {
        final FileLoadOperation operation;
        final PurpleAccountFiles.Identity identity;
        final long selection, dialog, random;
        final int mid, edit;
        final String media, photoType, text, openingSemantic;
        final PurpleArchive.Worker openingWorker;
        final PurpleArchiveStore openingStore;
        final long expected;
        final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
        final java.util.TreeMap<Long, Long> observed = new java.util.TreeMap<>();
        final String session = PurpleArchiveBinding.token();
        boolean provenanceGap;
        volatile boolean cancelled;
        boolean scheduled, initialized, complete, published, finishing, unknownReported;
        String keyText, bodyText;
        PurpleArchiveStaging staging;
        Completion completion;
        RandomAccessFile file;
        android.system.StructStat stamp;
        long position, total;
        int pass;
        MessageDigest digest;
        byte[] firstDigest;
        Entry(FileLoadOperation operation, PurpleAccountFiles.Identity identity, MessageObject message, String media, String photoType, long expected) throws Exception {
            this.operation = operation; this.identity = identity; selection = PurpleArchiveViewerModel.selectionGeneration();
            dialog = message.getDialogId(); mid = message.getId(); random = message.messageOwner.random_id; edit = message.messageOwner.edit_date;
            text = message.messageOwner.message == null ? "" : message.messageOwner.message;
            this.media = media; this.photoType = photoType; this.expected = expected;
            openingWorker = PurpleArchive.existing(identity);
            openingStore = openingWorker == null ? null : openingWorker.store;
            if (openingStore == null) throw new PurpleArchiveStore.Failure("Archive acquired opening store unavailable");
            openingSemantic = semantic(PurpleArchiveNormalize.body(message.messageOwner));
        }
        boolean owned() { return identity.current()
            && PurpleArchive.existing(identity) == openingWorker && !openingWorker.closing && !openingWorker.retired && openingWorker.store == openingStore; }
        boolean current() { return !cancelled && selection == PurpleArchiveViewerModel.selectionGeneration() && owned(); }
        void require() throws Exception { if (!current()) throw new PurpleArchiveStore.Failure("Archive acquired source revoked"); }
        void initialize(PurpleArchive.Worker worker) throws Exception {
            require(); if (initialized) return;
            if (worker != openingWorker || worker.store != openingStore) throw new PurpleArchiveStore.Failure("Archive acquired incarnation changed");
            PurpleArchiveStore store = openingStore;
            JSONObject key = new JSONObject(keyText), body = new JSONObject(bodyText);
            if (!openingSemantic.equals(semantic(body))) throw new PurpleArchiveStore.Failure("Archive acquired semantic preimage changed");
            String openingHash = PurpleArchiveCore.call("snapshot", store.arguments().put("key", key).put("body", body), store.crypto()).getString("hash");
            boolean absent;
            try (PurpleArchiveSlice before = new PurpleArchiveSlice(store, key, null)) {
                absent = before.head == null;
                if (!absent && !openingHash.equals(before.facts.getString("hash"))) throw new PurpleArchiveStore.Failure("Archive acquired head changed");
            }
            if (absent) PurpleArchiveSequence.capture(store, new PurpleArchiveCapture.Input(keyText, bodyText, PurpleArchiveOrigin.baseline(), false), this::current);
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, key, null)) {
                String hash = PurpleArchiveCore.call("snapshot", store.arguments().put("key", key).put("body", body), store.crypto()).getString("hash");
                if (slice.head == null || !slice.facts.getString("hash").equals(hash)) throw new PurpleArchiveStore.Failure("Archive acquired preimage differs");
                for (int i = 0; i < slice.head.getJSONArray("media").length(); ++i) {
                    JSONObject ref = slice.head.getJSONArray("media").getJSONObject(i);
                    if (ref.getString("identity").equals(media) && ref.getInt("state") == 2) { complete = true; cancel(this, false); return; }
                }
                JSONObject source = PurpleArchiveStaging.source(store, slice, media);
                if (expected > 0) { total = expected; staging = PurpleArchiveStaging.begin(store, source, total, this::current); }
                else {
                    if (completion == null) return;
                    openFile(); total = stamp.st_size;
                    staging = PurpleArchiveStaging.begin(store, source, total, this::current);
                }
            }
            staging.validator = this::validateStream;
            initialized = true; keyText = bodyText = null;
        }
        void openFile() throws Exception {
            require(); if (file != null) return;
            Completion source = completion;
            if (source == null) throw new PurpleArchiveStore.Failure("Archive acquired file unavailable");
            android.system.StructStat before = android.system.Os.lstat(source.file.getAbsolutePath());
            if (!android.system.OsConstants.S_ISREG(before.st_mode)) throw new PurpleArchiveStore.Failure("Archive acquired file type");
            file = new RandomAccessFile(source.file, "r"); stamp = android.system.Os.fstat(file.getFD());
            if (before.st_dev != stamp.st_dev || before.st_ino != stamp.st_ino || stamp.st_size < 0 || expected > 0 && expected != stamp.st_size)
                throw new PurpleArchiveStore.Failure("Archive acquired file changed");
            source.loadKey(); require(); digest = MessageDigest.getInstance("SHA-256");
        }
        void fileStep() throws Exception {
            try { filePartStep(); }
            catch (Exception | LinkageError e) {
                try { if (staging != null && owned()) staging.refuse(this::owned); } catch (Exception | LinkageError ignored) {}
                throw e;
            }
        }
        void filePartStep() throws Exception {
            require(); openFile();
            if (position < stamp.st_size) {
                byte[] bytes = new byte[(int) Math.min(PIECE, stamp.st_size - position)];
                try {
                    file.readFully(bytes);
                    if (completion.key != null) {
                        byte[] iv = completion.iv.clone(); long counter = position / 16;
                        iv[15] = (byte) counter; iv[14] = (byte) (counter >>> 8); iv[13] = (byte) (counter >>> 16); iv[12] = (byte) (counter >>> 24);
                        Utilities.aesCtrDecryptionByteArray(bytes, completion.key, iv, 0, bytes.length, 0); Arrays.fill(iv, (byte) 0);
                    }
                    require(); digest.update(bytes); if (pass == 0) staging.piece(position, bytes); position += bytes.length;
                } finally { Arrays.fill(bytes, (byte) 0); }
                return;
            }
            android.system.StructStat after = android.system.Os.fstat(file.getFD());
            if (after.st_dev != stamp.st_dev || after.st_ino != stamp.st_ino || after.st_size != stamp.st_size || after.st_mtime != stamp.st_mtime || after.st_nlink == stamp.st_nlink && after.st_ctime != stamp.st_ctime)
                throw new PurpleArchiveStore.Failure("Archive acquired file changed during copy");
            byte[] hash = digest.digest();
            if (pass == 0) { firstDigest = hash; pass = 1; position = 0; file.seek(0); digest.reset(); }
            else {
                if (!Arrays.equals(firstDigest, hash)) throw new PurpleArchiveStore.Failure("Archive acquired file content changed");
                StringBuilder agreed = new StringBuilder();
                for (byte b : hash) agreed.append(String.format(java.util.Locale.US, "%02x", b & 255));
                staging.admitConsistency(agreed.toString(), "cached_two_pass:" + session);
                file.close(); file = null; completion.clear(); completion = null; complete = true;
            }
        }
        void step(PurpleArchive.Worker worker) throws Exception {
            require(); if (!initialized) initialize(worker); if (!initialized) return;
            Chunk chunk;
            synchronized (entries) { chunk = chunks.poll(); }
            if (chunk != null) {
                try { staging.piece(chunk.offset, chunk.bytes); observe(chunk.offset, chunk.bytes.length); }
                finally { release(identity.key, chunk.bytes); }
            } else if (completion != null && !complete) fileStep();
            JSONObject inspection = staging.call("stagingInspect", new JSONObject().put("header", staging.head)); PurpleArchiveStaging.ready(inspection);
            boolean covered = inspection.getString("covered").equals(Long.toString(total));
            if (completion != null && !complete) return;
            if (covered && !complete && !observedAll() && !provenanceGap) {
                provenanceGap = true;
                PurpleArchiveGaps.record(staging.store, "stream_provenance_incomplete", System.currentTimeMillis() / 1000);
            }
            if ((complete || observedAll()) && !finishing) finishing = true;
            if (finishing) {
                int status = staging.finishStep();
                if (status == 0) throw new PurpleArchiveStore.Failure("Archive completed source has staging holes");
                if (status == 2) { published = complete = true; cancel(this, false); }
            }
        }
        void observe(long offset, int length) throws Exception {
            long end = Math.addExact(offset, length);
            java.util.Map.Entry<Long, Long> before = observed.floorEntry(offset);
            if (before != null && before.getValue() >= offset) { offset = before.getKey(); end = Math.max(end, before.getValue()); observed.remove(before.getKey()); }
            for (;;) {
                java.util.Map.Entry<Long, Long> after = observed.ceilingEntry(offset);
                if (after == null || after.getKey() > end) break;
                end = Math.max(end, after.getValue()); observed.remove(after.getKey());
            }
            if (observed.size() >= 1024 || end > total) throw new PurpleArchiveStore.Failure("Archive stream provenance limit");
            observed.put(offset, end);
        }
        boolean observedAll() { return total > 0 && observed.size() == 1 && observed.firstKey() == 0 && observed.firstEntry().getValue() == total; }
        void validateStream(String hash) throws Exception {
            require();
            if (!observedAll() || completion != null || file != null) throw new PurpleArchiveStore.Failure("Archive stream provenance incomplete");
            staging.admitConsistency(hash, "verified_stream_session:" + session);
        }
        boolean more() { return !cancelled && (keyText != null && (expected > 0 || completion != null) || initialized && (!chunks.isEmpty() || completion != null || finishing)); }
        void close() {
            keyText = bodyText = null;
            try { if (file != null) file.close(); } catch (Exception ignored) {} file = null;
            try { if (staging != null && !published && owned()) staging.refuse(this::owned); } catch (Exception | LinkageError ignored) {}
            try { if (staging != null) staging.closeFinalizer(); } catch (Exception ignored) {} staging = null;
            observed.clear();
            if (completion != null) completion.clear(); completion = null;
            if (firstDigest != null) Arrays.fill(firstDigest, (byte) 0); firstDigest = null;
        }
    }
    public static void bind(int slot, FileLoadOperation operation, Object parent, TLRPC.Document document, ImageLocation image, long size) {
        if (android.os.Build.VERSION.SDK_INT < 23 || operation == null || !(parent instanceof MessageObject)) return;
        MessageObject message = (MessageObject) parent; PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null || message.currentAccount != slot || message.messageOwner == null) return;
        String quality = image == null || image.photoSize == null ? "" : image.photoSize.type;
        String media = document != null ? "document_" + document.id : image != null && image.photoId != 0 ? "photo_" + image.photoId + "_" + quality
            : image != null && image.documentId != 0 ? "document_" + image.documentId + "_thumb_" + quality : "";
        if (media.isEmpty()) { PurpleArchive.captureGap(identity, "acquired_source_unsupported"); return; }
        long total = document != null ? document.size : size;
        String text = message.messageOwner.message;
        if (total < 0 || text != null && text.length() > 65536 || document == null && (quality == null || !quality.matches("[A-Za-z0-9]{1,8}"))) {
            PurpleArchive.captureGap(identity, "acquired_source_unsupported"); return;
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { PurpleArchive.captureGap(identity, "acquired_binding_on_main"); return; }
        Entry entry;
        try { entry = new Entry(operation, identity, message, media, image == null || image.photoSize == null ? "" : image.photoSize.type, total); }
        catch (Exception | LinkageError e) { PurpleArchive.captureGap(identity, "acquired_opening_refused"); return; }
        synchronized (entries) {
            ArrayList<Entry> list = entries.get(operation);
            if (list != null) for (Entry existing : list) if (existing.current() && existing.identity.key.equals(identity.key) && existing.identity.lifetime == identity.lifetime && existing.mid == entry.mid && existing.dialog == entry.dialog && existing.edit == entry.edit && existing.media.equals(media) && existing.text.equals(entry.text) && existing.photoType.equals(entry.photoType) && existing.expected == entry.expected && existing.openingSemantic.equals(entry.openingSemantic)) return;
            int accountJobs = 0;
            for (ArrayList<Entry> bound : entries.values()) for (Entry item : bound) if (item.identity.key.equals(identity.key)) ++accountJobs;
            if (count >= 128 || accountJobs >= 8 || list != null && list.size() >= 8) { PurpleArchive.captureGap(identity, "acquired_binding_overflow"); return; }
            if (list == null) entries.put(operation, list = new ArrayList<>()); list.add(entry); ++count;
        }
        MessagesStorage storage = MessagesStorage.getInstance(slot);
        storage.getStorageQueue().postRunnable(() -> {
            try { load(entry, storage); schedule(entry); }
            catch (Exception | LinkageError e) { cancel(entry, true); }
        });
    }
    private static void load(Entry entry, MessagesStorage storage) throws Exception {
        entry.require(); boolean secret = org.telegram.messenger.DialogObject.isEncryptedDialog(entry.dialog);
        String sql = secret ? "SELECT m.data,r.random_id,m.ttl FROM messages_v2 m JOIN randoms_v2 r ON r.mid=m.mid AND r.uid=m.uid WHERE m.mid=? AND m.uid=? LIMIT 2"
            : "SELECT data,mid,ttl FROM messages_v2 WHERE mid=? AND uid=? LIMIT 2";
        SQLiteCursor cursor = storage.getDatabase().queryFinalized(sql, entry.mid, entry.dialog);
        try {
            if (!cursor.next()) throw new PurpleArchiveStore.Failure("Archive acquired preimage unavailable");
            NativeByteBuffer data = cursor.byteBufferValue(0); if (data == null) throw new PurpleArchiveStore.Failure("Archive acquired preimage unavailable");
            TLRPC.Message message;
            try { message = TLRPC.Message.TLdeserialize(data, data.readInt32(true), true); }
            finally { data.reuse(); }
            if (message == null || message.id != entry.mid || message.edit_date != entry.edit || !entry.text.equals(message.message == null ? "" : message.message)
                || secret && (entry.random == 0 || cursor.longValue(1) != entry.random)) throw new PurpleArchiveStore.Failure("Archive acquired preimage changed");
            message.dialog_id = entry.dialog; if (secret) message.random_id = entry.random; if (message.ttl == 0) message.ttl = cursor.intValue(2);
            JSONObject body = PurpleArchiveNormalize.body(message);
            if (!entry.openingSemantic.equals(semantic(body))) throw new PurpleArchiveStore.Failure("Archive acquired semantic preimage changed");
            boolean found = false;
            for (int i = 0; i < body.getJSONArray("media").length(); ++i) if (body.getJSONArray("media").getJSONObject(i).getString("identity").equals(entry.media)) found = true;
            if (!found) throw new PurpleArchiveStore.Failure("Archive acquired media changed");
            if (entry.media.startsWith("photo_") || entry.media.contains("_thumb_")) {
                ArrayList<TLRPC.PhotoSize> sizes = message.media.photo != null ? message.media.photo.sizes : message.media.document.thumbs;
                boolean exact = false;
                for (TLRPC.PhotoSize candidate : sizes) if (entry.photoType.equals(candidate.type) && entry.expected == PurpleArchiveNormalize.qualityBytes(candidate)) exact = true;
                if (!exact) throw new PurpleArchiveStore.Failure("Archive acquired photo quality changed");
            } else {
                TLRPC.Document exact = entry.media.equals("document_" + message.media.document.id) ? message.media.document : null;
                for (TLRPC.Document alternative : message.media.alt_documents) if (entry.media.equals("document_" + alternative.id)) exact = alternative;
                if (exact == null || exact.size != entry.expected) throw new PurpleArchiveStore.Failure("Archive acquired document size changed");
            }
            if (cursor.next()) throw new PurpleArchiveStore.Failure("Archive acquired identity ambiguous");
            synchronized (entries) { entry.require(); entry.keyText = PurpleArchiveNormalize.key(entry.identity.key, message).toString(); entry.bodyText = body.toString(); }
        } finally { cursor.dispose(); }
    }
    private static String semantic(JSONObject body) throws Exception {
        JSONObject value = new JSONObject();
        for (String field : new String[]{"text", "caption", "entities", "rich", "opaque", "opaqueFormat", "media", "completeness", "missing", "topicId"}) value.put(field, body.get(field));
        return PurpleArchiveNormalize.digest(value.toString());
    }
    private static void release(String account, byte[] bytes) {
        Arrays.fill(bytes, (byte) 0);
        synchronized (entries) { int remaining = buffered.getOrDefault(account, 0) - bytes.length; if (remaining <= 0) buffered.remove(account); else buffered.put(account, remaining); }
    }
    static void schedule(Entry entry) {
        synchronized (entries) { if (entry.scheduled || entry.cancelled || entry.keyText == null && !entry.initialized) return; entry.scheduled = true; }
        boolean queued = PurpleArchive.media(entry.identity, entry::current, (worker, active) -> {
            try { entry.step(worker); }
            catch (Exception | LinkageError e) { cancel(entry, true); }
            finally {
                boolean again; synchronized (entries) { entry.scheduled = false; again = entry.more(); }
                if (entry.cancelled) entry.close(); else if (again) schedule(entry);
            }
        }, () -> cancel(entry, true));
        if (!queued) { synchronized (entries) { entry.scheduled = false; } cancel(entry, true); }
    }
    public static void bytes(FileLoadOperation operation, long offset, ByteBuffer source, int length, boolean plaintext) {
        ArrayList<Entry> targets; synchronized (entries) { ArrayList<Entry> list = entries.get(operation); if (list == null) return; targets = new ArrayList<>(list); }
        if (!plaintext || offset < 0 || length > Long.MAX_VALUE - offset || source == null || length < 0 || length > 1024 * 1024 || length > source.remaining()) { for (Entry entry : targets) cancel(entry, true); return; }
        for (Entry entry : targets) {
            if (!entry.current()) { cancel(entry, false); continue; }
            if (entry.expected == 0 && length > 0) {
                synchronized (entries) { if (!entry.unknownReported) { entry.unknownReported = true; PurpleArchive.captureGap(entry.identity, "unknown_length_partial_stream"); } }
                continue;
            }
            ByteBuffer input = source.duplicate(); long at = offset; int left = length;
            while (left > 0) {
                int size = (int) Math.min(Math.min(PIECE, left), PurpleArchiveStaging.WINDOW - at % PurpleArchiveStaging.WINDOW);
                synchronized (entries) {
                    int used = buffered.getOrDefault(entry.identity.key, 0);
                    if (entry.cancelled || size > BUDGET - used) { cancel(entry, true); break; }
                    byte[] copy = new byte[size]; input.get(copy); buffered.put(entry.identity.key, used + size); entry.chunks.add(new Chunk(at, copy));
                }
                at += size; left -= size;
            }
            schedule(entry);
        }
    }
    public static void verified(FileLoadOperation operation, long offset, byte[] bytes, int length) {
        if (bytes == null || length < 0 || length > bytes.length) { bytes(operation, offset, null, 0, false); return; }
        bytes(operation, offset, ByteBuffer.wrap(bytes, 0, length), length, true);
    }
    public static void complete(FileLoadOperation operation, File file, boolean encrypted, byte[] key, byte[] iv) {
        ArrayList<Entry> targets; synchronized (entries) { ArrayList<Entry> list = entries.get(operation); if (list == null) return; targets = new ArrayList<>(list); }
        for (Entry entry : targets) {
            try { synchronized (entries) { if (entry.cancelled || entry.completion != null) continue; entry.completion = new Completion(file, encrypted, key, iv); } schedule(entry); }
            catch (RuntimeException e) { cancel(entry, true); }
        }
    }
    private static void cancel(Entry entry, boolean gap) {
        synchronized (entries) {
            if (entry.cancelled) return; entry.cancelled = true; entry.keyText = entry.bodyText = null;
            for (Chunk chunk : entry.chunks) release(entry.identity.key, chunk.bytes); entry.chunks.clear();
            if (entry.completion != null) entry.completion.clear();
            ArrayList<Entry> list = entries.get(entry.operation);
            if (list != null && list.remove(entry)) { --count; if (list.isEmpty()) entries.remove(entry.operation); }
        }
        if (gap) PurpleArchive.captureGap(entry.identity, "acquired_media_gap");
        PurpleArchive.Worker worker = PurpleArchive.existing(entry.identity); if (worker != null) worker.control(entry::close);
    }
    public static void failed(FileLoadOperation operation) {
        ArrayList<Entry> targets; synchronized (entries) { ArrayList<Entry> list = entries.get(operation); if (list == null) return; targets = new ArrayList<>(list); }
        for (Entry entry : targets) cancel(entry, true);
    }
    static void revoke(int slot) {
        ArrayList<Entry> targets = new ArrayList<>(); synchronized (entries) { for (ArrayList<Entry> list : entries.values()) for (Entry entry : list) if (slot < 0 || entry.identity.slot == slot) targets.add(entry); }
        for (Entry entry : targets) cancel(entry, false);
    }
    private PurpleArchiveAcquiredSource() {}
}
