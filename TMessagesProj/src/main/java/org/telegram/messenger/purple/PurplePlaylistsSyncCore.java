package org.telegram.messenger.purple;

import androidx.annotation.Keep;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

@Keep
public final class PurplePlaylistsSyncCore {
    @Keep
    public static final class Result {
        private final byte[] detail, library, state, publication;
        private Result(byte[] detail, byte[] library, byte[] state, byte[] publication) {
            if (detail == null) throw new IllegalStateException("MissingPlaylistSyncDetail");
            this.detail = detail.clone(); this.library = copy(library);
            this.state = copy(state); this.publication = copy(publication);
        }
        Result withDetail(JSONObject value) {
            return new Result(value.toString().getBytes(StandardCharsets.UTF_8), library, state, publication);
        }
        public JSONObject detail() {
            try { return new JSONObject(new String(detail, StandardCharsets.UTF_8)); }
            catch (Exception error) { throw new IllegalStateException("InvalidPlaylistSyncDetail", error); }
        }
        public byte[] library() { return copy(library); }
        public byte[] state() { return copy(state); }
        public byte[] publication() { return copy(publication); }
    }
    private static byte[] copy(byte[] bytes) { return bytes == null ? null : bytes.clone(); }
    public static Result metadata(byte[] message, long self) {
        PurpleCore.ensureLoaded(); return require(metadataNative(message, self));
    }
    public static Result classify(byte[] message, byte[] compressed, long self) {
        PurpleCore.ensureLoaded(); return require(classifyNative(message, compressed, self));
    }
    public static Result encode(byte[] library, long self) {
        PurpleCore.ensureLoaded(); return require(encodeNative(library, self));
    }
    public static Result plan(byte[] request, byte[] library, byte[] state, byte[][] payloads, long self) {
        PurpleCore.ensureLoaded(); return require(planNative(request, library, state, payloads, self));
    }
    static Result partial(byte[] request, byte[] body, byte[] library, byte[] state, long self) {
        try {
            org.json.JSONObject local = new org.json.JSONObject(new String(state, java.nio.charset.StandardCharsets.UTF_8));
            org.json.JSONObject scan = new org.json.JSONObject(new String(request, java.nio.charset.StandardCharsets.UTF_8));
            if (scan.optBoolean("listingExhausted") || scan.getJSONArray("listed").length() != 1
                    || scan.getJSONArray("payloads").length() != 1) return null;
            org.json.JSONObject memo = local.optJSONObject("memo");
            if (memo != null) memo.remove(scan.getJSONArray("listed").getJSONObject(0).getString("message"));
            return PurplePlaylistsSyncCore.plan(request, library, local.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    new byte[][]{body}, self);
        } catch (Exception error) { return null; }
    }
    public static Result verifyPrune(byte[] request, byte[] target, byte[] witness, long self) {
        PurpleCore.ensureLoaded(); return require(verifyPruneNative(request, target, witness, self));
    }
    private static Result require(Result value) {
        if (value == null) throw new IllegalStateException("MissingPlaylistSyncResult");
        return value;
    }
    private static native Result metadataNative(byte[] message, long self);
    private static native Result classifyNative(byte[] message, byte[] compressed, long self);
    private static native Result encodeNative(byte[] library, long self);
    private static native Result planNative(byte[] request, byte[] library, byte[] state, byte[][] payloads, long self);
    private static native Result verifyPruneNative(byte[] request, byte[] target, byte[] witness, long self);
    private PurplePlaylistsSyncCore() { }
}
