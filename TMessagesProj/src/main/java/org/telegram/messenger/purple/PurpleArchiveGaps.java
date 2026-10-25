package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class PurpleArchiveGaps {
    static void record(PurpleArchiveStore store, String reason, long observedAt) throws Exception {
        record(store, reason, observedAt, PurpleArchiveBinding.token());
    }
    static void record(PurpleArchiveStore store, String reason, long observedAt, String id) throws Exception {
        if (!PurpleArchiveKeys.token(id)) throw new IllegalArgumentException("Archive gap identity");
        if (reason == null || !reason.matches("[A-Za-z0-9_-]{1,128}") || observedAt < 0)
            throw new IllegalArgumentException("Archive gap reason");
        synchronized (store.environment.publicationLock()) {
            store.requireCurrent();
            PurpleArchiveFiles.Snapshot old = store.paths.metadata.read("gap-outbox.json", 65536);
            if (old.bytes == null) {
                JSONObject gap = new JSONObject().put("account", store.binding.account).put("store", store.binding.store)
                    .put("generation", store.binding.generation).put("id", id)
                    .put("reason", reason).put("observedAt", Long.toString(observedAt));
                store.paths.metadata.publish("gap-outbox.json", old, PurpleArchiveBinding.canonical(gap.toString().getBytes(StandardCharsets.UTF_8)));
            }
        }
        flush(store);
    }
    static void flush(PurpleArchiveStore store) throws Exception {
        store.requireCurrent();
        PurpleArchiveFiles.Snapshot pending = store.paths.metadata.read("gap-outbox.json", 65536);
        if (pending.bytes == null) return;
        JSONObject value = PurpleArchiveBinding.json(pending.bytes);
        if (!value.getString("account").equals(store.binding.account) || !value.getString("store").equals(store.binding.store)
            || !value.getString("generation").equals(store.binding.generation)) throw new PurpleArchiveStore.Failure("Archive gap scope changed");
        JSONObject plan = PurpleArchiveCore.call("gap", store.arguments().put("id", value.getString("id"))
            .put("observedAt", value.getString("observedAt")).put("reason", value.getString("reason")).put("details", new JSONObject()), store.crypto());
        if (!store.execute(new PurpleArchiveCore.Program(plan)).committed) throw new PurpleArchiveStore.Failure("Archive gap retry pending");
        synchronized (store.environment.publicationLock()) {
            store.requireCurrent();
            PurpleArchiveFiles.Snapshot now = store.paths.metadata.read("gap-outbox.json", 65536);
            if (now.mark == null || !now.mark.stamp.equals(pending.mark.stamp)) throw new PurpleArchiveStore.Failure("Archive gap outbox changed");
            store.paths.metadata.remove("gap-outbox.json", false);
        }
    }
    private PurpleArchiveGaps() {}
}
