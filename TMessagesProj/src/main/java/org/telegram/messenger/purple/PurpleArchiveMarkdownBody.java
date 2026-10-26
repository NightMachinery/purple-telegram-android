package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;

final class PurpleArchiveMarkdownBody {
    static String render(JSONObject version) throws Exception {
        JSONObject content = version.getJSONObject("content");
        boolean partial = version.getInt("completeness") != 1;
        String result;
        try { result = structured(content); }
        catch (Exception | LinkageError e) { result = fallback(content); }
        return (partial ? "[Partial archived content: only observed structure is retained]\n\n" : "") + result;
    }

    private static String structured(JSONObject content) throws Exception {
        if (!content.getString("opaque").isEmpty() || content.getJSONArray("media").length() != 0) return fallback(content);
        String text = content.getString("text"), caption = content.getString("caption");
        if (!text.isEmpty() && !caption.isEmpty()) return fallback(content);
        String ordinary = entities(text.isEmpty() ? caption : text, content.getJSONArray("entities"));
        if (content.isNull("rich")) return ordinary;
        JSONObject rich = content.getJSONObject("rich");
        if (rich.length() != 2 || !rich.optString("format").equals("android-rich-tl-" + TLRPC.LAYER)) return fallback(content);
        byte[] bytes = PurpleArchiveCore.unbase64(rich.getString("tl"));
        if (bytes.length > PurpleArchiveNormalize.RAW_LIMIT) throw new IllegalArgumentException("Archive rich content limit");
        SerializedData data = new SerializedData(bytes);
        try {
            TL_iv.RichMessage article = TL_iv.RichMessage.TLdeserialize(data, data.readInt32(true), true);
            if (article == null || data.remaining() != 0) throw new IllegalArgumentException("Archive rich content framing");
            String blocks = PurpleRichMessageMarkdown.renderObserved(article);
            return ordinary.isEmpty() ? blocks : ordinary + "\n\n" + blocks;
        } finally { data.cleanup(); }
    }

    private static String entities(String text, JSONArray encoded) throws Exception {
        ArrayList<PurpleMarkdown.Span> spans = new ArrayList<>();
        for (int i = 0; i < encoded.length(); ++i) {
            JSONObject value = encoded.getJSONObject(i);
            byte[] bytes = PurpleArchiveCore.unbase64(value.getString("tl"));
            SerializedData data = new SerializedData(bytes);
            TLRPC.MessageEntity entity;
            try {
                entity = TLRPC.MessageEntity.TLdeserialize(data, data.readInt32(true), true);
                if (entity == null || data.remaining() != 0) throw new IllegalArgumentException("Archive entity framing");
            } finally { data.cleanup(); }
            int start = entity.offset, end = start + entity.length;
            if (start != value.getInt("offset") || entity.length != value.getInt("length") || start < 0
                    || entity.length < 0 || end < start || end > text.length()) throw new IllegalArgumentException("Archive entity range");
            int kind;
            String target = "";
            if (entity instanceof TLRPC.TL_messageEntityBold) kind = PurpleMarkdown.BOLD;
            else if (entity instanceof TLRPC.TL_messageEntityItalic) kind = PurpleMarkdown.ITALIC;
            else if (entity instanceof TLRPC.TL_messageEntityStrike) kind = PurpleMarkdown.STRIKE;
            else if (entity instanceof TLRPC.TL_messageEntityUnderline) kind = PurpleMarkdown.UNDERLINE;
            else if (entity instanceof TLRPC.TL_messageEntityCode) kind = PurpleMarkdown.CODE;
            else if (entity instanceof TLRPC.TL_messageEntityPre) { kind = PurpleMarkdown.PRE; target = entity.language; }
            else if (entity instanceof TLRPC.TL_messageEntityBlockquote) kind = PurpleMarkdown.QUOTE;
            else if (entity instanceof TLRPC.TL_messageEntityTextUrl) { kind = PurpleMarkdown.LINK; target = entity.url; }
            else if (entity instanceof TLRPC.TL_messageEntityUrl) { kind = PurpleMarkdown.LINK; target = text.substring(start, end); }
            else if (entity instanceof TLRPC.TL_messageEntityEmail) { kind = PurpleMarkdown.LINK; target = "mailto:" + text.substring(start, end); }
            else if (entity instanceof TLRPC.TL_messageEntityPhone) { kind = PurpleMarkdown.LINK; target = "tel:" + text.substring(start, end); }
            else if (entity instanceof TLRPC.TL_messageEntityMentionName) { kind = PurpleMarkdown.LINK; target = "tg://user?id=" + ((TLRPC.TL_messageEntityMentionName) entity).user_id; }
            else if (entity instanceof TLRPC.TL_messageEntityMention) { kind = PurpleMarkdown.LINK; target = "https://t.me/" + text.substring(start, end).replaceFirst("^@", ""); }
            else throw new IllegalArgumentException("Archive entity needs structural fallback");
            spans.add(new PurpleMarkdown.Span(start, end, kind, target));
        }
        return PurpleMarkdown.render(text, spans);
    }

    private static String fallback(JSONObject content) throws Exception {
        return "[Retained archive structure, JSON with original TL fields encoded as base64]\n\n"
                + PurpleArticleMarkdown.code(content.toString(2), "json", true).markdown;
    }

    private PurpleArchiveMarkdownBody() {}
}
