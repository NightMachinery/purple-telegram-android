package org.telegram.messenger.purple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class PurplePlaylistsPublicationJournalCases {
    private static int checks;
    private static final byte[] LIBRARY = "{\"format\":1,\"playlists\":{},\"purged\":{}}".getBytes(StandardCharsets.UTF_8);
    private static void check(boolean value, String name) { ++checks; if (!value) throw new AssertionError(name); }
    static final class Client extends PurplePlaylistsSyncPublicationCases.Client {
        boolean interruptHistory;
        @Override public int requestHistory(int offset, int limit, Reply<History> reply) {
            if (interruptHistory && offset != 0) reply.onReply(History.failed("InterruptedFixture"));
            else reply.onReply(History.page(offset == 0 ? Collections.singletonList(wrap()) : Collections.emptyList(), null));
            return ++tokens;
        }
    }
    static final class Flow implements AutoCloseable {
        final Client client = new Client();
        final PurplePlaylistsSyncPublicationCases.Authority authority;
        final PurplePlaylistsSyncPublicationCases.Port port;
        final PurplePlaylistsSyncPublication operation;
        final byte[] payload = PurplePlaylistsSyncCore.encode(LIBRARY, 101).publication();
        final List<PurplePlaylistsSyncPublication.Result> results = new ArrayList<>();
        Flow(Path root, int mode) throws Exception { this(root, mode, "publication-login", 101); }
        Flow(Path root, int mode, String login, int peer) throws Exception {
            client.origin = new PurpleSyncChatOrigin(0, 0, 101, login);
            if (peer != 101) client.chat = PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, peer);
            client.mode = mode; client.message = PurplePlaylistsSyncPublicationCases.message(41, payload.length);
            if (peer != 101) {
                client.message.peer_id = new org.telegram.tgnet.TLRPC.TL_peerChannel();
                client.message.peer_id.channel_id = peer;
            }
            Path file = root.resolve("wire.gz"); Files.write(file, payload); client.file = file.toFile();
            authority = new PurplePlaylistsSyncPublicationCases.Authority(client);
            port = new PurplePlaylistsSyncPublicationCases.Port(client, authority);
            operation = new PurplePlaylistsSyncPublication(client, authority, () -> true, port); port.operation = operation;
        }
        void start(PurplePlaylistsSyncPublication.Journal journal) {
            operation.start(payload, 0x1020304050607080L, (attempt, done) -> {
                ++port.journals; port.intent = attempt; journal.prepare(attempt, done);
            }, results::add); client.drain();
        }
        PurplePlaylistsSyncDiscovery.Result discover() {
            final PurplePlaylistsSyncDiscovery.Result[] found = {null};
            PurplePlaylistsSyncDiscovery discovery = new PurplePlaylistsSyncDiscovery(client, () -> true);
            discovery.start(LIBRARY, PurplePlaylistsLocalCore.defaults(101).canonicalBytes(), true, 1000, value -> found[0] = value);
            client.drain(); check(found[0] != null && "None".equals(found[0].error), "actual complete discovery result");
            return found[0];
        }
        @Override public void close() { operation.close(); client.drain(); }
    }
    private static Path anchor(Path root) throws Exception {
        Path value = Files.createTempDirectory(root, "journal-音乐-");
        Files.setPosixFilePermissions(value, PosixFilePermissions.fromString("rwx------")); return value;
    }
    private static PurplePlaylistsPhysical physical(Path root) throws Exception {
        return new PurplePlaylistsPhysical(root.toString(), "account/playlists", () -> true, true);
    }
    private static PurplePlaylistsPublicationJournal journal(PurplePlaylistsStore.Physical physical, Flow flow) {
        return new PurplePlaylistsPublicationJournal(physical, flow.client.origin, flow.client.chat, () -> true);
    }
    private static final class Unconfirmed implements PurplePlaylistsStore.Physical {
        final PurplePlaylistsPhysical actual;
        Unconfirmed(PurplePlaylistsPhysical actual) { this.actual = actual; }
        public boolean current() { return actual.current(); }
        public PurplePlaylistsStore.File read(PurplePlaylistsStore.Name name) throws Exception { return actual.read(name); }
        public boolean matches(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File file) throws Exception { return actual.matches(name, file); }
        public PurplePlaylistsStore.Publication replace(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File file, byte[] bytes, PurplePlaylistsStore.Current current) throws Exception { return actual.replace(name, file, bytes, current); }
        public PurplePlaylistsStore.Publication confirm(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File file, PurplePlaylistsStore.Current current) throws Exception {
            PurplePlaylistsStore.Publication value = actual.confirm(name, file, current);
            return new PurplePlaylistsStore.Publication(false, value.file);
        }
        public void close() { }
    }
    private static void lifecycle(Path root) throws Exception {
        Path path = anchor(root);
        try (PurplePlaylistsPhysical physical = physical(path); Flow flow = new Flow(path, 0)) {
            PurplePlaylistsPublicationJournal journal = journal(physical, flow);
            check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Empty, "absence is empty without writing");
            physical.replace(PurplePlaylistsStore.Name.Library, physical.read(PurplePlaylistsStore.Name.Library), LIBRARY, () -> true);
            byte[] local = PurplePlaylistsLocalCore.defaults(101).canonicalBytes();
            physical.replace(PurplePlaylistsStore.Name.Local, physical.read(PurplePlaylistsStore.Name.Local), local, () -> true);
            flow.start(journal);
            check(flow.port.sends == 1 && flow.results.size() == 1 && flow.results.get(0).status == PurplePlaylistsSyncPublication.Status.Confirmed,
                    "actual native durable journal permits one send and verified owned readback");
            PurplePlaylistsPublicationJournal.Read pending = journal.read();
            check(pending.status == PurplePlaylistsPublicationJournal.Status.Pending && pending.randomId == flow.port.intent.randomId
                    && Arrays.equals(pending.payload(), flow.payload), "exact bytes and random survive native readback");
            byte[] mutable = pending.payload(); mutable[0] ^= 1;
            check(Arrays.equals(pending.payload(), flow.payload), "recovered payload immutable");
            check(Files.getPosixFilePermissions(path.resolve("account/playlists/sync-publication.bin")).equals(PosixFilePermissions.fromString("rw-------")), "journal uses private native file mode");
            check(physical.read(PurplePlaylistsStore.Name.Library).content(LIBRARY) && physical.read(PurplePlaylistsStore.Name.Local).content(local), "journal leaves library and opaque local state bytes unchanged");
            PurplePlaylistsPublicationJournal.Read confirmed = journal.confirm(pending, flow.results.get(0));
            check(confirmed.status == PurplePlaylistsPublicationJournal.Status.Confirmed && confirmed.messageId == 41
                    && confirmed.documentId.equals("9007199254740993"), "confirmed result durably records exact owned message and document");
            final boolean[] repeated = {true}; journal.prepare(flow.port.intent, value -> repeated[0] = value);
            check(!repeated[0] && journal.read().status == PurplePlaylistsPublicationJournal.Status.Confirmed, "even same random cannot automatically resend confirmed record");
            check(journal.confirm(pending, flow.results.get(0)).status == PurplePlaylistsPublicationJournal.Status.Unavailable, "stale pending snapshot cannot overwrite confirmation");
            Flow foreignFlow = new Flow(path, 0);
            PurplePlaylistsPublicationJournal foreignJournal = journal(physical, foreignFlow);
            check(foreignJournal.confirm(confirmed, flow.results.get(0)).status == PurplePlaylistsPublicationJournal.Status.Unavailable, "another journal cannot borrow a snapshot capability");
            flow.operation.close(); flow.client.drain();
            check(journal.confirm(confirmed, flow.results.get(0)).status == PurplePlaylistsPublicationJournal.Status.Unavailable, "revoked result never confirms after close");
            final PurplePlaylistsPublicationJournal.Status[] other = {null};
            Thread thread = new Thread(() -> other[0] = journal.read().status); thread.start(); thread.join();
            check(other[0] == PurplePlaylistsPublicationJournal.Status.Unavailable, "wrong thread refuses journal access");
            foreignFlow.close();
        }
        try (PurplePlaylistsPhysical reopened = physical(path); Flow flow = new Flow(path, 0)) {
            check(journal(reopened, flow).read().status == PurplePlaylistsPublicationJournal.Status.Confirmed, "confirmed record retains bytes after physical reopen");
        }
    }
    private static void unknownAndRecovery(Path root) throws Exception {
        Path path = anchor(root);
        try (PurplePlaylistsPhysical physical = physical(path); Flow flow = new Flow(path, 5)) {
            PurplePlaylistsPublicationJournal journal = journal(physical, flow); flow.start(journal);
            check(flow.port.sends == 1 && flow.results.get(0).status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown, "lost send reply stays unknown");
            check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Pending, "lost receipt retains durable payload");
        }
        try (PurplePlaylistsPhysical reopened = physical(path); Flow flow = new Flow(path, 0)) {
            PurplePlaylistsPublicationJournal journal = journal(reopened, flow);
            flow.start(journal); check(flow.port.sends == 0 && flow.results.get(0).status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown,
                    "restart pending refuses new wire send");
            PurplePlaylistsPublicationJournal.Read pending = journal.read();
            byte[] changed = flow.payload.clone(); changed[4] ^= 1; Files.write(flow.client.file.toPath(), changed);
            check(journal.recover(pending, flow.discover()).status == PurplePlaylistsPublicationJournal.Status.Pending, "same canonical library with different gzip cannot settle intent");
            Files.write(flow.client.file.toPath(), flow.payload); flow.client.lookupCount = 0;
            PurplePlaylistsSyncDiscovery.Result result = flow.discover();
            check(journal.recover(pending, result).status == PurplePlaylistsPublicationJournal.Status.Confirmed, "fresh owned exact bytes reconcile lost receipt without resend");
            check(flow.port.sends == 0, "discovery reconciliation never uploads or sends");
        }
        Path partial = anchor(root);
        try (PurplePlaylistsPhysical physical = physical(partial); Flow flow = new Flow(partial, 0)) {
            flow.start(journal(new Unconfirmed(physical), flow));
            check(flow.port.sends == 0 && flow.results.get(0).status == PurplePlaylistsSyncPublication.Status.OutcomeUnknown,
                    "unconfirmed native durability cannot release send continuation");
            check(journal(physical, flow).read().status == PurplePlaylistsPublicationJournal.Status.Pending, "possibly committed journal remains visible after refusal");
        }
    }
    private static void corruptAndForeign(Path root) throws Exception {
        for (int variant = 0; variant < 9; ++variant) {
            Path path = anchor(root);
            try (PurplePlaylistsPhysical physical = physical(path); Flow flow = new Flow(path, 5)) {
                PurplePlaylistsPublicationJournal original = journal(physical, flow); flow.start(original);
                Path file = path.resolve("account/playlists/sync-publication.bin"); byte[] before = Files.readAllBytes(file);
                PurpleSyncChatOrigin origin = flow.client.origin;
                PurpleSyncChatCore.Peer chat = flow.client.chat;
                if (variant == 0) origin = new PurpleSyncChatOrigin(1, 0, 101, origin.login);
                if (variant == 1) origin = new PurpleSyncChatOrigin(0, 1, 101, origin.login);
                if (variant == 2) origin = new PurpleSyncChatOrigin(0, 0, 102, origin.login);
                if (variant == 3) origin = new PurpleSyncChatOrigin(0, 0, 101, "replacement-login");
                if (variant == 4) chat = PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, 303);
                if (variant == 5) { before[before.length - 33] ^= 1; Files.write(file, before); }
                if (variant == 6) { before = Arrays.copyOf(before, 7); Files.write(file, before); }
                if (variant == 7) { before[7] = 3; Files.write(file, before); }
                if (variant == 8) { before = Arrays.copyOf(before, before.length + 1); Files.write(file, before); }
                PurplePlaylistsPublicationJournal journal = new PurplePlaylistsPublicationJournal(physical, origin, chat, () -> true);
                check(journal.read().status == (variant < 3 ? PurplePlaylistsPublicationJournal.Status.Foreign
                        : variant < 5 ? PurplePlaylistsPublicationJournal.Status.Empty
                        : variant == 7 ? PurplePlaylistsPublicationJournal.Status.Newer : PurplePlaylistsPublicationJournal.Status.Invalid), "foreign or malformed journal refuses recovery " + variant);
                final boolean[] accepted = {true}; journal.prepare(flow.port.intent, value -> accepted[0] = value);
                check(!accepted[0] && Arrays.equals(before, Files.readAllBytes(file)), "foreign newer or corrupted journal is never overwritten " + variant);
            }
        }
    }
    private static void boundsAndPaths(Path root) throws Exception {
        Path path = anchor(root);
        try (PurplePlaylistsPhysical physical = physical(path); Flow flow = new Flow(path, 0)) {
            PurplePlaylistsStore.File missing = physical.read(PurplePlaylistsStore.Name.SyncPublication);
            boolean refused = false;
            try { physical.replace(PurplePlaylistsStore.Name.SyncPublication, missing, new byte[PurplePlaylistsPublicationJournal.LIMIT + 1], () -> true); }
            catch (IOException expected) { refused = true; }
            check(refused && physical.read(PurplePlaylistsStore.Name.SyncPublication).absent(), "native journal write cap precedes publication");
            Path target = path.resolve("target"); Files.write(target, new byte[]{9});
            Files.createSymbolicLink(path.resolve("account/playlists/sync-publication.bin"), target);
            PurplePlaylistsPublicationJournal journal = journal(physical, flow); flow.start(journal);
            check(flow.port.sends == 0 && Arrays.equals(Files.readAllBytes(target), new byte[]{9}), "symlink journal cannot send or overwrite target");
            Files.delete(path.resolve("account/playlists/sync-publication.bin"));
            try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(path.resolve("account/playlists/sync-publication.bin").toFile(), "rw")) { sparse.setLength(PurplePlaylistsPublicationJournal.LIMIT + 1L); }
            Files.setPosixFilePermissions(path.resolve("account/playlists/sync-publication.bin"), PosixFilePermissions.fromString("rw-------"));
            check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Unavailable, "native bounded read refuses oversized journal before allocation");
        }
    }
    private static boolean contains(byte[] whole, byte[] part) {
        outer: for (int i = 0; i <= whole.length - part.length; ++i) {
            for (int j = 0; j != part.length; ++j) if (whole[i + j] != part[j]) continue outer;
            return true;
        }
        return false;
    }
    private static void routes(Path root) throws Exception {
        Path path = anchor(root); byte[][] originals = new byte[4][];
        Path disk = path.resolve("account/playlists/sync-publication.bin");
        try (PurplePlaylistsPhysical physical = physical(path)) {
            for (int i = 0; i != 4; ++i) {
                try (Flow flow = new Flow(path, 5, "route-login-" + (i / 2), 101 + i % 2)) {
                    PurplePlaylistsPublicationJournal journal = journal(physical, flow);
                    byte[] before = Files.exists(disk) ? Files.readAllBytes(disk) : null;
                    check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Empty, "new selected route has no current intent " + i);
                    check(before == null || Arrays.equals(before, Files.readAllBytes(disk)), "route selection alone does not mutate journal " + i);
                    flow.start(journal);
                    check(flow.port.sends == 1 && journal.read().status == PurplePlaylistsPublicationJournal.Status.Pending, "new route sends only after durable preservation " + i);
                    originals[i] = flow.port.intent.bytes();
                    if (i == 0) originals[i] = Files.readAllBytes(disk);
                    check(contains(Files.readAllBytes(disk), originals[0]), "old unresolved record remains byte exact " + i);
                }
            }
            byte[] full = Files.readAllBytes(disk);
            try (Flow fifth = new Flow(path, 5, "route-login-3", 107)) {
                fifth.start(journal(physical, fifth));
                check(fifth.port.sends == 0 && Arrays.equals(full, Files.readAllBytes(disk)), "full route backlog refuses send without dropping any intent");
            }
            for (int i = 0; i != 4; ++i) {
                try (Flow old = new Flow(path, 5, "route-login-" + (i / 2), 101 + i % 2)) {
                    PurplePlaylistsPublicationJournal journal = journal(physical, old);
                    check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Pending
                            && journal.read().randomId == 0x1020304050607080L, "returning route reads its own original random " + i);
                    old.start(journal);
                    check(old.port.sends == 0 && Arrays.equals(full, Files.readAllBytes(disk)), "returning unresolved route never resends or edits another route " + i);
                }
            }
        }
        try (PurplePlaylistsPhysical reopened = physical(path); Flow old = new Flow(path, 0, "route-login-0", 101)) {
            PurplePlaylistsPublicationJournal journal = journal(reopened, old);
            check(Arrays.equals(journal.read().payload(), old.payload), "native reopen retains exact old-route payload");
            check(journal.recover(journal.read(), old.discover()).status == PurplePlaylistsPublicationJournal.Status.Confirmed,
                    "fresh exact owned discovery settles archived route without sending");
            byte[] settled = Files.readAllBytes(disk);
            try (Flow other = new Flow(path, 5, "route-login-1", 102)) {
                check(journal(reopened, other).read().status == PurplePlaylistsPublicationJournal.Status.Pending,
                        "settling one route preserves unrelated unknown route");
            }
            check(old.port.sends == 0 && !Arrays.equals(settled, originals[0]), "archived recovery has no upload and retains envelope");
            byte[] nested = settled.clone();
            int firstLength = java.nio.ByteBuffer.wrap(nested, 12, 4).getInt();
            nested[16 + firstLength - 1] ^= 1;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(nested, nested.length - 32));
            System.arraycopy(digest, 0, nested, nested.length - 32, 32); Files.write(disk, nested);
            check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Invalid, "valid envelope cannot mask corrupt retained record");
            Files.write(disk, settled);
            byte[] invalid = settled.clone(); invalid[invalid.length - 1] ^= 1; Files.write(disk, invalid);
            old.start(journal);
            check(old.port.sends == 0 && journal.read().status == PurplePlaylistsPublicationJournal.Status.Invalid
                    && Arrays.equals(invalid, Files.readAllBytes(disk)), "corrupt envelope refuses all writes and sends");
        }
        Path unconfirmed = anchor(root);
        try (PurplePlaylistsPhysical physical = physical(unconfirmed); Flow old = new Flow(unconfirmed, 5)) {
            old.start(journal(physical, old));
            byte[] saved = Files.readAllBytes(unconfirmed.resolve("account/playlists/sync-publication.bin"));
            try (Flow next = new Flow(unconfirmed, 5, "new-login", 101)) {
                next.start(journal(new Unconfirmed(physical), next));
                check(next.port.sends == 0, "failed envelope durability confirmation never releases new send");
                check(contains(Files.readAllBytes(unconfirmed.resolve("account/playlists/sync-publication.bin")), saved)
                        && journal(physical, old).read().status == PurplePlaylistsPublicationJournal.Status.Pending,
                        "partially committed envelope retains old pending bytes");
                check(journal(physical, next).read().status == PurplePlaylistsPublicationJournal.Status.Pending,
                        "uncertain new intent remains discoverable without resend");
            }
        }
    }
    private static void cold(Path root, String mode) throws Exception {
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        if (mode.startsWith("routes-")) {
            try (PurplePlaylistsPhysical physical = physical(root);
                    Flow old = new Flow(root, 5, "cold-old-login", 101);
                    Flow next = new Flow(root, 5, "cold-new-login", 303)) {
                if (mode.equals("routes-seed")) {
                    old.start(journal(physical, old));
                    byte[] before = Files.readAllBytes(root.resolve("account/playlists/sync-publication.bin"));
                    next.start(journal(physical, next));
                    check(contains(Files.readAllBytes(root.resolve("account/playlists/sync-publication.bin")), before),
                            "cold seed preserves byte-exact old intent before new send");
                    check(old.port.sends == 1 && next.port.sends == 1, "cold seed creates two independently scoped unknown intents");
                } else {
                    PurplePlaylistsPublicationJournal.Read prior = journal(physical, old).read();
                    check(prior.status == PurplePlaylistsPublicationJournal.Status.Pending && Arrays.equals(prior.payload(), old.payload),
                            "new JVM restores old-login intent from envelope");
                    check(journal(physical, next).read().status == PurplePlaylistsPublicationJournal.Status.Pending,
                            "new JVM restores selected-chat intent from envelope");
                    old.start(journal(physical, old)); next.start(journal(physical, next));
                    check(old.port.sends == 0 && next.port.sends == 0, "new JVM never resends either unknown route");
                }
            }
            System.out.println("PLAYLIST_PUBLICATION_JOURNAL_CHILD=" + mode + ":PASS"); return;
        }
        try (PurplePlaylistsPhysical physical = physical(root); Flow flow = new Flow(root, 5)) {
            PurplePlaylistsPublicationJournal journal = journal(physical, flow);
            if (mode.equals("seed")) { flow.start(journal); check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Pending, "child seeded pending journal"); }
            else { check(journal.read().status == PurplePlaylistsPublicationJournal.Status.Pending, "new JVM recovers exact pending journal");
                PurplePlaylistsPublicationJournal.Read saved = journal.recover(journal.read(), flow.discover());
                check(saved.status == PurplePlaylistsPublicationJournal.Status.Confirmed && flow.port.sends == 0, "new JVM reconciles owned record without wire resend"); }
        }
        System.out.println("PLAYLIST_PUBLICATION_JOURNAL_CHILD=" + mode + ":PASS");
    }
    public static void main(String[] args) throws Exception {
        Path root = java.nio.file.Paths.get(args[0]); Files.createDirectories(root);
        if (args.length > 1) { cold(root, args[1]); return; }
        routes(root); lifecycle(root); unknownAndRecovery(root); corruptAndForeign(root); boundsAndPaths(root);
        Path childRoot = root.resolve("cold-process");
        for (String mode : new String[]{"seed", "recover", "routes-seed", "routes-recover"}) {
            Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xcheck:jni", "-Xmx256m",
                    "-Dpurple.test.library=" + System.getProperty("purple.test.library"), "-cp", System.getProperty("java.class.path"),
                    PurplePlaylistsPublicationJournalCases.class.getName(), (mode.startsWith("routes-") ? root.resolve("cold-routes") : childRoot).toString(), mode).inheritIO().start();
            check(child.waitFor(60, TimeUnit.SECONDS) && child.exitValue() == 0, "cold child terminal " + mode);
        }
        System.out.println("PLAYLIST_PUBLICATION_JOURNAL_CHECKS=" + checks);
        System.out.println("PLAYLIST_PUBLICATION_JOURNAL_OK");
    }
}
