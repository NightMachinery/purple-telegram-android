package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

final class PurplePlaylistsSyncMetadata {
    static byte[] message(TLRPC.Message message, PurpleSyncChatCore.Ownership ownership) {
        if (message == null || message.id <= 0 || ownership == null)
            throw new IllegalArgumentException("MissingPlaylistMessage");
        boolean regular = message.getClass() == TLRPC.TL_message.class;
        TLRPC.Document document = regular && message.media != null
                && message.media.getClass() == TLRPC.TL_messageMediaDocument.class
                ? message.media.document : null;
        boolean isDocument = document != null && document.getClass() == TLRPC.TL_document.class;
        JSONArray names = new JSONArray();
        if (isDocument) for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute != null && attribute.getClass() == TLRPC.TL_documentAttributeFilename.class
                    && attribute.file_name != null) names.put(attribute.file_name);
        }
        try {
            return new JSONObject().put("regular", regular).put("forwarded", message.fwd_from != null)
                    .put("isDocument", isDocument).put("caption", regular && message.message != null ? message.message : "")
                    .put("mime", isDocument && document.mime_type != null ? document.mime_type : "")
                    .put("names", names).put("ownership", ownership.name()).put("message", Integer.toString(message.id))
                    .put("document", isDocument ? Long.toUnsignedString(document.id) : "0")
                    .put("read", "Succeeded").toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalArgumentException("InvalidPlaylistMessage", error); }
    }
    private PurplePlaylistsSyncMetadata() { }
}
