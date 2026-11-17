package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

public final class PurplePlaylistsSyncPublicationCases {
    private static int checks;
    private static final byte[] LIBRARY = bytes("{\"format\":1,\"playlists\":{\"pl-aaaaaaaaaaaaaaab\":{\"title\":[\"遠隔 ✓\",100],\"deleted\":[false,100]}},\"purged\":{}}");
    private static void check(boolean value, String label) { ++checks; if (!value) throw new AssertionError(label); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String hex(byte[] value) { StringBuilder s = new StringBuilder(); for (byte b : value) s.append(String.format("%02x", b & 255)); return s.toString(); }
    private static byte[] gzip(byte[] library) { return PurplePlaylistsSyncCore.encode(library, 101).publication(); }
    static TLRPC.TL_message message(int id, int size) {
        TLRPC.TL_message value = new TLRPC.TL_message(); value.id = id; value.date = 100; value.out = true;
        value.peer_id = new TLRPC.TL_peerUser(); value.peer_id.user_id = 101;
        value.from_id = new TLRPC.TL_peerUser(); value.from_id.user_id = 101;
        value.flags = (1 << 8) | (1 << 9); value.message = PurplePlaylistsSyncPublication.CAPTION;
        value.media = new TLRPC.TL_messageMediaDocument(); value.media.document = new TLRPC.TL_document();
        value.media.document.id = 9007199254740993L; value.media.document.size = size;
        value.media.document.mime_type = PurplePlaylistsSyncPublication.MIME;
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = PurplePlaylistsSyncPublication.NAME; value.media.document.attributes.add(name); return value;
    }
    static class Client implements PurpleSyncClient {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<Object> canceled = new ArrayList<>();
        PurpleSyncChatOrigin origin = new PurpleSyncChatOrigin(0, 0, 101, "publication-login");
        PurpleSyncChatCore.Peer chat = PurpleSyncChatCore.Peer.self();
        PurpleSyncChatCore.Ownership ownership = PurpleSyncChatCore.Ownership.Own;
        TLRPC.TL_message message;
        File file;
        int tokens, lookupCount, mode;
        boolean active = true;
        public long userId() { return 101; }
        public PurpleSyncChatCore.Peer chat() { return chat; }
        public PurpleSyncChatOrigin origin() { return origin; }
        public boolean isActive() { return active; }
        public void runOnMain(Runnable task) { tasks.add(task); }
        public void runOnWorker(Runnable task) { tasks.add(task); }
        public void log(String line) { }
        public int requestHistory(int offset, int limit, Reply<History> reply) { throw new AssertionError("No history during publication"); }
        public int requestMessage(int id, Reply<Lookup> reply) {
            ++lookupCount;
            if (mode == 8) { reply.onReply(Lookup.missing()); return ++tokens; }
            if (mode == 10 && lookupCount == 2) ++message.edit_date;
            if (mode == 21 && lookupCount == 2) ++message.media.document.id;
            reply.onReply(Lookup.found(wrap())); if (mode == 17) reply.onReply(Lookup.missing()); return ++tokens;
        }
        Message wrap() { return new Message(message.id, true, false, true, message.message, PurplePlaylistsSyncPublication.NAME,
                chat, message.media.document.id, message.media.document.size, message.edit_date, message); }
        public byte[] playlistMetadata(Message value) { return PurplePlaylistsSyncMetadata.message((TLRPC.Message) value.remote, ownership); }
        public void cancelRequest(int token) { canceled.add(token); }
        public Object download(Message value, Reply<File> reply) { reply.onReply(file); if (mode == 17) reply.onReply(null); return "owned-download"; }
        public void cancelDownload(Object token) { canceled.add(token); }
        public void localCopies(Reply<List<LocalCopy>> reply) { throw new AssertionError("No settings outbox reads"); }
        public File filesDirectory() { throw new AssertionError("No account directory"); }
        public File cacheDirectory() { throw new AssertionError("No account cache"); }
        public long nowMillis() { return 1000; }
        public void send(File file, String caption, String mime, Receipt reply) { throw new AssertionError("No settings uploader"); }
        void drain() { for (int steps = 0; !tasks.isEmpty(); ++steps) { if (steps > 500) throw new AssertionError("Unbounded publication"); tasks.remove().run(); } }
    }
    static final class Authority implements PurpleSyncTransport.PostAuthority {
        final Client client; final PurpleSyncChatOrigin capturedOrigin; final PurpleSyncChatCore.Peer capturedChat; int checks; boolean live = true; Runnable onIssued;
        Authority(Client client) { this.client = client; capturedOrigin = client.origin; capturedChat = client.chat; }
        public boolean current() { return live; }
        public void issued() { if (onIssued != null) onIssued.run(); }
        public boolean permits(PurpleSyncChatOrigin origin, PurpleSyncChatCore.Peer chat) { return origin.equals(capturedOrigin) && chat.equals(capturedChat); }
        public void check(Consumer<Boolean> reply) {
            ++checks; boolean allowed = client.mode != 2 && !(client.mode == 4 && checks == 2);
            reply.accept(allowed); if (client.mode == 17) reply.accept(false);
        }
    }
    static final class Port implements PurplePlaylistsSyncPublication.Port {
        final Client client; final Authority authority;
        final List<byte[]> parts = new ArrayList<>(); final List<Integer> indices = new ArrayList<>();
        final List<String> events = new ArrayList<>(); final ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        PurplePlaylistsSyncPublication operation; PurplePlaylistsSyncPublication.Attempt intent;
        Consumer<Boolean> journalReply; Runnable deadline; PurpleSyncClient.Reply<PurplePlaylistsSyncPublication.Rpc> held;
        TLRPC.TL_messages_sendMedia sent;
        long uploadId; int sends, timers, untimers, journals;
        Port(Client client, Authority authority) { this.client = client; this.authority = authority; }
        public int upload(long file, int index, byte[] data, PurpleSyncClient.Reply<Boolean> reply) {
            if (parts.isEmpty()) uploadId = file;
            check(file == uploadId && file != 0, "multipart upload uses one file identity");
            check(index == parts.size() && data.length > 0 && data.length <= PurplePlaylistsSyncPublication.PART_BYTES, "multipart sequence and part bound");
            parts.add(data.clone()); indices.add(index); uploaded.write(data, 0, data.length);
            if (client.mode == 18) operation.close();
            reply.onReply(client.mode != 1); if (client.mode == 17) reply.onReply(false);
            return ++client.tokens;
        }
        public TLRPC.InputPeer input() {
            if (client.mode == 7) return new TLRPC.TL_inputPeerChat();
            if (client.chat.kind == PurpleSyncChatCore.PeerKind.Channel) {
                TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel(); peer.channel_id = client.chat.id; peer.access_hash = 123; return peer;
            }
            return new TLRPC.TL_inputPeerSelf();
        }
        public int send(TLRPC.TL_messages_sendMedia request, PurpleSyncClient.Reply<PurplePlaylistsSyncPublication.Rpc> reply) {
            ++sends; events.add("send"); sent = request;
            check(journals == 1 && intent != null && authority.checks == 2, "durable intent and renewed rights precede send");
            if (client.mode == 15 || client.mode == 19) held = reply;
            else { reply.onReply(response()); if (client.mode == 17) reply.onReply(response()); }
            return ++client.tokens;
        }
        PurplePlaylistsSyncPublication.Rpc response() {
            if (client.mode == 5) return new PurplePlaylistsSyncPublication.Rpc(null, "TransportUnknown");
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            TL_update.TL_updateMessageID id = new TL_update.TL_updateMessageID(); id.id = 41;
            id.random_id = sent.random_id + (client.mode == 6 ? 1 : 0); updates.updates.add(id);
            TL_update.TL_updateNewMessage added = new TL_update.TL_updateNewMessage(); added.message = client.message;
            if (client.chat.kind == PurpleSyncChatCore.PeerKind.Channel && client.mode != 23) {
                TL_update.TL_updateNewChannelMessage channel = new TL_update.TL_updateNewChannelMessage(); channel.message = client.message; updates.updates.add(channel);
            } else updates.updates.add(added); return new PurplePlaylistsSyncPublication.Rpc(updates, null);
        }
        public int receipt(TLObject response, long random) {
            PurpleSyncChatOrigin origin = client.origin;
            PurpleSyncChatFacts.MappedPeer facts = new PurpleSyncChatFacts.MappedPeer(origin, client.chat,
                client.chat.kind == PurpleSyncChatCore.PeerKind.Channel ? PurpleSyncChatCore.Kind.Supergroup : PurpleSyncChatCore.Kind.Self,
                PurpleSyncChatCore.Truth.True, PurpleSyncChatCore.Truth.True, null, Collections.singleton(101L));
            return PurpleSyncChatUpload.receipt(new PurpleSyncChatIO(origin, client.chat, facts), response, random, 0);
        }
        public void schedule(Runnable task, long delay) { check(delay == 300000, "publication has finite deadline"); deadline = task; ++timers; }
        public void unschedule(Runnable task) { check(task == deadline, "only owned deadline removed"); ++untimers; }
        void journal(PurplePlaylistsSyncPublication.Attempt value, Consumer<Boolean> done) {
            ++journals; events.add("journal"); intent = value;
            if (client.mode == 26) journalReply = done; else done.accept(client.mode != 3); if (client.mode == 17) done.accept(false);
        }
    }
    private static final class Run implements AutoCloseable {
        final Client client = new Client(); final Authority authority; final Port port;
        final PurplePlaylistsSyncPublication operation;
        final List<PurplePlaylistsSyncPublication.Result> results = new ArrayList<>();
        final byte[] payload;
        Run(Path root, byte[] bytes, int mode) throws Exception {
            client.mode = mode; payload = bytes;
            client.message = message(41, bytes.length);
            if (mode == 22 || mode == 23) {
                client.chat = PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, 303);
                client.message.peer_id = new TLRPC.TL_peerChannel(); client.message.peer_id.channel_id = 303;
            }
            Path file = root.resolve("payload-" + mode + "-" + System.nanoTime() + ".gz"); Files.write(file, bytes);
            client.file = file.toFile();
            if (mode == 9) Files.write(file, new byte[bytes.length]);
            if (mode == 11) client.ownership = PurpleSyncChatCore.Ownership.Other;
            if (mode == 12) client.ownership = PurpleSyncChatCore.Ownership.Unknown;
            if (mode == 13) { Path link = root.resolve(file.getFileName() + ".link"); Files.createSymbolicLink(link, file); client.file = link.toFile(); }
            if (mode == 14) Files.write(file, Arrays.copyOf(bytes, bytes.length + 1));
            if (mode == 24) { byte[] changed = bytes.clone(); changed[4] ^= 1; Files.write(file, changed); }
            authority = new Authority(client); port = new Port(client, authority);
            operation = new PurplePlaylistsSyncPublication(client, authority, () -> true, port); port.operation = operation;
            if (mode == 28) authority.onIssued = operation::close;
            operation.start(bytes, 0x1020304050607080L, port::journal, results::add); client.drain();
        }
        PurplePlaylistsSyncPublication.Result result() { check(results.size() == 1, "one terminal publication result"); return results.get(0); }
        public void close() { operation.close(); client.drain(); }
    }
    private static void success(Path root, byte[] payload) throws Exception {
        try (Run run = new Run(root, payload, 0)) {
            PurplePlaylistsSyncPublication.Result result = run.result();
            check(result.status == PurplePlaylistsSyncPublication.Status.Confirmed, "exact server receipt and byte readback confirm publication");
            check(run.client.lookupCount == 2 && run.port.sends == 1, "one send and two identity reads");
            check(Arrays.equals(payload, run.port.uploaded.toByteArray()), "all multipart bytes uploaded once in order");
            check(Arrays.equals(payload, result.payload()), "confirmed exact payload returned");
            TLRPC.TL_messages_sendMedia request = run.port.sent;
            TLRPC.TL_inputMediaUploadedDocument media = (TLRPC.TL_inputMediaUploadedDocument) request.media;
            check(request.random_id == 0x1020304050607080L && request.silent, "persisted random ID and silent send");
            check(request.message.equals("Purple playlists sync data #purpleplaylists") && media.mime_type.equals("application/gzip"), "playlist caption and MIME never settings");
            check(media.attributes.size() == 1 && media.attributes.get(0).file_name.equals("Purple playlists.json.gz") && media.force_file, "actual uploaded document has playlist filename");
            TLRPC.TL_inputFile file = (TLRPC.TL_inputFile) media.file;
            check(file.id == run.port.uploadId && file.parts == run.port.parts.size() && file.name.equals("Purple playlists.json.gz"), "inputFile references complete multipart upload");
            check(file.md5_checksum.equals(hex(MessageDigest.getInstance("MD5").digest(payload))), "inputFile checksum covers complete compressed bytes");
            check(run.port.intent.compressedHash.equals(hex(MessageDigest.getInstance("SHA-256").digest(payload))), "durable intent binds exact compressed bytes");
            byte[] copy = run.port.intent.bytes(); copy[0] = 0;
            check(run.port.intent.bytes()[0] == payload[0], "intent bytes immutable");
            JSONObject proof = result.proof();
            check(proof.getBoolean("acknowledged") && proof.getString("origin").equals("PublishedThisStep") && proof.getString("document").equals("9007199254740993"), "only confirmed exact read creates publication proof");
            check(proof.getString("expectedHash").equals(run.port.intent.canonicalHash), "publication proof carries canonical hash");
            check(run.port.untimers == 1 && run.port.events.equals(Arrays.asList("journal", "send")), "deadline released and journal precedes wire send");
            result.payload()[0] = 0; check(result.payload()[0] == payload[0], "returned publication payload immutable");
            run.operation.close(); run.client.drain();
            check(result.payload() == null && result.metadata() == null && !result.current(), "closed publication loses acknowledgement authority");
            boolean denied = false; try { result.proof(); } catch (IllegalStateException error) { denied = true; }
            check(denied && run.results.size() == 1, "closed proof refused without second callback");
        }
    }
    private static byte[] large() throws Exception {
        JSONObject playlists = new JSONObject(); Random random = new Random(157);
        for (int i = 0; i < 8000; ++i) {
            StringBuilder id = new StringBuilder("pl-"); long n = i + 1;
            for (int k = 0; k < 16; ++k) { id.append("abcdefghijklmnopqrstuvwxyz234567".charAt((int) (n & 31))); n >>>= 5; }
            StringBuilder title = new StringBuilder(); for (int k = 0; k < 64; ++k) title.append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ23456789".charAt(random.nextInt(60)));
            JSONObject value = new JSONObject(); value.put("title", new JSONArray().put(title.toString()).put(100));
            value.put("deleted", new JSONArray().put(false).put(100)); playlists.put(id.toString(), value);
        }
        JSONObject value = new JSONObject(); value.put("format", 1); value.put("playlists", playlists); value.put("purged", new JSONObject());
        byte[] result = gzip(bytes(value.toString())); check(result != null && result.length > PurplePlaylistsSyncPublication.PART_BYTES, "valid shared-core publication needs multipart upload"); return result;
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root); byte[] payload = gzip(LIBRARY);
        check(payload != null, "shared core encoded synthetic library");
        success(root, payload); success(root, large());
        for (int mode = 1; mode <= 14; ++mode) {
            try (Run run = new Run(root, payload, mode)) {
                PurplePlaylistsSyncPublication.Result result = run.result();
                check(result.status == (mode <= 2 ? PurplePlaylistsSyncPublication.Status.NotSent : PurplePlaylistsSyncPublication.Status.OutcomeUnknown), "failed or ambiguous publication remains unacknowledged " + mode);
                check(result.payload() == null && result.metadata() == null, "failed publication has no proof bytes " + mode);
                boolean denied = false; try { result.proof(); } catch (IllegalStateException error) { denied = true; }
                check(denied, "failed publication proof refused " + mode);
                check(run.port.sends <= 1 && (mode >= 5 && mode != 7 || run.port.sends == 0), "no send retries or preauthority sends " + mode);
            }
        }
        try (Run run = new Run(root, payload, 17)) {
            check(run.result().status == PurplePlaylistsSyncPublication.Status.Confirmed && run.port.sends == 1 && run.port.journals == 1, "duplicate callbacks cannot repeat parts intent or send");
        }
        try (Run run = new Run(root, payload, 18)) {
            check(run.result().status == PurplePlaylistsSyncPublication.Status.NotSent && run.port.sends == 0, "close during upload handoff prevents send");
            check(run.client.canceled.size() == 1 && new HashSet<>(run.client.canceled).size() == 1, "close during handoff cancels returned token once");
        }
        try (Run run = new Run(root, payload, 15)) {
            check(run.results.isEmpty() && run.port.sends == 1 && run.port.intent != null, "lost send reply retains preexisting intent");
            run.port.deadline.run(); run.client.drain();
            check(run.result().status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown, "lost reply deadline never confirms or retries");
            run.port.held.onReply(run.port.response()); run.client.drain();
            check(run.results.size() == 1 && run.client.lookupCount == 0 && run.port.sends == 1, "late reply after timeout cannot publish acknowledgement");
        }
        try (Run run = new Run(root, payload, 19)) {
            run.client.origin = new PurpleSyncChatOrigin(0, 0, 101, "replacement-login");
            run.port.held.onReply(run.port.response()); run.client.drain();
            check(run.result().status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown && run.client.lookupCount == 0, "same user new login revokes old publication");
        }
        try (Run run = new Run(root, payload, 21)) { check(run.result().status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown, "post-read document replacement cannot acknowledge"); }
        try (Run run = new Run(root, payload, 22)) {
            check(run.result().status == PurplePlaylistsSyncPublication.Status.Confirmed && run.port.sent.peer.channel_id == 303, "captured supergroup publication has exact channel receipt and readback");
        }
        try (Run run = new Run(root, payload, 23)) {
            check(run.result().status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown && run.client.lookupCount == 0, "wrong update kind cannot confirm supergroup publication");
        }
        try (Run run = new Run(root, payload, 24)) { check(run.result().status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown, "same canonical library with different compressed bytes cannot acknowledge"); }
        try (Run run = new Run(root, payload, 26)) {
            check(run.results.isEmpty() && run.port.sends == 0, "wire send waits for durable journal callback");
            run.port.deadline.run(); run.client.drain();
            check(run.result().status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown && !run.result().sendIssued, "uncertain journal completion stays unresolved");
            run.port.journalReply.accept(true); run.client.drain();
            check(run.port.sends == 0 && run.results.size() == 1, "late journal callback after timeout cannot send");
        }
        try (Run run = new Run(root, payload, 28)) { check(run.result().status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown && run.port.sends == 0, "revocation inside issued hook prevents wire send"); }
        for (byte[] invalid : new byte[][] { null, new byte[0], new byte[] { 1, 2, 3 }, new byte[PurplePlaylistsSyncPublication.MAX_BYTES + 1] }) {
            Client client = new Client(); Authority authority = new Authority(client); Port port = new Port(client, authority);
            PurplePlaylistsSyncPublication operation = new PurplePlaylistsSyncPublication(client, authority, () -> true, port);
            List<PurplePlaylistsSyncPublication.Result> results = new ArrayList<>();
            operation.start(invalid, 3, port::journal, results::add); client.drain();
            check(results.size() == 1 && results.get(0).status == PurplePlaylistsSyncPublication.Status.NotSent && port.parts.isEmpty() && port.sends == 0, "invalid or oversized gzip never uploaded");
            operation.close(); client.drain();
        }
        System.out.println("PLAYLIST_SYNC_PUBLICATION_CHECKS=" + checks);
        System.out.println("PLAYLIST_SYNC_PUBLICATION_OK");
    }
}
