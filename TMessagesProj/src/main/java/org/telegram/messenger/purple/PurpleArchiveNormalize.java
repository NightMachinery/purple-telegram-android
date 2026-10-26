package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.DialogObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

final class PurpleArchiveNormalize {
    static final int RAW_LIMIT = 2 * 1024 * 1024;
    static byte[] bytes(TLObject object) throws Exception {
        if (object == null) return new byte[0];
        int size = object.getObjectSize();
        if (size < 0 || size > RAW_LIMIT) throw new PurpleArchiveStore.Failure("Archive payload too large");
        SerializedData data = new SerializedData(size);
        try { object.serializeToStream(data); return data.toByteArray(); }
        finally { data.cleanup(); }
    }
    static String digest(String value) throws Exception {
        byte[] canonical = PurpleArchiveCore.unbase64(PurpleArchiveCore.call("canonical", new JSONObject()
            .put("account", "0-1").put("bytes", PurpleArchiveCore.base64(value.getBytes(StandardCharsets.UTF_8))), null).getString("bytes"));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical); StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return out.toString();
    }
    static JSONObject key(String account, TLRPC.Message message) throws Exception {
        if (message == null || message instanceof TLRPC.TL_messageEmpty) throw new PurpleArchiveStore.Failure("Archive message unavailable");
        long dialog = message.dialog_id;
        if (dialog == 0 && message.peer_id != null) dialog = message.peer_id.channel_id != 0 ? -message.peer_id.channel_id
            : message.peer_id.chat_id != 0 ? -message.peer_id.chat_id
                : message.from_id == null || message.out || message.guestchat_via_from != null ? message.peer_id.user_id : message.from_id.user_id;
        boolean secret = DialogObject.isEncryptedDialog(dialog);
        long id = secret ? message.random_id : message.id;
        int kind = secret ? 3 : message.peer_id != null && message.peer_id.channel_id != 0 ? 2 : dialog < 0 ? 1 : 0;
        long chat = secret ? DialogObject.getEncryptedChatId(dialog) : Math.abs(dialog);
        if (chat <= 0 || (secret ? id == 0 : id <= 0)) throw new PurpleArchiveStore.Failure("Archive stable identity unavailable");
        return key(account, kind, chat, id);
    }
    static JSONObject key(String account, int kind, long chat, long id) throws Exception {
        return new JSONObject().put("account", account).put("chat", new JSONObject().put("kind", kind).put("id", Long.toString(chat)))
            .put("messageId", Long.toString(id)).put("collection", 0).put("collectionId", "0").put("representation", "original");
    }
    static long qualityBytes(TLRPC.PhotoSize size) {
        if (size instanceof TLRPC.TL_photoSizeEmpty || size instanceof TLRPC.TL_photoStrippedSize || size instanceof TLRPC.TL_photoPathSize) return -1;
        if (size instanceof TLRPC.TL_photoCachedSize) return size.bytes == null ? -1 : size.bytes.length;
        if (size instanceof TLRPC.TL_photoSizeProgressive) { long maximum = -1; for (int bytes : ((TLRPC.TL_photoSizeProgressive) size).sizes) maximum = Math.max(maximum, bytes); return maximum; }
        return size.size > 0 ? size.size : -1;
    }
    private static boolean quality(JSONArray media, JSONArray inline, String identity, String kind, TLRPC.PhotoSize size) throws Exception {
        long length = qualityBytes(size);
        if (length < 0 || size.type == null || !size.type.matches("[A-Za-z0-9]{1,8}")) return false;
        if (media.length() >= 32) throw new PurpleArchiveStore.Failure("Archive media descriptor limit");
        String id = identity + "_" + size.type;
        media.put(new JSONObject().put("identity", id).put("kind", kind).put("attributes", new JSONObject().put("quality", size.type)
            .put("width", size.w).put("height", size.h).put("bytes", Long.toString(length))));
        if (size instanceof TLRPC.TL_photoCachedSize && length <= 256 * 1024) inline.put(new JSONObject().put("identity", id).put("bytes", PurpleArchiveCore.base64(size.bytes)));
        return true;
    }
    private static void document(JSONArray media, JSONArray inline, TLRPC.Document document, boolean spoiler) throws Exception {
        if (document == null || document instanceof TLRPC.TL_documentEmpty) return;
        if (media.length() >= 32) throw new PurpleArchiveStore.Failure("Archive media descriptor limit");
        JSONArray attributes = new JSONArray(); for (TLRPC.DocumentAttribute attribute : document.attributes) attributes.put(PurpleArchiveCore.base64(bytes(attribute)));
        media.put(new JSONObject().put("identity", "document_" + document.id).put("kind", "document").put("attributes", new JSONObject()
            .put("mime", document.mime_type == null ? "" : document.mime_type).put("bytes", Long.toString(document.size)).put("attributes", attributes).put("spoiler", spoiler)));
        for (TLRPC.PhotoSize size : document.thumbs) quality(media, inline, "document_" + document.id + "_thumb", "thumbnail", size);
    }
    static JSONObject body(TLRPC.Message message) throws Exception {
        byte[] raw = bytes(message);
        JSONArray entities = new JSONArray(), missing = new JSONArray(), media = new JSONArray(), inline = new JSONArray();
        String text = message.message == null ? "" : message.message;
        if (message.entities != null) for (TLRPC.MessageEntity entity : message.entities) {
            if (entity == null || entity.offset < 0 || entity.length < 0 || entity.offset > text.length() - entity.length) {
                missing.put(new JSONArray().put("entities")); entities = new JSONArray(); break;
            }
            entities.put(new JSONObject().put("offset", entity.offset).put("length", entity.length)
                .put("tl", PurpleArchiveCore.base64(bytes(entity))));
        }
        int completeness = missing.length() == 0 ? 1 : 0;
        String opaque = "", opaqueFormat = ""; Object rich = JSONObject.NULL;
        if (message.rich_message != null) {
            rich = new JSONObject().put("format", "android-rich-tl-" + TLRPC.LAYER).put("tl", PurpleArchiveCore.base64(bytes(message.rich_message))); completeness = message.rich_message.part ? 2 : 1;
        }
        if (message.action != null && !(message.action instanceof TLRPC.TL_messageActionEmpty)) {
            JSONObject semantic = rich instanceof JSONObject ? (JSONObject) rich : new JSONObject();
            semantic.put("action", PurpleArchiveCore.base64(bytes(message.action))); rich = semantic; completeness = 2;
        }
        if (message.media instanceof TLRPC.TL_messageMediaPhoto && message.media.photo != null && !(message.media.photo instanceof TLRPC.TL_photoEmpty)) {
            for (TLRPC.PhotoSize size : message.media.photo.sizes) quality(media, inline, "photo_" + message.media.photo.id, "photo", size);
            if (media.length() == 0) media.put(new JSONObject().put("identity", "photo_" + message.media.photo.id).put("kind", "photo").put("attributes", new JSONObject().put("spoiler", message.media.spoiler)));
        } else if (message.media instanceof TLRPC.TL_messageMediaDocument && message.media.document != null && !(message.media.document instanceof TLRPC.TL_documentEmpty)) {
            document(media, inline, message.media.document, message.media.spoiler);
            for (TLRPC.Document alternative : message.media.alt_documents) if (alternative.id != message.media.document.id) document(media, inline, alternative, message.media.spoiler);
        } else if (message.media != null && !(message.media instanceof TLRPC.TL_messageMediaEmpty)) {
            if (message.media instanceof TLRPC.TL_messageMediaWebPage && message.media.webpage != null) {
                TLRPC.WebPage page = message.media.webpage;
                opaque = new JSONObject().put("kind", "webpage").put("id", Long.toString(page.id))
                    .put("url", page.url == null ? "" : page.url).put("title", page.title == null ? "" : page.title)
                    .put("description", page.description == null ? "" : page.description).toString();
                opaque = PurpleArchiveCore.base64(opaque.getBytes(StandardCharsets.UTF_8)); opaqueFormat = "android-webpage-v1";
            } else { opaque = PurpleArchiveCore.base64(bytes(message.media)); opaqueFormat = "android-media-tl-" + TLRPC.LAYER; }
            completeness = 2;
        }
        if (completeness == 2 && missing.length() != 0) completeness = 0;
        JSONObject context = new JSONObject().put("editDate", Long.toString(message.edit_date)).put("album", Long.toString(message.grouped_id))
            .put("ttl", message.ttl).put("mediaTtl", message.media == null ? 0 : message.media.ttl_seconds).put("destroyTime", Long.toString(message.destroyTime));
        if (message.media != null && message.media.ttl_seconds > 0 && (message.media.photo instanceof TLRPC.TL_photoEmpty || message.media.document instanceof TLRPC.TL_documentEmpty)) context.put("expiredReplacement", true);
        if (message.from_id != null) context.put("senderUser", Long.toString(message.from_id.user_id))
            .put("senderChat", Long.toString(message.from_id.chat_id)).put("senderChannel", Long.toString(message.from_id.channel_id));
        long topic = message.reply_to == null || !message.reply_to.forum_topic ? 0
            : message.reply_to.reply_to_top_id != 0 ? message.reply_to.reply_to_top_id : message.reply_to.reply_to_msg_id;
        if (message.reply_to != null) context.put("reply", Long.toString(message.reply_to.reply_to_msg_id));
        return new JSONObject().put("text", media.length() == 0 ? text : "").put("caption", media.length() == 0 ? "" : text)
            .put("entities", entities).put("rich", rich).put("opaque", opaque).put("opaqueFormat", opaqueFormat)
            .put("media", media).put("inlineMedia", inline).put("completeness", completeness).put("missing", missing)
            .put("observedAt", Long.toString(System.currentTimeMillis() / 1000)).put("serverTime", Long.toString(Math.max(0, message.date)))
            .put("topicId", Long.toString(Math.max(0, topic))).put("context", context)
            .put("transport", new JSONObject().put("views", message.views).put("sendState", message.send_state))
            .put("payloadFormat", "android-message-tl-" + TLRPC.LAYER).put("payload", PurpleArchiveCore.base64(raw));
    }
    static byte[] storedBytes(org.telegram.tgnet.NativeByteBuffer data) throws Exception {
        java.nio.ByteBuffer buffer = data.buffer.duplicate();
        if (buffer.remaining() > RAW_LIMIT) throw new PurpleArchiveStore.Failure("Archive preimage too large");
        byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
    }
    static JSONObject opaqueBody(byte[] raw) throws Exception {
        return new JSONObject().put("text", "").put("caption", "").put("entities", new JSONArray()).put("rich", JSONObject.NULL)
            .put("opaque", PurpleArchiveCore.base64(raw)).put("opaqueFormat", "android-cache-opaque-tl-" + TLRPC.LAYER)
            .put("media", new JSONArray()).put("completeness", 2).put("missing", new JSONArray())
            .put("observedAt", Long.toString(System.currentTimeMillis() / 1000)).put("serverTime", "0").put("topicId", "0")
            .put("context", new JSONObject()).put("transport", new JSONObject()).put("payloadFormat", "android-cache-tl-" + TLRPC.LAYER)
            .put("payload", PurpleArchiveCore.base64(raw));
    }
    private PurpleArchiveNormalize() {}
}
