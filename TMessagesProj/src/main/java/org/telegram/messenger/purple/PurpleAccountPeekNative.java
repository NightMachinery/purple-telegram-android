package org.telegram.messenger.purple;

@androidx.annotation.Keep
final class PurpleAccountPeekNative {
    static final int READ = 0;
    static final int START = 1;
    static final int EXTEND = 2;
    static final int STOP = 3;
    static final int APP_LOCK = 4;
    static final int SCREEN_LOCK = 5;

    static native String changeNative(byte[] state, long handle, int operation, int seconds);
    static native boolean withdrawNative(long handle);
    static native String normalizeNative(byte[] state, String[] stoppedKeys, boolean all, byte[] local, String global);

    private PurpleAccountPeekNative() { }
}
