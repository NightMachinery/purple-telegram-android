package org.telegram.messenger.purple;

import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PurpleSyncAutoCases {
    static final class Port implements PurpleSyncAutoEngine.Port {
        final Frame frame;
        final Env env;
        final PurpleSyncRunner.Session account;
        PurpleSyncAutoEngine engine;
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean draining, enabled, answered = true, available = true, failCheck, holdCheck, editSupported = true;
        PurpleSyncAutoCore.Action stopOn;
        long clock = 1800000000L, delay, transientAt;boolean transientTaken;
        public long takeTransientRetryAt(){if(transientTaken)return 0;transientTaken=true;return transientAt;}
        public long transientRetryAt(){return transientAt;}
        Runnable retry, checking;
        int checks, posts, edits, deletes;
        PurpleSyncAutoCore.Record record;
        PurpleSyncAutoCore.Reason reason;
        final Client client;
        PurpleSyncAutoNetwork network;
        Port(Frame frame, int slot) {
            this.frame = frame;
            env = frame.env(slot);
            account = new PurpleSyncRunner.Session(slot, slot == 0 ? 101 : 202, env);
            client = new Client(this);
            restart();
        }
        void restart() {
            if (engine != null)
                engine.close();
            engine = new PurpleSyncAutoEngine(account, account.account == 0 ? A : B, this);
            network = new PurpleSyncAutoNetwork(client, engine.session, this::current);
        }
        public boolean current() {
            return available && account.available();
        }
        public boolean enabledHint() {
            return enabled;
        }
        public void enabledHint(boolean value) {
            enabled = value;
        }
        public boolean ownerAnswered() {
            return answered;
        }
        public long now() {
            return clock;
        }
        public void queue(Runnable task) {
            tasks.add(task);
            if (draining)
                return;
            draining = true;
            try {
                while (!tasks.isEmpty()) tasks.remove().run();
            } finally {
                draining = false;
            }
        }
        public void retry(Runnable task, long milliseconds) {
            retry = task;
            delay = milliseconds;
        }
        public void cancelRetry() {
            retry = null;
        }
        public void status(PurpleSyncAutoCore.Record value, PurpleSyncAutoCore.Reason why) {
            record
            = value;
            reason = value != null ? value.held() : why;
            if (stopOn != null && value != null && value.value.optBoolean("active")
                && value.value.optInt("pending") == stopOn.ordinal())
                engine.close();
        }
        public boolean editSupported() {
            return editSupported;
        }
        public void check(String ticket, int[] ids, BooleanSupplier current,
            PurpleSyncAutoEngine.Reply<PurpleSyncAutoEngine.Observed> reply) {
            ++checks;
            Runnable step = () -> {
                if (failCheck)
                    reply.done(null);
                else
                    network.check(ids, current, reply);
            };
            if (holdCheck)
                checking = step;
            else
                step.run();
        }
        public void send(boolean edit, int id, byte[] bytes, byte[] previous, BooleanSupplier current,
            PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply) {
            network.send(edit, id, bytes, previous, current, reply);
        }
        public void retire(int id, byte[] exact, BooleanSupplier current,
            PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply) {
            network.retire(id, exact, current, reply);
        }
        void enable() {
            engine.event(PurpleSyncAutoCore.Event.Enable);
            PurpleModernSyncCases.check(enabled, "explicit enable persists opt-in");
        }
        void saved(byte[] bytes) throws Exception {
            write(frame.settings(account.account), bytes);
            engine.event(PurpleSyncAutoCore.Event.LocalSaved, bytes);
        }
        JSONObject disk() throws Exception {
            return new JSONObject(Files.readString(new File(frame.sync(account.account), "auto.json").toPath()));
        }
        void fireRetry() {
            Runnable task = retry;
            PurpleModernSyncCases.check(task != null, "core armed retry");
            retry = null;
            queue(task);
        }
        void finishCheck() {
            Runnable step = checking;
            checking = null;
            holdCheck = false;
            queue(step);
        }
    }
    static final class Client implements PurpleSyncClient {
        final Port port;
        final LinkedHashMap<Integer, byte[]> messages = new LinkedHashMap<>();
        int next = 500;
        final Set<Integer> hidden = new HashSet<>(), unknown = new HashSet<>();
        boolean holdSend, duplicate, editRefused, deleteRefused, deleteUnknown, deleteRemoves = true;
        Receipt sending;
        byte[] sent;
        Client(Port port) {
            this.port = port;
        }
        void remote(byte[] text, byte[]... parents) throws Exception {
            PurpleAccountSyncCore.Result built =
                PurpleAccountSyncCore.buildConfigRecord(text, parents, port.frame.space(port.account.account),
                    install(2), device(2), "Desktop", "auto fixture", messages.size() + 1, port.clock);
            check(built.isValid(), "real remote record");
            messages.put(++next, built.record);
        }
        public long userId() {
            return port.account.userId;
        }
        public boolean isActive() {
            return port.current();
        }
        public void runOnMain(Runnable task) {
            port.queue(task);
        }
        public void runOnWorker(Runnable task) {
            port.queue(task);
        }
        public void log(String line) {}
        public int requestHistory(int offset, int limit, Reply<History> reply) {
            ArrayList<Message> rows = new ArrayList<>();
            ArrayList<Integer> ids = new ArrayList<>(messages.keySet());
            ids.sort(Collections.reverseOrder());
            for (int id : ids)
                if ((offset == 0 || id < offset) && !hidden.contains(id)) {
                    rows.add(meta(id));
                    if (rows.size() == limit)
                        break;
                }
            reply.onReply(History.of(rows, messages.size() - hidden.size()));
            return 1;
        }
        Message meta(int id) {
            byte[] data = messages.get(id);
            return new Message(id, true, false, true, PurpleSyncPost.CAPTION, PurpleSyncPost.RECORD_FILE_NAME, true,
                10000 + id, data.length, port.clock, null);
        }
        public int requestMessage(int id, Reply<Lookup> reply) {
            reply.onReply(unknown.contains(id) ? Lookup.failed("unknown exact-id read")
                    : messages.containsKey(id) ? Lookup.found(meta(id))
                                               : Lookup.missing());
            return 2;
        }
        public void cancelRequest(int token) {}
        public Object download(Message message, Reply<File> reply) {
            try {
                File file = new File(port.frame.app, "download-" + message.id + "-" + UUID.randomUUID());
                Files.write(file.toPath(), messages.get(message.id));
                reply.onReply(file);
            } catch (Exception e) {
                reply.onReply(null);
            }
            return null;
        }
        public void cancelDownload(Object token) {}
        public void localCopies(Reply<List<LocalCopy>> reply) {
            reply.onReply(Collections.emptyList());
        }
        public File filesDirectory() {
            return new File(port.frame.app, "files");
        }
        public File cacheDirectory() {
            return new File(port.frame.app, "cache");
        }
        public long nowMillis() {
            return port.clock * 1000;
        }
        public void send(File file, String caption, String mime, Receipt receipt) {
            check(receipt.current(), "automatic upload guard remains valid at handoff");
            ++port.posts;
            try {
                sent = Files.readAllBytes(file.toPath());
                if (holdSend) {
                    sending = receipt;
                    return;
                }
                finish(receipt, false);
            } catch (Exception e) {
                receipt.onReceipt(0, true);
            }
        }
        void finish(Receipt receipt, boolean unknownOutcome) {
            messages.put(++next, sent.clone());
            if (duplicate)
                messages.put(++next, sent.clone());
            receipt.onReceipt(unknownOutcome ? 0 : next, false);
        }
        void finishSend(boolean unknownOutcome) {
            Receipt receipt = sending;
            sending = null;
            holdSend = false;
            finish(receipt, unknownOutcome);
        }
        public void edit(
            int id, File file, byte[] expected, BooleanSupplier current, Reply<PurpleSyncAutoCore.Outcome> reply) {
            ++port.edits;
            if (editRefused) {
                reply.onReply(PurpleSyncAutoCore.Outcome.EditRefused);
                return;
            }
            check(current.getAsBoolean() && Arrays.equals(expected, messages.get(id)),
                "exact old ledger body checked before edit");
            try {
                messages.put(id, Files.readAllBytes(file.toPath()));
                reply.onReply(PurpleSyncAutoCore.Outcome.Sent);
            } catch (Exception e) {
                reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
            }
        }
        public void deleteOwn(int id, BooleanSupplier current, Reply<PurpleSyncAutoCore.Outcome> reply) {
            ++port.deletes;
            check(current.getAsBoolean(), "retirement rechecks opening identity and store");
            if (deleteRefused) {
                reply.onReply(PurpleSyncAutoCore.Outcome.DeletionRefused);
                return;
            }
            if (deleteRemoves)
                messages.remove(id);
            reply.onReply(
                deleteUnknown ? PurpleSyncAutoCore.Outcome.OutcomeUnknown : PurpleSyncAutoCore.Outcome.Deleted);
        }
    }
    static Port bound(File parent, String name) throws Exception {
        Frame f = new Frame(parent, "auto-" + name, true);
        f.initialize(1);
        return new Port(f, 1);
    }
    static void initialPublish(Port p) throws Exception {
        p.enable();
        check(
            p.reason == PurpleSyncAutoCore.Reason.NoSavedBytes, "enable does not turn creation into saved provenance");
        p.saved(OWN);
        check(p.posts == 1 && p.reason == PurpleSyncAutoCore.Reason.None,
            "existing account-local save stages posts and reconciles");
        check(!new File(p.frame.sync(1), "pending/config.json").exists(),
            "verified confirmation clears only completed stage");
        p.frame.unchangedA();
    }
    static void defaults(File parent) throws Exception {
        Port p = bound(parent, "off");
        byte[] before = p.frame.state(1);
        Map<File, byte[]> state = PurpleSyncRecoveryCases.files(p.frame.sync(1));
        for (PurpleSyncAutoCore.Event e :
            new PurpleSyncAutoCore.Event[] {PurpleSyncAutoCore.Event.SessionReady, PurpleSyncAutoCore.Event.Foreground,
                PurpleSyncAutoCore.Event.Unlock, PurpleSyncAutoCore.Event.LocalSaved})
            p.engine.event(e, OWN);
        check(p.checks == 0 && !new File(p.frame.sync(1), "auto.json").exists(),
            "off has no lifecycle or save disk/network effects");
        PurpleSyncRecoveryCases.retained(state);
        check(Arrays.equals(before, p.frame.state(1)), "off state exact");
        p.frame.unchangedA();
        p.engine.close();
        Port q = bound(parent, "opaque");
        write(new File(q.frame.sync(1), "auto.json"), "{not a record}");
        q.engine.event(PurpleSyncAutoCore.Event.Enable);
        check(q.reason == PurpleSyncAutoCore.Reason.InvalidRecord && !q.enabled, "opaque record cannot enable");
    }
    static void publishAndEdit(File parent) throws Exception {
        Port p = bound(parent, "publish-edit");
        initialPublish(p);
        int id = p.client.messages.keySet().iterator().next();
        int checks = p.checks;
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(p.checks == checks, "foreground throttled inside15minutes");
        p.clock += 899;
        p.engine.event(PurpleSyncAutoCore.Event.Unlock);
        check(p.checks == checks, "unlock899seconds throttled");
        p.clock++;
        p.engine.event(PurpleSyncAutoCore.Event.SessionReady);
        check(p.checks == checks + 1, "exact900second foreground boundary");
        p.saved(REMOTE);
        check(p.posts == 1 && p.edits == 1 && p.client.messages.containsKey(id),
            "changed saved bytes edit the verified owned head");
        check(p.reason == PurpleSyncAutoCore.Reason.None, "edited record confirmed by fresh read");
        p.frame.unchangedA();
        p.engine.close();
        Port fallback = bound(parent, "edit-fallback");
        initialPublish(fallback);
        fallback.client.editRefused = true;
        fallback.saved(REMOTE);
        check(fallback.edits == 1 && fallback.posts == 2, "definitive edit refusal reconciles before post fallback");
        check(fallback.deletes == 1, "only obsolete own record retired after fallback");
        check(fallback.reason == PurpleSyncAutoCore.Reason.None, "post fallback finishes safely");
        fallback.frame.unchangedA();
        fallback.engine.close();
    }
    static void retries(File parent) throws Exception {
        Port p = bound(parent, "retry-run");
        p.failCheck = true;
        p.enable();
        check(p.delay == 30000 && p.disk().getInt("retries") == 1, "first retry30seconds");
        long at = p.disk().getLong("retryAt");
        String run = p.disk().getString("run");
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(p.disk().getLong("retryAt") == at && p.disk().getInt("retries") == 1,
            "ordinary trigger keeps backoff and budget");
        p.saved(REMOTE);
        check(p.disk().getLong("retryAt") == at && p.disk().getBoolean("queuedSave"), "save merges into backoff");
        p.fireRetry();
        check(p.checks == 1, "early Retry does not check");
        p.restart();
        p.clock = at + 1;
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(p.checks == 1, "restart and elapsed ordinary trigger do not bypass Retry");
        p.fireRetry();
        check(p.delay == 120000, "second retry120seconds");
        p.engine.event(PurpleSyncAutoCore.Event.Disable);
        check(!p.enabled && p.disk().getInt("retries") == 2, "disable preserves pending retry budget");
        p.restart();
        p.engine.event(PurpleSyncAutoCore.Event.Enable);
        check(p.disk().getInt("retries") == 2, "re-enable keeps backoff count");
        p.clock = p.disk().getLong("retryAt");
        p.fireRetry();
        check(p.delay == 600000, "third retry600seconds");
        p.clock = p.disk().getLong("retryAt");
        p.fireRetry();
        check(p.reason == PurpleSyncAutoCore.Reason.RetryStopped && p.disk().getBoolean("runStopped"),
            "fourth failure stops run");
        p.answered = false;
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(p.disk().getBoolean("runStopped"), "gate Hold preserves terminal run phase");
        p.engine.event(PurpleSyncAutoCore.Event.Disable);
        p.restart();
        p.engine.event(PurpleSyncAutoCore.Event.Enable);
        check(p.disk().getBoolean("runStopped"), "disable/restart while gate held preserves terminal run phase");
        p.answered = true;
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(!p.disk().getBoolean("runStopped") && p.disk().getInt("retries") == 1
                && !run.equals(p.disk().getString("run")),
            "next eligible ordinary trigger starts fresh retry budget");
        p.frame.unchangedA();
        p.engine.close();
    }
    static void safeApplyAndHolds(File parent) throws Exception {
        Port p = bound(parent, "safe-apply");
        initialPublish(p);
        byte[] parentRecord = p.client.messages.values().iterator().next();
        p.client.remote(REMOTE, parentRecord);
        p.clock += 900;
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(Arrays.equals(REMOTE, Files.readAllBytes(p.frame.settings(1).toPath())),
            "strict successor safe impact auto-applies exact bytes");
        check(p.env.history().list().size() == 1, "automatic update saves truthful History");
        check(p.posts == 1, "remote apply never echoes publication");
        p.frame.unchangedA();
        p.engine.close();
        for (String kind : new String[] {"absent", "oversized", "fallback", "q1", "v2", "conflict"}) {
            Port q = bound(parent, "hold-" + kind);
            initialPublish(q);
            int sends = q.posts;
            if (kind.equals("absent"))
                Files.delete(q.frame.settings(1).toPath());
            if (kind.equals("oversized"))
                write(q.frame.settings(1), new byte[256 * 1024 + 1]);
            if (kind.equals("fallback")) {
                write(q.frame.good(1), OWN);
                write(q.frame.settings(1), "invalid = [");
            }
            if (kind.equals("q1"))
                q.answered = false;
            if (kind.equals("v2")) {
                JSONObject state =
                    new JSONObject(new String(q.frame.state(1), java.nio.charset.StandardCharsets.UTF_8));
                state.put("version", 2).put("chat", "self");
                PurpleAccountSyncCore.Result parsed = PurpleAccountSyncCore.inspectState(bytes(state.toString()));
                check(parsed.isValid(), "native-valid v2 fixture");
                write(new File(q.frame.sync(1), "state.json"), parsed.state);
            }
            if (kind.equals("conflict"))
                q.client.remote(REMOTE);
            q.clock += 900;
            q.engine.event(PurpleSyncAutoCore.Event.Foreground);
            check(q.posts == sends && q.reason != PurpleSyncAutoCore.Reason.None, "automatic hold " + kind);
            q.frame.unchangedA();
            q.engine.close();
        }
    }
    static void uncertainAndRestart(File parent) throws Exception {
        for (boolean duplicate : new boolean[] {false, true}) {
            Port p = bound(parent, "unknown-send-" + duplicate);
            p.enable();
            p.client.holdSend = true;
            p.client.duplicate = duplicate;
            p.saved(OWN);
            check(p.client.sending != null && new File(p.frame.sync(1), "pending/config.json").isFile(),
                "stage and issued ledger durable before uncertain send");
            p.client.finishSend(true);
            check(p.record.retryAt() > p.clock, "uncertain callback schedules fresh reconciliation");
            p.restart();
            p.clock = p.record.retryAt();
            p.engine.event(PurpleSyncAutoCore.Event.Foreground);
            p.fireRetry();
            check(p.posts == 1 && p.reason == PurpleSyncAutoCore.Reason.None,
                "fresh exact read confirms unknown send without blind post, duplicate=" + duplicate);
            check(!new File(p.frame.sync(1), "pending/config.json").exists(), "reconciled exact pending stage cleared");
            p.frame.unchangedA();
            p.engine.close();
        }
        Port p = bound(parent, "late-old-send");
        p.enable();
        p.client.holdSend = true;
        p.saved(OWN);
        PurpleSyncClient.Receipt old = p.client.sending;
        p.client.messages.put(++p.client.next, p.client.sent.clone());
        p.restart();
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(p.posts == 1 && p.reason == PurpleSyncAutoCore.Reason.None,
            "restart finds the exact issued record before sending again");
        byte[] state = p.frame.state(1);
        old.onReceipt(p.client.next, false);
        check(Arrays.equals(state, p.frame.state(1)), "late old session callback cannot commit into resumed run");
        p.frame.unchangedA();
        p.engine.close();
        for (boolean completed : new boolean[] {false, true}) {
            Port q = bound(parent, "interrupted-apply-" + completed);
            initialPublish(q);
            q.client.remote(REMOTE, q.client.messages.values().iterator().next());
            q.stopOn = PurpleSyncAutoCore.Action.AutoApply;
            q.clock += 900;
            q.engine.event(PurpleSyncAutoCore.Event.Foreground);
            check(q.disk().getInt("pending") == PurpleSyncAutoCore.Action.AutoApply.ordinal(),
                "apply checkpoint persisted before effect");
            byte[] oldState = q.frame.state(1);
            JSONObject checkpoint = q.disk();
            if (completed) {
                PurpleSyncSettingsFile.Contents local = q.env.readSettings();
                byte[] text = PurpleSyncAutoCore.decode(checkpoint.getString("expectedFile"));
                PurpleSyncApply.Written written = PurpleSyncApply.writeWithHistory(q.env, q.account, local, text,
                    PurpleSyncCore.settingsFingerprint(text), PurpleSyncHistory.Reason.BeforeUpdate, "crash fixture",
                    "", null, true, "crash fixture");
                check(written.status == PurpleSyncCore.ApplyStatus.Applied,
                    "actual guarded apply and History before injected crash");
                try (PurpleAccountSyncStore store = q.env.store()) {
                    check(store.open(true, 1, device(1)).status == PurpleAccountSyncStore.Status.Ready,
                        "crash fixture account store open");
                    check(store.commitAutomatic(oldState,
                              PurpleSyncAutoCore.decode(checkpoint.getString("expectedState")), store.autoBytes(),
                              false, () -> true),
                        "actual state commit before injected completion loss");
                }
            }
            q.stopOn = null;
            q.restart();
            q.engine.event(PurpleSyncAutoCore.Event.Foreground);
            check(completed ? q.reason == PurpleSyncAutoCore.Reason.None
                            : q.reason == PurpleSyncAutoCore.Reason.InterruptedApply,
                "restart distinguishes fully committed apply from incomplete replacement");
            check(Arrays.equals(completed ? REMOTE : OWN, Files.readAllBytes(q.frame.settings(1).toPath())),
                "restart never repeats an unguarded replacement");
            q.frame.unchangedA();
            q.engine.close();
        }
    }
    static void retirement(File parent) throws Exception {
        for (boolean present : new boolean[] {false, true}) {
            Port p = bound(parent, "retire-uncertain-" + present);
            initialPublish(p);
            p.editSupported = false;
            p.client.deleteUnknown = true;
            p.client.deleteRemoves = !present;
            int old = p.client.messages.keySet().iterator().next();
            p.saved(REMOTE);
            check(p.deletes == 1 && p.record.retryAt() > p.clock, "ambiguous deletion retains ledger and backs off");
            p.client.unknown.add(old);
            p.client.hidden.add(old);
            p.clock = p.record.retryAt();
            p.fireRetry();
            check(p.deletes == 1 && p.record.retryAt() > p.clock,
                "inventory omission plus Unknown exact-id read never proves absence");
            check(PurpleSyncAutoEngine.exactIds(p.frame.state(1), 0).length == 2,
                "unknown deletion keeps both own ledger entries");
            p.client.unknown.clear();
            p.client.hidden.clear();
            p.client.deleteUnknown = false;
            p.client.deleteRemoves = true;
            p.clock = p.record.retryAt();
            p.fireRetry();
            check(p.reason == PurpleSyncAutoCore.Reason.None && p.deletes == (present ? 2 : 1),
                "affirmative absence commits, unchanged Present retries bounded deletion");
            check(PurpleSyncAutoEngine.exactIds(p.frame.state(1), 0).length == 1,
                "obsolete own ledger removed only after exact absence or definitive deletion");
            p.frame.unchangedA();
            p.engine.close();
        }
        Port refused = bound(parent, "retire-refused");
        initialPublish(refused);
        refused.editSupported = false;
        refused.client.deleteRefused = true;
        refused.saved(REMOTE);
        check(refused.reason == PurpleSyncAutoCore.Reason.DeletionRefused && refused.deletes == 1
                && PurpleSyncAutoEngine.exactIds(refused.frame.state(1), 0).length == 2,
            "definitive retirement refusal holds without dropping ledger");
        refused.frame.unchangedA();
        refused.engine.close();
        Port many = bound(parent, "retire-limit");
        PurpleSyncRunner manual = many.env.runner();
        for (int i = 0; i < 7; ++i) {
            write(many.frame.settings(1),
                new String(OWN, java.nio.charset.StandardCharsets.UTF_8) + "# manual " + i + "\n");
            PurpleSyncRunner.PostTicket ticket = PurpleSyncLifecycleCases.ticket(manual);
            PurpleSyncPublisher.Result[] out = {null};
            check(manual.publish(ticket, result -> out[0] = result) == PurpleSyncRunner.Start.Started && out[0] != null
                    && out[0].status == PurpleSyncCore.PublishStatus.Confirmed,
                "manual fixture preserves all own obsolete records");
        }
        manual.close();
        int id = 100;
        for (byte[] bytes : many.frame.clouds[1].records) many.client.messages.put(++id, bytes);
        many.client.next = id;
        many.enable();
        check(many.deletes == 4 && PurpleSyncAutoEngine.exactIds(many.frame.state(1), 0).length == 3,
            "retire at most four own obsolete records per run");
        many.frame.unchangedA();
        many.engine.close();
    }
    static void isolationAndCoalescing(File parent) throws Exception {
        for (String change :
            new String[] {"selected", "role", "local", "global-file", "backend", "logout", "slot-reuse"}) {
            Port p = bound(parent, "isolate-" + change);
            initialPublish(p);
            p.client.remote(REMOTE, p.client.messages.values().iterator().next());
            p.clock += 900;
            p.holdCheck = true;
            p.engine.event(PurpleSyncAutoCore.Event.Foreground);
            byte[] state = p.frame.state(1), settings = Files.readAllBytes(p.frame.settings(1).toPath());
            if (change.equals("selected"))
                org.telegram.messenger.UserConfig.selectedAccount = 0;
            if (change.equals("role"))
                ++p.frame.role;
            if (change.equals("local"))
                p.frame.local("follow");
            if (change.equals("global-file"))
                write(p.frame.settings(0),
                    new String(GLOBAL, java.nio.charset.StandardCharsets.UTF_8) + "# changed by A\n");
            if (change.equals("backend"))
                org.telegram.tgnet.ConnectionsManager.getInstance(1).test = true;
            if (change.equals("logout"))
                PurpleSyncReviewFixCases.logout(1);
            if (change.equals("slot-reuse"))
                PurpleSyncReviewFixCases.login(1, 303);
            p.finishCheck();
            if (change.equals("selected"))
                check(Arrays.equals(REMOTE, Files.readAllBytes(p.frame.settings(1).toPath())),
                    "selected account change still applies only captured B");
            else
                check(Arrays.equals(state, p.frame.state(1))
                        && Arrays.equals(settings, Files.readAllBytes(p.frame.settings(1).toPath())),
                    "old callback refuses changed opening scope: " + change);
            if (!change.equals("global-file"))
                p.frame.unchangedA();
            p.engine.close();
        }
        Port p = bound(parent, "manual-busy-save");
        initialPublish(p);
        Object manual = new Object();
        String key = p.env.operationKey(1, 202);
        PurpleSyncOperations.Capability manualToken=PurpleSyncOperations.acquire(key,manual,()->true);
        check(manualToken!=null, "manual operation obtains shared lease");
        int checks = p.checks;
        p.saved(REMOTE);
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(p.checks == checks, "automatic effects wait behind manual account lease");
        PurpleSyncOperations.end(key,manual,manualToken);
        check(p.edits == 1 && p.reason == PurpleSyncAutoCore.Reason.None,
            "settled saved intent survives later foreground while waiting for manual lease");
        p.frame.unchangedA();
        p.engine.close();
        Port q = bound(parent, "save-during-post");
        q.enable();
        q.client.holdSend = true;
        q.saved(OWN);
        byte[] staged = Files.readAllBytes(new File(q.frame.sync(1), "pending/config.json").toPath());
        q.saved(REMOTE);
        q.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(Arrays.equals(staged, Files.readAllBytes(new File(q.frame.sync(1), "pending/config.json").toPath())),
            "coalesced newer save never replaces exact pending record");
        q.client.finishSend(false);
        check(q.posts == 1 && q.edits == 1 && q.reason == PurpleSyncAutoCore.Reason.None,
            "pending bytes finish first then newer saved intent publishes after fresh Check");
        q.frame.unchangedA();
        q.engine.close();
    }
    static void codecsAndCrashFiles(File parent) throws Exception {
        Port p = bound(parent, "codec");
        p.enable();
        byte[] original = Files.readAllBytes(new File(p.frame.sync(1), "auto.json").toPath());
        JSONObject json = p.disk();
        json.put("future-field", new JSONObject().put("kept", true)).put("event", "9007199254740993");
        PurpleSyncAutoCore.Record record = PurpleSyncAutoCore.decodeRecord(bytes(json.toString()), B);
        check(record != null && record.value.getString("event").equals("9007199254740993")
                && record.nextEvent().equals("9007199254740994"),
            "auto counters retain exact64bit decimal above double precision");
        write(new File(p.frame.sync(1), "auto.json"), record.bytes);
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(p.disk().getJSONObject("future-field").getBoolean("kept"),
            "unknown auto fields survive durable NoOp transitions");
        byte[] opaque = bytes("{\"version\":2,\"future\":true}");
        write(new File(p.frame.sync(1), "auto.json"), opaque);
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(p.reason == PurpleSyncAutoCore.Reason.InvalidRecord
                && Arrays.equals(opaque, Files.readAllBytes(new File(p.frame.sync(1), "auto.json").toPath())),
            "newer auto record remains opaque and unmodified");
        p.frame.unchangedA();
        p.engine.close();
        for (String suffix : new String[] {"new", "bak"}) {
            Port q = bound(parent, "auto-crash-" + suffix);
            q.enable();
            File file = new File(q.frame.sync(1), "auto.json");
            byte[] committed = Files.readAllBytes(file.toPath());
            write(new File(file.getPath() + "." + suffix),
                suffix.equals("bak") ? committed : bytes("interrupted auto write"));
            if (suffix.equals("bak"))
                write(file, "incomplete");
            PurpleSyncRunner runner = q.env.runner();
            check(fresh(runner).review.status == PurpleSyncCore.ReviewStatus.Ready,
                "manual Check recovers own auto AtomicFile " + suffix + " before classification");
            runner.close();
            check(Arrays.equals(committed, Files.readAllBytes(file.toPath()))
                    && !new File(file.getPath() + "." + suffix).exists(),
                "auto recovery retains last committed record");
            q.frame.unchangedA();
            q.engine.close();
        }
        Port q = bound(parent, "unknown-auto-new");
        File unknown = new File(q.frame.sync(1), "auto.json.new");
        write(unknown, bytes("{\"version\":2}"));
        PurpleSyncRunner runner = q.env.runner();
        check(fresh(runner).review.status != PurpleSyncCore.ReviewStatus.Ready && unknown.exists(),
            "unproved opaque auto sidecar refuses without resetting it");
        runner.close();
        q.frame.unchangedA();
        q.engine.close();
    }
    static void staleTimersAndTimeouts(File parent) throws Exception {
        Port p = bound(parent, "stale-timer");
        p.failCheck = true;
        p.enable();
        Runnable old = p.retry;
        p.clock = p.record.retryAt();
        p.fireRetry();
        byte[] record = Files.readAllBytes(new File(p.frame.sync(1), "auto.json").toPath());
        int checks = p.checks;
        p.clock = p.record.retryAt();
        old.run();
        check(p.checks == checks
                && Arrays.equals(record, Files.readAllBytes(new File(p.frame.sync(1), "auto.json").toPath())),
            "old retry timer cannot consume a newer deadline or ticket");
        p.frame.unchangedA();
        p.engine.close();
        Port q = bound(parent, "network-timeout");
        q.enable();
        Set<Runnable> before = new HashSet<>(org.telegram.messenger.AndroidUtilities.delayed.keySet());
        q.client.holdSend = true;
        q.saved(OWN);
        ArrayList<Runnable> timers = new ArrayList<>();
        for (Runnable task : org.telegram.messenger.AndroidUtilities.delayed.keySet())
            if (!before.contains(task))
                timers.add(task);
        check(!timers.isEmpty(), "network operation has a bounded transport deadline");
        q.clock+=300;
        for (Runnable task : timers) task.run();
        check(q.record.retryAt() > q.clock && new File(q.frame.sync(1), "pending/config.json").isFile(),
            "missing network callback releases lease, retains stage and enters core backoff");
        PurpleSyncClient.Receipt late = q.client.sending;
        q.client.messages.put(++q.client.next, q.client.sent.clone());
        q.clock = q.record.retryAt();
        q.fireRetry();
        byte[] state = q.frame.state(1);
        late.onReceipt(q.client.next, false);
        check(q.posts == 1 && q.reason == PurpleSyncAutoCore.Reason.None && Arrays.equals(state, q.frame.state(1)),
            "timed-out callback cannot overwrite later verified reconciliation");
        q.frame.unchangedA();
        q.engine.close();
    }
    static void unsafeImpact(File parent) throws Exception {
        for (String kind :
            new String[] {"unknown-loss", "defined-list-emptied", "schema", "incomplete-context", "divergence"}) {
            Port p = bound(parent, "unsafe-" + kind);
            byte[] before = kind.equals("unknown-loss") ? bytes("version=2\nfuture_unknown=42\n")
                : kind.equals("defined-list-emptied")   ? bytes("version=2\n[lists.work]\ntitle=\"Own\"\nmembers=[1]\n")
                                                        : OWN;
            p.enable();
            p.saved(before);
            check(p.posts == 1 && p.reason == PurpleSyncAutoCore.Reason.None,
                "unsafe-impact fixture has exact confirmed base");
            byte[] after = kind.equals("unknown-loss") ? bytes("version=2\n")
                : kind.equals("defined-list-emptied")  ? bytes("version=2\n[lists.work]\ntitle=\"Own\"\nmembers=[]\n")
                : kind.equals("schema")
                ? bytes(new String(REMOTE, java.nio.charset.StandardCharsets.UTF_8).replace("version=2", "version=1"))
                : REMOTE;
            p.client.remote(after, p.client.messages.values().iterator().next());
            if (kind.equals("incomplete-context")) {
                write(p.frame.settings(0), "[invalid");
                p.frame.snapshotA();
            }
            if (kind.equals("divergence"))
                write(p.frame.settings(1),
                    new String(before, java.nio.charset.StandardCharsets.UTF_8) + "# divergent local\n");
            byte[] exact = Files.readAllBytes(p.frame.settings(1).toPath());
            p.clock += 900;
            p.engine.event(PurpleSyncAutoCore.Event.Foreground);
            check(p.reason != PurpleSyncAutoCore.Reason.None && p.posts == 1 && p.edits == 0
                    && Arrays.equals(exact, Files.readAllBytes(p.frame.settings(1).toPath())),
                "unsafe automatic update held without write: " + kind);
            check(p.env.history().list().isEmpty(), "held automatic impact creates no false History");
            p.frame.unchangedA();
            p.engine.close();
        }
    }
    static void casAndConcurrent(File parent) throws Exception {
        Port p = bound(parent, "cas");
        p.enable();
        File auto = new File(p.frame.sync(1), "auto.json");
        byte[] expected = Files.readAllBytes(auto.toPath());
        JSONObject changed = p.disk();
        changed.put("concurrent-editor", true);
        byte[] concurrent = bytes(changed.toString());
        try (PurpleAccountSyncStore store = p.env.store()) {
            check(store.open(true, 1, device(1)).status == PurpleAccountSyncStore.Status.Ready, "CAS store opens");
            boolean ok = store.compareAuto(expected, expected, () -> {
                try {
                    write(auto, concurrent);
                    return true;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            check(!ok && Arrays.equals(concurrent, Files.readAllBytes(auto.toPath())),
                "lost auto-record CAS preserves concurrent bytes and authorizes no action");
        }
        p.frame.unchangedA();
        p.engine.close();
        Port b = bound(parent, "concurrent-accounts");
        Port a = new Port(b.frame, 0);
        a.holdCheck = true;
        a.enable();
        byte[] aState = b.frame.state(0), aRecord = Files.readAllBytes(new File(b.frame.sync(0), "auto.json").toPath());
        b.enable();
        b.engine.event(PurpleSyncAutoCore.Event.LocalSaved, OWN);
        check(b.posts == 1 && b.reason == PurpleSyncAutoCore.Reason.None,
            "B progresses while A owns its distinct operation lease");
        check(Arrays.equals(aState, b.frame.state(0))
                && Arrays.equals(aRecord, Files.readAllBytes(new File(b.frame.sync(0), "auto.json").toPath())),
            "B leaves A active record and state exact");
        byte[] bState = b.frame.state(1);
        a.finishCheck();
        check(Arrays.equals(bState, b.frame.state(1)), "A callback cannot mutate B state");
        a.engine.close();
        b.engine.close();
    }
    static void exactAbsenceDecoder(File parent) throws Exception {
        Port p = bound(parent, "exact-absence-decoder");
        PurpleSyncClient client = PurpleSyncTelegramClient.create(1, 202);
        if(client instanceof PurpleSyncChatRecordClient){java.lang.reflect.Field nativeClient=client.getClass().getDeclaredField("client");nativeClient.setAccessible(true);client=(PurpleSyncClient)nativeClient.get(client);}
        java.lang.reflect.Method lookup = client.getClass().getDeclaredMethod(
            "lookup", org.telegram.tgnet.TLObject.class, org.telegram.tgnet.TLRPC.TL_error.class, int.class);
        lookup.setAccessible(true);
        org.telegram.tgnet.TLRPC.TL_messages_messages messages = new org.telegram.tgnet.TLRPC.TL_messages_messages();
        check(((PurpleSyncClient.Lookup) lookup.invoke(client, messages, null, 17)).status
                == PurpleSyncClient.LookupStatus.Failed,
            "zero-row exact response remains Unknown under D8");
        org.telegram.tgnet.TLRPC.TL_messageEmpty missing = new org.telegram.tgnet.TLRPC.TL_messageEmpty();
        missing.id = 17;
        messages.messages.add(missing);
        check(((PurpleSyncClient.Lookup) lookup.invoke(client, messages, null, 17)).status
                == PurpleSyncClient.LookupStatus.Failed,
            "peerless record exact remains Unknown under D8");
        missing.peer_id = new org.telegram.tgnet.TLRPC.TL_peerUser();
        missing.peer_id.user_id = 202;
        missing.flags = 1;
        check(((PurpleSyncClient.Lookup) lookup.invoke(client, messages, null, 17)).status
                == PurpleSyncClient.LookupStatus.Missing,
            "matching explicit scoped messageEmpty proves absence");
        missing.id = 18;
        check(((PurpleSyncClient.Lookup) lookup.invoke(client, messages, null, 17)).status
                == PurpleSyncClient.LookupStatus.Failed,
            "wrong-id messageEmpty never proves absence");
        messages.messages.add(new org.telegram.tgnet.TLRPC.TL_messageEmpty());
        check(((PurpleSyncClient.Lookup) lookup.invoke(client, messages, null, 17)).status
                == PurpleSyncClient.LookupStatus.Failed,
            "unexpected multi-message response never proves exact absence");
        check(((PurpleSyncClient.Lookup) lookup.invoke(
                   client, new org.telegram.tgnet.TLRPC.TL_messages_messagesNotModified(), null, 17))
                    .status
                == PurpleSyncClient.LookupStatus.Failed,
            "not-modified response is Unknown, not absent");
        p.frame.unchangedA();
        p.engine.close();
    }
    static void archivedScope(File parent) throws Exception {
        Port p = bound(parent, "archived-scope");
        p.enable();
        p.client.holdSend = true;
        p.saved(OWN);
        p.engine.close();
        byte[] automatic = Files.readAllBytes(new File(p.frame.sync(1), "auto.json").toPath());
        write(new File(p.frame.sync(1), "state.json"), "invalid old state");
        PurpleSyncRecoveryModel.Offer archive = PurpleSyncRecoveryCases.offer(
            p.frame, 1, p.frame.clouds[1].inventory(), PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(archive != null && archive.plan.offerStartAgain && archive.model.startAgain(archive, false),
            "explicit manual Start again archives old pending scope");
        check(Arrays.equals(automatic, Files.readAllBytes(new File(p.frame.sync(1), "auto.json").toPath())),
            "manual archive preserves automatic configuration and checkpoint");
        PurpleSyncRunner runner = p.env.runner();
        PurpleSyncRunner.ApplyOutcome joined = apply(runner, fresh(runner), null);
        check(joined.result.joined, "explicit manual Join creates new store identity");
        runner.close();
        byte[] next = p.frame.state(1);
        p.restart();
        p.engine.event(PurpleSyncAutoCore.Event.Foreground);
        check(p.posts == 1 && p.reason == PurpleSyncAutoCore.Reason.AccountUnbound
                && Arrays.equals(next, p.frame.state(1)),
            "old automatic checkpoint cannot publish into the fresh manual binding");
        p.frame.unchangedA();
        p.engine.close();
    }
    static void queuedOptIn(File parent) throws Exception {
        Port p = bound(parent, "queued-opt-in");
        p.draining = true;
        int[] intent = {1};
        p.engine.enabled(true, () -> intent[0] == 1);
        intent[0] = 2;
        p.engine.suspend();
        p.engine.enabled(false, () -> intent[0] == 2);
        p.draining = false;
        p.queue(() -> {});
        check(!p.enabled && !p.disk().getBoolean("enabled") && p.checks == 0 && p.posts == 0,
            "queued Enable superseded by Disable never admits a check or upload");
        p.frame.unchangedA();
        p.engine.close();
    }
    static void queuedManualOptIn(File parent) throws Exception {
        Port p = bound(parent, "manual-queued-opt-in");
        Object manual = new Object();
        String lease = p.env.operationKey(p.account.account, p.account.userId);
        PurpleSyncOperations.Capability manualToken=PurpleSyncOperations.acquire(lease,manual,()->true);
        check(manualToken!=null, "manual lease acquired before UI opt-in");
        int[] intent = {1};
        p.engine.enabled(true, () -> intent[0] == 1);
        p.draining = true;
        intent[0] = 2;
        PurpleSyncOperations.end(lease,manual,manualToken);
        p.engine.suspend();
        p.engine.enabled(false, () -> intent[0] == 2);
        p.draining = false;
        p.queue(() -> {});
        check(!p.enabled && !p.disk().getBoolean("enabled") && p.checks == 0 && p.posts == 0,
            "manual lease waiter retains UI intent so stale Enable cannot follow Disable");
        p.frame.unchangedA();
        p.engine.close();
    }
    static void replacedParents(File parent) throws Exception {
        for (boolean whole : new boolean[] {false, true}) {
            Port p = bound(parent, "replaced-parent-" + whole);
            initialPublish(p);
            p.clock += 900;
            p.holdCheck = true;
            p.engine.event(PurpleSyncAutoCore.Event.Foreground);
            byte[] state = p.frame.state(1), auto = Files.readAllBytes(new File(p.frame.sync(1), "auto.json").toPath()),
                   text = Files.readAllBytes(p.frame.settings(1).toPath());
            File moved = whole ? p.frame.settings(1).getParentFile() : p.frame.sync(1);
            File old = new File(moved.getPath() + "-previous");
            Files.move(moved.toPath(), old.toPath());
            directory(p.frame.sync(1));
            write(new File(p.frame.sync(1), "state.json"), state);
            write(new File(p.frame.sync(1), "auto.json"), auto);
            if (whole)
                write(p.frame.settings(1), text);
            java.util.List<String> names = Arrays.asList(p.frame.sync(1).list());
            p.finishCheck();
            check(Arrays.equals(state, p.frame.state(1))
                    && Arrays.equals(auto, Files.readAllBytes(new File(p.frame.sync(1), "auto.json").toPath())),
                "stale completion does not mutate replacement store");
            check(new java.util.HashSet<>(names).equals(new java.util.HashSet<>(Arrays.asList(p.frame.sync(1).list()))),
                "stale completion never creates lock or pending in replacement directory");
            p.frame.unchangedA();
            p.engine.close();
        }
    }
    static void transientWrite(File parent)throws Exception {
        for(String failure:new String[]{"FLOOD_WAIT_90","SERVER_503","TRANSPORT","DELAYED_CALLBACK"}){
            Port p=bound(parent,"transient-write-"+failure);p.enable();p.client.holdSend=true;p.saved(OWN);
            byte[] checkpoint=Files.readAllBytes(new File(p.frame.sync(1),"auto.json").toPath());
            byte[] staged=Files.readAllBytes(new File(p.frame.sync(1),"pending/config.json").toPath());
            p.transientAt=p.clock*1000+90000;if(failure.equals("DELAYED_CALLBACK"))p.clock+=91;p.client.finishSend(true);
            check(p.retry!=null&&p.delay==(failure.equals("DELAYED_CALLBACK")?1:90000)&&p.posts==1,"transient write reschedules existing reconciliation "+failure);
            check(Arrays.equals(checkpoint,Files.readAllBytes(new File(p.frame.sync(1),"auto.json").toPath()))&&Arrays.equals(staged,Files.readAllBytes(new File(p.frame.sync(1),"pending/config.json").toPath())),"transient write leaves journal and effect checkpoint exact");
            if(!failure.equals("DELAYED_CALLBACK")){p.engine.event(PurpleSyncAutoCore.Event.Foreground);check(p.posts==1&&p.delay==90000,"foreground cannot bypass write cooldown");}
            p.clock+=90;p.fireRetry();check(p.posts==1&&p.reason==PurpleSyncAutoCore.Reason.None,"fresh inventory confirms ambiguous write without repost");
            check(p.disk().getInt("retries")==0&&!p.disk().getBoolean("runStopped"),"transient write does not exhaust permanent retry budget");
            p.frame.unchangedA();p.engine.close();
        }
    }
    public static int run(File parent) throws Exception {
        int before = PurpleModernSyncCases.checks;
        PurpleSyncManagedReadCases.run();
        transientWrite(parent);
        defaults(parent);
        publishAndEdit(parent);
        retries(parent);
        safeApplyAndHolds(parent);
        uncertainAndRestart(parent);
        retirement(parent);
        isolationAndCoalescing(parent);
        codecsAndCrashFiles(parent);
        staleTimersAndTimeouts(parent);
        unsafeImpact(parent);
        casAndConcurrent(parent);
        exactAbsenceDecoder(parent);
        archivedScope(parent);
        queuedOptIn(parent);
        queuedManualOptIn(parent);
        replacedParents(parent);
        PurpleSyncAutoReviewCases.run(parent);
        return PurpleModernSyncCases.checks - before;
    }
}
