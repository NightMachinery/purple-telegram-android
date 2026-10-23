package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;

final class PurpleGlobalChoiceReload {
    interface Current { boolean current(); }
    final PurpleGlobalChoiceModel.ActivationSource source;
    private final Current context;
    private final PurpleGlobalChoiceFiles.Physical physical;
    private final ArrayList<PurpleGlobalChoiceFiles.Snapshot> inputs = new ArrayList<>();
    private PurpleGlobalChoiceReload(File root, File file, PurpleGlobalChoiceFiles.Physical physical, Current context) throws Exception {
        this.context = context;
        this.physical = physical;
        if (!context.current() || !file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IllegalStateException("reload source unavailable");
        }
        ArrayList<File> parents = new ArrayList<>();
        for (File p = file.getParentFile(); p != null && !p.equals(root.getParentFile()); p = p.getParentFile()) parents.add(0, p);
        for (File parent : parents) inputs.add(new PurpleGlobalChoiceFiles.Snapshot(parent, true, 0, physical));
        PurpleGlobalChoiceFiles.Snapshot primary = capture(file);
        JSONObject result = new JSONObject(PurpleGlobalChoiceModel.describeNative(primary.bytes, file.getPath(), null, null));
        PurpleGlobalChoiceFiles.Snapshot good = null;
        if (result.optBoolean("needsGood")) {
            good = capture(new File(file.getPath() + ".good"));
            result = new JSONObject(PurpleGlobalChoiceModel.describeNative(primary.bytes, file.getPath(), good.bytes, null));
        }
        if (!result.optBoolean("ok")) throw new IllegalStateException("reload has no supported source");
        boolean recovered = result.getBoolean("good");
        source = new PurpleGlobalChoiceModel.ActivationSource(primary.bytes, recovered ? good.bytes : primary.bytes,
                recovered, result.getBoolean("defaults"));
        if (!current()) throw new IllegalStateException("reload source changed during read");
    }
    private PurpleGlobalChoiceFiles.Snapshot capture(File file) throws Exception {
        PurpleGlobalChoiceFiles.Snapshot result = new PurpleGlobalChoiceFiles.Snapshot(file, false, PurpleGlobalChoiceFiles.LIMIT, physical);
        inputs.add(result);
        return result;
    }
    boolean current() {
        if (!context.current()) return false;
        for (PurpleGlobalChoiceFiles.Snapshot input : inputs) if (!input.current(physical)) return false;
        return context.current();
    }
    static PurpleGlobalChoiceReload read(File root, File file, PurpleGlobalChoiceFiles.Physical physical, Current context) {
        try { return new PurpleGlobalChoiceReload(root, file, physical, context); }
        catch (Exception | UnsatisfiedLinkError e) { return null; }
    }
}
