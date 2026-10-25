package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.json.JSONArray;

public final class PurpleArchiveStoreCases {
    private static int checks;
    private static void check(boolean value, String message) { ++checks; if (!value) throw new AssertionError(message); }
    static final class Keys implements PurpleArchiveKeys.Provider {
        final HashMap<String, SecretKey> values = new HashMap<>();
        int creates, finds, removes; boolean failCreateAfter, failRemove;
        public java.util.Enumeration<String> aliases() { return java.util.Collections.enumeration(values.keySet()); }
        public SecretKey find(String alias) { ++finds; return values.get(alias); }
        public SecretKey create(String alias) throws Exception {
            ++creates; if (values.containsKey(alias)) throw new IllegalStateException("duplicate platform alias");
            byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
            SecretKey value = new SecretKeySpec(key, "AES"); Arrays.fill(key, (byte) 0); values.put(alias, value);
            if (failCreateAfter) throw new Exception("platform interrupted after creation");
            return value;
        }
        public void remove(String alias) throws Exception { ++removes; if (failRemove) throw new Exception("platform unavailable"); values.remove(alias); }
    }
    static final class Environment implements PurpleArchiveStore.Environment {
        final File files; final Keys keys = new Keys(); final Object mutex = new Object();
        boolean current = true; int api = 23;
        Environment(File root, String name) throws Exception { files = new File(root, name); check(files.mkdir(), "exclusive account app files"); }
        public File files() { return files; }
        public int api() { return api; }
        public boolean current() { return current; }
        public Object publicationLock() { return mutex; }
        public Keys keys() { return keys; }
    }
    interface Action { void run() throws Exception; }
    static void refuses(Action action, String message) throws Exception {
        boolean refused = false; try { action.run(); } catch (Exception e) { refused = true; } check(refused, message);
    }
    private static void basic(File root) throws Exception {
        Environment env = new Environment(root, "basic");
        PurpleArchiveStore first = PurpleArchiveStore.open(env, "0-11");
        check(first.current() && env.keys.creates == 1, "new store committed with one platform key");
        String store = first.binding.store; File database = first.directory.file("archive.sqlite");
        check(database.length() > 0, "real bundled SQLite file");
        PurpleArchiveStore.Reader reader = first.reader(); check(reader.current(), "reader scoped to current generation");
        refuses(first::closeDatabase, "live reader prevents close acknowledgement"); reader.close(); first.close();
        PurpleArchiveStore again = PurpleArchiveStore.open(env, "0-11");
        check(again.binding.store.equals(store) && env.keys.creates == 1, "reopen retains store and key");
        PurpleArchiveBinding bound = again.binding; again.close();
        env.keys.values.clear();
        byte[] old = Files.readAllBytes(database.toPath());
        refuses(() -> PurpleArchiveStore.open(env, "0-11"), "lost platform key refuses existing archive");
        check(env.keys.creates == 1 && Arrays.equals(old, Files.readAllBytes(database.toPath())), "lost key never recreates or mutates DB");
        PurpleArchivePaths paths = new PurpleArchivePaths(env.files, "0-11");
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) {
            PurpleArchiveRemoval removal = PurpleArchiveRemoval.begin(paths);
            check(removal != null, "lost key does not block explicit logout removal"); removal.finish(null, env.keys);
        }
        check(!database.exists() && paths.metadata.read("binding.json", 65536).bytes == null
            && paths.metadata.read("removing.json", 65536).bytes == null, "logout removes DB binding intent");
        PurpleArchiveStore fresh = PurpleArchiveStore.open(env, "0-11");
        check(!fresh.binding.store.equals(store) && !fresh.binding.key.equals(bound.key), "relogin gets independent store and key"); fresh.close();
    }
    private static void readers(File root) throws Exception {
        Environment env = new Environment(root, "readers"); PurpleArchiveStore store = PurpleArchiveStore.open(env, "0-11");
        PurpleArchiveStore.Reader[] readers = new PurpleArchiveStore.Reader[8];
        PurpleArchiveCore.Statement metadata = new PurpleArchiveCore.Statement(PurpleArchiveCore.call("query",
            store.arguments().put("query", "metadata"), store.crypto()).getJSONObject("statement"));
        for (int i = 0; i < readers.length; ++i) {
            readers[i] = store.reader();
            check(readers[i].current() && readers[i].read(metadata).rows == 1, "separate checked reader snapshot " + i);
        }
        refuses(store::reader, "ninth reader refuses bounded lease limit");
        PurpleArchiveFaults.set(6);
        refuses(readers[0]::close, "BUSY reader close retains its lease");
        refuses(store::closeDatabase, "uncertain reader close cannot release writer guard");
        readers[0].close();
        PurpleArchiveStore.Reader replacement = store.reader();
        check(replacement.current(), "checked close frees exactly one reader slot");
        JSONObject headerPlan = PurpleArchiveCore.call("query", store.arguments().put("query", "header"), store.crypto());
        PurpleArchiveSqlite.Value[] header = store.readStatement(new PurpleArchiveCore.Statement(headerPlan.getJSONObject("statement"))).values[0];
        JSONObject row = new JSONObject().put("kind", 1).put("id", header[0].text()).put("position", Long.toString(header[1].integer))
            .put("frame", PurpleArchiveCore.base64(header[2].bytes));
        JSONObject key = new JSONObject().put("account", "0-11").put("chat", new JSONObject().put("kind", 0).put("id", "55"))
            .put("messageId", "77").put("collection", 0).put("collectionId", "0").put("representation", "original");
        JSONObject facts = PurpleArchiveCore.call("facts", store.arguments().put("rows", new JSONArray().put(row)).put("key", key), store.crypto());
        JSONObject event = new JSONObject().put("origin", 2).put("id", "reader-revision").put("order", "1")
            .put("accountGeneration", facts.getString("accountGeneration")).put("chatGeneration", facts.getString("chatGeneration"))
            .put("key", key).put("observedAt", "234").put("serverTime", "0");
        JSONObject staleEvent = new JSONObject(event.toString()).put("lifecycleEpoch", new JSONObject()
            .put("account", "0-11").put("store", store.binding.store)
            .put("generation", PurpleArchiveBinding.next(store.binding.generation)).put("removalPending", false))
            .put("lifecycleMediaIdentity", "already-acquired");
        JSONObject stale = PurpleArchiveCore.call("capture", store.arguments().put("rows", new JSONArray().put(row)).put("event", staleEvent), store.crypto());
        check(!stale.getString("status").equals("Ready") && stale.getJSONArray("statements").length() == 0,
            "native capture retains original lifecycle epoch and refuses against fresh executor epoch");
        JSONObject wrongStore = new JSONObject(event.toString()).put("lifecycleEpoch", new JSONObject()
            .put("account", "0-11").put("store", "different-store").put("generation", store.binding.generation).put("removalPending", false))
            .put("lifecycleMediaIdentity", "already-acquired");
        JSONObject refused = PurpleArchiveCore.call("capture", store.arguments().put("rows", new JSONArray().put(row)).put("event", wrongStore), store.crypto());
        check(!refused.getString("status").equals("Ready") && refused.getJSONArray("statements").length() == 0,
            "same generation cannot substitute lifecycle store identity");
        PurpleArchiveCore.Program change = new PurpleArchiveCore.Program(PurpleArchiveCore.call("capture",
            store.arguments().put("rows", new JSONArray().put(row)).put("event", event), store.crypto()));
        check(store.execute(change).committed, "real core capture changes header in checked durable transaction");
        check(!replacement.current(), "committed revision change revokes reader snapshot");
        refuses(() -> replacement.read(metadata), "old snapshot cannot publish a page after revision change");
        store.drainReaders();
        check(!replacement.current(), "drain releases every tracked reader");
        PurpleArchiveStore.Reader logout = store.reader();
        PurpleArchiveRemoval removal = PurpleArchiveRemoval.begin(store.paths);
        check(!logout.current(), "durable logout fence revokes reader before removal");
        removal.finish(store, env.keys);
        check(!logout.current() && !store.directory.file.exists(), "logout force-drains live snapshots before database deletion");
        logout.close(); store.close();
    }
    private static void admission(File root) throws Exception {
        Environment old = new Environment(root, "api22"); old.api = 22;
        refuses(() -> PurpleArchiveStore.open(old, "0-11"), "API22 visibly gated");
        check(!new File(old.files, "purple").exists() && old.keys.finds == 0, "API gate before filesystem and Keystore");
        Environment interrupted = new Environment(root, "interrupted"); interrupted.keys.failCreateAfter = true;
        refuses(() -> PurpleArchiveStore.open(interrupted, "0-11"), "creation interruption propagated");
        interrupted.keys.failCreateAfter = false;
        PurpleArchiveStore recovered = PurpleArchiveStore.open(interrupted, "0-11");
        check(interrupted.keys.creates == 1 && recovered.current(), "creation resumes dedicated alias without recreating key"); recovered.close();
        Environment future = new Environment(root, "future");
        PurpleArchiveStore existing = PurpleArchiveStore.open(future, "0-11"); File db = existing.directory.file("archive.sqlite"); existing.close();
        try (PurpleArchiveSqlite raw = new PurpleArchiveSqlite(db.getAbsolutePath(), false)) {
            check(raw.run("UPDATE archive_meta SET schema_version=9223372036854775807").done(), "future schema fixture persisted");
        }
        int finds = future.keys.finds;
        byte[] bytes = Files.readAllBytes(db.toPath());
        refuses(() -> PurpleArchiveStore.open(future, "0-11"), "full-width future schema refused");
        check(future.keys.finds == finds && Arrays.equals(bytes, Files.readAllBytes(db.toPath())), "future schema preflight read only before key access");
    }
    private static void schemaAdmission(File root) throws Exception {
        Environment old = new Environment(root, "format1"); PurpleArchivePaths paths = new PurpleArchivePaths(old.files, "0-11");
        PurpleArchiveBinding binding = new PurpleArchiveBinding("0-11", new JSONObject().put("format", "purple-archive-binding").put("version", 1)
            .put("account", "0-11").put("store", "fixture-store").put("key", "fixture-key").put("generation", "1"));
        PurpleArchiveFiles.Directory directory = paths.store(binding.store, true);
        paths.metadata.publish("binding.json", paths.metadata.read("binding.json", 65536), binding.bytes());
        paths.metadata.publish("fence.json", paths.metadata.read("fence.json", 65536), binding.bytes());
        SecretKey key = new SecretKeySpec(new byte[32], "AES"); old.keys.values.put(binding.alias(), key);
        try (PurpleArchiveCrypto wrapping = new PurpleArchiveCrypto(binding.alias(), key)) {
            byte[] wrapped = PurpleArchiveCore.envelopeNative(binding.account, binding.store, binding.key, binding.alias(), new byte[32], true, wrapping);
            directory.publish("wrapped.key", directory.read("wrapped.key", 65536), wrapped);
        }
        File database = directory.file("archive.sqlite");
        try (PurpleArchiveSqlite raw = new PurpleArchiveSqlite(database.getAbsolutePath(), true)) {
            check(raw.run("PRAGMA journal_mode=WAL").done() && raw.run("PRAGMA synchronous=FULL").done(), "base draft connection configured");
            for (String line : Files.readAllLines(new File(System.getProperty("purple.archive.format1")).toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                if (!line.isEmpty()) check(raw.run(line.substring(0, line.length() - 1)).done(), "actual base initialization statement");
            }
        }
        byte[] before = Files.readAllBytes(database.toPath());
        byte[] wal = Files.readAllBytes(new File(database.getAbsolutePath() + "-wal").toPath());
        boolean mismatch = false;
        try { PurpleArchiveStore.open(old, "0-11"); } catch (PurpleArchiveStore.Failure e) { mismatch = e.reason.contains("SchemaMismatch"); }
        check(mismatch && old.keys.finds == 0, "genuine format1 draft refuses SchemaMismatch before new columns or Keystore");
        check(Arrays.equals(before, Files.readAllBytes(database.toPath()))
            && Arrays.equals(wal, Files.readAllBytes(new File(database.getAbsolutePath() + "-wal").toPath())), "refused format1 draft preserved without migration");
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) {
            PurpleArchiveRemoval.begin(paths).finish(null, old.keys);
        }
        PurpleArchiveStore recreated = PurpleArchiveStore.open(old, "0-11");
        check(!recreated.binding.store.equals(binding.store) && old.keys.creates == 1, "explicit discard creates independent format2 store and key"); recreated.close();
        for (String mutation : new String[]{"UPDATE archive_meta SET schema_fingerprint='wrong'", "CREATE INDEX extra_index ON messages(message_id)", "ALTER TABLE messages ADD COLUMN extra_column INTEGER"}) {
            Environment env = new Environment(root, "shape" + checks); PurpleArchiveStore store = PurpleArchiveStore.open(env, "0-11");
            File db = store.directory.file("archive.sqlite"); store.close();
            try (PurpleArchiveSqlite raw = new PurpleArchiveSqlite(db.getAbsolutePath(), false)) { check(raw.run(mutation).done(), "schema drift fixture mutation"); }
            int finds = env.keys.finds; boolean refused = false;
            try { PurpleArchiveStore.open(env, "0-11"); } catch (PurpleArchiveStore.Failure e) { refused = e.reason.contains("SchemaMismatch"); }
            check(refused && env.keys.finds == finds, "fingerprint or actual DDL drift refuses on every reopen");
        }
    }
    private static void removalCuts(File root) throws Exception {
        for (int cut = 0; cut <= 10; ++cut) {
            Environment env = new Environment(root, "cut" + cut);
            PurpleArchiveStore a = PurpleArchiveStore.open(env, "0-11");
            PurpleArchiveStore b = PurpleArchiveStore.open(env, "0-22");
            File other = b.directory.file("archive.sqlite"); String otherAlias = b.binding.alias();
            PurpleArchiveFiles.Directory blobs = a.directory.child("blobs", true);
            for (int i = 0; i < 270; ++i) blobs.publish("chunk-" + i, blobs.read("chunk-" + i, 128), new byte[]{1, 2, 3});
            PurpleArchiveRemoval removal = PurpleArchiveRemoval.begin(a.paths);
            check(!a.current(), "durable removal fences active work before drain");
            for (int i = 0; i < cut && !removal.step(a, env.keys); ++i) {}
            a.close();
            try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(a.paths.metadata)) {
                byte[] pending = a.paths.metadata.read("removing.json", 65536).bytes;
                if (pending != null) PurpleArchiveRemoval.resume(a.paths, pending).finish(null, env.keys);
            }
            check(!a.directory.file.exists() && other.exists() && env.keys.values.containsKey(otherAlias), "restart cut removes only captured account " + cut);
            check(a.paths.metadata.read("removing.json", 65536).bytes == null, "durable intent clears after all artifacts " + cut);
            b.close();
        }
    }
    private static void usageAndGaps(File root) throws Exception {
        Environment env = new Environment(root, "usage"); PurpleArchiveStore store = PurpleArchiveStore.open(env, "0-11");
        PurpleArchiveFiles.Directory blobs = store.directory.child("blobs", true);
        long payload = 0;
        for (int i = 0; i < 260; ++i) {
            byte[] bytes = new byte[i + 1]; payload += bytes.length;
            blobs.publish("owned-" + i, blobs.read("owned-" + i, 300), bytes);
        }
        String total = PurpleArchiveUsage.scan(store);
        check(new java.math.BigInteger(total).compareTo(java.math.BigInteger.valueOf(payload)) > 0, "usage streams physical database and owned files beyond one page");
        check(!store.hasGaps(), "new store no capture gaps");
        PurpleArchiveStore capturedStore = store;
        PurpleArchiveFaults.set(3);
        refuses(() -> PurpleArchiveGaps.record(capturedStore, "storage-failure", 123), "failed gap COMMIT stays unacknowledged");
        check(!store.hasGaps() && store.paths.metadata.read("gap-outbox.json", 65536).bytes != null, "SQL rollback preserves bounded durable gap outbox");
        PurpleArchiveGaps.flush(store);
        check(store.hasGaps() && store.paths.metadata.read("gap-outbox.json", 65536).bytes == null, "gap acknowledged only after SQL and durable outbox clear");
        String storeId = store.binding.store; store.close();
        store = PurpleArchiveStore.open(env, "0-11");
        check(store.binding.store.equals(storeId) && store.hasGaps(), "durable gap visible after reopen");
        env.current = false;
        PurpleArchiveStore captured = store;
        refuses(() -> PurpleArchiveUsage.scan(captured), "usage refuses revoked account generation");
        store.close();
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return result.toString();
    }
    private static void ownedMedia(File root) throws Exception {
        Environment env = new Environment(root, "media"); PurpleArchiveStore store = PurpleArchiveStore.open(env, "0-11");
        byte[] source = new byte[2 * 1024 * 1024 + 13]; new java.security.SecureRandom().nextBytes(source);
        PurpleArchiveOwnedMedia.Prepared prepared = PurpleArchiveOwnedMedia.stage(store, new java.io.ByteArrayInputStream(source), source.length, hash(source));
        check(prepared.chunks == 3 && prepared.blobToken.matches("[0-9a-f]{64}") && !prepared.blobToken.equals(prepared.hash), "keyed blob token and bounded encrypted chunks");
        PurpleArchiveOwnedMedia.publish(store, prepared);
        refuses(() -> PurpleArchiveOwnedMedia.publish(store, prepared), "immutable owned blob cannot overwrite existing publication");
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        PurpleArchiveOwnedMedia.readVerified(store, prepared, output);
        check(Arrays.equals(source, output.toByteArray()), "whole-file length and hash verified before successful media delivery");
        File chunk = store.directory.child("blobs", true).child(prepared.blobToken, true).file("chunk-2");
        byte[] original = Files.readAllBytes(chunk.toPath()); byte[] damaged = original.clone(); damaged[damaged.length - 1] ^= 1;
        Files.write(chunk.toPath(), damaged); java.io.ByteArrayOutputStream rejected = new java.io.ByteArrayOutputStream();
        refuses(() -> PurpleArchiveOwnedMedia.readVerified(store, prepared, rejected), "late chunk corruption refuses whole file");
        check(rejected.size() == 0, "no plaintext exposed from failed whole-file verification");
        Files.write(chunk.toPath(), original);
        refuses(() -> PurpleArchiveOwnedMedia.stage(store, new java.io.ByteArrayInputStream(source), source.length, hash(new byte[]{1})), "changed source hash never becomes Complete");
        PurpleArchiveOwnedMedia.Prepared empty = PurpleArchiveOwnedMedia.stage(store, new java.io.ByteArrayInputStream(new byte[0]), 0, hash(new byte[0]));
        check(empty.chunks == 1, "empty media uses one authenticated chunk"); PurpleArchiveOwnedMedia.publish(store, empty);
        java.io.ByteArrayOutputStream emptyOutput = new java.io.ByteArrayOutputStream(); PurpleArchiveOwnedMedia.readVerified(store, empty, emptyOutput);
        check(emptyOutput.size() == 0, "empty file authenticated and delivered");
        java.io.OutputStream revoke = new java.io.OutputStream() {
            public void write(int value) { env.current = false; }
            public void write(byte[] bytes, int offset, int length) { env.current = false; }
        };
        refuses(() -> PurpleArchiveOwnedMedia.readVerified(store, prepared, revoke), "generation loss during reader delivery refuses completion");
        store.close();
    }
    private static void damagedBindingIsolation(File root) throws Exception {
        Environment env = new Environment(root, "binding-isolation");
        PurpleArchiveStore a = PurpleArchiveStore.open(env, "0-11"), b = PurpleArchiveStore.open(env, "0-22");
        PurpleArchiveBinding original = a.binding, other = b.binding;
        File first = a.directory.file("archive.sqlite"), second = b.directory.file("archive.sqlite");
        a.close(); b.close();
        byte[] firstBytes = Files.readAllBytes(first.toPath()), otherBytes = Files.readAllBytes(second.toPath());
        PurpleArchiveBinding damaged = new PurpleArchiveBinding("0-11", new JSONObject(other.value.toString()).put("account", "0-11")
            .put("generation", original.generation));
        check(!damaged.alias().equals(other.alias()), "Keystore aliases remain account-scoped when store and key ids coincide");
        PurpleArchivePaths paths = new PurpleArchivePaths(env.files, "0-11");
        paths.metadata.publish("binding.json", paths.metadata.read("binding.json", 65536), damaged.bytes());
        paths.metadata.publish("fence.json", paths.metadata.read("fence.json", 65536), damaged.bytes());
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) {
            PurpleArchiveRemoval.begin(paths).finish(null, env.keys);
        }
        check(paths.metadata.read("removing.json", 65536).bytes == null && !first.exists()
            && !env.keys.values.containsKey(original.alias()), "logout erases every retained store and key for the damaged account");
        check(Arrays.equals(otherBytes, Files.readAllBytes(second.toPath())) && env.keys.values.containsKey(other.alias()),
            "damaged account cleanup preserves the other account's files and key");
    }
    private static void missingCatalogue(File root) throws Exception {
        for (int variant = 0; variant < 6; ++variant) {
            Environment env = new Environment(root, "missing" + variant);
            PurpleArchiveStore a = PurpleArchiveStore.open(env, "0-11"), b = PurpleArchiveStore.open(env, "0-22");
            PurpleArchiveBinding original = a.binding, other = b.binding;
            File old = a.directory.file("archive.sqlite"), otherDb = b.directory.file("archive.sqlite");
            if (variant == 0) {
                a.paths.metadata.remove("binding.json", false);
                check(!a.current(), "binding loss immediately revokes an already-open store");
            }
            byte[] otherBytes; a.close(); b.close(); otherBytes = Files.readAllBytes(otherDb.toPath());
            PurpleArchivePaths paths = new PurpleArchivePaths(env.files, "0-11");
            paths.metadata.remove("binding.json", false);
            if (variant == 0) {
                PurpleArchiveFiles.Directory extra = paths.store("retained-second", true);
                extra.publish("wrapped.key", extra.read("wrapped.key", 32), new byte[]{1, 2});
                extra.publish("archive.sqlite", extra.read("archive.sqlite", 32), new byte[]{3, 4});
                env.keys.create(PurpleArchiveKeys.alias("0-11", "retained-second", "independent-key"));
            }
            if (variant == 1 || variant == 5) env.keys.values.remove(original.alias());
            if (variant == 2 || variant == 4 || variant == 5) PurpleArchiveRemoval.removeTree(paths.accountsRoot, "0-11");
            if (variant == 3 || variant == 4) paths.metadata.remove("fence.json", false);
            int creates = env.keys.creates;
            refuses(() -> PurpleArchiveStore.open(env, "0-11"), "missing binding refuses retained catalogue variant " + variant);
            check(env.keys.creates == creates && paths.metadata.read("creating.json", 65536).bytes == null
                && paths.metadata.read("recovery.json", 65536).bytes != null, "refusal creates no replacement key/store and retains recovery obligation");
            try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) {
                PurpleArchiveRemoval removal = PurpleArchiveRemoval.begin(paths);
                if (variant == 0) {
                    env.keys.failRemove = true;
                    PurpleArchiveRemoval interrupted = removal;
                    refuses(() -> interrupted.finish(null, env.keys), "interrupted orphan key cleanup refuses completion");
                    check(paths.metadata.read("removing.json", 65536).bytes != null, "orphan cleanup intent survives interruption");
                    env.keys.failRemove = false;
                    removal = PurpleArchiveRemoval.resume(paths, paths.metadata.read("removing.json", 65536).bytes);
                }
                removal.finish(null, env.keys);
            }
            check(!old.exists() && !PurpleArchiveCatalogue.hasUnexpectedStore(paths, null)
                && !PurpleArchiveCatalogue.hasUnexpectedKey("0-11", env.keys, null)
                && paths.metadata.read("removing.json", 65536).bytes == null
                && paths.metadata.read("recovery.json", 65536).bytes == null, "logout removes every retained account artifact variant " + variant);
            check(Arrays.equals(otherBytes, Files.readAllBytes(otherDb.toPath())) && env.keys.values.containsKey(other.alias()),
                "orphan cleanup leaves account B unchanged");
            PurpleArchiveStore fresh = PurpleArchiveStore.open(env, "0-11");
            check(!fresh.binding.store.equals(original.store) && env.keys.creates == creates + 1,
                "only completed cleanup admits a new independent store"); fresh.close();
        }
    }

    private static void unsafe(File root) throws Exception {
        Environment env = new Environment(root, "unsafe"); PurpleArchiveStore a = PurpleArchiveStore.open(env, "0-11");
        File outside = new File(env.files, "outside"); Files.write(outside.toPath(), new byte[]{7});
        Files.createSymbolicLink(a.directory.child("blobs", true).file("unsafe").toPath(), outside.toPath());
        PurpleArchiveRemoval removal = PurpleArchiveRemoval.begin(a.paths);
        refuses(() -> removal.finish(a, env.keys), "logout refuses symlink artifact");
        check(a.paths.metadata.read("removing.json", 65536).bytes != null && outside.exists(), "unsafe artifact preserves intent and outside bytes");
        Files.delete(a.directory.child("blobs", true).file("unsafe").toPath());
        a.close();
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(a.paths.metadata)) {
            PurpleArchiveRemoval.resume(a.paths, a.paths.metadata.read("removing.json", 65536).bytes).finish(null, env.keys);
        }
        check(outside.exists(), "safe retry leaves unrelated file");
    }
    private static void loginAdmission(File root) throws Exception {
        Environment environment = new Environment(root, "login-anchor");
        PurpleArchivePaths paths = new PurpleArchivePaths(environment.files, "0-11");
        String nonce = "11111111111111111111111111111111", next = "22222222222222222222222222222222";
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) { PurpleArchiveLogin.admit(paths, nonce, environment.keys); }
        PurpleArchiveStore first = PurpleArchiveStore.open(environment, "0-11"); PurpleArchiveLogin.bind(first, nonce);
        String store = first.binding.store; File database = first.directory.file("archive.sqlite"); first.close();
        byte[] before = paths.metadata.read("login.json", 65536).bytes;
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) { PurpleArchiveLogin.admit(paths, nonce, environment.keys); }
        PurpleArchiveStore retained = PurpleArchiveStore.open(environment, "0-11");
        check(retained.binding.store.equals(store), "same-login offline restart retains store"); retained.close();
        check(Arrays.equals(before, paths.metadata.read("login.json", 65536).bytes), "same-login admission does not rewrite its authenticated record");
        Environment otherEnvironment = new Environment(root, "login-other");
        PurpleArchiveStore other = PurpleArchiveStore.open(otherEnvironment, "0-22"); File otherDb = other.directory.file("archive.sqlite"); other.close();
        byte[] otherBefore = Files.readAllBytes(otherDb.toPath());
        JSONObject tampered = PurpleArchiveBinding.json(before).put("nonce", next);
        paths.metadata.publish("login.json", paths.metadata.read("login.json", 65536), PurpleArchiveCatalogue.bytes(tampered));
        refuses(() -> PurpleArchiveLogin.admit(paths, next, environment.keys), "unauthenticated nonce mismatch cannot erase archive");
        check(database.exists() && environment.keys.values.size() == 1, "tampered admission keeps database and key");
        paths.metadata.publish("login.json", paths.metadata.read("login.json", 65536), before);
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) { PurpleArchiveLogin.admit(paths, next, environment.keys); }
        check(!database.exists() && environment.keys.values.isEmpty(), "new login after all logout writes failed erases authenticated old orphan");
        check(Arrays.equals(otherBefore, Files.readAllBytes(otherDb.toPath())) && otherEnvironment.keys.values.size() == 1, "orphan cleanup leaves B archive and key untouched");
        PurpleArchiveStore fresh = PurpleArchiveStore.open(environment, "0-11"); PurpleArchiveLogin.bind(fresh, next);
        check(!fresh.binding.store.equals(store), "new login cannot reopen old store with retained transport keys");
        String freshStore = fresh.binding.store; fresh.close();
        paths.metadata.publish("login.json", paths.metadata.read("login.json", 65536), new byte[]{123});
        refuses(() -> PurpleArchiveLogin.admit(paths, next, environment.keys), "malformed login record refuses");
        check(paths.store(freshStore, false).file("archive.sqlite").exists() && environment.keys.values.size() == 1, "malformed admission preserves database and key");
        paths.metadata.remove("login.json", false);
        refuses(() -> PurpleArchiveLogin.admit(paths, next, environment.keys), "missing admission refuses retained archive");
        String seal = PurpleArchiveDiscard.seal(paths);
        paths.metadata.publish("recovery.json", paths.metadata.read("recovery.json", 65536), new byte[]{123});
        check(!seal.equals(PurpleArchiveDiscard.seal(paths)), "discard confirmation fences exact malformed catalogue");
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata)) { PurpleArchiveRemoval.begin(paths).finish(null, environment.keys); }
        check(!new File(paths.accountsRoot.file("0-11"), freshStore).exists(), "explicit account discard erases refused archive");
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); PurpleCore.ensureLoaded(); File root = new File(args[1]); check(root.mkdir(), "exclusive test root");
        basic(root); readers(root); admission(root); schemaAdmission(root); removalCuts(root); usageAndGaps(root); ownedMedia(root); damagedBindingIsolation(root); missingCatalogue(root); unsafe(root);
        PurpleArchiveLoginCases.run(); loginAdmission(root);
        System.out.println("ARCHIVE_STORE_CHECKS=" + checks);
    }
}
