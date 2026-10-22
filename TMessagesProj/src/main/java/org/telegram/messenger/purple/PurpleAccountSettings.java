package org.telegram.messenger.purple;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public final class PurpleAccountSettings {
    public static final class Effective {
        final PurpleAccountFiles.Identity identity;
        final long epoch;
        final long handle;
        final long ownerRevision;
        public final boolean reasons;
        public final boolean trade;
        public final int holdSeconds;
        public final boolean globalGood;
        public final boolean accountGood;
        public final boolean globalReadError;
        public final boolean accountReadError;
        public final boolean globalGoodReadError;
        public final boolean accountGoodReadError;
        public final String globalGoodError;
        public final String accountGoodError;
        public final String globalError;
        public final String accountError;

        private Effective(PurpleAccountFiles.Identity identity, long epoch, long ownerRevision, JSONObject json,
                boolean globalReadError, boolean accountReadError,
                boolean globalGoodReadError, boolean accountGoodReadError) throws Exception {
            this.identity = identity;
            this.epoch = epoch;
            this.ownerRevision = ownerRevision;
            handle = Long.parseLong(json.getString("handle"));
            reasons = json.getBoolean("reasons");
            trade = json.getBoolean("trade");
            holdSeconds = Math.max(0, json.getInt("hold"));
            globalGood = json.getBoolean("globalGood");
            accountGood = json.getBoolean("accountGood");
            globalGoodError = json.getString("globalGoodError");
            accountGoodError = json.getString("accountGoodError");
            this.globalGoodReadError = globalGoodReadError;
            this.accountGoodReadError = accountGoodReadError;
            globalError = json.getString("globalError");
            accountError = json.getString("accountError");
            this.globalReadError = globalReadError;
            this.accountReadError = accountReadError;
        }

        boolean current() { return epoch == generation && ownerRevision == PurpleAccountOwner.observedRevision() && identity.current(); }
    }

    private static volatile long generation;
    private static final Map<String, Effective> effective = new HashMap<>();

    public static synchronized void invalidate() {
        ++generation;
        effective.clear();
        try { clearNative(); } catch (UnsatisfiedLinkError e) { }
    }

    public static Effective forAccount(int slot) {
        PurpleAccountOwner.ensureStarted();
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(slot);
        if (id == null) return null;
        Effective found;
        synchronized (PurpleAccountSettings.class) { found = effective.get(id.key); }
        if (found != null && found.current()) return found;
        final long startedGeneration = generation;
        final long startedOwnerRevision = PurpleAccountOwner.revision();
        PurpleAccountFiles global = PurpleAccountOwner.globalFiles();
        PurpleAccountFiles own = PurpleAccountOwner.ready()
                ? PurpleAccountFiles.resolve(id.key, PurpleAccountOwner.owner()) : null;
        File globalFile = global == null ? PurpleAccountFiles.file("settings.toml") : global.settings;
        File ownFile = own == null ? globalFile : own.settings;
        PurpleAccountFileSnapshot g = PurpleAccountFileSnapshot.read(globalFile);
        PurpleAccountFileSnapshot a = PurpleAccountFileSnapshot.read(ownFile);
        PurpleAccountFileSnapshot gg = PurpleAccountFileSnapshot.read(new File(globalFile.getPath() + ".good"));
        PurpleAccountFileSnapshot ag = PurpleAccountFileSnapshot.read(new File(ownFile.getPath() + ".good"));
        long candidate = 0;
        boolean published = false;
        try {
            PurpleCore.ensureLoaded();
            final long nativeEpoch;
            synchronized (PurpleAccountSettings.class) {
                if (startedGeneration != generation) return null;
                nativeEpoch = epochNative();
            }
            candidate = createNative(nativeEpoch, g.bytes, globalFile.getPath(), gg.bytes,
                    a.bytes, ownFile.getPath(), ag.bytes);
            if (candidate == 0) return null;
            JSONObject json = new JSONObject(describeNative(candidate));
            if (startedGeneration != generation || startedOwnerRevision != PurpleAccountOwner.revision()
                    || !g.sameRead(globalFile) || !a.sameRead(ownFile)
                    || !gg.sameRead(new File(globalFile.getPath() + ".good"))
                    || !ag.sameRead(new File(ownFile.getPath() + ".good"))) return null;
            found = new Effective(id, startedGeneration, startedOwnerRevision, json,
                    g.status == PurpleAccountFileSnapshot.Status.ReadError,
                    a.status == PurpleAccountFileSnapshot.Status.ReadError,
                    gg.status == PurpleAccountFileSnapshot.Status.ReadError,
                    ag.status == PurpleAccountFileSnapshot.Status.ReadError);
            if (!found.current()) return null;
            synchronized (PurpleAccountSettings.class) {
                if (found.epoch != generation || found.ownerRevision != PurpleAccountOwner.observedRevision()) return null;
                Effective winner = effective.get(id.key);
                if (winner != null && winner.epoch == generation
                        && winner.ownerRevision == found.ownerRevision
                        && winner.identity.lifetime == id.lifetime) return winner;
                if (winner != null) releaseNative(winner.handle);
                effective.put(id.key, found);
                published = true;
            }
            return found;
        } catch (Exception | UnsatisfiedLinkError e) {
            return null;
        } finally {
            if (candidate != 0 && !published) {
                try { releaseNative(candidate); } catch (UnsatisfiedLinkError e) { }
            }
        }
    }

    public static boolean preview(int slot, long peer, int kind) {
        Effective settings = forAccount(slot);
        try {
            return settings != null && settings.current() && previewNative(settings.handle, peer, kind)
                    && settings.current();
        } catch (RuntimeException | UnsatisfiedLinkError e) { return false; }
    }

    public static PurpleCore.LastSeenNote note(int slot, long peer, int reason, int shape) {
        Effective settings = forAccount(slot);
        if (settings == null || !settings.current()) return PurpleCore.LastSeenNote.PLAIN;
        final long[] result;
        try { result = noteNative(settings.handle, PurpleState.read(), peer, reason, shape); }
        catch (RuntimeException | UnsatisfiedLinkError e) { return PurpleCore.LastSeenNote.PLAIN; }
        if (result == null || result.length != 5 || !settings.current()) return PurpleCore.LastSeenNote.PLAIN;
        return new PurpleCore.LastSeenNote((int) result[0], result[1] != 0,
                result[2], result[3], (int) result[4]);
    }

    public static File fileForAccount(int slot) {
        if (!PurpleAccountOwner.ready()) return PurpleAccountFiles.rootSettings();
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(slot);
        PurpleAccountFiles files = id == null ? null : PurpleAccountFiles.resolve(id.key, PurpleAccountOwner.owner());
        return files == null || !id.current() ? null : files.settings;
    }

    public static File globalFile() {
        PurpleAccountOwner.ensureStarted();
        PurpleAccountFiles files = PurpleAccountOwner.globalFiles();
        return files == null ? PurpleAccountFiles.file("settings.toml") : files.settings;
    }

    public static byte[] globalBytes() { return PurpleAccountFileSnapshot.read(globalFile()).bytes; }

    static synchronized long generation() { return generation; }

    static native long epochNative();
    private static native long createNative(long epoch, byte[] global, String globalPath, byte[] globalGood,
            byte[] account, String accountPath, byte[] accountGood);
    private static native String describeNative(long handle);
    private static native void releaseNative(long handle);
    private static native String layerNative(byte[] global, String globalPath, byte[] globalGood,
            byte[] account, String accountPath, byte[] accountGood);
    private static native void clearNative();
    private static native boolean previewNative(long handle, long peer, int kind);
    private static native long[] noteNative(long handle, byte[] state, long peer, int reason, int shape);
    private PurpleAccountSettings() { }
}
