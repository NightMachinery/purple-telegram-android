package org.telegram.messenger.purple;

@androidx.annotation.Keep
final class PurpleGateLoadNative {
    static native String prepareNative(byte[] settings, byte[] state, String deviceId, String platform, String deviceClass);
    static native boolean currentNative(long token);
    static native boolean publishNative(long token);
    static native void discardNative(long token);
    private PurpleGateLoadNative() { }
}
