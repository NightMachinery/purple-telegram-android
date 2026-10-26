package org.telegram.messenger.purple;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveMarkdown {
    public static final class Guard {
        final PurpleAccountFiles.Identity identity;
        final PurpleArchive.Worker worker;
        final PurpleArchiveStore store;
        final long selection, purge, delivery, copy;
        final String login;

        Guard(PurpleAccountFiles.Identity identity) {
            this.identity = identity;
            worker = PurpleArchive.ingressWorker(identity.key); store = worker.store;
            selection = PurpleArchiveViewerModel.selectionGeneration();
            purge = worker.purgeEpoch; delivery = worker.deliveryEpoch;
            copy = store == null ? 0 : store.copyEpoch; login = worker.loginContext;
        }

        public boolean current() {
            return identity.current() && org.telegram.messenger.UserConfig.selectedAccount == identity.slot
                    && selection == PurpleArchiveViewerModel.selectionGeneration()
                    && PurpleArchive.boundWorker(identity.key) == worker && !worker.retired && !worker.closing
                    && worker.store == store && worker.purgeEpoch == purge && worker.deliveryEpoch == delivery
                    && java.util.Objects.equals(login, worker.loginContext)
                    && (store == null || !store.closed() && !store.maintenance && store.copyEpoch == copy);
        }

        public boolean publish(BooleanSupplier active, Runnable action) {
            synchronized (worker.publication) {
                if (!current() || !active.getAsBoolean()) return false;
                action.run(); return true;
            }
        }
    }

    public static Guard guard(PurpleAccountFiles.Identity identity) { return identity == null ? null : new Guard(identity); }

    public static void current(Guard guard, TLRPC.Message message, Consumer<String> delivered, Runnable refused) {
        if (guard == null || !guard.current()) return;
        if (guard.store == null || message.id <= 0) { delivered.accept(null); return; }
        final String keyText, bodyText;
        try {
            keyText = PurpleArchiveNormalize.key(guard.identity.key, message).toString();
            bodyText = PurpleArchiveNormalize.body(message).toString();
        } catch (Exception | LinkageError e) { delivered.accept(null); return; }
        boolean queued = PurpleArchive.media(guard.identity, guard::current, (worker, active) -> {
            String text = null;
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(guard.store, new JSONObject(keyText), null)) {
                JSONObject body = new JSONObject(bodyText);
                String hash = PurpleArchiveCore.call("snapshot", guard.store.arguments().put("key", slice.key).put("body", body), guard.store.crypto()).getString("hash");
                if (slice.head != null && !slice.facts.getBoolean("deleted") && hash.equals(slice.facts.getString("hash"))
                        && slice.head.getJSONObject("observation").getJSONObject("context").optInt("editDate") == new JSONObject(bodyText).getJSONObject("context").optInt("editDate")) {
                    PurpleArchiveSource.Choice choice = PurpleArchiveSource.choose(new PurpleArchiveSource.Prepared(slice, slice.head.getString("id")), 0, false);
                    if (choice.genuine) text = choice.text();
                }
            } catch (Exception | LinkageError ignored) {
                AndroidUtilities.runOnUIThread(() -> { if (guard.current()) refused.run(); });
                return;
            }
            String result = text;
            AndroidUtilities.runOnUIThread(() -> { if (guard.current()) delivered.accept(result); });
        }, () -> AndroidUtilities.runOnUIThread(() -> { if (guard.current()) delivered.accept(null); }));
        if (!queued && guard.current()) delivered.accept(null);
    }

    private PurpleArchiveMarkdown() {}
}
