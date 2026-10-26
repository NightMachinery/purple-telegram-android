package org.telegram.messenger.purple;

import java.util.ArrayList;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveCapture {
    static final class Input {
        final String key, body;
        final PurpleArchiveOrigin.Cause cause;
        final boolean preimage;
        final PurpleArchiveIngress.Ticket ingress;
        Input(String key, String body, PurpleArchiveOrigin.Cause cause, boolean preimage) {
            this.key = key; this.body = body; this.cause = cause; this.preimage = preimage;
            ingress = cause.ingress == null ? PurpleArchiveIngress.key(key) : cause.ingress;
        }
    }
    public static PurpleArchiveOrigin.Batch observations(int slot, TLRPC.messages_Messages messages, int mode) {
        return observations(slot, messages == null ? null : messages.messages, mode);
    }
    public static PurpleArchiveOrigin.Batch observations(int slot, ArrayList<TLRPC.Message> messages, int mode) {
        PurpleArchiveOrigin.Batch batch = new PurpleArchiveOrigin.Batch(slot);
        if (mode != 0 || messages == null) return batch;
        for (TLRPC.Message message : messages) {
            if (message == null || (message.flags & 1073741824) != 0 || message.quick_reply_shortcut != null
                || message.id <= 0 && !DialogObject.isEncryptedDialog(message.dialog_id)) continue;
            batch.add(message, PurpleArchiveOrigin.baseline(), false);
        }
        return batch;
    }
    public static void beforeWrite(int slot, SQLiteDatabase database, PurpleArchiveOrigin.Batch batch) { beforeWrite(slot, database, batch, 0); }
    public static void beforeWrite(int slot, SQLiteDatabase database, PurpleArchiveOrigin.Batch batch, long onlyDialog) {
        if (batch == null || batch.identity == null || batch.identity.slot != slot || !batch.identity.current()) return;
        if (batch.refused) { PurpleArchive.captureGap(batch.identity, "capture_overflow"); return; }
        ArrayList<Input> inputs = new ArrayList<>(); long lifecycleDeadline = System.nanoTime() + 250_000_000L;
        try {
            for (Input input : batch.inputs) {
                boolean replacement = new JSONObject(input.body).getJSONObject("context").optBoolean("expiredReplacement");
                boolean replaced = false;
                JSONObject key = new JSONObject(input.key); JSONObject chat = key.getJSONObject("chat");
                long dialog = chat.getInt("kind") == 3 ? DialogObject.makeEncryptedDialogId(Long.parseLong(chat.getString("id")))
                    : Long.parseLong(chat.getString("id")) * (chat.getInt("kind") == 0 ? 1 : -1);
                if (onlyDialog != 0 && dialog != onlyDialog) continue;
                long id = Long.parseLong(key.getString("messageId"));
                String sql = chat.getInt("kind") == 3
                    ? "SELECT m.data FROM messages_v2 m JOIN randoms_v2 r ON r.mid=m.mid AND r.uid=m.uid WHERE r.random_id=? AND m.uid=? LIMIT 2"
                    : "SELECT data FROM messages_v2 WHERE mid=? AND uid=? LIMIT 2";
                try (Cursor close = new Cursor(database.queryFinalized(sql, id, dialog))) {
                    SQLiteCursor cursor = close.cursor;
                    if (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data != null) {
                            byte[] raw = PurpleArchiveNormalize.storedBytes(data); TLRPC.Message old;
                            try { old = raw.length < 4 ? null : TLRPC.Message.TLdeserialize(data, data.readInt32(true), true); }
                            catch (RuntimeException e) { old = null; }
                            finally { data.reuse(); }
                            JSONObject body;
                            if (old != null) {
                                if (chat.getInt("kind") != 3 && old.id != id) throw new PurpleArchiveStore.Failure("Archive preimage identity mismatch");
                                old.dialog_id = dialog;
                                if (chat.getInt("kind") == 3) old.random_id = id;
                                body = PurpleArchiveNormalize.body(old).put("payload", PurpleArchiveCore.base64(raw));
                            } else body = PurpleArchiveNormalize.opaqueBody(raw);
                            if (replacement) {
                                PurpleArchiveLifecycle.captured(batch.identity, input.key, body.toString(),
                                    new PurpleArchiveOrigin.Cause(4, "media", 0, "", input.cause.selection).bind(input.ingress), lifecycleDeadline); replaced = true;
                            } else inputs.add(new Input(input.key, body.toString(), PurpleArchiveOrigin.baseline().bind(input.ingress), true));
                        }
                        if (cursor.next()) throw new PurpleArchiveStore.Failure("Archive preimage ambiguous");
                    }
                }
                if (!replacement) inputs.add(input);
                else if (!replaced) PurpleArchive.captureGap(batch.identity, "lifecycle_replacement_preimage_missing");
            }
            PurpleArchive.capture(batch.identity, inputs);
        } catch (Exception | LinkageError e) { PurpleArchive.captureGap(batch.identity, "capture_preimage_failed"); }
    }
    public static void beforeDelete(int slot, SQLiteDatabase database, long dialog, ArrayList<Integer> messages, PurpleArchiveOrigin.Batch batch) {
        if (batch == null || batch.identity == null || batch.identity.slot != slot || !batch.identity.current()) return;
        if (batch.refused || messages == null || messages.size() > 64) { PurpleArchive.captureGap(batch.identity, "capture_overflow"); return; }
        long deadline = System.nanoTime() + 250_000_000L;
        for (int mid : messages) {
            PurpleArchiveOrigin.Cause cause = batch.deletes.get(dialog + ":" + mid);
            if (cause != null) delete(batch.identity, database, dialog, mid, cause, false, deadline);
        }
    }
    public static void localDelete(int slot, MessagesStorage storage, long dialog, ArrayList<Integer> ids, boolean eligible) {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (!eligible || identity == null || ids == null) return;
        if (ids.size() > 64) { PurpleArchive.captureGap(identity, "capture_overflow"); return; }
        ArrayList<Integer> copy = new ArrayList<>(ids); PurpleArchiveOrigin.Cause cause = PurpleArchiveOrigin.local().bind(PurpleArchiveIngress.capture(identity.key));
        storage.getStorageQueue().postRunnable(() -> {
            if (!identity.current()) return;
            long deadline = System.nanoTime() + 250_000_000L;
            for (int mid : copy) {
                PurpleArchiveLifecycle.Event lifecycle = PurpleArchiveLifecycle.cause(slot, dialog, mid);
                if (lifecycle == null) delete(identity, storage.getDatabase(), dialog, mid, cause, false, deadline);
                else PurpleArchiveLifecycle.preimage(lifecycle, storage.getDatabase(), dialog, mid, deadline);
            }
        });
    }
    public static void secretDelete(int slot, MessagesStorage storage, long chat, long service, ArrayList<Long> ids) {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null || ids == null || ids.size() > 64) { if (identity != null) PurpleArchive.captureGap(identity, "capture_overflow"); return; }
        ArrayList<Long> copy = new ArrayList<>(ids);
        PurpleArchiveOrigin.Cause cause = new PurpleArchiveOrigin.Cause(2, "secret_" + chat, 0, "secret_" + service).bind(PurpleArchiveIngress.capture(identity.key));
        storage.getStorageQueue().postRunnable(() -> {
            if (!identity.current()) return;
            long deadline = System.nanoTime() + 250_000_000L;
            for (long random : copy) delete(identity, storage.getDatabase(), DialogObject.makeEncryptedDialogId(chat), random, cause, true, deadline);
        });
    }
    static Input stored(PurpleAccountFiles.Identity identity, SQLiteDatabase database, long dialog, long id,
        PurpleArchiveOrigin.Cause cause, boolean random) throws Exception {
        boolean localSecret = !random && DialogObject.isEncryptedDialog(dialog);
        String sql = localSecret ? "SELECT m.uid,m.data,r.random_id,m.ttl FROM messages_v2 m JOIN randoms_v2 r ON r.mid=m.mid AND r.uid=m.uid WHERE m.mid=? AND m.uid=? LIMIT 2" : random ? "SELECT m.uid,m.data,m.ttl FROM messages_v2 m JOIN randoms_v2 r ON r.mid=m.mid AND r.uid=m.uid WHERE r.random_id=? AND m.uid=? LIMIT 2"
            : dialog == 0 ? "SELECT uid,data,ttl FROM messages_v2 WHERE mid=? AND is_channel=0 LIMIT 2"
            : "SELECT uid,data,ttl FROM messages_v2 WHERE mid=? AND uid=? LIMIT 2";
        SQLiteCursor query = dialog == 0 && !random ? database.queryFinalized(sql, id) : database.queryFinalized(sql, id, dialog);
        JSONObject key = null; String body = null;
        try (Cursor close = new Cursor(query)) {
            if (query.next()) {
                long actual = query.longValue(0); NativeByteBuffer data = query.byteBufferValue(1);
                if (data != null) {
                    byte[] raw = PurpleArchiveNormalize.storedBytes(data); TLRPC.Message old;
                    try { old = raw.length < 4 ? null : TLRPC.Message.TLdeserialize(data, data.readInt32(true), true); }
                    catch (RuntimeException e) { old = null; }
                    finally { data.reuse(); }
                    if (old != null) {
                        old.dialog_id = actual;
                        if (old.ttl == 0) old.ttl = query.intValue(localSecret ? 3 : 2);
                        if (random) old.random_id = id;
                        else if (localSecret) old.random_id = query.longValue(2);
                        key = PurpleArchiveNormalize.key(identity.key, old); body = PurpleArchiveNormalize.body(old).put("payload", PurpleArchiveCore.base64(raw)).toString();
                    } else {
                        int kind = random || localSecret ? 3 : cause.stream.startsWith("channel_") ? 2 : actual > 0 ? 0 : dialog == 0 ? 1 : -1;
                        if (kind >= 0) {
                            long stable = localSecret ? query.longValue(2) : id;
                            key = PurpleArchiveNormalize.key(identity.key, kind, kind == 3 ? DialogObject.getEncryptedChatId(actual) : Math.abs(actual), stable);
                            body = PurpleArchiveNormalize.opaqueBody(raw).toString();
                        }
                    }
                }
                if (query.next()) throw new PurpleArchiveStore.Failure("Archive delete ambiguous");
            }
        }
        return key == null ? null : new Input(key.toString(), body, cause, false);
    }
    static void delete(PurpleAccountFiles.Identity identity, SQLiteDatabase database, long dialog, long id,
        PurpleArchiveOrigin.Cause cause, boolean random, long deadline) {
        if (!identity.current()) return;
        if (System.nanoTime() >= deadline) { PurpleArchive.captureGap(identity, "capture_timeout"); return; }
        ArrayList<Input> inputs = new ArrayList<>();
        try {
            Input stored = stored(identity, database, dialog, id, cause, random);
            JSONObject key = stored == null ? null : new JSONObject(stored.key); String body = stored == null ? null : stored.body;
            if (cause.origin == 0) {
                if (key != null && body != null) {
                    inputs.add(new Input(key.toString(), body, cause, false)); PurpleArchive.capture(identity, inputs, deadline);
                } else PurpleArchive.captureGap(identity, "lifecycle_schedule_preimage_missing");
                return;
            }
            if (cause.origin == 4 || cause.origin == 5) {
                if (key == null) PurpleArchive.captureGap(identity, "lifecycle_identity_missing");
                else PurpleArchiveLifecycle.captured(identity, key.toString(), body, cause, deadline);
                return;
            }
            if (key != null) {
                if (body != null) inputs.add(new Input(key.toString(), body, PurpleArchiveOrigin.baseline().bind(cause.ingress), true));
                inputs.add(new Input(key.toString(), null, cause, false)); PurpleArchive.capture(identity, inputs, deadline);
            } else if (random || DialogObject.isEncryptedDialog(dialog)) {
                if (random) {
                    key = PurpleArchiveNormalize.key(identity.key, 3, DialogObject.getEncryptedChatId(dialog), id);
                    inputs.add(new Input(key.toString(), null, cause, false)); PurpleArchive.capture(identity, inputs, deadline);
                } else PurpleArchive.captureGap(identity, "capture_identity_missing");
            } else if (dialog > 0) {
                key = PurpleArchiveNormalize.key(identity.key, 0, dialog, id);
                inputs.add(new Input(key.toString(), null, cause, false)); PurpleArchive.capture(identity, inputs, deadline);
            } else if (dialog < 0 && cause.stream.startsWith("channel_")) {
                key = PurpleArchiveNormalize.key(identity.key, 2, -dialog, id);
                inputs.add(new Input(key.toString(), null, cause, false)); PurpleArchive.capture(identity, inputs, deadline);
            } else PurpleArchive.captureLookup(identity, dialog, id, cause, deadline);
        } catch (Exception | LinkageError e) { PurpleArchive.captureGap(identity, "capture_preimage_failed"); }
    }
    private static final class Cursor implements AutoCloseable {
        final SQLiteCursor cursor;
        Cursor(SQLiteCursor cursor) { this.cursor = cursor; }
        @Override public void close() { cursor.dispose(); }
    }
    private PurpleArchiveCapture() {}
}
