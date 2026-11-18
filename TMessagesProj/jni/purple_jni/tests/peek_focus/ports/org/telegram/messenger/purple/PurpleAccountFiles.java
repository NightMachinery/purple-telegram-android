package org.telegram.messenger.purple;

import java.io.File;
import org.telegram.messenger.UserConfig;

public final class PurpleAccountFiles {
    public static long lifetime;
    public static boolean signedIn = true;
    private static final File TOP = new File("peek-focus-synthetic");

    public static final class Identity {
        public final int slot;
        public final String key;
        public final long userId, lifetime;

        Identity(int slot) {
            this.slot = slot;
            userId = 101 + slot;
            key = "0-" + userId;
            lifetime = PurpleAccountFiles.lifetime;
        }

        public boolean current() { return signedIn && lifetime == PurpleAccountFiles.lifetime; }
        boolean matchesSlot() { return current(); }
    }

    public final File settings, good;

    private PurpleAccountFiles(String key) {
        settings = file(key + "/settings.toml");
        good = new File(settings.getPath() + ".good");
    }

    public static Identity capture(int slot) {
        return signedIn && slot >= 0 && slot < UserConfig.MAX_ACCOUNT_COUNT ? new Identity(slot) : null;
    }

    public static PurpleAccountFiles resolve(String key, String owner) { return new PurpleAccountFiles(key); }
    public static File root() { return TOP; }
    public static File file(String name) { return new File(TOP, name); }
}
