package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PurpleAccountOwner {
    private static boolean started;
    private static String owner;
    private static String global;
    private static boolean ready;
    private static boolean recovered;
    private static boolean localWarning;
    private static JSONObject plan;
    private static volatile long revision;
    private static long roleEpoch;
    private static String bindingAccount;

    public static synchronized void ensureStarted() {
        if (!started) {
            started = true;
            startup(null);
        }
    }

    private static void startup(String choice) {
        ready = false;
        try {
            PurpleCore.ensureLoaded();
            File localFile = PurpleAccountFiles.file("local.toml");
            File ownerFile = PurpleAccountFiles.file("accounts/root.toml");
            PurpleAccountFileSnapshot local = PurpleAccountFileSnapshot.read(localFile);
            PurpleAccountFileSnapshot record = PurpleAccountFileSnapshot.read(ownerFile);
            int accounts = PurpleAccountFileSnapshot.directory(PurpleAccountFiles.file("accounts"));
            if (local.status == PurpleAccountFileSnapshot.Status.ReadError
                    || record.status == PurpleAccountFileSnapshot.Status.ReadError || accounts < 0) {
                plan = null;
                ++revision;
                return;
            }
            List<PurpleAccountFiles.Identity> identities = signedIn();
            String[] keys = new String[identities.size()];
            boolean[] dirs = new boolean[keys.length];
            boolean[] bindings = new boolean[keys.length];
            PurpleAccountFileSnapshot store = PurpleAccountFileSnapshot.read(PurpleAccountFiles.file("sync/state.json"));
            for (int i = 0; i < keys.length; ++i) {
                PurpleAccountFiles.Identity id = identities.get(i);
                keys[i] = id.key;
                int directory = PurpleAccountFileSnapshot.directory(PurpleAccountFiles.file("accounts/" + id.key));
                if (directory < 0) { plan = null; ++revision; return; }
                dirs[i] = directory == 1;
                bindings[i] = store.status == PurpleAccountFileSnapshot.Status.Present
                        && bindingMatches(id.slot, store.bytes);
            }
            bindingAccount = null;
            for (int i = 0; i < bindings.length; ++i) {
                if (bindings[i]) { bindingAccount = bindingAccount == null ? keys[i] : ""; }
            }
            JSONObject next = new JSONObject(planNative(local.bytes, record.bytes, accounts == 1,
                    keys, dirs, bindings, choice));
            for (PurpleAccountFiles.Identity id : identities) {
                if (!id.current()) { plan = null; ++revision; return; }
            }
            if (!sameAccounts(identities, signedIn())
                    || (bindingAccount != null && !bindingAccount.isEmpty() && !store.matches(PurpleAccountFiles.file("sync/state.json")))
                    || !local.matches(localFile)
                    || !record.matches(ownerFile)
                    || accounts != PurpleAccountFileSnapshot.directory(PurpleAccountFiles.file("accounts"))) {
                plan = null; ++revision; return;
            }
            String confirmed = next.optString("owner");
            String pending = next.optString("pendingOwner");
            String targetOwner = confirmed.isEmpty() ? pending : confirmed;
            if (owner != null && !owner.equals(targetOwner)) { plan = null; ++revision; return; }
            if (!confirmed.isEmpty()) owner = confirmed;
            plan = next;
            recovered |= next.optBoolean("recovered");
            JSONArray writes = next.getJSONArray("writes");
            for (int i = 0; i < writes.length(); ++i) {
                JSONObject write = writes.getJSONObject(i);
                String path = write.getString("path");
                if (!path.equals("accounts/root.toml") && !path.equals("local.toml")) return;
                for (PurpleAccountFiles.Identity id : identities) if (!id.current()) return;
                if (!sameAccounts(identities, signedIn()) || !local.matches(localFile) || !record.matches(ownerFile)
                        || (bindingAccount != null && !bindingAccount.isEmpty()
                        && !store.matches(PurpleAccountFiles.file("sync/state.json")))) return;
                File target = PurpleAccountFiles.file(path);
                if (!PurpleAccountFileSnapshot.replace(target,
                        path.equals("local.toml") ? local : record,
                        write.getString("text").getBytes(StandardCharsets.UTF_8))) return;
                if (path.equals("local.toml")) local = PurpleAccountFileSnapshot.read(localFile);
                else {
                    record = PurpleAccountFileSnapshot.read(ownerFile);
                    owner = targetOwner;
                    global = owner;
                }
            }
            if (!targetOwner.isEmpty()) {
                JSONObject verified = new JSONObject(planNative(local.bytes, record.bytes, true,
                        keys, dirs, bindings, null));
                if (verified.optInt("status", -1) != 0
                        || !targetOwner.equals(verified.optString("owner"))
                        || verified.getJSONArray("writes").length() != 0) return;
                owner = targetOwner;
                plan = verified;
                ready = verified.optBoolean("automatic");
            }
            reloadGlobal();
        } catch (Exception | UnsatisfiedLinkError e) {
            FileLog.e("Purple: account owner input unavailable; operations held");
            plan = null;
        } finally {
            ++revision;
        }
    }

    private static boolean bindingMatches(int slot, byte[] bytes) {
        PurpleAccountSyncCore.Result checked = PurpleAccountSyncCore.checkAccountBinding(slot, bytes);
        return checked.isValid() && "Bound".equals(checked.verdict);
    }

    private static boolean sameAccounts(List<PurpleAccountFiles.Identity> a, List<PurpleAccountFiles.Identity> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); ++i) {
            if (!a.get(i).key.equals(b.get(i).key) || a.get(i).lifetime != b.get(i).lifetime) return false;
        }
        return true;
    }

    public static List<PurpleAccountFiles.Identity> signedIn() {
        List<PurpleAccountFiles.Identity> result = new ArrayList<>();
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) {
            PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(i);
            if (id != null) result.add(id);
        }
        return result;
    }

    public static synchronized String bindingAccount() { ensureStarted(); return bindingAccount; }

    public static synchronized boolean started() { return started; }

    public static void accountsChanged() {
        synchronized (PurpleAccountOwner.class) {
            if (!started) return;
            if (!ready) startup(null);
        }
        PurpleAccountSettings.invalidate();
        PurpleWatcher.rebind();
    }

    public static synchronized long roleEpoch() { ensureStarted(); return roleEpoch; }

    public static synchronized void reloadGlobal() {
        String before = global;
        try { readGlobal(); }
        finally {
            if (!java.util.Objects.equals(before, global)) ++roleEpoch;
        }
    }

    private static void readGlobal() {
        global = owner;
        localWarning = false;
        if (owner == null) return;
        PurpleAccountFileSnapshot local = PurpleAccountFileSnapshot.read(PurpleAccountFiles.file("local.toml"));
        try {
            if (local.status == PurpleAccountFileSnapshot.Status.ReadError) { localWarning = true; return; }
            JSONObject result = new JSONObject(choiceNative(local.bytes, owner, null));
            if (!result.optBoolean("ok")) { localWarning = true; return; }
            global = result.getString("global");
            localWarning = result.getJSONArray("warnings").length() != 0;
        } catch (Exception | UnsatisfiedLinkError e) {
            localWarning = true;
        }
        ++revision;
    }

    public static synchronized String owner() { ensureStarted(); return owner; }
    public static synchronized String global() { ensureStarted(); return global; }
    public static synchronized long revision() { ensureStarted(); return revision; }
    static long observedRevision() { return revision; }
    public static synchronized boolean ready() { ensureStarted(); return ready; }
    public static synchronized boolean recovered() { ensureStarted(); return recovered; }
    public static synchronized boolean localWarning() { ensureStarted(); return localWarning; }

    public static synchronized boolean chooseOwner(String key) {
        ensureStarted();
        if (owner != null || ready) return false;
        startup(key);
        return ready;
    }

    public static synchronized List<PurpleAccountFiles.Identity> eligible() {
        ensureStarted();
        List<PurpleAccountFiles.Identity> result = new ArrayList<>();
        if (owner != null || plan == null) return result;
        JSONArray keys = plan.optJSONArray("eligible");
        if (keys == null) return result;
        for (PurpleAccountFiles.Identity id : signedIn()) {
            for (int i = 0; i < keys.length(); ++i) if (id.key.equals(keys.optString(i))) result.add(id);
        }
        return result;
    }

    public static final class GlobalChoice {
        private final PurpleAccountFiles.Identity origin;
        private final PurpleAccountFiles.Identity chosen;
        private final String owner;
        private final String oldGlobal;
        private final long revision;
        private final File[] files;
        private final PurpleAccountFileSnapshot[] snapshots;
        private final byte[] localText;
        private final byte[] stateText;
        public final boolean normal;
        public final boolean missing;
        public final boolean empty;
        public final boolean usingGood;

        private GlobalChoice(PurpleAccountFiles.Identity origin, PurpleAccountFiles.Identity chosen,
                String owner, String oldGlobal, long revision, File[] files,
                PurpleAccountFileSnapshot[] snapshots, byte[] localText, JSONObject transition) throws Exception {
            this.origin = origin;
            this.chosen = chosen;
            this.owner = owner;
            this.oldGlobal = oldGlobal;
            this.revision = revision;
            this.files = files;
            this.snapshots = snapshots;
            this.localText = localText;
            normal = transition.getBoolean("normal");
            missing = transition.getBoolean("missing");
            empty = transition.getBoolean("empty");
            usingGood = transition.getBoolean("good");
            stateText = transition.getString("state").getBytes(StandardCharsets.UTF_8);
        }

        private boolean current(boolean stateWritten) {
            if (origin.slot != UserConfig.selectedAccount || !origin.current() || !chosen.current() || !owner.equals(PurpleAccountOwner.owner())
                    || !java.util.Objects.equals(oldGlobal, PurpleAccountOwner.global())
                    || revision != PurpleAccountOwner.revision()) return false;
            for (int i = 0; i < files.length; ++i) {
                if (stateWritten && i == 1) continue;
                if (!snapshots[i].matches(files[i])) return false;
            }
            return !stateWritten || java.util.Arrays.equals(stateText,
                    PurpleAccountFileSnapshot.read(files[1], (int) PurpleState.MAX_SIZE).bytes);
        }
    }

    public static synchronized GlobalChoice prepareGlobal(PurpleAccountFiles.Identity chosen,
            PurpleAccountFiles.Identity origin) {
        ensureStarted();
        PurpleGate.ensureLoaded();
        if (!ready || chosen == null || origin == null || !chosen.current() || !origin.current()) return null;
        PurpleAccountFiles old = globalFiles();
        PurpleAccountFiles next = PurpleAccountFiles.resolve(chosen.key, owner);
        if (old == null || next == null) return null;
        File[] files = {PurpleAccountFiles.file("local.toml"), PurpleState.stateFile(),
                old.settings, old.good, next.settings, next.good, PurpleAccountFiles.file("accounts/root.toml")};
        PurpleAccountFileSnapshot[] snapshots = new PurpleAccountFileSnapshot[files.length];
        for (int i = 0; i < files.length; ++i) {
            snapshots[i] = PurpleAccountFileSnapshot.read(files[i],
                    i == 1 ? (int) PurpleState.MAX_SIZE : (int) PurpleSettings.MAX_SIZE);
            if (snapshots[i].status == PurpleAccountFileSnapshot.Status.ReadError) return null;
        }
        try {
            JSONObject choice = new JSONObject(choiceNative(snapshots[0].bytes, owner, chosen.key));
            JSONObject transition = new JSONObject(globalTransitionNative(snapshots[4].bytes,
                    next.settings.getPath(), snapshots[5].bytes, snapshots[1].bytes));
            if (!choice.optBoolean("ok") || !transition.optBoolean("ok")) return null;
            GlobalChoice result = new GlobalChoice(origin, chosen, owner, global, revision, files, snapshots,
                    choice.getString("text").getBytes(StandardCharsets.UTF_8), transition);
            return result.current(false) ? result : null;
        } catch (Exception | UnsatisfiedLinkError e) { return null; }
    }

    public static synchronized boolean chooseGlobal(GlobalChoice choice) {
        if (choice == null || !choice.current(false)) return false;
        boolean stateWritten = false;
        if (choice.normal) {
            if (!PurpleAccountFileSnapshot.replace(choice.files[1], choice.snapshots[1], choice.stateText)) return false;
            stateWritten = true;
        }
        if (!choice.current(stateWritten) || !PurpleAccountFileSnapshot.replace(
                choice.files[0], choice.snapshots[0], choice.localText)) {
            if (stateWritten) {
                reloadGlobal();
                PurpleGate.reload("global choice state committed, choice refused");
            }
            return false;
        }
        reloadGlobal();
        return true;
    }

    private static native String globalTransitionNative(byte[] settings, String path, byte[] good, byte[] state);

    public static PurpleAccountFiles globalFiles() {
        ensureStarted();
        return PurpleAccountFiles.resolve(global(), owner());
    }

    public static boolean legacyOperation(int slot) {
        ensureStarted();
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(slot);
        return ready() && id != null && id.key.equals(owner()) && owner().equals(global())
                && id.current();
    }

    public static boolean legacyEditorAllowed(int slot) { return legacyOperation(slot) && legacyRootFile(); }

    public static boolean manualBackupAllowed(int slot, byte[] bytes) {
        ensureStarted();
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(slot);
        if (id == null || (owner() != null && !id.key.equals(owner()))) return false;
        PurpleAccountFileSnapshot root = PurpleAccountFileSnapshot.read(PurpleAccountFiles.file("settings.toml"));
        return root.status == PurpleAccountFileSnapshot.Status.Present && Arrays.equals(root.bytes, bytes)
                && legacyRootFile() && id.current();
    }

    private static boolean legacyRootFile() {
        PurpleAccountFileSnapshot root = PurpleAccountFileSnapshot.read(PurpleAccountFiles.file("settings.toml"));
        if (root.status != PurpleAccountFileSnapshot.Status.Present) return false;
        try { PurpleCore.ParseResult p = PurpleCore.parse(root.bytes); return p.ok && p.version <= 1; }
        catch (RuntimeException | UnsatisfiedLinkError e) { return false; }
    }

    private static native String planNative(byte[] local, byte[] owner, boolean accounts,
            String[] keys, boolean[] directories, boolean[] bindings, String choice);
    private static native String choiceNative(byte[] local, String owner, String global);
    private PurpleAccountOwner() { }
}
