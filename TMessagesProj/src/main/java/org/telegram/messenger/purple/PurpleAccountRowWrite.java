package org.telegram.messenger.purple;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.json.JSONObject;
import org.telegram.messenger.NotificationCenter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PurpleAccountRowWrite {
    public interface Current { boolean current(); }

    private static boolean writing;

    private static final class Stamp {
        final File file;
        final boolean directory;
        final boolean missing;
        final long device;
        final long inode;
        final int mode;
        final PurpleAccountFileSnapshot snapshot;

        Stamp(File file, boolean directory) throws Exception {
            this.file = file;
            this.directory = directory;
            StructStat stat = null;
            try { stat = Os.lstat(file.getPath()); }
            catch (ErrnoException e) { if (e.errno != OsConstants.ENOENT) throw e; }
            missing = stat == null;
            if (stat != null && !(directory ? OsConstants.S_ISDIR(stat.st_mode) : OsConstants.S_ISREG(stat.st_mode))) {
                throw new IllegalStateException("unsafe row path");
            }
            device = stat == null ? 0 : stat.st_dev;
            inode = stat == null ? 0 : stat.st_ino;
            mode = stat == null ? 0 : stat.st_mode;
            snapshot = directory ? null : PurpleAccountFileSnapshot.read(file);
            if (snapshot != null && snapshot.status == PurpleAccountFileSnapshot.Status.ReadError) {
                throw new IllegalStateException("unreadable row input");
            }
        }

        boolean current() {
            try {
                Stamp now = new Stamp(file, directory);
                return missing == now.missing && device == now.device && inode == now.inode && mode == now.mode
                        && (directory || snapshot.same(now.snapshot));
            } catch (Exception e) { return false; }
        }
    }

    public static final class Intent {
        public final boolean value;
        public final boolean pinned;
        public final boolean fileLocal;
        public final boolean globalAccount;
        public final int account;
        public final String globalKey;
        private final int purpose;
        private final Current view;
        private final PurpleAccountFiles.Identity origin;
        private final String owner;
        private final long revision;
        private final long roleEpoch;
        private final long generation;
        private final long nativeEpoch;
        private final File target;
        private final File good;
        private final PurpleAccountFileSnapshot own;
        private final Map<String, Stamp> inputs = new LinkedHashMap<>();
        private final Map<String, Stamp> directories = new LinkedHashMap<>();
        private final ArrayList<Stamp> createdDirectories = new ArrayList<>();

        private Intent(PurpleAccountFiles.Identity origin, Current view, int purpose) throws Exception {
            this.origin = origin;
            this.view = view;
            this.purpose = purpose;
            account = origin.slot;
            owner = PurpleAccountOwner.owner();
            globalKey = PurpleAccountOwner.global();
            revision = PurpleAccountOwner.revision();
            roleEpoch = PurpleAccountOwner.roleEpoch();
            generation = PurpleAccountSettings.generation();
            nativeEpoch = PurpleAccountSettings.epochNative();
            PurpleAccountFiles files = PurpleAccountFiles.resolve(origin.key, owner);
            PurpleAccountFiles global = PurpleAccountFiles.resolve(globalKey, owner);
            if (files == null || global == null) throw new IllegalStateException("missing row role");
            target = files.settings;
            good = files.good;
            globalAccount = origin.key.equals(globalKey);
            fileLocal = purpose == 2;
            for (File file : new File[]{target, good, global.settings, global.good,
                    PurpleAccountFiles.file("local.toml"), PurpleAccountFiles.file("accounts/root.toml")}) {
                inputs.put(file.getPath(), new Stamp(file, false));
                ArrayList<File> chain = new ArrayList<>();
                for (File parent = file.getParentFile(); parent != null
                        && !parent.equals(PurpleAccountFiles.root().getParentFile()); parent = parent.getParentFile()) chain.add(0, parent);
                for (File parent : chain) directories.put(parent.getPath(), new Stamp(parent, true));
            }
            own = inputs.get(target.getPath()).snapshot;
            JSONObject description = new JSONObject(planNative(own.bytes, target.getPath(),
                    inputs.get(good.getPath()).snapshot.bytes, purpose, -2));
            if (!description.optBoolean("ok")) throw new IllegalStateException("invalid row input");
            pinned = description.getBoolean("pinned");
            PurpleAccountSettings.Effective effective = PurpleAccountSettings.forAccount(account);
            if (effective == null || !effective.current() || effective.identity.lifetime != origin.lifetime
                    || !effective.identity.key.equals(origin.key)) throw new IllegalStateException("stale row account");
            value = fileLocal ? description.getBoolean("value") : purpose == 0 ? effective.reasons : effective.trade;
            if (!current()) throw new IllegalStateException("stale row capture");
        }

        public boolean canReset() { return !fileLocal && !globalAccount && pinned && current(); }

        public boolean overriddenBy(int slot) {
            if (fileLocal || !globalAccount || !current()) return false;
            PurpleAccountFiles.Identity other = PurpleAccountFiles.capture(slot);
            if (other == null || other.key.equals(globalKey)) return false;
            PurpleAccountFiles files = PurpleAccountFiles.resolve(other.key, owner);
            if (files == null) return false;
            PurpleAccountFileSnapshot primary = PurpleAccountFileSnapshot.read(files.settings);
            PurpleAccountFileSnapshot fallback = PurpleAccountFileSnapshot.read(files.good);
            if (primary.status == PurpleAccountFileSnapshot.Status.ReadError
                    || fallback.status == PurpleAccountFileSnapshot.Status.ReadError) return false;
            try {
                JSONObject result = new JSONObject(planNative(primary.bytes, files.settings.getPath(), fallback.bytes, purpose, -2));
                return result.optBoolean("ok") && result.optBoolean("pinned") && other.current()
                        && primary.matches(files.settings) && fallback.matches(files.good) && current();
            } catch (Exception | UnsatisfiedLinkError e) { return false; }
        }

        private boolean rolesCurrent(long expectedGeneration, long expectedNativeEpoch) {
            return view.current() && origin.current() && PurpleAccountOwner.ready()
                    && revision == PurpleAccountOwner.revision() && roleEpoch == PurpleAccountOwner.roleEpoch()
                    && Objects.equals(owner, PurpleAccountOwner.owner()) && Objects.equals(globalKey, PurpleAccountOwner.global())
                    && generation == expectedGeneration && nativeEpoch == expectedNativeEpoch
                    && PurpleAccountSettings.generation() == expectedGeneration
                    && PurpleAccountSettings.epochNative() == expectedNativeEpoch;
        }

        private boolean current() {
            if (!rolesCurrent(generation, nativeEpoch)) return false;
            for (Stamp stamp : inputs.values()) if (!stamp.current()) return false;
            for (Stamp stamp : directories.values()) if (!stamp.current()) return false;
            return true;
        }

        private boolean makeParents() throws Exception {
            for (Map.Entry<String, Stamp> entry : directories.entrySet()) {
                Stamp previous = entry.getValue();
                if (!previous.missing) continue;
                if (!current() || !previous.file.mkdir()) return false;
                Os.chmod(previous.file.getPath(), 0700);
                Stamp created = new Stamp(previous.file, true);
                createdDirectories.add(created);
                entry.setValue(created);
                if (!current()) return false;
            }
            return true;
        }

        private void cleanupDirectories() {
            for (int i = createdDirectories.size() - 1; i >= 0; --i) {
                Stamp stamp = createdDirectories.get(i);
                if (stamp.current()) stamp.file.delete();
            }
        }

        private boolean committedCurrent(Map<String, Stamp> committed) {
            return revision == PurpleAccountOwner.revision()
                    && PurpleAccountSettings.generation() == generation + 1
                    && PurpleAccountSettings.epochNative() == nativeEpoch + 1 && committedInputsCurrent(committed);
        }

        private boolean committedInputsCurrent(Map<String, Stamp> committed) {
            if (!view.current() || !origin.current() || !PurpleAccountOwner.ready()
                    || roleEpoch != PurpleAccountOwner.roleEpoch()
                    || !Objects.equals(owner, PurpleAccountOwner.owner()) || !Objects.equals(globalKey, PurpleAccountOwner.global())) return false;
            for (Stamp stamp : committed.values()) if (!stamp.current()) return false;
            for (Stamp stamp : directories.values()) if (!stamp.current()) return false;
            return true;
        }

        public String set(boolean value) { return apply(value ? 1 : 0); }
        public String reset() { return canReset() ? apply(-1) : "row is not a current pinned account value"; }

        private String apply(int action) {
            synchronized (PurpleAccountOwner.class) {
                synchronized (PurpleAccountFiles.class) {
                    synchronized (PurpleAccountSettings.class) {
                        if (writing || !current()) return "account row changed";
                        writing = true;
                        boolean published = false;
                        try {
                            if (globalAccount && own.status == PurpleAccountFileSnapshot.Status.Missing
                                    && inputs.get(good.getPath()).snapshot.status != PurpleAccountFileSnapshot.Status.Missing) {
                                return "repair the app-wide settings file before changing this row";
                            }
                            JSONObject result = new JSONObject(planNative(own.bytes, target.getPath(),
                                    inputs.get(good.getPath()).snapshot.bytes, purpose, action));
                            if (!result.optBoolean("ok")) return "account file is not safely editable";
                            if (!result.optBoolean("changed")) return null;
                            byte[] bytes = outputBytes(result.getString("text"));
                            if (bytes == null) return "account file would exceed the readable byte limit";
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated);
                            if (!current() || !makeParents() || !current()) return "account row changed";
                            published = PurpleRowFileMutation.replace(target, bytes, new PurpleRowFileMutation.Guard() {
                                public boolean current() { return Intent.this.current(); }
                                public boolean prepare(File temporary) throws Exception { Os.chmod(temporary.getPath(), 0600); return current(); }
                            });
                            if (!published) return "account row changed or could not be written";
                            PurpleAccountFileSnapshot actual = PurpleAccountFileSnapshot.read(target);
                            if (!Arrays.equals(bytes, actual.bytes) || actual.status != PurpleAccountFileSnapshot.Status.Present) {
                                return "account row publication could not be verified";
                            }
                            Map<String, Stamp> committed = new LinkedHashMap<>(inputs);
                            committed.put(target.getPath(), new Stamp(target, false));
                            boolean allowSend = !result.optBoolean("created") && result.optInt("version") <= 2;
                            if (globalAccount) PurpleGate.reload("account settings row");
                            else PurpleAccountSettings.invalidate();
                            Stamp committedGood = new Stamp(good, false);
                            if (!inputs.get(good.getPath()).current()) {
                                if (!globalAccount || !Arrays.equals(committedGood.snapshot.bytes, bytes)) {
                                    return "settings context changed after writing";
                                }
                                committed.put(good.getPath(), committedGood);
                            }
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated);
                            if (!committedCurrent(committed)) return "settings context changed after writing";
                            PurpleSaveDebounce.Receipt receipt = new PurpleSaveDebounce.RefreshReceipt(
                                    () -> committedInputsCurrent(committed), new PurpleSaveDebounce.Versions() {
                                        public long revision() { return PurpleAccountOwner.revision(); }
                                        public long generation() { return PurpleAccountSettings.generation(); }
                                        public long nativeEpoch() { return PurpleAccountSettings.epochNative(); }
                                    }, revision, generation + 1, nativeEpoch + 1);
                            PurpleSync.afterAccountRowWrite(origin, target, bytes, receipt, allowSend, allowSend && purpose != 2);
                            return null;
                        } catch (Exception | UnsatisfiedLinkError e) {
                            return "account row could not be safely written";
                        } finally {
                            if (!published) cleanupDirectories();
                            writing = false;
                        }
                    }
                }
            }
        }
    }

    public static Intent reasons(int account, Current view) { return capture(account, view, 0); }
    public static Intent trade(int account, Current view) { return capture(account, view, 1); }
    public static Intent sendAfterSave(int account, Current view) { return capture(account, view, 2); }

    private static Intent capture(int account, Current view, int purpose) {
        synchronized (PurpleAccountOwner.class) {
            synchronized (PurpleAccountFiles.class) {
                synchronized (PurpleAccountSettings.class) {
                    if (writing || view == null || !view.current() || !PurpleAccountOwner.ready()) return null;
                    PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
                    if (origin == null) return null;
                    try { return new Intent(origin, view, purpose); }
                    catch (Exception | UnsatisfiedLinkError e) { return null; }
                }
            }
        }
    }

    static byte[] outputBytes(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= PurpleAccountFileSnapshot.LIMIT ? bytes : null;
    }

    private static native String planNative(byte[] own, String path, byte[] good, int purpose, int action);
    private PurpleAccountRowWrite() { }
}
