package org.telegram.messenger.purple;

import androidx.annotation.Keep;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

@Keep
final class PurpleSyncRetainedBases {
    static final class Result {
        final String status;
        final byte[] state, record;
        final JSONObject fields;
        Result(byte[] bytes) throws Exception {
            if (bytes == null) throw new IllegalStateException("Missing retention response");
            fields = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            status = fields.getString("status");
            state = decode(fields.optString("state"));
            record = decode(fields.optString("record"));
        }
        boolean ready() { return status.equals("Ready"); }
    }
    static Result request(String op, byte[] state, byte[] next, byte[]... candidates) {
        PurpleCore.ensureLoaded();
        try { return new Result(retentionNative(op, state, next, candidates)); }
        catch (Exception e) { throw new IllegalStateException("Invalid retention response", e); }
    }
    static String encode(byte[] bytes) { return Base64.encodeToString(bytes == null ? new byte[0] : bytes, Base64.NO_WRAP); }
    static byte[] decode(String value) { return Base64.decode(value, Base64.NO_WRAP); }
    @Keep private static native byte[] retentionNative(String op, byte[] state, byte[] next, byte[][] records);
    private PurpleSyncRetainedBases() { }
}
