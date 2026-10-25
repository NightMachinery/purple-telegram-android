package org.telegram.messenger.purple;

import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleArchiveInline {
    static void capture(PurpleArchiveStore store, PurpleArchiveCapture.Input input, BooleanSupplier active) throws Exception {
        if (input.body == null) return;
        JSONObject body = new JSONObject(input.body); JSONArray values = body.optJSONArray("inlineMedia");
        if (values == null || values.length() == 0) return;
        if (values.length() > 32) throw new PurpleArchiveStore.Failure("Archive inline media count");
        JSONObject key = new JSONObject(input.key); int bytes = 0;
        for (int i = 0; i < values.length(); ++i) {
            byte[] data = null;
            try {
                if (!active.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive inline media revoked");
                JSONObject value = values.getJSONObject(i); String media = value.getString("identity");
                data = PurpleArchiveCore.unbase64(value.getString("bytes")); bytes = Math.addExact(bytes, data.length);
                if (bytes > 256 * 1024) throw new PurpleArchiveStore.Failure("Archive inline media budget");
                JSONObject source;
                try (PurpleArchiveSlice slice = new PurpleArchiveSlice(store, key, null)) {
                    String hash = PurpleArchiveCore.call("snapshot", store.arguments().put("key", key).put("body", body), store.crypto()).getString("hash");
                    if (slice.head == null || !slice.facts.getString("hash").equals(hash)) throw new PurpleArchiveStore.Failure("Archive inline body changed");
                    boolean complete = false;
                    for (int n = 0; n < slice.head.getJSONArray("media").length(); ++n) {
                        JSONObject ref = slice.head.getJSONArray("media").getJSONObject(n);
                        if (ref.getString("identity").equals(media) && ref.getInt("state") == 2) complete = true;
                    }
                    if (complete) continue;
                    source = PurpleArchiveStaging.source(store, slice, media);
                }
                PurpleArchiveStaging staging = PurpleArchiveStaging.begin(store, source, data.length, active);
                try {
                    for (int offset = 0; offset < data.length; offset += 65536) {
                        byte[] piece = Arrays.copyOfRange(data, offset, Math.min(data.length, offset + 65536));
                        try { staging.piece(offset, piece); } finally { Arrays.fill(piece, (byte) 0); }
                    }
                    staging.admitConsistency(PurpleArchiveStaging.hash(data), "immutable_inline_bytes");
                    if (!staging.finish()) throw new PurpleArchiveStore.Failure("Archive inline media incomplete");
                } catch (Exception | LinkageError e) {
                    try { staging.refuse(store::current); } catch (Exception | LinkageError ignored) {}
                    throw e;
                } finally { staging.closeFinalizer(); }
            } catch (Exception | LinkageError e) {
                if (!active.getAsBoolean()) throw e;
                PurpleArchiveGaps.record(store, "inline_media_unavailable", System.currentTimeMillis() / 1000);
            } finally { if (data != null) Arrays.fill(data, (byte) 0); }
        }
    }
    private PurpleArchiveInline() {}
}
