package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.telegram.messenger.ApplicationLoader;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

/**
 * Devices of one account and the magic group they share, for the end-to-end magic cases
 * (PurpleSyncMagicScenarioCases, PurpleSyncFirstUseCases). Each device has its own files directory behind a link and
 * its own device id, and runs the real store, retention and known-files code. Only Telegram is faked: {@link Group}
 * is the magic group, read and written by the Sync screen's runner (as a transport) and by automatic sync (as the
 * client of the real PurpleSyncAutoNetwork), so both paths see the same messages, edits and deletions.
 */
final class PurpleSyncMagicDevices {
    static final PurpleSyncChatCore.Peer MAGIC = PurpleSyncMagicPublishCases.MAGIC;
    /** Customised settings of one device (a real key away from its default). */
    static final byte[] MINE = REMOTE;
    /** Customised settings of another device, meaning something else. */
    static final byte[] THEIRS = bytes("# C exact\nversion=2\n[last_seen]\ntrade_p=false\n[sync]\nsend_after_save_p=false\n");
    /** MINE with a comment more: other bytes, the same meaning. */
    static final byte[] MINE_COMMENTED = bytes(new String(REMOTE, java.nio.charset.StandardCharsets.UTF_8) + "# a note of this device\n");
    /** The shipped defaults spelled out: OWN sets reasons_p to its default and a key the parser ignores. */
    static final byte[] SPELLED_DEFAULTS = OWN;

    /** The magic group: messages by id, each with a version that an edit bumps (a new document and edit date). */
    static class Group extends PurpleSyncMagicPublishCases.Chat {
        final LinkedHashMap<Integer, byte[]> messages = new LinkedHashMap<>();
        final Map<Integer, Integer> versions = new java.util.HashMap<>();
        int next = 100, edits, deletes;
        int add(byte[] record) { messages.put(++next, record.clone()); versions.put(next, 0); records.add(record.clone()); return next; }
        long document(int id) { return id * 1000L + versions.get(id); }
        long date(int id) { return 1700000000L + versions.get(id); }
        @Override PurpleSyncInventory inventory() {
            PurpleSyncInventory.Builder builder = new PurpleSyncInventory.Builder(202, MAGIC, PurpleSyncMagicPublishCases.ORIGIN);
            for (Map.Entry<Integer, byte[]> entry : messages.entrySet()) builder.fetched(entry.getKey(), document(entry.getKey()), date(entry.getKey()), entry.getValue());
            return builder.build(true);
        }
        /** The next post is refused, as Telegram can refuse one. */
        boolean refuseNextPost;
        @Override public void post(byte[] staged, PostDone done) {
            if (refuseNextPost) { refuseNextPost = false; done.onPostDone(new PostResult(PostStatus.NeedsReview, 0, null)); return; }
            ++posts;
            int id = add(staged);
            done.onPostDone(new PostResult(PostStatus.Confirmed, id, staged.clone(), MAGIC, PurpleSyncMagicPublishCases.ORIGIN));
        }
        /** The records in the group, oldest first. */
        List<byte[]> current() { return new ArrayList<>(messages.values()); }
        Set<String> spaces() {
            Set<String> spaces = new HashSet<>();
            for (byte[] record : messages.values()) spaces.add(PurpleAccountSyncCore.inspectConfigRecord(record).space);
            return spaces;
        }
        /**
         * The group's current settings: the heads core offers a device with no state, that is each install's head
         * that no other head succeeds. Core lists one head per install; a strict successor leaves one offered.
         */
        List<byte[]> heads() {
            PurpleSyncCore.Review review = PurpleSyncCore.review(inventory(), null, null,
                    new PurpleSyncSettingsFile.Contents(PurpleSyncSettingsFile.Status.Absent, null, false, null), "fixture-probe");
            List<byte[]> heads = new ArrayList<>();
            for (PurpleSyncCore.Head head : review.heads) if (review.offered.contains(head.key)) heads.add(head.text);
            return heads;
        }
        int writes() { return posts + edits; }
        /** The Check numbered {@code failCheckAt} (counting from 1) does not finish, as when the network drops. */
        int checks, failCheckAt = -1;
        @Override public void check(Progress progress, CheckDone done) {
            if (++checks == failCheckAt) { done.onCheckDone(new CheckResult(CheckStatus.Cancelled, null, false)); return; }
            super.check(progress, done);
        }
    }

    /** One device of the account. */
    static final class Device {
        final Frame frame;
        final String id;
        Device(File parent, String name, String id) throws Exception {
            frame = PurpleSyncMagicScenarioCases.linked(parent, name);
            this.id = id;
        }
        void use() { ApplicationLoader.files = frame.app; }
        Dev env(PurpleSyncChatCore.Peer chat) { use(); return new Dev(this, new PurpleSyncFileContext(1), chat); }
        PurpleSyncRunner runner(PurpleSyncTransport transport, PurpleSyncChatCore.Peer chat) {
            return new PurpleSyncRunner(1, 202, transport, env(chat), Runnable::run, Runnable::run);
        }
        File sync() { return frame.sync(1); }
        void local(byte[] bytes) throws Exception {
            use();
            File file = frame.settings(1);
            if (bytes == null) Files.deleteIfExists(file.toPath()); else write(file, bytes);
        }
        byte[] local() throws Exception { File file = frame.settings(1); return file.exists() ? Files.readAllBytes(file.toPath()) : null; }
        void bind() throws Exception { directory(sync()); write(new File(sync(), "chat.json"), PurpleSyncMagicBindingFileCases.r2Binding()); }
        PurpleAccountSyncCore.Result state() throws Exception {
            File file = new File(sync(), "state.json");
            return file.exists() ? PurpleAccountSyncCore.inspectState(Files.readAllBytes(file.toPath())) : null;
        }
        String space() throws Exception { PurpleAccountSyncCore.Result state = state(); return state != null && state.isValid() ? state.space : null; }
        boolean archived() {
            String[] names = sync().list();
            if (names != null) for (String name : names) if (PurpleSyncRecoveryFiles.archiveName(name) && new File(sync(), name + "/state.json").isFile()) return true;
            return false;
        }
        int history() { use(); return frame.env(1).history().list().size(); }
    }

    /**
     * The production environment of the Sync screen and of automatic sync (PurpleSyncRunner.Platform over a real
     * PurpleSyncFileContext) for {@code chat}, with what needs Telegram replaced. Whether the account recovery scan
     * applies is automatic sync's production rule ({@link PurpleSyncAutoEngine#recoveryScanApplies}); when it applies,
     * the scan is the real one.
     */
    static final class Dev extends PurpleSyncRunner.Platform {
        final Device device;
        final PurpleSyncChatCore.Peer chat;
        Dev(Device device, PurpleSyncFileContext context, PurpleSyncChatCore.Peer chat) { super(context); this.device = device; this.chat = chat; }
        @Override public void resolveChat(boolean beforePost, Object owner, String key, BooleanSupplier current, Consumer<Boolean> done) { done.accept(current.getAsBoolean()); }
        @Override public void cancelChat(Object owner) { }
        @Override public PurpleAccountSyncCore.Result initialize(int account, String install, String device, String space) {
            return PurpleSyncChatCore.Peer.self().equals(chat) ? PurpleAccountSyncCore.initializeBoundLocalState(account, install, device, space)
                    : PurpleAccountSyncCore.initializeBoundLocalState(account, install, device, space, chat);
        }
        @Override public boolean workerEffects() { return true; }
        @Override public boolean requiresRecovery() { return PurpleSyncAutoEngine.recoveryScanApplies(chat); }
        @Override public PurpleSyncRecoveryModel.Offer recovery(PurpleSyncInventory inventory, PurpleSyncSettingsFile.Contents local) {
            return requiresRecovery() ? super.recovery(inventory, local) : null;
        }
        @Override public boolean sendQueued() { return false; }
        @Override public String device() { return device.id; }
        @Override public long serverTimeMillis(int account) { return 1800000000000L; }
        @Override public PurpleAccountSyncStore store() {
            return PurpleAccountSyncStore.following(context, PurpleSyncChatCore.Peer.self().equals(chat) ? null : chat);
        }
        @Override public PurpleSyncHistory history() { return device.frame.env(1).history(); }
        @Override public PurpleSyncSettingsFile.Contents readSettings() { return device.frame.env(1).readSettings(); }
        @Override public PurpleSyncSettingsFile.WriteResult replaceSettings(PurpleSyncSettingsFile.Contents expected, byte[] bytes, String reason, boolean imported) {
            return device.frame.env(1).replaceSettings(expected, bytes, reason, imported);
        }
    }

    /** The magic group as automatic sync's client reads and writes it. */
    static final class GroupClient implements PurpleSyncClient {
        final Group group;
        final Automatic port;
        final PurpleSyncChatCore.Peer chat;
        boolean editRefused;
        RuntimeException failHistory;
        GroupClient(Group group, Automatic port, PurpleSyncChatCore.Peer chat) { this.group = group; this.port = port; this.chat = chat; }
        Message meta(int id) {
            return new Message(id, true, false, true, PurpleSyncPost.CAPTION, PurpleSyncPost.RECORD_FILE_NAME, chat, group.document(id),
                    group.messages.get(id).length, group.date(id), null);
        }
        public PurpleSyncChatCore.Peer chat() { return chat; }
        public PurpleSyncChatOrigin origin() { return PurpleSyncChatCore.Peer.self().equals(chat) ? null : PurpleSyncMagicPublishCases.ORIGIN; }
        public long userId() { return 202; }
        public boolean isActive() { return port.current(); }
        public void runOnMain(Runnable task) { port.queue(task); }
        public void runOnWorker(Runnable task) { port.queue(task); }
        public void log(String line) { }
        public int requestHistory(int offset, int limit, Reply<History> reply) {
            if (failHistory != null) throw failHistory;
            ArrayList<Integer> ids = new ArrayList<>(group.messages.keySet());
            ids.sort(Collections.reverseOrder());
            ArrayList<Message> rows = new ArrayList<>();
            for (int id : ids) if ((offset == 0 || id < offset) && rows.size() < limit) rows.add(meta(id));
            reply.onReply(History.of(rows, group.messages.size()));
            return 1;
        }
        public int requestMessage(int id, Reply<Lookup> reply) { reply.onReply(group.messages.containsKey(id) ? Lookup.found(meta(id)) : Lookup.missing()); return 2; }
        public void cancelRequest(int token) { }
        public Object download(Message message, Reply<File> reply) {
            try {
                File file = new File(port.device.frame.app, "download-" + message.id + "-" + UUID.randomUUID());
                Files.write(file.toPath(), group.messages.get(message.id));
                reply.onReply(file);
            } catch (Exception e) { reply.onReply(null); }
            return null;
        }
        public void cancelDownload(Object token) { }
        public void localCopies(Reply<List<LocalCopy>> reply) { reply.onReply(Collections.emptyList()); }
        public File filesDirectory() { return new File(port.device.frame.app, "files"); }
        public File cacheDirectory() { return new File(port.device.frame.app, "cache"); }
        public long nowMillis() { return port.clock * 1000; }
        public void send(File file, String caption, String mime, Receipt receipt) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                ++group.posts; ++port.posts;
                receipt.onReceipt(group.add(bytes), false);
            } catch (Exception e) { receipt.onReceipt(0, true); }
        }
        @Override public void edit(int id, File file, byte[] expected, BooleanSupplier current, Reply<PurpleSyncAutoCore.Outcome> reply) {
            if (editRefused) { reply.onReply(PurpleSyncAutoCore.Outcome.EditRefused); return; }
            try {
                check(current.getAsBoolean() && java.util.Arrays.equals(expected, group.messages.get(id)), "automatic edit checks the exact owned body");
                group.messages.put(id, Files.readAllBytes(file.toPath()));
                group.versions.put(id, group.versions.get(id) + 1);
                ++group.edits; ++port.edits;
                reply.onReply(PurpleSyncAutoCore.Outcome.Sent);
            } catch (Exception e) { reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown); }
        }
        @Override public void deleteOwn(int id, BooleanSupplier current, Reply<PurpleSyncAutoCore.Outcome> reply) {
            group.messages.remove(id);
            ++group.deletes;
            reply.onReply(PurpleSyncAutoCore.Outcome.Deleted);
        }
    }

    /** {@code guard} asked on a thread the Looper stub calls main, where PurpleAccountFileSnapshot.worker refuses. */
    static boolean onMain(BooleanSupplier guard) {
        boolean[] answer = { false };
        Thread main = new Thread(() -> answer[0] = guard.getAsBoolean());
        android.os.Looper.mainThread = main;
        try {
            main.start();
            main.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            android.os.Looper.mainThread = null;
        }
        return answer[0];
    }

    /**
     * Automatic sync of one device on the real engine (PurpleSyncAutoEngine with the real PurpleSyncAutoNetwork) over
     * the magic group. The port does what PurpleSyncAuto.Runtime does around the engine: records of another space are
     * not handed to core; with {@link #runFirstUse} a device without a state runs first use (a Check, then
     * PurpleSyncFirstUse with no answer) and, once it joined or published, turns automatic sync on for the new state;
     * an exception of a pass is recorded ({@link #failures}) and holds automatic sync until resumed.
     */
    static final class Automatic implements PurpleSyncAutoEngine.Port {
        final Device device;
        final Group group;
        final PurpleSyncRunner.Session account;
        final PurpleSyncAutoEngine engine;
        final GroupClient client;
        final PurpleSyncAutoNetwork network;
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<String> failures = new ArrayList<>();
        boolean draining, enabled, runFirstUse;
        long clock = 1800000000L;
        int posts, edits, firstUses, mismatches;
        PurpleSyncFirstUse.Outcome firstUse;
        PurpleSyncAutoCore.Reason reason;
        final PurpleSyncChatCore.Peer chat;
        Automatic(Device device, Group group) { this(device, group, MAGIC); }
        /** Automatic sync of {@code device} through {@code chat}: the magic group, or Saved Messages before magic. */
        Automatic(Device device, Group group, PurpleSyncChatCore.Peer chat) {
            this.device = device;
            this.group = group;
            this.chat = chat;
            account = new PurpleSyncRunner.Session(1, 202, device.env(chat));
            engine = new PurpleSyncAutoEngine(account, B, this);
            client = new GroupClient(group, this, chat);
            network = new PurpleSyncAutoNetwork(client, engine.session, this::current);
        }
        public boolean current() { return account.available(); }
        public boolean enabledHint() { return enabled; }
        public void enabledHint(boolean value) { enabled = value; }
        public boolean ownerAnswered() { return true; }
        public long now() { return clock; }
        public void queue(Runnable task) {
            tasks.add(task);
            if (draining) return;
            draining = true;
            try {
                while (!tasks.isEmpty()) {
                    Runnable next = tasks.remove();
                    try { next.run(); } catch (Exception | LinkageError e) { failed(e); engine.suspend(); }
                }
            } finally { draining = false; }
        }
        public void retry(Runnable task, long delay) { retryTask = task; retryDelay = delay; }
        long retryDelay;
        public void cancelRetry() { }
        public void status(PurpleSyncAutoCore.Record value, PurpleSyncAutoCore.Reason why) { reason = value != null ? value.held() : why; }
        /** Thrown by the next automatic check, as an unexpected failure inside a pass. */
        RuntimeException failCheck;
        int checks;
        public void check(String ticket, int[] ids, BooleanSupplier current, PurpleSyncAutoEngine.Reply<PurpleSyncAutoEngine.Observed> reply) {
            ++checks;
            if (failCheck != null) throw failCheck;
            network.check(ids, current, observed -> {
                if (observed != null && PurpleSyncChat.spaceMismatch(chat, observed.inventory, PurpleAccountSyncStore.stateSpace(((Dev) account.env).context))) {
                    ++mismatches;
                    reply.done(null);
                    return;
                }
                reply.done(observed);
            });
        }
        /** The next sends end with an unknown outcome before reaching Telegram, as the device's stuck pass did. */
        int dropSends, sends;
        Runnable retryTask;
        public void send(boolean edit, int id, byte[] bytes, byte[] previous, BooleanSupplier current, PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply) {
            ++sends;
            if (dropSends > 0) { --dropSends; reply.done(PurpleSyncAutoCore.Outcome.OutcomeUnknown); return; }
            // As PurpleSyncAuto.Runtime: the lookup's gate and Telegram's callbacks ask this guard on the main thread,
            // where the store refuses to open. A guard that opens it there refused every automatic send on the device.
            PurpleModernSyncCases.check(onMain(current), "the send's guard answers on the main thread");
            enabled = false;
            PurpleModernSyncCases.check(!onMain(current), "and still refuses there once automatic sync is off");
            enabled = true;
            network.send(edit, id, bytes, previous, current, reply);
        }
        public void retire(int id, byte[] exact, BooleanSupplier current, PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply) {
            network.retire(id, exact, current, reply);
        }
        long[] backoff = { 0, 0 };
        @Override public long[] backoff() { return backoff.clone(); }
        @Override public void backoff(long failures, long notBefore) { backoff = new long[] { failures, notBefore }; }
        @Override public boolean held() { return !failures.isEmpty() || firstUse != null && firstUse.result == PurpleSyncFirstUse.Result.Ask; }
        @Override public void failed(Throwable error) { failures.add(PurpleSyncAutoEngine.failureCode(error)); }
        long firstChecked;
        int firstScans;
        @Override public boolean firstUse(PurpleSyncAutoCore.Event event) {
            ++firstUses;
            if (!runFirstUse || event == PurpleSyncAutoCore.Event.Disable) return false;
            // As PurpleSyncAuto.Runtime.firstUse: the lifecycle triggers read the chat once per 15 minutes.
            if (!PurpleSyncFirstUse.due(event, clock, firstChecked)) return true;
            firstChecked = clock;
            ++firstScans;
            firstUse = PurpleSyncFirstUseCases.firstUse(device, group, PurpleSyncCore.FirstUseChoice.None, "automatic first use of " + device.id);
            if (firstUse.result == PurpleSyncFirstUse.Result.Joined || firstUse.result == PurpleSyncFirstUse.Result.Published) {
                engine.enabled(true, () -> true);
                // As PurpleSyncAuto.Runtime.settle: an unsent post behind first use is the file's Save.
                if (firstUse.unsent) try { byte[] local = device.local(); if (local != null) engine.event(PurpleSyncAutoCore.Event.LocalSaved, local); }
                catch (Exception e) { throw new AssertionError(e); }
            } else if (firstUse.result == PurpleSyncFirstUse.Result.Wait || firstUse.result == PurpleSyncFirstUse.Result.Ask) {
                enabled = true;
            }
            return true;
        }
        void event(PurpleSyncAutoCore.Event event) { device.use(); engine.event(event); }
        /** A Save of {@code bytes}: the file is written and automatic sync gets the LocalSaved event, as after the debounce. */
        void saved(byte[] bytes) throws Exception { device.local(bytes); engine.event(PurpleSyncAutoCore.Event.LocalSaved, bytes); }
        /** A later automatic pass: the 15 minute lifecycle limit has passed. */
        void later() { clock += 1000; event(PurpleSyncAutoCore.Event.SessionReady); }
    }

    private PurpleSyncMagicDevices() { }
}
