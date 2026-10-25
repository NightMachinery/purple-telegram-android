package org.telegram.messenger;
public final class Utilities {
    public static void aesCtrDecryptionByteArray(byte[] bytes, byte[] key, byte[] iv, int offset, long length, int n) {
        if (n != 0 || length > Integer.MAX_VALUE) throw new IllegalArgumentException();
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
            byte[] result = cipher.doFinal(bytes, offset, (int) length);
            System.arraycopy(result, 0, bytes, offset, result.length); java.util.Arrays.fill(result, (byte) 0);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
