package org.telegram.messenger.purple;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

public final class PurpleSyncAuto {
    private static final HashMap<String, Runtime> accounts = new HashMap<>();
    private static DispatchQueue queue;
    private static boolean observing;
    private static final PurpleSaveDebounce.Scheduler scheduler = new PurpleSaveDebounce.Scheduler() {
        public boolean currentThread() {
            return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        }
        public void post(Runnable task, long delay) {
            AndroidUtilities.runOnUIThread(task, delay);
        }
        public void cancel(Runnable task) {
            AndroidUtilities.cancelRunOnUIThread(task);
        }
    };
    private static final PurpleSaveDebounces saves = new PurpleSaveDebounces(scheduler);
    static void quiesceCarry(PurpleSyncChatOrigin origin,Runnable done) {
        AndroidUtilities.runOnUIThread(()->{
            Runtime runtime=accounts.get(origin.accountKey());
            if(runtime!=null&&runtime.opening.slot==origin.slot){
                runtime.cancelRetry();runtime.cancelNetworks();
                runtime.queue(()->{runtime.engine.suspend();PurpleSyncAutoEdits.cleanup(origin.slot);done.run();});
            } else {PurpleSyncAutoEdits.cleanup(origin.slot);done.run();}
        });
    }
    public static final class Status {
        public final boolean enabled;
        public final String text;
        Status(boolean enabled, String text) {
            this.enabled = enabled;
            this.text = text;
        }
    }
    public static final class Control {
        private final PurpleAccountFiles.Identity opening;
        private final Consumer<Status> listener;
        private Runtime runtime;
        private boolean closed;
        private Control(int account, Consumer<Status> listener) {
            opening = PurpleAccountFiles.capture(account);
            this.listener = listener;
            if (opening != null) {
                runtime = runtime(opening);
                runtime.listeners.add(listener);
                runtime.render();
                runtime.loadStatus();
            }
        }
        public boolean current() {
            return !closed && opening != null && opening.current();
        }
        public boolean enabled() {
            return current() && runtime != null && runtime.enabledHint();
        }
        public void setEnabled(boolean enabled) {
            if (current() && runtime != null)
                runtime.change(enabled);
        }
        public void close() {
            closed = true;
            if (runtime != null)
                runtime.listeners.remove(listener);
        }
    }
    private static final class Runtime implements PurpleSyncAutoEngine.Port {
        final PurpleAccountFiles.Identity opening;
        final PurpleSyncRunner.Platform env;
        final PurpleSyncRunner.Session account;
        final PurpleSyncAutoEngine engine;
        final PurpleSyncAutoNetwork network;
        final String preference;
        final ArrayList<Consumer<Status>> listeners = new ArrayList<>();
        Runnable retry;
        /** The last first-use answer while it waits or asks (PurpleSyncFirstUse); null once a state exists. */
        volatile PurpleSyncFirstUse.Result firstUse;
        volatile boolean firstUseRunning;
        PurpleSyncAutoCore.Reason reason = PurpleSyncAutoCore.Reason.None;
        long retryAt;
        volatile boolean paused;
        volatile Boolean requested;
        volatile long intent;
        Runtime(PurpleAccountFiles.Identity origin) {
            opening = origin;
            env = new PurpleSyncRunner.Platform(origin.slot) {
                @Override public boolean requiresRecovery() {
                    return PurpleSyncAutoEngine.recoveryScanApplies(PurpleSyncChat.boundChat(origin.slot));
                }
            };
            account = new PurpleSyncRunner.Session(origin.slot, origin.userId, env);
            preference = hintKey(origin.key);
            engine = new PurpleSyncAutoEngine(account, origin.key, this);
            PurpleSyncClient client = PurpleSyncTelegramClient.create(origin.slot, origin.userId);
            client.transientWait(value->AndroidUtilities.runOnUIThread(this::render));
            network = new PurpleSyncAutoNetwork(client, engine.session, () -> current() && enabledHint());
        }
        public boolean current() {
            return !org.telegram.messenger.SharedConfig.appLocked
                && !org.telegram.messenger.SharedConfig.isWaitingForPasscodeEnter && opening.current()
                && account.available() && env.context.origin != null && env.context.origin.lifetime == opening.lifetime
                && env.context.origin.key.equals(opening.key);
        }
        public boolean enabledHint() {
            return !paused && opening.current()
                && MessagesController.getMainSettings(account.account).getBoolean(preference, false);
        }
        synchronized void change(boolean enabled) {
            if (enabled && !current()) {
                reason = PurpleSyncAutoCore.Reason.OwnershipUnanswered;
                render();
                return;
            }
            requested = enabled;
            if (enabled && failure() != null)
                MessagesController.getMainSettings(account.account).edit().remove(failureKey(opening.key)).remove(failureShownKey(opening.key)).commit();
            long generation = ++intent;
            if (!enabled) {
                paused = true;
                PurpleSyncChat.cancelOperation(account.account,engine);
                engine.suspend();
            }
            engine.enabled(enabled, () -> generation == intent && opening.current());
        }
        public void enabledHint(boolean enabled) {
            if (enabled && Boolean.FALSE.equals(requested))
                return;
            paused = !enabled;
            if ((enabled ? current() : opening.current())
                && !MessagesController.getMainSettings(account.account).edit().putBoolean(preference, enabled).commit())
                reason = PurpleSyncAutoCore.Reason.StateChanged;
            if (!enabled) {
                PurpleSyncChat.cancelOperation(account.account,engine);
                cancelNetworks();
                PurpleSyncAutoEdits.cleanup(account.account);
            }
        }
        @Override public boolean firstUse(PurpleSyncAutoCore.Event event) {
            if (event == PurpleSyncAutoCore.Event.Disable) return false;
            PurpleSyncChatCore.Peer bound = PurpleSyncChat.boundChat(opening.slot);
            if (bound == null || PurpleSyncChatCore.Peer.self().equals(bound)) return false;
            if (event != PurpleSyncAutoCore.Event.Enable && !enabledHint()) return false;
            android.content.SharedPreferences preferences = MessagesController.getMainSettings(account.account);
            String checkedKey = "purple_sync_first_checked_" + opening.key;
            long now = now();
            if (!PurpleSyncFirstUse.due(event, now, preferences.getLong(checkedKey, 0))) return true;
            preferences.edit().putLong(checkedKey, now).commit();
            AndroidUtilities.runOnUIThread(() -> runFirstUse(event == PurpleSyncAutoCore.Event.Enable));
            return true;
        }
        @Override public boolean held() { return firstUse == PurpleSyncFirstUse.Result.Ask || failure() != null; }
        /** The diag code of the automatic pass that failed and paused automatic sync, or null. */
        String failure() { return MessagesController.getMainSettings(account.account).getString(failureKey(opening.key), null); }
        @Override public void failed(Throwable error) {
            String code = PurpleSyncAutoEngine.failureCode(error);
            PurpleDiagLog.event("sync", "auto_failed", PurpleDiagLogStore.slot(account.account), PurpleDiagLogStore.reason(code));
            if (opening.current())
                MessagesController.getMainSettings(account.account).edit().putString(failureKey(opening.key), code)
                        .putBoolean(failureShownKey(opening.key), false).commit();
            AndroidUtilities.runOnUIThread(this::render);
        }
        /** The user acted (a finished Check on the Sync screen, or the switch turned on): the pause ends. */
        void resume() {
            if (failure() == null) return;
            MessagesController.getMainSettings(account.account).edit().remove(failureKey(opening.key)).remove(failureShownKey(opening.key)).commit();
            render();
            if (enabledHint()) engine.event(PurpleSyncAutoCore.Event.SessionReady);
        }
        /**
         * First use from automatic sync: a Check of the bound chat, then {@link PurpleSyncFirstUse} with no answer.
         * Wait and Ask keep automatic sync on (the hint) so the next trigger looks again; Ask also shows the
         * attention line and holds until the Sync screen gets the answer. Once it joined or published, Enable
         * writes the automatic record for the new state.
         */
        void runFirstUse(boolean enabling) {
            if (firstUseRunning || !current()) return;
            firstUseRunning = true;
            java.util.function.BooleanSupplier live = () -> current() && !Boolean.FALSE.equals(requested);
            PurpleSyncChat.manualRunner(opening.slot, live, runner -> {
                if (runner == null) { firstUseRunning = false; return; }
                PurpleSyncRunner.Start started = runner.check(null, checked -> {
                    if (checked.status != PurpleSyncRunner.CheckStatus.Finished || checked.check == null) {
                        PurpleDiagLog.event("sync", "first_use", PurpleDiagLogStore.slot(account.account),
                                PurpleDiagLogStore.code("outcome", "refused"), PurpleDiagLogStore.reason("check_failed"));
                        firstUseRunning = false; runner.close(); render(); return;
                    }
                    PurpleSyncFirstUse.run(runner, checked.check, PurpleSyncCore.FirstUseChoice.None, outcome -> {
                        firstUseRunning = false;
                        runner.close();
                        settle(outcome, enabling);
                    });
                });
                if (started != PurpleSyncRunner.Start.Started) {
                    PurpleDiagLog.event("sync", "first_use", PurpleDiagLogStore.slot(account.account),
                            PurpleDiagLogStore.code("outcome", "refused"), PurpleDiagLogStore.reason(started == PurpleSyncRunner.Start.Busy ? "busy" : "check_failed"));
                    firstUseRunning = false; runner.close();
                }
            });
        }
        void settle(PurpleSyncFirstUse.Outcome outcome, boolean enabling) {
            switch (outcome.result) {
                case Wait:
                case Ask:
                    firstUse = outcome.result;
                    if (live()) enabledHint(true);
                    break;
                case Joined:
                case Published:
                    firstUse = null;
                    requested = true;
                    long generation = ++intent;
                    engine.enabled(true, () -> generation == intent && opening.current());
                    if (outcome.unsent) queue(() -> {
                        // The post behind the answer did not go out: the file on disk is this device's real Save.
                        PurpleSyncSettingsFile.Contents local = env.context.current()
                                ? PurpleSyncSettingsFile.read(env.context.files.settings, env.context.files.good) : null;
                        if (local != null && local.status == PurpleSyncSettingsFile.Status.Present)
                            engine.event(PurpleSyncAutoCore.Event.LocalSaved, local.bytes);
                    });
                    break;
                case NotFirstUse:
                    firstUse = null;
                    break;
                default:
                    firstUse = enabling || enabledHint() ? PurpleSyncFirstUse.Result.Refused : null;
                    break;
            }
            render();
        }
        private boolean live() { return current() && !Boolean.FALSE.equals(requested); }
        public long takeTransientRetryAt(){return PurpleRpcRequests.takeWriteRetryAt(account.account,"sync-write");}
        public long transientRetryAt(){PurpleRpcRetry.Transient wait=PurpleRpcRequests.writeWaiting(account.account,"sync-write");return wait!=null?wait.retryAt:0;}
        public boolean ownerAnswered() {
            return PurpleAccountOwner.ready();
        }
        public long now() {
            return System.currentTimeMillis() / 1000;
        }
        public void queue(Runnable task) {
            work().postRunnable(() -> {
                try {
                    task.run();
                } catch (Exception | LinkageError e) {
                    // Purple: no automatic pass dies silently; see PurpleSyncAutoEngine.Port#failed.
                    org.telegram.messenger.FileLog.e(e);
                    failed(e);
                    engine.suspend();
                }
            });
        }
        public void retry(Runnable task, long delay) {
            cancelRetry();
            if (delay > Long.MAX_VALUE - android.os.SystemClock.uptimeMillis())
                return;
            retry = () -> {
                retry = null;
                queue(task);
            };
            AndroidUtilities.runOnUIThread(retry, delay);
        }
        public void cancelRetry() {
            if (retry != null)
                AndroidUtilities.cancelRunOnUIThread(retry);
            retry = null;
        }
        public void status(PurpleSyncAutoCore.Record record, PurpleSyncAutoCore.Reason reason) {
            this.reason = reason != PurpleSyncAutoCore.Reason.None ? reason : record != null ? record.held() : reason;
            retryAt = record != null ? record.retryAt() : 0;
            if (record != null && !record.enabled())
                enabledHint(false);
            AndroidUtilities.runOnUIThread(this::render);
        }
        void loadStatus() {
            queue(() -> {
                if (!current())
                    return;
                try {
                    PurpleSyncRecoveryFiles.Snapshot input =
                        new PurpleSyncRecoveryFiles.Snapshot(new File(env.context.files.syncDirectory, "auto.json"),
                            false, 2 * 1024 * 1024, PurpleGlobalAccountChoice.PHYSICAL);
                    PurpleSyncAutoCore.Record record = PurpleSyncAutoCore.decodeRecord(input.bytes, opening.key);
                    if (current() && input.current(PurpleGlobalAccountChoice.PHYSICAL))
                        status(record, record != null ? record.held() : PurpleSyncAutoCore.Reason.InvalidRecord);
                } catch (Exception | LinkageError e) {
                    status(null, PurpleSyncAutoCore.Reason.InvalidRecord);
                }
            });
        }
        void render() {
            PurpleSyncAutoCore.Reason shownReason =
                enabledHint() && !current() ? PurpleSyncAutoCore.Reason.AccountUnavailable : reason;
            String text = !enabledHint()
                ? (LocaleController.getString(R.string.PurpleSyncAutoOff)
                      + (shownReason != PurpleSyncAutoCore.Reason.None ? "\n"
                                  + LocaleController.formatString(
                                      R.string.PurpleSyncAutoAttention, reasonText(shownReason))
                                                                       : ""))
                : retryAt > 0
                ? LocaleController.formatString(R.string.PurpleSyncAutoRetry, Math.max(0, retryAt - now()))
                : shownReason == PurpleSyncAutoCore.Reason.None
                ? LocaleController.getString(R.string.PurpleSyncAutoReady)
                : LocaleController.formatString(R.string.PurpleSyncAutoAttention, reasonText(shownReason));
            PurpleSyncFirstUse.Result first = firstUse;
            if (enabledHint() && failure() != null)
                text = LocaleController.formatString(R.string.PurpleSyncAutoAttention, LocaleController.getString(R.string.PurpleSyncAutoFailed));
            else if (enabledHint() && first == PurpleSyncFirstUse.Result.Wait)
                text = LocaleController.getString(R.string.PurpleSyncFirstWait);
            else if (enabledHint() && first == PurpleSyncFirstUse.Result.Ask)
                text = LocaleController.formatString(R.string.PurpleSyncAutoAttention, LocaleController.getString(R.string.PurpleSyncFirstAskAttention));
            else if (enabledHint() && first == PurpleSyncFirstUse.Result.Refused)
                text = LocaleController.formatString(R.string.PurpleSyncAutoAttention, LocaleController.getString(R.string.PurpleSyncFirstRefusedAttention));
            PurpleRpcRetry.Transient wait=PurpleRpcRequests.waiting(account.account);
            if(enabledHint()&&wait!=null)text=PurpleRpcRequests.text(wait);
            Status status = new Status(enabledHint(), text);
            for (Consumer<Status> listener : new ArrayList<>(listeners)) listener.accept(status);
        }
        private PurpleSyncChatCore.Peer scopedChat;
        private PurpleSyncAutoNetwork scoped;
        /** The network for {@code chat}: Saved Messages, or the bound chat; null when its local facts are missing. */
        private synchronized PurpleSyncAutoNetwork network(PurpleSyncChatCore.Peer chat) {
            if (PurpleSyncChatCore.Peer.self().equals(chat)) return network;
            if (scoped != null && chat.equals(scopedChat)) return scoped;
            PurpleSyncClient client = PurpleSyncChat.boundClient(opening.slot, chat, () -> current() && enabledHint());
            if (client == null) return null;
            client.transientWait(value->AndroidUtilities.runOnUIThread(this::render));
            if (scoped != null) scoped.cancel();
            scopedChat = chat;
            scoped = new PurpleSyncAutoNetwork(client, engine.session, () -> current() && enabledHint());
            return scoped;
        }
        synchronized void cancelNetworks() {
            network.cancel();
            if (scoped != null) scoped.cancel();
        }
        /** The chat the stored state belongs to; the store moved it into the bound chat when the engine opened it. */
        private PurpleSyncChatCore.Peer stateChat() {
            return PurpleAccountSyncStore.stateChat(env.context);
        }
        public void check(
            String ticket, int[] exactIds, java.util.function.BooleanSupplier current,
            PurpleSyncAutoEngine.Reply<PurpleSyncAutoEngine.Observed> reply) {
            PurpleSyncChat.underLock(env.context,engine,env.operationKey(account.account,account.userId),false,engine.borrowedAuthority(),current,status->queue(()->{
                if(!current.getAsBoolean())return;
                PurpleSyncChatCore.Peer chat=stateChat();
                PurpleSyncAutoNetwork scope=status.readable(chat)?network(chat):null;
                if(scope!=null)scope.check(exactIds,current,observed->{
                    if(observed!=null&&PurpleSyncChat.spaceMismatch(chat,observed.inventory,PurpleAccountSyncStore.stateSpace(env.context))){
                        // Purple: the state is in another space than the chat's records: first use, not a pause.
                        reply.done(null);
                        AndroidUtilities.runOnUIThread(()->runFirstUse(false));
                        return;
                    }
                    reply.done(observed);
                });else reply.done(null);
            }));
        }
        public void send(boolean edit, int id, byte[] bytes, byte[] previous,
            java.util.function.BooleanSupplier current, PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply) {
            PurpleSyncChat.underLock(env.context,engine,env.operationKey(account.account,account.userId),true,engine.borrowedAuthority(),current,status->queue(()->{
                if(!current.getAsBoolean())return;
                PurpleSyncChatCore.Peer chat=stateChat();
                PurpleSyncAutoNetwork scope=status.postable(chat)?network(chat):null;
                if(scope!=null)scope.send(edit,id,bytes,previous,current,new PurpleSyncTransport.PostAuthority(){
                    final java.util.function.BooleanSupplier lock=engine.borrowedAuthority();
                    public boolean current(){return current.getAsBoolean() && lock.getAsBoolean();}
                    public void check(Consumer<Boolean> done){
                        PurpleSyncChat.underLock(env.context,engine,env.operationKey(account.account,account.userId),true,
                                lock,this::current,fresh->done.accept(fresh.postable(chat) && current()));
                    }
                    public boolean automatic(PurpleSyncChatOrigin origin,PurpleSyncChatCore.Peer target){
                        return current() && chat.equals(target) && !PurpleSyncChatCore.Peer.self().equals(target);
                    }
                },reply);else reply.done(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
            }));
        }
        public void retire(int id, byte[] exact, java.util.function.BooleanSupplier current,
            PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply) {
            PurpleSyncChat.underLock(env.context,engine,env.operationKey(account.account,account.userId),true,engine.borrowedAuthority(),current,status->queue(()->{
                if(!current.getAsBoolean())return;
                PurpleSyncChatCore.Peer chat=stateChat();
                PurpleSyncAutoNetwork scope=status.postable(chat)?network(chat):null;
                if(scope!=null)scope.retire(id,exact,current,reply);else reply.done(PurpleSyncAutoCore.Outcome.DeletionRefused);
            }));
        }
    }
    private static synchronized DispatchQueue work() {
        if (queue == null)
            queue = new DispatchQueue("purpleSyncAuto");
        return queue;
    }
    private static String hintKey(String key) {
        return "purple_sync_auto_enabled_" + key;
    }
    private static String failureKey(String key) {
        return "purple_sync_auto_failure_" + key;
    }
    private static String failureShownKey(String key) {
        return "purple_sync_auto_failure_shown_" + key;
    }
    /**
     * Once per failure: true when automatic sync of {@code slot} is paused by a failed automatic pass that the Sync
     * screen has not shown yet; it is then marked shown.
     */
    public static boolean failureOnce(int slot) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null) return false;
        android.content.SharedPreferences preferences = MessagesController.getMainSettings(slot);
        if (preferences.getString(failureKey(identity.key), null) == null || preferences.getBoolean(failureShownKey(identity.key), false)) return false;
        preferences.edit().putBoolean(failureShownKey(identity.key), true).commit();
        return true;
    }
    /** A finished Check on the Sync screen of {@code slot}: a pause after a failed automatic pass ends. */
    public static void resume(int slot) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null) return;
        AndroidUtilities.runOnUIThread(() -> {
            Runtime runtime = accounts.get(identity.key);
            if (runtime != null && runtime.current()) runtime.resume();
            else MessagesController.getMainSettings(slot).edit().remove(failureKey(identity.key)).remove(failureShownKey(identity.key)).commit();
        });
    }
    private static synchronized Runtime runtime(PurpleAccountFiles.Identity identity) {
        Runtime value = accounts.get(identity.key);
        if (value != null && !value.current()) {
            value.engine.close();
            accounts.remove(identity.key);
            value = null;
        }
        if (value == null) {
            value = new Runtime(identity);
            accounts.put(identity.key, value);
        }
        return value;
    }
    /**
     * The Sync screen ran first use for {@code slot} ({@link PurpleSyncFirstUse}). After a join or publish the new
     * state gets its automatic record when automatic sync is on, because setting an old state aside also set its
     * record aside; a pending question is cleared.
     */
    public static void firstUseSettled(int slot, PurpleSyncFirstUse.Outcome outcome) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null || outcome == null) return;
        AndroidUtilities.runOnUIThread(() -> {
            Runtime runtime = accounts.get(identity.key);
            if (runtime == null || !runtime.current()) return;
            boolean on = MessagesController.getMainSettings(slot).getBoolean(hintKey(identity.key), false);
            if (outcome.result == PurpleSyncFirstUse.Result.Joined || outcome.result == PurpleSyncFirstUse.Result.Published) {
                runtime.firstUse = null;
                if (on) runtime.settle(outcome, false); else runtime.render();
            } else if (outcome.result == PurpleSyncFirstUse.Result.Ask || outcome.result == PurpleSyncFirstUse.Result.Wait) {
                runtime.firstUse = on ? outcome.result : null;
                runtime.render();
            }
        });
    }
    static void reconcile(PurpleAccountFiles.Identity origin) {
        if (origin == null || !origin.current()) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (origin.current()) trigger(origin.slot, PurpleSyncAutoCore.Event.SessionReady);
        });
    }
    public static Control control(int account, Consumer<Status> listener) {
        return new Control(account, listener);
    }
    private static void trigger(int slot, PurpleSyncAutoCore.Event event) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (org.telegram.messenger.SharedConfig.appLocked
            || org.telegram.messenger.SharedConfig.isWaitingForPasscodeEnter)
            return;
        if (identity == null || !MessagesController.getMainSettings(slot).getBoolean(hintKey(identity.key), false))
            return;
        if (!PurpleAccountOwner.ready())
            return;
        runtime(identity).engine.event(event);
    }
    private static synchronized void observe() {
        if (observing)
            return;
        observing = true;
        NotificationCenter.getGlobalInstance().addObserver(
            (id, account, args) -> all(PurpleSyncAutoCore.Event.Unlock), NotificationCenter.passcodeDismissed);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (android.content.Intent.ACTION_USER_PRESENT.equals(intent.getAction()))
                    all(PurpleSyncAutoCore.Event.Unlock);
            }
        };
        try {
            ApplicationLoader.applicationContext.registerReceiver(
                receiver, new android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT));
        } catch (RuntimeException e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }
    private static void all(PurpleSyncAutoCore.Event event) {
        observe();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; ++account)
            if (UserConfig.getInstance(account).isClientActivated())
                trigger(account, event);
    }
    public static void foreground() {
        all(PurpleSyncAutoCore.Event.Foreground);
    }
    public static void sessionReady(int account) {
        observe();
        trigger(account, PurpleSyncAutoCore.Event.SessionReady);
    }
    static void refresh(Runnable reload) {
        saves.refresh(reload);
    }
    static void saved(PurpleAccountFiles.Identity origin, File file, byte[] bytes, PurpleSaveDebounce.Receipt receipt,
        boolean actualSave) {
        if (origin == null || !origin.current())
            return;
        PurpleSaveDebounce debounce = saves.of(origin.key);
        if (!actualSave) {
            debounce.cancel();
            return;
        }
        if (!MessagesController.getMainSettings(origin.slot).getBoolean(hintKey(origin.key), false))
            return;
        String owner = PurpleAccountOwner.owner(), global = PurpleAccountOwner.global();
        long role = PurpleAccountOwner.roleEpoch();
        PurpleAccountFiles files = PurpleAccountFiles.resolve(origin.key, owner);
        if (files == null || !files.settings.equals(file))
            return;
        PurpleAccountFileSnapshot written = PurpleAccountFileSnapshot.read(file),
                                  local = PurpleAccountFileSnapshot.read(PurpleAccountFiles.file("local.toml")),
                                  record =
                                      PurpleAccountFileSnapshot.read(PurpleAccountFiles.file("accounts/root.toml"));
        if (bytes == null || !java.util.Arrays.equals(bytes, written.bytes))
            return;
        byte[] exact = bytes.clone();
        PurpleSaveDebounce.Receipt guarded = new PurpleSaveDebounce.Receipt() {
            public boolean current() {
                return origin.current() && (receipt == null || receipt.current())
                    && role == PurpleAccountOwner.roleEpoch()
                    && java.util.Objects.equals(owner, PurpleAccountOwner.owner())
                    && java.util.Objects.equals(global, PurpleAccountOwner.global()) && written.matches(file)
                    && local.matches(PurpleAccountFiles.file("local.toml"))
                    && record.matches(PurpleAccountFiles.file("accounts/root.toml"));
            }
            public void beforeRefresh() {
                if (receipt != null)
                    receipt.beforeRefresh();
            }
            public void afterRefresh() {
                if (receipt != null)
                    receipt.afterRefresh();
            }
        };
        debounce.admit(guarded, () -> runtime(origin).engine.event(PurpleSyncAutoCore.Event.LocalSaved, exact), true);
    }
    private static String reasonText(PurpleSyncAutoCore.Reason reason) {
        int id;
        switch (reason) {
            case OwnershipUnanswered:
            case WrongFileAccount:
            case AccountUnavailable:
                id = R.string.PurpleSyncAutoAccount;
                break;
            case AccountUnbound:
                id = R.string.PurpleSyncAutoBinding;
                break;
            case SyncChatNotResolved:
            case SyncChatProblem:
            case ChatNotWritable:
                id = R.string.PurpleSyncAutoChat;
                break;
            case FileAbsent:
            case InvalidSettings:
            case LastGood:
            case NoSavedBytes:
                id = R.string.PurpleSyncAutoFile;
                break;
            case Conflict:
            case UnsafeImpact:
            case WorkModeScopeChanged:
            case NeedsReview:
                id = R.string.PurpleSyncAutoReview;
                break;
            case RetryStopped:
                id = R.string.PurpleSyncAutoStopped;
                break;
            default:
                id = R.string.PurpleSyncAutoRecovery;
                break;
        }
        return LocaleController.getString(id);
    }
    private PurpleSyncAuto() {}
}
