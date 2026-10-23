package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PurpleAppWideRemovalModel {
    interface Context {
        boolean current(boolean refreshed);
        void refresh();
        void notifyChanged();
    }
    static final class Result {
        boolean ok;
        boolean backupChanged;
        boolean changed;
        boolean refreshed;
        PurpleGlobalChoiceFiles.Snapshot backup;
        PurpleGlobalChoiceFiles.Snapshot main;
    }
    final File target;
    final File backup;
    final int count;
    final JSONArray groups;
    private final PurpleGlobalChoiceFiles.Physical physical;
    private final Context context;
    private final Map<String, PurpleGlobalChoiceFiles.Snapshot> inputs = new LinkedHashMap<>();
    private final Map<String, PurpleGlobalChoiceFiles.Snapshot> directories = new LinkedHashMap<>();
    private final byte[] original;
    private final byte[] successor;
    private boolean consumed;

    PurpleAppWideRemovalModel(File root, File target, File backup, String owner, String global, String account,
            List<File> authorities,
            PurpleGlobalChoiceFiles.Physical physical, Context context) throws Exception {
        this.target = target;
        this.backup = backup;
        this.physical = physical;
        this.context = context;
        if (target.equals(backup) || !eligible(account, global)) throw new IllegalStateException("ineligible removal destination");
        for (File file : authorities) capture(root, file, false);
        PurpleGlobalChoiceFiles.Snapshot local = capture(root, new File(root, "local.toml"), false);
        PurpleGlobalChoiceFiles.Snapshot ownerRecord = capture(root, new File(root, "accounts/root.toml"), false);
        JSONObject route = new JSONObject(PurpleGlobalChoiceModel.planNative(
                local.bytes, ownerRecord.bytes, owner, global, account));
        if (!route.optBoolean("ok") || !new File(root, route.getString("selectedPath")).equals(target)
                || !inputs.containsKey(new File(root, route.getString("currentPath")).getPath())) {
            throw new IllegalStateException("actual local choice no longer matches the captured source");
        }
        PurpleGlobalChoiceFiles.Snapshot own = capture(root, target, true);
        PurpleGlobalChoiceFiles.Snapshot previousBackup = capture(root, backup, true);
        if (own.bytes == null) throw new IllegalStateException("existing account file required");
        if ((own.mark.mode & 0222) == 0 || !target.canWrite() || !target.getParentFile().canWrite()
                || !backup.getParentFile().canWrite() || (!previousBackup.mark.missing()
                && ((previousBackup.mark.mode & 0222) == 0 || !backup.canWrite()))) {
            throw new IllegalStateException("account destination is not writable");
        }
        original = own.bytes.clone();
        JSONObject plan = new JSONObject(planNative(original, target.getPath()));
        if (!plan.optBoolean("ok")) throw new IllegalStateException("app-wide removal unavailable");
        successor = plan.getString("text").getBytes(StandardCharsets.UTF_8);
        count = plan.getInt("count");
        groups = plan.getJSONArray("groups");
        if (count <= 0 || successor.length > PurpleGlobalChoiceFiles.LIMIT || !current()) {
            throw new IllegalStateException("inactive app-wide settings unavailable");
        }
    }
    private PurpleGlobalChoiceFiles.Snapshot capture(File root, File file, boolean requireParents) throws Exception {
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IllegalStateException("removal path outside account root");
        }
        ArrayList<File> chain = new ArrayList<>();
        for (File p = file.getParentFile(); p != null && !p.equals(root.getParentFile()); p = p.getParentFile()) chain.add(0, p);
        for (File p : chain) {
            PurpleGlobalChoiceFiles.Snapshot directory = directories.get(p.getPath());
            if (directory == null) {
                directory = new PurpleGlobalChoiceFiles.Snapshot(p, true, 0, physical);
                directories.put(p.getPath(), directory);
            }
            if (requireParents && directory.mark.missing()) throw new IllegalStateException("existing account directory required");
        }
        if (inputs.containsKey(file.getPath())) return inputs.get(file.getPath());
        PurpleGlobalChoiceFiles.Snapshot value = new PurpleGlobalChoiceFiles.Snapshot(file, false, PurpleGlobalChoiceFiles.LIMIT, physical);
        inputs.put(file.getPath(), value);
        return value;
    }
    boolean current() { return current(null); }
    private boolean current(Result result) {
        if (!context.current(result != null && result.refreshed)) return false;
        for (PurpleGlobalChoiceFiles.Snapshot value : inputs.values()) {
            PurpleGlobalChoiceFiles.Snapshot expected = value;
            if (result != null && value.file.equals(backup) && result.backup != null) expected = result.backup;
            if (result != null && value.file.equals(target) && result.main != null) expected = result.main;
            if (!expected.current(physical)) return false;
        }
        for (PurpleGlobalChoiceFiles.Snapshot value : directories.values()) if (!value.current(physical)) return false;
        return context.current(result != null && result.refreshed);
    }
    static boolean eligible(String account, String global) {
        return account != null && global != null && !account.equals(global);
    }
    Result apply() {
        Result result = new Result();
        try {
            if (consumed || !current()) return result;
            consumed = true;
            PurpleGlobalChoiceFiles.Snapshot previousBackup = inputs.get(backup.getPath());
            if (Arrays.equals(previousBackup.bytes, original)) result.backup = previousBackup;
            else {
                PurpleGlobalChoiceFiles.Publication publication = PurpleGlobalChoiceFiles.replace(
                        backup, original, physical, () -> current(result), PurpleAppWideRemovalModel::ownedTemporary);
                result.backupChanged = publication.changed;
                result.backup = publication.local;
                if (!publication.changed || publication.local == null) return result;
            }
            if (!current(result) || !Arrays.equals(result.backup.bytes, original)) return result;
            PurpleGlobalChoiceFiles.Publication publication = PurpleGlobalChoiceFiles.replace(
                    target, successor, physical, () -> current(result), PurpleAppWideRemovalModel::ownedTemporary);
            result.changed = publication.changed;
            result.main = publication.local;
            if (!publication.changed || publication.local == null || !current(result)) return result;
            context.refresh();
            result.refreshed = true;
            if (!current(result)) return result;
            context.notifyChanged();
            result.ok = current(result);
        } catch (RuntimeException | UnsatisfiedLinkError e) { result.ok = false; }
        return result;
    }
    private static PurpleGlobalChoiceFiles.Created ownedTemporary(File parent) throws Exception {
        JSONObject created = new JSONObject(createNative(parent.getPath()));
        if (!created.optBoolean("ok")) return null;
        return new PurpleGlobalChoiceFiles.Created(new File(created.getString("path")),
                new PurpleGlobalChoiceFiles.Mark(created.getString("identity"), "", created.getInt("mode")));
    }
    private static native String createNative(String parent);
    static native String planNative(byte[] original, String path);
}
