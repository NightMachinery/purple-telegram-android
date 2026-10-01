package org.telegram.messenger.purple;

import java.util.function.Predicate;

public final class PurpleCore {
    private static boolean loaded;
    static volatile Predicate<byte[]> parses = text -> true;

    public static final class ParseResult {
        public final boolean ok;

        ParseResult(boolean ok) {
            this.ok = ok;
        }
    }

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

    public static ParseResult parse(byte[] utf8) {
        return new ParseResult(parses.test(utf8));
    }
}
