package org.telegram.messenger.purple;

public final class PurpleDevice {
    public static volatile String current = "android-00000000";

    private PurpleDevice() {
    }

    public static String id() {
        return current;
    }
}
