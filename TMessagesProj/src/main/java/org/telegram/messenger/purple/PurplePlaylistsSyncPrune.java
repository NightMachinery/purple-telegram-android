package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurplePlaylistsSyncPrune implements AutoCloseable {
    enum Status { Ready, Refused, OutcomeUnknown, Canceled, TimedOut }
    static final class Result {
        final Status status;
        final int deleted;
        final boolean deleteIssued;
        Result(Status status, int deleted, boolean deleteIssued) {
            this.status = status; this.deleted = deleted; this.deleteIssued = deleteIssued;
        }
    }
    private final PurpleSyncClient client;
    private final PurpleSyncTransport.PostAuthority authority;
    private final BooleanSupplier scope;
    private final PurplePlaylistsSyncActor.Scheduler scheduler;
    private final PurpleSyncChatOrigin origin;
    private final PurpleSyncChatCore.Peer chat;
    private final AtomicBoolean started = new AtomicBoolean(), finished = new AtomicBoolean();
    private final Runnable timeout = () -> finish(Status.TimedOut);
    private volatile Pending<?> pending;
    private Consumer<Result> done;
    private JSONArray targets;
    private JSONObject selected;
    private int index, deleted;
    private volatile boolean deleteIssued, waitingDelete;
    private PurpleSyncClient.Message target, witness;
    private byte[] targetMeta, witnessMeta, targetBody, witnessBody;

    PurplePlaylistsSyncPrune(PurpleSyncClient client, PurpleSyncTransport.PostAuthority authority,
            BooleanSupplier scope, PurplePlaylistsSyncActor.Scheduler scheduler) {
        if (client == null || authority == null || scope == null || scheduler == null
                || client.origin() == null || client.chat() == null || client.userId() != client.origin().userId)
            throw new IllegalArgumentException("MissingPlaylistPruneRoute");
        this.client = client; this.authority = authority; this.scope = scope; this.scheduler = scheduler;
        origin = client.origin(); chat = client.chat();
    }
    private boolean current() {
        return !finished.get() && scope.getAsBoolean() && client.isActive() && origin.userId == client.userId()
                && origin.equals(client.origin()) && chat.equals(client.chat()) && authority.current()
                && (authority.permits(origin, chat) || authority.automatic(origin, chat));
    }
    void start(JSONObject plan, Consumer<Result> done) {
        if (done == null || !started.compareAndSet(false, true) || finished.get())
            throw new IllegalArgumentException("PlaylistPruneAlreadyStarted");
        this.done = done;
        scheduler.post(timeout, 2 * 60 * 1000L);
        enqueue(() -> {
            JSONObject prune = plan == null ? null : plan.optJSONObject("prune");
            JSONArray values = prune == null ? null : prune.optJSONArray("targets");
            if (values == null) { finish(Status.Ready); return; }
            try { targets = new JSONArray(values.toString()); }
            catch (Exception error) { finish(Status.Refused); return; }
            next();
        });
    }
    private void enqueue(Runnable action) {
        client.runOnWorker(() -> {
            if (finished.get()) return;
            try {
                if (!current()) { finish(Status.Refused); return; }
                action.run();
            } catch (RuntimeException error) { finish(Status.Refused); }
        });
    }
    private final class Pending<T> {
        private final AtomicBoolean answered = new AtomicBoolean();
        private final Consumer<T> reply;
        private final boolean download;
        private Object token;
        private boolean attached, canceled;
        Pending(boolean download, Consumer<T> reply) { this.download = download; this.reply = reply; }
        synchronized void attach(Object value) { token = value; attached = true; if (canceled) stop(value); }
        void complete(T value) {
            if (!answered.compareAndSet(false, true)) return;
            enqueue(() -> { if (pending == this) { pending = null; reply.accept(value); } });
        }
        synchronized void cancel() {
            if (canceled) return;
            canceled = true; answered.set(true); if (attached) stop(token);
        }
        private void stop(Object value) {
            if (download) { if (value != null) client.cancelDownload(value); }
            else if (value instanceof Integer && (Integer) value != 0) client.cancelRequest((Integer) value);
        }
    }
    private <T> void issue(boolean download, Function<PurpleSyncClient.Reply<T>, Object> wire, Consumer<T> reply) {
        if (!current() || pending != null) throw new IllegalStateException("PlaylistPruneRequestScope");
        Pending<T> active = new Pending<>(download, reply); pending = active;
        try { active.attach(wire.apply(active::complete)); }
        catch (RuntimeException error) { active.cancel(); if (pending == active) pending = null; throw error; }
        if (!current()) active.cancel();
    }
    private void next() {
        target = witness = null; targetMeta = witnessMeta = targetBody = witnessBody = null;
        if (index == targets.length()) { finish(Status.Ready); return; }
        selected = targets.optJSONObject(index++);
        if (selected == null || id("target") == id("witness")) { finish(Status.Refused); return; }
        lookup("target", false, () -> lookup("witness", false, () -> download(false)));
    }
    private int id(String key) {
        JSONObject value = selected.optJSONObject(key);
        return value == null ? 0 : Integer.parseInt(value.optString("message"));
    }
    private byte[] metadata(PurpleSyncClient.Message message, String key) {
        if (message == null || message.id != id(key) || !client.contains(message) || message.size <= 0
                || message.size > PurplePlaylistsSyncPublication.MAX_BYTES
                || !Long.toUnsignedString(message.documentId).equals(selected.optJSONObject(key).optString("document")))
            throw new IllegalArgumentException("PruneIdentityChanged");
        byte[] bytes = client.playlistMetadata(message);
        if (bytes == null) throw new IllegalArgumentException("MissingPruneMetadata");
        JSONObject value = object(bytes);
        if (!Integer.toString(message.id).equals(value.optString("message"))
                || !Long.toUnsignedString(message.documentId).equals(value.optString("document"))
                || !"Candidate".equals(PurplePlaylistsSyncCore.metadata(bytes, origin.userId).detail().optString("verdict")))
            throw new IllegalArgumentException("PruneOwnershipChanged");
        return bytes.clone();
    }
    private void lookup(String key, boolean compare, Runnable next) {
        this.<PurpleSyncClient.Lookup>issue(false, reply -> client.requestMessage(id(key), reply), fresh -> {
            if (fresh == null || fresh.status != PurpleSyncClient.LookupStatus.Found) { finish(Status.Refused); return; }
            byte[] meta = metadata(fresh.message, key);
            boolean first = "target".equals(key);
            PurpleSyncClient.Message before = first ? target : witness;
            byte[] old = first ? targetMeta : witnessMeta;
            if (compare && (before == null || before.editDate != fresh.message.editDate
                    || before.size != fresh.message.size || !Arrays.equals(old, meta))) { finish(Status.Refused); return; }
            if (!compare) {
                if (first) { target = fresh.message; targetMeta = meta; }
                else { witness = fresh.message; witnessMeta = meta; }
            }
            next.run();
        });
    }
    private void download(boolean second) {
        PurpleSyncClient.Message message = second ? witness : target;
        this.<File>issue(true, reply -> client.download(message, reply), file -> {
            byte[] body = PurplePlaylistsSyncFiles.read(file, message.size, this::current);
            if (body == null) { finish(Status.Refused); return; }
            if (!second) { targetBody = body; download(true); return; }
            witnessBody = body;
            try {
                JSONObject request = new JSONObject().put("chat", chat.token())
                        .put("target", selected.getJSONObject("target")).put("witness", selected.getJSONObject("witness"))
                        .put("hash", selected.getString("hash"))
                        .put("messages", new JSONArray().put(object(targetMeta)).put(object(witnessMeta)));
                if (!PurplePlaylistsSyncCore.verifyPrune(request.toString().getBytes(StandardCharsets.UTF_8),
                        targetBody, witnessBody, origin.userId).detail().optBoolean("allowed")) { finish(Status.Refused); return; }
            } catch (Exception error) { finish(Status.Refused); return; }
            renew(() -> lookup("target", true, () -> lookup("witness", true, () -> renew(this::delete))));
        });
    }
    private void renew(Runnable next) {
        AtomicBoolean once = new AtomicBoolean();
        authority.check(ready -> {
            if (once.compareAndSet(false, true)) enqueue(() -> {
                if (Boolean.TRUE.equals(ready)) next.run(); else finish(Status.Refused);
            });
        });
    }
    private boolean unchanged(PurpleSyncClient.Message message, byte[] meta) {
        if (message == null || !(message.remote instanceof org.telegram.tgnet.TLRPC.Message)) return false;
        org.telegram.tgnet.TLRPC.Message raw = (org.telegram.tgnet.TLRPC.Message) message.remote;
        return raw.id == message.id && raw.edit_date == message.editDate && raw.media != null
                && raw.media.document != null && raw.media.document.id == message.documentId
                && raw.media.document.size == message.size && client.contains(message)
                && Arrays.equals(meta, client.playlistMetadata(message));
    }
    private boolean deleteCurrent() {
        return current() && unchanged(target, targetMeta) && unchanged(witness, witnessMeta);
    }
    private void delete() {
        if (!deleteCurrent()) { finish(Status.Refused); return; }
        this.<PurpleSyncAutoCore.Outcome>issue(false, reply -> {
            authority.issued();
            if (!deleteCurrent()) { reply.onReply(PurpleSyncAutoCore.Outcome.DeletionRefused); return 0; }
            deleteIssued = waitingDelete = true;
            client.deleteOwn(id("target"), this::deleteCurrent, reply); return 0;
        }, outcome -> {
            waitingDelete = false;
            if (outcome != PurpleSyncAutoCore.Outcome.Deleted) {
                finish(outcome == PurpleSyncAutoCore.Outcome.DeletionRefused ? Status.Refused : Status.OutcomeUnknown); return;
            }
            ++deleted; next();
        });
    }
    private void finish(Status status) {
        if (!finished.compareAndSet(false, true)) return;
        finalStatus = waitingDelete ? Status.OutcomeUnknown : status;
        scheduler.cancel(timeout);
        Pending<?> active = pending; pending = null; if (active != null) active.cancel();
        targetBody = witnessBody = null;
        Consumer<Result> reply = done;
        if (reply != null) reply.accept(new Result(waitingDelete ? Status.OutcomeUnknown : status, deleted, deleteIssued));
    }
    Result snapshot() { return new Result(waitingDelete ? Status.OutcomeUnknown : finished.get() ? finalStatus : Status.Canceled, deleted, deleteIssued); }
    private volatile Status finalStatus = Status.Canceled;
    boolean outcomeUnknown() { return waitingDelete; }
    @Override public void close() { finish(Status.Canceled); }
    private static JSONObject object(byte[] value) {
        try { return new JSONObject(new String(value, StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalArgumentException("InvalidPruneMetadata", error); }
    }
}
