package org.telegram.messenger.purple;

import java.util.Arrays;

public final class PurpleState {
    public static final int MAX_SIZE = 1024 * 1024;
    public static byte[] bytes;
    public static boolean fail;
    public static int writes;
    public static Runnable readHook;

    public static byte[] read() {
        if (readHook != null) readHook.run();
        return bytes == null ? null : bytes.clone();
    }

    public static boolean write(byte[] replacement) {
        ++writes;
        if (fail) return false;
        bytes = replacement.clone();
        return true;
    }

    static synchronized boolean compareAndWrite(byte[] expected, byte[] replacement) {
        return Arrays.equals(bytes, expected) && write(replacement);
    }

    static synchronized boolean replaceIfUnchanged(byte[] expected, byte[] replacement) {
        return compareAndWrite(expected, replacement);
    }
}
