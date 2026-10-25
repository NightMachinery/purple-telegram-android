package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveCaptureCases {
    private static int checks;
    private static PurpleArchive.Worker worker;
    private static void check(boolean value, String message) { ++checks; if (!value) throw new AssertionError(message); }
    private static <T> T on(Callable<T> call) throws Exception { return worker.queue.submit(call).get(20, TimeUnit.SECONDS); }
    private static TLRPC.Message message(int id, String text) {
        TLRPC.TL_message value = new TLRPC.TL_message(); value.id = id; value.message = text; value.date = 100; value.edit_date = 100;
        value.peer_id = new TLRPC.TL_peerUser(); value.peer_id.user_id = 55; value.dialog_id = 55;
        value.from_id = new TLRPC.TL_peerUser(); value.from_id.user_id = 44; value.flags = 256;
        return value;
    }
    private static PurpleArchiveCapture.Input input(TLRPC.Message message, int origin, int pts) throws Exception {
        return new PurpleArchiveCapture.Input(PurpleArchiveNormalize.key("0-11", message).toString(), origin == 2 || origin == 3 ? null : PurpleArchiveNormalize.body(message).toString(),
            new PurpleArchiveOrigin.Cause(origin, pts == 0 ? "" : "global", pts, origin == 3 ? "local_action" : ""), false);
    }
    private static String apply(PurpleArchiveCapture.Input input) throws Exception { return on(() -> PurpleArchiveSequence.capture(worker.store, input, () -> true)); }
    private static JSONObject facts(TLRPC.Message message) throws Exception {
        return on(() -> { try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, PurpleArchiveNormalize.key("0-11", message), "")) { return slice.facts; } });
    }
    private static int count(TLRPC.Message message) throws Exception {
        return on(() -> {
            JSONObject query = PurpleArchiveCore.call("query", worker.store.arguments().put("query", "versions")
                .put("key", PurpleArchiveNormalize.key("0-11", message)).put("limit", 128), worker.store.crypto());
            try (PurpleArchiveStore.Reader reader = worker.store.reader()) { return Math.toIntExact(reader.read(new PurpleArchiveCore.Statement(query.getJSONObject("statement"))).rows); }
        });
    }
    private static void drain() {
        ArrayList<Runnable> tasks;
        synchronized (AndroidUtilities.class) { tasks = new ArrayList<>(AndroidUtilities.pending); AndroidUtilities.pending.clear(); }
        for (Runnable task : tasks) task.run();
    }
    private static void await(java.util.function.BooleanSupplier ready, String message) throws Exception {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < end) { drain(); Thread.sleep(5); }
        check(ready.getAsBoolean(), message);
    }
    private static PurpleArchiveViewerModel.Page page(PurpleArchiveViewerModel viewer) throws Exception {
        AtomicReference<PurpleArchiveViewerModel.Page> result = new AtomicReference<>(); viewer.next(result::set);
        await(() -> result.get() != null, "actual asynchronous viewer page delivered"); return result.get();
    }
    private static void normalization() throws Exception {
        TLRPC.Message value = message(77, "first");
        JSONObject a = PurpleArchiveNormalize.body(value);
        value.views = 500; value.send_state = 3;
        JSONObject b = PurpleArchiveNormalize.body(value);
        String key = PurpleArchiveNormalize.key("0-11", value).toString();
        String first = on(() -> PurpleArchiveCore.call("snapshot", worker.store.arguments().put("key", new JSONObject(key)).put("body", a), worker.store.crypto()).getString("hash"));
        String refreshed = on(() -> PurpleArchiveCore.call("snapshot", worker.store.arguments().put("key", new JSONObject(key)).put("body", b), worker.store.crypto()).getString("hash"));
        check(first.equals(refreshed), "views and send state are transport, not semantic edits");
        TLRPC.TL_photo photo = new TLRPC.TL_photo(); photo.id = 9000000001L; photo.access_hash = 4; photo.file_reference = new byte[]{1}; photo.date = 100; photo.dc_id = 2;
        TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto(); media.flags = 1; media.photo = photo; value.media = media; value.flags |= 512;
        JSONObject mediaBefore = PurpleArchiveNormalize.body(value); photo.file_reference = new byte[]{8, 9}; photo.dc_id = 4;
        TLRPC.TL_photoSizeEmpty quality = new TLRPC.TL_photoSizeEmpty(); quality.type = "s"; photo.sizes.add(quality);
        JSONObject mediaAfter = PurpleArchiveNormalize.body(value);
        String mediaHash = on(() -> PurpleArchiveCore.call("snapshot", worker.store.arguments().put("key", new JSONObject(key)).put("body", mediaBefore), worker.store.crypto()).getString("hash"));
        check(mediaHash.equals(on(() -> PurpleArchiveCore.call("snapshot", worker.store.arguments().put("key", new JSONObject(key)).put("body", mediaAfter), worker.store.crypto()).getString("hash"))), "photo references DC and quality refresh do not change semantic body");
        value.media = null; value.flags &= ~512;

        TLRPC.Message inbound = message(79, "inbound"); inbound.dialog_id = 0; inbound.out = false; inbound.peer_id.user_id = 11; inbound.from_id.user_id = 88;
        check(PurpleArchiveNormalize.key("0-11", inbound).getJSONObject("chat").getString("id").equals("88"), "uncached inbound private message uses sender dialog identity");
        inbound.out = true;
        check(PurpleArchiveNormalize.key("0-11", inbound).getJSONObject("chat").getString("id").equals("11"), "outgoing private message uses recipient dialog identity");
        inbound.dialog_id = 0; inbound.peer_id = new TLRPC.TL_peerChannel(); inbound.peer_id.channel_id = 9999999999L;
        check(PurpleArchiveNormalize.key("0-11", inbound).getJSONObject("chat").getInt("kind") == 2
            && PurpleArchiveNormalize.key("0-11", inbound).getJSONObject("chat").getString("id").equals("9999999999"), "wide channel identity remains typed and exact");

        TLRPC.TL_messageEntityBold bad = new TLRPC.TL_messageEntityBold(); bad.offset = 200; bad.length = 4; value.entities.add(bad);
        check(PurpleArchiveNormalize.body(value).getInt("completeness") == 0, "malformed entity range is explicitly partial");
        value.entities.clear(); value.dialog_id = org.telegram.messenger.DialogObject.makeEncryptedDialogId(7); value.random_id = Long.MIN_VALUE + 9; value.id = -100;
        JSONObject secret = PurpleArchiveNormalize.key("0-11", value);
        check(secret.getJSONObject("chat").getInt("kind") == 3 && secret.getString("messageId").equals(Long.toString(value.random_id)), "secret key uses stable signed random id");
        TLRPC.Message huge = message(78, new String(new char[10 * 1024 * 1024]).replace('\0', 'x'));
        boolean refused = false; try { PurpleArchiveNormalize.body(huge); } catch (Exception e) { refused = true; }
        check(refused, "10 MiB source is explicitly refused before buffer allocation");
    }
    private static void causal() throws Exception {
        TLRPC.Message a = message(77, "A"), b = message(77, "B");
        check(apply(input(a, 0, 0)).equals("capture_committed"), "baseline commits through real core SQL");
        check(apply(input(b, 1, 10)).equals("capture_committed"), "accepted edit commits");
        check(apply(input(a, 1, 11)).equals("capture_committed") && count(a) == 3, "A-B-A in the same second retains three bodies");
        String order = facts(a).getString("order");
        check(apply(input(a, 1, 11)).equals("capture_stale") && facts(a).getString("order").equals(order), "replay does not allocate a fresh order");
        check(apply(input(b, 1, 9)).equals("capture_stale") && count(a) == 3, "older accepted update cannot regress head");
        TLRPC.Message c = message(77, "C");
        check(apply(input(c, 0, 0)).equals("capture_committed") && count(c) == 4, "changed refresh is a labelled observation");
        check(apply(input(b, 1, 10)).equals("capture_stale") && count(c) == 4, "server progress survives an intervening local observation");
        c.views = 1000; c.send_state = 1;
        check(apply(input(c, 0, 0)).equals("capture_unchanged") && count(c) == 4, "quality/send refresh produces no edit");
        c.edit_date = 99; c.message = "older refresh";
        check(apply(input(c, 0, 0)).equals("capture_stale") && count(c) == 4, "older refresh is refused");
        for (int i = 0; i < 40; ++i) apply(input(message(77, "body " + i), 1, 20 + i));
        PurpleArchiveViewerModel viewer = new PurpleArchiveViewerModel(0, a);
        PurpleArchiveViewerModel.Page first = page(viewer), second = page(viewer);
        check(first.error.isEmpty() && first.versions.size() == 32 && first.more, "viewer first page uses bounded core cursor");
        check(second.error.isEmpty() && second.versions.size() == 12 && !second.more, "same-second order cursor neither loses nor repeats history");
        check(first.versions.get(0).origin == 1 && second.versions.get(11).origin == 0, "viewer preserves authoritative versus observed labels");
        on(() -> { PurpleArchiveFaults.set(6); return null; });
        viewer.close(); on(() -> null); on(() -> null);
        check(on(() -> worker.store.current()), "failed reader close is retried without killing the account owner");
        PurpleArchiveViewerModel stale = new PurpleArchiveViewerModel(0, a); AtomicReference<PurpleArchiveViewerModel.Page> late = new AtomicReference<>();
        stale.next(late::set); on(() -> null);
        ArrayList<PurpleArchiveCapture.Input> next = new ArrayList<>(); next.add(input(message(77, "newer"), 1, 80));
        long writeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { PurpleArchive.capture(PurpleAccountFiles.capture(0), next); on(() -> null); }
        while (count(a) < 45 && System.nanoTime() < writeDeadline);
        check(count(a) == 45, "write revoking queued delivery commits before UI drain"); drain();
        check(late.get() == null, "completion queued before a write cannot deliver after revision revocation"); stale.close(); on(() -> null);
        check(apply(input(a, 2, 81)).equals("capture_committed") && facts(a).getBoolean("deleted"), "delete after cache eviction uses retained exact body");
        check(count(a) == 45, "deletion does not erase retained versions");
        PurpleArchiveViewerModel deleted = new PurpleArchiveViewerModel(0, a); check(page(deleted).versions.size() == 32, "deleted message history remains read-only and available"); deleted.close(); on(() -> null);
    }
    private static void payloads() throws Exception {
        for (int size : new int[]{50 * 1024, 1024 * 1024}) {
            TLRPC.Message message = message(size, new String(new char[size]).replace('\0', 'p'));
            byte[] raw = PurpleArchiveNormalize.bytes(message);
            check(apply(input(message, 0, 0)).equals("capture_committed"), "large exact payload commits " + size);
            on(() -> {
                try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, PurpleArchiveNormalize.key("0-11", message), "")) {
                    check(Arrays.equals(raw, PurpleArchiveCore.unbase64(slice.head.getJSONObject("observation").getString("payload"))), "chunked exact TL payload round trip " + size);
                }
                return null;
            });
        }
        TLRPC.Message missing = message(900, "unseen");
        check(apply(input(missing, 2, 100)).equals("capture_committed") && facts(missing).getBoolean("deleted") && count(missing) == 0,
            "known identity with no primary/preimage creates truthful bodyless tombstone");
        TLRPC.Message failure = message(901, "not retained");
        on(() -> { PurpleArchiveFaults.set(1); return null; });
        boolean refused = false; try { apply(input(failure, 0, 0)); } catch (Exception e) { refused = true; }
        check(refused && count(failure) == 0, "SQL failure never acknowledges a capture");
    }
    private static void hooks() throws Exception {
        TLRPC.Message old = message(1001, "preimage"), edited = message(1001, "accepted body");
        byte[] raw = PurpleArchiveNormalize.bytes(old);
        org.telegram.SQLite.SQLiteDatabase cache = new org.telegram.SQLite.SQLiteDatabase((sql, arguments) -> new Object[][]{{raw}});
        PurpleArchiveOrigin.Batch accepted = new PurpleArchiveOrigin.Batch(0); accepted.edit(edited, 1);
        edited.message = "producer changed later";
        PurpleArchiveCapture.beforeWrite(0, cache, accepted);
        check(cache.reads == 1 && count(old) == 2, "storage hook captures preimage before replacement");
        on(() -> {
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, PurpleArchiveNormalize.key("0-11", old), "")) {
                check(slice.head.getJSONObject("content").getString("text").equals("accepted body"), "accepted TL snapshot is immutable across producer mutation");
            }
            return null;
        });
        org.telegram.SQLite.SQLiteDatabase empty = new org.telegram.SQLite.SQLiteDatabase((sql, arguments) -> new Object[0][]);
        PurpleArchiveOrigin.Batch deletion = new PurpleArchiveOrigin.Batch(0); ArrayList<Integer> ids = new ArrayList<>(); ids.add(1001); deletion.delete(0, ids, 2);
        PurpleArchiveCapture.beforeDelete(0, empty, 0, ids, deletion);
        check(facts(old).getBoolean("deleted"), "production delete scanner recovers exact archived identity after eviction");
        TLRPC.Message ambiguousA = message(1002, "one"), ambiguousB = message(1002, "two");
        ambiguousB.dialog_id = 66; ambiguousB.peer_id.user_id = 66;
        apply(input(ambiguousA, 0, 0)); apply(input(ambiguousB, 0, 0));
        ids.clear(); ids.add(1002); deletion = new PurpleArchiveOrigin.Batch(0); deletion.delete(0, ids, 3);
        PurpleArchiveCapture.beforeDelete(0, empty, 0, ids, deletion);
        check(!facts(ambiguousA).getBoolean("deleted") && !facts(ambiguousB).getBoolean("deleted"), "ambiguous non-channel deletion never expands to all chats");
        TLRPC.Message corrupt = message(1800, "cache body unavailable"); ids.clear(); ids.add(1800);
        PurpleArchiveOrigin.Batch corruptDelete = new PurpleArchiveOrigin.Batch(0); corruptDelete.delete(0, ids, 200);
        byte[] opaque = new byte[]{1, 2};
        org.telegram.SQLite.SQLiteDatabase corruptCache = new org.telegram.SQLite.SQLiteDatabase((sql, arguments) -> new Object[][]{{55L, opaque}});
        PurpleArchiveCapture.beforeDelete(0, corruptCache, 0, ids, corruptDelete);
        check(facts(corrupt).getBoolean("deleted") && count(corrupt) == 1, "typed delete retains malformed cached TL as an opaque preimage");
        on(() -> {
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, PurpleArchiveNormalize.key("0-11", corrupt), "")) {
                check(Arrays.equals(opaque, PurpleArchiveCore.unbase64(slice.head.getJSONObject("observation").getString("payload"))), "opaque deleted preimage preserves exact original bytes");
            }
            return null;
        });
        TLRPC.Message quick = message(1099, "quick reply"); quick.flags |= 1073741824;
        ArrayList<TLRPC.Message> quickList = new ArrayList<>(); quickList.add(quick);
        check(PurpleArchiveCapture.observations(0, quickList, 0).inputs.isEmpty(), "embedded quick-reply identity is excluded even on default storage route");
        TLRPC.Message scheduled = message(1003, "scheduled"); ArrayList<TLRPC.Message> list = new ArrayList<>(); list.add(scheduled);
        PurpleArchiveCapture.beforeWrite(0, empty, PurpleArchiveCapture.observations(0, list, 1));
        check(count(scheduled) == 0, "scheduled collection is excluded at capture boundary");
        org.telegram.messenger.MessagesStorage storage = new org.telegram.messenger.MessagesStorage(empty);
        ids.clear(); ids.add(1002); PurpleArchiveCapture.localDelete(0, storage, 55, ids, false);
        check(storage.getStorageQueue().tasks.isEmpty() && !facts(ambiguousA).getBoolean("deleted"), "cache-only or scheduled move has no archive deletion cause");
        PurpleArchiveOrigin.Batch stale = new PurpleArchiveOrigin.Batch(0); stale.edit(message(1004, "old account"), 4);
        UserConfig.getInstance(0).user = 33; PurpleAccountFiles.identityChanged(0);
        PurpleArchiveCapture.beforeWrite(0, null, stale);
        check(stale.identity != null && !stale.identity.current(), "stale production batch refuses before touching storage after slot reuse");
        await(() -> worker.retired, "old slot worker retires before the same login is reopened");
        UserConfig.getInstance(0).user = 11; PurpleAccountFiles.identityChanged(0);
        PurpleArchive.refresh(0, () -> {}); await(() -> PurpleArchive.status(0).state.equals("Ready"), "same login archive reopens after slot close");
        worker = PurpleArchive.existing(PurpleAccountFiles.capture(0));
    }
    private static void hydration() throws Exception {
        TLRPC.Message partial = message(1100, "known text");
        TLRPC.TL_messageEntityBold bad = new TLRPC.TL_messageEntityBold(); bad.offset = 100; bad.length = 4; partial.entities.add(bad);
        apply(input(partial, 0, 0)); String order = facts(partial).getString("order");
        partial.entities.clear();
        TLRPC.TL_messageEntityBold good = new TLRPC.TL_messageEntityBold(); good.offset = 0; good.length = 5; partial.entities.add(good);
        check(apply(input(partial, 0, 0)).equals("capture_committed") && count(partial) == 1
            && facts(partial).getString("order").equals(order), "same-revision hydration fills declared partial content without adding an edit");
        on(() -> {
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, PurpleArchiveNormalize.key("0-11", partial), "")) {
                check(slice.head.getInt("completeness") == 1, "hydration retains complete head under exact version/revision/hash fences");
            }
            return null;
        });
    }
    private static void cancellation() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        worker.queue.execute(() -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); } });
        check(started.await(5, TimeUnit.SECONDS), "account queue blocker started");
        TLRPC.Message value = message(1200, "must not commit late"); ArrayList<PurpleArchiveCapture.Input> inputs = new ArrayList<>(); inputs.add(input(value, 0, 0));
        long start = System.nanoTime(); PurpleArchive.capture(PurpleAccountFiles.capture(0), inputs); long elapsed = System.nanoTime() - start;
        check(elapsed < 1_000_000_000L && worker.captureBytes.get() == 0 && PurpleArchive.status(0).gap, "timeout releases reservation immediately and exposes a gap");
        Object[] pending = worker.queue.getQueue().toArray(); boolean found = false;
        for (Object task : pending) if (task.getClass().getName().endsWith("CaptureTask")) {
            java.lang.reflect.Field field = task.getClass().getDeclaredField("action"); field.setAccessible(true);
            check(((AtomicReference<?>) field.get(task)).get() == null, "timed-out queued capture drops its heavy input closure"); found = true;
        }
        check(found, "bounded cancelled task remains as a lightweight queue entry"); release.countDown(); on(() -> null);
        check(count(value) == 0, "late cancelled task never applies success");
    }
    private static JSONObject pending(PurpleArchiveCapture.Input input, JSONObject facts, String id, String hash) throws Exception {
        String signature = PurpleArchiveNormalize.digest(new JSONObject().put("origin", input.cause.origin).put("hash", hash)
            .put("stream", input.cause.stream).put("pts", input.cause.pts).put("token", input.cause.token).toString());
        return new JSONObject().put("id", id).put("order", "1").put("origin", input.cause.origin).put("signature", signature)
            .put("hash", hash).put("editDate", 100).put("pts", input.cause.pts).put("stream", input.cause.stream).put("observedAt", "123")
            .put("accountGeneration", facts.getString("accountGeneration")).put("chatGeneration", facts.getString("chatGeneration"));
    }
    private static void cut(TLRPC.Message message, boolean committed, String id) throws Exception {
        PurpleArchiveCapture.Input input = input(message, 0, 0); JSONObject facts = facts(message);
        on(() -> {
            PurpleArchiveStore store = worker.store; JSONObject key = new JSONObject(input.key);
            String token = PurpleArchiveCore.call("query", store.arguments().put("query", "message").put("key", key), store.crypto()).getString("id");
            String hash = PurpleArchiveCore.call("snapshot", store.arguments().put("key", key).put("body", new JSONObject(input.body)), store.crypto()).getString("hash");
            JSONObject state = new JSONObject().put("version", 1).put("account", "0-11").put("store", store.binding.store).put("key", key)
                .put("order", "0").put("pts", 0).put("stream", "").put("lastEdit", 0).put("pending", pending(input, facts, id, hash));
            JSONObject sealed = PurpleArchiveCore.call("journalSeal", store.arguments().put("token", token)
                .put("bytes", PurpleArchiveCore.base64(PurpleArchiveCatalogue.bytes(state))), store.crypto());
            PurpleArchiveFiles.Directory causal = store.directory.child("causal", true);
            causal.publish(token + ".journal", causal.read(token + ".journal", 65536), PurpleArchiveCore.unbase64(sealed.getString("bytes")));
            JSONObject marker = new JSONObject().put("account", "0-11").put("store", store.binding.store).put("generation", store.binding.generation)
                .put("token", token).put("id", id).put("observedAt", "123");
            store.paths.metadata.publish("capture-inflight.json", store.paths.metadata.read("capture-inflight.json", 65536), PurpleArchiveCatalogue.bytes(marker));
            PurpleArchiveSequence.recover(store);
            check(store.paths.metadata.read("capture-inflight.json", 65536).bytes == null, "recovery clears only reconciled in-flight marker");
            if (!committed) check(store.hasGaps(), "affirmative absent receipt records interrupted-capture gap before Ready");
            return null;
        });
        String before = facts(message).getString("order");
        check(apply(input).equals(committed ? "capture_unchanged" : "capture_committed"), "crash cut reconciles exact event receipt");
        check(count(message) == 1 && facts(message).getString("head").equals(id)
            && facts(message).getString("order").equals(committed ? before : "1"), "crash replay retains the SAME id/order without duplicate body");
    }
    private static void crashes() throws Exception {
        TLRPC.Message after = message(1300, "committed before acknowledgement"); apply(input(after, 0, 0));
        cut(after, true, facts(after).getString("head"));
        cut(message(1301, "not committed before crash"), false, "crash_before_commit");
        TLRPC.Message stable = message(1302, "persistent progress"); apply(input(stable, 1, 100)); String order = facts(stable).getString("order");
        on(() -> { worker.store.drainReaders(); worker.store.close(); worker.store = null; worker.open(PurpleAccountFiles.capture(0)); return null; });
        check(apply(input(stable, 1, 99)).equals("capture_stale") && facts(stable).getString("order").equals(order), "reopened store retains causal progress across process-style restart");
        TLRPC.Message secret = message(1303, "secret retained"); secret.dialog_id = org.telegram.messenger.DialogObject.makeEncryptedDialogId(7);
        secret.random_id = -8000000001L; secret.id = -1303;
        apply(input(secret, 0, 0));
        org.telegram.SQLite.SQLiteDatabase empty = new org.telegram.SQLite.SQLiteDatabase((sql, args) -> new Object[0][]);
        org.telegram.messenger.MessagesStorage storage = new org.telegram.messenger.MessagesStorage(empty);
        ArrayList<Long> randoms = new ArrayList<>(); randoms.add(secret.random_id);
        PurpleArchiveCapture.secretDelete(0, storage, 7, 42, randoms); storage.getStorageQueue().drain();
        check(facts(secret).getBoolean("deleted") && count(secret) == 1, "production secret-delete path retains random identity after cache eviction");
        String deleted = facts(secret).getString("order");
        PurpleArchiveCapture.secretDelete(0, storage, 7, 42, randoms); storage.getStorageQueue().drain();
        check(facts(secret).getString("order").equals(deleted), "secret service replay finds stable receipt without a new order");
    }
    private static void logoutSaturated() throws Exception {
        UserConfig.getInstance(1).active = true; UserConfig.getInstance(1).user = 22;
        PurpleArchive.refresh(1, () -> {}); await(() -> PurpleArchive.status(1).state.equals("Ready"), "B worker ready");
        PurpleArchive.Worker other = PurpleArchive.existing(PurpleAccountFiles.capture(1));
        TLRPC.Message same = message(77, "B private body"); ArrayList<TLRPC.Message> observed = new ArrayList<>(); observed.add(same);
        PurpleArchiveCapture.beforeWrite(1, new org.telegram.SQLite.SQLiteDatabase((sql, args) -> new Object[0][]), PurpleArchiveCapture.observations(1, observed, 0));
        String otherText = other.queue.submit(() -> {
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(other.store, PurpleArchiveNormalize.key("0-22", same), "")) { return slice.head.getJSONObject("content").getString("text"); }
        }).get(10, TimeUnit.SECONDS);
        check(otherText.equals("B private body") && count(same) == 45, "identical chat/message ids are isolated by the opening account");
        File otherDb = other.queue.submit(() -> other.store.directory.file("archive.sqlite")).get(10, TimeUnit.SECONDS);
        File otherWal = new File(otherDb.getPath() + "-wal");
        byte[] before = java.nio.file.Files.readAllBytes(otherDb.toPath()), beforeWal = java.nio.file.Files.readAllBytes(otherWal.toPath());
        File oldDb = on(() -> worker.store.directory.file("archive.sqlite"));
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        worker.queue.execute(() -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); } });
        check(started.await(5, TimeUnit.SECONDS), "logout queue blocker started");
        for (int i = 0; i < 64; ++i) worker.queue.execute(() -> {});
        long start = System.nanoTime(); PurpleArchive.beforeLogout(0);
        check(System.nanoTime() - start < 500_000_000L && worker.closing, "logout revokes immediately even with a saturated queue");
        UserConfig.getInstance(0).active = false; UserConfig.getInstance(0).user = 0; PurpleAccountFiles.identityChanged(0);
        release.countDown(); await(() -> !oldDb.exists() && worker.retired, "control path erases archive and causal journals after queue saturation");
        check(Arrays.equals(before, java.nio.file.Files.readAllBytes(otherDb.toPath())) && Arrays.equals(beforeWal, java.nio.file.Files.readAllBytes(otherWal.toPath())) && other.store != null,
            "A logout leaves B database and worker untouched");
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root = new File(args[1]); check(root.mkdir(), "exclusive capture fixtures");
        ApplicationLoader.files = new File(root, "files"); check(ApplicationLoader.files.mkdir(), "capture app root");
        UserConfig.getInstance(0).active = true; UserConfig.getInstance(0).user = 11;
        PurpleArchiveLogin.source = new PurpleArchiveLogin.Source() {
            public String nonce(int slot) { return "11111111111111111111111111111111"; }
        };
        PurpleArchive.refresh(0, () -> {});
        await(() -> PurpleArchive.status(0).state.equals("Ready"), "production account worker ready");
        worker = PurpleArchive.existing(PurpleAccountFiles.capture(0));
        normalization(); causal(); hooks(); payloads(); hydration(); cancellation(); crashes();
        on(() -> { worker.store.drainReaders(); return null; });
        logoutSaturated();
        System.out.println("ARCHIVE_CAPTURE_CHECKS=" + checks);
    }
}
