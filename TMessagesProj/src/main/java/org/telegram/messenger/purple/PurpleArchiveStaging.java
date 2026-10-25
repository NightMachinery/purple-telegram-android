package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleArchiveStaging {
    static final int WINDOW = 256 * 1024;
    final PurpleArchiveStore store;
    final JSONObject binding;
    final String id;
    final PurpleArchiveFiles.Directory directory;
    final BooleanSupplier active;
    JSONObject head;
    PurpleArchiveFiles.Snapshot headSnapshot;
    JSONArray empty;
    String blobHash;
    boolean recovery, refused, refusalCommitted;
    private BooleanSupplier cleanupActive;
    interface ConsistencyValidator { void admit(String hash) throws Exception; }
    ConsistencyValidator validator;
    final String initialHash;
    final long selection = PurpleArchiveViewerModel.selectionGeneration();

    static JSONObject source(PurpleArchiveStore store, PurpleArchiveSlice slice, String media) throws Exception {
        if (slice.head == null) throw new PurpleArchiveStore.Failure("Archive source head missing");
        return new JSONObject().put("epoch", new JSONObject().put("account", store.binding.account).put("store", store.binding.store)
            .put("generation", store.binding.generation).put("removalPending", false)).put("key", slice.key)
            .put("version", slice.facts.getString("head")).put("revision", slice.facts.getString("revisionId"))
            .put("contentHash", slice.facts.getString("hash")).put("mediaIdentity", media)
            .put("accountGeneration", slice.facts.getString("accountGeneration")).put("chatGeneration", slice.facts.getString("chatGeneration"));
    }
    static PurpleArchiveStaging begin(PurpleArchiveStore store, JSONObject source, long total, BooleanSupplier active) throws Exception {
        if (total < 0 || !active.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive unknown or revoked source");
        JSONObject binding = new JSONObject().put("source", source).put("job", PurpleArchiveBinding.token()).put("total", Long.toString(total));
        JSONObject plan = null; String resume = null;
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, source.getJSONObject("key"), null)) {
            slice.media(binding, null);
            for (int i = 0; i < slice.rows.length(); ++i) {
                JSONObject row = slice.rows.getJSONObject(i); if (row.getInt("kind") != 6) continue;
                JSONObject job = PurpleArchiveSlice.open(store, slice.reader, row).getJSONObject("value");
                if (job.getInt("state") != 0 || !job.getString("version").equals(source.getString("version")) || !job.getString("mediaIdentity").equals(source.getString("mediaIdentity"))) continue;
                if (!job.getString("revision").equals(source.getString("revision")) || !job.getString("contentHash").equals(source.getString("contentHash"))
                    || job.isNull("expectedSize") || !job.getString("expectedSize").equals(Long.toString(total))) throw new PurpleArchiveStore.Failure("Archive existing source declaration changed");
                JSONObject original = new JSONObject(binding.toString()).put("job", job.getString("id"));
                if (!job.getString("expectedHash").isEmpty()) original.put("hash", job.getString("expectedHash"));
                resume = PurpleArchiveCore.call("stagingId", store.arguments().put("rows", slice.rows).put("binding", original), store.crypto()).getString("bindingId");
                break;
            }
            if (resume == null) {
                prepare(store, binding, active, slice.rows);
                plan = PurpleArchiveCore.call("mediaStage", store.arguments().put("rows", slice.rows).put("binding", binding), store.crypto());
            }
        }
        if (resume != null) {
            PurpleArchiveStaging retained = retained(store, resume, active);
            if (retained.terminal()) { retained.refuse(active); return begin(store, source, total, active); }
            return retained;
        }
        if (!store.execute(new PurpleArchiveCore.Program(plan), active).committed) throw new PurpleArchiveStore.Failure("Archive media stage refused");
        return new PurpleArchiveStaging(store, binding, active, false);
    }
    static String prepare(PurpleArchiveStore store, JSONObject binding, BooleanSupplier active, JSONArray rows) throws Exception {
        String id = PurpleArchiveCore.call("stagingId", store.arguments().put("rows", rows).put("binding", binding), store.crypto()).getString("bindingId");
        if (!id.matches("[0-9a-f]{64}")) throw new PurpleArchiveStore.Failure("Archive staging binding identity");
        byte[] plain = PurpleArchiveBinding.canonical(binding.toString().getBytes(StandardCharsets.UTF_8));
        try {
            if (plain.length > 32768) throw new PurpleArchiveStore.Failure("Archive staging binding limit");
            JSONObject seal = PurpleArchiveCore.call("journalSeal", store.arguments().put("token", id).put("bytes", PurpleArchiveCore.base64(plain)), store.crypto());
            byte[] frame = new JSONObject().put("frame", seal.getString("bytes")).toString().getBytes(StandardCharsets.UTF_8);
            synchronized (store.environment.publicationLock()) {
                if (!active.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive staging revoked"); store.requireCurrent();
                PurpleArchiveFiles.Directory directory = store.directory.child("staging", true).child("stream", true).exclusiveChild(id);
                directory.publish("binding", new PurpleArchiveFiles.Snapshot(null, null), frame);
            }
        } finally { Arrays.fill(plain, (byte) 0); }
        return id;
    }
    private PurpleArchiveStaging(PurpleArchiveStore store, JSONObject binding, BooleanSupplier active, boolean allowRefused) throws Exception {
        this.store = store; this.binding = new JSONObject(binding.toString()); this.active = active;
        JSONObject begin = call("stagingBegin", new JSONObject()); ready(begin);
        id = begin.getString("bindingId"); if (!id.matches("[0-9a-f]{64}")) throw new PurpleArchiveStore.Failure("Archive staging identity");
        PurpleArchiveFiles.Directory root = store.directory.child("staging", true).child("stream", true);
        directory = new PurpleArchiveFiles.Directory(root.file(id), true);
        if (!PurpleArchiveNormalize.digest(read("binding", 65536).toString()).equals(PurpleArchiveNormalize.digest(binding.toString()))) throw new PurpleArchiveStore.Failure("Archive staging binding changed");
        empty = begin.getJSONArray("emptyProof"); initialHash = begin.getJSONObject("header").getString("hash");
        headSnapshot = directory.read("head", 70000);
        if (headSnapshot.bytes == null) {
            try (PurpleArchiveFiles.Listing listing = directory.listing()) {
                for (;;) { String[] page = listing.next(); if (page.length == 0) break;
                    for (String name : page) if (!name.equals("binding")) throw new PurpleArchiveStore.Failure("Archive staging header missing with retained progress");
                }
            }
            head = begin.getJSONObject("header"); publishHead(head, headSnapshot);
        } else head = PurpleArchiveBinding.json(headSnapshot.bytes);
        ready(call("stagingInspect", new JSONObject().put("header", head)));
        if (!allowRefused && terminal()) throw new PurpleArchiveStore.Failure("Archive staging terminal refusal");
    }
    static JSONObject readBinding(PurpleArchiveStore store, String id) throws Exception {
        if (!id.matches("[0-9a-f]{64}")) throw new PurpleArchiveStore.Failure("Archive staging identity");
        PurpleArchiveFiles.Directory directory = new PurpleArchiveFiles.Directory(store.directory.child("staging", true).child("stream", true).file(id), true);
        JSONObject envelope = PurpleArchiveBinding.json(directory.read("binding", 70000).bytes);
        JSONObject opened = PurpleArchiveCore.call("journalOpen", store.arguments().put("token", id).put("bytes", envelope.getString("frame")), store.crypto());
        return PurpleArchiveBinding.json(PurpleArchiveCore.unbase64(opened.getString("bytes")));
    }
    static int status(PurpleArchiveStore store, JSONObject binding) throws Exception {
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, binding.getJSONObject("source").getJSONObject("key"), null)) {
            slice.media(binding, null);
            return PurpleArchiveCore.call("stagingStatus", store.arguments().put("rows", slice.rows).put("binding", binding), store.crypto()).getInt("status");
        }
    }
    static PurpleArchiveStaging retained(PurpleArchiveStore store, String id, BooleanSupplier active) throws Exception {
        JSONObject binding = readBinding(store, id);
        PurpleArchiveStaging result = new PurpleArchiveStaging(store, binding, active, true);
        if (!result.id.equals(id)) throw new PurpleArchiveStore.Failure("Archive staging ownership mismatch");
        return result;
    }
    static PurpleArchiveStaging reopen(PurpleArchiveStore store, String id, BooleanSupplier active) throws Exception {
        PurpleArchiveStaging result = retained(store, id, active);
        if (result.terminal()) throw new PurpleArchiveStore.Failure("Archive staging terminal refusal");
        return result;
    }
    boolean terminal() throws Exception {
        if (directory.read("refused", 70000).bytes == null) return false;
        JSONObject expected = new JSONObject().put("format", 1).put("bindingId", id).put("bindingHash", PurpleArchiveNormalize.digest(binding.toString()));
        if (!PurpleArchiveNormalize.digest(expected.toString()).equals(PurpleArchiveNormalize.digest(read("refused", 32768).toString())))
            throw new PurpleArchiveStore.Failure("Archive terminal refusal authentication changed");
        return true;
    }
    void current() throws Exception { if (cleanupActive != null ? !cleanupActive.getAsBoolean() : selection != PurpleArchiveViewerModel.selectionGeneration() || !active.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive staging revoked"); store.requireCurrent(); }
    JSONObject call(String operation, JSONObject extra) throws Exception {
        current(); JSONObject args = store.arguments().put("binding", binding);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, binding.getJSONObject("source").getJSONObject("key"), null)) {
            slice.media(binding, blobHash); args.put("rows", slice.rows);
        }
        for (java.util.Iterator<String> keys = extra.keys(); keys.hasNext();) { String key = keys.next(); args.put(key, extra.get(key)); }
        return PurpleArchiveCore.call(operation, args, store.crypto());
    }
    static void ready(JSONObject value) throws Exception {
        int status = value.getInt("status");
        if (status != 0 && status != 1 && status != 12 && status != 14) throw new PurpleArchiveStore.Failure("Archive staging refused " + status);
    }
    JSONObject read(String name, int limit) throws Exception {
        current(); byte[] bytes = directory.read(name, 131072).bytes;
        if (bytes == null) throw new PurpleArchiveStore.Failure("Archive staging record missing");
        if (!name.equals("binding") && !name.equals("consistency") && !name.equals("refused")) {
            if (bytes.length > limit) throw new PurpleArchiveStore.Failure("Archive staging index limit");
            return PurpleArchiveBinding.json(bytes);
        }
        JSONObject envelope = PurpleArchiveBinding.json(bytes);
        JSONObject opened = PurpleArchiveCore.call("journalOpen", store.arguments().put("token", journalToken(name)).put("bytes", envelope.getString("frame")), store.crypto());
        byte[] plain = PurpleArchiveCore.unbase64(opened.getString("bytes"));
        try { if (plain.length > limit) throw new PurpleArchiveStore.Failure("Archive staging record limit"); return PurpleArchiveBinding.json(plain); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    void writeImmutable(String name, JSONObject value) throws Exception {
        current(); PurpleArchiveFiles.Snapshot old = directory.read(name, 131072);
        if (old.bytes != null) {
            JSONObject before = read(name, 131072);
            if (name.startsWith("page-")) {
                if (!before.getString("piece").equals(value.getString("piece"))) throw new PurpleArchiveStore.Failure("Archive staging piece ownership changed");
                ready(call("stagingEquivalent", new JSONObject().put("before", before.getJSONObject("page")).put("after", value.getJSONObject("page"))));
            } else if (name.startsWith("delta-") || name.startsWith("frozen-")) {
                ready(call("stagingEquivalent", new JSONObject().put("before", before).put("after", value)));
            } else if (!PurpleArchiveNormalize.digest(before.toString()).equals(PurpleArchiveNormalize.digest(value.toString())))
                throw new PurpleArchiveStore.Failure("Archive staging immutable record changed");
            return;
        }
        byte[] plain = PurpleArchiveBinding.canonical(value.toString().getBytes(StandardCharsets.UTF_8));
        try {
            if (plain.length > (name.equals("binding") ? 32768 : 131072)) throw new PurpleArchiveStore.Failure("Archive staging record limit");
            byte[] frame;
            if (name.equals("binding") || name.equals("consistency") || name.equals("refused")) {
                JSONObject seal = PurpleArchiveCore.call("journalSeal", store.arguments().put("token", journalToken(name)).put("bytes", PurpleArchiveCore.base64(plain)), store.crypto());
                frame = new JSONObject().put("frame", seal.getString("bytes")).toString().getBytes(StandardCharsets.UTF_8);
            } else frame = plain;
            synchronized (store.environment.publicationLock()) { current(); directory.publish(name, old, frame); }
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    private String journalToken(String name) throws Exception {
        return name.equals("binding") ? id : hash(("staging-" + name + "/" + id).getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject consistency(String hash, String provenance) throws Exception {
        JSONObject inspected = call("stagingInspect", new JSONObject().put("header", head)); ready(inspected);
        if (!inspected.getString("covered").equals(binding.getString("total")) || !hash.matches("[0-9a-f]{64}"))
            throw new PurpleArchiveStore.Failure("Archive consistency coverage refused");
        return new JSONObject().put("format", 1).put("provenance", provenance).put("bindingId", id).put("binding", binding)
            .put("headerHash", inspected.getString("hash")).put("checkpoint", inspected.getString("checkpoint"))
            .put("covered", inspected.getString("covered")).put("hash", hash);
    }
    void discardUnadmitted() throws Exception {
        refuse(active);
        synchronized (store.environment.publicationLock()) {
            current();
            PurpleArchiveRemoval.removeTree(store.directory.child("staging", true).child("stream", true), id);
        }
    }
    void admitConsistency(String hash) throws Exception { admitConsistency(hash, "immutable_bytes"); }
    void admitConsistency(String hash, String provenance) throws Exception {
        current();
        if (refused || directory.read("refused", 70000).bytes != null || provenance.isEmpty()) throw new PurpleArchiveStore.Failure("Archive refused source cannot validate");
        writeImmutable("consistency", consistency(hash, provenance));
    }
    void refuse(BooleanSupplier owned) throws Exception {
        cleanupActive = owned;
        try {
            current();
            if (refusalCommitted || call("stagingStatus", new JSONObject()).getInt("status") == 14) return;
            refused = true; validator = null;
            Exception markerFailure = null;
            try { writeImmutable("refused", new JSONObject().put("format", 1).put("bindingId", id).put("bindingHash", PurpleArchiveNormalize.digest(binding.toString()))); }
            catch (Exception e) { markerFailure = e; }
            JSONObject plan = call("stagingReject", new JSONObject());
            refusalCommitted = store.execute(new PurpleArchiveCore.Program(plan), owned).committed;
            if (!refusalCommitted) throw new PurpleArchiveStore.Failure("Archive terminal refusal pending");
            if (markerFailure != null) throw markerFailure;
        } finally { cleanupActive = null; }
    }
    String requireConsistency() throws Exception {
        JSONObject admitted = read("consistency", 32768);
        String hash = admitted.getString("hash");
        if (!PurpleArchiveNormalize.digest(admitted.toString()).equals(PurpleArchiveNormalize.digest(consistency(hash, admitted.getString("provenance")).toString())))
            throw new PurpleArchiveStore.Failure("Archive consistency admission changed");
        return hash;
    }
    void cache(String name, JSONObject value) throws Exception {
        if (!name.matches("n-[0-9]{1,2}-[0-9a-f]{64}")) throw new PurpleArchiveStore.Failure("Archive cache address");
        synchronized (store.environment.publicationLock()) {
            current(); PurpleArchiveFiles.Snapshot old = directory.read(name, 131072);
            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(old.bytes, bytes)) directory.publish(name, old, bytes);
        }
    }
    void publishHead(JSONObject value, PurpleArchiveFiles.Snapshot expected) throws Exception {
        synchronized (store.environment.publicationLock()) {
            current(); headSnapshot = directory.publish("head", expected, value.toString().getBytes(StandardCharsets.UTF_8)); head = value;
        }
    }
    static final class Page {
        final JSONArray proof;
        final JSONObject value;
        Page(JSONArray proof, JSONObject value) { this.proof = proof; this.value = value; }
    }
    Page page(long index) throws Exception {
        try { return indexedPage(index); }
        catch (Exception failure) {
            rebuild(); return indexedPage(index);
        }
    }
    private Page indexedPage(long index) throws Exception {
        JSONObject inspection = call("stagingInspect", new JSONObject().put("header", head)); ready(inspection);
        String hash = null; JSONObject node = null;
        String root = readRoot(); JSONArray proof = new JSONArray();
        for (int i = 0; i < 46; ++i) proof.put(empty.getString(i));
        hash = root;
        boolean absent = hash.equals(emptyRoot());
        for (int level = 46; level > 0 && !absent; --level) {
            node = read("n-" + level + "-" + hash, 1024);
            boolean right = ((index >>> (level - 1)) & 1) != 0;
            proof.put(level - 1, node.getString(right ? "left" : "right"));
            hash = node.getString(right ? "right" : "left");
            absent = hash.equals(empty.getString(level - 1));
        }
        JSONObject leaf = absent ? null : read("n-0-" + hash, 131072);
        JSONObject validation = new JSONObject().put("header", head).put("index", Long.toString(index)).put("proof", proof);
        if (leaf != null) validation.put("page", leaf.getJSONObject("page"));
        ready(call("stagingPage", validation));
        return new Page(proof, leaf);
    }
    private void cacheNodes(JSONArray nodes, JSONArray proof, long index) throws Exception {
        if (nodes.length() != 47 || proof.length() != 46) throw new PurpleArchiveStore.Failure("Archive staging node shape");
        for (int level = 1; level <= 46; ++level) {
            String own = nodes.getJSONObject(level - 1).getString("hash"), sibling = proof.getString(level - 1);
            boolean right = ((index >>> (level - 1)) & 1) != 0;
            cache("n-" + level + "-" + nodes.getJSONObject(level).getString("hash"),
                new JSONObject().put("left", right ? sibling : own).put("right", right ? own : sibling));
        }
    }
    private void rebuild() throws Exception {
        JSONObject inspection = call("stagingInspect", new JSONObject().put("header", head)); ready(inspection);
        long count = Long.parseLong(inspection.getString("checkpoint")); String previous = initialHash;
        for (long checkpoint = 1; checkpoint <= count; ++checkpoint) {
            current(); JSONObject delta = read("delta-" + checkpoint, 131072);
            JSONObject recovered = call("stagingRecover", new JSONObject().put("header", head).put("checkpoint", delta).put("expectedCheckpoint", Long.toString(checkpoint))); ready(recovered);
            if (!previous.equals(recovered.getString("previous"))) throw new PurpleArchiveStore.Failure("Archive staging checkpoint chain changed");
            previous = recovered.getString("headerHash");
            long index = Long.parseLong(recovered.getString("page"));
            JSONObject leaf = read("page-" + recovered.getString("pageHash"), 131072);
            ready(call("stagingRecover", new JSONObject().put("header", head).put("checkpoint", delta)
                .put("expectedCheckpoint", Long.toString(checkpoint)).put("page", leaf.getJSONObject("page"))));
            cache("n-0-" + recovered.getString("pageHash"), leaf);
            cacheNodes(recovered.getJSONArray("nodes"), recovered.getJSONArray("proof"), index);
        }
        if (count != 0 && !inspection.getString("hash").equals(previous)) throw new PurpleArchiveStore.Failure("Archive staging recovered head mismatch");
    }
    private String readRoot() throws Exception {
        JSONObject header = call("stagingInspect", new JSONObject().put("header", head)); ready(header); return header.getString("root");
    }
    private String emptyRoot() throws Exception { return call("stagingInspect", new JSONObject().put("header", head)).getString("emptyRoot"); }
    void piece(long offset, byte[] bytes) throws Exception {
        current(); if (refused || directory.read("refused", 70000).bytes != null) throw new PurpleArchiveStore.Failure("Archive refused source cannot append");
        if (offset < 0 || bytes.length == 0 || bytes.length > WINDOW || offset % WINDOW + bytes.length > WINDOW)
            throw new PurpleArchiveStore.Failure("Archive staging range limit");
        long index = offset / WINDOW; Page previous = page(index);
        JSONObject extra = new JSONObject().put("header", head).put("offset", Long.toString(offset)).put("bytes", PurpleArchiveCore.base64(bytes)).put("proof", previous.proof);
        byte[] oldPiece = null;
        if (previous.value != null) {
            extra.put("page", previous.value.getJSONObject("page"));
            oldPiece = directory.read(previous.value.getString("piece"), WINDOW + 4608).bytes;
            if (oldPiece == null) throw new PurpleArchiveStore.Failure("Archive staging referenced image missing");
            extra.put("oldPiece", PurpleArchiveCore.base64(oldPiece));
        }
        JSONObject update = call("stagingPiece", extra); ready(update); if (update.getInt("status") == 1) return;
        String piece = update.getString("pieceId"); if (!piece.matches("[0-9a-f]{64}")) throw new PurpleArchiveStore.Failure("Archive staging image identity");
        byte[] frame = PurpleArchiveCore.unbase64(update.getString("pieceFrame"));
        synchronized (store.environment.publicationLock()) {
            current(); PurpleArchiveFiles.Snapshot old = directory.read(piece, WINDOW + 4608);
            if (old.bytes == null) old = directory.publish(piece, old, frame);
            update.put("pieceFrame", PurpleArchiveCore.base64(old.bytes));
        }
        JSONArray nodes = update.getJSONArray("nodes");
        if (nodes.length() != 47) throw new PurpleArchiveStore.Failure("Archive staging node limit");
        JSONObject leaf = new JSONObject().put("page", update.getJSONObject("page")).put("piece", piece);
        writeImmutable("page-" + nodes.getJSONObject(0).getString("hash"), leaf);
        cache("n-0-" + nodes.getJSONObject(0).getString("hash"), leaf);
        cacheNodes(nodes, previous.proof, index);
        writeImmutable("delta-" + update.getJSONObject("delta").getString("checkpoint"), update.getJSONObject("delta"));
        JSONObject oldHead = head; PurpleArchiveFiles.Snapshot expected = headSnapshot;
        extra.put("oldHeader", oldHead).put("update", update).put("pieceDurable", true).put("checkpointDurable", false);
        JSONObject preflight = call("stagingAck", extra);
        if (preflight.getInt("status") != 11) throw new PurpleArchiveStore.Failure("Archive staging durable preflight refused");
        synchronized (store.environment.publicationLock()) {
            current(); ready(call("stagingInspect", new JSONObject().put("header", oldHead)));
            publishHead(update.getJSONObject("header"), expected);
            extra.put("header", head).put("checkpointDurable", true); ready(call("stagingAck", extra));
        }
    }
    static String hash(byte[] value) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(value)) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return out.toString();
    }
    JSONObject finalStep;
    String finalHandle;
    void closeFinalizer() throws Exception {
        String handle = finalHandle;
        if (handle != null) PurpleArchiveCore.call("stagingClose", store.binding.arguments().put("workGeneration", store.binding.generation).put("handle", handle), store.crypto());
        finalHandle = null; finalStep = null;
    }
    boolean finish() throws Exception {
        for (;;) { int status = finishStep(); if (status == 0) return false; if (status == 2) return true; }
    }
    int finishStep() throws Exception {
        current(); if (refused || directory.read("refused", 70000).bytes != null) throw new PurpleArchiveStore.Failure("Archive refused source cannot complete");
        if (finalStep == null) {
            JSONObject freeze = call("stagingFreeze", new JSONObject().put("header", head));
            if (freeze.getInt("status") == 9) return 0; ready(freeze);
            JSONObject frozen = freeze.getJSONObject("frozen"); writeImmutable("frozen-" + frozen.getString("checkpoint"), frozen);
            finalStep = call("stagingFinalize", new JSONObject().put("header", head).put("frozen", frozen)); ready(finalStep);
            finalHandle = finalStep.getString("handle");
        }
        JSONObject step = finalStep; String handle = finalHandle;
        try {
            current(); ready(step); int kind = step.getInt("kind");
            if (kind == 4) {
                if (directory.read("consistency", 70000).bytes == null) {
                    if (recovery || validator == null) throw new PurpleArchiveStore.Failure("Archive source has no consistency validator");
                    validator.admit(blobHash);
                }
                if (!requireConsistency().equals(blobHash)) throw new PurpleArchiveStore.Failure("Archive verified source hash differs");
                JSONObject completion = step.getJSONObject("completion");
                if (!store.execute(new PurpleArchiveCore.Program(completion), active).committed) throw new PurpleArchiveStore.Failure("Archive exact-version completion not committed");
                closeFinalizer(); return 2;
            }
            JSONObject input = new JSONObject().put("kind", kind).put("request", step.getString("request"));
            if (kind == 0) {
                Page page = page(Long.parseLong(step.getString("page")));
                if (page.value == null) throw new PurpleArchiveStore.Failure("Archive finalizer page missing");
                JSONObject record = page.value.getJSONObject("page"); input.put("checkpoint", record.getString("checkpoint")).put("frame", record.getString("frame")).put("proof", page.proof);
            } else if (kind == 1) {
                Page page = page(Long.parseLong(step.getString("page")));
                if (page.value == null) throw new PurpleArchiveStore.Failure("Archive finalizer piece missing");
                input.put("frame", PurpleArchiveCore.base64(directory.read(page.value.getString("piece"), WINDOW + 4608).bytes));
            } else if (kind == 2) {
                JSONObject publication = step.getJSONObject("publication"); String blob = publication.getString("blobToken");
                blobHash = publication.getString("blobHash");
                writeImmutable("canonical-" + blob, new JSONObject().put("blob", blob));
                if (!blob.matches("[0-9a-f]{64}")) throw new PurpleArchiveStore.Failure("Archive canonical blob identity");
                PurpleArchiveFiles.Directory target = store.directory.child("blobs", true).child(blob, true);
                String name = "chunk-" + publication.getString("chunk");
                synchronized (store.environment.publicationLock()) {
                    current(); ready(call("stagingPublication", new JSONObject().put("header", head).put("handle", handle).put("publication", publication)));
                    PurpleArchiveFiles.Snapshot old = target.read(name, 1024 * 1024 + 4608);
                    if (old.bytes == null) old = target.publish(name, old, PurpleArchiveCore.unbase64(publication.getString("frame")));
                    input.put("publication", publication).put("durable", true).put("frameHash", hash(old.bytes));
                    if (!Arrays.equals(old.bytes, PurpleArchiveCore.unbase64(publication.getString("frame")))) input.put("existingFrame", PurpleArchiveCore.base64(old.bytes));
                }
            } else if (kind != 3) throw new PurpleArchiveStore.Failure("Archive finalizer request");
            finalStep = call("stagingAdvance", new JSONObject().put("header", head).put("handle", handle).put("input", input));
            return finalStep.getInt("kind") >= 3 ? finishStep() : 1;
        } catch (Exception | LinkageError e) { closeFinalizer(); throw e; }
    }
}
