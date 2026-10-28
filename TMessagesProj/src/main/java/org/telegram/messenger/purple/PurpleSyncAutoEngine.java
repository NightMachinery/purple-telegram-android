package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleSyncAutoEngine {
    interface Reply<T> {
        void done(T value);
    }
    interface Port {
        boolean current();
        boolean enabledHint();
        void enabledHint(boolean enabled);
        boolean ownerAnswered();
        long now();
        void queue(Runnable task);
        void retry(Runnable task, long delayMillis);
        void cancelRetry();
        void status(PurpleSyncAutoCore.Record record, PurpleSyncAutoCore.Reason reason);
        void check(String ticket, int[] exactIds, BooleanSupplier current, Reply<Observed> reply);
        void send(boolean edit, int messageId, byte[] bytes, byte[] previous, BooleanSupplier current,
            Reply<PurpleSyncAutoCore.Outcome> reply);
        void retire(int messageId, byte[] exact, BooleanSupplier current, Reply<PurpleSyncAutoCore.Outcome> reply);
        default long transientRetryAt(){return 0;}
        default long takeTransientRetryAt(){return 0;}
        default boolean editSupported() {
            return true;
        }
    }
    static final class Observed {
        final PurpleSyncInventory inventory;
        final JSONArray reads;
        final long readAt;
        final boolean queued;
        Observed(PurpleSyncInventory inventory, JSONArray reads, long readAt, boolean queued) {
            this.inventory = inventory;
            this.reads = reads;
            this.readAt = readAt;
            this.queued = queued;
        }
    }
    static final class Snapshot {
        final PurpleSyncAutoCore.Facts facts;
        final PurpleSyncSettingsFile.Contents local;
        final byte[] state, staged;
        String authority;
        Snapshot(PurpleSyncAutoCore.Facts facts, PurpleSyncSettingsFile.Contents local, byte[] state, byte[] staged) {
            this.facts = facts;
            this.local = local;
            this.state = state;
            this.staged = staged;
        }
        boolean effects() {
            return local.mutation != null && local.mutation.effectCurrent();
        }
    }
    final PurpleSyncRunner.Session account;
    final String key;
    volatile String session = UUID.randomUUID().toString();
    final Port port;
    private volatile boolean owned;
    private volatile PurpleSyncOperations.Capability acquisition;
    private volatile String running = "";
    private volatile boolean closed;
    private boolean observedQueue;
    private PurpleSyncAutoCore.Event waitingToggle, waitingTrigger;
    private BooleanSupplier waitingToggleAdmission = () -> true;
    private byte[] waitingSave;
    private String waitingRetry;
    private String savedFingerprint = "";
    PurpleSyncAutoEngine(PurpleSyncRunner.Session account, String key, Port port) {
        this.account = account;
        this.key = key;
        this.port = port;
    }
    void event(PurpleSyncAutoCore.Event event) {
        event(event, null);
    }
    void event(PurpleSyncAutoCore.Event event, byte[] saved) {
        if (event == PurpleSyncAutoCore.Event.Disable) {
            port.enabledHint(false);
            port.cancelRetry();
        }
        port.queue(() -> transition(event, saved, null, PurpleSyncAutoCore.Outcome.Inventory, null, null));
    }
    void enabled(boolean value, BooleanSupplier admitted) {
        if (!value) {
            port.enabledHint(false);
            port.cancelRetry();
        }
        port.queue(() -> {
            if (admitted.getAsBoolean())
                transition(value ? PurpleSyncAutoCore.Event.Enable : PurpleSyncAutoCore.Event.Disable, null, null,
                    PurpleSyncAutoCore.Outcome.Inventory, null, null, null, admitted);
        });
    }
    void suspend() {
        port.cancelRetry();
        end();
    }
    private String operationKey() {
        return account.env.operationKey(account.account, account.userId);
    }
    private boolean current() {
        return !closed && port.current() && account.available();
    }
    BooleanSupplier borrowedAuthority() {
        String opening=session;PurpleSyncOperations.Capability token=acquisition;
        return ()->owned&&token!=null&&token==acquisition&&token.current()&&current()&&opening.equals(session);
    }
    private void end() {
        PurpleSyncOperations.Capability token=owned?acquisition:null;
        owned=false;acquisition=null;if(token!=null)token.revoke();
        try {session=UUID.randomUUID().toString();running="";}
        finally {if(token!=null)PurpleSyncOperations.end(token.key,this,token);}
    }
    void close() {
        closed = true;
        port.cancelRetry();
        end();
    }
    private void idle() {
        PurpleSyncAutoCore.Event toggle = waitingToggle, trigger = waitingTrigger;
        byte[] saved = waitingSave;
        BooleanSupplier toggleAdmission = waitingToggleAdmission;
        String expected = waitingRetry;
        waitingToggle = null;
        waitingTrigger = null;
        waitingSave = null;
        waitingRetry = null;
        if (toggle != null)
            enabled(toggle == PurpleSyncAutoCore.Event.Enable, toggleAdmission);
        if (saved != null)
            event(PurpleSyncAutoCore.Event.LocalSaved, saved);
        if (trigger != null)
            port.queue(
                () -> transition(trigger, null, null, PurpleSyncAutoCore.Outcome.Inventory, null, null, expected));
    }
    private static boolean sameDirectory(PurpleAccountSyncStore store, String expected) {
        try {
            return expected.equals(store.directoryIdentity());
        } catch (Exception e) {
            return false;
        }
    }
    private Snapshot snapshot(PurpleAccountSyncStore store) throws Exception {
        PurpleSyncSettingsFile.Contents local = account.env.readSettings();
        byte[] state = store.stateBytes();
        String openingSession = session;
        String directory = store.directoryIdentity();
        if (local.mutation != null)
            local.mutation.require(()
                                       -> current() && port.enabledHint() && openingSession.equals(session)
                    && sameDirectory(store, directory));
        PurpleSyncApply.Pending pending = PurpleSyncApply.readPending(store);
        if (!pending.ok())
            throw new IllegalStateException("automatic pending state unavailable");
        PurpleSyncAutoCore.Facts facts = new PurpleSyncAutoCore.Facts(key, session);
        PurpleAccountBinding.Binding binding = PurpleAccountBinding.read(account.account);
        JSONObject json = state != null ? new JSONObject(new String(state, StandardCharsets.UTF_8)) : new JSONObject();
        boolean bound = true;
        if (account.env.requiresRecovery()) {
            PurpleSyncRecoveryModel.Offer recovery = account.env.recovery(null, local);
            bound = recovery != null && recovery.plan.status == PurpleSyncRecoveryCore.Status.Bound
                && recovery.model.retained();
        }
        facts.json.put("signedIn", current())
            .put("ownerAnswered", port.ownerAnswered())
            .put("accountLock", owned)
            .put("inFlightSession", running.isEmpty() ? "" : session)
            .put("device", account.env.device())
            .put("bindingToken", binding.current() ? binding.token : "")
            .put("state", PurpleSyncAutoCore.encode(bound ? state : null))
            .put("staged", PurpleSyncAutoCore.encode(pending.staged))
            .put("chatResolved", json.optInt("version") == 1)
            .put("chatWritable", current())
            .put("localStatus", local.status.ordinal())
            .put("local", PurpleSyncAutoCore.encode(local.bytes))
            .put("lastGood", local.usingLastGood)
            .put("saveDebounceFinished", true)
            .put("savedFingerprint", savedFingerprint)
            .put("editSupported", port.editSupported())
            .put("sendQueued", observedQueue || account.env.sendQueued());
        facts.impact = local.mutation != null ? local.mutation.impact : null;
        facts.json.put("impactComplete", facts.impact != null && local.current());
        Snapshot result = new Snapshot(facts, local, state, pending.staged);
        result.authority = authority(facts.impact);
        return result;
    }
    private String authority(PurpleConfigImpact.Input input) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        java.io.OutputStream discard = new java.io.OutputStream() {
            public void write(int value) {}
            public void write(byte[] value, int offset, int length) {}
        };
        java.io.DataOutputStream out = new java.io.DataOutputStream(new java.security.DigestOutputStream(discard, digest));
        out.writeUTF(key);
        for (String path : new String[]{"local.toml", "accounts/root.toml"}) {
            byte[] value = PurpleSyncReader.readAtMost(PurpleAccountFiles.file(path), 4 * 1024 * 1024 + 1);
            out.writeInt(value == null ? -1 : value.length);
            if (value != null) out.write(value);
        }
        out.writeBoolean(input != null);
        if (input != null) {
            out.writeBoolean(input.appWide); out.writeInt(input.mode);
            byte[] global = !input.appWide ? input.global : null;
            out.writeInt(global == null ? -1 : global.length); if (global != null) out.write(global);
            for (int i = 0; i < input.accounts.length; ++i) {
                if (key.equals(input.accounts[i])) continue;
                out.writeUTF(input.accounts[i]); out.writeInt(input.modes[i]);
                byte[] file = input.files[i]; out.writeInt(file == null ? -1 : file.length);
                if (file != null) out.write(file);
            }
        }
        out.close();
        return PurpleSyncAutoCore.encode(digest.digest());
    }
    private static boolean sameStore(PurpleSyncAutoCore.Record record, byte[] state) throws Exception {
        byte[] expected = PurpleSyncAutoCore.decode(record.value.optString("expectedState"));
        if (state == null)
            return true;
        byte[] pending = PurpleSyncAutoCore.decode(record.value.optString("record"));
        if (pending.length > 0) {
            PurpleAccountSyncCore.Result body = PurpleAccountSyncCore.inspectConfigRecord(pending),
                                         local = PurpleAccountSyncCore.inspectState(state);
            if (!body.isValid() || !local.isValid() || !body.space.equals(local.space)
                || !body.install.equals(local.install) || !body.device.equals(local.device))
                return false;
        }
        if (expected.length == 0)
            return true;
        JSONObject before = new JSONObject(new String(expected, StandardCharsets.UTF_8)),
                   after = new JSONObject(new String(state, StandardCharsets.UTF_8));
        for (String name : new String[] {"version", "install", "created_device", "space", "binding_token", "chat"})
            if (!java.util.Objects.equals(before.opt(name), after.opt(name)))
                return false;
        return true;
    }
    private static String retryKey(PurpleSyncAutoCore.Record record) {
        return record.value.optString("run") + "/" + record.ticket() + "/" + record.retryAt();
    }
    private void transition(PurpleSyncAutoCore.Event event, byte[] saved, String completed,
        PurpleSyncAutoCore.Outcome outcome, Observed observed, Snapshot opening) {
        transition(event, saved, completed, outcome, observed, opening, null);
    }
    private void transition(PurpleSyncAutoCore.Event event, byte[] saved, String completed,
        PurpleSyncAutoCore.Outcome outcome, Observed observed, Snapshot opening, String expectedRetry) {
        transition(event, saved, completed, outcome, observed, opening, expectedRetry, () -> true);
    }
    private void transition(PurpleSyncAutoCore.Event event, byte[] saved, String completed,
        PurpleSyncAutoCore.Outcome outcome, Observed observed, Snapshot opening, String expectedRetry,
        BooleanSupplier toggleAdmission) {
        if (!toggleAdmission.getAsBoolean())
            return;
        if (!current()) {
            end();
            return;
        }
        if (opening != null && !opening.effects()) {
            port.status(null, PurpleSyncAutoCore.Reason.StateChanged);
            end();
            return;
        }
        if (event != PurpleSyncAutoCore.Event.Enable && event != PurpleSyncAutoCore.Event.Disable
            && !port.enabledHint()) {
            end();
            return;
        }
        long transientAt=port.transientRetryAt();
        if(event!=PurpleSyncAutoCore.Event.Disable&&event!=PurpleSyncAutoCore.Event.Completed&&transientAt>port.now()*1000){
            if(event==PurpleSyncAutoCore.Event.LocalSaved)waitingSave=saved!=null?saved.clone():null;
            if(event==PurpleSyncAutoCore.Event.Enable){waitingToggle=event;waitingToggleAdmission=toggleAdmission;}
            end();
            port.retry(()->{idle();event(PurpleSyncAutoCore.Event.SessionReady);},Math.max(1,transientAt-port.now()*1000));
            return;
        }
        if (!owned) {
            String openingSession=session;PurpleSyncOperations.Capability previous=acquisition;
            PurpleSyncOperations.Capability token=PurpleSyncOperations.acquire(operationKey(),this,this::current,()->port.queue(this::suspend));
            boolean installed=false;
            try {
            if(closed||owned||!openingSession.equals(session)||acquisition!=previous||!current())return;
            if (token==null) {
                if (event == PurpleSyncAutoCore.Event.Enable || event == PurpleSyncAutoCore.Event.Disable) {
                    waitingToggle = event;
                    waitingToggleAdmission = toggleAdmission;
                    if (event == PurpleSyncAutoCore.Event.Disable)
                        waitingSave = null;
                } else if (event == PurpleSyncAutoCore.Event.LocalSaved)
                    waitingSave = saved != null ? saved.clone() : null;
                else {
                    waitingTrigger = event;
                    waitingRetry = expectedRetry;
                }
                PurpleSyncOperations.whenIdle(operationKey(), this, () -> port.queue(this::idle));
                return;
            }
            acquisition=token;owned=true;installed=true;
            } finally {if(token!=null&&!installed){token.revoke();PurpleSyncOperations.end(token.key,this,token);}}
        }
        try {
            if (!account.env.hasState()) {
                port.status(null, PurpleSyncAutoCore.Reason.AccountUnbound);
                end();
                return;
            }
            PurpleSyncSettingsFile.Contents paths = account.env.readSettings();
            if (paths.mutation == null || !paths.current()) {
                port.status(null, PurpleSyncAutoCore.Reason.InvalidSettings);
                end();
                return;
            }
            PurpleSyncAutoCore.Plan plan;
            Snapshot facts;
            try (PurpleAccountSyncStore store = account.env.store()) {
                if (!paths.current()) {
                    port.status(null, PurpleSyncAutoCore.Reason.StateChanged);
                    end();
                    return;
                }
                if (store.open(true, account.account, account.env.device()).status
                    != PurpleAccountSyncStore.Status.Ready) {
                    port.status(null, PurpleSyncAutoCore.Reason.StoreMismatch);
                    end();
                    return;
                }
                byte[] expected = store.autoBytes();
                if (expected == null && event != PurpleSyncAutoCore.Event.Enable
                    && event != PurpleSyncAutoCore.Event.Disable) {
                    port.enabledHint(false);
                    port.status(null, PurpleSyncAutoCore.Reason.None);
                    end();
                    return;
                }
                PurpleSyncAutoCore.Record record = PurpleSyncAutoCore.decodeRecord(expected, key);
                if (record == null) {
                    port.status(null, PurpleSyncAutoCore.Reason.InvalidRecord);
                    end();
                    return;
                }
                if (expectedRetry != null && !expectedRetry.equals(retryKey(record))) {
                    if (running.isEmpty())
                        end();
                    return;
                }
                facts = snapshot(store);
                boolean pendingAuthority = !record.value.optString("record").isEmpty()
                    || !record.value.optString("expectedState").isEmpty();
                if (event != PurpleSyncAutoCore.Event.Disable && pendingAuthority
                    && !facts.authority.equals(record.value.optString("androidAuthority"))) {
                    port.status(null, PurpleSyncAutoCore.Reason.StateChanged);
                    end();
                    return;
                }
                if (!sameStore(record, facts.state))
                    facts.facts.json.put("state", "");
                if (event == PurpleSyncAutoCore.Event.LocalSaved) {
                    if (saved == null || facts.local.status != PurpleSyncSettingsFile.Status.Present
                        || !Arrays.equals(saved, facts.local.bytes)) {
                        if (running.isEmpty())
                            end();
                        return;
                    }
                    savedFingerprint = PurpleSyncCore.settingsFingerprint(saved);
                    facts.facts.json.put("savedFingerprint", savedFingerprint);
                }
                if (event == PurpleSyncAutoCore.Event.Completed
                    && (!record.value.optBoolean("active") || !record.ticket().equals(completed)
                        || !session.equals(record.value.optString("session")))) {
                    end();
                    return;
                }
                if (observed != null)
                    observedQueue = observed.queued;
                facts.facts.json.put("sendQueued", observedQueue || account.env.sendQueued());
                PurpleSyncAutoCore.Inventory inventory = observed != null
                    ? new PurpleSyncAutoCore.Inventory(
                          observed.inventory, session, completed, observed.readAt, observed.reads)
                    : null;
                if (opening != null && !opening.effects()) {
                    inventory = null;
                    outcome = PurpleSyncAutoCore.Outcome.OutcomeUnknown;
                }
                plan = PurpleSyncAutoCore.plan(record, facts.facts, event, port.now(), completed, outcome, inventory);
                byte[] nextRecord = plan.record.bytes;
                if (!pendingAuthority && (!plan.record.value.optString("record").isEmpty()
                        || !plan.record.value.optString("expectedState").isEmpty())) {
                    JSONObject scoped = new JSONObject(plan.record.value.toString()).put("androidAuthority", facts.authority);
                    PurpleSyncAutoCore.Record encoded = PurpleSyncAutoCore.decodeRecord(
                        scoped.toString().getBytes(StandardCharsets.UTF_8), key);
                    if (encoded == null) throw new IllegalStateException("automatic authority checkpoint refused");
                    nextRecord = encoded.bytes;
                }
                if (!store.compareAuto(expected, nextRecord, this::current)) {
                    port.status(null, PurpleSyncAutoCore.Reason.StateChanged);
                    end();
                    return;
                }
            }
            if (event == PurpleSyncAutoCore.Event.Enable) {
                port.enabledHint(plan.record.enabled());
                if (plan.record.enabled() && !port.enabledHint()) {
                    port.status(plan.record, PurpleSyncAutoCore.Reason.StateChanged);
                    end();
                    return;
                }
            }
            port.status(plan.record, plan.reason);
            if (plan.record.retryAt() > 0) {
                long seconds = Math.max(0, plan.record.retryAt() - port.now());
                String expectedRetryKey = retryKey(plan.record);
                if (seconds <= Long.MAX_VALUE / 1000)
                    port.retry(
                        ()
                            -> port.queue(()
                                              -> transition(PurpleSyncAutoCore.Event.Retry, null, null,
                                                  PurpleSyncAutoCore.Outcome.Inventory, null, null, expectedRetryKey)),
                        Math.max(1, seconds * 1000));
            } else
                port.cancelRetry();
            if (plan.action == PurpleSyncAutoCore.Action.NoOp) {
                if (!plan.record.value.optBoolean("active"))
                    end();
                return;
            }
            if (plan.action == PurpleSyncAutoCore.Action.Hold || plan.action == PurpleSyncAutoCore.Action.RetryAt) {
                end();
                return;
            }
            running = plan.ticket;
            execute(plan, facts);
        } catch (Exception | LinkageError e) {
            org.telegram.messenger.FileLog.e(e);
            port.status(null, PurpleSyncAutoCore.Reason.StoreMismatch);
            end();
        }
    }
    private boolean liveEffects(Snapshot facts) {
        return current() && port.enabledHint() && facts.effects();
    }
    private boolean permitted(PurpleSyncAutoCore.Plan plan, Snapshot facts) {
        if (!liveEffects(facts))
            return false;
        try (PurpleAccountSyncStore store = account.env.store()) {
            if (store.open(true, account.account, account.env.device()).status != PurpleAccountSyncStore.Status.Ready
                || !Arrays.equals(facts.state, store.stateBytes()))
                return false;
            PurpleSyncAutoCore.Record record = PurpleSyncAutoCore.decodeRecord(store.autoBytes(), key);
            return record != null && record.enabled() && record.value.optBoolean("active")
                && record.ticket().equals(plan.ticket) && session.equals(record.value.optString("session"))
                && record.value.optInt("pending") == plan.action.ordinal()
                && record.value.optString("expectedState").equals(plan.record.value.optString("expectedState"))
                && record.value.optString("record").equals(plan.record.value.optString("record"));
        } catch (Exception e) {
            return false;
        }
    }
    private void complete(
        PurpleSyncAutoCore.Plan plan, Snapshot facts, PurpleSyncAutoCore.Outcome outcome, Observed observed) {
        port.queue(() -> {
            if (!current() || !running.equals(plan.ticket)) {
                if (!current())
                    end();
                return;
            }
            long retryAt=outcome==PurpleSyncAutoCore.Outcome.OutcomeUnknown?port.takeTransientRetryAt():0;
            if(retryAt>0){
                end();
                port.retry(()->{idle();event(PurpleSyncAutoCore.Event.SessionReady);},Math.max(1,retryAt-port.now()*1000));
                return;
            }
            transition(PurpleSyncAutoCore.Event.Completed, null, plan.ticket, outcome, observed, facts);
        });
    }
    private void execute(PurpleSyncAutoCore.Plan plan, Snapshot facts) throws Exception {
        switch (plan.action) {
            case Check:
            case Reconcile:
                if (!plan.commitOnly) {
                    int[] ids = exactIds(facts.state, plan.record.messageId());
                    port.check(plan.ticket, ids,
                        ()
                            -> liveEffects(facts),
                        observed
                        -> complete(plan, facts,
                            observed != null ? PurpleSyncAutoCore.Outcome.Inventory
                                             : PurpleSyncAutoCore.Outcome.NetworkFailure,
                            observed));
                    return;
                }
                mutate(plan, facts, true);
                return;
            case AutoApply:
            case Publish:
                mutate(plan, facts, false);
                return;
            case Post:
            case EditInPlace:
                if (!permitted(plan, facts)) {
                    complete(plan, facts, PurpleSyncAutoCore.Outcome.OutcomeUnknown, null);
                    return;
                }
                port.send(plan.action == PurpleSyncAutoCore.Action.EditInPlace, plan.messageId, plan.bytes,
                    plan.previous, () -> permitted(plan, facts), outcome -> complete(plan, facts, outcome, null));
                return;
            case Retire:
                if (!permitted(plan, facts)) {
                    complete(plan, facts, PurpleSyncAutoCore.Outcome.OutcomeUnknown, null);
                    return;
                }
                port.retire(plan.messageId, plan.bytes, () -> permitted(plan, facts), outcome -> {
                    if (outcome == PurpleSyncAutoCore.Outcome.Deleted)
                        port.queue(() -> {
                            try {
                                mutate(plan, facts, false);
                            } catch (Exception e) {
                                complete(plan, facts, PurpleSyncAutoCore.Outcome.OutcomeUnknown, null);
                            }
                        });
                    else
                        complete(plan, facts, outcome, null);
                });
                return;
            default:
                throw new IllegalStateException("non-executable automatic action");
        }
    }
    private void mutate(PurpleSyncAutoCore.Plan plan, Snapshot facts, boolean clearStage) throws Exception {
        boolean ok = false;
        try (PurpleAccountSyncStore store = account.env.store()) {
            if (!liveEffects(facts)
                || store.open(true, account.account, account.env.device()).status != PurpleAccountSyncStore.Status.Ready
                || !Arrays.equals(facts.state, store.stateBytes())) {
                complete(plan, facts, PurpleSyncAutoCore.Outcome.OutcomeUnknown, null);
                return;
            }
            byte[] checkpoint = store.autoBytes();
            PurpleSyncAutoCore.Record record = PurpleSyncAutoCore.decodeRecord(checkpoint, key);
            if (record == null || !record.enabled() || !record.value.optBoolean("active")
                || !record.ticket().equals(plan.ticket) || !session.equals(record.value.optString("session"))
                || record.value.optInt("pending") != plan.action.ordinal()
                || !record.value.optString("expectedState").equals(plan.record.value.optString("expectedState"))
                || !record.value.optString("record").equals(plan.record.value.optString("record"))) {
                end();
                return;
            }
            if (plan.action == PurpleSyncAutoCore.Action.Publish) {
                if (facts.local.sameAs(account.env.readSettings())
                    && Arrays.equals(plan.bytes, PurpleSyncAutoCore.decode(record.value.getString("record"))))
                    ok = store.stageConfig(plan.bytes, plan.nextState, () -> liveEffects(facts)).status
                        == PurpleAccountSyncStore.Status.Ready;
            } else {
                if (plan.action == PurpleSyncAutoCore.Action.AutoApply) {
                    PurpleAccountSyncStore.Result pinned = store.pinConfigBase(new byte[0][]);
                    if (pinned.status != PurpleAccountSyncStore.Status.Ready) {
                        complete(plan, facts, PurpleSyncAutoCore.Outcome.OutcomeUnknown, null);
                        return;
                    }
                    PurpleSyncApply.Written written =
                        PurpleSyncApply.writeWithHistory(account.env, account, facts.local, plan.bytes,
                            PurpleSyncCore.settingsFingerprint(plan.bytes), PurpleSyncHistory.Reason.BeforeUpdate,
                            "Automatic settings update", "", null, true, "automatic settings update");
                    if (written.status != PurpleSyncCore.ApplyStatus.Applied) {
                        complete(plan, facts, PurpleSyncAutoCore.Outcome.OutcomeUnknown, null);
                        return;
                    }
                    if (written.restorable)
                        PurpleSyncOperations.undo(
                            operationKey(), new PurpleSyncRunner.UndoOffer(written.historyId, null));
                }
                ok = store.commitAutomatic(
                    facts.state, plan.nextState, checkpoint, clearStage, () -> current() && port.enabledHint());
            }
        }
        PurpleSyncAutoCore.Outcome outcome = plan.action == PurpleSyncAutoCore.Action.Publish
            ? PurpleSyncAutoCore.Outcome.Staged
            : plan.action == PurpleSyncAutoCore.Action.AutoApply ? PurpleSyncAutoCore.Outcome.Applied
            : plan.action == PurpleSyncAutoCore.Action.Retire    ? PurpleSyncAutoCore.Outcome.Deleted
                                                                 : PurpleSyncAutoCore.Outcome.Committed;
        complete(plan, plan.action == PurpleSyncAutoCore.Action.AutoApply ? null : facts,
            ok ? outcome : PurpleSyncAutoCore.Outcome.OutcomeUnknown, null);
    }
    static int[] exactIds(byte[] state, int extra) throws Exception {
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
        if (extra > 0)
            ids.add(extra);
        if (state != null) {
            JSONArray messages = new JSONObject(new String(state, StandardCharsets.UTF_8)).optJSONArray("own_messages");
            if (messages != null)
                for (int i = 0; i < messages.length(); ++i) {
                    int id = messages.getJSONObject(i).optInt("message_id");
                    if (id > 0)
                        ids.add(id);
                }
        }
        if (ids.size() > 10000)
            throw new IllegalStateException("automatic ledger exceeds bound");
        int[] out = new int[ids.size()];
        int i = 0;
        for (int id : ids) out[i++] = id;
        return out;
    }
}
