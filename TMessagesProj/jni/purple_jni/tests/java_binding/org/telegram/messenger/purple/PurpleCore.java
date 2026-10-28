package org.telegram.messenger.purple;

public final class PurpleCore {
    private PurpleCore() { }
    public static void ensureLoaded() {
        System.load(System.getProperty("purple.test.library"));
    }
}
