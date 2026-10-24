package org.telegram.messenger.purple;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class PurpleArchiveKeys {
    interface Provider {
        SecretKey find(String alias) throws Exception;
        SecretKey create(String alias) throws Exception;
        void remove(String alias) throws Exception;
        java.util.Enumeration<String> aliases() throws Exception;
    }
    static class RemovalProvider implements Provider {
        final KeyStore store() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
        }
        public SecretKey find(String alias) throws Exception {
            KeyStore store = store();
            if (!store.containsAlias(alias)) return null;
            java.security.Key key = store.getKey(alias, null);
            if (!(key instanceof SecretKey)) throw new IllegalStateException("archive key unavailable");
            return (SecretKey) key;
        }
        public java.util.Enumeration<String> aliases() throws Exception { return store().aliases(); }
        public SecretKey create(String alias) throws Exception { throw new IllegalStateException("archive wrapping unavailable"); }
        public void remove(String alias) throws Exception { KeyStore store = store(); if (store.containsAlias(alias)) store.deleteEntry(alias); }
    }
    static final class AndroidProvider extends RemovalProvider {
        @Override public SecretKey create(String alias) throws Exception {
            if (Build.VERSION.SDK_INT < 23) throw new IllegalStateException("archive requires API23");
            KeyStore store = store();
            if (store.containsAlias(alias)) throw new IllegalStateException("archive alias already exists");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build());
            return generator.generateKey();
        }
    }
    static String prefix(String account) {
        if (!PurpleSyncRecoveryFiles.key(account)) throw new IllegalArgumentException("archive account key");
        return "purple.archive." + account + ".";
    }
    static String alias(String account, String store, String key) {
        if (!PurpleSyncRecoveryFiles.key(account) || !token(store) || !token(key)) throw new IllegalArgumentException("archive key identity");
        return prefix(account) + store + "." + key;
    }
    static boolean token(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,128}"); }
    private PurpleArchiveKeys() {}
}
