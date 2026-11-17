package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurplePlaylistsSyncDiscovery implements AutoCloseable {
    private static final int PAGE_SIZE = 100, MAX_BYTES = 10 * 1024 * 1024;
    interface Checkpoint {
        void merge(byte[] request, byte[] body, Consumer<PurplePlaylistsSyncCore.Result> done);
    }
    private static final class Receipt {
        final byte[] proof, metadata, digest;
        final String canonicalHash;
        final int length;
        Receipt(JSONObject proof, byte[] metadata, byte[] body, String canonicalHash) {
            this.proof = bytes(proof); this.metadata = metadata.clone();
            digest = digest(body); length = body.length; this.canonicalHash = canonicalHash;
        }
        boolean exact(byte[] body) { return body != null && length == body.length && Arrays.equals(digest, digest(body)); }
    }
    static final class Result {
        final String error;
        private final PurplePlaylistsSyncDiscovery owner;
        private final byte[] request, merged, memo, knownMemo;
        private final ArrayList<Receipt> receipts;
        private Result(PurplePlaylistsSyncDiscovery owner, String error) {
            this.owner = owner; this.error = error; request = bytes(owner.request);
            merged = owner.initialLibrary;
            memo = bytes(owner.acceptedMemo); knownMemo = owner.initialState;
            receipts = new ArrayList<>(owner.receipts);
        }
        boolean current() { return owner.current(); }
        Recovery publication(PurpleSyncChatOrigin origin, PurpleSyncChatCore.Peer chat, byte[] exact) {
            if (!matches(origin, chat) || exact == null) return null;
            for (Receipt receipt : receipts) {
                if (!receipt.exact(exact)) continue;
                PurplePlaylistsSyncCore.Result checked = PurplePlaylistsSyncCore.classify(receipt.metadata, exact, owner.self);
                if (!"Valid".equals(checked.detail().optString("status"))) continue;
                JSONObject proof = object(receipt.proof);
                put(proof, "expectedHash", checked.detail().optString("hash"));
                return current() ? new Recovery(this, bytes(proof)) : null;
            }
            return null;
        }
        boolean matches(PurpleSyncChatOrigin origin, PurpleSyncChatCore.Peer chat) {
            return current() && owner.origin.equals(origin) && owner.chat.equals(chat);
        }
        boolean contains(byte[] library, PurplePlaylistsSyncPublication.Result published) {
            if (!current()) return false;
            PurplePlaylistsCore.Result canonical = PurplePlaylistsCore.canonicalize(library, owner.self);
            if (!canonical.valid()) return false;
            String hash = hex(digest(canonical.canonicalBytes()));
            for (Receipt receipt : receipts) if (hash.equals(receipt.canonicalHash)) return current();
            JSONObject prior = object(knownMemo), entries = prior.optJSONObject("memo");
            if (owner.chat.token().equals(prior.optString("chat")) && entries != null) {
                java.util.Iterator<String> keys = entries.keys();
                while (keys.hasNext()) {
                    String id = keys.next(); JSONObject entry = entries.optJSONObject(id);
                    byte[] observed = owner.metadata.get(Integer.parseInt(id));
                    if (entry != null && "valid".equals(entry.optString("state"))
                            && hash.equals(entry.optString("canonical_hash")) && observed != null
                            && entry.optString("document_id").equals(object(observed).optString("document"))
                            && "Candidate".equals(PurplePlaylistsSyncCore.metadata(observed, owner.self).detail().optString("verdict"))) return current();
                }
            }
            if (published == null || published.status != PurplePlaylistsSyncPublication.Status.Confirmed
                    || !published.current() || !matches(published.attempt.origin, published.attempt.chat)) return false;
            PurplePlaylistsSyncCore.Result record = PurplePlaylistsSyncCore.classify(published.metadata(), published.payload(), owner.self);
            if (!"Valid".equals(record.detail().optString("status"))) return false;
            PurplePlaylistsCore.Result contained = PurplePlaylistsCore.contains(record.library(), library, owner.self);
            return contained.valid() && Boolean.TRUE.equals(contained.contains) && current();
        }
        PurplePlaylistsSyncCore.Result plan(byte[] library, byte[] state, boolean manual, long now) {
            return plan(library, state, manual, now, null);
        }
        PurplePlaylistsSyncCore.Result plan(byte[] library, byte[] state, boolean manual, long now,
                PurplePlaylistsSyncPublication.Result published) {
            if (!current() || now < 0) throw new IllegalStateException("PlaylistSyncRouteChanged");
            PurplePlaylistsCore.Result joined = PurplePlaylistsCore.join(library, merged, owner.self);
            if (!joined.valid()) throw new IllegalStateException("PlaylistScanMergeFailed");
            JSONObject local = object(state), accepted = object(memo);
            JSONObject entries = local.optJSONObject("memo");
            if (entries == null || !owner.chat.token().equals(local.optString("chat"))) entries = new JSONObject();
            java.util.Iterator<String> keys = accepted.keys();
            while (keys.hasNext()) { String key = keys.next(); put(entries, key, accepted.opt(key)); }
            if (accepted.length() != 0) {
                if (!owner.chat.token().equals(local.optString("chat"))) local.remove("due_seen");
                put(local, "chat", owner.chat.token()); put(local, "memo", entries);
            }
            JSONObject value = object(request);
            put(value, "trigger", manual ? "Manual" : "Automatic");
            put(value, "clock", clock(owner.self, owner.chat.token(), now));
            JSONArray proofs = new JSONArray(); byte[][] bodies = new byte[0][];
            if (published != null) {
                if (published.status != PurplePlaylistsSyncPublication.Status.Confirmed || !published.current()
                        || !matches(published.attempt.origin, published.attempt.chat))
                    throw new IllegalStateException("PlaylistPublicationRouteChanged");
                JSONObject meta = object(published.metadata()); boolean seen = false;
                for (String key : new String[]{"listed", "byId"}) {
                    JSONArray observations = array(value, key);
                    for (int i = 0; i < observations.length(); ++i) {
                        JSONObject item = objectAt(observations, i);
                        if (item.optString("message").equals(meta.optString("message"))) {
                            if (!item.optString("document").equals(meta.optString("document")))
                                throw new IllegalStateException("PlaylistPublishedIdentityChanged");
                            seen = true;
                        }
                    }
                }
                if (!seen) array(value, "listed").put(meta);
                proofs.put(published.proof()); bodies = new byte[][]{published.payload()};
            }
            put(value, "payloads", proofs);
            PurplePlaylistsSyncCore.Result result = PurplePlaylistsSyncCore.plan(bytes(value), joined.canonicalBytes(), bytes(local), bodies, owner.self);
            JSONObject detail = result.detail();
            if ("Planned".equals(detail.optString("status"))) {
                boolean changed = !Arrays.equals(library, result.library());
                put(detail, "writeLibrary", changed);
                if (changed || accepted.length() != 0) put(detail, "steadyState", false);
                if (detail.optBoolean("discoveryComplete")) proposePrunes(detail, result.state(), published);
                result = result.withDetail(detail);
            }
            if (!current()) throw new IllegalStateException("PlaylistSyncRouteChanged");
            return result;
        }
        private void proposePrunes(JSONObject detail, byte[] state, PurplePlaylistsSyncPublication.Result published) {
            JSONObject entries = object(state).optJSONObject("memo");
            if (entries == null) return;
            String witness = null, hash = detail.optString("canonicalHash");
            java.util.Iterator<String> keys = entries.keys();
            while (keys.hasNext()) {
                String id = keys.next(); JSONObject entry = entries.optJSONObject(id);
                if (entry != null && "valid".equals(entry.optString("state")) && hash.equals(entry.optString("canonical_hash"))) {
                    if (witness == null || Integer.parseInt(id) > Integer.parseInt(witness)) witness = id;
                }
            }
            if (witness == null) return;
            JSONArray present = array(detail.optJSONObject("inventory"), "present"), targets = new JSONArray();
            JSONObject cover = new JSONObject(); put(cover, "message", witness);
            put(cover, "document", entries.optJSONObject(witness).optString("document_id"));
            for (int i = 0; i < present.length(); ++i) {
                JSONObject target = objectAt(present, i); String id = target.optString("message");
                JSONObject entry = entries.optJSONObject(id);
                if (id.equals(witness) || entry == null || !"valid".equals(entry.optString("state"))) continue;
                JSONObject candidate = new JSONObject(); put(candidate, "target", target); put(candidate, "witness", cover);
                put(candidate, "hash", hash); targets.put(candidate);
            }
            JSONObject prune = detail.optJSONObject("prune");
            if (prune != null && targets.length() != 0) { put(prune, "status", "Planned"); put(prune, "targets", targets); }
        }
    }

    static final class Recovery {
        private final Result owner;
        private final byte[] proof;
        private Recovery(Result owner, byte[] proof) { this.owner = owner; this.proof = proof.clone(); }
        boolean current() { return owner.current(); }
        JSONObject proof() {
            if (!current()) throw new IllegalStateException("PlaylistRecoveryRouteChanged");
            return object(proof);
        }
    }

    private final PurpleSyncClient client;
    private final BooleanSupplier scope;
    private final PurpleSyncChatOrigin origin;
    private final PurpleSyncChatCore.Peer chat;
    private final long self;
    private final boolean freshBodies;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final HashMap<Integer, PurpleSyncClient.Message> messages = new HashMap<>();
    private final HashMap<Integer, byte[]> metadata = new HashMap<>();
    private final ArrayDeque<Integer> lookups = new ArrayDeque<>(), reads = new ArrayDeque<>();
    private final ArrayList<Receipt> receipts = new ArrayList<>();
    private final JSONObject acceptedMemo = new JSONObject();
    private Checkpoint checkpoint;
    private Runnable afterReads;
    private boolean manual;
    private long now;
    private final JSONObject request = new JSONObject();
    private final JSONArray listed = new JSONArray(), byId = new JSONArray();
    private volatile Pending<?> pending;
    private byte[] initialLibrary, initialState;
    private Consumer<Result> done;
    private Integer declaredTotal;
    private long historyCount;
    private boolean finished;

    PurplePlaylistsSyncDiscovery(PurpleSyncClient client, BooleanSupplier scope) {
        this(client, scope, false);
    }
    PurplePlaylistsSyncDiscovery(PurpleSyncClient client, BooleanSupplier scope, boolean freshBodies) {
        if (client == null || scope == null || client.origin() == null || client.userId() <= 0
                || client.chat() == null || client.origin().userId != client.userId())
            throw new IllegalArgumentException("MissingPlaylistSyncRoute");
        this.client = client; this.scope = scope; this.freshBodies = freshBodies;
        origin = client.origin(); chat = client.chat(); self = client.userId();
    }
    static PurplePlaylistsSyncDiscovery capture(PurplePlaylistsWorker worker) {
        if (worker == null || !worker.downloadCurrent()) return null;
        PurpleSyncChatCore.Peer selected = PurpleSyncChat.boundChat(worker.accountSlot());
        PurpleSyncClient client = PurpleSyncChat.boundPlaylistsClient(worker.accountSlot(), selected, worker::downloadCurrent);
        return client == null ? null : new PurplePlaylistsSyncDiscovery(client, worker::downloadCurrent);
    }
    void checkpoint(Checkpoint value) {
        if (started.get()) throw new IllegalStateException("PlaylistScanAlreadyStarted");
        checkpoint = value;
    }
    boolean current() {
        return !closed.get() && scope.getAsBoolean() && client.isActive() && self == client.userId()
                && origin.equals(client.origin()) && chat.equals(client.chat());
    }
    void start(byte[] library, byte[] state, boolean manual, long now, Consumer<Result> done) {
        if (library == null || state == null || done == null || now < 0 || !started.compareAndSet(false, true))
            throw new IllegalArgumentException("InvalidPlaylistDiscoveryStart");
        initialLibrary = library.clone(); initialState = state.clone(); this.done = done; this.manual = manual; this.now = now;
        put(request, "authorized", true); put(request, "trigger", manual ? "Manual" : "Automatic");
        put(request, "libraryRead", "Succeeded"); put(request, "stateRead", "Succeeded");
        put(request, "discoverySelf", Long.toString(self)); put(request, "chat", chat.token());
        put(request, "listingSucceeded", false); put(request, "listingExhausted", false);
        put(request, "byIdSucceeded", true); put(request, "listed", listed); put(request, "byId", byId);
        put(request, "payloads", new JSONArray()); put(request, "clock", clock(self, chat.token(), now));
        enqueue(() -> {
            PurplePlaylistsSyncCore.Result check = planInitial();
            if (!"Planned".equals(check.detail().optString("status"))) { finish("LocalSyncUnavailable"); return; }
            history(0);
        });
    }
    private void enqueue(Runnable task) {
        client.runOnWorker(() -> {
            if (!current()) { close(); return; }
            if (finished) return;
            try { task.run(); }
            catch (RuntimeException error) { finish("InvalidDiscoveryResponse"); }
        });
    }
    private PurplePlaylistsSyncCore.Result planInitial() {
        return PurplePlaylistsSyncCore.plan(bytes(request), initialLibrary, initialState,
                new byte[0][], self);
    }
    private final class Pending<T> {
        private final AtomicBoolean answered = new AtomicBoolean();
        private Object handle;
        private boolean attached, canceled;
        private final boolean download;
        private final Consumer<T> reply;
        Pending(boolean download, Consumer<T> reply) { this.download = download; this.reply = reply; }
        synchronized void attach(Object value) {
            handle = value; attached = true;
            if (canceled) stop(value);
        }
        void complete(T value) {
            if (!answered.compareAndSet(false, true)) return;
            enqueue(() -> { if (pending == this) { pending = null; reply.accept(value); } });
        }
        synchronized void cancel() {
            if (canceled) return;
            canceled = true; answered.set(true);
            if (attached) stop(handle);
        }
        private void stop(Object value) {
            if (download) { if (value != null) client.cancelDownload(value); }
            else if (value instanceof Integer && ((Integer) value) != 0) client.cancelRequest((Integer) value);
        }
    }
    private <T> void issue(boolean download, Function<PurpleSyncClient.Reply<T>, Object> send, Consumer<T> reply) {
        if (!current() || pending != null) throw new IllegalStateException("PlaylistRequestScope");
        Pending<T> next = new Pending<>(download, reply); pending = next;
        try { next.attach(send.apply(next::complete)); }
        catch (RuntimeException error) { next.cancel(); pending = null; throw error; }
        if (!current()) next.cancel();
    }
    private void history(int offset) {
        this.<PurpleSyncClient.History>issue(false, reply -> client.requestHistory(offset, PAGE_SIZE, reply), page -> {
            if (page == null || page.messages == null || page.error != null || page.messages.size() > PAGE_SIZE
                    || page.total < 0) { finish("HistoryFailed"); return; }
            if (page.declaredTotal != null) {
                if (page.declaredTotal < 0 || declaredTotal != null && !declaredTotal.equals(page.declaredTotal)) {
                    finish("HistoryChanged"); return;
                }
                declaredTotal = page.declaredTotal;
            }
            long previous = offset == 0 ? Long.MAX_VALUE : offset;
            JSONObject state = object(initialState), prior = state.optJSONObject("memo");
            for (PurpleSyncClient.Message message : page.messages) {
                if (message == null || message.id <= 0 || message.id >= previous
                        || !client.contains(message)) { finish("IncompleteHistory"); return; }
                previous = message.id;
                byte[] raw = client.playlistMetadata(message);
                JSONObject classification = PurplePlaylistsSyncCore.metadata(raw, self).detail();
                String verdict = classification.optString("verdict");
                if (!"Ignored".equals(verdict) || prior != null && prior.has(Integer.toString(message.id))) {
                    JSONObject meta = capture(message); listed.put(meta);
                    if ("Candidate".equals(verdict) && needsRead(message, state, classification.optInt("classifierVersion", -1))) reads.add(message.id);
                    else messages.remove(message.id);
                }
            }
            historyCount += page.messages.size();
            if (declaredTotal != null && historyCount > declaredTotal) { finish("HistoryChanged"); return; }
            boolean end = page.messages.isEmpty() || declaredTotal != null && historyCount == declaredTotal;
            if (end) {
                if (declaredTotal != null && historyCount != declaredTotal) { finish("IncompleteHistory"); return; }
                put(request, "listingSucceeded", true); put(request, "listingExhausted", true);
                JSONArray pendingIds = array(planInitial().detail().optJSONObject("inventory"), "pendingById");
                for (int i = 0; i < pendingIds.length(); ++i) lookups.add(Integer.parseInt(stringAt(pendingIds, i)));
                afterReads = this::lookupNext;
            } else {
                int next = (int) previous; afterReads = () -> history(next);
            }
            readNext();
        });
    }
    private JSONObject capture(PurpleSyncClient.Message message) {
        byte[] raw = client.playlistMetadata(message);
        if (raw == null) throw new IllegalArgumentException("PlaylistMetadataUnavailable");
        JSONObject meta = object(raw);
        if (!Integer.toString(message.id).equals(meta.optString("message"))
                || !Long.toUnsignedString(message.documentId).equals(meta.optString("document")))
            throw new IllegalArgumentException("PlaylistMetadataChanged");
        PurplePlaylistsSyncCore.metadata(raw, self);
        messages.put(message.id, message); metadata.put(message.id, raw.clone());
        return meta;
    }
    private boolean needsRead(PurpleSyncClient.Message message, JSONObject state, int classifierVersion) {
        if (freshBodies || classifierVersion <= 0) return true;
        JSONObject memo = state.optJSONObject("memo");
        if (!chat.token().equals(state.optString("chat")) || memo == null) return true;
        JSONObject entry = memo.optJSONObject(Integer.toString(message.id));
        if (entry == null || !Long.toUnsignedString(message.documentId).equals(entry.optString("document_id"))) return true;
        return !"valid".equals(entry.optString("state")) && entry.optInt("classifier") != classifierVersion;
    }
    private void lookupNext() {
        if (lookups.isEmpty()) { finish("None"); return; }
        int id = lookups.removeFirst();
        this.<PurpleSyncClient.Lookup>issue(false, reply -> client.requestMessage(id, reply), value -> {
            JSONObject meta;
            if (value != null && value.status == PurpleSyncClient.LookupStatus.Found && value.message != null
                    && value.message.id == id && client.contains(value.message)) {
                meta = capture(value.message); put(meta, "outcome", "Present");
                int classifierVersion = PurplePlaylistsSyncCore.metadata(metadata.get(id), self).detail().optInt("classifierVersion", -1);
                if (needsRead(value.message, object(initialState), classifierVersion)) reads.add(id); else messages.remove(id);
            } else {
                meta = absent(id);
                boolean gone = value != null && value.status == PurpleSyncClient.LookupStatus.Missing;
                put(meta, "outcome", gone ? "Gone" : "Failed");
                if (!gone) put(request, "byIdSucceeded", false);
            }
            byId.put(meta); afterReads = this::lookupNext; readNext();
        });
    }
    private void readNext() {
        if (reads.isEmpty()) {
            Runnable next = afterReads; afterReads = null;
            if (next != null) enqueue(next);
            return;
        }
        int id = reads.removeFirst();
        PurpleSyncClient.Message message = messages.remove(id);
        if (message == null || message.size < 0 || message.size > MAX_BYTES) { enqueue(this::readNext); return; }
        byte[] before = metadata.get(id);
        if (!"Candidate".equals(PurplePlaylistsSyncCore.metadata(before, self).detail().optString("verdict"))) {
            enqueue(this::readNext); return;
        }
        this.<File>issue(true, reply -> client.download(message, reply), file -> {
            byte[] downloaded = PurplePlaylistsSyncFiles.read(file, message.size, this::current);
            if (downloaded == null) { readNext(); return; }
            this.<PurpleSyncClient.Lookup>issue(false, reply -> client.requestMessage(id, reply), fresh -> {
                if (fresh != null && fresh.status == PurpleSyncClient.LookupStatus.Found && fresh.message != null
                        && fresh.message.id == id && fresh.message.documentId == message.documentId
                        && fresh.message.size == message.size && fresh.message.editDate == message.editDate
                        && client.contains(fresh.message) && Arrays.equals(before, client.playlistMetadata(fresh.message))) {
                    JSONObject proof = new JSONObject();
                    put(proof, "chat", chat.token()); put(proof, "message", Integer.toString(id));
                    put(proof, "document", Long.toUnsignedString(message.documentId));
                    put(proof, "origin", "DownloadedThisStep"); put(proof, "readSucceeded", true);
                    put(proof, "acknowledged", false); put(proof, "expectedHash", JSONObject.NULL);
                    accept(message, before, proof, downloaded); return;
                }
                readNext();
            });
        });
    }
    private void accept(PurpleSyncClient.Message message, byte[] metadata, JSONObject proof, byte[] body) {
        JSONObject batch = object(bytes(request));
        put(batch, "listingSucceeded", true); put(batch, "listingExhausted", false);
        put(batch, "listed", new JSONArray().put(object(metadata))); put(batch, "byId", new JSONArray());
        put(batch, "payloads", new JSONArray().put(proof));
        AtomicBoolean answered = new AtomicBoolean();
        Consumer<PurplePlaylistsSyncCore.Result> accepted = result -> {
            if (!answered.compareAndSet(false, true)) return;
            enqueue(() -> {
                if (result == null || !"Planned".equals(result.detail().optString("status"))) { finish("CheckpointFailed"); return; }
                initialLibrary = result.library(); initialState = result.state();
                JSONObject entries = object(initialState).optJSONObject("memo");
                String id = Integer.toString(message.id); JSONObject entry = entries == null ? null : entries.optJSONObject(id);
                if (entry != null && Long.toUnsignedString(message.documentId).equals(entry.optString("document_id"))) {
                    put(acceptedMemo, id, entry);
                    receipts.add(new Receipt(proof, metadata, body, "valid".equals(entry.optString("state")) ? entry.optString("canonical_hash") : null));
                }
                readNext();
            });
        };
        if (checkpoint != null) checkpoint.merge(bytes(batch), body, accepted);
        else accepted.accept(PurplePlaylistsSyncCore.partial(bytes(batch), body, initialLibrary, initialState, self));
    }
    private void finish(String error) {
        if (finished || !current()) return;
        finished = true;
        Pending<?> active = pending; pending = null;
        if (active != null) active.cancel();
        Result result = new Result(this, error);
        initialLibrary = null; initialState = null;
        done.accept(result);
    }
    @Override public void close() {
        closed.set(true);
        Pending<?> active = pending;
        if (active != null) active.cancel();
    }
    private static byte[] digest(byte[] value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte part : value) result.append(Character.forDigit((part & 255) >>> 4, 16)).append(Character.forDigit(part & 15, 16));
        return result.toString();
    }
    private static byte[] bytes(JSONObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
    private static byte[] copy(byte[] value) { return value == null ? null : value.clone(); }
    private static JSONObject object(byte[] value) {
        try { return new JSONObject(new String(value, StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalArgumentException("InvalidPlaylistDiscovery", error); }
    }
    private static void put(JSONObject value, String key, Object field) {
        try { value.put(key, field); }
        catch (Exception error) { throw new IllegalArgumentException("InvalidPlaylistDiscovery", error); }
    }
    private static JSONObject clock(long self, String chat, long now) {
        JSONObject result = new JSONObject();
        put(result, "self", Long.toString(self)); put(result, "chat", chat);
        put(result, "now", Long.toString(now)); put(result, "succeeded", true); return result;
    }
    private static JSONArray array(JSONObject value, String key) {
        JSONArray result = value == null ? null : value.optJSONArray(key);
        if (result == null) throw new IllegalArgumentException("MissingPlaylistDiscoveryArray");
        return result;
    }
    private static JSONObject objectAt(JSONArray value, int index) {
        JSONObject result = value.optJSONObject(index);
        if (result == null) throw new IllegalArgumentException("MissingPlaylistDiscoveryEntry");
        return result;
    }
    private static String stringAt(JSONArray value, int index) {
        Object result = value.opt(index);
        if (!(result instanceof String)) throw new IllegalArgumentException("MissingPlaylistDiscoveryId");
        return (String) result;
    }
    private static JSONObject absent(int id) {
        JSONObject result = new JSONObject();
        put(result, "message", Integer.toString(id)); put(result, "document", "0"); put(result, "regular", false);
        put(result, "forwarded", false); put(result, "isDocument", false); put(result, "caption", "");
        put(result, "mime", ""); put(result, "names", new JSONArray()); put(result, "ownership", "Unknown"); return result;
    }
}
