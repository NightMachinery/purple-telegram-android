package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleArchiveSequence {
    private final PurpleArchiveStore store;
    private final PurpleArchiveFiles.Directory directory;
    private final String name, token;
    private PurpleArchiveFiles.Snapshot snapshot;
    private JSONObject state;
    private PurpleArchiveSequence(PurpleArchiveStore store, JSONObject key) throws Exception {
        this.store = store;
        token = PurpleArchiveCore.call("query", store.arguments().put("query", "message").put("key", key), store.crypto()).getString("id");
        if (!token.matches("[0-9a-f]{64}")) throw new PurpleArchiveStore.Failure("Archive sequence token");
        directory = store.directory.child("causal", true); name = token + ".journal"; snapshot = directory.read(name, 65536);
        if (snapshot.bytes == null) state = new JSONObject().put("version", 1).put("account", store.binding.account)
            .put("store", store.binding.store).put("key", key).put("order", "0").put("pts", 0).put("stream", "").put("lastEdit", 0);
        else {
            JSONObject opened = PurpleArchiveCore.call("journalOpen", store.arguments().put("token", token)
                .put("bytes", PurpleArchiveCore.base64(snapshot.bytes)), store.crypto());
            state = PurpleArchiveBinding.json(PurpleArchiveCore.unbase64(opened.getString("bytes")));
            if (state.getInt("version") != 1 || !state.getString("account").equals(store.binding.account)
                || !state.getString("store").equals(store.binding.store)
                || !PurpleArchiveNormalize.digest(state.getJSONObject("key").toString()).equals(PurpleArchiveNormalize.digest(key.toString())))
                throw new PurpleArchiveStore.Failure("Archive sequence scope");
        }
    }
    private void publish() throws Exception {
        byte[] bytes = PurpleArchiveBinding.canonical(state.toString().getBytes(StandardCharsets.UTF_8));
        if (bytes.length > 32768) throw new PurpleArchiveStore.Failure("Archive sequence limit");
        JSONObject sealed = PurpleArchiveCore.call("journalSeal", store.arguments().put("token", token).put("bytes", PurpleArchiveCore.base64(bytes)), store.crypto());
        synchronized (store.environment.publicationLock()) {
            store.requireCurrent(); snapshot = directory.publish(name, snapshot, PurpleArchiveCore.unbase64(sealed.getString("bytes")));
        }
    }
    private void acknowledge(JSONObject pending) throws Exception {
        state.put("order", pending.getString("order"));
        if (pending.has("hash") && !pending.getString("hash").isEmpty()) state.put("lastBody", pending.getString("hash"));
        state.put("lastEdit", Math.max(state.optInt("lastEdit"), pending.optInt("editDate")));
        if (pending.optInt("pts") > 0) state.put("pts", pending.getInt("pts")).put("stream", pending.getString("stream"))
            .put("lastServerSignature", pending.getString("signature"));
        state.remove("pending"); publish();
    }
    static String capture(PurpleArchiveStore store, PurpleArchiveCapture.Input input, BooleanSupplier active) throws Exception {
        JSONObject key = new JSONObject(input.key);
        recover(store);
        PurpleArchiveSequence sequence = new PurpleArchiveSequence(store, key);
        return sequence.apply(input, active);
    }
    private String apply(PurpleArchiveCapture.Input input, BooleanSupplier active) throws Exception {
        JSONObject key = new JSONObject(input.key);
        JSONObject pending = state.optJSONObject("pending");
        String stable = input.cause.origin == 2 && input.cause.pts == 0 && !input.cause.token.isEmpty()
            ? PurpleArchiveNormalize.digest(new JSONObject().put("key", key).put("token", input.cause.token).put("stream", input.cause.stream).toString()) : "";
        String replay = pending == null ? stable : pending.getString("id");
        JSONObject snapshotBody = null; String hash = "";
        int editDate = 0;
        if (input.body != null) {
            JSONObject body = new JSONObject(input.body); editDate = body.getJSONObject("context").optInt("editDate");
            snapshotBody = PurpleArchiveCore.call("snapshot", store.arguments().put("key", key).put("body", body), store.crypto());
            if (!snapshotBody.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive snapshot limit");
            hash = snapshotBody.getString("hash");
        }
        String signature = PurpleArchiveNormalize.digest(new JSONObject().put("origin", input.cause.origin).put("hash", hash)
            .put("stream", input.cause.stream).put("pts", input.cause.pts).put("token", input.cause.token).toString());
        JSONArray rows; JSONObject facts; JSONObject head;
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, key, replay)) {
            facts = slice.facts; head = slice.head;
            if (pending == null && !stable.isEmpty() && slice.receipt(stable)) return "capture_unchanged";
            if (input.preimage && facts.has("head")) return "capture_unchanged";
            if (pending != null) {
                if (slice.receipt(replay)) {
                    boolean same = signature.equals(pending.getString("signature"));
                    acknowledge(pending); pending = null;
                    if (same) return "capture_unchanged";
                }
                else if (!signature.equals(pending.getString("signature"))) {
                    PurpleArchiveGaps.record(store, "capture_interrupted", Long.parseLong(pending.getString("observedAt")), pending.getString("id") + "_interrupted");
                    state.remove("pending"); publish(); pending = null;
                }
            }
            rows = slice.rows;
        }
        if (pending != null && (!pending.getString("accountGeneration").equals(facts.getString("accountGeneration"))
            || !pending.getString("chatGeneration").equals(facts.getString("chatGeneration"))))
            throw new PurpleArchiveStore.Failure("Archive pending generation changed");
        if (!active.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive capture cancelled");
        if (input.cause.pts > 0 && !state.optString("stream").isEmpty() && !state.getString("stream").equals(input.cause.stream))
            throw new PurpleArchiveStore.Failure("Archive causal stream changed");
        if (pending == null && input.cause.pts > 0 && input.cause.pts <= state.optInt("pts")) {
            if (input.cause.pts == state.optInt("pts") && !signature.equals(state.optString("lastServerSignature")))
                throw new PurpleArchiveStore.Failure("Archive replay conflict");
            return "capture_stale";
        }
        int origin = input.cause.origin;
        boolean enrichment = origin == 0 && head != null && head.getInt("completeness") == 0 && snapshotBody != null
            && new JSONObject(input.body).getInt("completeness") == 1;
        if (enrichment) {
            JSONObject probe = new JSONObject().put("origin", 6).put("id", "hydration_probe").put("order", facts.getString("order"))
                .put("snapshot", snapshotBody.getString("snapshot")).put("accountGeneration", facts.getString("accountGeneration"))
                .put("chatGeneration", facts.getString("chatGeneration")).put("targetVersion", facts.getString("head"))
                .put("targetRevision", facts.getString("revisionId")).put("expectedContentHash", facts.getString("hash"));
            JSONObject admitted = PurpleArchiveCore.call("capture", store.arguments().put("rows", rows).put("event", probe), store.crypto());
            if (admitted.getString("status").equals("Ready")) origin = 6;
        }
        if (pending != null) origin = pending.optInt("origin", origin);
        if (input.cause.origin == 0 && pending == null) {
            if (!enrichment && hash.equals(state.optString("lastBody"))) return "capture_unchanged";
            if (editDate < state.optInt("lastEdit")) return "capture_stale";
            if (facts.getBoolean("deleted")) return "capture_stale";
        }
        if (pending == null) {
            if (new java.math.BigInteger(state.getString("order")).compareTo(new java.math.BigInteger(facts.getString("order"))) > 0)
                throw new PurpleArchiveStore.Failure("Archive sequence ahead of committed head");
            pending = new JSONObject().put("id", stable.isEmpty() ? PurpleArchiveBinding.token() : stable).put("order", origin == 6 ? facts.getString("order") : PurpleArchiveBinding.next(facts.getString("order")))
                .put("accountGeneration", facts.getString("accountGeneration")).put("chatGeneration", facts.getString("chatGeneration"))
                .put("observedAt", Long.toString(System.currentTimeMillis() / 1000)).put("origin", origin).put("signature", signature).put("hash", hash).put("editDate", editDate).put("pts", input.cause.pts).put("stream", input.cause.stream);
            if (snapshot.bytes == null) publish();
            inflight(pending);
            state.put("pending", pending); publish();
        }
        inflight(pending);
        JSONObject event = new JSONObject().put("origin", origin).put("id", pending.getString("id"))
            .put("order", pending.getString("order")).put("key", key).put("accountGeneration", facts.getString("accountGeneration"))
            .put("chatGeneration", facts.getString("chatGeneration")).put("observedAt", Long.toString(System.currentTimeMillis() / 1000)).put("serverTime", "0");
        if (snapshotBody != null) event.put("snapshot", snapshotBody.getString("snapshot"));
        if ((origin == 0 || origin == 6) && facts.has("head")) event.put("targetVersion", facts.getString("head"))
            .put("targetRevision", facts.getString("revisionId")).put("expectedContentHash", facts.getString("hash"));
        JSONObject plan = PurpleArchiveCore.call("capture", store.arguments().put("rows", rows).put("event", event), store.crypto());
        if (!active.getAsBoolean() || !store.execute(new PurpleArchiveCore.Program(plan), active).committed)
            throw new PurpleArchiveStore.Failure("Archive capture not committed");
        acknowledge(pending); clearInflight(store, token, pending.getString("id")); return "capture_committed";
    }
    private void inflight(JSONObject pending) throws Exception {
        synchronized (store.environment.publicationLock()) {
            store.requireCurrent();
            PurpleArchiveFiles.Snapshot old = store.paths.metadata.read("capture-inflight.json", 65536);
            JSONObject value = new JSONObject().put("account", store.binding.account).put("store", store.binding.store)
                .put("generation", store.binding.generation).put("token", token).put("id", pending.getString("id"))
                .put("observedAt", pending.getString("observedAt"));
            if (old.bytes != null) {
                JSONObject previous = PurpleArchiveBinding.json(old.bytes);
                if (!previous.getString("token").equals(token) || !previous.getString("id").equals(pending.getString("id")))
                    throw new PurpleArchiveStore.Failure("Archive prior capture unresolved");
            } else store.paths.metadata.publish("capture-inflight.json", old, PurpleArchiveCatalogue.bytes(value));
        }
    }
    private static void clearInflight(PurpleArchiveStore store, String token, String id) throws Exception {
        synchronized (store.environment.publicationLock()) {
            store.requireCurrent(); PurpleArchiveFiles.Snapshot old = store.paths.metadata.read("capture-inflight.json", 65536);
            if (old.bytes == null) return;
            JSONObject value = PurpleArchiveBinding.json(old.bytes);
            if (!value.getString("account").equals(store.binding.account) || !value.getString("store").equals(store.binding.store)
                || !value.getString("generation").equals(store.binding.generation) || !value.getString("token").equals(token) || !value.getString("id").equals(id))
                throw new PurpleArchiveStore.Failure("Archive capture completion changed");
            store.paths.metadata.remove("capture-inflight.json", false);
        }
    }
    static void recover(PurpleArchiveStore store) throws Exception {
        PurpleArchiveFiles.Snapshot old = store.paths.metadata.read("capture-inflight.json", 65536);
        if (old.bytes == null) return;
        JSONObject value = PurpleArchiveBinding.json(old.bytes);
        if (value.length() != 6 || !value.getString("account").equals(store.binding.account) || !value.getString("store").equals(store.binding.store)
            || !value.getString("generation").equals(store.binding.generation)) throw new PurpleArchiveStore.Failure("Archive interrupted capture scope");
        String token = value.getString("token"), id = value.getString("id");
        if (!token.matches("[0-9a-f]{64}") || !PurpleArchiveKeys.token(id)) throw new PurpleArchiveStore.Failure("Archive interrupted capture identity");
        PurpleArchiveFiles.Directory directory = store.directory.child("causal", true);
        byte[] journal = directory.read(token + ".journal", 65536).bytes;
        if (journal == null) throw new PurpleArchiveStore.Failure("Archive interrupted journal missing");
        JSONObject opened = PurpleArchiveCore.call("journalOpen", store.arguments().put("token", token).put("bytes", PurpleArchiveCore.base64(journal)), store.crypto());
        JSONObject state = PurpleArchiveBinding.json(PurpleArchiveCore.unbase64(opened.getString("bytes")));
        JSONObject key = state.getJSONObject("key");
        PurpleArchiveSequence sequence = new PurpleArchiveSequence(store, key);
        if (!sequence.token.equals(token)) throw new PurpleArchiveStore.Failure("Archive interrupted token mismatch");
        boolean confirmed;
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, key, id)) { confirmed = slice.receipt(id); }
        JSONObject pending = sequence.state.optJSONObject("pending");
        if (pending != null && !pending.getString("id").equals(id)) throw new PurpleArchiveStore.Failure("Archive interrupted event changed");
        if (confirmed) { if (pending != null) sequence.acknowledge(pending); }
        else PurpleArchiveGaps.record(store, "capture_interrupted", Long.parseLong(value.getString("observedAt")), id + "_interrupted");
        clearInflight(store, token, id);
    }
    private PurpleArchiveSequence() { throw new AssertionError(); }
}
