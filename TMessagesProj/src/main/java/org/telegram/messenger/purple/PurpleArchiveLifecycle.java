package org.telegram.messenger.purple;

import androidx.collection.LongSparseArray;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.MessagesStorage;

public final class PurpleArchiveLifecycle {
    static final class Event {
        final PurpleAccountFiles.Identity identity;
        final long selection;
        final boolean expiry, whole, pending;
        final String source, order;
        final int lifetime;
        Event(PurpleAccountFiles.Identity identity, boolean expiry, boolean whole) { this(identity, expiry, whole, PurpleArchiveViewerModel.selectionGeneration()); }
        Event(PurpleAccountFiles.Identity identity, boolean expiry, boolean whole, long selection) { this(identity, expiry, whole, selection, false, null, null, 0); }
        Event(PurpleAccountFiles.Identity identity, boolean expiry, boolean whole, long selection, boolean pending, String source, String order, int lifetime) {
            this.identity = identity; this.expiry = expiry; this.whole = whole; this.selection = selection;
            this.pending = pending; this.source = source; this.order = order; this.lifetime = lifetime;
        }
        boolean current() { return identity.current() && selection == PurpleArchiveViewerModel.selectionGeneration(); }
    }
    private static final LinkedHashMap<String, Event> causes = new LinkedHashMap<>();
    private static String address(int slot, long dialog, int mid) { return slot + ":" + dialog + ":" + mid; }
    private static synchronized void register(Event event, long dialog, int mid) {
        if (!event.current()) return;
        if (causes.size() >= 512) { causes.remove(causes.keySet().iterator().next()); PurpleArchive.captureGap(event.identity, "lifecycle_cause_overflow"); }
        causes.put(address(event.identity.slot, dialog, mid), event);
    }
    static synchronized Event cause(int slot, long dialog, int mid) { return causes.get(address(slot, dialog, mid)); }
    static synchronized void revoke(int slot) { causes.entrySet().removeIf(e -> slot < 0 || e.getValue().identity.slot == slot); }
    public static void expiry(int slot, MessagesStorage storage, LongSparseArray<ArrayList<Integer>> messages, LongSparseArray<ArrayList<Integer>> media) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null || android.os.Build.VERSION.SDK_INT < 23) return;
        ArrayList<long[]> work = new ArrayList<>();
        collecting: for (int kind = 0; kind < 2; ++kind) {
            LongSparseArray<ArrayList<Integer>> tasks = kind == 0 ? messages : media;
            if (tasks == null) continue;
            for (int i = 0; i < tasks.size(); ++i) for (int mid : tasks.valueAt(i)) {
                if (work.size() == 64) { PurpleArchive.captureGap(identity, "lifecycle_batch_overflow"); break collecting; }
                Event event = new Event(identity, true, kind == 0); register(event, tasks.keyAt(i), mid);
                work.add(new long[]{tasks.keyAt(i), mid});
            }
        }
        storage.getStorageQueue().postRunnable(() -> {
            long deadline = System.nanoTime() + 250_000_000L;
            for (long[] item : work) {
                Event event = cause(slot, item[0], (int) item[1]);
                if (event != null) preimage(event, storage.getDatabase(), item[0], (int) item[1], deadline);
            }
        });
    }
    public static void scheduled(int slot, SQLiteDatabase database, long dialog, int mid) {
        ArrayList<Integer> ids = new ArrayList<>(); ids.add(mid); scheduledIds(slot, database, dialog, ids);
    }
    public static void scheduled(int slot, SQLiteDatabase database, long dialog, android.util.SparseArray<ArrayList<Integer>> tasks) {
        ArrayList<Integer> ids = new ArrayList<>();
        collecting: for (int i = 0; i < tasks.size(); ++i) for (int mid : tasks.valueAt(i)) {
            if (ids.size() == 64) break collecting;
            ids.add(mid);
        }
        scheduledIds(slot, database, dialog, ids);
        if (ids.size() == 64) { PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot); if (identity != null) PurpleArchive.captureGap(identity, "lifecycle_schedule_limit"); }
    }
    private static void scheduledIds(int slot, SQLiteDatabase database, long dialog, ArrayList<Integer> ids) {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot); if (identity == null) return;
        long deadline = System.nanoTime() + 250_000_000L;
        for (int mid : ids) PurpleArchiveCapture.delete(identity, database, dialog, mid, PurpleArchiveOrigin.baseline(), false, deadline);
    }
    public static void beforeMediaEmpty(int slot, SQLiteDatabase database, long dialog, ArrayList<Integer> ids) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null || android.os.Build.VERSION.SDK_INT < 23 || ids == null) return;
        long deadline = System.nanoTime() + 250_000_000L;
        for (int i = 0; i < Math.min(64, ids.size()); ++i) {
            Event event = cause(slot, dialog, ids.get(i));
            if (event == null) PurpleArchive.captureGap(identity, "lifecycle_cause_missing");
            else preimage(event, database, dialog, ids.get(i), deadline);
        }
        if (ids.size() > 64) PurpleArchive.captureGap(identity, "lifecycle_batch_overflow");
    }
    public static void pending(int slot, MessagesStorage storage, long task, long dialog, int mid, boolean expiry) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null || android.os.Build.VERSION.SDK_INT < 23) return;
        Event event = new Event(identity, expiry, false, PurpleArchiveViewerModel.selectionGeneration(), true, null, null, 0); register(event, dialog, mid);
        storage.getStorageQueue().postRunnable(() -> {
            long deadline = System.nanoTime() + 250_000_000L;
            try {
                PurpleArchiveCapture.Input input = PurpleArchiveCapture.stored(identity, storage.getDatabase(), dialog, mid, PurpleArchiveOrigin.baseline(), false);
                if (input == null || input.body == null) throw new PurpleArchiveStore.Failure("Archive lifecycle preimage missing");
                PurpleArchive.runCapture(identity, 2L * (input.key.length() + input.body.length()), (worker, active) -> {
                    BooleanSupplier valid = () -> active.getAsBoolean() && event.current();
                    PurpleArchiveStore store = worker.store; String token = token(store, task);
                    PurpleArchiveFiles.Directory directory = store.directory.child("lifecycle", true);
                    PurpleArchiveFiles.Snapshot old = directory.read(token, 65536);
                    JSONObject value;
                    if (old.bytes != null) value = verify(store, token, old.bytes, dialog, mid);
                    else {
                        value = pendingValue(store, input, event, dialog, mid, valid);
                        byte[] frame = PurpleArchiveCore.unbase64(PurpleArchiveCore.call("journalSeal", store.arguments().put("token", token)
                            .put("bytes", PurpleArchiveCore.base64(value.toString().getBytes(StandardCharsets.UTF_8))), store.crypto()).getString("bytes"));
                        synchronized (store.environment.publicationLock()) {
                            if (!valid.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive lifecycle revoked");
                            store.requireCurrent(); directory.publish(token, old, frame);
                        }
                    }
                    if (valid.getAsBoolean()) register(bound(event, value), dialog, mid);
                }, deadline);
            } catch (Exception | LinkageError e) { PurpleArchive.captureGap(identity, "lifecycle_pending_failed"); }
        });
    }
    static JSONObject pendingValue(PurpleArchiveStore store, PurpleArchiveCapture.Input input, Event event, long dialog, int mid, BooleanSupplier active) throws Exception {
        if (!active.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive lifecycle revoked");
        PurpleArchiveSequence.capture(store, input, active); PurpleArchiveInline.capture(store, input, active);
        JSONObject body = new JSONObject(input.body);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, new JSONObject(input.key), null)) {
            String hash = PurpleArchiveCore.call("snapshot", store.arguments().put("key", slice.key).put("body", body), store.crypto()).getString("hash");
            if (slice.head == null || !slice.facts.getString("hash").equals(hash) || slice.head.getJSONArray("media").length() == 0)
                throw new PurpleArchiveStore.Failure("Archive lifecycle original preimage differs");
            JSONObject source = PurpleArchiveStaging.source(store, slice, slice.head.getJSONArray("media").getJSONObject(0).getString("identity"));
            return new JSONObject().put("dialog", Long.toString(dialog)).put("mid", mid).put("expiry", event.expiry)
                .put("account", store.binding.account).put("store", store.binding.store).put("generation", store.binding.generation)
                .put("source", source).put("order", slice.facts.getString("order")).put("lifetime", lifetime(slice.key, body));
        }
    }
    private static Event bound(Event opening, JSONObject value) throws Exception {
        return new Event(opening.identity, value.getBoolean("expiry"), false, opening.selection, true,
            value.getJSONObject("source").toString(), value.getString("order"), value.getInt("lifetime"));
    }
    public static void replay(int slot, MessagesStorage storage, long task, long dialog, int mid) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null || android.os.Build.VERSION.SDK_INT < 23) return;
        Event opening = new Event(identity, false, false, PurpleArchiveViewerModel.selectionGeneration(), true, null, null, 0);
        storage.getStorageQueue().postRunnable(() -> {
            java.util.concurrent.atomic.AtomicReference<Event> admitted = new java.util.concurrent.atomic.AtomicReference<>();
            long deadline = System.nanoTime() + 250_000_000L;
            register(opening, dialog, mid);
            PurpleArchive.runCapture(identity, 0, (worker, active) -> {
                if (!opening.current() || !active.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive lifecycle replay revoked");
                PurpleArchiveStore store = worker.store; String token = token(store, task);
                byte[] bytes = store.directory.child("lifecycle", true).read(token, 65536).bytes;
                if (bytes == null) throw new PurpleArchiveStore.Failure("Archive lifecycle replay cause missing");
                JSONObject value = verify(store, token, bytes, dialog, mid);
                if (active.getAsBoolean() && opening.current()) {
                    Event event = bound(opening, value); admitted.set(event); register(event, dialog, mid);
                }
            }, deadline);
            Event event = admitted.get();
            if (event != null) preimage(event, storage.getDatabase(), dialog, mid, deadline);
            else PurpleArchive.captureGap(identity, "lifecycle_replay_unknown");
        });
    }
    private static String token(PurpleArchiveStore store, long task) throws Exception {
        return PurpleArchiveCore.call("causeToken", store.arguments().put("task", Long.toString(task)), store.crypto()).getString("token");
    }
    private static JSONObject verify(PurpleArchiveStore store, String token, byte[] bytes, long dialog, int mid) throws Exception {
        JSONObject opened = PurpleArchiveCore.call("journalOpen", store.arguments().put("token", token).put("bytes", PurpleArchiveCore.base64(bytes)), store.crypto());
        JSONObject value = PurpleArchiveBinding.json(PurpleArchiveCore.unbase64(opened.getString("bytes")));
        if (!value.getString("account").equals(store.binding.account) || !value.getString("store").equals(store.binding.store)
            || !value.getString("generation").equals(store.binding.generation) || !value.getString("dialog").equals(Long.toString(dialog)) || value.getInt("mid") != mid)
            throw new PurpleArchiveStore.Failure("Archive lifecycle cause scope changed");
        JSONObject source = value.getJSONObject("source"), epoch = source.getJSONObject("epoch");
        if (!epoch.getString("account").equals(store.binding.account) || !epoch.getString("store").equals(store.binding.store)
            || !epoch.getString("generation").equals(store.binding.generation) || epoch.getBoolean("removalPending")
            || value.getInt("lifetime") < 1 || value.getInt("lifetime") > 3)
            throw new PurpleArchiveStore.Failure("Archive lifecycle original source scope changed");
        PurpleArchiveBinding.next(value.getString("order"));
        return value;
    }
    static void preimage(Event event, SQLiteDatabase database, long dialog, int mid, long deadline) {
        if (!event.current()) { PurpleArchive.captureGap(event.identity, "lifecycle_revoked"); return; }
        if (event.pending) {
            if (event.source == null) { PurpleArchive.captureGap(event.identity, "lifecycle_original_cause_missing"); return; }
            PurpleArchive.runCapture(event.identity, event.source.length() * 2L, (worker, active) -> {
                BooleanSupplier valid = () -> active.getAsBoolean() && event.current();
                applyBound(worker.store, event, valid); ++worker.deliveryEpoch;
            }, deadline);
            return;
        }
        PurpleArchiveCapture.delete(event.identity, database, dialog, mid,
            new PurpleArchiveOrigin.Cause(event.expiry ? 4 : 5, event.whole ? "whole_ttl" : "media", 0, "", event.selection), false, deadline);
    }
    static void captured(PurpleAccountFiles.Identity identity, String key, String body, PurpleArchiveOrigin.Cause cause, long deadline) {
        if (body == null) { PurpleArchive.captureGap(identity, "lifecycle_preimage_missing"); return; }
        Event event = new Event(identity, cause.origin == 4, cause.stream.equals("whole_ttl"), cause.selection);
        PurpleArchive.runCapture(identity, 2L * (body.length() + key.length()), (worker, active) -> {
            BooleanSupplier valid = () -> active.getAsBoolean() && event.current();
            if (!valid.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive lifecycle revoked");
            apply(worker.store, key, body, event.expiry, event.whole, valid); ++worker.deliveryEpoch;
        }, deadline);
    }
    private static int lifetime(JSONObject key, JSONObject body) throws Exception {
        return key.getJSONObject("chat").getInt("kind") == 3 ? 3
            : body.getJSONObject("context").optInt("ttl") == Integer.MAX_VALUE || body.getJSONObject("context").optInt("mediaTtl") == Integer.MAX_VALUE ? 2 : 1;
    }
    static void applyBound(PurpleArchiveStore store, Event event, BooleanSupplier active) throws Exception {
        if (!active.getAsBoolean() || !event.current() || event.source == null) throw new PurpleArchiveStore.Failure("Archive lifecycle original cause revoked");
        JSONObject source = new JSONObject(event.source);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, source.getJSONObject("key"), null)) {
            String id = source.getString("version") + ":lifecycle";
            if (slice.receipt(id)) return;
            JSONObject fact = new JSONObject().put("source", source).put("lifetime", event.lifetime).put("transition", event.expiry ? 0 : 1)
                .put("available", 0).put("destroys", true).put("event", id).put("order", PurpleArchiveBinding.next(event.order))
                .put("observedAt", Long.toString(System.currentTimeMillis() / 1000));
            JSONObject plan = PurpleArchiveCore.call("mediaLifecycle", store.arguments().put("rows", slice.rows).put("fact", fact), store.crypto());
            if (!active.getAsBoolean() || !store.execute(new PurpleArchiveCore.Program(plan), active).committed)
                throw new PurpleArchiveStore.Failure("Archive lifecycle original version refused");
            if (slice.head != null) for (int i = 0; i < slice.head.getJSONArray("media").length(); ++i)
                if (slice.head.getJSONArray("media").getJSONObject(i).getInt("state") != 2) {
                    PurpleArchiveGaps.record(store, "lifecycle_media_unavailable", System.currentTimeMillis() / 1000); break;
                }
        }
    }
    static void apply(PurpleArchiveStore store, String keyText, String bodyText, boolean expiry, boolean whole, BooleanSupplier active) throws Exception {
        JSONObject key = new JSONObject(keyText), body = new JSONObject(bodyText);
        String observedHash = PurpleArchiveCore.call("snapshot", store.arguments().put("key", key).put("body", body), store.crypto()).getString("hash");
        boolean absent;
        try (PurpleArchiveSlice opening = new PurpleArchiveSlice(store, key, null)) {
            absent = opening.head == null;
            if (!absent && !opening.facts.getString("hash").equals(observedHash)) throw new PurpleArchiveStore.Failure("Archive lifecycle preimage is no longer the head");
        }
        if (absent) PurpleArchiveSequence.capture(store, new PurpleArchiveCapture.Input(keyText, bodyText, PurpleArchiveOrigin.baseline(), false), active);
        PurpleArchiveInline.capture(store, new PurpleArchiveCapture.Input(keyText, bodyText, PurpleArchiveOrigin.baseline(), false), active);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, key, null)) {
            if (slice.head == null) throw new PurpleArchiveStore.Failure("Archive lifecycle preimage missing");
            JSONObject facts = slice.facts;
            String expected = PurpleArchiveCore.call("snapshot", store.arguments().put("key", key).put("body", body), store.crypto()).getString("hash");
            if (!facts.getString("hash").equals(expected)) throw new PurpleArchiveStore.Failure("Archive lifecycle preimage differs");
            if (facts.getBoolean("deleted") && (slice.head.getInt("origin") == 4 || slice.head.getInt("origin") == 5)) return;
            String id = facts.getString("head") + ":lifecycle";
            if (slice.receipt(id)) return;
            String order = PurpleArchiveBinding.next(facts.getString("order"));
            JSONArray media = slice.head.getJSONArray("media"); JSONObject plan; boolean missing = false;
            if (media.length() == 0) {
                if (!expiry || !whole || body.getInt("completeness") != 1) throw new PurpleArchiveStore.Failure("Archive body-only lifecycle unsupported");
                JSONObject event = new JSONObject().put("origin", 4).put("key", key).put("id", id).put("order", order)
                    .put("accountGeneration", facts.getString("accountGeneration")).put("chatGeneration", facts.getString("chatGeneration"))
                    .put("targetVersion", facts.getString("head")).put("targetRevision", facts.getString("revisionId")).put("expectedContentHash", expected)
                    .put("observedAt", Long.toString(System.currentTimeMillis() / 1000)).put("serverTime", "0");
                plan = PurpleArchiveCore.call("capture", store.arguments().put("rows", slice.rows).put("event", event), store.crypto());
            } else {
                JSONObject item = media.getJSONObject(0); JSONObject source = PurpleArchiveStaging.source(store, slice, item.getString("identity"));
                int lifetime = lifetime(key, body);
                JSONObject fact = new JSONObject().put("source", source).put("lifetime", lifetime).put("transition", expiry ? 0 : 1)
                    .put("available", 0).put("destroys", true).put("event", id).put("order", order).put("observedAt", Long.toString(System.currentTimeMillis() / 1000));
                plan = PurpleArchiveCore.call("mediaLifecycle", store.arguments().put("rows", slice.rows).put("fact", fact), store.crypto());
                for (int i = 0; i < media.length(); ++i) if (media.getJSONObject(i).getInt("state") != 2)
                    missing = true;
            }
            if (!active.getAsBoolean() || !store.execute(new PurpleArchiveCore.Program(plan), active).committed)
                throw new PurpleArchiveStore.Failure("Archive lifecycle commit refused");
            if (missing) PurpleArchiveGaps.record(store, "lifecycle_media_unavailable", System.currentTimeMillis() / 1000);
        }
    }
    private PurpleArchiveLifecycle() {}
}
