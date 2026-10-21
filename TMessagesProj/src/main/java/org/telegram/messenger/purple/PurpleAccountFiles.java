package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;

public final class PurpleAccountFiles {
    public static final class Identity {
        public final int slot;
        public final String key;
        public final long userId;
        public final long lifetime;

        private Identity(int slot, String key, long userId, long lifetime) {
            this.slot = slot;
            this.key = key;
            this.userId = userId;
            this.lifetime = lifetime;
        }

        public boolean current() {
            Identity now = capture(slot);
            return now != null && key.equals(now.key) && lifetime == now.lifetime;
        }
    }

    private static final long[] lifetimes = new long[UserConfig.MAX_ACCOUNT_COUNT];
    public final File directory;
    public final File settings;
    public final File good;
    public final File backup;
    public final File importBackup;
    public final File syncDirectory;
    public final File syncStore;
    public final File history;
    public final File fingerprint;

    private PurpleAccountFiles(JSONObject paths) throws Exception {
        directory = file(paths.getString("directory"));
        settings = file(paths.getString("settings"));
        good = file(paths.getString("good"));
        backup = file(paths.getString("backup"));
        importBackup = file(paths.getString("importBackup"));
        syncDirectory = file(paths.getString("syncDirectory"));
        syncStore = file(paths.getString("syncStore"));
        history = file(paths.getString("history"));
        fingerprint = file(paths.getString("fingerprint"));
    }

    public static PurpleAccountFiles legacy() {
        String owner = PurpleAccountOwner.owner();
        return owner == null ? null : resolve(owner, owner);
    }

    public static File rootSettings() { PurpleAccountFiles f = legacy(); return f == null ? file("settings.toml") : f.settings; }
    public static File rootGood() { PurpleAccountFiles f = legacy(); return f == null ? file("settings.toml.good") : f.good; }
    public static File rootBackup() { PurpleAccountFiles f = legacy(); return f == null ? file("settings.toml.bak") : f.backup; }
    public static File rootImportBackup() { PurpleAccountFiles f = legacy(); return f == null ? file("settings.toml.import.bak") : f.importBackup; }
    public static File rootSync() { PurpleAccountFiles f = legacy(); return f == null ? file("sync") : f.syncDirectory; }
    public static File rootHistory() { PurpleAccountFiles f = legacy(); return f == null ? file("sync-history") : f.history; }

    public static File root() {
        return new File(ApplicationLoader.getFilesDirFixed(), "purple");
    }

    public static File file(String relative) {
        return relative.isEmpty() ? root() : new File(root(), relative);
    }

    public static synchronized Identity capture(int slot) {
        if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT) return null;
        UserConfig config = UserConfig.getInstance(slot);
        if (!config.isClientActivated() || config.getClientUserId() <= 0) return null;
        long id = config.getClientUserId();
        int environment = ConnectionsManager.getInstance(slot).isTestBackend() ? 1 : 0;
        if (!config.isClientActivated() || id != config.getClientUserId()) return null;
        final String key;
        try {
            PurpleCore.ensureLoaded();
            key = keyNative(environment, id);
        } catch (RuntimeException | UnsatisfiedLinkError e) { return null; }
        return key == null || key.isEmpty() ? null : new Identity(slot, key, id, lifetimes[slot]);
    }

    public static void userChanging(int slot, long newUserId) {
        if (slot >= 0 && slot < UserConfig.MAX_ACCOUNT_COUNT
                && UserConfig.getInstance(slot).getClientUserId() != newUserId) identityChanged(slot);
    }

    public static void identityChanged(int slot) {
        synchronized (PurpleAccountFiles.class) {
            if (slot >= 0 && slot < lifetimes.length) ++lifetimes[slot];
        }
        PurpleAccountSettings.invalidate();
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            PurpleAccountOwner.accountsChanged();
            if (PurpleAccountOwner.started()) PurpleGate.reload("account identity changed");
        });
    }

    public static PurpleAccountFiles resolve(String key, String owner) {
        if (key == null || owner == null) return null;
        try {
            PurpleCore.ensureLoaded();
            JSONObject paths = new JSONObject(resolveNative(key, owner));
            return paths.optBoolean("ok") ? new PurpleAccountFiles(paths) : null;
        } catch (Exception | UnsatisfiedLinkError e) {
            return null;
        }
    }

    private static native String keyNative(int environment, long userId);
    private static native String resolveNative(String key, String owner);
    private PurpleAccountFiles() { throw new AssertionError(); }
}
