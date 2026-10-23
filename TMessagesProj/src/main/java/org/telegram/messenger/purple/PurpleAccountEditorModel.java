package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PurpleAccountEditorModel {
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
        boolean directoryAttempted;
        int directoriesCreated;
        PurpleGlobalChoiceFiles.Snapshot backup;
        PurpleGlobalChoiceFiles.Snapshot main;
    }
    static final class Preview {
        final byte[] bytes;
        final JSONObject facts;
        Preview(byte[] bytes, JSONObject facts) { this.bytes = bytes; this.facts = facts; }
    }
    final File target;
    final File backup;
    final boolean global;
    final int workMode;
    final boolean missing;
    final boolean needsDirectory;
    final String openedText;
    private final PurpleGlobalChoiceFiles.Physical physical;
    private final Context context;
    private final PurpleEditorDirectories.Creator directoryCreator;
    private final ArrayList<File> targetParents = new ArrayList<>();
    private final Map<String, PurpleGlobalChoiceFiles.Snapshot> inputs = new LinkedHashMap<>();
    private final Map<String, PurpleGlobalChoiceFiles.Snapshot> directories = new LinkedHashMap<>();
    private final byte[] original;
    private boolean consumed;

    PurpleAccountEditorModel(File root, File target, File backup, String owner, String globalKey,
            String key, List<File> authorities, PurpleGlobalChoiceFiles.Physical physical, Context context) throws Exception {
        this(root, target, backup, owner, globalKey, key, authorities, physical, context, null);
    }
    PurpleAccountEditorModel(File root, File target, File backup, String owner, String globalKey,
            String key, List<File> authorities, PurpleGlobalChoiceFiles.Physical physical, Context context,
            PurpleEditorDirectories.Creator directoryCreator) throws Exception {
        this.target = target; this.backup = backup;
        this.physical = physical; this.context = context; this.directoryCreator = directoryCreator;
        global = key.equals(globalKey);
        for (File p = target.getParentFile(); p != null && !p.equals(root); p = p.getParentFile()) targetParents.add(0, p);
        for (File file : authorities) capture(root, file);
        PurpleGlobalChoiceFiles.Snapshot local = capture(root, new File(root, "local.toml"));
        PurpleGlobalChoiceFiles.Snapshot record = capture(root, new File(root, "accounts/root.toml"));
        workMode = PurpleRoutedWriteModel.modeNative(local.bytes, key, globalKey);
        if (workMode < 0) throw new IllegalStateException("editor Work Mode choice unavailable");
        JSONObject route = new JSONObject(PurpleGlobalChoiceModel.planNative(local.bytes, record.bytes, owner, globalKey, key));
        if (!route.optBoolean("ok") || !new File(root, route.getString("selectedPath")).equals(target)
                || target.equals(backup)) throw new IllegalStateException("editor target changed");
        PurpleGlobalChoiceFiles.Snapshot own = capture(root, target);
        capture(root, backup);
        needsDirectory = directories.get(target.getParent()).mark.missing();
        original = own.bytes == null ? null : own.bytes.clone();
        missing = own.mark.missing();
        if (missing && (key.equals(owner) || !capture(root, new File(target.getPath() + ".good")).mark.missing())) {
            throw new IllegalStateException("account file recovery required");
        }
        openedText = missing ? templateNative() : new String(original, StandardCharsets.UTF_8);
        if (openedText == null || !current()) throw new IllegalStateException("editor context changed");
    }
    private PurpleGlobalChoiceFiles.Snapshot capture(File root, File file) throws Exception {
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IllegalStateException("editor path outside account root");
        }
        ArrayList<File> chain = new ArrayList<>();
        for (File p = file.getParentFile(); p != null && !p.equals(root.getParentFile()); p = p.getParentFile()) chain.add(0, p);
        for (File p : chain) if (!directories.containsKey(p.getPath())) {
            directories.put(p.getPath(), new PurpleGlobalChoiceFiles.Snapshot(p, true, 0, physical));
        }
        if (!inputs.containsKey(file.getPath())) inputs.put(file.getPath(),
                new PurpleGlobalChoiceFiles.Snapshot(file, false, PurpleGlobalChoiceFiles.LIMIT, physical));
        return inputs.get(file.getPath());
    }
    byte[] capturedBytes(File file) {
        PurpleGlobalChoiceFiles.Snapshot value = inputs.get(file.getPath());
        return value == null || value.bytes == null ? null : value.bytes.clone();
    }
    boolean currentAfterLocal(PurpleGlobalChoiceFiles.Snapshot local) {
        if (local == null || !context.current(false)) return false;
        for (PurpleGlobalChoiceFiles.Snapshot value : inputs.values()) {
            if (!(value.file.equals(local.file) ? local : value).current(physical)) return false;
        }
        for (PurpleGlobalChoiceFiles.Snapshot value : directories.values()) if (!value.current(physical)) return false;
        return context.current(false);
    }
    boolean current() { return current(null); }
    private boolean current(Result result) { return current(result, null, null); }
    private boolean current(Result result, File pending, PurpleGlobalChoiceFiles.Snapshot receipt) {
        if (!context.current(result != null && result.refreshed)) return false;
        for (PurpleGlobalChoiceFiles.Snapshot value : inputs.values()) {
            PurpleGlobalChoiceFiles.Snapshot expected = value;
            if (result != null && value.file.equals(backup) && result.backup != null) expected = result.backup;
            if (result != null && value.file.equals(target) && result.main != null) expected = result.main;
            if (!expected.current(physical)) return false;
        }
        for (PurpleGlobalChoiceFiles.Snapshot value : directories.values()) {
            PurpleGlobalChoiceFiles.Snapshot expected = value.file.equals(pending) ? receipt : value;
            if (expected == null || !expected.current(physical)) return false;
        }
        return context.current(result != null && result.refreshed);
    }
    private boolean prepareParents(Result result) throws Exception {
        for (File directory : targetParents) {
            PurpleGlobalChoiceFiles.Snapshot absent = directories.get(directory.getPath());
            if (absent == null || !current(result)) return false;
            if (!absent.mark.missing()) continue;
            PurpleGlobalChoiceFiles.Snapshot parent = directories.get(directory.getParent());
            if (directoryCreator == null || parent == null || parent.mark.missing() || !parent.current(physical)) return false;
            result.directoryAttempted = true;
            PurpleEditorDirectories.Receipt receipt = directoryCreator.create(directory, parent.mark);
            if (receipt == null) return false;
            if (receipt.created) ++result.directoriesCreated;
            if (!receipt.created || !receipt.owned || !receipt.parentIdentity.equals(parent.mark.identity)) return false;
            PurpleGlobalChoiceFiles.Snapshot created = new PurpleGlobalChoiceFiles.Snapshot(directory, true, 0, physical);
            if (!created.mark.same(receipt.mark) || !parent.current(physical)
                    || !current(result, directory, created)) return false;
            directories.put(directory.getPath(), created);
            if (!current(result)) return false;
        }
        return current(result);
    }
    Preview preview(String text) {
        try {
            byte[] bytes = encode(text);
            if (bytes == null || !current()) return null;
            JSONObject facts = new JSONObject(previewNative(original, bytes, target.getPath(), global, workMode));
            if (!facts.optBoolean("ok") || !current()) return null;
            return new Preview(bytes, facts);
        } catch (Exception | UnsatisfiedLinkError e) { return null; }
    }
    static byte[] encode(String text) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if (buffer.remaining() > PurpleGlobalChoiceFiles.LIMIT) return null;
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (Exception e) { return null; }
    }
    Result save(Preview preview) {
        Result result = new Result();
        try {
            if (consumed || preview == null || !current()) return result;
            JSONObject verified = new JSONObject(previewNative(original, preview.bytes, target.getPath(), global, workMode));
            if (!verified.optBoolean("ok") || !current()) return result;
            consumed = true;
            if (!prepareParents(result)) return result;
            for (File destination : new File[]{target, backup}) {
                PurpleGlobalChoiceFiles.Snapshot parent = directories.get(destination.getParent());
                PurpleGlobalChoiceFiles.Snapshot prior = inputs.get(destination.getPath());
                if (parent == null || parent.mark.missing() || !destination.getParentFile().canWrite()
                        || !prior.mark.missing() && ((prior.mark.mode & 0222) == 0 || !destination.canWrite())) return result;
            }
            if (!current(result)) return result;
            if (original != null) {
                PurpleGlobalChoiceFiles.Snapshot previous = inputs.get(backup.getPath());
                if (Arrays.equals(original, previous.bytes)) result.backup = previous;
                else {
                    PurpleGlobalChoiceFiles.Publication published = PurpleGlobalChoiceFiles.replace(
                            backup, original, physical, () -> current(result), PurpleAppWideRemovalModel::ownedTemporary);
                    result.backupChanged = published.changed; result.backup = published.local;
                    if (!published.changed || published.local == null) return result;
                }
            }
            if (!current(result)) return result;
            PurpleGlobalChoiceFiles.Publication published = PurpleGlobalChoiceFiles.replace(
                    target, preview.bytes, physical, () -> current(result), PurpleAppWideRemovalModel::ownedTemporary);
            result.changed = published.changed; result.main = published.local;
            if (!published.changed || published.local == null || !current(result)) return result;
            context.refresh(); result.refreshed = true;
            if (!current(result)) return result;
            context.notifyChanged(); result.ok = current(result);
        } catch (Exception | UnsatisfiedLinkError e) { result.ok = false; }
        return result;
    }
    static native String templateNative();
    static native String previewNative(byte[] opened, byte[] draft, String path, boolean global, int workMode);
}
