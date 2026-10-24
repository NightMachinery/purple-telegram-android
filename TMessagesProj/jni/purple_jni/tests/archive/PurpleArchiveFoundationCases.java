package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;

public final class PurpleArchiveFoundationCases {
    private static int checks;
    private static void check(boolean value, String message) { ++checks; if (!value) throw new AssertionError(message); }
    private static PurpleArchiveSqlite.Result done(PurpleArchiveSqlite db, String sql, PurpleArchiveSqlite.Value... binds) {
        PurpleArchiveSqlite.Result result = db.run(sql, binds); check(result.done(), "SQL completion: " + sql); return result;
    }
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; ++i) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    private static void sqlite(File root) throws Exception {
        String file = new File(root, "archive.db").getAbsolutePath();
        PurpleArchiveSqlite db = new PurpleArchiveSqlite(file, true);
        done(db, "PRAGMA foreign_keys=ON");
        check(done(db, "PRAGMA foreign_keys").values[0][0].integer == 1, "foreign keys read back");
        check(done(db, "PRAGMA journal_mode=WAL").values[0][0].text().equals("wal"), "WAL read back");
        done(db, "PRAGMA synchronous=FULL");
        check(done(db, "PRAGMA synchronous").values[0][0].integer == 2, "FULL read back");
        done(db, "CREATE TABLE value_rows(id INTEGER PRIMARY KEY,txt TEXT,payload BLOB,nothing_value BLOB)");
        String text = "literal\u0000中文\n";
        done(db, "BEGIN IMMEDIATE");
        PurpleArchiveSqlite.Result inserted = done(db, "INSERT INTO value_rows VALUES(?,?,?,?)",
            PurpleArchiveSqlite.Value.integer(Long.MAX_VALUE), PurpleArchiveSqlite.Value.text(text),
            PurpleArchiveSqlite.Value.blob(new byte[0]), PurpleArchiveSqlite.Value.nil());
        check(inserted.directChanges == 1 && inserted.totalChanges == 1, "direct and statement-local total changes");
        check(!db.autocommit(), "transaction active"); done(db, "COMMIT"); check(db.autocommit(), "commit acknowledged");
        PurpleArchiveSqlite.Result row = done(db, "SELECT * FROM value_rows");
        check(row.values[0][0].integer == Long.MAX_VALUE && row.values[0][1].text().equals(text), "wide ids and literal UTF8 NUL");
        check(row.values[0][2].type == PurpleArchiveSqlite.BLOB && row.values[0][2].bytes.length == 0
            && row.values[0][3].type == PurpleArchiveSqlite.NULL, "zero blob and NULL remain distinct");
        check(row.directChanges == 1 && row.totalChanges == 0 && row.counts(1, 1, 0, 0, true), "non-DML ignores stale direct changes");
        check(!db.run("SELECT 1; SELECT 2").done(), "multiple statements refused");
        check(!db.run("SELECT ?").done(), "missing bind refused");
        check(!db.run("SELECT * FROM absent_table").done(), "prepare error exposed");
        check(!db.run("SELECT 1.5").done(), "floating values never narrow into integer rows");
        done(db, "BEGIN IMMEDIATE");
        check(!db.run("INSERT INTO value_rows VALUES(?,?,?,?)", PurpleArchiveSqlite.Value.integer(Long.MAX_VALUE),
            PurpleArchiveSqlite.Value.text("duplicate"), PurpleArchiveSqlite.Value.nil(), PurpleArchiveSqlite.Value.nil()).done(), "step constraint error exposed");
        done(db, "ROLLBACK"); check(done(db, "SELECT COUNT(*) FROM value_rows").values[0][0].integer == 1, "failed write rolled back");
        PurpleArchiveSqlite.Result bounded = db.run("WITH RECURSIVE x(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM x WHERE n<130) SELECT n FROM x");
        check(!bounded.done() && bounded.values.length == 128, "row overflow refuses without unbounded materialization");
        check(!db.run("SELECT zeroblob(16777217)").done(), "byte overflow refuses");
        PurpleArchiveSqlite other = new PurpleArchiveSqlite(file, false);
        done(db, "BEGIN IMMEDIATE");
        check(!other.run("BEGIN IMMEDIATE").done(), "BUSY is a failure");
        done(db, "ROLLBACK"); other.close();
        done(db, "CREATE TABLE parent(id INTEGER PRIMARY KEY)");
        done(db, "CREATE TABLE child(id INTEGER PRIMARY KEY,p INTEGER REFERENCES parent(id) ON DELETE CASCADE)");
        done(db, "INSERT INTO parent VALUES(1)"); done(db, "INSERT INTO child VALUES(2,1)");
        row = done(db, "DELETE FROM parent WHERE id=1");
        check(row.directChanges == 1 && row.totalChanges == 2, "cascade total is statement-local and distinct");
        boolean[] refused = {false}; Thread thread = new Thread(() -> {
            try { db.run("SELECT 1"); } catch (IllegalStateException e) { refused[0] = true; }
        }); thread.start(); thread.join(); check(refused[0], "connection refuses other thread");
        db.close(); boolean closed = false;
        try { db.run("SELECT 1"); } catch (IllegalStateException e) { closed = true; }
        check(closed, "closed handle refuses");
        try (PurpleArchiveSqlite reopened = new PurpleArchiveSqlite(file, false)) {
            check(done(reopened, "SELECT COUNT(*) FROM value_rows").values[0][0].integer == 1, "committed rows survive reopen");
            check(done(reopened, "SELECT ?", PurpleArchiveSqlite.Value.integer(Long.MIN_VALUE)).values[0][0].integer == Long.MIN_VALUE,
                "signed int64 minimum survives");
        }
        File link = new File(root, "linked.db"); Files.createSymbolicLink(link.toPath(), new File(file).toPath());
        boolean refusedLink = false;
        try { new PurpleArchiveSqlite(link.getAbsolutePath(), false); } catch (IllegalStateException e) { refusedLink = true; }
        check(refusedLink, "symlink leaf refused");
    }
    private static void crypto() throws Exception {
        byte[] ikm = new byte[22]; Arrays.fill(ikm, (byte) 0x0b);
        check(Arrays.equals(PurpleArchiveCrypto.derive(ikm, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42),
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")), "RFC5869 vector");
        byte[] key = new byte[32], index = new byte[32]; Arrays.fill(index, (byte) 9);
        byte[] plain = "literal\u0000body\n".getBytes(StandardCharsets.UTF_8), aad = "account/store/kind/row".getBytes(StandardCharsets.UTF_8);
        PurpleArchiveCrypto crypto = new PurpleArchiveCrypto("data", key, index);
        for (int i = 0; i < 100; ++i) {
            byte[] nonce = crypto.random(12); byte[][] sealed = crypto.seal("data", nonce, plain, aad);
            check(sealed != null && Arrays.equals(crypto.open("data", nonce, sealed[0], sealed[1], aad), plain), "authenticated round trip");
            sealed[1][0] ^= 1; check(crypto.open("data", nonce, sealed[0], sealed[1], aad) == null, "tag failure exposes no bytes");
        }
        byte[] nonce = crypto.random(12); byte[][] sealed = crypto.seal("data", nonce, plain, aad);
        check(crypto.open("wrong", nonce, sealed[0], sealed[1], aad) == null, "wrong key identity refused");
        check(crypto.open("data", nonce, sealed[0], sealed[1], new byte[0]) == null, "wrong associated context refused");
        check(crypto.hmac(aad).length == 32, "full HMAC output");
        boolean[] refused = {false}; Thread thread = new Thread(() -> refused[0] = crypto.random(12) == null);
        thread.start(); thread.join(); check(refused[0], "crypto refuses different thread");
        crypto.close(); check(crypto.random(12) == null && crypto.hmac(aad) == null, "closed crypto drops execution capability");
        PurpleArchiveCrypto wrap = new PurpleArchiveCrypto("platform", new SecretKeySpec(key, "AES"));
        check(wrap.seal("platform", new byte[12], key, aad) == null, "wrapping needs generated-IV operation");
        nonce = wrap.random(12); byte[][] wrapped = wrap.seal("platform", nonce, key, aad);
        check(wrapped != null && Arrays.equals(wrap.open("platform", nonce, wrapped[0], wrapped[1], aad), key), "one generated-IV Cipher wraps and unwraps");
        check(wrap.seal("platform", nonce, key, aad) == null, "wrapping Cipher cannot replay"); wrap.close();
    }
    private interface Action { void run() throws Exception; }
    private static void refuses(Action action, String message) throws Exception {
        boolean refused = false; try { action.run(); } catch (Exception e) { refused = true; } check(refused, message);
    }
    private static void files(File root) throws Exception {
        PurpleArchiveFiles.Directory parent = new PurpleArchiveFiles.Directory(root, false);
        PurpleArchiveFiles.Directory directory = parent.child("metadata", true);
        PurpleArchiveFiles.Snapshot absent = directory.read("state.json", 128);
        PurpleArchiveFiles.Snapshot first = directory.publish("state.json", absent, new byte[]{1, 2, 3});
        check(Arrays.equals(first.bytes, new byte[]{1, 2, 3}), "descriptor publication readback");
        refuses(() -> directory.publish("state.json", absent, new byte[]{9}), "stale absent publication refuses");
        directory.publish("state.json", first, new byte[]{4});
        refuses(() -> directory.publish("state.json", first, new byte[]{5}), "stale inode publication refuses");
        refuses(() -> directory.read("state.json", 0), "metadata bound refuses oversized input");
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(directory)) {
            refuses(() -> new PurpleArchiveFiles.Lock(directory), "same-process lock collision refused");
        }
        try (PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(directory)) { check(true, "lock released for next owner"); }
        for (int i = 0; i < 301; ++i) directory.publish("entry-" + i, directory.read("entry-" + i, 1), new byte[0]);
        int count = 0, pages = 0;
        try (PurpleArchiveFiles.Listing listing = directory.listing()) {
            for (;;) { String[] page = listing.next(); check(page.length <= 128, "bounded native directory page"); if (page.length == 0) break; count += page.length; ++pages; }
        }
        check(count == 303 && pages >= 3, "directory iterator streams all entries including lock");
        File outside = new File(root, "outside"); Files.write(outside.toPath(), new byte[]{7});
        Files.createSymbolicLink(directory.file("link").toPath(), outside.toPath());
        refuses(() -> directory.read("link", 128), "metadata symlink refused");
        refuses(() -> directory.remove("link", false), "unlink refuses symlink rather than following");
        Files.delete(directory.file("link").toPath());
        Files.createLink(directory.file("hardlink").toPath(), outside.toPath());
        refuses(() -> directory.read("hardlink", 128), "metadata hardlink refused");
        Files.delete(directory.file("hardlink").toPath());
        File moved = new File(root, "metadata-old"); check(directory.file.renameTo(moved), "replace owned directory fixture");
        PurpleArchiveFiles.Directory replacement = parent.child("metadata", true);
        replacement.publish("state.json", replacement.read("state.json", 128), new byte[]{8});
        refuses(() -> directory.read("state.json", 128), "old directory identity refuses replacement");
        check(replacement.read("state.json", 128).bytes[0] == 8, "replacement bytes untouched");
    }
    private static void searchOnlyAncestors(File root) throws Exception {
        File ancestor = new File(root, "search-only"), application = new File(ancestor, "package"), files = new File(application, "files");
        check(ancestor.mkdir() && application.mkdir() && files.mkdir(), "search-only platform ancestor fixture");
        Files.setPosixFilePermissions(ancestor.toPath(), java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        try {
            PurpleArchiveFiles.Directory directory = new PurpleArchiveFiles.Directory(files, false).child("owned", true);
            directory.publish("state.json", directory.read("state.json", 32), new byte[]{4, 2});
            check(Arrays.equals(directory.read("state.json", 32).bytes, new byte[]{4, 2}),
                "native file walk traverses Android-style search-only ancestors without directory read permission");
            try (PurpleArchiveSqlite database = new PurpleArchiveSqlite(directory.file("archive.sqlite").getAbsolutePath(), true)) {
                done(database, "CREATE TABLE facts(id INTEGER)"); done(database, "INSERT INTO facts VALUES(42)");
                check(done(database, "SELECT id FROM facts").values[0][0].integer == 42, "SQLite shim opens through search-only platform ancestors");
            }
        } finally {
            Files.setPosixFilePermissions(ancestor.toPath(), java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE, java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        }
    }
    private static void movedConnection(File root) throws Exception {
        File directory = new File(root, "moved-db"); check(directory.mkdir(), "database parent");
        PurpleArchiveSqlite database = new PurpleArchiveSqlite(new File(directory, "archive.sqlite").getAbsolutePath(), true);
        done(database, "PRAGMA journal_mode=WAL"); done(database, "CREATE TABLE facts(id INTEGER)");
        done(database, "INSERT INTO facts VALUES(1)");
        File moved = new File(root, "moved-db-old"); check(directory.renameTo(moved), "move database parent"); check(directory.mkdir(), "replacement database parent");
        File replacement = new File(directory, "archive.sqlite-wal"); Files.write(replacement.toPath(), new byte[]{9, 8, 7});
        refuses(() -> database.run("INSERT INTO facts VALUES(2)"), "stale database parent refuses SQL");
        database.close();
        check(Arrays.equals(Files.readAllBytes(replacement.toPath()), new byte[]{9, 8, 7}), "stale close does not checkpoint or unlink replacement WAL");
        File original = new File(moved, "archive.sqlite"); byte[] before = Files.readAllBytes(original.toPath());
        try (PurpleArchiveSqlite inspection = new PurpleArchiveSqlite(original.getAbsolutePath(), false, true)) {
            check(done(inspection, "SELECT * FROM facts").rows == 1, "read-only inspection sees acknowledged WAL");
            check(!inspection.run("CREATE TABLE forbidden(id INTEGER)").done(), "read-only handle refuses DDL");
        }
        check(Arrays.equals(before, Files.readAllBytes(original.toPath())), "inspection and close preserve primary bytes");
    }
    private static void sidecars(File root) throws Exception {
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            File folder = new File(root, "unsafe" + suffix); check(folder.mkdir(), "sidecar fixture directory");
            File file = new File(folder, "archive.sqlite");
            try (PurpleArchiveSqlite db = new PurpleArchiveSqlite(file.getAbsolutePath(), true)) { done(db, "CREATE TABLE facts(id INTEGER)"); }
            byte[] primary = Files.readAllBytes(file.toPath());
            File outside = new File(folder, "outside"); Files.write(outside.toPath(), new byte[]{4, 5, 6});
            Files.setPosixFilePermissions(outside.toPath(), java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            File sidecar = new File(file.getAbsolutePath() + suffix);
            Files.createSymbolicLink(sidecar.toPath(), outside.toPath());
            refuses(() -> new PurpleArchiveSqlite(file.getAbsolutePath(), false), "sidecar symlink refused before SQLite recovery " + suffix);
            Files.delete(sidecar.toPath()); Files.createLink(sidecar.toPath(), outside.toPath());
            refuses(() -> new PurpleArchiveSqlite(file.getAbsolutePath(), false), "sidecar hardlink refused before SQLite recovery " + suffix);
            Files.delete(sidecar.toPath());
            check(Arrays.equals(primary, Files.readAllBytes(file.toPath())) && Arrays.equals(new byte[]{4, 5, 6}, Files.readAllBytes(outside.toPath())), "unsafe sidecar refuses without changing primary or outside bytes");
        }
        File directory = new File(root, "active-db"); check(directory.mkdir(), "active database directory");
        PurpleArchiveSqlite db = new PurpleArchiveSqlite(new File(directory, "archive.sqlite").getAbsolutePath(), true);
        done(db, "CREATE TABLE facts(id INTEGER)"); done(db, "BEGIN IMMEDIATE"); done(db, "INSERT INTO facts VALUES(1)");
        File moved = new File(root, "active-db-old"); check(directory.renameTo(moved) && directory.mkdir(), "active journal parent replacement");
        File journal = new File(directory, "archive.sqlite-journal"); Files.write(journal.toPath(), new byte[]{7, 8});
        refuses(db::close, "active transaction close never performs implicit path rollback");
        check(Arrays.equals(new byte[]{7, 8}, Files.readAllBytes(journal.toPath())), "refused active close preserves replacement journal");
        File replacement = new File(root, "active-db-replacement"); check(directory.renameTo(replacement) && moved.renameTo(directory), "restore original parent for checked rollback");
        done(db, "ROLLBACK"); db.close();
    }
    private static void faults(File root) throws Exception {
        PurpleArchiveSqlite database = new PurpleArchiveSqlite(new File(root, "faults.sqlite").getAbsolutePath(), true);
        done(database, "CREATE TABLE facts(id INTEGER)");
        PurpleArchiveFaults.set(1); check(!database.run("SELECT 1").done(), "prepare failure exposed");
        PurpleArchiveFaults.set(2); check(!database.run("SELECT ?", PurpleArchiveSqlite.Value.integer(1)).done(), "bind failure exposed");
        PurpleArchiveFaults.set(5); check(!database.run("SELECT 1").done(), "finalize failure exposed");
        PurpleArchiveFaults.set(7); check(!database.run("SELECT 1").done(), "reset failure exposed");
        done(database, "BEGIN IMMEDIATE"); done(database, "INSERT INTO facts VALUES(1)");
        PurpleArchiveFaults.set(3); check(!database.run("COMMIT").done() && !database.autocommit(), "failed durable commit never acknowledged");
        done(database, "ROLLBACK"); check(done(database, "SELECT * FROM facts").rows == 0, "failed commit rolled back real SQLite bytes");
        done(database, "BEGIN IMMEDIATE"); PurpleArchiveFaults.set(4);
        check(!database.run("INSERT INTO facts VALUES(2)").done(), "disk-full step exposed");
        done(database, "ROLLBACK");
        PurpleArchiveFaults.set(6); refuses(database::close, "BUSY close refused and handle retained");
        check(done(database, "SELECT * FROM facts").rows == 0, "retained handle remains owned after close failure"); database.close();
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); File root = new File(args[1]); if (!root.mkdir()) throw new AssertionError("exclusive fixture");
        sqlite(root); crypto(); files(root); searchOnlyAncestors(root); movedConnection(root); sidecars(root); faults(root); System.out.println("ARCHIVE_FOUNDATION_CHECKS=" + checks);
    }
}
