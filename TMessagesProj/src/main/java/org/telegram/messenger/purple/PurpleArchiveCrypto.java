package org.telegram.messenger.purple;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@androidx.annotation.Keep
final class PurpleArchiveCrypto implements AutoCloseable {
    private final String keyId;
    private byte[] dataKey, indexKey;
    private SecretKey wrappingKey;
    private Cipher pendingWrap;
    private final Thread owner = Thread.currentThread();
    private boolean closed;
    PurpleArchiveCrypto(String keyId, byte[] dataKey, byte[] indexKey) {
        if (dataKey == null || dataKey.length != 32 || indexKey == null || indexKey.length != 32)
            throw new IllegalArgumentException("archive key size");
        this.keyId = keyId; this.dataKey = dataKey.clone(); this.indexKey = indexKey.clone();
    }
    PurpleArchiveCrypto(String keyId, SecretKey wrappingKey) {
        if (wrappingKey == null) throw new IllegalArgumentException("archive wrapping key");
        this.keyId = keyId; this.wrappingKey = wrappingKey;
    }
    private boolean live() { return !closed && Thread.currentThread() == owner; }
    private SecretKey key() { return wrappingKey != null ? wrappingKey : new SecretKeySpec(dataKey, "AES"); }
    byte[] random(int size) {
        if (!live() || size < 0 || size > 4096) return null;
        try {
            if (wrappingKey != null) {
                if (size != 12 || pendingWrap != null) return null;
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length != 12) return null;
                pendingWrap = cipher; return iv.clone();
            }
            byte[] value = new byte[size]; new SecureRandom().nextBytes(value); return value;
        } catch (GeneralSecurityException | RuntimeException e) { return null; }
    }
    byte[][] seal(String id, byte[] nonce, byte[] plaintext, byte[] aad) {
        if (!live() || !keyId.equals(id) || nonce == null || nonce.length != 12 || plaintext == null
            || plaintext.length > 4 * 1024 * 1024 || aad == null) return null;
        try {
            Cipher cipher;
            if (wrappingKey != null) {
                cipher = pendingWrap; pendingWrap = null;
                if (cipher == null || !Arrays.equals(nonce, cipher.getIV())) return null;
            } else {
                cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            }
            cipher.updateAAD(aad); byte[] sealed = cipher.doFinal(plaintext);
            if (sealed.length != plaintext.length + 16) { Arrays.fill(sealed, (byte) 0); return null; }
            byte[][] result = {Arrays.copyOf(sealed, sealed.length - 16), Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length)};
            Arrays.fill(sealed, (byte) 0); return result;
        } catch (GeneralSecurityException | RuntimeException e) { pendingWrap = null; return null; }
    }
    byte[] open(String id, byte[] nonce, byte[] ciphertext, byte[] tag, byte[] aad) {
        if (!live() || !keyId.equals(id) || nonce == null || nonce.length != 12 || ciphertext == null
            || ciphertext.length > 4 * 1024 * 1024 || tag == null || tag.length != 16 || aad == null) return null;
        byte[] sealed = new byte[ciphertext.length + 16];
        System.arraycopy(ciphertext, 0, sealed, 0, ciphertext.length); System.arraycopy(tag, 0, sealed, ciphertext.length, 16);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce)); cipher.updateAAD(aad);
            return cipher.doFinal(sealed);
        } catch (GeneralSecurityException | RuntimeException e) { return null; }
        finally { Arrays.fill(sealed, (byte) 0); }
    }
    byte[] hmac(byte[] input) {
        if (!live() || indexKey == null || input == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(indexKey, "HmacSHA256")); return mac.doFinal(input);
        } catch (GeneralSecurityException | RuntimeException e) { return null; }
    }
    static byte[] derive(byte[] key, byte[] salt, byte[] info, int length) {
        if (key == null || salt == null || info == null || length < 1 || length > 255 * 32) return null;
        byte[] extracted = null, previous = new byte[0];
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256")); extracted = mac.doFinal(key);
            mac.init(new SecretKeySpec(extracted, "HmacSHA256")); byte[] result = new byte[length]; int offset = 0;
            for (int counter = 1; offset < length; ++counter) {
                mac.update(previous); mac.update(info); mac.update((byte) counter);
                byte[] next = mac.doFinal(); Arrays.fill(previous, (byte) 0); previous = next;
                int size = Math.min(previous.length, length - offset); System.arraycopy(previous, 0, result, offset, size); offset += size;
            }
            return result;
        } catch (GeneralSecurityException | RuntimeException e) { return null; }
        finally { if (extracted != null) Arrays.fill(extracted, (byte) 0); Arrays.fill(previous, (byte) 0); }
    }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("archive crypto ownership");
        closed = true; pendingWrap = null; wrappingKey = null;
        if (dataKey != null) Arrays.fill(dataKey, (byte) 0);
        if (indexKey != null) Arrays.fill(indexKey, (byte) 0);
        dataKey = null; indexKey = null;
    }
}
