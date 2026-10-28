package org.telegram.messenger.purple;

import androidx.annotation.Keep;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

@Keep
public final class PurpleSyncChatMoveCore {
    public static final class Result {
        public final String status;
        public final JSONObject fields;

        private Result(byte[] response) throws JSONException {
            fields = new JSONObject(new String(response, StandardCharsets.UTF_8));
            status = fields.getString("status");
        }
    }

    public static Result request(String operation, byte[] input) {
        PurpleCore.ensureLoaded();
        final byte[] response = requestNative(operation, input);
        if (response == null) throw new IllegalStateException("Missing carry bridge response");
        try {
            return new Result(response);
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid carry bridge response", e);
        }
    }

    @Keep
    private static native byte[] requestNative(String operation, byte[] input);

    private PurpleSyncChatMoveCore() { }
}
