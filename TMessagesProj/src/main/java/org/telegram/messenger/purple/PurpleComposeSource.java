package org.telegram.messenger.purple;

import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.ParagraphStyle;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;

public final class PurpleComposeSource {
    private static final PurpleComposePending pending = new PurpleComposePending(System::nanoTime);

    public static final class Capture {
        final PurpleAccountFiles.Identity identity;
        final PurpleArchive.Worker worker;
        final PurpleArchiveStore store;
        final PurpleArchiveIngress.Ticket ingress;
        final long dialog;
        final String login;
        final boolean styled;
        String original;

        Capture(PurpleAccountFiles.Identity identity, PurpleArchive.Worker worker, long dialog, CharSequence text) {
            this.identity = identity; this.worker = worker; store = worker.store;
            ingress = PurpleArchiveIngress.capture(identity.key); login = worker.loginContext;
            this.dialog = dialog; original = text.toString();
            boolean formatting = false;
            if (text instanceof Spanned) {
                Spanned spanned = (Spanned) text;
                Object[] spans = spanned.getSpans(0, text.length(), Object.class);
                formatting = spans.length > 64;
                for (Object span : spans) if (span instanceof CharacterStyle || span instanceof ParagraphStyle) formatting = true;
            }
            styled = formatting;
        }

        boolean current() {
            return identity.current() && PurpleArchive.existing(identity) == worker && worker.store == store
                    && !worker.closing && !worker.retired && login != null && login.equals(worker.loginContext) && ingress.current(worker);
        }
    }

    public static Capture before(int slot, long dialog, CharSequence text) {
        if (text == null || text.length() == 0 || text.length() > 16384 || dialog == 0) return null;
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        PurpleArchive.Worker worker = PurpleArchive.existing(identity);
        if (worker == null || worker.store == null || !worker.status.state.equals("Ready")) return null;
        Capture capture = new Capture(identity, worker, dialog, text);
        return capture.current() ? capture : null;
    }

    public static void prepared(Capture capture, SendMessagesHelper.SendMessageParams params) {
        if (capture == null || params == null) return;
        try {
            String original = capture.original;
            capture.original = null;
            if (original == null || !capture.current() || params.peer != capture.dialog || params.scheduleDate != 0
                    || DialogObject.isEncryptedDialog(params.peer) || params.mediaWebPage != null || params.webPage != null
                    || params.document != null || params.photo != null || params.location != null || params.retryMessageObject != null) return;
            boolean markdown = false;
            if (params.entities == null || params.entities.size() > 64) return;
            for (TLRPC.MessageEntity entity : params.entities) {
                if (entity instanceof TLRPC.TL_messageEntityBold || entity instanceof TLRPC.TL_messageEntityItalic
                        || entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre
                        || entity instanceof TLRPC.TL_messageEntityStrike || entity instanceof TLRPC.TL_messageEntitySpoiler) markdown = true;
            }
            if (!markdown) return;
            PurpleMarkdownSource.Candidate candidate = PurpleMarkdownSource.compose(original, params.message, normalized(params.message, params.entities), capture.styled);
            if (candidate == null) return;
            if (!pending.prepare(params, new PurpleComposePending.Scope(capture.identity.slot, capture.dialog, capture::current, capture), candidate)) candidate.close();
            AndroidUtilities.runOnUIThread(pending::prune, 5 * 60 * 1000L);
        } catch (Exception | LinkageError e) { if (capture.current()) PurpleArchive.captureGap(capture.identity, "markdown_compose_source_failed"); }
    }

    static byte[] normalized(String text, ArrayList<TLRPC.MessageEntity> entities) throws Exception {
        JSONArray encoded = new JSONArray();
        if (text == null || text.length() > 16384 || entities != null && entities.size() > 64)
            throw new PurpleArchiveStore.Failure("Markdown compose input limit");
        if (entities != null) for (TLRPC.MessageEntity entity : entities) {
            if (entity == null || entity.offset < 0 || entity.length < 0 || entity.offset > text.length() - entity.length)
                throw new PurpleArchiveStore.Failure("Markdown compose entity range");
            encoded.put(new JSONObject().put("offset", entity.offset).put("length", entity.length)
                    .put("tl", PurpleArchiveCore.base64(PurpleArchiveNormalize.bytes(entity))));
        }
        return PurpleArchiveSource.composeBody(text, encoded);
    }

    public static void bind(int slot, SendMessagesHelper.SendMessageParams params, TLRPC.Message owner) {
        if (owner == null) return;
        pending.bind(slot, params, owner, owner.id, owner.random_id, MessageObject.getDialogId(owner));
    }

    static void finish(int slot, int localId, Object value, int serverId) {
        if (!(value instanceof TLRPC.Message)) return;
        TLRPC.Message owner = (TLRPC.Message) value;
        PurpleComposePending.Transfer transfer = null;
        try {
            if (owner.id != serverId || owner.rich_message != null || owner.action != null || owner.grouped_id != 0
                    || owner.media != null && !(owner.media instanceof TLRPC.TL_messageMediaEmpty)) { pending.cancel(slot, owner); return; }
            byte[] normalized = normalized(owner.message, owner.entities);
            transfer = pending.confirm(slot, owner, localId, serverId, owner.random_id, MessageObject.getDialogId(owner), normalized);
            if (transfer == null) return;
            Capture capture = (Capture) transfer.scope.context;
            String key = PurpleArchiveNormalize.key(capture.identity.key, owner).toString();
            String body = PurpleArchiveNormalize.body(owner).toString();
            PurpleComposePending.Transfer accepted = transfer;
            capture.worker.queue.execute(() -> retain(capture, accepted, key, body));
            transfer = null;
        } catch (Exception | LinkageError e) {
            pending.cancel(slot, owner);
        } finally { if (transfer != null) transfer.close(); }
    }

    private static void retain(Capture capture, PurpleComposePending.Transfer transfer, String keyText, String bodyText) {
        try (PurpleComposePending.Transfer accepted = transfer) {
            if (!accepted.current() || !capture.current()) return;
            JSONObject key = new JSONObject(keyText), body = new JSONObject(bodyText);
            String hash = PurpleArchiveCore.call("snapshot", capture.store.arguments().put("key", key).put("body", body), capture.store.crypto()).getString("hash");
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(capture.store, key, null)) {
                if (slice.head != null && !initial(slice, hash, body.getJSONObject("context").getString("editDate"))) return;
            }
            PurpleArchiveSequence.capture(capture.store, new PurpleArchiveCapture.Input(keyText, bodyText,
                    PurpleArchiveOrigin.baseline().bind(capture.ingress), false), accepted::current);
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(capture.store, key, null)) {
                if (!accepted.current() || !capture.current() || !initial(slice, hash, body.getJSONObject("context").getString("editDate"))) return;
                PurpleArchiveSource.Prepared prepared = new PurpleArchiveSource.Prepared(slice, slice.head.getString("id"));
                PurpleArchiveSource.bind(prepared, accepted.source, (long) accepted.localId, accepted.randomId, accepted::current);
            }
        } catch (Exception | LinkageError e) { if (capture.current()) PurpleArchive.captureGap(capture.identity, "markdown_compose_source_failed"); }
    }

    static boolean initial(PurpleArchiveSlice slice, String hash, String editDate) throws Exception {
        return slice.head != null && !slice.facts.getBoolean("deleted") && slice.facts.getString("order").equals("1")
                && slice.head.getInt("origin") == 0 && slice.head.getInt("completeness") == 1
                && slice.head.getString("id").equals(slice.head.getString("revision"))
                && slice.facts.getString("hash").equals(hash)
                && slice.head.getJSONObject("observation").getJSONObject("context").getString("editDate").equals(editDate);
    }

    static void cancel(int slot, int local, long dialog) { pending.cancelLocal(slot, local, dialog); }

    static void revoke(int slot) { pending.cleanup(slot); }

    private PurpleComposeSource() {}
}
