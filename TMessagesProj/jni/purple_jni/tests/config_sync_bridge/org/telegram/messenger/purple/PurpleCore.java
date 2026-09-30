package org.telegram.messenger.purple;

public final class PurpleCore {
    private static boolean loaded;

    private PurpleCore() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        final String path = System.getProperty("purple.core.dylib");
        if (path == null) {
            throw new UnsatisfiedLinkError("no purplecore on this host");
        }
        System.load(path);
        loaded = true;
    }
}
