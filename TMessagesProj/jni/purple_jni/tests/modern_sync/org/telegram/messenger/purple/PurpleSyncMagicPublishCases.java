package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.telegram.messenger.FileLog;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

/**
 * The first publish in magic mode (native check A3): the account's sync folder holds only {@code lock} and an R2
 * {@code chat.json}, the manual screen checks, keeps the local file and publishes it into the bound chat. On the device
 * the store's durable file layer opened "/" for reading, which Android refuses an app, and the publish ended in
 * StoreError (IoError): the native walk of every durable path started by opening "/". On macOS hosts the modern suite
 * runs with every open of "/" refused the same way (rootdeny.c, injected by run_modern_sync.py), so these cases go
 * through the production runner, file context, store and native durable layer and must reach the send.
 */
final class PurpleSyncMagicPublishCases {
    static final PurpleSyncChatCore.Peer MAGIC = PurpleSyncMagicMoveCases.MAGIC;
    static final PurpleSyncChatOrigin ORIGIN = new PurpleSyncChatOrigin(1, 0, 202, "magic-fixture");

    /** The bound chat as the lookup's transport sees it: its inventory and post replies name the magic chat. */
    static final class Chat implements PurpleSyncTransport {
        final ArrayList<byte[]> records = new ArrayList<>();
        int posts;
        PurpleSyncInventory inventory() {
            PurpleSyncInventory.Builder builder = new PurpleSyncInventory.Builder(202, MAGIC, ORIGIN);
            int id = 100;
            for (byte[] record : records) builder.fetched(++id, id + 1000L, 1700000000L, record);
            return builder.build(true);
        }
        public void check(Progress progress, CheckDone done) { done.onCheckDone(new CheckResult(CheckStatus.Finished, inventory(), false)); }
        public void post(byte[] staged, PostDone done) {
            ++posts; records.add(staged.clone());
            done.onPostDone(new PostResult(PostStatus.Confirmed, 100 + records.size(), staged.clone(), MAGIC, ORIGIN));
        }
        public void cancel() { }
    }

    /**
     * The production environment of the manual screen (PurpleSyncRunner.Platform over a real PurpleSyncFileContext)
     * with only what needs Telegram replaced, as PurpleSyncMagicManual does for a bound magic chat: the lookup has
     * bound MAGIC, the state is initialized for it, and no Saved Messages recovery applies.
     */
    static final class Magic extends PurpleSyncRunner.Platform {
        final Frame frame;
        Magic(Frame frame, PurpleSyncFileContext context) { super(context); this.frame = frame; }
        @Override public void resolveChat(boolean beforePost, Object owner, String key, BooleanSupplier current, Consumer<Boolean> done) { done.accept(current.getAsBoolean()); }
        @Override public void cancelChat(Object owner) { }
        @Override public PurpleAccountSyncCore.Result initialize(int account, String install, String device, String space) {
            return PurpleAccountSyncCore.initializeBoundLocalState(account, install, device, space, MAGIC);
        }
        @Override public boolean workerEffects() { return true; }
        @Override public boolean requiresRecovery() { return false; }
        @Override public PurpleSyncRecoveryModel.Offer recovery(PurpleSyncInventory inventory, PurpleSyncSettingsFile.Contents local) { return null; }
        @Override public boolean sendQueued() { return false; }
        @Override public String device() { return PurpleModernSyncCases.device(1); }
        @Override public long serverTimeMillis(int account) { return 1800000000000L; }
        /** The production store factory, following the chat the lookup bound. */
        @Override public PurpleAccountSyncStore store() { return PurpleAccountSyncStore.following(context, MAGIC); }
        @Override public PurpleSyncHistory history() { return frame.env(1).history(); }
        @Override public PurpleSyncSettingsFile.Contents readSettings() { return frame.env(1).readSettings(); }
        @Override public PurpleSyncSettingsFile.WriteResult replaceSettings(PurpleSyncSettingsFile.Contents expected, byte[] bytes, String reason, boolean imported) {
            return frame.env(1).replaceSettings(expected, bytes, reason, imported);
        }
    }

    static Object field(String name) throws Exception {
        java.lang.reflect.Field f = PurpleAccountOwner.class.getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }
    static void field(String name, Object value) throws Exception {
        java.lang.reflect.Field f = PurpleAccountOwner.class.getDeclaredField(name); f.setAccessible(true); f.set(null, value);
    }

    static void run(File parent) throws Exception {
        Map<String, Object> saved = new HashMap<>();
        for (String name : new String[] { "started", "owner", "global", "ready" }) saved.put(name, field(name));
        try {
            rootRefused();
            movedPublish(parent);
            firstPublish(parent);
            guard(parent);
        } finally {
            for (Map.Entry<String, Object> entry : saved.entrySet()) field(entry.getKey(), entry.getValue());
        }
    }

    /** The refusal log of rootdeny.c, or null when the run does not refuse "/" (not macOS). */
    static File rootLog() {
        String log = System.getProperty("purple.test.rootdeny");
        return log == null ? null : new File(log);
    }
    static int rootRefusals() throws Exception {
        File log = rootLog();
        return log == null || !log.exists() ? 0 : Files.readAllLines(log.toPath()).size();
    }
    /** The run refuses "/" as Android does: a listing of "/" fails and is logged, while paths below it still open. */
    static void rootRefused() throws Exception {
        if (rootLog() == null) { System.out.println("ROOTDENY=unavailable (not macOS)"); return; }
        int before = rootRefusals();
        check(new File("/").list() == null && rootRefusals() == before + 1, "the host refuses a read of \"/\" like Android");
        check(new File(System.getProperty("java.io.tmpdir")).list() != null, "and only \"/\"");
    }

    /** The device of A3: a Saved Messages state with a published record, then the magic chat bound beside it. */
    static void movedPublish(File parent) throws Exception {
        Frame frame = new Frame(parent, "magic-moved-publish", true);
        frame.initialize(1);
        PurpleSyncRecoveryCases.publish(frame, 1);
        field("started", true); field("ready", true); field("owner", A); field("global", A);
        PurpleSyncFileContext context = new PurpleSyncFileContext(1);
        File sync = context.files.syncDirectory;
        write(new File(sync, "chat.json"), PurpleSyncMagicBindingFileCases.r2Binding());
        // The device's first Check moved the state into the bound chat (PurpleAccountSyncStore.moveToBound, which
        // needs the Android settings editor); the same move through moveTo.
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(sync, PurpleAccountSyncStore.NATIVE, PHYSICAL, PurpleEditorDirectories.NATIVE)) {
            check(store.open(true, 1, device(1)).status == PurpleAccountSyncStore.Status.Ready, "the Saved Messages state opens");
            String fingerprint = PurpleSyncCore.settingsFingerprint(Files.readAllBytes(frame.settings(1).toPath()));
            check(store.moveTo(MAGIC, 202, PurpleAccountFiles.capture(1).key, fingerprint).status == PurpleAccountSyncStore.Status.Ready, "and moves to the bound chat");
        }
        byte[] binding = Files.readAllBytes(new File(sync, "chat.json").toPath());
        PurpleAccountSyncCore.Result moved = PurpleAccountSyncCore.inspectState(Files.readAllBytes(new File(sync, "state.json").toPath()));
        check(moved.isValid() && moved.seq >= 1 && MAGIC.token().equals(stateChat(sync)), "the moved state names the magic chat");
        Chat chat = new Chat();
        Magic env = new Magic(frame, context);
        int refusals = rootRefusals();
        PurpleSyncRunner runner = new PurpleSyncRunner(1, 202, chat, env, Runnable::run, Runnable::run);
        PurpleSyncRunner.Check shown = fresh(runner);
        check(shown.review.status == PurpleSyncCore.ReviewStatus.Ready && shown.review.bound, "Check reviews the moved state in the bound chat");
        PurpleSyncRunner.ApplyOutcome applied = apply(runner, shown, null);
        check(applied.result.status == PurpleSyncCore.ApplyStatus.Applied && applied.post != null, "Publish changes prepares the post");
        PurpleSyncPublisher.Result[] sent = { null };
        check(runner.publish(applied.post, value -> sent[0] = value) == PurpleSyncRunner.Start.Started, "the publish starts");
        check(sent[0] != null && sent[0].status == PurpleSyncCore.PublishStatus.Confirmed,
                "the moved state's publish reaches the send and is confirmed: " + (sent[0] != null ? sent[0].status + " " + sent[0].storeStatus : "none"));
        check(chat.posts == 1 && chat.records.size() == 1, "the bound chat received the record");
        PurpleAccountSyncCore.Result state = PurpleAccountSyncCore.inspectState(Files.readAllBytes(new File(sync, "state.json").toPath()));
        check(state.isValid() && state.seq == moved.seq + 1 && state.pendingSeq == 0 && MAGIC.token().equals(stateChat(sync)),
                "the store holds the confirmed state: seq " + moved.seq + " -> " + state.seq);
        check(Arrays.equals(binding, Files.readAllBytes(new File(sync, "chat.json").toPath())), "chat.json is untouched");
        check(rootRefusals() == refusals, "nothing on the publish path opened \"/\"");
        runner.close();
    }

    static void firstPublish(File parent) throws Exception {
        Frame frame = new Frame(parent, "magic-first-publish", true);
        field("started", true); field("ready", true); field("owner", A); field("global", A);
        PurpleSyncFileContext context = new PurpleSyncFileContext(1);
        check(context.current() && context.files.syncDirectory.getCanonicalFile().equals(frame.sync(1).getCanonicalFile()),
                "the real file context names B's sync folder");
        File sync = context.files.syncDirectory;
        directory(sync);
        write(new File(sync, "lock"), new byte[0]);
        byte[] binding = PurpleSyncMagicBindingFileCases.r2Binding();
        write(new File(sync, "chat.json"), binding);
        check(Arrays.equals(new String[] { "chat.json", "lock" }, PurpleSyncRecoveryFiles.names(sync)), "the device's folder: lock and chat.json only");

        Chat chat = new Chat();
        Magic env = new Magic(frame, context);
        check(!env.hasState(), "no sync state yet, so automatic sync waits for the manual first publish");
        int refusals = rootRefusals();
        PurpleSyncRunner runner = new PurpleSyncRunner(1, 202, chat, env, Runnable::run, Runnable::run);
        PurpleSyncRunner.Check shown = fresh(runner);
        PurpleSyncRunner.ApplyOutcome applied = apply(runner, shown, null);
        check(applied.result.status == PurpleSyncCore.ApplyStatus.Applied && applied.post != null,
                "keeping the local file prepares the first post: " + applied.result.status + " " + applied.result.setupStatus + " " + applied.result.storeStatus);
        check(env.hasState() && MAGIC.token().equals(new org.json.JSONObject(new String(Files.readAllBytes(new File(sync, "state.json").toPath()),
                java.nio.charset.StandardCharsets.UTF_8)).getString("chat")), "the new state belongs to the magic chat");
        PurpleSyncPublisher.Result[] sent = { null };
        check(runner.publish(applied.post, value -> sent[0] = value) == PurpleSyncRunner.Start.Started, "the first publish starts");
        check(sent[0] != null && sent[0].status == PurpleSyncCore.PublishStatus.Confirmed,
                "the first publish reaches the send and is confirmed: " + (sent[0] != null ? sent[0].status + " " + sent[0].storeStatus : "none"));
        check(chat.posts == 1 && chat.records.size() == 1, "the bound chat received exactly the first record");
        PurpleAccountSyncCore.Result state = PurpleAccountSyncCore.inspectState(Files.readAllBytes(new File(sync, "state.json").toPath()));
        check(state.isValid() && state.seq == 1 && state.pendingSeq == 0, "the store holds the confirmed state: seq " + state.seq);
        check(Arrays.equals(binding, Files.readAllBytes(new File(sync, "chat.json").toPath())), "chat.json is untouched");
        check(rootRefusals() == refusals, "nothing on the first publish opened \"/\"");
        runner.close();
    }

    static String stateChat(File sync) throws Exception {
        return new org.json.JSONObject(new String(Files.readAllBytes(new File(sync, "state.json").toPath()), java.nio.charset.StandardCharsets.UTF_8)).getString("chat");
    }

    static Object durableNative(String op, String anchor, String path) throws Exception {
        java.lang.reflect.Method method = PurpleAccountFileSnapshot.class.getDeclaredMethod("durableNative",
                String.class, String.class, String.class, byte[].class, byte[].class, int.class, int.class);
        method.setAccessible(true);
        byte[] reply = (byte[]) method.invoke(null, op, anchor, path, null, null, PurpleAccountFileSnapshot.LIMIT, 0);
        return new org.json.JSONObject(new String(reply, java.nio.charset.StandardCharsets.UTF_8)).getString("status");
    }

    /**
     * The path guard: no durable list, open or walk may name "/" or anything outside the app's files directory. The Java
     * guard refuses with PathOutsideRoot before any native call and logs {@code sync_chat path_refused
     * reason=path_outside_root}; the native walk refuses the same paths again.
     */
    static void guard(File parent) throws Exception {
        Frame frame = new Frame(parent, "magic-path-guard", true);
        field("started", true); field("ready", true); field("owner", A); field("global", A);
        File anchor = PurpleAccountFileSnapshot.anchor();
        check(anchor.equals(PurpleAccountFiles.root().getParentFile()), "the anchor is the app's files directory");
        File inside = new File(new PurpleSyncFileContext(1).files.syncDirectory, "retained-bases");
        check(PurpleAccountFileSnapshot.below(anchor.getAbsolutePath(), inside.getAbsolutePath()), "the sync folder lies below the anchor");
        File[] outside = { new File("/"), new File("/etc/hosts"), anchor, anchor.getParentFile(), new File(anchor.getParentFile(), "other"),
                new File(anchor, "../outside"), new File(anchor, "purple/../../outside"), new File(anchor, "./purple"),
                new File(anchor.getAbsolutePath() + "-sibling/purple") };
        int lines = FileLog.lines.size(), refusals = rootRefusals();
        for (File file : outside) {
            check(!PurpleAccountFileSnapshot.below(anchor.getAbsolutePath(), file.getAbsolutePath()), "outside the anchor: " + file);
            for (String op : new String[] { "read", "directoryRead", "directory" }) {
                String status;
                try {
                    if (op.equals("read")) PurpleAccountFileSnapshot.durable(op, file, null, null, PurpleAccountFileSnapshot.LIMIT, 0);
                    else if (op.equals("directory")) PurpleAccountFileSnapshot.durableDirectory(file);
                    else PurpleAccountFileSnapshot.durableDirectoryIdentity(file);
                    status = "Ready";
                } catch (java.io.IOException e) { status = e.getMessage(); }
                check("PathOutsideRoot".equals(status), "the guard refuses " + op + " of " + file + ": " + status);
            }
            if (file.equals(anchor)) continue; // the anchor is where parent walks stop, not a durable path
            check(!PurpleAccountFileSnapshot.plainParents(file), "the parent walk refuses " + file);
            check(PurpleAccountFileSnapshot.read(new File(file, "state.json")).status == PurpleAccountFileSnapshot.Status.ReadError, "a read below " + file + " is refused");
        }
        check(rootRefusals() == refusals, "no refused path reached \"/\"");
        java.util.List<String> logged = new ArrayList<>(FileLog.lines.subList(lines, FileLog.lines.size()));
        String line = null;
        for (String entry : logged) if (entry.endsWith(" sync_chat path_refused reason=path_outside_root")) { line = entry; break; }
        check(line != null, "each refusal logs sync_chat path_refused reason=path_outside_root: " + logged);
        for (String part : line.substring(line.indexOf(" sync_chat ") + 1).split(" "))
            for (String word : part.split("=")) if (!word.equals("reason")) check(PurpleDiagLogStore.VOCABULARY.contains(word), "vocabulary word: " + word);
        // The native layer refuses on its own: a path outside its anchor, a dot part, and "/" as the anchor.
        check("PathOutsideRoot".equals(durableNative("read", anchor.getAbsolutePath(), "/etc/hosts")), "the native walk refuses a path outside the anchor");
        check("PathOutsideRoot".equals(durableNative("read", anchor.getAbsolutePath(), anchor.getAbsolutePath() + "/purple/../x")), "and a dot part");
        check("PathOutsideRoot".equals(durableNative("read", "/", "/etc/hosts")), "and \"/\" as its anchor");
        check("PathOutsideRoot".equals(durableNative("directoryRead", anchor.getAbsolutePath(), anchor.getAbsolutePath())), "and the anchor itself");
        check(rootRefusals() == refusals, "the native refusals open nothing");
        // A path below the anchor still goes through: the account's sync folder, created on demand.
        PurpleAccountFileSnapshot.durableDirectory(inside.getParentFile());
        check(PurpleAccountFileSnapshot.durableDirectoryIdentity(inside.getParentFile()) != null, "a path below the anchor is served");
        // The impact guard's directory walk stops at the root and refuses a target outside it.
        String refused = null;
        try { new PurpleSyncImpactGuard(PurpleAccountFiles.root(), new ArrayList<>(), new File("/"), PHYSICAL, () -> true); }
        catch (IllegalStateException e) { refused = e.getMessage(); }
        check("impact path outside root".equals(refused), "the impact guard refuses a walk from outside the root: " + refused);
        check(rootRefusals() == refusals, "the impact guard opened nothing above the root");
    }

    private PurpleSyncMagicPublishCases() { }
}
