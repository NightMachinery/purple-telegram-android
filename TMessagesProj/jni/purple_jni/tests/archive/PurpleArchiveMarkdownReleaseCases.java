package org.telegram.messenger.purple;

import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;

import java.io.File;
import java.security.Security;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveMarkdownReleaseCases {
    private static void drain() {
        ArrayList<Runnable> tasks;
        synchronized (AndroidUtilities.class) {
            tasks = new ArrayList<>(AndroidUtilities.pending);
            AndroidUtilities.pending.clear();
        }
        for (Runnable task : tasks) task.run();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 4; ++i) { drain(); on(() -> null); }
    }

    private static int readers() throws Exception {
        return on(() -> {
            java.lang.reflect.Field field = PurpleArchiveStore.class.getDeclaredField("readers");
            field.setAccessible(true);
            return field.getInt(worker.store);
        });
    }

    private static Set<PurpleArchiveViewerModel> viewers() throws Exception {
        java.lang.reflect.Field field = PurpleArchiveViewerModel.class.getDeclaredField("viewers");
        field.setAccessible(true);
        return (Set<PurpleArchiveViewerModel>) field.get(null);
    }

    private static boolean closed(PurpleArchiveViewerModel viewer) throws Exception {
        java.lang.reflect.Field field = PurpleArchiveViewerModel.class.getDeclaredField("closed");
        field.setAccessible(true);
        return ((AtomicBoolean) field.get(viewer)).get();
    }

    private static JSONObject statement(String sql, boolean write) throws Exception {
        return new JSONObject().put("sql", sql).put("binds", new JSONArray())
            .put("expectation", write ? 2 : 0).put("counter", write ? 0 : 1)
            .put("minRows", "0").put("maxRows", "0")
            .put("minChanges", write ? "1" : "0").put("maxChanges", write ? "1" : "0");
    }

    private static void purgeAdmission() throws Exception {
        JSONObject program = new JSONObject().put("status", "Ready").put("statements", new JSONArray()
            .put(statement("BEGIN IMMEDIATE", false))
            .put(statement("UPDATE archive_meta SET maintenance_id='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' WHERE singleton=1 AND maintenance_id=''", true))
            .put(statement("COMMIT", false)));
        check(worker.store.execute(new PurpleArchiveCore.Program(program)).committed, "purge admission commits through the real Store boundary");
        check(worker.store.maintenance, "durable purge admission closes the normal read gate");
    }

    public static void main(String[] args) throws Exception {
        String mode = args[2];
        check(java.util.Arrays.asList("control", "capture", "cancel", "parent-close", "purge", "cancel-before-worker", "purge-before-worker").contains(mode), "known release case");
        System.load(args[0]);
        PurpleCore.ensureLoaded();
        Security.addProvider(new PurpleArchivePlatformProvider());
        File root = new File(args[1]);
        check(root.mkdir(), "exclusive fixture");
        ApplicationLoader.files = new File(root, "files");
        check(ApplicationLoader.files.mkdir(), "exclusive files");
        ApplicationLoader.applicationContext = new android.content.Context();
        UserConfig.selectedAccount = 0;
        UserConfig.getInstance(0).active = true;
        UserConfig.getInstance(0).user = 11;
        PurpleArchiveLogin.source = slot -> "11111111111111111111111111111111";
        PurpleArchive.refresh(0, () -> {});
        await(() -> PurpleArchive.status(0).state.equals("Ready"), "archive ready");
        worker = PurpleArchive.existing(PurpleAccountFiles.capture(0));
        PurpleArchiveDeletedModel parent = null;
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            int baseline = readers();
            TLRPC.Message message = message(8800, "retained body", -1);
            on(() -> { capture(message); PurpleArchiveQueryCases.apply(key(message), null, 2, 0); return null; });
            parent = new PurpleArchiveDeletedModel(PurpleArchiveQueryCases.scope(0, 55));
            AtomicReference<PurpleArchiveQueries.Page> page = new AtomicReference<>();
            check(parent.next(page::set), "deleted page admitted");
            await(() -> page.get() != null, "deleted page delivered");
            check(page.get().entries.size() == 1, "one exact retained entry");
            int before = readers();
            check(before == baseline + 1, "parent owns a real Store read transaction");
            AtomicBoolean active = new AtomicBoolean(true);
            AtomicInteger callbacks = new AtomicInteger(), published = new AtomicInteger();
            worker.queue.execute(() -> {
                blocked.countDown();
                try { check(release.await(10, TimeUnit.SECONDS), "release worker gate"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            });
            check(blocked.await(10, TimeUnit.SECONDS), "worker gate entered");
            if (mode.equals("purge-before-worker")) worker.queue.execute(() -> {
                try { purgeAdmission(); } catch (Exception e) { throw new AssertionError(e); }
            });
            Set<PurpleArchiveViewerModel> registered = viewers();
            int registeredBefore;
            synchronized (registered) { registeredBefore = registered.size(); }
            check(parent.copy(page.get().entries.get(0), active::get, value -> {
                callbacks.incrementAndGet();
                if (value != null) { check(value.contains("retained body"), "real adapter renders the exact retained body"); published.incrementAndGet(); }
            }), "real deleted-view copy admitted");
            PurpleArchiveViewerModel hidden;
            synchronized (registered) {
                check(registered.size() == registeredBefore + 1, "one internal pinned model has an explicit owner");
                hidden = registered.iterator().next();
            }
            if (mode.equals("cancel-before-worker")) active.set(false);
            release.countDown();
            on(() -> null);
            on(() -> null);
            int held = readers();
            check(closed(hidden), "internal model closes on the worker before any UI delivery");
            synchronized (registered) { check(registered.size() == registeredBefore, "internal model registration released before UI delivery"); }
            check(callbacks.get() == 0, "copy result remains queued");
            check(held <= before, "copy leaves no escaping Store reader or WAL snapshot");
            if (mode.equals("capture")) {
                TLRPC.Message fresh = message(8801, "unrelated incoming capture", -1);
                fresh.dialog_id = 66; fresh.peer_id.user_id = 66;
                ArrayList<PurpleArchiveCapture.Input> batch = new ArrayList<>(); batch.add(input(fresh));
                PurpleArchive.capture(PurpleAccountFiles.capture(0), batch);
                on(() -> null);
                check(on(() -> { try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(fresh), null)) { return slice.head != null; } }), "unrelated public incoming capture actually committed");
                check(parent.available(), "ordinary capture preserves the parent snapshot");
            } else if (mode.equals("cancel")) active.set(false);
            else if (mode.equals("parent-close")) parent.close();
            else if (mode.equals("purge")) on(() -> { purgeAdmission(); return null; });
            settle();
            int after = readers();
            boolean revoked = mode.equals("purge") || mode.equals("purge-before-worker") || mode.equals("parent-close");
            check(after == (revoked ? baseline : before), "all real reader ownership returns to its expected baseline");
            check(callbacks.get() == (mode.equals("control") ? 1 : 0), "stale or cancelled result is never delivered");
            check(published.get() == (mode.equals("control") ? 1 : 0), "real adapter publishes only the valid control");
            hidden.close(); hidden.close();
            parent.close(); parent.close();
            settle();
            check(readers() == baseline, "idempotent model release leaves no real Store readers");
            System.out.println("DELETED_COPY_RELEASE mode=" + mode + " before=" + before + " held=" + held + " after=" + after + " final=" + readers() + " callbacks=" + callbacks.get() + " published=" + published.get());
            System.out.println("DELETED_COPY_RELEASE_CHECKS=" + checks);
        } finally {
            release.countDown();
            if (parent != null) parent.close();
            on(() -> { worker.store.drainReaders(); worker.store.close(); worker.store = null; return null; });
            worker.queue.shutdown();
        }
    }
}
