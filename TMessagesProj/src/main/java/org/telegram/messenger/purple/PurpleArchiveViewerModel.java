package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveViewerModel implements AutoCloseable {
    private static final java.util.concurrent.atomic.AtomicLong selection = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Set<PurpleArchiveViewerModel> viewers = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    public static void selectionChanged(int account) {
        selection.incrementAndGet();
        ArrayList<PurpleArchiveViewerModel> old;
        synchronized (viewers) { old = new ArrayList<>(viewers); }
        for (PurpleArchiveViewerModel viewer : old) {
            if (viewer.openingAccount != account || viewer.openingSelection != selection.get()) viewer.close();
        }
    }
    public static final class Version {
        public final String text, caption, observedAt, serverTime, format;
        public final int origin, media;
        public final boolean partial, missingPreimage;
        private Version(JSONObject value) throws Exception {
            JSONObject content = value.getJSONObject("content"), observation = value.getJSONObject("observation");
            text = content.getString("text"); caption = content.getString("caption");
            observedAt = value.getString("firstObservedAt"); serverTime = observation.getString("serverTime");
            format = content.getString("opaqueFormat"); origin = value.getInt("origin");
            media = value.getJSONArray("media").length(); partial = value.getInt("completeness") != 1;
            missingPreimage = value.getBoolean("missingPreimage");
        }
    }
    public static final class Page {
        public final List<Version> versions;
        public final boolean more;
        public final String error;
        Page(List<Version> versions, boolean more, String error) { this.versions = Collections.unmodifiableList(versions); this.more = more; this.error = error; }
    }
    public interface Delivered { void page(Page page); }
    final PurpleAccountFiles.Identity identity;
    private final String key;
    private final int openingAccount;
    private final long openingSelection;
    private final AtomicBoolean fetching = new AtomicBoolean();
    private volatile boolean closed;
    private PurpleArchive.Worker worker;
    private PurpleArchiveStore store;
    private PurpleArchiveStore.Reader reader;
    private long epoch;
    private int closeAttempts;
    private boolean readFailed;
    private String before = "";
    public PurpleArchiveViewerModel(int slot, TLRPC.Message message) throws Exception {
        openingAccount = UserConfig.selectedAccount; openingSelection = selection.get();
        if (slot != openingAccount) throw new IllegalStateException("Archive account selection changed");
        identity = PurpleAccountFiles.capture(slot);
        if (identity == null) throw new IllegalStateException("Archive account signed out");
        key = PurpleArchiveNormalize.key(identity.key, message).toString();
        synchronized (viewers) { viewers.add(this); }
    }
    private PurpleArchiveViewerModel(PurpleAccountFiles.Identity identity, String key, int openingAccount, long openingSelection) {
        this.identity = identity; this.key = key; this.openingAccount = openingAccount; this.openingSelection = openingSelection;
        synchronized (viewers) { viewers.add(this); }
    }
    private boolean selected() {
        if (UserConfig.selectedAccount != openingAccount || selection.get() != openingSelection) { close(); return false; }
        return true;
    }
    public boolean available() { return !closed && selected() && identity.current(); }
    public PurpleArchiveViewerModel restart() {
        if (!available()) throw new IllegalStateException("Archive account changed");
        return new PurpleArchiveViewerModel(identity, key, openingAccount, openingSelection);
    }
    public boolean next(Delivered delivered) {
        if (!available() || !fetching.compareAndSet(false, true)) return false;
        worker = PurpleArchive.existing(identity);
        if (worker == null || worker.store == null) {
            PurpleArchive.refresh(identity.slot, () -> {
                fetching.set(false);
                if (!available()) return;
                PurpleArchive.Worker ready = PurpleArchive.existing(identity);
                if (ready == null || !ready.status.state.equals("Ready")) deliver(delivered, new Page(new ArrayList<>(), false, "unavailable"));
                else next(delivered);
            });
            return true;
        }
        try { worker.queue.execute(() -> read(delivered)); }
        catch (java.util.concurrent.RejectedExecutionException e) { fetching.set(false); deliver(delivered, new Page(new ArrayList<>(), false, "unavailable")); }
        return true;
    }
    private boolean current() {
        return available() && worker != null && !worker.closing && !worker.retired
            && worker.store == store && worker.deliveryEpoch == epoch;
    }
    private void read(Delivered delivered) {
        try {
            if (readFailed || !available() || worker.closing || worker.retired) throw new IllegalStateException("Archive viewer revoked");
            if (reader == null) { store = worker.store; reader = store.reader(); epoch = worker.deliveryEpoch; }
            if (!current() || !reader.current()) throw new IllegalStateException("Archive viewer revoked");
            JSONObject key = new JSONObject(this.key);
            JSONObject messagePlan = PurpleArchiveCore.call("query", store.arguments().put("query", "message").put("key", key), store.crypto());
            String token = messagePlan.getString("id");
            JSONObject plan = PurpleArchiveCore.call("query", store.arguments().put("query", "versions").put("key", key).put("limit", 32).put("before", before), store.crypto());
            PurpleArchiveSqlite.Result rows = reader.read(new PurpleArchiveCore.Statement(plan.getJSONObject("statement")));
            ArrayList<Version> versions = new ArrayList<>(); long bytes = 0; boolean more = rows.rows == 32;
            for (PurpleArchiveSqlite.Value[] values : rows.values) {
                JSONObject row = new JSONObject().put("kind", 3).put("id", token + "/" + values[0].text())
                    .put("position", "0").put("frame", PurpleArchiveCore.base64(values[2].bytes));
                JSONObject decoded = PurpleArchiveSlice.open(store, reader, row).getJSONObject("value");
                if (!PurpleArchiveNormalize.digest(decoded.getJSONObject("key").toString()).equals(PurpleArchiveNormalize.digest(this.key)))
                    throw new PurpleArchiveStore.Failure("Archive viewer identity mismatch");
                Version version = new Version(decoded.getJSONObject("snapshot"));
                long size = (version.text.length() + version.caption.length()) * 2L;
                if (!versions.isEmpty() && bytes + size > 2 * 1024 * 1024) { more = true; break; }
                versions.add(version); bytes += size; before = values[1].text();
            }
            if (!current() || !reader.current()) throw new IllegalStateException("Archive viewer revoked");
            deliver(delivered, new Page(versions, more, ""));
        } catch (Exception | LinkageError e) {
            readFailed = true; closeReader();
            deliver(delivered, new Page(new ArrayList<>(), false, "stale"));
        } finally { fetching.set(false); }
    }
    private void deliver(Delivered delivered, Page page) {
        AndroidUtilities.runOnUIThread(() -> {
            if (available() && (!page.error.isEmpty() || current())) delivered.page(page);
        });
    }
    private void closeReader() {
        if (reader != null) {
            try { reader.close(); reader = null; }
            catch (Exception | LinkageError e) { if (++closeAttempts < 3 && worker != null) worker.control(this::closeReader); }
        }
    }
    @Override public void close() {
        closed = true;
        synchronized (viewers) { viewers.remove(this); }
        PurpleArchive.Worker owner = worker;
        if (owner != null) owner.control(this::closeReader);
    }
}
