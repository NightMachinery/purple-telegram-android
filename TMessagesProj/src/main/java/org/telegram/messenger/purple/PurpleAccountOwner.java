package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
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
    private static volatile long roleEpoch;
    private static String bindingAccount;

    /**
     * Plans the owner on first use, but only once every account slot's
     * UserConfig has loaded. Loading fills in the signed-in user without an
     * identity event, so a plan made earlier would see some accounts or none:
     * it could wait for a first sign-in that already happened, or make the one
     * account it saw the owner of a multi-account phone. Until then nothing is
     * planned, owner() is null and ready() is false, so readers use the
     * app-wide file and writes are held, and the next call tries again.
     */
    public static synchronized void ensureStarted() {
        if (!started && accountsLoaded()) {
            started = true;
            startup(null);
        }
    }

    /** True once every slot's UserConfig.loadConfig() has run. */
    private static boolean accountsLoaded() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) {
            if (!UserConfig.getInstance(i).isConfigLoaded()) return false;
        }
        return true;
    }

    /**
     * Upstream hook: ApplicationLoader.postInitApplication, right after the
     * loop that loads every slot's UserConfig. Plans the owner now that
     * signedIn() sees every account, then, if the gate already ran on the
     * app-wide fallback during that loop, reloads it under the plan.
     */
    public static void onAccountsLoaded() {
        ensureStarted();
        if (!PurpleGate.everLoaded()) return;
        AndroidUtilities.runOnUIThread(() -> {
            PurpleAccountSettings.invalidate();
            PurpleWatcher.rebind();
            PurpleGate.reload("accounts loaded");
        });
    }

    /** Slots whose UserConfig has a signed-in user, whether or not capture() could name it. */
    private static int activatedSlots() {
        int count = 0;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) {
            if (UserConfig.getInstance(i).isClientActivated()) ++count;
        }
        return count;
    }

    private static String stateName(int status) {
        switch (status) {
            case 0: return "owned";
            case 1: return "pending_owner_writes";
            case 2: return "waiting_for_first_sign_in";
            case 3: return "needs_choice";
            case 4: return "single_file_error";
            default: return "invalid_input";
        }
    }

    private static void startup(String choice) {
        ready = false;
        String outcome = "input_unavailable";
        int seen = -1;
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
            seen = identities.size();
            if (seen != activatedSlots()) {
                // A signed-in slot that could not be named: planning from the
                // rest could make one of them the owner automatically.
                outcome = "partial_view";
                plan = null;
                ++revision;
                return;
            }
            outcome = "plan_stale";
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
            outcome = stateName(next.optInt("status", -1));
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
                outcome = stateName(verified.optInt("status", -1));
                ready = verified.optBoolean("automatic");
            }
            reloadGlobal();
        } catch (Exception | UnsatisfiedLinkError e) {
            FileLog.e("Purple: account owner input unavailable; operations held");
            outcome = "input_unavailable";
            plan = null;
        } finally {
            ++revision;
            PurpleDiagLog.event("owner", "planned", PurpleDiagLog.count("accounts", seen),
                    PurpleDiagLog.code("state", outcome), PurpleDiagLog.flag("ready", ready),
                    PurpleDiagLog.flag("owner", owner != null));
        }
    }

    private static boolean bindingMatches(int slot, byte[] bytes) {
        PurpleAccountSyncCore.Result checked = PurpleAccountSyncCore.checkStartupBinding(slot, bytes);
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
    static long observedRoleEpoch() { return roleEpoch; }
    public static synchronized boolean ready() { ensureStarted(); return ready; }
    public static synchronized boolean recovered() { ensureStarted(); return recovered; }
    public static synchronized boolean localWarning() { ensureStarted(); return localWarning; }

    public static synchronized boolean chooseOwner(String key) {
        ensureStarted();
        if (!started || owner != null || ready) return false;
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

    public static boolean modernOperation(int slot) {
        ensureStarted();
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(slot);
        return ready() && id != null && id.current();
    }

    public static boolean legacyTransferOperation(int slot) {
        ensureStarted();
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(slot);
        return ready() && id != null && id.current();
    }

    public static boolean manualBackupAllowed(int slot, byte[] bytes) {
        if (bytes == null || !legacyTransferOperation(slot)) return false;
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(slot);
        PurpleAccountFiles files = id == null ? null : PurpleAccountFiles.resolve(id.key, owner());
        if (files == null) return false;
        PurpleAccountFileSnapshot file = PurpleAccountFileSnapshot.read(files.settings);
        try {
            return file.status == PurpleAccountFileSnapshot.Status.Present && Arrays.equals(file.bytes, bytes)
                    && PurpleConfigImpact.ready(new PurpleConfigImpact.Input(bytes, true, 0, null,
                    new String[0], new byte[0][], new int[0]).analyze(bytes)) && id.current();
        } catch (Exception | UnsatisfiedLinkError e) { return false; }
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
