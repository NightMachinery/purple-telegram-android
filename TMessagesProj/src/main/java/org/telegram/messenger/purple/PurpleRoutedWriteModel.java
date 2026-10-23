package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PurpleRoutedWriteModel {
    final PurpleAccountEditorModel editor;
    final String key, globalKey, targetKey;
    final File globalFile, ownFile;
    final boolean appWide;
    final int mode;
    private final File localFile;
    private final PurpleGlobalChoiceFiles.Physical physical;
    private final byte[] local, global, own;

    PurpleRoutedWriteModel(File root, File ownFile, File globalFile, String owner, String globalKey,
            String key, boolean appWide, PurpleGlobalChoiceFiles.Physical physical,
            PurpleAccountEditorModel.Context context, PurpleEditorDirectories.Creator creator) throws Exception {
        this.key = key; this.globalKey = globalKey; this.globalFile = globalFile; this.ownFile = ownFile;
        this.physical = physical;
        localFile = new File(root, "local.toml");
        PurpleGlobalChoiceFiles.Snapshot choice = new PurpleGlobalChoiceFiles.Snapshot(localFile, false, PurpleGlobalChoiceFiles.LIMIT, physical);
        mode = modeNative(choice.bytes, key, globalKey);
        if (mode < 0) throw new IllegalStateException("invalid Work Mode choice");
        this.appWide = appWide || key.equals(globalKey) || mode == 1;
        targetKey = this.appWide ? globalKey : key;
        File target = this.appWide ? globalFile : ownFile;
        editor = new PurpleAccountEditorModel(root, target, new File(target.getPath() + ".bak"), owner, globalKey, targetKey,
                Arrays.asList(ownFile, new File(ownFile.getPath() + ".good"), globalFile, new File(globalFile.getPath() + ".good")),
                physical, context, creator);
        local = editor.capturedBytes(localFile);
        global = editor.capturedBytes(globalFile);
        own = editor.capturedBytes(ownFile);
        if (!Arrays.equals(choice.bytes, local) || !choice.current(physical) || !editor.current()) {
            throw new IllegalStateException("write route changed");
        }
    }

    byte[] bytes() { return editor.openedText.getBytes(StandardCharsets.UTF_8); }
    boolean current() { return editor.current(); }
    JSONObject lists(long peer) throws Exception {
        if (!current()) return null;
        JSONObject result = new JSONObject(listNative(local, own, ownFile.getPath(), global, globalFile.getPath(), key, globalKey, null, peer, false, null));
        return result.optBoolean("ok") && current() ? result : null;
    }
    JSONObject toggle(String list, long peer, boolean add, String title) throws Exception {
        if (!current()) return null;
        JSONObject result = new JSONObject(listNative(local, own, ownFile.getPath(), global, globalFile.getPath(), key, globalKey, list, peer, add, title));
        return result.optBoolean("ok") && result.optBoolean("appWide") == appWide && current() ? result : null;
    }
    JSONObject deleteOwn(String list) throws Exception {
        if (appWide || !current()) return null;
        JSONObject result = new JSONObject(deleteOwnNative(own, ownFile.getPath(), global, globalFile.getPath(), list));
        return result.optBoolean("ok") && current() ? result : null;
    }
    boolean setMode(int next) throws Exception {
        if (!current()) return false;
        JSONObject plan = new JSONObject(modePlanNative(local, key, globalKey, next));
        if (!plan.optBoolean("ok") || !current()) return false;
        if (!plan.optBoolean("changed")) return true;
        byte[] text = PurpleAccountEditorModel.encode(plan.getString("text"));
        if (text == null) return false;
        PurpleGlobalChoiceFiles.Publication result = PurpleGlobalChoiceFiles.replace(localFile, text, physical,
                this::current, PurpleAppWideRemovalModel::ownedTemporary);
        return result.changed && result.local != null && editor.currentAfterLocal(result.local);
    }
    static native String modePlanNative(byte[] local, String key, String globalKey, int mode);
    static native String deleteOwnNative(byte[] own, String ownPath, byte[] global, String globalPath, String list);
    PurpleAccountEditorModel.Result save(String text) { return editor.save(editor.preview(text)); }
    static native int modeNative(byte[] local, String key, String globalKey);
    static native String listNative(byte[] local, byte[] own, String ownPath, byte[] global, String globalPath,
            String key, String globalKey, String list, long peer, boolean add, String title);
}
