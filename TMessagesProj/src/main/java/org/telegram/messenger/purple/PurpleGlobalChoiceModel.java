package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PurpleGlobalChoiceModel {
    interface Context {
        boolean current(boolean activated);
        boolean normal();
        boolean activate(Result result);
        void notifyChanged();
    }
    static final class Account {
        final int slot;
        final String key;
        final long lifetime;
        final Object config;
        final Object connection;
        final Object manager;
        Account(int slot, String key, long lifetime, Object config, Object connection, Object manager) {
            this.slot = slot; this.key = key; this.lifetime = lifetime;
            this.config = config; this.connection = connection; this.manager = manager;
        }
    }
    static boolean sameRoster(List<Account> a, List<Account> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); ++i) {
            Account x = a.get(i), y = b.get(i);
            if (x.slot != y.slot || !x.key.equals(y.key) || x.lifetime != y.lifetime
                    || x.config != y.config || x.connection != y.connection || x.manager != y.manager) return false;
        }
        return true;
    }
    static final class Route {
        final String owner, global, selected;
        final long revision, role, generation, nativeEpoch;
        final int gate;
        Route(String owner, String global, String selected, long revision, long role, long generation, long nativeEpoch, int gate) {
            this.owner = owner; this.global = global; this.selected = selected;
            this.revision = revision; this.role = role; this.generation = generation; this.nativeEpoch = nativeEpoch; this.gate = gate;
        }
        boolean current(boolean activated, int steps, String owner, String global, long revision, long role,
                long generation, long nativeEpoch, int gate) {
            return this.owner.equals(owner) && (activated ? selected : this.global).equals(global)
                    && revision == this.revision + (activated ? 1 : 0) && role == this.role + (activated ? 1 : 0)
                    && generation == this.generation + steps && nativeEpoch == this.nativeEpoch + steps && gate == this.gate + steps;
        }
    }
    static final class ActivationSource {
        final byte[] primary;
        final byte[] settings;
        final boolean good;
        final boolean defaults;
        ActivationSource(byte[] primary, byte[] settings, boolean good, boolean defaults) {
            this.primary = primary == null ? null : primary.clone();
            this.settings = settings == null ? null : settings.clone();
            this.good = good; this.defaults = defaults;
        }
    }
    static final class Result {
        boolean ok;
        boolean changed;
        boolean activated;
        PurpleGlobalChoiceFiles.Snapshot local;
    }
    final String owner;
    final String global;
    final String selected;
    final File root;
    final File currentFile;
    final File selectedFile;
    final JSONObject before;
    final JSONObject after;
    final boolean enterNormal;
    final boolean noOp;
    private final Context context;
    private final PurpleGlobalChoiceFiles.Physical physical;
    private final Map<String, PurpleGlobalChoiceFiles.Snapshot> inputs = new LinkedHashMap<>();
    private final Map<String, PurpleGlobalChoiceFiles.Snapshot> directories = new LinkedHashMap<>();
    private final byte[] nextLocal;
    private final ActivationSource activation;
    private final File local;
    private final File state;
    private boolean applying;

    PurpleGlobalChoiceModel(File root, String owner, String global, String selected,
            PurpleGlobalChoiceFiles.Physical physical, Context context) throws Exception {
        this.root = root; this.owner = owner; this.global = global; this.selected = selected;
        this.physical = physical; this.context = context;
        noOp = global.equals(selected);
        local = new File(root, "local.toml");
        state = new File(root, "state.toml");
        PurpleGlobalChoiceFiles.Snapshot localInput = capture(local, PurpleGlobalChoiceFiles.LIMIT);
        PurpleGlobalChoiceFiles.Snapshot ownerInput = capture(new File(root, "accounts/root.toml"), PurpleGlobalChoiceFiles.LIMIT);
        PurpleGlobalChoiceFiles.Snapshot stateInput = capture(state, PurpleGlobalChoiceFiles.LIMIT * 2);
        JSONObject plan = new JSONObject(planNative(localInput.bytes, ownerInput.bytes, owner, global, selected));
        if (!plan.optBoolean("ok")) throw new IllegalStateException("local choice unavailable");
        currentFile = destination(plan.getString("currentPath"));
        selectedFile = destination(plan.getString("selectedPath"));
        before = describe(currentFile, stateInput.bytes);
        after = describe(selectedFile, stateInput.bytes);
        enterNormal = after.getBoolean("normal");
        byte[] primary = inputs.get(selectedFile.getPath()).bytes;
        boolean good = after.getBoolean("good");
        activation = new ActivationSource(primary, good ? inputs.get(selectedFile.getPath() + ".good").bytes : primary,
                good, after.getBoolean("defaults"));
        nextLocal = noOp ? localInput.bytes : plan.getString("text").getBytes(StandardCharsets.UTF_8);
        if (nextLocal == null || nextLocal.length > PurpleGlobalChoiceFiles.LIMIT || !current()) {
            throw new IllegalStateException("choice changed during preview");
        }
    }
    private File destination(String relative) throws Exception {
        File result = new File(root, relative);
        if (!result.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IllegalStateException("invalid account path");
        }
        return result;
    }
    private PurpleGlobalChoiceFiles.Snapshot capture(File file, int limit) throws Exception {
        PurpleGlobalChoiceFiles.Snapshot old = inputs.get(file.getPath());
        if (old != null) return old;
        ArrayList<File> parents = new ArrayList<>();
        for (File p = file.getParentFile(); p != null && !p.equals(root.getParentFile()); p = p.getParentFile()) parents.add(0, p);
        for (File p : parents) if (!directories.containsKey(p.getPath())) {
            directories.put(p.getPath(), new PurpleGlobalChoiceFiles.Snapshot(p, true, 0, physical));
        }
        PurpleGlobalChoiceFiles.Snapshot value = new PurpleGlobalChoiceFiles.Snapshot(file, false, limit, physical);
        inputs.put(file.getPath(), value);
        return value;
    }
    private JSONObject describe(File file, byte[] stateBytes) throws Exception {
        PurpleGlobalChoiceFiles.Snapshot primary = capture(file, PurpleGlobalChoiceFiles.LIMIT);
        JSONObject result = new JSONObject(describeNative(primary.bytes, file.getPath(), null, stateBytes));
        if (result.optBoolean("needsGood")) {
            PurpleGlobalChoiceFiles.Snapshot good = capture(new File(file.getPath() + ".good"), PurpleGlobalChoiceFiles.LIMIT);
            result = new JSONObject(describeNative(primary.bytes, file.getPath(), good.bytes, stateBytes));
        }
        if (!result.optBoolean("ok")) throw new IllegalStateException("account preview unavailable");
        return result;
    }
    boolean current() { return currentInputs(null, false); }
    boolean committedCurrent(Result result) { return currentInputs(result, true); }
    ActivationSource activationSource() { return activation; }
    private void acceptState(String preset) throws Exception {
        PurpleGlobalChoiceFiles.Snapshot now = new PurpleGlobalChoiceFiles.Snapshot(state, false, PurpleGlobalChoiceFiles.LIMIT * 2, physical);
        if (now.bytes == null || !stateMatchesNative(now.bytes, preset)) throw new IllegalStateException("state changed during activation");
        inputs.put(state.getPath(), now);
    }
    private boolean currentInputs(Result result, boolean committed) {
        if (!context.current(result != null && result.activated)) return false;
        for (PurpleGlobalChoiceFiles.Snapshot value : inputs.values()) {
            if (committed && value.file.equals(local)) {
                if (result.local == null || !result.local.current(physical)) return false;
            } else if (!value.current(physical)) return false;
        }
        for (PurpleGlobalChoiceFiles.Snapshot value : directories.values()) if (!value.current(physical)) return false;
        return true;
    }
    Result commit() {
        Result result = new Result();
        if (!current()) return result;
        if (noOp) { result.ok = true; return result; }
        PurpleGlobalChoiceFiles.Publication publication = PurpleGlobalChoiceFiles.replace(local, nextLocal, physical, this::current);
        result.changed = publication.changed;
        result.local = publication.local;
        try { result.ok = publication.changed && publication.local != null && currentInputs(result, true); }
        catch (RuntimeException | UnsatisfiedLinkError e) { result.ok = false; }
        return result;
    }
    Result apply() {
        if (applying) return new Result();
        applying = true;
        Result result = new Result();
        try {
            result = commit();
            if (!result.changed) return result;
            if (!result.ok || !currentInputs(result, true)) { result.ok = false; return result; }
            if (enterNormal) {
                if (!context.normal()) { result.ok = false; return result; }
                acceptState("normal");
            }
            if (!currentInputs(result, true) || !context.activate(result)) { result.ok = false; return result; }
            acceptState(after.getString("preset"));
            if (!currentInputs(result, true)) { result.ok = false; return result; }
            context.notifyChanged();
            result.ok = currentInputs(result, true);
            return result;
        } catch (Exception | UnsatisfiedLinkError e) {
            result.ok = false;
            return result;
        } finally { applying = false; }
    }
    static native String planNative(byte[] local, byte[] ownerRecord, String owner, String global, String selected);
    static native String describeNative(byte[] settings, String path, byte[] good, byte[] state);
    static native boolean stateMatchesNative(byte[] state, String preset);
}
