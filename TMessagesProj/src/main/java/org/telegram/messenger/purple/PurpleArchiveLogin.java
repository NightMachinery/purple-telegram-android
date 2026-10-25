package org.telegram.messenger.purple;

import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

final class PurpleArchiveLogin {
    interface Source { String nonce(int slot) throws Exception; }
    private static final String PREF = "purpleArchiveLoginV1";
    private static final class Login {
        final long user;
        final String nonce;
        Login(long user) { this.user = user; nonce = PurpleArchiveBinding.token(); }
    }
    private static final ConcurrentHashMap<Integer, Login> logins = new ConcurrentHashMap<>();
    static Source source = slot -> {
        Login login = logins.get(slot);
        if (login != null) {
            if (login.user != UserConfig.getInstance(slot).getClientUserId()) throw new PurpleArchiveStore.Failure("Archive login changed");
            return login.nonce;
        }
        return UserConfig.getInstance(slot).getPreferences().getString(PREF, null);
    };
    static void changing(int slot, long before, long after) {
        if (after != 0 && (before == 0 || before != after)) logins.put(slot, new Login(after));
    }
    static void write(int slot, long user, SharedPreferences.Editor editor) {
        Login login = logins.get(slot);
        if (login != null) {
            if (login.user != user) throw new IllegalStateException("Archive login changed");
            editor.putString(PREF, login.nonce);
        }
    }
    static String context(int slot) throws Exception {
        String nonce = source.nonce(slot);
        if (nonce == null || !nonce.matches("[0-9a-f]{32}")) throw new PurpleArchiveStore.Failure("Archive login nonce unavailable; discard required");
        return nonce;
    }
    static void admit(PurpleArchivePaths paths, String nonce, PurpleArchiveKeys.Provider keys) throws Exception { admit(paths, nonce, keys, true); }
    static void admit(PurpleArchivePaths paths, String nonce, PurpleArchiveKeys.Provider keys, boolean removeOrphan) throws Exception {
        if (nonce == null || !nonce.matches("[0-9a-f]{32}")) throw new PurpleArchiveStore.Failure("Archive login nonce invalid");
        PurpleArchiveCatalogue.requireAdmission(paths, keys);
        PurpleArchiveFiles.Snapshot old = paths.metadata.read("login.json", 65536);
        byte[] bound = paths.metadata.read("binding.json", 65536).bytes;
        byte[] creating = paths.metadata.read("creating.json", 65536).bytes;
        if (old.bytes == null) {
            if (bound != null || creating != null) throw new PurpleArchiveStore.Failure("Archive login record missing; discard required");
            return;
        }
        if (bound == null) throw new PurpleArchiveStore.Failure("Archive login binding missing; discard required");
        PurpleArchiveBinding binding = new PurpleArchiveBinding(paths.account, PurpleArchiveBinding.json(bound));
        JSONObject value = PurpleArchiveBinding.json(old.bytes);
        if (value.length() != 7 || !(value.get("version") instanceof Integer || value.get("version") instanceof Long) || value.getInt("version") != 1
            || !value.getString("account").equals(paths.account) || !value.getString("store").equals(binding.store)
            || !value.getString("key").equals(binding.key) || !value.getString("generation").equals(binding.generation)
            || !value.getString("nonce").matches("[0-9a-f]{32}")) throw new PurpleArchiveStore.Failure("Archive login record invalid; discard required");
        byte[] expected = PurpleArchiveCore.unbase64(value.getString("mac")); value.remove("mac");
        if (expected == null || expected.length != 32 || !MessageDigest.isEqual(expected, authenticate(paths, binding, value, keys)))
            throw new PurpleArchiveStore.Failure("Archive login record cannot be authenticated; discard required");
        if (!value.getString("nonce").equals(nonce)) {
            if (!removeOrphan) throw new PurpleArchiveStore.Failure("Archive login changed; reopen required");
            PurpleArchiveRemoval.begin(paths).finish(null, keys);
        }
    }
    static void bind(PurpleArchiveStore store, String nonce) throws Exception {
        PurpleArchiveFiles.Snapshot old = store.paths.metadata.read("login.json", 65536);
        if (old.bytes != null) return;
        JSONObject value = new JSONObject().put("version", 1).put("account", store.binding.account).put("store", store.binding.store)
            .put("key", store.binding.key).put("generation", store.binding.generation).put("nonce", nonce);
        value.put("mac", PurpleArchiveCore.base64(authenticate(store.paths, store.binding, value, store.environment.keys())));
        store.paths.metadata.publish("login.json", old, PurpleArchiveCatalogue.bytes(value));
    }
    private static byte[] authenticate(PurpleArchivePaths paths, PurpleArchiveBinding binding, JSONObject value, PurpleArchiveKeys.Provider keys) throws Exception {
        PurpleArchiveFiles.Snapshot wrapper = paths.store(binding.store, false).read("wrapped.key", 65536);
        SecretKey key = keys.find(binding.alias());
        if (key == null || wrapper.bytes == null) throw new PurpleArchiveStore.Failure("Archive login key unavailable; discard required");
        byte[] raw;
        try (PurpleArchiveCrypto wrapping = new PurpleArchiveCrypto(binding.alias(), key)) {
            raw = PurpleArchiveCore.envelopeNative(binding.account, binding.store, binding.key, binding.alias(), wrapper.bytes, false, wrapping);
        }
        if (raw == null || raw.length != 32) throw new PurpleArchiveStore.Failure("Archive login key cannot be authenticated; discard required");
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(raw, "HmacSHA256"));
            mac.update("purple-archive-login-v1\0".getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(PurpleArchiveCatalogue.bytes(value));
        } finally { Arrays.fill(raw, (byte) 0); }
    }
    private PurpleArchiveLogin() {}
}
