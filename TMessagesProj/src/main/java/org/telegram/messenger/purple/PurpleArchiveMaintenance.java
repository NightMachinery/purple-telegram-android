package org.telegram.messenger.purple;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleArchiveMaintenance {
    private static final IdentityHashMap<PurpleArchiveStore, Runner> runners = new IdentityHashMap<>();
    static JSONObject call(PurpleArchiveStore store, String operation, JSONObject arguments) throws Exception {
        JSONObject result = PurpleArchiveCore.call(operation, arguments, store.crypto());
        if (!result.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive maintenance plan refused");
        return result;
    }
    static JSONObject row(int kind, String id, PurpleArchiveSqlite.Value frame) throws Exception { return PurpleArchiveQueries.row(kind, id, frame); }
    static JSONObject query(PurpleArchiveStore store, String name) throws Exception { return call(store, "maintenanceQuery", store.arguments().put("query", name)); }
    static PurpleArchiveSqlite.Result read(PurpleArchiveStore.MaintenanceRead snapshot, JSONObject plan, int columns) throws Exception {
        PurpleArchiveSqlite.Result result = snapshot.read(new PurpleArchiveCore.Statement(plan.getJSONObject("statement")));
        if (result.rows != result.values.length) throw new PurpleArchiveStore.Failure("Archive maintenance row count");
        for (PurpleArchiveSqlite.Value[] values : result.values) if (values.length != columns) throw new PurpleArchiveStore.Failure("Archive maintenance projection shape");
        return result;
    }
    static JSONObject decode(PurpleArchiveStore store, JSONObject row) throws Exception {
        return call(store, "maintenanceRow", store.arguments().put("row", row));
    }
    static JSONObject epochs(PurpleArchiveStore store, PurpleArchiveStore.MaintenanceRead snapshot, JSONObject arguments, JSONObject scope) throws Exception {
        JSONArray epochs = new JSONArray();
        if (!scope.isNull("chat")) {
            ArrayList<JSONObject> chats = new ArrayList<>(); chats.add(scope.getJSONObject("chat"));
            JSONArray aliases = scope.getJSONArray("aliases"); for (int i = 0; i < aliases.length(); ++i) chats.add(aliases.getJSONObject(i));
            for (JSONObject chat : chats) {
                JSONObject plan = call(store, "maintenanceQuery", store.arguments().put("query", "epoch").put("chat", chat));
                PurpleArchiveSqlite.Result rows = read(snapshot, plan, 2);
                if (rows.rows > 1) throw new PurpleArchiveStore.Failure("Archive maintenance epoch count");
                JSONObject value = new JSONObject().put("chat", chat).put("generation", rows.rows == 0 ? "0" : rows.values[0][0].text())
                    .put("row", rows.rows == 0 ? JSONObject.NULL : row(8, plan.getString("id"), rows.values[0][1]));
                epochs.put(value);
            }
        }
        return arguments.put("epochs", epochs);
    }
    static void copies(PurpleArchiveStore store, PurpleArchiveStore.MaintenanceRead snapshot, JSONObject target, JSONArray output) throws Exception {
        JSONArray refs = decode(store, target).getJSONArray("refs");
        if (refs.length() > 128) throw new PurpleArchiveStore.Failure("Archive maintenance attachment limit");
        for (int i = 0; i < refs.length(); ++i) {
            JSONObject ref = refs.getJSONObject(i);
            JSONObject plan = call(store, "maintenanceQuery", store.arguments().put("query", "copies").put("identity", ref.getString("identity")).put("mediaKind", ref.getString("kind")));
            PurpleArchiveSqlite.Result matches = read(snapshot, plan, 5);
            if (matches.rows > 1) throw new PurpleArchiveStore.Failure("Archive maintenance copy count");
            if (matches.rows == 0) continue;
            PurpleArchiveSqlite.Value[] values = matches.values[0];
            if (values[4].type != PurpleArchiveSqlite.INTEGER || values[4].integer < 0) throw new PurpleArchiveStore.Failure("Archive maintenance copy position");
            output.put(new JSONObject().put("identity", ref.getString("identity")).put("kind", ref.getString("kind"))
                .put("blob", row(5, values[0].text(), values[1])).put("owner", row(3, values[2].text(), values[3])).put("position", Long.toString(values[4].integer)));
        }
    }
    static JSONArray page(PurpleArchiveStore store, PurpleArchiveStore.MaintenanceRead snapshot) throws Exception {
        int phase = snapshot.inspection.getInt("phase"), kind = snapshot.inspection.getInt("kind");
        JSONArray output = new JSONArray(); if (phase == 4) return output;
        JSONObject query = call(store, "maintenanceQuery", snapshot.arguments().put("query", "page"));
        PurpleArchiveSqlite.Result rows = read(snapshot, query, 2); long evidenceBytes = 0;
        if (rows.rows > 128) throw new PurpleArchiveStore.Failure("Archive maintenance page limit");
        for (PurpleArchiveSqlite.Value[] values : rows.values) {
            JSONObject target = row(phase == 0 ? 3 : phase == 1 ? 2 : phase == 2 ? 6 : 7, values[0].text(), values[1]);
            decode(store, target); JSONArray copies = new JSONArray(); JSONObject head = null;
            if (phase == 0) copies(store, snapshot, target, copies);
            else if (phase == 1 && kind == 1) {
                JSONObject plan = call(store, "maintenanceQuery", snapshot.arguments().put("query", "headToken").put("message", target));
                PurpleArchiveSqlite.Result token = read(snapshot, plan, 1);
                if (token.rows != 1 || !token.values[0][0].text().matches("[0-9a-f]{64}")) throw new PurpleArchiveStore.Failure("Archive maintenance head identity");
                plan = call(store, "maintenanceQuery", snapshot.arguments().put("query", "head").put("message", target));
                PurpleArchiveSqlite.Result body = read(snapshot, plan, 1);
                if (body.rows != 1) throw new PurpleArchiveStore.Failure("Archive maintenance head missing");
                head = row(3, values[0].text() + "/" + token.values[0][0].text(), body.values[0][0]); copies(store, snapshot, head, copies);
            }
            long bytes = values[1].bytes.length + (head == null ? 0 : PurpleArchiveCore.unbase64(head.getString("frame")).length);
            for (int i = 0; i < copies.length(); ++i) {
                JSONObject copy = copies.getJSONObject(i); bytes += PurpleArchiveCore.unbase64(copy.getJSONObject("owner").getString("frame")).length
                    + PurpleArchiveCore.unbase64(copy.getJSONObject("blob").getString("frame")).length;
            }
            if (bytes > 12L * 1024 * 1024) throw new PurpleArchiveStore.Failure("Archive maintenance evidence row limit");
            if (output.length() != 0 && evidenceBytes + bytes > 12L * 1024 * 1024) break;
            evidenceBytes += bytes; output.put(new JSONObject().put("row", target).put("head", head == null ? JSONObject.NULL : head).put("copies", copies));
        }
        return output;
    }
    static java.math.BigInteger generation(String value) throws Exception {
        if (!value.matches("0|[1-9][0-9]{0,19}") || new java.math.BigInteger(value).bitLength() > 64) throw new PurpleArchiveStore.Failure("Archive purge generation encoding");
        return new java.math.BigInteger(value);
    }
    static boolean purged(PurpleArchiveStore store, JSONObject binding) throws Exception {
        JSONObject source = binding.getJSONObject("source"), epoch = source.getJSONObject("epoch"), key = source.getJSONObject("key");
        if (!store.binding.account.equals(epoch.getString("account")) || !store.binding.account.equals(key.getString("account"))
            || !store.binding.store.equals(epoch.getString("store")) || !store.binding.generation.equals(epoch.getString("generation")) || epoch.getBoolean("removalPending")) return false;
        try (PurpleArchiveStore.MaintenanceRead snapshot = store.maintenanceRead()) {
            int account = generation(source.getString("accountGeneration")).compareTo(generation(snapshot.inspection.getString("generation")));
            if (account != 0) return account < 0;
            JSONObject chat = key.getJSONObject("chat");
            JSONObject plan = call(store, "maintenanceQuery", store.arguments().put("query", "epoch").put("chat", chat));
            PurpleArchiveSqlite.Result rows = read(snapshot, plan, 2);
            if (rows.rows > 1) throw new PurpleArchiveStore.Failure("Archive purge epoch count");
            String current = "0";
            if (rows.rows == 1) {
                JSONObject value = PurpleArchiveQueries.decoded(decode(store, row(8, plan.getString("id"), rows.values[0][1])).getString("row")).getJSONObject("value");
                JSONObject actual = value.getJSONObject("chat"); current = value.getString("generation");
                if (actual.getInt("kind") != chat.getInt("kind") || !actual.getString("id").equals(chat.getString("id")) || !current.equals(rows.values[0][0].text())) throw new PurpleArchiveStore.Failure("Archive purge epoch projection");
            }
            return generation(source.getString("chatGeneration")).compareTo(generation(current)) < 0;
        }
    }
    static boolean busy(PurpleArchiveStore store) { synchronized (runners) { return runners.containsKey(store); } }
    static void resume(PurpleArchive.Worker worker) {
        PurpleArchiveStore store = worker.store; if (store == null) return;
        synchronized (runners) {
            Runner retained = runners.get(store);
            if (retained != null) { retained.retryCleanup(); return; }
            Runner runner = new Runner(worker); runners.put(store, runner); worker.control(runner);
        }
    }
    static void closed(PurpleArchiveStore store) {
        Runner runner; synchronized (runners) { runner = runners.get(store); }
        if (runner != null) runner.close();
    }
    static final class Sweep implements AutoCloseable {
        static final class Frame {
            final PurpleArchiveFiles.Directory parent, directory;
            final String name;
            final PurpleArchiveFiles.Listing listing;
            String[] page = new String[0]; int index;
            Frame(PurpleArchiveFiles.Directory parent, String name) {
                this.parent = parent; this.name = name; directory = new PurpleArchiveFiles.Directory(parent.file(name), true); listing = directory.listing();
            }
        }
        final ArrayDeque<Frame> stack = new ArrayDeque<>();
        Sweep(PurpleArchiveFiles.Directory parent, String name) {
            parent.check(); if (PurpleArchiveFiles.markAny(parent.file(name)) != null) stack.push(new Frame(parent, name));
        }
        boolean step(PurpleArchiveStore store, BooleanSupplier current) throws Exception {
            for (int effects = 0; effects < 64 && !stack.isEmpty(); ++effects) {
                if (!current.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive maintenance cleanup revoked");
                Frame frame = stack.peek();
                if (frame.index == frame.page.length) { frame.page = frame.listing.next(); frame.index = 0; }
                if (frame.page.length == 0) {
                    frame.listing.close(); synchronized (store.environment.publicationLock()) { store.requireAuthoritativeCurrent(); if (!current.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive cleanup revoked"); frame.parent.remove(frame.name, true); }
                    stack.pop(); continue;
                }
                String name = frame.page[frame.index++]; PurpleArchiveFiles.Mark mark = PurpleArchiveFiles.markAny(frame.directory.file(name));
                if (mark == null) continue;
                if ((mark.mode & 0170000) == 0040000) {
                    if (stack.size() >= 16) throw new PurpleArchiveStore.Failure("Archive cleanup depth refused");
                    stack.push(new Frame(frame.directory, name));
                } else {
                    if ((mark.mode & 0170000) != 0100000) throw new PurpleArchiveStore.Failure("Archive cleanup file kind refused");
                    synchronized (store.environment.publicationLock()) { store.requireAuthoritativeCurrent(); if (!current.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive cleanup revoked"); frame.directory.remove(name, false); }
                }
            }
            return stack.isEmpty();
        }
        @Override public void close() { for (Frame frame : stack) frame.listing.close(); stack.clear(); }
    }
    static final class Runner implements Runnable {
        final PurpleArchive.Worker worker;
        final PurpleArchiveStore store;
        final PurpleAccountFiles.Identity identity;
        final String login;
        int renewalAttempts;
        boolean renewed, closed, jobsComplete, cleanupHeld, cleanupRetryQueued;
        String jobCursor = "", cleaningJob, pendingBlob;
        String pendingBlobValue;
        Sweep sweep;
        Runner(PurpleArchive.Worker worker) { this.worker = worker; store = worker.store; identity = worker.identity; login = worker.loginContext; }
        boolean current() { return !closed && !worker.retired && !worker.closing && worker.store == store && identity != null && identity.current()
            && login != null && login.equals(worker.loginContext); }
        public void run() {
            try {
                if (!current()) { close(); return; }
                JSONObject plan = null; boolean pending;
                try (PurpleArchiveStore.MaintenanceRead snapshot = store.maintenanceRead()) {
                    pending = snapshot.inspection.getBoolean("pending");
                    if (pending) plan = call(store, "maintenanceContinue", snapshot.arguments().put("rows", page(store, snapshot)));
                }
                if (pending) {
                    worker.maintenanceState("Maintenance", "purge_in_progress");
                    if (!store.executeMaintenance(new PurpleArchiveCore.Program(plan), this::current).committed) throw new PurpleArchiveStore.Failure("Archive maintenance commit refused");
                    worker.control(this); return;
                }
                if (!renewed) {
                    if (++renewalAttempts > 3) throw new PurpleArchiveStore.Failure("Archive capture admission renewal exhausted");
                    try {
                        PurpleArchiveSequence.recover(store); PurpleArchiveGaps.flush(store);
                        try (PurpleArchiveStore.MaintenanceRead snapshot = store.maintenanceRead()) {
                            if (snapshot.inspection.getBoolean("pending") || !store.admissionCurrent(snapshot.inspection.getString("generation"))) throw new PurpleArchiveStore.Failure("Archive capture admission renewal refused");
                        }
                        renewed = true; worker.maintenanceReady();
                    } catch (Exception | LinkageError e) {
                        worker.maintenanceState("Maintenance", "capture_admission_renewal_retry");
                        if (renewalAttempts < 3) { worker.control(this); return; }
                        throw new PurpleArchiveStore.Failure("Archive capture admission renewal exhausted");
                    }
                }
                if (garbage()) { close(); PurpleArchiveStagingRecovery.start(worker); return; }
                worker.control(this);
            } catch (Exception | LinkageError e) {
                if (current()) worker.maintenanceState("Maintenance held", e instanceof PurpleArchiveStore.Failure ? ((PurpleArchiveStore.Failure)e).reason : "maintenance_io_or_authentication_failed");
                try { close(); }
                catch (Exception | LinkageError cleanup) { cleanupHeld = true; if (!worker.retired) worker.maintenanceState("Maintenance held", "maintenance_cleanup_pending"); }
            }
        }
        boolean garbage() throws Exception {
            if (!jobsComplete) {
                if (sweep != null) {
                    if (!sweep.step(store, this::current)) return false;
                    sweep.close(); sweep = null; jobCursor = cleaningJob; cleaningJob = null; return false;
                }
                try (PurpleArchiveStore.MaintenanceRead snapshot = store.maintenanceRead()) {
                    JSONObject plan = call(store, "maintenanceQuery", store.arguments().put("query", "cancelledJobs").put("after", jobCursor));
                    PurpleArchiveSqlite.Result rows = read(snapshot, plan, 2);
                    if (rows.rows == 0) { jobsComplete = true; return false; }
                    PurpleArchiveSqlite.Value[] values = rows.values[0];
                    if (values[0].text().compareTo(jobCursor) <= 0) throw new PurpleArchiveStore.Failure("Archive cancelled-job cursor");
                    JSONObject value = call(store, "maintenanceCancelled", store.arguments().put("row", row(6, values[0].text(), values[1])));
                    String token = value.getString("staging"); cleaningJob = values[0].text();
                    if (!token.isEmpty() && PurpleArchiveFiles.markAny(store.directory.file("staging")) != null) {
                        PurpleArchiveFiles.Directory root = new PurpleArchiveFiles.Directory(store.directory.file("staging"), true);
                        if (PurpleArchiveFiles.markAny(root.file("stream")) != null) sweep = new Sweep(new PurpleArchiveFiles.Directory(root.file("stream"), true), token);
                    }
                    if (sweep == null) { jobCursor = cleaningJob; cleaningJob = null; }
                    return false;
                }
            }
            JSONObject facts, blob; String token;
            try (PurpleArchiveStore.MaintenanceRead snapshot = store.maintenanceRead()) {
                facts = snapshot.arguments();
                if (pendingBlob == null) {
                    PurpleArchiveSqlite.Result rows = read(snapshot, query(store, "garbage"), 2); if (rows.rows == 0) return true;
                    token = rows.values[0][0].text(); blob = row(5, token, rows.values[0][1]);
                } else {
                    token = pendingBlob;
                    JSONObject plan = call(store, "maintenanceQuery", store.arguments().put("query", "pendingBlob").put("token", token));
                    PurpleArchiveSqlite.Result rows = read(snapshot, plan, 1); blob = row(5, token, rows.values[0][0]);
                }
            }
            String encoded = decode(store, blob).getString("row");
            JSONObject decoded = PurpleArchiveQueries.decoded(encoded).getJSONObject("value");
            if (pendingBlob == null) {
                JSONObject plan = call(store, "maintenanceGarbage", facts.put("blob", blob));
                if (!store.executeMaintenance(new PurpleArchiveCore.Program(plan), this::current).committed) throw new PurpleArchiveStore.Failure("Archive garbage intent not committed");
                pendingBlob = token; return false;
            }
            if (decoded.getInt("state") != 1) throw new PurpleArchiveStore.Failure("Archive garbage pending state changed");
            if (pendingBlobValue == null) pendingBlobValue = encoded;
            if (!pendingBlobValue.equals(encoded)) throw new PurpleArchiveStore.Failure("Archive garbage pending identity changed");
            if (sweep == null) sweep = new Sweep(new PurpleArchiveFiles.Directory(store.directory.file("blobs"), true), pendingBlob);
            if (!sweep.step(store, this::current)) return false;
            sweep.close(); sweep = null;
            JSONObject completion;
            try (PurpleArchiveStore.MaintenanceRead snapshot = store.maintenanceRead()) {
                completion = call(store, "maintenanceGarbageComplete", snapshot.arguments().put("blob", blob));
            }
            if (!store.executeMaintenance(new PurpleArchiveCore.Program(completion), this::current).committed) throw new PurpleArchiveStore.Failure("Archive garbage completion not committed");
            pendingBlob = null; pendingBlobValue = null; return false;
        }
        void retryCleanup() {
            if (!cleanupHeld || cleanupRetryQueued || closed) return;
            cleanupRetryQueued = true;
            worker.control(() -> {
                cleanupRetryQueued = false;
                try {
                    close();
                    if (!worker.retired && !worker.closing && worker.store == store && identity.current() && login.equals(worker.loginContext)) resume(worker);
                } catch (Exception | LinkageError e) {
                    if (!worker.retired) worker.maintenanceState("Maintenance held", "maintenance_cleanup_pending");
                }
            });
        }
        void close() {
            if (closed) return;
            if (sweep != null) { sweep.close(); sweep = null; }
            closed = true;
            synchronized (runners) { runners.remove(store, this); }
        }
    }
    private PurpleArchiveMaintenance() {}
}
