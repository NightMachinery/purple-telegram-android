package org.telegram.messenger.purple;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;

public final class ArchiveMarkdownTest {
    private static int checks;
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); ++checks; }
    private static String encoded(TLObject value) {
        SerializedData data = new SerializedData();
        try { value.serializeToStream(data); return PurpleArchiveCore.base64(data.toByteArray()); }
        finally { data.cleanup(); }
    }
    private static JSONObject content(String text) throws Exception {
        return new JSONObject().put("text", text).put("caption", "").put("entities", new JSONArray())
            .put("rich", JSONObject.NULL).put("opaque", "").put("opaqueFormat", "").put("media", new JSONArray());
    }
    private static JSONObject version(JSONObject content, boolean partial) throws Exception {
        return new JSONObject().put("content", content).put("completeness", partial ? 0 : 1);
    }
    private static TL_iv.RichMessage article(boolean partial) {
        TL_iv.RichMessage value = new TL_iv.RichMessage(); value.part = partial;
        TL_iv.pageBlockParagraph block = new TL_iv.pageBlockParagraph(); TL_iv.textPlain text = new TL_iv.textPlain();
        text.text = "observed <body>"; block.text = text; value.blocks.add(block); return value;
    }
    public static void main(String[] args) throws Exception {
        JSONObject plain = content("ordinary <body>");
        check(PurpleArchiveMarkdownBody.render(version(plain, false)).equals("ordinary &lt;body&gt;"), "archive ordinary escapes HTML");
        TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold(); bold.offset = 0; bold.length = 8;
        plain.getJSONArray("entities").put(new JSONObject().put("offset",0).put("length",8).put("tl",encoded(bold)));
        check(PurpleArchiveMarkdownBody.render(version(plain,false)).equals("**ordinary** &lt;body&gt;"), "archive keeps retained entity formatting");
        JSONObject rich = content("").put("rich",new JSONObject().put("format","android-rich-tl-"+TLRPC.LAYER).put("tl",encoded(article(false))));
        check(PurpleArchiveMarkdownBody.render(version(rich,false)).equals("observed &lt;body&gt;"), "archive renders full received blocks");
        rich.getJSONObject("rich").put("tl",encoded(article(true)));
        String partial = PurpleArchiveMarkdownBody.render(version(rich,true));
        check(partial.startsWith("[Partial archived content:") && partial.endsWith("observed &lt;body&gt;"), "partial archive renders only observed blocks with disclosure");
        TL_iv.RichMessage unknown = article(false); unknown.blocks.add(new TL_iv.pageBlockUnsupported());
        rich.getJSONObject("rich").put("tl",encoded(unknown));
        String fallback = PurpleArchiveMarkdownBody.render(version(rich,false));
        check(fallback.contains("Retained archive structure") && fallback.contains(rich.getJSONObject("rich").getString("tl")), "unknown block retains full lossless TL structure");
        JSONObject media = content("caption").put("media",new JSONArray().put(new JSONObject().put("identity","document_7").put("kind","document")));
        fallback = PurpleArchiveMarkdownBody.render(version(media,false));
        check(fallback.contains("caption") && fallback.contains("document_7") && fallback.contains("```json"), "caption and unsupported media structure remain explicit");
        media.put("text","```\n</script>\n````");
        fallback = PurpleArchiveMarkdownBody.render(version(media,true));
        check(fallback.startsWith("[Partial archived content:") && fallback.contains("Retained archive structure"), "opaque partial is never labelled complete");
        JSONObject malformed = content("A"); malformed.getJSONArray("entities").put(new JSONObject().put("offset",1).put("length",8).put("tl",encoded(bold)));
        fallback = PurpleArchiveMarkdownBody.render(version(malformed,false));
        check(fallback.contains("Retained archive structure") && fallback.contains(encoded(bold)), "invalid entity remains lossless structural fallback");
        TLRPC.Message observed = new TLRPC.TL_message(); observed.id=7; observed.message="";
        observed.peer_id=new TLRPC.TL_peerUser(); observed.peer_id.user_id=7; observed.rich_message=article(false);
        check(PurpleArchiveNormalize.body(observed).getInt("completeness")==1,"new full rich observation retains complete structure");
        observed.rich_message.part=true;
        check(PurpleArchiveNormalize.body(observed).getInt("completeness")==2,"new partial rich observation remains explicitly opaque/partial");
        UserConfig.selectedAccount=0; UserConfig.getInstance(0).user=7; UserConfig.getInstance(0).active=true;
        PurpleArchiveViewerModel.generation=0;
        PurpleAccountFiles.Identity identity=PurpleAccountFiles.capture(0);
        PurpleArchive.Worker worker=PurpleArchive.ingressWorker(identity.key);
        AtomicInteger published=new AtomicInteger();
        PurpleArchiveMarkdown.Guard guard=PurpleArchiveMarkdown.guard(identity);
        check(guard.current() && guard.publish(()->true,published::incrementAndGet) && published.get()==1,"real archive guard publishes while opening authority remains current");
        worker.purgeEpoch++;
        check(!guard.current() && !guard.publish(()->true,published::incrementAndGet) && published.get()==1,"real purge generation revokes queued clipboard publication");
        guard=PurpleArchiveMarkdown.guard(identity); worker.deliveryEpoch++;
        check(!guard.current(),"new capture invalidates opening delivery");
        guard=PurpleArchiveMarkdown.guard(identity); worker.loginContext="rotated";
        check(!guard.current(),"login context change invalidates source delivery");
        guard=PurpleArchiveMarkdown.guard(identity); UserConfig.selectedAccount=1;
        check(!guard.current(),"cross-account publication refuses");
        UserConfig.selectedAccount=0; guard=PurpleArchiveMarkdown.guard(identity);
        check(!guard.publish(()->false,published::incrementAndGet),"closed or paused UI refuses final publication");
        worker.retire();
        check(!guard.current(),"retired worker cannot publish");
        System.out.println("ARCHIVE_MARKDOWN_CHECKS="+checks);
    }
}
