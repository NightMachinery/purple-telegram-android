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
    public static long selectionGeneration() { return selection.get(); }
    public static void selectionChanged(int account) {
        selection.incrementAndGet();
        PurpleArchiveDeletedModel.revoke(-1);
        PurpleArchiveMediaAccess.revoke(-1);
        PurpleArchiveLifecycle.revoke(-1); PurpleArchiveAcquiredSource.revoke(-1);
        ArrayList<PurpleArchiveViewerModel> old;
        synchronized (viewers) { old = new ArrayList<>(viewers); }
        for (PurpleArchiveViewerModel viewer : old) {
            if (viewer.openingAccount != account || viewer.openingSelection != selection.get()) viewer.close();
        }
    }
    public static final class Attachment {
        public final String identity, name, mime;
        public final int state;
        final String key, version, revision, snapshot, accountGeneration, chatGeneration;
        final long selection;
        final PurpleArchiveStore store;
        final String login;
        Attachment(JSONObject value, JSONObject ref, String key, PurpleArchiveStore store, String login, String accountGeneration, String chatGeneration, long selection) throws Exception {
            this.store = store; this.login = login;
            this.key = key; this.accountGeneration = accountGeneration; this.chatGeneration = chatGeneration; this.selection = selection;
            version = value.getString("id"); revision = value.getString("revision"); snapshot = PurpleArchiveNormalize.digest(value.toString());
            identity = ref.getString("identity"); state = ref.getInt("state");
            String type = "application/octet-stream", file = "archive-attachment";
            org.json.JSONArray descriptors = value.getJSONObject("content").getJSONArray("media");
            for (int i = 0; i < descriptors.length(); ++i) {
                JSONObject descriptor = descriptors.getJSONObject(i);
                if (!descriptor.getString("identity").equals(identity)) continue;
                JSONObject attributes = descriptor.getJSONObject("attributes");
                type = attributes.optString("mime", descriptor.getString("kind").equals("photo") || descriptor.getString("kind").equals("thumbnail") ? "image/jpeg" : type);
                file = attributes.optString("name", file);
                org.json.JSONArray encoded = attributes.optJSONArray("attributes");
                if (encoded != null) for (int j = 0; j < encoded.length(); ++j) {
                    byte[] raw = PurpleArchiveCore.unbase64(encoded.getString(j));
                    if (raw.length > PurpleArchiveNormalize.RAW_LIMIT) throw new PurpleArchiveStore.Failure("Archive filename metadata too large");
                    org.telegram.tgnet.SerializedData data = new org.telegram.tgnet.SerializedData(raw);
                    try {
                        TLRPC.DocumentAttribute attribute = TLRPC.DocumentAttribute.TLdeserialize(data, data.readInt32(true), true);
                        if (attribute instanceof TLRPC.TL_documentAttributeFilename) file = attribute.file_name;
                    } finally { data.cleanup(); }
                }
            }
            mime = type.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") ? type : "application/octet-stream";
            if (file == null || file.equals("archive-attachment")) file = "archive-attachment" + (mime.equals("image/jpeg") ? ".jpg" : mime.equals("image/png") ? ".png" : mime.equals("video/mp4") ? ".mp4" : mime.equals("audio/ogg") ? ".ogg" : "");
            StringBuilder safe = new StringBuilder();
            for (int i = 0; i < Math.min(160, file.length()); ++i) {
                char c = file.charAt(i); safe.append(c == '/' || c == '\\' || Character.isISOControl(c) ? '_' : c);
            }
            file = safe.toString();
            name = file.isEmpty() ? "archive-attachment" : file.substring(0, Math.min(160, file.length()));
        }
    }
    public static final class Version {
        public final String text, caption, observedAt, serverTime, format;
        public final int origin, media;
        public final boolean partial, missingPreimage;
        public final List<Attachment> attachments;
        private Version(JSONObject value, String key, PurpleArchiveStore store, String login, String accountGeneration, String chatGeneration, long selection) throws Exception {
            JSONObject content = value.getJSONObject("content"), observation = value.getJSONObject("observation");
            text = content.getString("text"); caption = content.getString("caption");
            observedAt = value.getString("firstObservedAt"); serverTime = observation.getString("serverTime");
            format = content.getString("opaqueFormat"); origin = value.getInt("origin");
            media = value.getJSONArray("media").length(); partial = value.getInt("completeness") != 1;
            missingPreimage = value.getBoolean("missingPreimage");
            ArrayList<Attachment> values = new ArrayList<>();
            for (int i = 0; i < media; ++i) values.add(new Attachment(value, value.getJSONArray("media").getJSONObject(i), key, store, login, accountGeneration, chatGeneration, selection));
            attachments = Collections.unmodifiableList(values);
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
    private PurpleArchive.Worker pinnedWorker;
    private PurpleArchiveStore pinnedStore;
    private String pinnedLogin, pinnedVersion, pinnedDigest, pinnedAccountGeneration, pinnedChatGeneration;
    private java.util.function.BooleanSupplier pinnedPolicy = () -> true;
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
        this(identity,key,openingAccount,openingSelection,true);
    }
    private PurpleArchiveViewerModel(PurpleAccountFiles.Identity identity, String key, int openingAccount, long openingSelection, boolean register) {
        this.identity = identity; this.key = key; this.openingAccount = openingAccount; this.openingSelection = openingSelection;
        if (register) synchronized (viewers) { viewers.add(this); }
    }
    static PurpleArchiveViewerModel pinned(PurpleAccountFiles.Identity identity, String key, long selection, PurpleArchive.Worker worker,
        PurpleArchiveStore store, String login, String version, String digest, String accountGeneration, String chatGeneration, java.util.function.BooleanSupplier policy) {
        PurpleArchiveViewerModel model = new PurpleArchiveViewerModel(identity,key,identity.slot,selection,false);
        model.pinnedWorker = worker; model.pinnedStore = store; model.pinnedLogin = login; model.pinnedVersion = version; model.pinnedDigest = digest; model.pinnedAccountGeneration = accountGeneration; model.pinnedChatGeneration = chatGeneration; model.pinnedPolicy = policy;
        synchronized (viewers) { viewers.add(model); }
        if (!model.available()) { model.close(); return null; } return model;
    }
    static void committed(PurpleArchiveStore store) {
        ArrayList<PurpleArchiveViewerModel> retained;
        synchronized (viewers) { retained = new ArrayList<>(viewers); }
        for (PurpleArchiveViewerModel viewer : retained) if (viewer.pinnedStore == store && !viewer.closed) {
            try {
                JSONObject chat = new JSONObject(viewer.key).getJSONObject("chat");
                JSONObject scope = new JSONObject().put("chat", chat).put("aliases", new org.json.JSONArray());
                if (!viewer.available() || !store.admissionCurrent(viewer.pinnedAccountGeneration)
                    || !PurpleArchiveQueries.generationCurrent(store,scope,chat,viewer.pinnedChatGeneration)) viewer.close();
            } catch (Exception | LinkageError e) { viewer.close(); }
        }
    }
    public PurpleArchiveMediaAccess.Capability media(Attachment attachment, PurpleArchiveMediaAccess.Delivered delivered) {
        if (!available()) return null;
        return PurpleArchiveMediaAccess.request(identity, attachment, delivered, this::leaseCommitted);
    }
    private void leaseCommitted(PurpleArchiveMediaAccess.Capability capability, JSONObject transition) {
        if (current() && reader != null && capability.worker == worker && capability.store == store
            && capability.login != null && capability.login.equals(worker.loginContext)) reader.advanceLease(transition);
    }
    private boolean selected() {
        if (UserConfig.selectedAccount != openingAccount || selection.get() != openingSelection) { close(); return false; }
        return true;
    }
    public boolean available() {
        if (!pinnedPolicy.getAsBoolean()) { close(); return false; }
        return !closed && selected() && identity.current() && (pinnedWorker == null || pinnedWorker == PurpleArchive.existing(identity)
            && !pinnedWorker.closing && !pinnedWorker.retired && pinnedWorker.store == pinnedStore && pinnedLogin.equals(pinnedWorker.loginContext));
    }
    public PurpleArchiveViewerModel restart() {
        if (!available()) throw new IllegalStateException("Archive account changed");
        return pinnedWorker == null ? new PurpleArchiveViewerModel(identity, key, openingAccount, openingSelection)
            : pinned(identity,key,openingSelection,pinnedWorker,pinnedStore,pinnedLogin,pinnedVersion,pinnedDigest,pinnedAccountGeneration,pinnedChatGeneration,pinnedPolicy);
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
            JSONObject key = new JSONObject(this.key); String accountGeneration, chatGeneration;
            if (pinnedVersion != null) try (PurpleArchiveSlice original = new PurpleArchiveSlice(store,key,null)) {
                if (!PurpleArchiveNormalize.digest(original.version(pinnedVersion).toString()).equals(pinnedDigest)) throw new PurpleArchiveStore.Failure("Archive opening version changed");
            }
            try (PurpleArchiveSlice scope = new PurpleArchiveSlice(store, key, null)) {
                accountGeneration = scope.facts.getString("accountGeneration"); chatGeneration = scope.facts.getString("chatGeneration");
                if (pinnedAccountGeneration != null && (!pinnedAccountGeneration.equals(accountGeneration) || !pinnedChatGeneration.equals(chatGeneration)))
                    throw new PurpleArchiveStore.Failure("Archive opening purge generation changed");
            }
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
                Version version = new Version(decoded.getJSONObject("snapshot"), this.key, store, worker.loginContext, accountGeneration, chatGeneration, openingSelection);
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
