package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

final class PurplePlaylistsSyncPublication implements AutoCloseable {
    static final String NAME = "Purple playlists.json.gz", MIME = "application/gzip";
    static final String CAPTION = "Purple playlists sync data #purpleplaylists";
    static final int MAX_BYTES = 10 * 1024 * 1024, PART_BYTES = 256 * 1024;
    enum Status { NotSent, OutcomeUnknown, Confirmed }
    interface Journal { void prepare(Attempt attempt, Consumer<Boolean> done); }
    interface Port {
        int upload(long file, int part, byte[] bytes, PurpleSyncClient.Reply<Boolean> done);
        int send(TLRPC.TL_messages_sendMedia request, PurpleSyncClient.Reply<Rpc> done);
        TLRPC.InputPeer input();
        int receipt(TLObject response, long random);
        void schedule(Runnable task, long delay);
        void unschedule(Runnable task);
    }
    static final class Rpc {
        final TLObject response;
        final String error;
        Rpc(TLObject response, String error) { this.response = response; this.error = error; }
    }
    static final class Attempt {
        final PurpleSyncChatOrigin origin;
        final PurpleSyncChatCore.Peer chat;
        final long randomId;
        final String canonicalHash, compressedHash;
        final int size;
        private final byte[] bytes;
        private Attempt(PurplePlaylistsSyncPublication owner, byte[] bytes, long randomId, String hash) {
            origin = owner.origin; chat = owner.chat; this.randomId = randomId;
            canonicalHash = hash; compressedHash = digest("SHA-256", bytes);
            size = bytes.length; this.bytes = bytes.clone();
        }
        byte[] bytes() { return bytes.clone(); }
    }
    static final class Result {
        final Status status;
        final String error;
        final Attempt attempt;
        final int messageId;
        final boolean intentRequested, sendIssued;
        private final PurplePlaylistsSyncPublication owner;
        private final byte[] metadata, payload;
        private Result(PurplePlaylistsSyncPublication owner, Status status, String error, byte[] meta, byte[] payload) {
            this.owner = owner; this.status = status; this.error = error; attempt = owner.attempt;
            messageId = owner.messageId; intentRequested = owner.intentRequested; sendIssued = owner.sendIssued;
            metadata = copy(meta); this.payload = copy(payload);
        }
        boolean current() { return owner.current(); }
        byte[] metadata() { return current() && status == Status.Confirmed ? copy(metadata) : null; }
        byte[] payload() { return current() && status == Status.Confirmed ? copy(payload) : null; }
        JSONObject proof() {
            if (!current() || status != Status.Confirmed) throw new IllegalStateException("UnconfirmedPlaylistPublication");
            JSONObject value = object(metadata);
            JSONObject proof = new JSONObject();
            put(proof, "chat", attempt.chat.token()); put(proof, "message", Integer.toString(messageId));
            put(proof, "document", value.optString("document")); put(proof, "origin", "PublishedThisStep");
            put(proof, "readSucceeded", true); put(proof, "acknowledged", true);
            put(proof, "expectedHash", attempt.canonicalHash); return proof;
        }
    }

    private final PurpleSyncClient client;
    private final PurpleSyncTransport.PostAuthority authority;
    private final BooleanSupplier scope;
    private final Port port;
    private final PurpleSyncChatOrigin origin;
    private final PurpleSyncChatCore.Peer chat;
    private final long self;
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();
    private final Runnable timeout = () -> enqueue(() -> fail("PublicationTimeout"));
    private volatile Pending<?> pending;
    private Consumer<Result> done;
    private Attempt attempt;
    private long fileId;
    private int part, messageId;
    private boolean finished, intentRequested, timerArmed;
    private volatile boolean sendIssued;

    PurplePlaylistsSyncPublication(PurpleSyncClient client, PurpleSyncTransport.PostAuthority authority,
            BooleanSupplier scope, Port port) {
        if (client == null || client.origin() == null || client.chat() == null || authority == null
                || scope == null || port == null || client.userId() <= 0 || client.origin().userId != client.userId())
            throw new IllegalArgumentException("MissingPlaylistPublicationRoute");
        this.client = client; this.authority = authority; this.scope = scope; this.port = port;
        origin = client.origin(); chat = client.chat(); self = client.userId();
    }
    private volatile boolean confirmed;
    boolean outcomeUnknown() { return intentRequested && !confirmed; }
    boolean sendIssued() { return sendIssued; }
    boolean current() {
        return !closed.get() && scope.getAsBoolean() && client.isActive() && authority.current()
                && self == client.userId() && origin.equals(client.origin()) && chat.equals(client.chat())
                && (authority.permits(origin, chat) || authority.automatic(origin, chat));
    }
    void start(byte[] compressed, long randomId, Journal journal, Consumer<Result> done) {
        if (done == null || journal == null || closed.get() || !started.compareAndSet(false, true))
            throw new IllegalArgumentException("InvalidPlaylistPublicationStart");
        this.done = done;
        byte[] captured = compressed != null && compressed.length <= MAX_BYTES ? copy(compressed) : null;
        enqueue(() -> {
            if (captured == null || captured.length == 0 || captured.length > MAX_BYTES || randomId == 0) {
                fail("InvalidPublication"); return;
            }
            PurplePlaylistsSyncCore.Result checked = PurplePlaylistsSyncCore.classify(validationMetadata(), captured, self);
            String hash = checked.detail().optString("hash");
            if (!"Valid".equals(checked.detail().optString("status")) || !hash.matches("[0-9a-f]{64}")) {
                fail("InvalidPublication"); return;
            }
            attempt = new Attempt(this, captured, randomId, hash);
            fileId = nonzeroRandom(); timerArmed = true; port.schedule(timeout, 5 * 60 * 1000L);
            uploadNext(journal);
        });
    }
    private void enqueue(Runnable action) {
        client.runOnWorker(() -> {
            if (finished) return;
            try {
                if (!current()) { fail("PublicationRouteChanged"); return; }
                action.run();
            } catch (RuntimeException error) { fail("PublicationTransportFailed"); }
        });
    }
    private final class Pending<T> {
        private final AtomicBoolean answered = new AtomicBoolean();
        private final Consumer<T> reply;
        private final boolean download;
        private Object token;
        private boolean attached, canceled;
        Pending(boolean download, Consumer<T> reply) { this.download = download; this.reply = reply; }
        synchronized void attach(Object value) { token = value; attached = true; if (canceled) stop(value); }
        void complete(T value) {
            if (!answered.compareAndSet(false, true)) return;
            enqueue(() -> { if (pending == this) { pending = null; reply.accept(value); } });
        }
        synchronized void cancel() {
            if (canceled) return;
            canceled = true; answered.set(true); if (attached) stop(token);
        }
        private void stop(Object token) {
            if (download) { if (token != null) client.cancelDownload(token); }
            else if (token instanceof Integer && (Integer) token != 0) client.cancelRequest((Integer) token);
        }
    }
    private <T> void issue(boolean download, Function<PurpleSyncClient.Reply<T>, Object> wire, Consumer<T> reply) {
        if (!current() || pending != null) throw new IllegalStateException("PlaylistPublicationRequestScope");
        Pending<T> active = new Pending<>(download, reply); pending = active;
        try { active.attach(wire.apply(active::complete)); }
        catch (RuntimeException error) { active.cancel(); if (pending == active) pending = null; throw error; }
        if (!current()) active.cancel();
    }
    private void uploadNext(Journal journal) {
        int count = (attempt.size + PART_BYTES - 1) / PART_BYTES;
        if (part == count) { renew(() -> journal(journal)); return; }
        int index = part, offset = index * PART_BYTES;
        byte[] bytes = Arrays.copyOfRange(attempt.bytes, offset, Math.min(offset + PART_BYTES, attempt.size));
        this.<Boolean>issue(false, reply -> port.upload(fileId, index, bytes, reply), accepted -> {
            if (!Boolean.TRUE.equals(accepted)) { fail("PartUploadFailed"); return; }
            ++part; uploadNext(journal);
        });
    }
    private void renew(Runnable next) {
        AtomicBoolean once = new AtomicBoolean();
        authority.check(ready -> {
            if (once.compareAndSet(false, true)) enqueue(() -> {
                if (Boolean.TRUE.equals(ready)) next.run(); else fail("PostingRefused");
            });
        });
    }
    private void journal(Journal journal) {
        intentRequested = true;
        AtomicBoolean once = new AtomicBoolean();
        journal.prepare(attempt, accepted -> {
            if (once.compareAndSet(false, true)) enqueue(() -> {
                if (!Boolean.TRUE.equals(accepted)) { fail("PublicationIntentUnconfirmed"); return; }
                renew(this::send);
            });
        });
    }
    private void send() {
        TLRPC.InputPeer peer = port.input();
        if (!PurpleSyncChatRequests.matches(chat, peer)) { fail("PublicationPeerChanged"); return; }
        TLRPC.TL_inputFile file = new TLRPC.TL_inputFile();
        file.id = fileId; file.parts = part; file.name = NAME; file.md5_checksum = digest("MD5", attempt.bytes);
        TLRPC.TL_inputMediaUploadedDocument media = new TLRPC.TL_inputMediaUploadedDocument();
        media.file = file; media.mime_type = MIME; media.force_file = true;
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = NAME; media.attributes.add(name);
        TLRPC.TL_messages_sendMedia request = new TLRPC.TL_messages_sendMedia();
        request.peer = peer; request.media = media; request.message = CAPTION;
        request.random_id = attempt.randomId; request.silent = true;
        this.<Rpc>issue(false, reply -> {
            sendIssued = true; authority.issued();
            if (!current()) { reply.onReply(new Rpc(null, "PublicationRouteChanged")); return 0; }
            return port.send(request, reply);
        }, result -> {
            if (result == null || result.error != null || (messageId = port.receipt(result.response, attempt.randomId)) <= 0) {
                fail("PublicationReceiptUnknown"); return;
            }
            lookup();
        });
    }
    private void lookup() {
        this.<PurpleSyncClient.Lookup>issue(false, reply -> client.requestMessage(messageId, reply), lookup -> {
            PurpleSyncClient.Message message = found(lookup);
            byte[] meta = message == null ? null : client.playlistMetadata(message);
            if (meta == null || message.size != attempt.size
                    || !"Candidate".equals(PurplePlaylistsSyncCore.metadata(meta, self).detail().optString("verdict"))) {
                fail("PublicationReadbackFailed"); return;
            }
            this.<File>issue(true, reply -> client.download(message, reply), file -> {
                byte[] read = PurplePlaylistsSyncFiles.read(file, attempt.size, this::current);
                if (!Arrays.equals(attempt.bytes, read)) { fail("PublicationBytesChanged"); return; }
                this.<PurpleSyncClient.Lookup>issue(false, reply -> client.requestMessage(messageId, reply), fresh -> {
                    PurpleSyncClient.Message after = found(fresh);
                    if (after == null || after.documentId != message.documentId || after.size != message.size
                            || after.editDate != message.editDate || !Arrays.equals(meta, client.playlistMetadata(after))) {
                        fail("PublicationMetadataChanged"); return;
                    }
                    PurplePlaylistsSyncCore.Result classified = PurplePlaylistsSyncCore.classify(meta, read, self);
                    if (!"Valid".equals(classified.detail().optString("status"))
                            || !attempt.canonicalHash.equals(classified.detail().optString("hash"))) {
                        fail("PublicationHashChanged"); return;
                    }
                    finish(Status.Confirmed, "None", meta, read);
                });
            });
        });
    }
    private PurpleSyncClient.Message found(PurpleSyncClient.Lookup value) {
        return value != null && value.status == PurpleSyncClient.LookupStatus.Found && value.message != null
                && value.message.id == messageId && client.contains(value.message) ? value.message : null;
    }
    private byte[] validationMetadata() {
        JSONObject value = new JSONObject();
        put(value, "regular", true); put(value, "forwarded", false); put(value, "isDocument", true);
        put(value, "caption", CAPTION); put(value, "mime", MIME); put(value, "names", new JSONArray().put(NAME));
        put(value, "ownership", "Own"); put(value, "message", "1"); put(value, "document", "1");
        put(value, "read", "Succeeded"); return value.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void fail(String error) { finish(intentRequested ? Status.OutcomeUnknown : Status.NotSent, error, null, null); }
    private void finish(Status status, String error, byte[] meta, byte[] payload) {
        if (finished) return;
        confirmed = status == Status.Confirmed;
        finished = true; Pending<?> active = pending; pending = null;
        if (active != null) active.cancel();
        if (timerArmed) { timerArmed = false; port.unschedule(timeout); }
        if (done != null) done.accept(new Result(this, status, error, meta, payload));
    }
    @Override public void close() {
        closed.set(true); Pending<?> active = pending; if (active != null) active.cancel();
        client.runOnWorker(() -> fail("PublicationClosed"));
    }
    private static long nonzeroRandom() { long value; SecureRandom random = new SecureRandom(); do { value = random.nextLong(); } while (value == 0); return value; }
    private static String digest(String kind, byte[] bytes) {
        try {
            byte[] value = MessageDigest.getInstance(kind).digest(bytes);
            StringBuilder text = new StringBuilder(value.length * 2);
            for (byte item : value) text.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            return text.toString();
        } catch (Exception error) { throw new IllegalStateException("PlaylistDigestUnavailable", error); }
    }
    private static byte[] copy(byte[] value) { return value == null ? null : value.clone(); }
    private static JSONObject object(byte[] value) {
        try { return new JSONObject(new String(value, StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalArgumentException("InvalidPlaylistPublicationMetadata", error); }
    }
    private static void put(JSONObject value, String key, Object field) {
        try { value.put(key, field); }
        catch (Exception error) { throw new IllegalArgumentException("InvalidPlaylistPublicationMetadata", error); }
    }
}
