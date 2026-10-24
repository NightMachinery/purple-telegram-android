package org.telegram.messenger.purple;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import org.json.JSONObject;

final class PurpleArchiveBinding {
    private static final BigInteger MAX_GENERATION = new BigInteger("18446744073709551615");
    final String account, store, key, generation;
    final JSONObject value;
    PurpleArchiveBinding(String account, JSONObject value) throws Exception {
        Object version = value.get("version");
        if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() != 1
            || !value.getString("format").equals("purple-archive-binding") || !account.equals(value.getString("account")))
            throw new IllegalStateException("archive binding format");
        this.account = account; this.value = value;
        store = value.getString("store"); key = value.getString("key"); generation = value.getString("generation");
        if (!PurpleSyncRecoveryFiles.key(account) || !PurpleArchiveKeys.token(store) || !PurpleArchiveKeys.token(key))
            throw new IllegalArgumentException("archive binding identity");
        generation(generation);
    }
    static PurpleArchiveBinding create(String account, String generation) throws Exception {
        return new PurpleArchiveBinding(account, new JSONObject().put("format", "purple-archive-binding").put("version", 1)
            .put("account", account).put("store", token()).put("key", token()).put("generation", generation));
    }
    JSONObject arguments() throws Exception {
        return new JSONObject().put("account", account).put("store", store).put("keyId", key);
    }
    String alias() { return PurpleArchiveKeys.alias(account, store, key); }
    byte[] bytes() throws Exception { return canonical(value.toString().getBytes(StandardCharsets.UTF_8)); }
    static JSONObject json(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length > 64 * 1024) throw new IllegalArgumentException("archive metadata size");
        byte[] canonical = canonical(bytes);
        String text = new String(canonical, StandardCharsets.UTF_8);
        if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), canonical)) throw new IllegalArgumentException("archive metadata UTF8");
        return new JSONObject(text);
    }
    static byte[] canonical(byte[] bytes) throws Exception {
        JSONObject result = PurpleArchiveCore.call("canonical", new JSONObject().put("bytes", PurpleArchiveCore.base64(bytes)), null);
        if (!result.optString("status").equals("Ready")) throw new IllegalArgumentException("archive metadata JSON");
        return PurpleArchiveCore.unbase64(result.getString("bytes"));
    }
    static BigInteger generation(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("archive generation");
        BigInteger result = new BigInteger(value);
        if (result.compareTo(MAX_GENERATION) > 0) throw new IllegalArgumentException("archive generation exhausted");
        return result;
    }
    static String next(String value) {
        BigInteger next = generation(value).add(BigInteger.ONE);
        if (next.compareTo(MAX_GENERATION) > 0) throw new IllegalArgumentException("archive generation exhausted");
        return next.toString();
    }
    static String token() {
        byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes); StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return result.toString();
    }
}
