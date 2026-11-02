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
    /** The user id the persisted nonce belongs to, written beside it in the same editor. */
    private static final String PREF_USER = "purpleArchiveLoginUserV1";
    private static final String BOOTSTRAP = "purpleArchiveBootstrapV1";
    private static final class Login {
        final long user;
        final String nonce;
        Login(long user) { this(user, PurpleArchiveBinding.token()); }
        Login(long user, String nonce) { this.user = user; this.nonce = nonce; }
    }
    private static final ConcurrentHashMap<Integer, Login> logins = new ConcurrentHashMap<>();
    private static final java.util.Set<Integer> bootstrapFailed = ConcurrentHashMap.newKeySet();
    static Source source = slot -> {
        Login login = logins.get(slot);
        if (login != null) {
            if (login.user != UserConfig.getInstance(slot).getClientUserId()) throw new PurpleArchiveStore.Failure("Archive login changed");
            return login.nonce;
        }
        return UserConfig.getInstance(slot).getPreferences().getString(PREF, null);
    };
    /**
     * A user becomes current. A different user, or no user becoming one,
     * starts a new login with a fresh nonce, except when the slot still holds
     * a persisted nonce of this same user: then no login ended (logout clears
     * the preferences first, and every login screen path calls clearConfig),
     * the in-memory user was only missing, and minting would orphan the
     * archive bound to that nonce.
     */
    static void changing(int slot, long before, long after) {
        if (after == 0 || (before != 0 && before == after)) return;
        Login login = before == 0 ? persisted(slot, after) : null;
        logins.put(slot, login != null ? login : new Login(after));
        bootstrapFailed.remove(slot);
    }
    /** The persisted nonce of {@code user} in this slot, or null when it is absent, invalid or another user's. */
    private static Login persisted(int slot, long user) {
        try {
            SharedPreferences preferences = UserConfig.getInstance(slot).getPreferences();
            String nonce = preferences == null ? null : preferences.getString(PREF, null);
            String owner = nonce == null ? null : preferences.getString(PREF_USER, null);
            if (nonce == null || !nonce.matches("[0-9a-f]{32}") || owner == null || !owner.equals(Long.toString(user))) return null;
            return new Login(user, nonce);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }
    static void write(int slot, long user, SharedPreferences.Editor editor) {
        Login login = logins.get(slot);
        if (login != null) {
            if (login.user != user) throw new IllegalStateException("Archive login changed");
            editor.putString(PREF, login.nonce).putString(PREF_USER, Long.toString(user)).remove(BOOTSTRAP);
        }
    }
    static void loaded(int slot, SharedPreferences preferences, String serializedUser) { loaded(slot, preferences, serializedUser, 0); }
    /**
     * A persisted user decoded. Bootstraps a nonce for a login made before
     * the archive existed, and records the nonce's owner for a nonce saved
     * before the owner was kept beside it.
     */
    static void loaded(int slot, SharedPreferences preferences, String serializedUser, long user) {
        try {
            if (serializedUser == null) return;
            String existing = preferences.getString(PREF, null);
            if (existing != null) {
                if (user != 0 && preferences.getString(PREF_USER, null) == null) preferences.edit().putString(PREF_USER, Long.toString(user)).apply();
                return;
            }
            String nonce = PurpleArchiveBinding.token();
            SharedPreferences.Editor editor = preferences.edit().putString("user", serializedUser).putString(PREF, nonce).putBoolean(BOOTSTRAP, true);
            if (user != 0) editor.putString(PREF_USER, Long.toString(user));
            if (editor.commit()) bootstrapFailed.remove(slot);
            else {
                bootstrapFailed.add(slot);
                PurpleArchiveFailures.record(slot, "login_bootstrap", new PurpleArchiveStore.Failure("Archive login persistence failed"));
            }
        } catch (Exception | LinkageError e) { bootstrapFailed.add(slot); PurpleArchiveFailures.record(slot, "login_bootstrap", e); }
    }
    static boolean orphanRemovalAllowed(int slot) {
        if (logins.containsKey(slot)) return true;
        SharedPreferences preferences = UserConfig.getInstance(slot).getPreferences();
        return preferences != null && !preferences.getBoolean(BOOTSTRAP, false);
    }
    static String context(int slot) throws Exception {
        if (bootstrapFailed.contains(slot)) throw new PurpleArchiveStore.Failure("Archive login persistence failed");
        String nonce = source.nonce(slot);
        if (nonce == null || !nonce.matches("[0-9a-f]{32}")) throw new PurpleArchiveStore.Failure("Archive login nonce unavailable; discard required");
        return nonce;
    }
    static void prepareBootstrap(PurpleArchivePaths paths, PurpleArchiveKeys.Provider keys, java.util.function.BooleanSupplier current) throws Exception {
        if (PurpleArchiveCatalogue.hasUnexpectedStore(paths, null) || PurpleArchiveCatalogue.hasUnexpectedKey(paths.account, keys, null)) return;
        try (PurpleArchiveFiles.Listing listing = paths.metadata.listing()) {
            for (;;) {
                String[] page = listing.next(); if (page.length == 0) break;
                for (String name : page) if (!name.equals("lock") && !name.equals("capture-gap.json") && !name.equals("gap-outbox.json")) return;
            }
        }
        PurpleArchiveFiles.Snapshot outbox = paths.metadata.read("gap-outbox.json", 65536);
        if (outbox.bytes == null) return;
        if (!current.getAsBoolean()) throw new PurpleArchiveStore.Failure("Account changed");
        PurpleArchiveFiles.Snapshot gap = paths.metadata.read("capture-gap.json", 65536);
        if (gap.bytes == null) paths.metadata.publish("capture-gap.json", gap, PurpleArchiveCatalogue.bytes(new JSONObject()
            .put("account", paths.account).put("reason", "capture_interrupted").put("observedAt", Long.toString(System.currentTimeMillis() / 1000))));
        if (!current.getAsBoolean()) throw new PurpleArchiveStore.Failure("Account changed");
        paths.metadata.remove("gap-outbox.json", false);
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
