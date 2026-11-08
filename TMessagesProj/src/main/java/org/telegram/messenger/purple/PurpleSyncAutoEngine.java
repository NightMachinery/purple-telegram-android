package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
        /**
         * First use for an account with no sync state ({@link PurpleSyncFirstUse}): true when the port runs it for
         * {@code event}, false to hold AccountUnbound as before.
         */
        default boolean firstUse(PurpleSyncAutoCore.Event event) { return false; }
        /**
         * Automatic sync holds for a reason of the client's own: first use asked the one-time question and waits
         * for the answer, or an automatic pass failed with an exception ({@link #failed}) and the user has not
         * resumed. Core holds it as a chat problem.
         */
        default boolean held() { return false; }
        /**
         * An automatic pass threw. The port logs a diag reason code and pauses automatic sync with the problem
         * shown once, so no failure dies silently.
         */
        default void failed(Throwable error) { }
        /** A pass failed in a way that can heal (diag code {@code code}): it runs again at {@code notBefore}, no pause. */
        default void retrying(String code, long notBefore) { }
        /**
         * The client's backoff of automatic passes that end without the write they planned (an unknown outcome or a
         * network failure), kept across restarts: how many in a row, and the unix time before which no pass runs.
         * Core restarts its own 30/120/600 retry with each run, so a post that never goes out was retried every 30
         * seconds, reading the chat each time.
         */
        default long[] backoff() { return new long[] { 0, 0 }; }
        default void backoff(long failures, long notBefore) { }
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
    /**
     * Whether automatic sync asks the account recovery scan (PurpleSyncRecoveryModel) before it trusts the stored
     * state: only for Saved Messages, or while no chat is bound, as the Sync screen does (PurpleSyncMagicManual).
     * In a bound magic chat the scan refused the sync folder for entries that say nothing about the binding (on the
     * R3/R4 builds chat.json behind Android's /data/user/0 link; still today leftovers of older builds such as
     * sync-chat-setup.json or carry files), and automatic sync then passed the moved state to core as no state:
     * AccountUnbound, so the moved state never made its publish into the magic chat. Core's gate still checks the
     * state's binding token against the account's.
     */
    /** Seconds to wait after {@code failures} passes in a row without their write: 30, 60, 120 ... up to an hour. */
    static long backoffSeconds(long failures) {
        return failures <= 0 ? 0 : Math.min(3600, 30L << Math.min(7, failures - 1));
    }

    private static String word(Enum<?> value) {
        return value == null ? "none" : value.name().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    /** One diag line per automatic pass: the action core planned, the outcome it came from and its reason code. */
    private void pass(PurpleSyncAutoCore.Plan plan, PurpleSyncAutoCore.Outcome outcome, PurpleSyncAutoCore.Event event) {
        PurpleDiagLog.event("sync", "auto_pass", PurpleDiagLogStore.slot(account.account),
                PurpleDiagLogStore.code("action", word(plan.action)),
                PurpleDiagLogStore.code("outcome", event == PurpleSyncAutoCore.Event.Completed ? word(outcome) : "none"),
                PurpleDiagLogStore.reason(word(plan.reason)));
    }

    static boolean recoveryScanApplies(PurpleSyncChatCore.Peer bound) {
        return bound == null || PurpleSyncChatCore.Peer.self().equals(bound);
    }

    /** The diag reason code of an exception of an automatic pass: its message when that is a known code, else its kind. */
    static String failureCode(Throwable error) {
        String message = error != null ? error.getMessage() : null;
        if ("AutomaticChatMismatch".equals(message)) return "automatic_chat_mismatch";
        if (error instanceof LinkageError) return "native_unavailable";
        if (error instanceof org.json.JSONException) return "json";
        if (error instanceof java.io.IOException) return "io";
        if (error instanceof IllegalArgumentException) return "illegal_argument";
        if (error instanceof IllegalStateException) return "illegal_state";
        return "unexpected";
    }
    /** Only these cannot heal by trying again; every other failure of a pass retries with backoff. */
    static boolean pausing(String code) {
        return "automatic_chat_mismatch".equals(code) || "native_unavailable".equals(code);
    }
    /**
     * An exception of a pass, the engine stopped. WHY: one file race or transient state must not stop fully automatic
     * sync until someone opens the Sync screen; it gets its reason in the log and a retry, also across a restart.
     */
    void failure(Throwable error) {
        String code = failureCode(error);
        if (pausing(code)) {
            port.status(null, PurpleSyncAutoCore.Reason.StoreMismatch);
            port.failed(error);
            return;
        }
        long[] counted = port.backoff();
        long notBefore = port.now() + backoffSeconds(counted[0] + 1);
        port.backoff(counted[0] + 1, notBefore);
        port.retrying(code, notBefore);
        port.retry(() -> { idle(); event(PurpleSyncAutoCore.Event.SessionReady); }, Math.max(1, (notBefore - port.now()) * 1000));
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
    private Observed adoptTried;
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
    private PurpleSyncChatCore.Peer pickupChat;
    private int[] pickupIds;
    private boolean pickupOverflow;
    void chatUpdated(PurpleSyncChatCore.Peer chat, int[] ids, boolean overflow) {
        int[] copy = Arrays.copyOf(ids, Math.min(128, ids.length));
        boolean truncated = overflow || ids.length > copy.length;
        port.queue(() -> {
            PurpleSyncChatCore.Peer bound = PurpleSyncChat.boundChat(account.account);
            if (!current() || pickupChat != null && !pickupChat.equals(bound)) clearPickupBatch();
            if (!current() || !chat.equals(bound)) return;
            if (!chat.equals(pickupChat)) clearPickupBatch();
            pickupChat = chat;
            LinkedHashSet<Integer> merged = new LinkedHashSet<>();
            if (pickupIds != null) for (int id : pickupIds) merged.add(id);
            for (int id : copy) {
                if (id <= 0) continue;
                if (merged.size() < 128) merged.add(id);
                else if (!merged.contains(id)) pickupOverflow = true;
            }
            pickupIds = merged.stream().mapToInt(Integer::intValue).toArray();
            pickupOverflow |= truncated;
            transition(PurpleSyncAutoCore.Event.ChatUpdated, null, null,
                PurpleSyncAutoCore.Outcome.Inventory, null, null);
        });
    }
    private void clearPickupBatch() {
        pickupIds = null;
        pickupOverflow = false;
        if (waitingTrigger == PurpleSyncAutoCore.Event.ChatUpdated) waitingTrigger = null;
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
        clearPickupBatch();
        pickupChat = null;
        port.cancelRetry();
        end();
    }
    private void idle() {
        if (pickupChat != null && !pickupChat.equals(PurpleSyncChat.boundChat(account.account))) clearPickupBatch();
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
        PurpleSyncChatCore.Peer boundChat = PurpleSyncChat.boundChat(account.account);
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
            .put("chat", store.chat().token())
            // WHY: the store moved the state into the bound chat when it opened; a bound chat that still
            // differs means the move was refused, so automatic sync holds instead of using the old chat.
            .put("chatResolved", boundChat == null || boundChat.equals(store.chat()))
            .put("chatProblem", PurpleSyncChat.chatProblem(account.account) || port.held())
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
            clearPickupBatch();
            end();
            return;
        }
        if (event == PurpleSyncAutoCore.Event.Disable) clearPickupBatch();
        if (pickupChat != null && !pickupChat.equals(PurpleSyncChat.boundChat(account.account))) {
            clearPickupBatch();
            if (event == PurpleSyncAutoCore.Event.ChatUpdated) return;
        }
        if (opening != null && !opening.effects()) {
            port.status(null, PurpleSyncAutoCore.Reason.StateChanged);
            end();
            return;
        }
        if (event != PurpleSyncAutoCore.Event.Enable && event != PurpleSyncAutoCore.Event.Disable
            && !port.enabledHint()) {
            clearPickupBatch();
            end();
            return;
        }
        long[] backoff = port.backoff();
        if ((event == PurpleSyncAutoCore.Event.SessionReady || event == PurpleSyncAutoCore.Event.Foreground
                || event == PurpleSyncAutoCore.Event.Unlock || event == PurpleSyncAutoCore.Event.Retry
                || event == PurpleSyncAutoCore.Event.ChatUpdated)
            && backoff[0] > 0 && port.now() < backoff[1]) {
            // A pass that is not a real change waits out the backoff, across restarts too.
            if (event == PurpleSyncAutoCore.Event.ChatUpdated) waitingTrigger = event;
            end();
            port.retry(() -> { idle(); event(PurpleSyncAutoCore.Event.SessionReady); }, Math.max(1, (backoff[1] - port.now()) * 1000));
            return;
        }
        long transientAt=port.transientRetryAt();
        if(event!=PurpleSyncAutoCore.Event.Disable&&event!=PurpleSyncAutoCore.Event.Completed&&transientAt>port.now()*1000){
            if(event==PurpleSyncAutoCore.Event.ChatUpdated)waitingTrigger=event;
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
                    if (waitingTrigger != PurpleSyncAutoCore.Event.ChatUpdated) waitingTrigger = event;
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
                end();
                if (!port.firstUse(event))
                    port.status(null, PurpleSyncAutoCore.Reason.AccountUnbound);
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
            boolean adopting;
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
                if (event == PurpleSyncAutoCore.Event.ChatUpdated && pickupChat != null) {
                    PurpleSyncChatCore.Peer bound = PurpleSyncChat.boundChat(account.account);
                    boolean needed = bound != null && bound.equals(pickupChat)
                        && (pickupOverflow || pickupIds != null && PurpleSyncAutoCore.pickupNative(facts.state, bound.token(), pickupChat.token(), pickupIds, account.userId));
                    pickupIds = null;
                    pickupOverflow = false;
                    if (!needed) {
                        if (running.isEmpty()) end();
                        return;
                    }
                }
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
                          observed.inventory, store.chat(), session, completed, observed.readAt, observed.reads)
                    : null;
                if (opening != null && !opening.effects()) {
                    inventory = null;
                    outcome = PurpleSyncAutoCore.Outcome.OutcomeUnknown;
                }
                plan = PurpleSyncAutoCore.plan(record, facts.facts, event, port.now(), completed, outcome, inventory);
                adopting = inventory != null && observed != adoptTried && plan.action == PurpleSyncAutoCore.Action.Hold
                    && plan.reason == PurpleSyncAutoCore.Reason.NeedsReview && !plan.record.value.optBoolean("retiring");
                if (!adopting) {
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
            }
            if (adopting) {
                adoptTried = observed;
                adoptSameHead(observed.inventory, facts);
                transition(event, saved, completed, outcome, observed, opening, expectedRetry);
                return;
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
            pass(plan, outcome, event);
            if (event == PurpleSyncAutoCore.Event.Completed) {
                long[] counted = port.backoff();
                if (outcome == PurpleSyncAutoCore.Outcome.OutcomeUnknown || outcome == PurpleSyncAutoCore.Outcome.NetworkFailure)
                    port.backoff(counted[0] + 1, port.now() + backoffSeconds(counted[0] + 1));
                else if (outcome == PurpleSyncAutoCore.Outcome.Sent || outcome == PurpleSyncAutoCore.Outcome.Committed
                    || outcome == PurpleSyncAutoCore.Outcome.Applied || outcome == PurpleSyncAutoCore.Outcome.Deleted)
                    port.backoff(0, 0);
            }
            if (plan.record.retryAt() > 0) {
                long[] waiting = port.backoff();
                long seconds = Math.max(Math.max(0, plan.record.retryAt() - port.now()), waiting[0] > 0 ? waiting[1] - port.now() : 0);
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
            end();
            failure(e);
        }
    }
    // WHY: this file already means what the chat's newest head means, so joining that head asks nothing and writes no
    // settings. Core holds this verdict as NeedsReview, which left a moved device stopped with nothing to review.
    private void adoptSameHead(PurpleSyncInventory inventory, Snapshot facts) {
        if (!liveEffects(facts))
            return;
        PurpleSyncCore.Review review = PurpleSyncApply.review(account, inventory).review;
        if (review.status == PurpleSyncCore.ReviewStatus.Ready && review.bound && review.verdict == PurpleSyncCore.Verdict.Adopt)
            PurpleSyncApply.apply(account, inventory, review, null);
    }
    private boolean liveEffects(Snapshot facts) {
        return current() && port.enabledHint() && facts.effects();
    }
    private boolean permitted(PurpleSyncAutoCore.Plan plan, Snapshot facts) {
        if (!liveEffects(facts))
            return false;
        // WHY: the chat lookup's gate and Telegram's callbacks ask here on the main thread, where the store refuses to
        // open; answering false there refused every automatic send. The worker runs the full check before each send.
        if (android.os.Looper.getMainLooper() != null && android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            return true;
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
                long[] counted = port.backoff();
                // Telegram's own wait first; repeated ones grow by the backoff of the earlier failures.
                long notBefore = Math.max((retryAt + 999) / 1000, port.now() + backoffSeconds(counted[0]));
                port.backoff(counted[0] + 1, notBefore);
                PurpleDiagLog.event("sync", "auto_pass", PurpleDiagLogStore.slot(account.account),
                        PurpleDiagLogStore.code("action", word(plan.action)), PurpleDiagLogStore.code("outcome", "outcome_unknown"),
                        PurpleDiagLogStore.reason("transient_write_wait"));
                port.retry(()->{idle();event(PurpleSyncAutoCore.Event.SessionReady);},Math.max(1,(notBefore-port.now())*1000));
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
                                failure(e);
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
