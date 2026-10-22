package org.telegram.messenger.purple;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class PurpleAccountRowCases {
    private static byte[] bytes(String text) { return text == null ? null : text.getBytes(StandardCharsets.UTF_8); }
    private static int assertions;
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); ++assertions; }
    private static Object call(Class<?> type, String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
    private static JSONObject plan(String own, String good, int purpose, int action) throws Exception {
        return new JSONObject((String) call(PurpleAccountRowWrite.class, "planNative",
                new Class<?>[]{byte[].class, String.class, byte[].class, int.class, int.class},
                bytes(own), "accounts/0-202/settings.toml", bytes(good), purpose, action));
    }
    private static JSONObject layer(String global, String own) throws Exception {
        long epoch = (Long) call(PurpleAccountSettings.class, "epochNative", new Class<?>[]{});
        long handle = (Long) call(PurpleAccountSettings.class, "createNative",
                new Class<?>[]{long.class, byte[].class, String.class, byte[].class, byte[].class, String.class, byte[].class},
                epoch, bytes(global), "settings.toml", null, bytes(own), "accounts/0-202/settings.toml", null);
        require(handle != 0, "real layer handle");
        try {
            return new JSONObject((String) call(PurpleAccountSettings.class, "describeNative", new Class<?>[]{long.class}, handle));
        } finally { call(PurpleAccountSettings.class, "releaseNative", new Class<?>[]{long.class}, handle); }
    }
    private static byte[] read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[128];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
    public static void run(File directory) throws Exception {
        require(directory.isDirectory() && directory.list() != null && directory.list().length == 0, "new empty owned fixture");
        String global = "version = 1\n[last_seen]\nreasons_p = true\ntrade_p = true\n[sync]\nsend_after_save_p = true\n";
        String inherited = "version = 2\n# retained comment\n[unknown.nested]\nvalue = 0\n";
        require(layer(global, inherited).getBoolean("reasons"), "B inherits A reasons through actual LayerSettings");
        require(!plan(inherited, null, 0, -2).getBoolean("pinned"), "inherited key not pinned");
        JSONObject edited = plan(inherited, null, 0, 0);
        require(edited.getBoolean("ok") && edited.getBoolean("changed") && !edited.getBoolean("created"), "explicit false edit");
        String pinned = edited.getString("text");
        require(pinned.contains("version = 2") && pinned.contains("[unknown.nested]\nvalue = 0")
                && pinned.contains("# retained comment"), "version unknown nested zero and comment preserved");
        require(plan(pinned, null, 0, -2).getBoolean("pinned") && !layer(global, pinned).getBoolean("reasons"), "false pins B against A true");
        JSONObject reset = plan(pinned, null, 0, -1);
        require(reset.getBoolean("ok") && reset.getBoolean("changed"), "real RemoveTableKey Reset");
        require(!plan(reset.getString("text"), null, 0, -2).getBoolean("pinned")
                && layer(global, reset.getString("text")).getBoolean("reasons"), "Reset restores actual inheritance");
        require(reset.getString("text").contains("value = 0") && global.contains("reasons_p = true"), "Reset preserves unrelated bytes and A input");
        require(!plan(null, null, 2, -2).getBoolean("value") && plan(global, null, 2, -2).getBoolean("value"), "file-local send never inherits from A");
        require(!plan(global, null, 2, -1).getBoolean("ok"), "file-local Reset refused");
        require(!plan(null, null, 2, 1).getBoolean("ok"), "file-local switch cannot implicitly create account file");
        JSONObject created = plan(null, null, 1, 0);
        String minimal = created.getString("text");
        require(created.getBoolean("ok") && created.getBoolean("created") && created.getInt("version") == 2
                && minimal.startsWith("# Purple Telegram settings for one account.\n")
                && minimal.contains("version = 2") && minimal.contains("trade_p = false")
                && !minimal.contains("send_after_save_p") && !minimal.contains("0-202")
                && !minimal.contains("[presets]"), "explicit first edit creates public minimal v2 with no send flag or identity");
        String v1 = "version = 1\n[last_seen]\ntrade_p = false # keep me\n";
        require(plan(v1, null, 1, 1).getString("text").contains("version = 1"), "existing whole file version preserved");
        require(!plan("[broken", global, 0, 0).getBoolean("ok"), "own invalid current cannot write fallback");
        require(plan("[broken", global, 0, -2).getBoolean("usingGood"), "own good provenance readable");
        require(!plan("[broken", "[also-broken", 0, -2).getBoolean("ok"), "unusable fallback refused");
        require(!plan(inherited, null, 9, 1).getBoolean("ok") && !plan(inherited, null, -1, 1).getBoolean("ok"), "caller cannot forge row purpose");

        byte[] payload = bytes("inert account-row fixture");
        File target = new File(directory, "retired.toml");
        AtomicBoolean original = new AtomicBoolean(true);
        File[] temporary = new File[1];
        boolean published = PurpleRowFileMutation.replace(target, payload, new PurpleRowFileMutation.Guard() {
            public boolean prepare(File file) { temporary[0] = file; return true; }
            public boolean current() {
                if (temporary[0] != null && temporary[0].length() == payload.length) original.set(false);
                return original.get();
            }
        });
        require(!published && !target.exists() && temporary[0] != null && !temporary[0].exists(), "retirement after actual fsync refuses publication and cleans only owned temp");
        File live = new File(directory, "live.toml");
        require(PurpleRowFileMutation.replace(live, payload, new PurpleRowFileMutation.Guard() {
            public boolean prepare(File file) { return true; }
            public boolean current() { return true; }
        }) && Arrays.equals(read(live), payload), "still-current real publication control");
        AtomicBoolean originalRole = new AtomicBoolean(true);
        File stale = new File(directory, "reentrant.toml");
        require(!PurpleRowFileMutation.replace(stale, payload, new PurpleRowFileMutation.Guard() {
            public boolean prepare(File file) { originalRole.set(false); return true; }
            public boolean current() { return originalRole.get(); }
        }) && !stale.exists() && Arrays.equals(read(live), payload), "synchronous preparation retirement refuses without unrelated file mutation");
        System.out.println("REAL_ACCOUNT_ROW_NATIVE_PLAN_AND_FILE_MUTATION_CASES_COMPLETED");
    }
    private static final class VirtualScheduler implements PurpleSaveDebounce.Scheduler {
        long now;
        boolean currentThread = true;
        public boolean currentThread() { return currentThread; }
        final java.util.ArrayList<Runnable> callbacks = new java.util.ArrayList<>();
        final java.util.ArrayList<Long> due = new java.util.ArrayList<>();
        public void post(Runnable callback, long delay) { callbacks.add(callback); due.add(now + delay); }
        public void cancel(Runnable callback) { }
        void advance(long time) {
            now = time;
            for (int i = 0; i < callbacks.size(); ++i) {
                if (due.get(i) <= now) {
                    due.set(i, Long.MAX_VALUE);
                    callbacks.get(i).run();
                }
            }
        }
    }

    private static final class VersionInputs implements PurpleSaveDebounce.Versions {
        long revision = 7;
        long generation = 11;
        long nativeEpoch = 13;
        long roleEpoch;
        long lifetime = 19;
        boolean originalFiles = true;
        public long revision() { return revision; }
        public long generation() { return generation; }
        public long nativeEpoch() { return nativeEpoch; }
        void ownRefresh() { ++revision; generation += 2; nativeEpoch += 2; }
        PurpleSaveDebounce.RefreshReceipt receipt() {
            long originalRole = roleEpoch;
            long originalLifetime = lifetime;
            return new PurpleSaveDebounce.RefreshReceipt(
                    () -> originalFiles && roleEpoch == originalRole && lifetime == originalLifetime,
                    this, revision, generation, nativeEpoch);
        }
    }

    private static String boundedInput(int length, boolean multibyte) {
        String prefix = "version = 1\n[last_seen]\ntrade_p = true\n#";
        int remaining = length - bytes(prefix).length;
        StringBuilder result = new StringBuilder(prefix);
        if (multibyte) {
            for (int i = 0; i < remaining / 2; ++i) result.append('é');
            if (remaining % 2 != 0) result.append('x');
        } else {
            for (int i = 0; i < remaining; ++i) result.append('x');
        }
        require(bytes(result.toString()).length == length, "exact native input UTF-8 size");
        return result.toString();
    }

    public static void runRepairs(File directory) throws Exception {
        VirtualScheduler scheduler = new VirtualScheduler();
        PurpleSaveDebounce saves = new PurpleSaveDebounce(scheduler);
        VersionInputs inputs = new VersionInputs();
        PurpleSaveDebounce.RefreshReceipt receipt = inputs.receipt();
        java.util.ArrayList<String> decisions = new java.util.ArrayList<>();
        saves.replace(receipt, () -> decisions.add("own-row"));
        scheduler.advance(400);
        saves.refresh(inputs::ownRefresh);
        require(receipt.current(), "exact own watcher reload preserves originating receipt");
        scheduler.advance(4999);
        require(decisions.isEmpty(), "own watcher refresh does not advance five-second deadline");
        scheduler.advance(5000);
        require(decisions.equals(java.util.Arrays.asList("own-row")), "own refresh still permits exactly one inert decision");
        scheduler.callbacks.get(0).run();
        require(decisions.size() == 1, "already delivered callback cannot repeat");

        for (int changed = 0; changed < 5; ++changed) {
            VirtualScheduler clock = new VirtualScheduler();
            PurpleSaveDebounce queue = new PurpleSaveDebounce(clock);
            VersionInputs state = new VersionInputs();
            PurpleSaveDebounce.RefreshReceipt captured = state.receipt();
            int[] sent = {0};
            queue.replace(captured, () -> ++sent[0]);
            final int kind = changed;
            queue.refresh(() -> {
                state.ownRefresh();
                if (kind == 0) state.roleEpoch += 2;
                if (kind == 1) ++state.lifetime;
                if (kind == 2) state.originalFiles = false;
                if (kind == 3) ++state.generation;
                if (kind == 4) ++state.nativeEpoch;
            });
            state.roleEpoch = 0;
            state.lifetime = 19;
            state.originalFiles = true;
            require(!captured.current(), "changed context cannot revive after expected reload boundary " + changed);
            clock.advance(5000);
            require(sent[0] == 0, "changed role/lifetime/file/generation/native epoch refuses inert decision " + changed);
        }
        VersionInputs unexpected = new VersionInputs();
        PurpleSaveDebounce.RefreshReceipt stale = unexpected.receipt();
        ++unexpected.generation;
        stale.beforeRefresh();
        unexpected.ownRefresh();
        stale.afterRefresh();
        require(!stale.current(), "unrelated invalidation before watcher cannot be laundered");

        for (boolean reverse : new boolean[]{false, true}) {
            VirtualScheduler clock = new VirtualScheduler();
            PurpleSaveDebounce queue = new PurpleSaveDebounce(clock);
            java.util.ArrayList<String> delivered = new java.util.ArrayList<>();
            String first = reverse ? "row" : "legacy";
            String latest = reverse ? "legacy" : "row";
            queue.replace(() -> true, () -> delivered.add(first));
            clock.advance(3000);
            queue.replace(() -> true, () -> delivered.add(latest));
            clock.advance(5000);
            require(delivered.isEmpty(), "superseded " + first + " cannot send latest bytes early");
            clock.advance(7999);
            require(delivered.isEmpty(), "latest save gets complete debounce " + latest);
            clock.advance(8000);
            require(delivered.equals(java.util.Arrays.asList(latest)), "mixed routes coalesce to latest one decision " + latest);
            for (Runnable callback : clock.callbacks) callback.run();
            require(delivered.size() == 1, "canceled or repeated queued callbacks cannot duplicate " + latest);
            queue.replace(() -> true, () -> delivered.add("ineligible"));
            queue.cancel();
            clock.advance(13000);
            require(delivered.size() == 1, "no-send/import cancellation retires previous save");
        }

        int limit = PurpleAccountFileSnapshot.LIMIT;
        File original = new File(directory, "size-original.toml");
        String maximum = boundedInput(limit, false);
        try (FileOutputStream out = new FileOutputStream(original)) { out.write(bytes(maximum)); }
        java.nio.file.attribute.BasicFileAttributes before = java.nio.file.Files.readAttributes(
                original.toPath(), java.nio.file.attribute.BasicFileAttributes.class);
        String[] names = directory.list();
        for (boolean multibyte : new boolean[]{false, true}) {
            String input = boundedInput(limit, multibyte);
            JSONObject overflow = plan(input, null, 1, 0);
            require(overflow.getBoolean("ok") && overflow.getBoolean("changed")
                    && bytes(overflow.getString("text")).length == limit + 1, "actual native true-to-false grows by one byte");
            require(PurpleAccountRowWrite.outputBytes(overflow.getString("text")) == null,
                    "actual production final-byte policy refuses oversized native plan");
            if (multibyte) require(overflow.getString("text").length() < limit, "character count would incorrectly accept multibyte overflow");
            JSONObject atCap = plan(boundedInput(limit - 1, multibyte), null, 1, 0);
            byte[] accepted = PurpleAccountRowWrite.outputBytes(atCap.getString("text"));
            require(atCap.getBoolean("ok") && accepted != null && accepted.length == limit,
                    "actual native plan and production byte policy accept exactly-at-cap control");
        }
        java.nio.file.attribute.BasicFileAttributes after = java.nio.file.Files.readAttributes(
                original.toPath(), java.nio.file.attribute.BasicFileAttributes.class);
        require(Arrays.equals(bytes(maximum), read(original)) && Arrays.equals(names, directory.list())
                && before.fileKey().equals(after.fileKey()) && before.lastModifiedTime().equals(after.lastModifiedTime())
                && before.size() == after.size(), "overflow refusal preserves original bytes metadata and directory entries");
        System.out.println("REAL_THREE_P2_CAUSAL_REPAIR_CASES_COMPLETED assertions=" + assertions);
    }
    public static void runAdmission() {
        for (boolean inline : new boolean[]{false, true}) {
            VirtualScheduler scheduler = new VirtualScheduler();
            scheduler.currentThread = inline;
            PurpleSaveDebounce saves = new PurpleSaveDebounce(scheduler);
            VersionInputs inputs = new VersionInputs();
            PurpleSaveDebounce.RefreshReceipt receipt = inputs.receipt();
            int[] decisions = {0};
            scheduler.post(() -> saves.refresh(inputs::ownRefresh), 400);
            scheduler.now = 600;
            saves.admit(receipt, () -> ++decisions[0], true);
            if (inline) require(scheduler.due.get(1) == 5600,
                    "actual inline admission registers five-second decision before returning to overdue watcher");
            scheduler.advance(650);
            require(receipt.current() == inline,
                    "production admission policy distinguishes registered receipt from queued admission behind due watcher");
            scheduler.advance(5599);
            require(decisions[0] == 0, "admission never shortens original five-second decision");
            scheduler.advance(5600);
            require(decisions[0] == (inline ? 1 : 0),
                    "inline production admission preserves valid send that queued-admission control loses");
            if (!inline) System.out.println("QUEUED_ADMISSION_CONTROL_EXPECTED_VALID_DECISION_LOST");
            for (Runnable callback : scheduler.callbacks) callback.run();
            require(decisions[0] == (inline ? 1 : 0), "admission callback cannot duplicate decision");
        }
        for (int changed = 0; changed < 5; ++changed) {
            VirtualScheduler scheduler = new VirtualScheduler();
            PurpleSaveDebounce saves = new PurpleSaveDebounce(scheduler);
            VersionInputs inputs = new VersionInputs();
            PurpleSaveDebounce.RefreshReceipt receipt = inputs.receipt();
            int[] decisions = {0};
            final int kind = changed;
            scheduler.post(() -> saves.refresh(() -> {
                inputs.ownRefresh();
                if (kind == 0) inputs.roleEpoch += 2;
                if (kind == 1) ++inputs.lifetime;
                if (kind == 2) inputs.originalFiles = false;
                if (kind == 3) ++inputs.generation;
                if (kind == 4) ++inputs.nativeEpoch;
            }), 400);
            scheduler.now = 600;
            saves.admit(receipt, () -> ++decisions[0], true);
            scheduler.advance(650);
            inputs.roleEpoch = 0;
            inputs.lifetime = 19;
            inputs.originalFiles = true;
            require(!receipt.current(), "admitted receipt still retires changed context or extra invalidation " + changed);
            scheduler.advance(5600);
            require(decisions[0] == 0, "admission does not waive changed-context refusal " + changed);
        }
        VirtualScheduler scheduler = new VirtualScheduler();
        PurpleSaveDebounce saves = new PurpleSaveDebounce(scheduler);
        int[] decisions = {0};
        saves.replace(() -> true, () -> ++decisions[0]);
        scheduler.now = 600;
        saves.admit(() -> true, () -> ++decisions[0], false);
        scheduler.advance(5000);
        require(decisions[0] == 0, "inline no-send admission cancels older pending callback before leaving UI writer");
        System.out.println("REAL_ROW_RECEIPT_ADMISSION_CASES_COMPLETED assertions=" + assertions);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && !(args.length == 3 && (args[2].equals("repairs") || args[2].equals("admission")))) throw new IllegalArgumentException("actual host JNI library and new fixture directory");
        System.load(args[0]);
        File directory = new File(args[1]);
        require(!directory.exists() && directory.mkdir(), "exclusive own fixture creation");
        if (args.length == 3 && args[2].equals("admission")) runAdmission();
        else if (args.length == 3) runRepairs(directory);
        else run(directory);
        for (Class<?> type : new Class<?>[]{PurpleAccountRowWrite.class, PurpleRowFileMutation.class,
                PurpleAccountSettings.class, PurpleSaveDebounce.class, PurpleSaveDebounce.RefreshReceipt.class, PurpleAccountRowCases.class, JSONObject.class}) {
            System.out.println("CLASS_ORIGIN " + type.getName() + " " + type.getProtectionDomain().getCodeSource().getLocation());
        }
    }
}
