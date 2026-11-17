package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class PurplePlaylistsSyncCommitCases {
    private static int checks;
    private static final byte[] EMPTY = bytes("{\"format\":1,\"playlists\":{},\"purged\":{}}");
    private static final byte[] EDIT = bytes("{\"format\":1,\"playlists\":{\"pl-aaaaaaaaaaaaaaab\":{\"title\":[\"最新 ✓\",300],\"deleted\":[false,300]}},\"purged\":{}}");
    private static final byte[] LOCAL = bytes("{\"format\":1,\"unsynced_since\":50,\"status\":{\"result\":\"problem\",\"future\":7},\"android_playlist_downloads\":{\"format\":99,\"opaque\":\"保留\"},\"future\":[1,2]}");
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value, String name) { ++checks; if (!value) throw new AssertionError(name); }
    private static JSONObject object(byte[] value) throws Exception { return new JSONObject(new String(value, StandardCharsets.UTF_8)); }
    private static Path anchor(Path root) throws Exception {
        Path path = Files.createTempDirectory(root, "commit-音楽-");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")); return path;
    }
    private static final class Faults implements PurplePlaylistsStore.Physical {
        final PurplePlaylistsPhysical actual;
        int mode;
        Faults(PurplePlaylistsPhysical actual) { this.actual = actual; }
        public boolean current() { return actual.current(); }
        public PurplePlaylistsStore.File read(PurplePlaylistsStore.Name name) throws Exception { return actual.read(name); }
        public boolean matches(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File file) throws Exception { return actual.matches(name, file); }
        public PurplePlaylistsStore.Publication replace(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File file, byte[] bytes, PurplePlaylistsStore.Current scope) throws Exception {
            if (mode == 1 && name == PurplePlaylistsStore.Name.Local) throw new java.io.IOException("SyntheticStateWriteFault");
            return actual.replace(name, file, bytes, scope);
        }
        public PurplePlaylistsStore.Publication confirm(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File file, PurplePlaylistsStore.Current scope) throws Exception {
            PurplePlaylistsStore.Publication result = actual.confirm(name, file, scope);
            return mode == 2 && name == PurplePlaylistsStore.Name.Local ? new PurplePlaylistsStore.Publication(false, result.file) : result;
        }
        public void close() { }
    }
    private static PurplePlaylistsSyncCommit.Result apply(PurplePlaylistsStore store, PurplePlaylistsPublicationJournal journal,
            PurplePlaylistsPublicationJournalCases.Flow flow, PurplePlaylistsSyncDiscovery.Result discovery,
            boolean published, long now, java.util.function.BooleanSupplier scope) {
        return PurplePlaylistsSyncCommit.apply(store, journal, flow.client.origin, flow.client.chat,
                discovery, published ? flow.results.get(0) : null, true, now, scope);
    }
    private static void lifecycle(Path root) throws Exception {
        for (boolean edited : new boolean[]{false, true}) {
            Path path = anchor(root);
            try (PurplePlaylistsPhysical physical = new PurplePlaylistsPhysical(path.toString(), "account/playlists", () -> true, true);
                    PurplePlaylistsPublicationJournalCases.Flow flow = new PurplePlaylistsPublicationJournalCases.Flow(path, 0)) {
                PurplePlaylistsStore store = new PurplePlaylistsStore(101, physical);
                PurplePlaylistsStore.Read initial = store.read();
                check(initial.ready() && store.write(initial, EMPTY, LOCAL, PurplePlaylistsStore.Kind.LocalEdit).ready(), "seed native store");
                PurplePlaylistsPublicationJournal journal = new PurplePlaylistsPublicationJournal(physical, flow.client.origin, flow.client.chat, () -> true);
                flow.start(journal);
                PurplePlaylistsSyncDiscovery.Result discovery = flow.discover();
                if (edited) check(store.write(store.read(), EDIT, LOCAL, PurplePlaylistsStore.Kind.LocalEdit).ready(), "intervening actual local edit");
                PurplePlaylistsSyncCommit.Result committed = apply(store, journal, flow, discovery, true, 1000, () -> true);
                check(committed.status == PurplePlaylistsSyncCommit.Status.Ready, "actual commit and retirement complete " + edited);
                check(committed.synced == !edited, "new local edit prevents global Synced " + edited);
                PurplePlaylistsStore.Read saved = store.read(); JSONObject state = object(saved.state());
                check(state.getJSONObject("status").getString("result").equals(edited ? "problem" : "synced"), "actual typed result " + edited);
                check(state.getJSONObject("status").getLong("last_step_at_ms") == 1000, "shared typed step timestamp");
                check(state.has("unsynced_since") == edited && (!edited || state.getLong("unsynced_since") == 50), "earliest unsynced age preserved or acknowledged");
                check(state.getJSONObject("status").getInt("future") == 7 && state.getJSONArray("future").length() == 2
                        && state.getJSONObject("android_playlist_downloads").getInt("format") == 99, "opaque and unknown local members preserved");
                check(Arrays.equals(saved.library(), PurplePlaylistsCore.canonicalize(edited ? EDIT : EMPTY, 101).canonicalBytes()), "latest library preserved");
                check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Retired, "durable local ACK retires exact journal");
                check(flow.port.sends == 1, "commit does not send again");
                final boolean[] accepted = {true}; journal.prepare(flow.port.intent, value -> accepted[0] = value);
                check(!accepted[0], "retired random ID cannot be reused");
                PurplePlaylistsStore.Read latest = store.read(); PurplePlaylistsStore.Durable durable = store.confirm(latest, () -> true);
                check(durable != null && durable.current(), "actual paired native durability capability");
                check(store.write(latest, saved.library(), LOCAL, PurplePlaylistsStore.Kind.LocalEdit).ready(), "later state mutation");
                check(!durable.current(), "later state mutation revokes durable capability");
            }
            try (PurplePlaylistsPhysical physical = new PurplePlaylistsPhysical(path.toString(), "account/playlists", () -> true, true);
                    PurplePlaylistsPublicationJournalCases.Flow flow = new PurplePlaylistsPublicationJournalCases.Flow(path, 0)) {
                PurplePlaylistsPublicationJournal journal = new PurplePlaylistsPublicationJournal(physical, flow.client.origin, flow.client.chat, () -> true);
                check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Retired, "retired state survives physical reopen");
                flow.operation.start(flow.payload, 987654321, (attempt, done) -> { ++flow.port.journals; flow.port.intent = attempt; journal.prepare(attempt, done); }, flow.results::add); flow.client.drain();
                check(flow.port.sends == 1 && journal.read().randomId == 987654321, "retirement admits only a fresh random publication");
            }
        }
    }
    private static void refusalAndRecovery(Path root) throws Exception {
        for (int mode = 0; mode < 5; ++mode) {
            Path path = anchor(root);
            try (PurplePlaylistsPhysical physical = new PurplePlaylistsPhysical(path.toString(), "account/playlists", () -> true, true);
                    PurplePlaylistsPublicationJournalCases.Flow flow = new PurplePlaylistsPublicationJournalCases.Flow(path, 5)) {
                Faults faults = new Faults(physical); PurplePlaylistsStore store = new PurplePlaylistsStore(101, faults);
                check(store.write(store.read(), EMPTY, LOCAL, PurplePlaylistsStore.Kind.LocalEdit).ready(), "fault seed");
                PurplePlaylistsPublicationJournal journal = new PurplePlaylistsPublicationJournal(physical, flow.client.origin, flow.client.chat, () -> true);
                flow.start(journal); PurplePlaylistsSyncDiscovery.Result discovery = flow.discover();
                faults.mode = mode;
                if (mode == 3) flow.client.active = false;
                PurplePlaylistsSyncCommit.Result result = apply(store, journal, flow, discovery, false, 2000, () -> true);
                check(result.status == (mode == 1 ? PurplePlaylistsSyncCommit.Status.WriteFailed : mode == 2
                        ? PurplePlaylistsSyncCommit.Status.DurabilityUnknown : mode == 3
                        ? PurplePlaylistsSyncCommit.Status.Refused : PurplePlaylistsSyncCommit.Status.Ready), "real failure truth " + mode);
                check(journal.read().status == (mode == 1 || mode == 2 || mode == 3
                        ? PurplePlaylistsPublicationJournal.Status.Pending : PurplePlaylistsPublicationJournal.Status.Retired), "failed durability never retires pending intent " + mode);
                if (mode == 1 || mode == 2) {
                    faults.mode = 0;
                    check(apply(store, journal, flow, discovery, false, 2001, () -> true).status == PurplePlaylistsSyncCommit.Status.Ready,
                            "fresh readback recovers partial store write without resend " + mode);
                }
                check(flow.port.sends == 1, "recovery never issues wire send " + mode);
                check(journal.retire(journal.read(), null).status == PurplePlaylistsPublicationJournal.Status.Unavailable,
                        "no synthetic ACK can clear intent " + mode);
            }
        }
    }
    private static void localCheckpoint() throws Exception {
        check(!PurplePlaylistsLocalCore.checkpoint(LOCAL, false, 0, 101).valid(), "zero step clock refused");
        check(!PurplePlaylistsLocalCore.checkpoint(bytes("{\"format\":2}"), true, 1000, 101).valid(), "newer local format refused");
        byte[] first = PurplePlaylistsLocalCore.checkpoint(LOCAL, false, 1000, 101).canonicalBytes();
        JSONObject oldClock = object(PurplePlaylistsLocalCore.checkpoint(first, false, 100, 101).canonicalBytes());
        check(oldClock.getLong("unsynced_since") == 50, "clock rollback never resets earliest unsynced");
        check(oldClock.getJSONObject("status").getLong("last_step_at_ms") == 1000, "step clock never goes backwards");
    }
    private static void worker(Path root) throws Exception {
        PurplePlaylistsWorkerCases.Fixture fixture = new PurplePlaylistsWorkerCases.Fixture();
        PurplePlaylistsWorker worker = fixture.worker(0);
        try (PurplePlaylistsPublicationJournalCases.Flow flow = new PurplePlaylistsPublicationJournalCases.Flow(root, 0)) {
            PurplePlaylistsSyncDiscovery.Result discovery = flow.discover();
            CountDownLatch done = new CountDownLatch(1); AtomicInteger answers = new AtomicInteger();
            AtomicReference<PurplePlaylistsSyncCommit.Result> result = new AtomicReference<>();
            PurplePlaylistsSyncCommit.onWorker(worker, flow.client.origin, flow.client.chat, discovery, null, true, 3000,
                    () -> true, value -> { result.set(value); answers.incrementAndGet(); done.countDown(); });
            check(done.await(10, TimeUnit.SECONDS), "bounded actual account worker commit");
            check(result.get().status == PurplePlaylistsSyncCommit.Status.Ready && result.get().synced, "actual captured-account factory JNI durable commit");
            check(answers.get() == 1 && Files.exists(fixture.folder.resolve("local.json")), "single worker callback follows native state persistence");
        } finally { fixture.stop(worker); }
    }
    private static void partialCheckpoints(Path root) throws Exception {
        byte[] body = PurplePlaylistsSyncCore.encode(EDIT, 101).publication();
        JSONObject meta = new JSONObject().put("message", "41").put("document", "91").put("regular", true)
                .put("forwarded", false).put("isDocument", true).put("caption", PurplePlaylistsSyncPublication.CAPTION)
                .put("mime", PurplePlaylistsSyncPublication.MIME).put("names", new org.json.JSONArray().put(PurplePlaylistsSyncPublication.NAME))
                .put("ownership", "Own").put("read", "Succeeded");
        JSONObject proof = new JSONObject().put("chat", "self").put("message", "41").put("document", "91")
                .put("origin", "DownloadedThisStep").put("readSucceeded", true).put("acknowledged", false).put("expectedHash", JSONObject.NULL);
        JSONObject batch = new JSONObject().put("authorized", true).put("trigger", "Manual").put("libraryRead", "Succeeded")
                .put("stateRead", "Succeeded").put("discoverySelf", "101").put("chat", "self").put("listingSucceeded", true)
                .put("listingExhausted", false).put("byIdSucceeded", true).put("listed", new org.json.JSONArray().put(meta))
                .put("byId", new org.json.JSONArray()).put("payloads", new org.json.JSONArray().put(proof))
                .put("clock", new JSONObject().put("self", "101").put("chat", "self").put("now", "1000").put("succeeded", true));
        byte[] request = bytes(batch.toString());
        for (int mode = 0; mode < 4; ++mode) {
            Path path = anchor(root);
            try (PurplePlaylistsPhysical physical = new PurplePlaylistsPhysical(path.toString(), "account/playlists", () -> true, true)) {
                Faults faults = new Faults(physical); PurplePlaylistsStore store = new PurplePlaylistsStore(101, faults);
                check(store.write(store.read(), EMPTY, LOCAL, PurplePlaylistsStore.Kind.LocalEdit).ready(), "partial checkpoint seed");
                faults.mode = mode;
                PurplePlaylistsSyncCommit.Result result = PurplePlaylistsSyncCommit.checkpoint(store, request, body, 101, 1000, () -> true);
                check(!result.synced && !result.plan.detail().getBoolean("discoveryComplete"), "partial result never claims full coverage " + mode);
                check(result.status == (mode == 1 ? PurplePlaylistsSyncCommit.Status.WriteFailed : mode == 2
                        ? PurplePlaylistsSyncCommit.Status.DurabilityUnknown : PurplePlaylistsSyncCommit.Status.Ready), "partial native durability truth " + mode);
                check(result.write.libraryCommitted && (mode != 1 || !result.write.stateCommitted), "partial writes retain committed flags " + mode);
                faults.mode = 0;
                PurplePlaylistsStore.Read observed = store.read();
                check(Arrays.equals(observed.library(), PurplePlaylistsCore.canonicalize(EDIT, 101).canonicalBytes()), "partial remote merge survives state fault " + mode);
                if (mode != 1) check(!"synced".equals(object(observed.state()).getJSONObject("status").getString("result")), "partial persisted status is unsynced " + mode);
                check(PurplePlaylistsSyncCommit.checkpoint(store, request, body, 101, 1001, () -> false).status == PurplePlaylistsSyncCommit.Status.Refused,
                        "revoked scope refuses next checkpoint " + mode);
            }
        }
        batch.put("listingExhausted", true);
        check(PurplePlaylistsSyncCore.partial(bytes(batch.toString()), body, EMPTY, LOCAL, 101) == null, "batch interface rejects fabricated complete coverage");
    }
    private static void incompleteJournal(Path root) throws Exception {
        Path path = anchor(root);
        try (PurplePlaylistsPhysical physical = new PurplePlaylistsPhysical(path.toString(), "account/playlists", () -> true, true);
                PurplePlaylistsPublicationJournalCases.Flow flow = new PurplePlaylistsPublicationJournalCases.Flow(path, 5)) {
            PurplePlaylistsStore store = new PurplePlaylistsStore(101, physical);
            check(store.write(store.read(), EMPTY, LOCAL, PurplePlaylistsStore.Kind.LocalEdit).ready(), "incomplete journal seed");
            PurplePlaylistsPublicationJournal journal = new PurplePlaylistsPublicationJournal(physical, flow.client.origin, flow.client.chat, () -> true);
            flow.start(journal); flow.client.interruptHistory = true;
            AtomicReference<PurplePlaylistsSyncDiscovery.Result> found = new AtomicReference<>();
            PurplePlaylistsSyncDiscovery discovery = new PurplePlaylistsSyncDiscovery(flow.client, () -> true);
            discovery.start(EMPTY, LOCAL, true, 1000, found::set); flow.client.drain();
            check(found.get() != null && !"None".equals(found.get().error), "journal payload seen before history interruption");
            check(found.get().publication(flow.client.origin, flow.client.chat, flow.payload) != null, "partial exact body has recovery identity");
            PurplePlaylistsSyncCommit.Result result = apply(store, journal, flow, found.get(), false, 1000, () -> true);
            check(result.status == PurplePlaylistsSyncCommit.Status.RetirementPending && !result.synced,
                    "incomplete coverage cannot ACK or retire exact seen journal");
            check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Pending && flow.port.sends == 1,
                    "incomplete scan leaves original unknown intent unchanged without resend");
            discovery.close();
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = java.nio.file.Paths.get(args[0]); Files.createDirectories(root);
        localCheckpoint(); lifecycle(root); refusalAndRecovery(root); worker(root); partialCheckpoints(root); incompleteJournal(root);
        System.out.println("PLAYLIST_SYNC_COMMIT_CHECKS=" + checks); System.out.println("PLAYLIST_SYNC_COMMIT_OK");
    }
}
