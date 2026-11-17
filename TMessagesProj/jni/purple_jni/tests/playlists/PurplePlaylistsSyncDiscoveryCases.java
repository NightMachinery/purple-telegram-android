package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

public final class PurplePlaylistsSyncDiscoveryCases {
    private static int checks;
    private static final byte[] EMPTY = bytes("{\"format\":1,\"playlists\":{},\"purged\":{}}");
    private static final byte[] LIBRARY = bytes("{\"format\":1,\"playlists\":{\"pl-aaaaaaaaaaaaaaab\":{\"title\":[\"遠隔 ✓\",100],\"deleted\":[false,100]}},\"purged\":{}}");
    private static final byte[] LOCAL = bytes("{\"sync\":true,\"opaque\":17,\"downloads\":{\"android\":{\"future\":true}}}");
    private static void check(boolean good, String label) { ++checks; if (!good) throw new AssertionError(label); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static TLRPC.TL_message message(int id) {
        TLRPC.TL_message value = new TLRPC.TL_message(); value.id = id; value.date = 100;
        value.peer_id = new TLRPC.TL_peerUser(); value.peer_id.user_id = 101;
        value.message = "Purple playlists sync data #purpleplaylists";
        value.media = new TLRPC.TL_messageMediaDocument(); value.media.document = new TLRPC.TL_document();
        value.media.document.id = 9007199254740993L; value.media.document.mime_type = "application/gzip";
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = "Purple playlists.json.gz"; value.media.document.attributes.add(name); return value;
    }
    private static byte[] metadata(TLRPC.Message message, PurpleSyncChatCore.Ownership own) {
        return PurplePlaylistsSyncMetadata.message(message, own);
    }
    private static final class Client implements PurpleSyncClient {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final ArrayDeque<History> pages = new ArrayDeque<>();
        final HashMap<Integer, Lookup> exact = new HashMap<>();
        final List<Integer> offsets = new ArrayList<>(), lookups = new ArrayList<>();
        final List<Object> canceled = new ArrayList<>();
        final List<Reply<History>> histories = new ArrayList<>();
        final List<Reply<Lookup>> lookupReplies = new ArrayList<>();
        final List<Reply<File>> downloadReplies = new ArrayList<>();
        PurpleSyncChatOrigin origin = new PurpleSyncChatOrigin(0, 0, 101, "fixture-login");
        PurpleSyncChatCore.Peer chat = PurpleSyncChatCore.Peer.self();
        PurpleSyncChatCore.Ownership own = PurpleSyncChatCore.Ownership.Own;
        boolean active = true, scope = true, holdHistory, holdLookup, holdDownload;
        File file; int token, downloads; Runnable onHistory, onDownload;
        public long userId() { return 101; }
        public PurpleSyncChatCore.Peer chat() { return chat; }
        public PurpleSyncChatOrigin origin() { return origin; }
        public boolean isActive() { return active; }
        public void runOnMain(Runnable task) { tasks.add(task); }
        public void runOnWorker(Runnable task) { tasks.add(task); }
        public void log(String text) { }
        public int requestHistory(int offset, int limit, Reply<History> reply) {
            offsets.add(offset); histories.add(reply); int id = ++token;
            if (onHistory != null) onHistory.run();
            if (!holdHistory) reply.onReply(pages.isEmpty() ? History.page(Collections.emptyList(), null) : pages.remove());
            return id;
        }
        public int requestMessage(int id, Reply<Lookup> reply) {
            lookups.add(id); lookupReplies.add(reply); int result = ++token;
            if (!holdLookup) reply.onReply(exact.getOrDefault(id, Lookup.failed("NoFixture")));
            return result;
        }
        public void cancelRequest(int token) { canceled.add(token); }
        public Object download(Message message, Reply<File> reply) {
            ++downloads; downloadReplies.add(reply); Object result = new Object();
            if (onDownload != null) onDownload.run();
            if (!holdDownload) reply.onReply(file); return result;
        }
        public void cancelDownload(Object value) { canceled.add(value); }
        public byte[] playlistMetadata(Message value) { return metadata((TLRPC.Message) value.remote, own); }
        public void localCopies(Reply<List<LocalCopy>> reply) { throw new AssertionError("No local outbox access"); }
        public File filesDirectory() { throw new AssertionError("No user files directory"); }
        public File cacheDirectory() { throw new AssertionError("No user cache directory"); }
        public long nowMillis() { return 200; }
        public void send(File file, String caption, String mime, Receipt receipt) { throw new AssertionError("Discovery never publishes"); }
        void drain() { int steps = 0; while (!tasks.isEmpty()) { if (++steps > 10000) throw new AssertionError("Unbounded tasks"); tasks.remove().run(); } }
        Message wrap(TLRPC.Message message) {
            TLRPC.Document doc = message.media == null ? null : message.media.document;
            return new Message(message.id, message instanceof TLRPC.TL_message, message.fwd_from != null,
                    doc != null, message.message, doc == null ? null : doc.attributes.get(0).file_name,
                    chat, doc == null ? 0 : doc.id, doc == null ? 0 : doc.size, message.edit_date, message);
        }
    }
    private static final class Run implements AutoCloseable {
        final Client client; final PurplePlaylistsSyncDiscovery discovery; final byte[] state;
        final List<PurplePlaylistsSyncDiscovery.Result> results = new ArrayList<>();
        Run(Client client, byte[] state) {
            this.client = client; this.state = state; discovery = new PurplePlaylistsSyncDiscovery(client, () -> client.scope);
            discovery.start(EMPTY, state, false, 200, results::add); client.drain();
        }
        PurplePlaylistsSyncDiscovery.Result result() { check(results.size() == 1, "exactly one discovery result"); return results.get(0); }
        PurplePlaylistsSyncCore.Result plan() { return result().plan(EMPTY, state, false, 201); }
        public void close() { discovery.close(); client.drain(); }
    }
    private static Client remote(Path root) throws Exception {
        Client c = new Client(); byte[] compressed = PurplePlaylistsSyncCore.encode(LIBRARY, 101).publication();
        Path file = root.resolve("record-" + System.nanoTime() + ".gz"); Files.write(file, compressed); c.file = file.toFile();
        TLRPC.TL_message m = message(41); m.media.document.size = compressed.length;
        PurpleSyncClient.Message wrapped = c.wrap(m);
        c.pages.add(PurpleSyncClient.History.page(Collections.singletonList(wrapped), 1));
        c.exact.put(41, PurpleSyncClient.Lookup.found(wrapped)); return c;
    }
    private static byte[] memo() {
        return bytes("{\"sync\":true,\"chat\":\"self\",\"memo\":{\"41\":{\"document_id\":\"9007199254740992\",\"state\":\"invalid\",\"classifier\":1}}}");
    }
    private static void metadataCases() throws Exception {
        TLRPC.TL_message m = message(41);
        JSONObject value = new JSONObject(new String(metadata(m, PurpleSyncChatCore.Ownership.Own), StandardCharsets.UTF_8));
        check(value.getString("document").equals("9007199254740993"), "actual TL document above 2^53 exact");
        check(value.getJSONArray("names").length() == 1, "actual TL filename captured");
        check(PurplePlaylistsSyncCore.metadata(metadata(m, PurpleSyncChatCore.Ownership.Own), 101).detail().getString("verdict").equals("Candidate"), "actual TL metadata qualifies through shared classifier");
        TLRPC.TL_documentAttributeFilename second = new TLRPC.TL_documentAttributeFilename(); second.file_name = "Purple settings.toml";
        m.media.document.attributes.add(second);
        check(new JSONObject(new String(metadata(m, PurpleSyncChatCore.Ownership.Own), StandardCharsets.UTF_8)).getJSONArray("names").length() == 2, "all filename attributes retained");
        check(!PurplePlaylistsSyncCore.metadata(metadata(m, PurpleSyncChatCore.Ownership.Own), 101).detail().getString("verdict").equals("Candidate"), "ambiguous actual filenames never download as playlist");
        m = message(41); m.fwd_from = new TLRPC.TL_messageFwdHeader();
        check(!PurplePlaylistsSyncCore.metadata(metadata(m, PurpleSyncChatCore.Ownership.Own), 101).detail().getString("verdict").equals("Candidate"), "actual forwarded flag preserved");
        m = message(41); m.media.document.id = -1;
        check(new JSONObject(new String(metadata(m, PurpleSyncChatCore.Ownership.Own), StandardCharsets.UTF_8)).getString("document").equals("18446744073709551615"), "unsigned TL document preserves all bits");
    }
    private static void discoveryCases(Path root) throws Exception {
        Client c = new Client();
        try (Run run = new Run(c, LOCAL)) {
            check(run.plan().detail().getBoolean("discoveryComplete"), "empty exhausted history is complete");
            check(c.downloads == 0 && c.lookups.isEmpty(), "empty history needs no payload or exact reads");
        }
        c = remote(root);
        try (Run run = new Run(c, LOCAL)) {
            PurplePlaylistsSyncCore.Result plan = run.plan();
            check(plan.detail().getBoolean("discoveryComplete"), "owned download with exact reread completes discovery");
            check(plan.detail().getBoolean("writeLibrary") && new String(plan.library(), StandardCharsets.UTF_8).contains("遠隔 ✓"), "actual core merges downloaded remote library");
            check(plan.publication() == null && plan.detail().getString("publication").equals("EqualRecord"), "equal server record prevents duplicate publication");
            check(c.downloads == 1 && c.lookups.equals(Collections.singletonList(41)), "one download followed by one exact identity read");
            check(new JSONObject(new String(plan.state(), StandardCharsets.UTF_8)).getInt("opaque") == 17, "replan preserves latest local opaque state");
            byte[] newer = bytes(new String(LIBRARY, StandardCharsets.UTF_8).replace("遠隔 ✓", "Local edit").replace("100", "300"));
            PurplePlaylistsSyncCore.Result fresh = run.result().plan(newer, LOCAL, false, 301);
            check(new String(fresh.library(), StandardCharsets.UTF_8).contains("Local edit"), "result replans against intervening local edit");
            check(fresh.publication() != null, "intervening local edit needs its own publication");
            plan.library()[0] = 0;
            check(run.plan().library()[0] == '{', "discovery payload result cannot be mutated through planner output");
            c.scope = false;
            boolean refused = false; try { run.result().plan(EMPTY, LOCAL, false, 302); } catch (IllegalStateException e) { refused = true; }
            check(refused, "result cannot replan after route revocation");
        }
        c = new Client(); List<PurpleSyncClient.Message> first = new ArrayList<>();
        for (int id = 200; id > 100; --id) { TLRPC.TL_message m = message(id); m.media = null; first.add(c.wrap(m)); }
        c.pages.add(PurpleSyncClient.History.page(first, 101)); TLRPC.TL_message last = message(100); last.media = null;
        c.pages.add(PurpleSyncClient.History.page(Collections.singletonList(c.wrap(last)), 101));
        try (Run run = new Run(c, LOCAL)) {
            check(run.plan().detail().getBoolean("discoveryComplete") && c.offsets.equals(Arrays.asList(0, 101)), "complete counted history pages use last positive ID");
        }
        c = remote(root); c.pages.clear(); c.pages.add(PurpleSyncClient.History.failed("offline"));
        try (Run run = new Run(c, LOCAL)) {
            check(!run.plan().detail().getBoolean("discoveryComplete") && run.plan().publication() == null, "failed history cannot authorize publication");
        }
        c = remote(root); PurpleSyncClient.Message wrapped = c.exact.get(41).message; c.pages.clear();
        c.pages.add(PurpleSyncClient.History.page(Collections.singletonList(wrapped), 2));
        c.pages.add(PurpleSyncClient.History.page(Collections.emptyList(), 2));
        try (Run run = new Run(c, LOCAL)) {
            check(run.result().error.equals("IncompleteHistory") && run.plan().publication() == null, "counted premature empty page is incomplete");
        }
        c = remote(root); wrapped = c.exact.get(41).message; c.pages.clear();
        c.pages.add(PurpleSyncClient.History.page(Arrays.asList(wrapped, wrapped), null));
        try (Run run = new Run(c, LOCAL)) { check(run.result().error.equals("IncompleteHistory"), "overlapping history IDs refused"); }
        c = remote(root); c.own = PurpleSyncChatCore.Ownership.Other;
        try (Run run = new Run(c, LOCAL)) { check(c.downloads == 0 && !run.plan().detail().getBoolean("writeLibrary"), "foreign record never downloaded or merged"); }
        c = remote(root); c.own = PurpleSyncChatCore.Ownership.Unknown;
        try (Run run = new Run(c, LOCAL)) { check(c.downloads == 0 && !run.plan().detail().getBoolean("discoveryComplete"), "unknown ownership remains incomplete"); }
        for (int mode = 0; mode < 3; ++mode) {
            c = remote(root); c.pages.clear();
            if (mode == 0) c.exact.put(41, PurpleSyncClient.Lookup.missing());
            if (mode == 1) c.exact.put(41, PurpleSyncClient.Lookup.failed("offline"));
            try (Run run = new Run(c, memo())) {
                check(c.lookups.contains(41), "missing memo ID fetched from exact chat " + mode);
                JSONObject detail = run.plan().detail();
                if (mode == 0) check(detail.getJSONObject("inventory").getJSONArray("gone").length() == 1, "explicit scoped absence clears memo");
                if (mode == 1) check(!detail.getBoolean("discoveryComplete"), "lookup failure never becomes confirmed absence");
                if (mode == 2) check(c.downloads == 1 && detail.getBoolean("writeLibrary"), "memo lookup recovers a replaced present document");
            }
        }
    }
    private static void staleAndFiles(Path root) throws Exception {
        for (int mode = 0; mode < 7; ++mode) {
            Client c = remote(root);
            if (mode == 0) c.file = null;
            if (mode == 1) Files.write(c.file.toPath(), new byte[]{1});
            if (mode == 2) { Path link = root.resolve("link-" + System.nanoTime()); Files.createSymbolicLink(link, c.file.toPath()); c.file = link.toFile(); }
            TLRPC.TL_message changed = message(41); changed.media.document.size = c.exact.get(41).message.size;
            if (mode == 3) changed.media.document.id++;
            if (mode == 4) changed.edit_date++;
            if (mode == 5) changed.message = "settings #purplesync";
            if (mode >= 3) c.exact.put(41, mode == 6 ? PurpleSyncClient.Lookup.failed("lost") : PurpleSyncClient.Lookup.found(c.wrap(changed)));
            try (Run run = new Run(c, LOCAL)) { check(!run.plan().detail().getBoolean("discoveryComplete") && !run.plan().detail().getBoolean("writeLibrary"), "failed or replaced payload cannot merge " + mode); }
        }
        Client c = remote(root); c.holdHistory = true;
        try (Run run = new Run(c, LOCAL)) {
            PurpleSyncClient.History page = c.pages.remove();
            c.histories.get(0).onReply(page); c.histories.get(0).onReply(page); c.drain();
            check(run.results.size() == 1 && c.downloads == 1, "duplicate history callback cannot duplicate reads or completion");
        }
        c = remote(root); c.holdDownload = true;
        try (Run run = new Run(c, LOCAL)) {
            check(c.downloads == 1, "download in flight before cancellation"); run.discovery.close();
            c.downloadReplies.get(0).onReply(c.file); c.drain();
            check(run.results.isEmpty() && !c.canceled.isEmpty(), "close cancels owned download and ignores late callback");
        }
        c = remote(root); c.holdHistory = true;
        try (Run run = new Run(c, LOCAL)) {
            c.origin = new PurpleSyncChatOrigin(0, 0, 101, "new-login");
            c.histories.get(0).onReply(c.pages.remove()); c.drain();
            check(run.results.isEmpty() && c.downloads == 0, "same user new login cannot consume old history");
        }
        c = remote(root); c.holdHistory = true;
        try (Run run = new Run(c, LOCAL)) {
            c.chat = PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, 77);
            c.histories.get(0).onReply(c.pages.remove()); c.drain();
            check(run.results.isEmpty() && c.downloads == 0, "changed chat callback never starts download");
        }
        c = remote(root); c.holdHistory = true;
        PurplePlaylistsSyncDiscovery discovery = new PurplePlaylistsSyncDiscovery(c, () -> true);
        List<PurplePlaylistsSyncDiscovery.Result> results = new ArrayList<>(); c.onHistory = discovery::close;
        discovery.start(EMPTY, LOCAL, false, 200, results::add); c.drain();
        check(c.canceled.equals(Collections.singletonList(1)) && results.isEmpty(), "cancel during synchronous request handoff owns returned token");
    }
    private static void streaming(Path root) throws Exception {
        Client pages = new Client();
        for (int id = 1100; id > 0; --id) {
            TLRPC.TL_message ignored = message(id); ignored.media = null;
            pages.pages.add(PurpleSyncClient.History.page(Collections.singletonList(pages.wrap(ignored)), null));
        }
        try (Run run = new Run(pages, LOCAL)) {
            check("None".equals(run.result().error) && run.plan().detail().getBoolean("discoveryComplete"), "more than 1000 descending pages complete");
            check(pages.offsets.size() == 1101 && pages.downloads == 0, "ignored history has no retained bodies or page cap");
        }
        Client held = remote(root);
        PurplePlaylistsSyncDiscovery discovery = new PurplePlaylistsSyncDiscovery(held, () -> held.scope);
        List<PurplePlaylistsSyncDiscovery.Result> results = new ArrayList<>();
        List<Consumer<PurplePlaylistsSyncCore.Result>> answers = new ArrayList<>();
        List<PurplePlaylistsSyncCore.Result> admitted = new ArrayList<>();
        discovery.checkpoint((request, body, done) -> {
            PurplePlaylistsSyncCore.Result part = PurplePlaylistsSyncCore.partial(request, body, EMPTY, LOCAL, 101);
            check(part != null && !part.detail().getBoolean("discoveryComplete"), "checkpoint remains incomplete");
            admitted.add(part); answers.add(done);
        });
        discovery.start(EMPTY, LOCAL, true, 200, results::add); held.drain();
        check(results.isEmpty() && answers.size() == 1, "discovery waits for actual checkpoint admission");
        answers.get(0).accept(admitted.get(0)); answers.get(0).accept(admitted.get(0)); held.drain();
        check(results.size() == 1 && results.get(0).contains(LIBRARY, null), "one receipt after duplicate checkpoint callback");
        byte[] exact = Files.readAllBytes(held.file.toPath());
        check(results.get(0).publication(held.origin, held.chat, exact) != null, "exact journal bytes recover from compact digest");
        byte[] changed = exact.clone(); changed[changed.length - 1] ^= 1;
        check(results.get(0).publication(held.origin, held.chat, changed) == null, "changed gzip bytes never recover from canonical hash");
        discovery.close();
        Client large = new Client(); byte[] invalid = new byte[9 * 1024 * 1024];
        new java.util.Random(17).nextBytes(invalid);
        Path payload = root.resolve("bounded-invalid-body.gz"); Files.write(payload, invalid); large.file = payload.toFile();
        ArrayList<PurpleSyncClient.Message> records = new ArrayList<>();
        for (int id = 5; id > 0; --id) {
            TLRPC.TL_message raw = message(id); raw.media.document.id += id; raw.media.document.size = invalid.length;
            PurpleSyncClient.Message message = large.wrap(raw); records.add(message); large.exact.put(id, PurpleSyncClient.Lookup.found(message));
        }
        large.pages.add(PurpleSyncClient.History.page(records, 5));
        try (Run run = new Run(large, LOCAL)) {
            check("None".equals(run.result().error) && large.downloads == 5, "45 MiB cumulative bodies do not hit retained-payload budget");
            check(run.plan().detail().getBoolean("discoveryComplete") && run.plan().detail().getJSONObject("records").getInt("invalid") == 5,
                    "each oversized aggregate batch is actually classified by shared core");
            check(!run.result().contains(EMPTY, null), "invalid streamed bytes cannot prove synced containment");
        }
    }
    private static void memoReuse(Path root) throws Exception {
        for (String state : new String[]{"invalid", "newer_format", "oversized", "valid"}) {
            for (boolean current : new boolean[]{true, false}) {
                Client client = remote(root);
                int classifier = PurplePlaylistsSyncCore.metadata(client.playlistMetadata(client.pages.peek().messages.get(0)), 101)
                        .detail().getInt("classifierVersion");
                JSONObject local = new JSONObject(new String(LOCAL, StandardCharsets.UTF_8));
                JSONObject entry = new JSONObject().put("document_id", "9007199254740993")
                        .put("state", state).put("classifier", current ? classifier : classifier + 1);
                if (state.equals("valid")) entry.put("canonical_hash", String.join("", Collections.nCopies(64, "a")));
                local.put("chat", "self").put("memo", new JSONObject().put("41", entry));
                try (Run run = new Run(client, bytes(local.toString()))) {
                    check(run.plan().detail().getBoolean("discoveryComplete"), "memo coverage " + state + current);
                    check(client.downloads == (current || state.equals("valid") ? 0 : 1),
                            "shared classifier memo reuse " + state + current);
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = new File(args[0]).toPath(); Files.createDirectories(root);
        metadataCases(); discoveryCases(root); staleAndFiles(root); streaming(root); memoReuse(root);
        System.out.println("PLAYLIST_SYNC_DISCOVERY_CHECKS=" + checks);
        System.out.println("PLAYLIST_SYNC_DISCOVERY_OK");
    }
}
