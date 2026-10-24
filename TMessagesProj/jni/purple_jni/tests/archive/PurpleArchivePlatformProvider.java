package org.telegram.messenger.purple;

import android.security.keystore.KeyGenParameterSpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class PurpleArchivePlatformProvider extends Provider {
    static final ConcurrentHashMap<String, SecretKey> keys = new ConcurrentHashMap<>();
    static final AtomicInteger creates = new AtomicInteger(), removals = new AtomicInteger();
    static volatile boolean failRemoval;
    static volatile KeyGenParameterSpec last;
    public PurpleArchivePlatformProvider() {
        super("AndroidKeyStore", 1.0, "Host platform port; no hardware or non-exportability claim");
        put("KeyStore.AndroidKeyStore", Store.class.getName());
        put("KeyGenerator.AES", Generator.class.getName());
    }
    public static final class Generator extends KeyGeneratorSpi {
        private KeyGenParameterSpec spec;
        protected void engineInit(SecureRandom random) { throw new IllegalArgumentException("explicit archive key policy required"); }
        protected void engineInit(int size, SecureRandom random) { throw new IllegalArgumentException("explicit archive alias required"); }
        protected void engineInit(AlgorithmParameterSpec parameters, SecureRandom random) {
            spec = (KeyGenParameterSpec) parameters;
            if (spec.size != 256 || spec.purposes != 3 || !spec.randomized || spec.authentication
                || spec.modes.length != 1 || !spec.modes[0].equals("GCM") || spec.paddings.length != 1 || !spec.paddings[0].equals("NoPadding"))
                throw new IllegalArgumentException("archive Keystore policy");
            last = spec;
        }
        protected SecretKey engineGenerateKey() {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            SecretKey key = new SecretKeySpec(bytes, "AES"); java.util.Arrays.fill(bytes, (byte) 0);
            if (keys.putIfAbsent(spec.alias, key) != null) throw new IllegalStateException("alias recreation");
            creates.incrementAndGet(); return key;
        }
    }
    public static final class Store extends KeyStoreSpi {
        public Key engineGetKey(String alias, char[] password) { return keys.get(alias); }
        public Certificate[] engineGetCertificateChain(String alias) { return null; }
        public Certificate engineGetCertificate(String alias) { return null; }
        public Date engineGetCreationDate(String alias) { return keys.containsKey(alias) ? new Date(0) : null; }
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) { throw new UnsupportedOperationException(); }
        public void engineSetKeyEntry(String alias, byte[] bytes, Certificate[] chain) { throw new UnsupportedOperationException(); }
        public void engineSetCertificateEntry(String alias, Certificate certificate) { throw new UnsupportedOperationException(); }
        public void engineDeleteEntry(String alias) throws KeyStoreException {
            if (failRemoval) throw new KeyStoreException("injected platform removal interruption");
            keys.remove(alias); removals.incrementAndGet();
        }
        public Enumeration<String> engineAliases() { return Collections.enumeration(keys.keySet()); }
        public boolean engineContainsAlias(String alias) { return keys.containsKey(alias); }
        public int engineSize() { return keys.size(); }
        public boolean engineIsKeyEntry(String alias) { return keys.containsKey(alias); }
        public boolean engineIsCertificateEntry(String alias) { return false; }
        public String engineGetCertificateAlias(Certificate certificate) { return null; }
        public void engineStore(OutputStream output, char[] password) { throw new UnsupportedOperationException(); }
        public void engineLoad(InputStream input, char[] password) { if (input != null) throw new UnsupportedOperationException(); }
    }
}
