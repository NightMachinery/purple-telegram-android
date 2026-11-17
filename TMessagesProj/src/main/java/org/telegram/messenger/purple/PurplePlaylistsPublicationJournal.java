package org.telegram.messenger.purple;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

final class PurplePlaylistsPublicationJournal implements PurplePlaylistsSyncPublication.Journal {
    private static final int MAGIC = 0x50504a31, VERSION = 1;
    static final int RECORD_LIMIT = PurplePlaylistsSyncPublication.MAX_BYTES + 4096;
    static final int MAX_ROUTES = 4;
    static final int LIMIT = MAX_ROUTES * (RECORD_LIMIT + 4) + 44;
    enum Status { Empty, Pending, Confirmed, Retired, Newer, Invalid, Foreign, Unavailable }
    static final class Read {
        final Status status;
        final long randomId;
        final String canonicalHash, compressedHash;
        final int messageId;
        final String documentId;
        private final PurplePlaylistsPublicationJournal owner;
        private final PurplePlaylistsStore.File file;
        private final byte[] payload;
        private Read(PurplePlaylistsPublicationJournal owner, Status status, PurplePlaylistsStore.File file,
                long randomId, String canonicalHash, String compressedHash, byte[] payload, int messageId, String documentId) {
            this.owner = owner; this.status = status; this.file = file; this.randomId = randomId;
            this.canonicalHash = canonicalHash; this.compressedHash = compressedHash;
            this.payload = payload == null ? null : payload.clone(); this.messageId = messageId; this.documentId = documentId;
        }
        byte[] payload() { return payload == null ? null : payload.clone(); }
        boolean unresolved() { return status == Status.Pending || status == Status.Confirmed; }
    }
    private final Thread thread = Thread.currentThread();
    private final PurplePlaylistsStore.Physical physical;
    private final PurpleSyncChatOrigin origin;
    private final PurpleSyncChatCore.Peer chat;
    private final BooleanSupplier scope;
    private final String loginHash;
    private boolean busy;

    PurplePlaylistsPublicationJournal(PurplePlaylistsStore.Physical physical, PurpleSyncChatOrigin origin,
            PurpleSyncChatCore.Peer chat, BooleanSupplier scope) {
        if (physical == null || origin == null || chat == null || scope == null)
            throw new IllegalArgumentException("MissingPublicationJournalRoute");
        this.physical = physical; this.origin = origin; this.chat = chat; this.scope = scope;
        loginHash = hash(origin.login.getBytes(StandardCharsets.UTF_8));
    }
    static PurplePlaylistsSyncPublication.Journal onWorker(PurplePlaylistsWorker worker,
            PurpleSyncChatOrigin origin, PurpleSyncChatCore.Peer chat, BooleanSupplier scope) {
        if (worker == null || origin == null || chat == null || scope == null || worker.accountSlot() != origin.slot)
            throw new IllegalArgumentException("MissingPublicationJournalWorker");
        return (attempt, done) -> {
            if (done == null) throw new IllegalArgumentException("MissingPublicationJournalCallback");
            AtomicBoolean answered = new AtomicBoolean();
            Consumer<Boolean> answer = value -> { if (answered.compareAndSet(false, true)) done.accept(value); };
            if (!worker.postStore(null, (store, self) -> {
                if (self != origin.userId || !worker.downloadCurrent() || !scope.getAsBoolean()) { answer.accept(false); return; }
                PurplePlaylistsPublicationJournal journal = new PurplePlaylistsPublicationJournal(store.media(), origin, chat,
                        () -> worker.downloadCurrent() && scope.getAsBoolean());
                journal.prepare(attempt, answer);
            }, () -> answer.accept(false), false)) answer.accept(false);
        };
    }
    private boolean current() {
        return Thread.currentThread() == thread && scope.getAsBoolean() && physical.current();
    }
    private Read empty(Status status, PurplePlaylistsStore.File file) {
        return new Read(this, status, file, 0, null, null, null, 0, null);
    }
    private static final class JournalFormat extends IOException {
        final Status status;
        JournalFormat(Status status) { this.status = status; }
    }
    private java.util.List<byte[]> records(byte[] encoded) throws IOException {
        if (encoded.length < 8 || encoded.length > LIMIT) throw new JournalFormat(Status.Invalid);
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != MAGIC) throw new JournalFormat(Status.Invalid);
        int version = input.readInt();
        if (version == VERSION) return java.util.Collections.singletonList(encoded);
        if (version > 2) throw new JournalFormat(Status.Newer);
        if (version != 2 || encoded.length < 44) throw new JournalFormat(Status.Invalid);
        byte[] body = Arrays.copyOf(encoded, encoded.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(encoded, body.length, encoded.length)))
            throw new JournalFormat(Status.Invalid);
        int count = input.readInt();
        if (count <= 0 || count > MAX_ROUTES) throw new JournalFormat(Status.Invalid);
        java.util.List<byte[]> result = new java.util.ArrayList<>();
        for (int i = 0; i != count; ++i) {
            int length = input.readInt();
            if (length < 40 || length > RECORD_LIMIT || length > input.available() - 32)
                throw new JournalFormat(Status.Invalid);
            byte[] record = new byte[length]; input.readFully(record); result.add(record);
        }
        if (input.available() != 32) throw new JournalFormat(Status.Invalid);
        return result;
    }
    private String route(byte[] encoded) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        input.readInt(); input.readInt(); input.readByte();
        int slot = input.readInt(), environment = input.readInt(); long self = input.readLong();
        String login = input.readUTF(), peer = input.readUTF();
        if (slot != origin.slot || environment != origin.environment || self != origin.userId)
            throw new JournalFormat(Status.Foreign);
        return login + ":" + peer;
    }
    private Read decode(PurplePlaylistsStore.File file, byte[] encoded) throws Exception {
        if (encoded.length < 8 || encoded.length > RECORD_LIMIT) return empty(Status.Invalid, file);
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != MAGIC) return empty(Status.Invalid, file);
        int version = input.readInt();
        if (version > VERSION) return empty(Status.Newer, file);
        if (version != VERSION || encoded.length < 40) return empty(Status.Invalid, file);
        byte[] body = Arrays.copyOf(encoded, encoded.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(encoded, body.length, encoded.length)))
            return empty(Status.Invalid, file);
        int phase = input.readUnsignedByte();
        int slot = input.readInt(), environment = input.readInt(); long self = input.readLong();
        String login = input.readUTF(), peer = input.readUTF(); long random = input.readLong();
        String canonical = input.readUTF(), compressed = input.readUTF();
        int message = input.readInt(); String document = input.readUTF(); int length = input.readInt();
        if ((phase != 1 && phase != 2 && phase != 3) || random == 0 || length <= 0 || length > PurplePlaylistsSyncPublication.MAX_BYTES
                || input.available() != length + 32 || !canonical.matches("[0-9a-f]{64}")
                || !compressed.matches("[0-9a-f]{64}") || !login.matches("[0-9a-f]{64}")
                || (phase == 1 ? message != 0 || !document.isEmpty() : message <= 0 || !document.matches("[1-9][0-9]{0,19}")))
            return empty(Status.Invalid, file);
        byte[] payload = new byte[length]; input.readFully(payload);
        if (!compressed.equals(hash(payload))) return empty(Status.Invalid, file);
        PurplePlaylistsSyncCore.Result checked = PurplePlaylistsSyncCore.classify(metadata(), payload, self);
        if (!"Valid".equals(checked.detail().optString("status")) || !canonical.equals(checked.detail().optString("hash")))
            return empty(Status.Invalid, file);
        if (slot != origin.slot || environment != origin.environment || self != origin.userId
                || !loginHash.equals(login) || !chat.token().equals(peer)) return empty(Status.Foreign, file);
        if (!current() || !physical.matches(PurplePlaylistsStore.Name.SyncPublication, file))
            return empty(Status.Unavailable, null);
        return new Read(this, phase == 1 ? Status.Pending : phase == 2 ? Status.Confirmed : Status.Retired, file, random,
                canonical, compressed, payload, message, document);
    }
    Read read() {
        if (busy || !current()) return empty(Status.Unavailable, null);
        try {
            PurplePlaylistsStore.File file = physical.read(PurplePlaylistsStore.Name.SyncPublication);
            if (file == null || !physical.matches(PurplePlaylistsStore.Name.SyncPublication, file) || !current())
                return empty(Status.Unavailable, null);
            if (file.absent()) return empty(Status.Empty, file);
            Read selected = empty(Status.Empty, file);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (byte[] record : records(file.bytes())) {
                Read decoded = decode(file, record);
                if (decoded.status != Status.Foreign && !decoded.unresolved() && decoded.status != Status.Retired)
                    return decoded;
                String key = route(record);
                if (!seen.add(key)) return empty(Status.Invalid, file);
                if (decoded.status != Status.Foreign) selected = decoded;
            }
            return current() && physical.matches(PurplePlaylistsStore.Name.SyncPublication, file)
                    ? selected : empty(Status.Unavailable, null);
        } catch (JournalFormat error) { return empty(error.status, null); }
        catch (Exception error) { return empty(Status.Unavailable, null); }
    }
    private byte[] routeBytes(Read expected, Read next) throws IOException {
        byte[] encoded = encode(next);
        if (expected.file.absent()) return encoded;
        java.util.List<byte[]> retained = new java.util.ArrayList<>();
        String selected = loginHash + ":" + chat.token();
        for (byte[] record : records(expected.file.bytes())) {
            if (!selected.equals(route(record))) retained.add(record);
        }
        if (retained.isEmpty()) return encoded;
        if (retained.size() >= MAX_ROUTES) throw new JournalFormat(Status.Unavailable);
        retained.add(encoded);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC); output.writeInt(2); output.writeInt(retained.size());
        for (byte[] record : retained) { output.writeInt(record.length); output.write(record); }
        output.flush(); byte[] body = bytes.toByteArray(); output.write(digest(body)); output.flush();
        if (bytes.size() > LIMIT) throw new JournalFormat(Status.Invalid);
        return bytes.toByteArray();
    }
    @Override public void prepare(PurplePlaylistsSyncPublication.Attempt attempt, Consumer<Boolean> done) {
        if (done == null) throw new IllegalArgumentException("MissingPublicationJournalCallback");
        boolean accepted = false;
        try {
            if (attempt != null && origin.equals(attempt.origin) && chat.equals(attempt.chat) && current()) {
                Read expected = read();
                if (expected.status == Status.Empty || expected.status == Status.Retired && expected.randomId != attempt.randomId) {
                    Read pending = new Read(this, Status.Pending, expected.file, attempt.randomId, attempt.canonicalHash,
                            attempt.compressedHash, attempt.bytes(), 0, "");
                    Read saved = write(expected, pending, this::current);
                    accepted = saved.status == Status.Pending && saved.randomId == attempt.randomId
                            && saved.compressedHash.equals(attempt.compressedHash) && current();
                }
            }
        } catch (Exception error) { accepted = false; }
        done.accept(accepted);
    }
    Read confirm(Read expected, PurplePlaylistsSyncPublication.Result result) {
        if (result == null || result.status != PurplePlaylistsSyncPublication.Status.Confirmed || !result.current()
                || result.attempt == null || !origin.equals(result.attempt.origin) || !chat.equals(result.attempt.chat)
                || !owns(expected) || expected.randomId != result.attempt.randomId
                || !Arrays.equals(expected.payload, result.payload())) return empty(Status.Unavailable, null);
        JSONObject proof = result.proof();
        return confirm(expected, proof, result::current);
    }
    Read recover(Read expected, PurplePlaylistsSyncDiscovery.Result result) {
        if (!owns(expected) || result == null) return empty(Status.Unavailable, null);
        PurplePlaylistsSyncDiscovery.Recovery recovery = result.publication(origin, chat, expected.payload);
        if (recovery == null) return expected;
        return confirm(expected, recovery.proof(), recovery::current);
    }
    private Read confirm(Read expected, JSONObject proof, BooleanSupplier witness) {
        try {
            if (!owns(expected) || !witness.getAsBoolean() || !chat.token().equals(proof.optString("chat")))
                return empty(Status.Unavailable, null);
            int message = Integer.parseInt(proof.getString("message")); String document = proof.getString("document");
            if (message <= 0 || Long.parseUnsignedLong(document) == 0) return empty(Status.Invalid, null);
            Read next = new Read(this, Status.Confirmed, expected.file, expected.randomId, expected.canonicalHash,
                    expected.compressedHash, expected.payload, message, document);
            return write(expected, next, witness);
        } catch (Exception error) { return empty(Status.Unavailable, null); }
    }
    Read retire(Read expected, PurplePlaylistsSyncCommit.Ack ack) {
        try {
            if (!owns(expected) || expected.status != Status.Confirmed || ack == null
                    || !ack.acknowledges(origin, chat, expected.payload)) return empty(Status.Unavailable, null);
            Read next = new Read(this, Status.Retired, expected.file, expected.randomId, expected.canonicalHash,
                    expected.compressedHash, expected.payload, expected.messageId, expected.documentId);
            return write(expected, next, () -> ack.acknowledges(origin, chat, expected.payload));
        } catch (Exception error) { return empty(Status.Unavailable, null); }
    }
    private boolean owns(Read value) {
        try {
            return !busy && current() && value != null && value.owner == this && value.unresolved()
                    && value.file != null && physical.matches(PurplePlaylistsStore.Name.SyncPublication, value.file);
        } catch (Exception error) { return false; }
    }
    private Read write(Read expected, Read next, BooleanSupplier witness) throws IOException {
        if (busy || !current() || expected.owner != this || expected.file == null) return empty(Status.Unavailable, null);
        byte[] encoded = routeBytes(expected, next);
        PurplePlaylistsStore.Current guard = () -> current() && witness.getAsBoolean();
        busy = true;
        try {
            if (!guard.current()) return empty(Status.Unavailable, null);
            PurplePlaylistsStore.Publication saved = physical.replace(PurplePlaylistsStore.Name.SyncPublication,
                    expected.file, encoded, guard);
            if (saved == null || !saved.durable || saved.file == null || !saved.file.content(encoded)
                    || !physical.matches(PurplePlaylistsStore.Name.SyncPublication, saved.file) || !guard.current())
                return empty(Status.Unavailable, null);
            PurplePlaylistsStore.Publication confirmed = physical.confirm(PurplePlaylistsStore.Name.SyncPublication, saved.file, guard);
            if (confirmed == null || !confirmed.durable || confirmed.file == null || !confirmed.file.content(encoded)
                    || !physical.matches(PurplePlaylistsStore.Name.SyncPublication, confirmed.file) || !guard.current())
                return empty(Status.Unavailable, null);
        } catch (Exception error) { return empty(Status.Unavailable, null); }
        finally { busy = false; }
        Read actual = read();
        return guard.current() && actual.status == next.status && actual.randomId == next.randomId
                && Arrays.equals(actual.payload, next.payload) ? actual : empty(Status.Unavailable, null);
    }
    private byte[] encode(Read value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeByte(value.status == Status.Pending ? 1 : value.status == Status.Confirmed ? 2 : 3);
        out.writeInt(origin.slot); out.writeInt(origin.environment); out.writeLong(origin.userId);
        out.writeUTF(loginHash); out.writeUTF(chat.token()); out.writeLong(value.randomId);
        out.writeUTF(value.canonicalHash); out.writeUTF(value.compressedHash); out.writeInt(value.messageId);
        out.writeUTF(value.documentId); out.writeInt(value.payload.length); out.write(value.payload); out.flush();
        byte[] body = bytes.toByteArray(); out.write(digest(body)); out.flush();
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > RECORD_LIMIT) throw new IOException("PublicationJournalLimit");
        return encoded;
    }
    private static byte[] metadata() {
        return ("{\"regular\":true,\"forwarded\":false,\"isDocument\":true,\"caption\":\""
                + PurplePlaylistsSyncPublication.CAPTION + "\",\"mime\":\"application/gzip\",\"names\":[\""
                + PurplePlaylistsSyncPublication.NAME + "\"],\"ownership\":\"Own\",\"message\":\"1\",\"document\":\"1\",\"read\":\"Succeeded\"}").getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception error) { throw new IllegalStateException("PublicationJournalDigest", error); }
    }
    private static String hash(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte b : digest(value)) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
