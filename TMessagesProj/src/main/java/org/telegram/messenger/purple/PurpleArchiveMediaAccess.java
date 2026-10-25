package org.telegram.messenger.purple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;

public final class PurpleArchiveMediaAccess {
    static final int CHUNK = 1024 * 1024;
    private static final ConcurrentHashMap<String, Capability> entries = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "PurpleArchiveMediaMonitor"); thread.setDaemon(true); return thread;
    });
    static { monitor.scheduleWithFixedDelay(PurpleArchiveMediaAccess::tick, 2, 2, TimeUnit.SECONDS); }
    public interface Delivered { void result(Capability value, String error); }
    interface LeaseCommitted { void committed(Capability capability, JSONObject transition); }
    public static final class Capability implements AutoCloseable {
        public final String token, name, mime;
        public volatile long bytes;
        final PurpleArchiveViewerModel.Attachment attachment;
        final PurpleAccountFiles.Identity identity;
        final PurpleArchive.Worker worker;
        final PurpleArchiveStore store;
        final String login;
        final LeaseCommitted leaseOwner;
        final long selection;
        final AtomicBoolean stopped = new AtomicBoolean(), checking = new AtomicBoolean(), releasing = new AtomicBoolean();
        volatile boolean ready;
        volatile long touched = System.nanoTime();
        boolean acquired, attempted;
        volatile boolean released;
        JSONObject expected;
        String blobToken;
        long verifiedIndex;
        MessageDigest digest;
        volatile Runnable revoked;
        int streams;
        final java.util.Set<PurpleArchiveMediaDataSource> sources = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        Capability(PurpleArchiveViewerModel.Attachment attachment, PurpleAccountFiles.Identity identity, PurpleArchive.Worker worker, LeaseCommitted leaseOwner) {
            this.attachment = attachment; this.identity = identity; this.worker = worker; this.leaseOwner = leaseOwner; store = attachment.store; login = attachment.login;
            selection = attachment.selection; token = PurpleArchiveBinding.token() + PurpleArchiveBinding.token(); name = attachment.name; mime = attachment.mime;
        }
        boolean memory() {
            return !stopped.get() && !org.telegram.messenger.SharedConfig.appLocked
                && !org.telegram.messenger.SharedConfig.isWaitingForPasscodeEnter && !AndroidUtilities.needShowPasscode(false) && identity.matchesSlot() && selection == PurpleArchiveViewerModel.selectionGeneration()
                && UserConfig.selectedAccount == identity.slot && worker.store == store && !worker.closing && !worker.retired
                && login != null && login.equals(worker.loginContext);
        }
        public boolean available() { return ready && memory(); }
        void require() throws Exception { if (!memory()) throw new PurpleArchiveStore.Failure("Archive media authority changed"); store.requireCurrent(); }
        public void onRevoked(Runnable action) { revoked = action; if (!available()) AndroidUtilities.runOnUIThread(action); }
        public synchronized boolean openStream() { if (!available() || streams >= 2) return false; ++streams; touched = System.nanoTime(); return true; }
        public synchronized void closeStream() { if (streams > 0) --streams; }
        JSONObject arguments(PurpleArchiveSlice slice) throws Exception {
            JSONObject value = store.arguments().put("rows", slice.rows).put("key", new JSONObject(attachment.key)).put("lease", token)
                .put("version", attachment.version).put("media", attachment.identity);
            if (expected != null) value.put("expected", expected); return value;
        }
        PurpleArchiveSlice slice(boolean lease) throws Exception {
            require(); PurpleArchiveSlice result = new PurpleArchiveSlice(store, new JSONObject(attachment.key), null);
            try {
                JSONObject version = result.version(attachment.version);
                if (!PurpleArchiveNormalize.digest(version.toString()).equals(attachment.snapshot)) throw new PurpleArchiveStore.Failure("Archive media version changed");
                if (!result.facts.getString("accountGeneration").equals(attachment.accountGeneration)
                    || !result.facts.getString("chatGeneration").equals(attachment.chatGeneration)) throw new PurpleArchiveStore.Failure("Archive media generation changed");
                JSONArray refs = version.getJSONArray("media"); String blob = null;
                for (int i = 0; i < refs.length(); ++i) {
                    JSONObject ref = refs.getJSONObject(i);
                    if (ref.getString("identity").equals(attachment.identity) && ref.getInt("state") == 2) blob = ref.getString("blobId");
                }
                if (blob == null) throw new PurpleArchiveStore.Failure("Archive media is not Complete");
                result.blob(blob); if (lease && result.lease(token) == null) throw new PurpleArchiveStore.Failure("Archive media lease missing");
                return result;
            } catch (Exception | LinkageError e) { result.close(); throw e; }
        }
        void check() throws Exception {
            require(); try (PurpleArchiveSlice slice = slice(true)) {
                JSONObject value = PurpleArchiveCore.call("accessCheck", arguments(slice), store.crypto());
                if (!value.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive media lease refused");
            }
        }
        byte[] chunk(long index) throws Exception {
            require(); check();
            long chunks = bytes == 0 ? 1 : 1 + (bytes - 1) / CHUNK;
            if (index < 0 || index >= chunks) throw new IOException("Archive chunk outside attachment");
            PurpleArchiveFiles.Directory directory = new PurpleArchiveFiles.Directory(store.directory.child("blobs", true).file(blobToken), true);
            byte[] frame = directory.read("chunk-" + index, CHUNK + 4608).bytes;
            if (frame == null) throw new IOException("Archive chunk missing");
            byte[] plain = PurpleArchiveCore.mediaNative(store.binding.account, store.binding.store, store.binding.key, blobToken, bytes, index, frame, false, store.crypto());
            if (plain == null || plain.length != Math.min(CHUNK, bytes - index * CHUNK)) {
                if (plain != null) Arrays.fill(plain, (byte) 0); throw new IOException("Archive chunk authentication failed");
            }
            try { require(); return plain; } catch (Exception e) { Arrays.fill(plain, (byte) 0); throw e; }
        }
        void prepare(Delivered callback) {
            try {
                require();
                try (PurpleArchiveSlice slice = slice(false)) {
                    expected = PurpleArchiveCore.call("accessInfo", arguments(slice), store.crypto());
                    bytes = PurpleArchiveBinding.generation(expected.getString("bytes")).longValueExact();
                    blobToken = PurpleArchiveCore.call("blobToken", store.arguments().put("blob", expected.getString("blob")), store.crypto()).getString("token");
                    JSONObject plan = PurpleArchiveCore.call("accessAcquire", arguments(slice), store.crypto());
                    attempted = true;
                    if (!store.execute(new PurpleArchiveCore.Program(plan), this::memory).committed) throw new IOException("Archive media lease not committed");
                    acquired = true;
                    if (leaseOwner != null) leaseOwner.committed(this, plan.getJSONObject("leaseAdvance"));
                }
                digest = MessageDigest.getInstance("SHA-256"); worker.control(() -> verify(callback));
            } catch (Exception | LinkageError e) { fail(callback, "unavailable"); }
        }
        void verify(Delivered callback) {
            try {
                byte[] plain = chunk(verifiedIndex++);
                try { digest.update(plain); } finally { Arrays.fill(plain, (byte) 0); }
                long chunks = bytes == 0 ? 1 : 1 + (bytes - 1) / CHUNK;
                if (verifiedIndex < chunks) { worker.control(() -> verify(callback)); return; }
                if (!hex(digest.digest()).equals(expected.getString("blob"))) throw new IOException("Archive media total hash changed");
                digest = null; require(); ready = true; touched = System.nanoTime();
                AndroidUtilities.runOnUIThread(() -> { if (available()) callback.result(this, ""); else { close(); callback.result(null, "revoked"); } });
            } catch (Exception | LinkageError e) { fail(callback, "unavailable"); }
        }
        void fail(Delivered callback, String reason) {
            close(); PurpleArchive.captureGap(identity, "archive_media_access_failed");
            AndroidUtilities.runOnUIThread(() -> callback.result(null, reason));
        }
        void validate(BooleanSupplier active) throws IOException {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            try {
                worker.queue.execute(() -> {
                    try { if (!active.getAsBoolean()) throw new IOException("Archive read cancelled"); check(); future.complete(memory() && active.getAsBoolean()); }
                    catch (Exception | LinkageError e) { future.completeExceptionally(e); }
                });
                if (!future.get(5, TimeUnit.SECONDS) || !available() || !active.getAsBoolean()) throw new IOException("Archive media revoked");
                touched = System.nanoTime();
            } catch (Exception e) { close(); throw new IOException("Archive validation unavailable", e); }
        }
        byte[] read(long position, int length, BooleanSupplier active) throws IOException {
            if (!available() || !active.getAsBoolean() || position < 0 || length < 0 || length > CHUNK) throw new IOException("Archive read refused");
            if (position >= bytes || length == 0) return new byte[0];
            CompletableFuture<byte[]> future = new CompletableFuture<>(); AtomicBoolean ticket = new AtomicBoolean(true);
            try {
                worker.queue.execute(() -> {
                    byte[] plain = null, result = null;
                    try {
                        if (!ticket.get() || !active.getAsBoolean()) throw new IOException("Archive read cancelled");
                        plain = chunk(position / CHUNK); int offset = (int) (position % CHUNK);
                        result = Arrays.copyOfRange(plain, offset, offset + Math.min(length, plain.length - offset));
                        if (!available() || !ticket.get() || !active.getAsBoolean()) throw new IOException("Archive read revoked");
                        if (!future.complete(result)) Arrays.fill(result, (byte) 0); result = null;
                    } catch (Exception | LinkageError e) { future.completeExceptionally(e); }
                    finally { if (plain != null) Arrays.fill(plain, (byte) 0); if (result != null) Arrays.fill(result, (byte) 0); }
                });
                byte[] result = future.get(5, TimeUnit.SECONDS);
                if (!available() || !active.getAsBoolean()) { Arrays.fill(result, (byte) 0); throw new IOException("Archive read revoked"); }
                touched = System.nanoTime(); return result;
            } catch (Exception e) {
                ticket.set(false); future.thenAccept(value -> Arrays.fill(value, (byte) 0)); close(); throw new IOException("Archive read unavailable", e);
            }
        }
        @Override public void close() {
            if (stopped.compareAndSet(false, true)) {
                ready = false;
                ArrayList<PurpleArchiveMediaDataSource> active; synchronized (sources) { active = new ArrayList<>(sources); }
                for (PurpleArchiveMediaDataSource source : active) source.close();
                Runnable action = revoked; if (action != null) AndroidUtilities.runOnUIThread(action);
            }
            if (worker.retired) { finishClose(); return; }
            if (!released && releasing.compareAndSet(false, true)) worker.control(() -> {
                try { release(); } finally { releasing.set(false); }
            });
        }
        void release() {
            if (released) return;
            if (!attempted) { finishClose(); return; }
            if (worker.store != store || worker.retired) { finishClose(); return; }
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, new JSONObject(attachment.key), null)) {
                if (slice.lease(token) != null) {
                    JSONObject plan = PurpleArchiveCore.call("accessRelease", arguments(slice), store.crypto());
                    if (!store.execute(new PurpleArchiveCore.Program(plan), () -> !worker.closing).committed) throw new IOException("Archive lease release not committed");
                    if (leaseOwner != null) leaseOwner.committed(this, plan.getJSONObject("leaseAdvance"));
                }
                finishClose();
            } catch (Exception | LinkageError e) {
                PurpleArchive.captureGap(identity, "archive_reader_release_pending");
            }
        }
        void finishClose() { released = true; digest = null; entries.remove(token, this); }
    }
    public static Capability request(PurpleAccountFiles.Identity identity, PurpleArchiveViewerModel.Attachment attachment, Delivered callback) {
        return request(identity, attachment, callback, null);
    }
    static Capability request(PurpleAccountFiles.Identity identity, PurpleArchiveViewerModel.Attachment attachment, Delivered callback, LeaseCommitted leaseOwner) {
        if (identity == null || attachment == null || attachment.state != 2 || callback == null || !identity.current()
            || identity.slot != UserConfig.selectedAccount || attachment.selection != PurpleArchiveViewerModel.selectionGeneration()) return null;
        PurpleArchive.Worker worker = PurpleArchive.existing(identity);
        if (worker == null || worker.store == null || worker.store != attachment.store || attachment.login == null
            || !attachment.login.equals(worker.loginContext) || !worker.status.state.equals("Ready")) return null;
        Capability cap;
        synchronized (entries) {
            int account = 0; for (Capability old : entries.values()) if (old.identity.key.equals(identity.key)) ++account;
            if (entries.size() >= 8 || account >= 4) return null;
            cap = new Capability(attachment, identity, worker, leaseOwner); entries.put(cap.token, cap);
        }
        try { worker.queue.execute(() -> cap.prepare(callback)); return cap; }
        catch (java.util.concurrent.RejectedExecutionException e) { cap.finishClose(); return null; }
    }
    static Capability find(String token) { Capability cap = entries.get(token); return cap != null && cap.available() ? cap : null; }
    static void revoke(int slot) { for (Capability cap : entries.values()) if (slot < 0 || cap.identity.slot == slot) cap.close(); }
    static void closed(PurpleArchiveStore store) { for (Capability cap : entries.values()) if (cap.store == store) { cap.close(); cap.finishClose(); } }
    static void committed(PurpleArchiveStore store) {
        for (Capability cap : entries.values()) if (cap.store == store && cap.acquired && !cap.stopped.get()) {
            try { cap.check(); } catch (Exception | LinkageError e) { cap.close(); }
        }
    }
    static void recover(PurpleArchive.Worker worker) {
        PurpleArchiveStore opening = worker.store;
        class Orphans implements Runnable {
            String after = "";
            public void run() {
                if (worker.store != opening || worker.closing || worker.retired) return;
                try {
                    JSONObject query = PurpleArchiveCore.call("accessLeases", opening.arguments().put("after", after), opening.crypto());
                    PurpleArchiveSqlite.Result page;
                    try (PurpleArchiveStore.Reader reader = opening.reader()) { page = reader.read(new PurpleArchiveCore.Statement(query.getJSONObject("statement"))); }
                    if (page.rows == 0) return;
                    for (PurpleArchiveSqlite.Value[] row : page.values) {
                        JSONObject value;
                        try (PurpleArchiveStore.Reader reader = opening.reader()) {
                            value = PurpleArchiveSlice.open(opening, reader, new JSONObject().put("kind", 7).put("id", row[0].text())
                                .put("position", "0").put("frame", PurpleArchiveCore.base64(row[2].bytes))).getJSONObject("value");
                        }
                        after = row[0].text(); String id = value.getString("id");
                        if (entries.containsKey(id)) continue;
                        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(opening, value.getJSONObject("key"), null)) {
                            if (slice.lease(id) == null) throw new IOException("Archive orphan lease disappeared");
                            JSONObject plan = PurpleArchiveCore.call("accessRelease", opening.arguments().put("rows", slice.rows).put("key", slice.key).put("lease", id), opening.crypto());
                            if (!opening.execute(new PurpleArchiveCore.Program(plan), () -> !worker.closing).committed) throw new IOException("Archive orphan lease release pending");
                        }
                        worker.control(this); return;
                    }
                    worker.control(this);
                } catch (Exception | LinkageError e) { PurpleArchive.captureGap(worker.identity, "archive_orphan_reader_release_pending"); }
            }
        }
        worker.control(new Orphans());
    }
    private static void tick() {
        for (Capability cap : entries.values()) {
            if (!cap.memory() || System.nanoTime() - cap.touched > TimeUnit.MINUTES.toNanos(30)) { cap.close(); continue; }
            if (cap.ready && cap.checking.compareAndSet(false, true)) cap.worker.control(() -> {
                try { cap.check(); } catch (Exception | LinkageError e) { cap.close(); } finally { cap.checking.set(false); }
            });
        }
    }
    static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(java.util.Locale.US, "%02x", b & 255)); return value.toString(); }
    private PurpleArchiveMediaAccess() {}
}
