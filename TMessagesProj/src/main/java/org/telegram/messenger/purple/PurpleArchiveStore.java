package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleArchiveStore implements AutoCloseable {
    interface Environment {
        File files();
        int api();
        boolean current();
        Object publicationLock();
        PurpleArchiveKeys.Provider keys();
    }
    static final class Failure extends Exception {
        final String reason;
        Failure(String reason) { super(reason); this.reason = reason; }
    }
    final Environment environment;
    final PurpleArchivePaths paths;
    final PurpleArchiveBinding binding;
    final PurpleArchiveFiles.Directory directory;
    private final Thread owner = Thread.currentThread();
    private PurpleArchiveFiles.Lock lock;
    private PurpleArchiveSqlite database;
    private PurpleArchiveCrypto crypto;
    private volatile boolean closed;
    private int readers;
    private final java.util.HashSet<Reader> activeReaders = new java.util.HashSet<>();
    private final boolean creationAllowed;
    private PurpleArchiveStore(Environment environment, PurpleArchivePaths paths, PurpleArchiveBinding binding,
        PurpleArchiveFiles.Lock lock, boolean creationAllowed) {
        this.environment = environment; this.paths = paths; this.binding = binding; this.lock = lock; this.creationAllowed = creationAllowed;
        directory = paths.store(binding.store, creationAllowed);
    }
    static PurpleArchiveStore open(Environment environment, String account) throws Exception { return open(environment, account, null); }
    static PurpleArchiveStore open(Environment environment, String account, String loginNonce) throws Exception {
        if (environment.api() < 23) throw new Failure("Requires Android 6 (API 23)");
        if (!environment.current()) throw new Failure("Account changed");
        PurpleArchivePaths paths = new PurpleArchivePaths(environment.files(), account);
        PurpleArchiveFiles.Lock lock = new PurpleArchiveFiles.Lock(paths.metadata);
        PurpleArchiveStore store = null;
        try {
            PurpleArchiveBinding binding;
            synchronized (environment.publicationLock()) {
                if (!environment.current()) throw new Failure("Account changed");
                if (paths.metadata.read("removing.json", 65536).bytes != null) throw new Failure("Archive removal pending");
                if (loginNonce != null) PurpleArchiveLogin.admit(paths, loginNonce, environment.keys());
                PurpleArchiveCatalogue.requireAdmission(paths, environment.keys());
                PurpleArchiveFiles.Snapshot bound = paths.metadata.read("binding.json", 65536);
                PurpleArchiveFiles.Snapshot creating = paths.metadata.read("creating.json", 65536);
                PurpleArchiveFiles.Snapshot fence = paths.metadata.read("fence.json", 65536);
                if (bound.bytes != null) {
                    binding = new PurpleArchiveBinding(account, PurpleArchiveBinding.json(bound.bytes));
                    if (creating.bytes != null) {
                        JSONObject intent = PurpleArchiveBinding.json(creating.bytes);
                        if (!same(binding, new PurpleArchiveBinding(account, intent.getJSONObject("binding"))))
                            throw new Failure("Conflicting archive creation");
                    }
                    if (!matches(binding, fence.bytes)) throw new Failure("Archive generation changed");
                } else {
                    JSONObject intent;
                    if (creating.bytes != null) {
                        intent = PurpleArchiveBinding.json(creating.bytes);
                        binding = new PurpleArchiveBinding(account, intent.getJSONObject("binding"));
                    } else {
                        String generation = fence.bytes == null ? "0" : new PurpleArchiveBinding(account, PurpleArchiveBinding.json(fence.bytes)).generation;
                        binding = PurpleArchiveBinding.create(account, PurpleArchiveBinding.next(generation));
                        intent = new JSONObject().put("binding", binding.value).put("previousFence",
                            fence.bytes == null ? JSONObject.NULL : PurpleArchiveCore.base64(fence.bytes));
                        paths.metadata.publish("creating.json", creating, PurpleArchiveBinding.canonical(intent.toString().getBytes(StandardCharsets.UTF_8)));
                    }
                    if (!matches(binding, fence.bytes)) {
                        byte[] previous = intent.isNull("previousFence") ? null : PurpleArchiveCore.unbase64(intent.getString("previousFence"));
                        if (!Arrays.equals(previous, fence.bytes)) throw new Failure("Archive creation fence changed");
                        paths.metadata.publish("fence.json", fence, binding.bytes());
                    }
                }
                store = new PurpleArchiveStore(environment, paths, binding, lock, bound.bytes == null);
            }
            store.unlockAndOpen();
            synchronized (environment.publicationLock()) {
                store.requireCurrent();
                if (loginNonce != null) PurpleArchiveLogin.bind(store, loginNonce);
                PurpleArchiveFiles.Snapshot bound = paths.metadata.read("binding.json", 65536);
                if (bound.bytes == null) paths.metadata.publish("binding.json", bound, store.binding.bytes());
                else if (!matches(store.binding, bound.bytes)) throw new Failure("Archive binding changed");
                paths.metadata.remove("creating.json", false);
            }
            return store;
        } catch (Exception | LinkageError e) {
            if (store != null) store.close(); else lock.close();
            throw e;
        }
    }
    private void unlockAndOpen() throws Exception {
        requireCurrent();
        boolean creating = creationAllowed && paths.metadata.read("creating.json", 65536).bytes != null;
        PurpleArchiveFiles.Mark databaseMark = PurpleArchiveFiles.mark(directory.file("archive.sqlite"), false);
        boolean initialize = databaseMark == null;
        if (databaseMark != null) {
            try (PurpleArchiveSqlite inspection = new PurpleArchiveSqlite(directory.file("archive.sqlite").getAbsolutePath(), false, true)) {
                initialize = admit(inspection, creating);
            }
        }
        PurpleArchiveFiles.Snapshot wrapper = directory.read("wrapped.key", 65536);
        if (wrapper.bytes != null) {
            JSONObject inspected = PurpleArchiveCore.call("keyInspect", arguments().put("platformKeyId", binding.alias())
                .put("frame", PurpleArchiveCore.base64(wrapper.bytes)), null);
            if (inspected.getString("status").equals("ReadOnly")) throw new Failure("Unsupported archive envelope schema (read only)");
            if (!inspected.getString("status").equals("Ready")) throw new Failure("Archive wrapping key frame invalid");
        }
        SecretKey platform = environment.keys().find(binding.alias());
        int action = PurpleArchiveCore.call("keyOpen", arguments().put("archiveExists", databaseMark != null)
            .put("wrappedKeyExists", wrapper.bytes != null).put("platformKeyAvailable", platform != null)
            .put("removalPending", false).put("storageVersion", "1").put("envelopeVersion", "1")
            .put("api", environment.api()).put("wrappingAvailable", true), null).getInt("action");
        if (action != 0 && action != 1 && !(action == 2 && creating && databaseMark == null && wrapper.bytes != null && platform != null))
            throw new Failure("Archive key unavailable; existing data preserved");
        if (wrapper.bytes == null) {
            if (!creating || databaseMark != null) throw new Failure("Archive wrapping key missing");
            if (platform == null) platform = environment.keys().create(binding.alias());
            requireCurrent();
            byte[] raw = new byte[32]; new SecureRandom().nextBytes(raw);
            try (PurpleArchiveCrypto wrapping = new PurpleArchiveCrypto(binding.alias(), platform)) {
                byte[] encoded = PurpleArchiveCore.envelopeNative(binding.account, binding.store, binding.key, binding.alias(), raw, true, wrapping);
                if (encoded == null) throw new Failure("Archive key wrapping unavailable");
                synchronized (environment.publicationLock()) {
                    requireCurrent(); wrapper = directory.publish("wrapped.key", wrapper, encoded);
                }
                byte[] verified = PurpleArchiveCore.envelopeNative(binding.account, binding.store, binding.key, binding.alias(), wrapper.bytes, false, wrapping);
                try {
                    if (!Arrays.equals(raw, verified)) throw new Failure("Archive wrapped key verification failed");
                } finally { if (verified != null) Arrays.fill(verified, (byte) 0); }
            } finally { Arrays.fill(raw, (byte) 0); }
        }
        if (platform == null) throw new Failure("Archive key unavailable; existing data preserved");
        byte[] raw;
        try (PurpleArchiveCrypto wrapping = new PurpleArchiveCrypto(binding.alias(), platform)) {
            raw = PurpleArchiveCore.envelopeNative(binding.account, binding.store, binding.key, binding.alias(), wrapper.bytes, false, wrapping);
        }
        if (raw == null || raw.length != 32) throw new Failure("Archive key cannot be authenticated");
        byte[] index = null;
        try {
            JSONObject context = PurpleArchiveCore.call("derive", arguments(), null);
            if (!context.getString("status").equals("Ready")) throw new Failure("Archive index derivation refused");
            index = PurpleArchiveCrypto.derive(raw, PurpleArchiveCore.unbase64(context.getString("salt")),
                PurpleArchiveCore.unbase64(context.getString("info")), context.getInt("length"));
            crypto = new PurpleArchiveCrypto(binding.key, raw, index);
        } finally { Arrays.fill(raw, (byte) 0); if (index != null) Arrays.fill(index, (byte) 0); }
        requireCurrent();
        if (!initialize) {
            try (PurpleArchiveSqlite inspection = new PurpleArchiveSqlite(directory.file("archive.sqlite").getAbsolutePath(), false, true)) {
                verifyHeader(inspection);
            }
        } else if (!creating) throw new Failure("Existing archive database missing");
        synchronized (environment.publicationLock()) {
            requireCurrent();
            database = new PurpleArchiveSqlite(directory.file("archive.sqlite").getAbsolutePath(), databaseMark == null);
        }
        if (!initialize) admit(database, false);
        JSONArray setup = PurpleArchiveCore.call("connection", arguments(), crypto).getJSONArray("setup");
        for (int i = 0; i < setup.length(); ++i) {
            synchronized (environment.publicationLock()) {
                requireCurrent(); PurpleArchiveSqlite.Result result = database.run(setup.getString(i));
                if (!result.counts(i == 1 ? 1 : 0, i == 1 ? 1 : 0, 0, 0, true)) throw new Failure("Archive connection setup failed");
                if (i == 1 && !result.values[0][0].text().equalsIgnoreCase("wal")) throw new Failure("Archive WAL unavailable");
            }
        }
        requireInteger(query(database, "foreignKeys"), 1);
        requireInteger(query(database, "synchronous"), 2);
        if (!query(database, "journalMode").values[0][0].text().equalsIgnoreCase("wal")) throw new Failure("Archive WAL readback failed");
        if (initialize) {
            PurpleArchiveCore.Program plan = new PurpleArchiveCore.Program(PurpleArchiveCore.call("initialize", arguments(), crypto));
            if (!execute(plan).committed) throw new Failure("Archive initialization not committed");
        }
        admit(database, false);
        verifyHeader(database);
        directory.child("blobs", true); directory.child("staging", true);
    }
    private boolean admit(PurpleArchiveSqlite db, boolean allowEmpty) throws Exception {
        boolean ownTransaction = db.autocommit();
        if (ownTransaction) PurpleArchiveCore.requireDone(db.run("BEGIN"));
        try {
            if (allowEmpty && query(db, "tables").rows == 0) {
                if (ownTransaction) PurpleArchiveCore.requireDone(db.run("COMMIT")); return true;
            }
            PurpleArchiveSqlite.Result versionRow = query(db, "schemaVersion");
            if (versionRow.values[0][0].type != PurpleArchiveSqlite.INTEGER) throw new Failure("Archive schema unreadable");
            String version = Long.toString(versionRow.values[0][0].integer);
            String preflight = PurpleArchiveCore.call("schema", arguments().put("version", version), null).getString("status");
            if (!preflight.equals("Ready")) throw new Failure("Archive schema " + preflight);
            String fingerprint = query(db, "schemaFingerprint").values[0][0].text();
            PurpleArchiveSqlite.Result actual = query(db, "schemaDefinitions");
            JSONArray definitions = new JSONArray();
            for (PurpleArchiveSqlite.Value[] row : actual.values) {
                if (row.length != 1) throw new Failure("Archive schema row shape");
                definitions.put(row[0].text());
            }
            String admission = PurpleArchiveCore.call("admit", arguments().put("version", version)
                .put("fingerprint", fingerprint).put("definitions", definitions), null).getString("status");
            if (!admission.equals("Ready")) throw new Failure("Archive schema " + admission);
            if (ownTransaction) PurpleArchiveCore.requireDone(db.run("COMMIT")); return false;
        } catch (Exception | LinkageError e) {
            if (ownTransaction && !db.autocommit()) PurpleArchiveCore.requireDone(db.run("ROLLBACK"));
            throw e;
        }
    }
    private void verifyHeader(PurpleArchiveSqlite db) throws Exception {
        boolean ownTransaction = db.autocommit();
        if (ownTransaction) PurpleArchiveCore.requireDone(db.run("BEGIN"));
        try {
            JSONObject query = queryPlan("metadata");
            PurpleArchiveSqlite.Result metadata = runQuery(db, query);
            PurpleArchiveSqlite.Value[] values = metadata.values[0];
            if (values[0].type != PurpleArchiveSqlite.INTEGER || !PurpleArchiveCore.call("schema", arguments().put("version", Long.toString(values[0].integer)), null).getString("status").equals("Ready")
                || !values[1].text().equals(query.getString("accountToken")) || query.getString("accountToken").isEmpty()
                || !values[2].text().equals(binding.store)) throw new Failure("Archive metadata mismatch");
            if (!values[5].text().isEmpty()) throw new Failure("Archive maintenance pending");
            PurpleArchiveSqlite.Value[] header = query(db, "header").values[0];
            JSONObject opened = PurpleArchiveCore.call("openRow", arguments().put("row", new JSONObject().put("kind", 1)
                .put("id", header[0].text()).put("position", Long.toString(header[1].integer)).put("frame", PurpleArchiveCore.base64(header[2].bytes))), crypto);
            if (!opened.getString("status").equals("Ready") || !values[3].text().equals(opened.getString("revision"))
                || !values[4].text().equals(opened.getString("accountGeneration"))) throw new Failure("Archive header mismatch");
            if (ownTransaction) PurpleArchiveCore.requireDone(db.run("COMMIT"));
        } catch (Exception | LinkageError e) {
            if (ownTransaction && !db.autocommit()) PurpleArchiveCore.requireDone(db.run("ROLLBACK"));
            throw e;
        }
    }
    PurpleArchiveCore.Execution execute(PurpleArchiveCore.Program plan) {
        checkOwner();
        return execute(plan, () -> true);
    }
    PurpleArchiveCore.Execution execute(PurpleArchiveCore.Program plan, BooleanSupplier active) {
        checkOwner();
        PurpleArchiveCore.Execution result = PurpleArchiveCore.execute(database, plan, () -> current() && active.getAsBoolean(), environment.publicationLock());
        if (result.committed) PurpleArchiveMediaAccess.committed(this);
        if (result.committed) PurpleArchiveDeletedModel.committed(this);
        if (result.committed) PurpleArchiveViewerModel.committed(this);
        return result;
    }
    boolean hasGaps() throws Exception { requireCurrent(); return query(database, "gaps").rows != 0; }
    PurpleArchiveCrypto crypto() { checkOwner(); return crypto; }
    JSONObject arguments() throws Exception { requireCurrent(); return binding.arguments().put("workGeneration", binding.generation); }
    private JSONObject queryPlan(String name) throws Exception {
        JSONObject result = PurpleArchiveCore.call("query", arguments().put("query", name), crypto);
        if (!result.getString("status").equals("Ready")) throw new Failure("Archive query refused");
        return result;
    }
    private PurpleArchiveSqlite.Result query(PurpleArchiveSqlite db, String name) throws Exception { return runQuery(db, queryPlan(name)); }
    private static PurpleArchiveSqlite.Result runQuery(PurpleArchiveSqlite db, JSONObject query) throws Exception {
        PurpleArchiveCore.Statement statement = new PurpleArchiveCore.Statement(query.getJSONObject("statement"));
        PurpleArchiveSqlite.Result result = db.run(statement.sql, statement.binds);
        if (!statement.accepts(result)) throw new Failure("Archive query incomplete");
        return result;
    }
    private static void requireInteger(PurpleArchiveSqlite.Result result, long expected) throws Failure {
        if (result.rows != 1 || result.values[0].length != 1 || result.values[0][0].type != PurpleArchiveSqlite.INTEGER
            || result.values[0][0].integer != expected) throw new Failure("Archive connection readback failed");
    }
    static boolean same(PurpleArchiveBinding a, PurpleArchiveBinding b) {
        return a.account.equals(b.account) && a.store.equals(b.store) && a.key.equals(b.key) && a.generation.equals(b.generation);
    }
    static boolean matches(PurpleArchiveBinding binding, byte[] bytes) throws Exception {
        return bytes != null && same(binding, new PurpleArchiveBinding(binding.account, PurpleArchiveBinding.json(bytes)));
    }
    boolean current() {
        if (closed || !environment.current()) return false;
        try {
            paths.check(); directory.check();
            if (paths.metadata.read("removing.json", 65536).bytes != null || paths.metadata.read("recovery.json", 65536).bytes != null) return false;
            byte[] bound = paths.metadata.read("binding.json", 65536).bytes;
            if (bound == null) {
                byte[] creating = paths.metadata.read("creating.json", 65536).bytes;
                if (!creationAllowed || creating == null || !same(binding,
                    new PurpleArchiveBinding(binding.account, PurpleArchiveBinding.json(creating).getJSONObject("binding")))) return false;
            } else if (!matches(binding, bound)) return false;
            return matches(binding, paths.metadata.read("fence.json", 65536).bytes);
        } catch (Exception | LinkageError e) { return false; }
    }
    void requireCurrent() throws Failure { checkOwner(); if (!current()) throw new Failure("Archive work revoked"); }
    private void checkOwner() {
        if (Thread.currentThread() != owner || closed) throw new IllegalStateException("Archive queue ownership");
    }
    PurpleArchiveSqlite.Result readStatement(PurpleArchiveCore.Statement statement) throws Exception {
        requireCurrent();
        synchronized (environment.publicationLock()) {
            requireCurrent();
            PurpleArchiveSqlite.Result result = database.run(statement.sql, statement.binds);
            if (!statement.accepts(result)) throw new Failure("Archive query incomplete");
            requireCurrent(); return result;
        }
    }
    boolean admissionCurrent(String generation) {
        try {
            requireCurrent();
            boolean transaction = database.autocommit();
            if (transaction) PurpleArchiveCore.requireDone(database.run("BEGIN"));
            try {
                admit(database, false); verifyHeader(database);
                PurpleArchiveSqlite.Value[] now = query(database, "metadata").values[0];
                boolean matches = generation.equals(now[4].text()) && now[5].text().isEmpty();
                if (transaction) PurpleArchiveCore.requireDone(database.run("COMMIT"));
                return matches;
            } catch (Exception | LinkageError e) {
                if (transaction && !database.autocommit()) PurpleArchiveCore.requireDone(database.run("ROLLBACK"));
                throw e;
            }
        } catch (Exception | LinkageError e) { return false; }
    }
    final class Reader implements AutoCloseable {
        private volatile boolean released;
        private boolean closing;
        private PurpleArchiveSqlite snapshot;
        private String revision, generation;
        private final boolean retained;
        private Reader() throws Exception { this(false); }
        private Reader(boolean retained) throws Exception {
            this.retained = retained;
            if (activeReaders.size() >= 8) throw new Failure("Archive reader limit");
            activeReaders.add(this); ++readers;
            try {
                snapshot = new PurpleArchiveSqlite(directory.file("archive.sqlite").getAbsolutePath(), false, true);
                PurpleArchiveCore.requireDone(snapshot.run("BEGIN"));
                admit(snapshot, false); verifyHeader(snapshot);
                PurpleArchiveSqlite.Value[] facts = query(snapshot, "metadata").values[0];
                revision = facts[3].text(); generation = facts[4].text();
            } catch (Exception | LinkageError e) { close(); throw e; }
        }
        boolean current() {
            if (released || closing || Thread.currentThread() != owner || !PurpleArchiveStore.this.current() || database == null || !database.autocommit()) return false;
            try {
                if (retained) return admissionCurrent(generation);
                PurpleArchiveSqlite.Value[] now = query(database, "metadata").values[0];
                return now[3].text().equals(revision) && now[4].text().equals(generation) && now[5].text().isEmpty();
            } catch (Exception | LinkageError e) { return false; }
        }
        void advanceLease(JSONObject transition) {
            if (released || Thread.currentThread() != owner || !PurpleArchiveStore.this.current()
                || database == null || !database.autocommit()) return;
            try {
                if (!transition.getBoolean("leaseOnly") || !transition.getString("from").equals(revision)) return;
                String to = transition.getString("to");
                if (!PurpleArchiveBinding.next(revision).equals(to)) return;
                PurpleArchiveSqlite.Value[] now = query(database, "metadata").values[0];
                if (now[3].text().equals(to) && now[4].text().equals(generation) && now[5].text().isEmpty()) revision = to;
            } catch (Exception | LinkageError e) { }
        }
        PurpleArchiveSqlite.Result read(PurpleArchiveCore.Statement statement) throws Exception {
            checkOwner();
            if (!current()) throw new Failure("Archive reader revoked");
            PurpleArchiveSqlite.Result result = snapshot.run(statement.sql, statement.binds);
            if (!statement.accepts(result) || !current()) throw new Failure("Archive reader page refused");
            return result;
        }
        boolean changed() throws Exception { return !query(database, "metadata").values[0][3].text().equals(revision); }
        boolean released() { return released; }
        @Override public void close() {
            if (released) return;
            checkOwner(); closing = true;
            if (snapshot != null) {
                if (!snapshot.autocommit()) PurpleArchiveCore.requireDone(snapshot.run("COMMIT"));
                snapshot.close(); snapshot = null;
            }
            released = true; --readers; activeReaders.remove(this);
        }
    }
    boolean closed() { return closed; }
    void retryClosingReaders() { checkOwner(); for (Reader reader : new java.util.ArrayList<>(activeReaders)) if (reader.closing) reader.close(); }
    Reader reader() throws Exception { requireCurrent(); return new Reader(); }
    Reader retainedReader() throws Exception { requireCurrent(); return new Reader(true); }
    void drainReaders() {
        checkOwner();
        for (Reader reader : new java.util.ArrayList<>(activeReaders)) reader.close();
        if (readers != 0 || !activeReaders.isEmpty()) throw new IllegalStateException("Archive readers not drained");
    }
    void closeDatabase() {
        checkOwner();
        try { PurpleArchiveStagingRecovery.close(this); }
        catch (Exception | LinkageError e) { throw new IllegalStateException("Archive staging recovery not closed", e); }
        if (readers != 0) throw new IllegalStateException("Archive readers still active");
        if (database != null) { database.close(); database = null; }
        if (crypto != null) {
            try { PurpleArchiveCore.call("stagingCloseAll", binding.arguments().put("workGeneration", binding.generation), crypto); }
            catch (Exception | LinkageError e) { throw new IllegalStateException("Archive native finalizers not closed", e); }
            crypto.close(); crypto = null;
        }
    }
    @Override public void close() {
        if (closed) return;
        closeDatabase();
        if (lock != null) { lock.close(); lock = null; }
        closed = true; PurpleArchiveMediaAccess.closed(this);
    }
}
