package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;
import org.json.JSONObject;
import org.json.JSONArray;

public final class PurplePlaylistsSyncCases {
    private static int checks;
    private static void check(boolean good, String text) { ++checks; if (!good) throw new AssertionError(text); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] bytes(JSONObject value) { return bytes(value.toString()); }
    private static final byte[] EMPTY = bytes("{\"format\":1,\"playlists\":{},\"purged\":{}}");
    private static final byte[] LIBRARY = bytes("{\"format\":1,\"playlists\":{\"pl-aaaaaaaaaaaaaaab\":{\"title\":[\"Sync ✓\",100],\"deleted\":[false,100]}},\"purged\":{}}");
    private static JSONObject message() throws Exception {
        return new JSONObject().put("regular",true).put("forwarded",false).put("isDocument",true)
                .put("caption","Purple playlists sync data #purpleplaylists").put("mime","application/gzip")
                .put("names",new JSONArray().put("Purple playlists.json.gz")).put("ownership","Own")
                .put("message","41").put("document","9007199254740993").put("read","Succeeded");
    }
    private static JSONObject request() throws Exception {
        return new JSONObject().put("authorized",true).put("trigger","Automatic")
                .put("libraryRead","Succeeded").put("stateRead","Succeeded").put("discoverySelf","101")
                .put("chat","self").put("listingSucceeded",true).put("listingExhausted",true).put("byIdSucceeded",true)
                .put("listed",new JSONArray()).put("byId",new JSONArray()).put("payloads",new JSONArray())
                .put("clock",new JSONObject().put("self","101").put("chat","self").put("now","200").put("succeeded",true));
    }
    private static JSONObject proof() throws Exception {
        return new JSONObject().put("chat","self").put("message","41").put("document","9007199254740993")
                .put("origin","DownloadedThisStep").put("readSucceeded",true).put("acknowledged",false)
                .put("expectedHash",JSONObject.NULL);
    }
    private static PurplePlaylistsSyncCore.Result plan(JSONObject request, byte[] library, byte[] state, byte[]... payloads) {
        return PurplePlaylistsSyncCore.plan(bytes(request),library,state,payloads,101);
    }
    private static byte[] zip(String text) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) { output.write(bytes(text)); }
        return bytes.toByteArray();
    }
    private static void refused(Runnable run, String label) {
        try { run.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException expected) { check(true,label); }
    }
    public static void main(String[] args) throws Exception {
        JSONObject meta=message();
        check(PurplePlaylistsSyncCore.metadata(bytes(meta),101).detail().getString("verdict").equals("Candidate"),"real shared metadata identifies playlist record");
        meta.put("ownership","Unknown");
        check(PurplePlaylistsSyncCore.metadata(bytes(meta),101).detail().getString("verdict").equals("UnknownOwnership"),"unknown ownership is not an owned record");
        meta.put("ownership","Other");
        check(PurplePlaylistsSyncCore.metadata(bytes(meta),101).detail().getString("verdict").equals("Ignored"),"foreign record is ignored");
        meta=message().put("forwarded",true);
        check(PurplePlaylistsSyncCore.metadata(bytes(meta),101).detail().getString("verdict").equals("Ignored"),"forwarded record never qualifies");
        meta=message().put("caption","#purplesync");
        check(PurplePlaylistsSyncCore.metadata(bytes(meta),101).detail().getString("verdict").equals("AmbiguousSettings"),"settings and playlist markers remain disjoint");
        PurplePlaylistsSyncCore.Result encoded=PurplePlaylistsSyncCore.encode(LIBRARY,101);
        check(encoded.detail().getString("status").equals("Valid")&&encoded.publication()!=null,"native shared gzip encoder accepts canonical library");
        byte[] compressed=encoded.publication(); byte[] changed=encoded.publication(); changed[0]=0;
        check(encoded.publication()[0]!=0,"publication bytes are immutable across callers");
        encoded.detail().put("status","changed");
        check(encoded.detail().getString("status").equals("Valid"),"detail is defensive UTF8 JSON");
        PurplePlaylistsSyncCore.Result classified=PurplePlaylistsSyncCore.classify(bytes(message()),compressed,101);
        check(classified.detail().getString("status").equals("Valid")&&classified.library()!=null,"actual native classifier validates gzip and schema");
        check(new String(classified.library(),StandardCharsets.UTF_8).contains("Sync ✓"),"JNI bytes preserve non-ASCII playlist title");
        check(classified.detail().getString("hash").matches("[a-f0-9]{64}"),"classifier exposes full canonical SHA256 proof");
        check(PurplePlaylistsSyncCore.classify(bytes(message().put("ownership","Other")),compressed,101).library()==null,"foreign payload never escapes refusal");
        check(PurplePlaylistsSyncCore.classify(bytes(message()),new byte[]{1,2},101).detail().getString("status").equals("Invalid"),"invalid gzip fails actual codec");
        check(PurplePlaylistsSyncCore.classify(bytes(message().put("read","Failed")),compressed,101).library()==null,"failed read cannot consume stale successful payload");
        check(PurplePlaylistsSyncCore.classify(bytes(message()),zip("{\"format\":2}"),101).detail().getString("status").equals("NewerFormat"),"newer record remains distinct from invalid");
        check(PurplePlaylistsSyncCore.classify(bytes(message()),new byte[10*1024*1024+1],101).detail().getString("status").equals("Oversized"),"shared compressed byte limit enforced through JNI");
        byte[] local=bytes("{\"sync\":true,\"future\":{\"opaque\":7},\"downloads\":{\"android\":{\"future\":true}}}");
        JSONObject input=request();
        PurplePlaylistsSyncCore.Result empty=plan(input,EMPTY,local);
        check(empty.detail().getString("status").equals("Planned")&&empty.publication()==null,"initial empty library does not publish gratuitous snapshot");
        check(empty.detail().getString("publication").equals("InitialEmpty"),"shared planner supplies empty publication reason");
        PurplePlaylistsSyncCore.Result initial=plan(input,LIBRARY,local);
        check(initial.detail().getString("publication").equals("Required")&&initial.publication()!=null,"nonempty local library schedules shared gzip publication");
        check(new JSONObject(new String(initial.state(),StandardCharsets.UTF_8)).getJSONObject("future").getInt("opaque")==7,"planning preserves unknown local state");
        check(new JSONObject(new String(initial.state(),StandardCharsets.UTF_8)).getJSONObject("downloads").getJSONObject("android").getBoolean("future"),"sync preserves opaque Android download metadata");
        input.put("authorized",false);
        check(plan(input,LIBRARY,local).detail().getString("error").equals("Unauthorized"),"unauthorized account cannot plan sync");
        input=request();
        check(plan(input,LIBRARY,bytes("{\"sync\":false}")).detail().getString("status").equals("Skipped"),"automatic sync respects local Off");
        input.put("trigger","Manual");
        check(plan(input,LIBRARY,bytes("{\"sync\":false}")).detail().getString("status").equals("Planned"),"manual check is a distinct shared trigger");
        input=request().put("libraryRead","Failed");
        check(plan(input,LIBRARY,local).detail().getString("error").equals("LibraryReadFailed"),"failed library read never uses supplied old bytes");
        input=request().put("stateRead","Failed");
        check(plan(input,LIBRARY,local).detail().getString("error").equals("StateReadFailed"),"failed local-state read remains failed");
        input=request();
        check(plan(input,LIBRARY,bytes("{\"format\":2}")).detail().getString("error").equals("NewerState"),"newer local state is not rewritten");
        check(plan(input,bytes("{\"format\":2}"),local).detail().getString("error").equals("NewerLibrary"),"newer library is not rewritten");
        input=request().put("listed",new JSONArray().put(message()));
        PurplePlaylistsSyncCore.Result pending=plan(input,EMPTY,local);
        check(!pending.detail().getBoolean("discoveryComplete")&&pending.detail().getJSONArray("retryReads").length()==1,"unread discovered record remains pending");
        check(pending.detail().getJSONObject("inventory").getJSONArray("reads").getJSONObject(0).getString("document").equals("9007199254740993"),"document identity above 2^53 is exact across JSON JNI boundary");
        input.put("payloads",new JSONArray().put(proof()));
        PurplePlaylistsSyncCore.Result merged=plan(input,EMPTY,local,compressed);
        check(merged.detail().getBoolean("discoveryComplete")&&merged.detail().getBoolean("writeLibrary"),"real shared step merges verified remote payload");
        check(merged.detail().getString("newlyMergedRecords").equals("1"),"actual step records successful remote merge");
        check(new String(merged.library(),StandardCharsets.UTF_8).contains("Sync ✓"),"planned merged bytes contain remote library");
        check(merged.detail().getString("publication").equals("EqualRecord")&&merged.publication()==null,"equal present remote proof avoids duplicate publication");
        check(merged.detail().getJSONObject("prune").getJSONArray("targets").length()==0,"unacknowledged discovery never fabricates prune authority");
        input.put("payloads",new JSONArray().put(proof().put("origin","Unproven")));
        PurplePlaylistsSyncCore.Result unproven=plan(input,EMPTY,local,compressed);
        check(!unproven.detail().getBoolean("writeLibrary")&&!unproven.detail().getBoolean("discoveryComplete"),"unproven bytes cannot merge as downloaded receipt");
        input.put("payloads",new JSONArray().put(proof().put("chat","channel:77")));
        check(!plan(input,EMPTY,local,compressed).detail().getBoolean("discoveryComplete"),"foreign-chat payload cannot satisfy current discovery");
        input=request().put("discoverySelf","202");
        check(plan(input,LIBRARY,local).detail().getString("error").equals("AccountMismatch"),"foreign discovery account fails shared planner");
        input=request().put("listingSucceeded",false);
        check(plan(input,LIBRARY,local).publication()==null,"failed discovery does not grant publication");
        JSONObject finalRequest=request();
        refused(()->PurplePlaylistsSyncCore.plan(bytes(finalRequest),EMPTY,local,new byte[][]{compressed},101),"payload metadata and byte array counts must agree");
        refused(()->PurplePlaylistsSyncCore.metadata(bytes("{\"regular\":true,\"regular\":false}"),101),"duplicate JSON keys refused before interpretation");
        refused(()->PurplePlaylistsSyncCore.metadata(new byte[]{'{','"','x','"',':','"',(byte)0xff,'"','}'},101),"invalid UTF8 refused rather than silently replaced");
        refused(()->PurplePlaylistsSyncCore.encode(EMPTY,0),"invalid account identity refused");
        System.out.println("PLAYLIST_SYNC_CORE_CHECKS="+checks);
        System.out.println("PLAYLIST_SYNC_CORE_OK");
    }
}
