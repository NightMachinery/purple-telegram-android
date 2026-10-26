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
        PurpleSyncAutoCore.Reason reason = PurpleSyncAutoCore.Reason.None;
        long retryAt;
        volatile boolean paused;
        volatile Boolean requested;
        volatile long intent;
        Runtime(PurpleAccountFiles.Identity origin) {
            opening = origin;
            env = new PurpleSyncRunner.Platform(origin.slot);
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
                network.cancel();
                PurpleSyncAutoEdits.cleanup(account.account);
            }
        }
        public long takeTransientRetryAt(){return PurpleRpcRequests.takeWriteRetryAt(account.account,"sync-write");}
        public long transientRetryAt(){PurpleRpcRetry.Transient wait=PurpleRpcRequests.writeWaiting(account.account,"sync-write");return wait!=null?wait.retryAt:0;}
        public boolean ownerAnswered() {
            return PurpleAccountOwner.ready();
        }
        public long now() {
            return System.currentTimeMillis() / 1000;
        }
        public void queue(Runnable task) {
            work().postRunnable(task);
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
            PurpleRpcRetry.Transient wait=PurpleRpcRequests.waiting(account.account);
            if(enabledHint()&&wait!=null)text=PurpleRpcRequests.text(wait);
            Status status = new Status(enabledHint(), text);
            for (Consumer<Status> listener : new ArrayList<>(listeners)) listener.accept(status);
        }
        public void check(
            String ticket, int[] exactIds, java.util.function.BooleanSupplier current,
            PurpleSyncAutoEngine.Reply<PurpleSyncAutoEngine.Observed> reply) {
            PurpleSyncChat.underLock(env.context,engine,env.operationKey(account.account,account.userId),false,engine.borrowedAuthority(),current,status->queue(()->{
                if(!current.getAsBoolean())return;
                if(status.selfReadable())network.check(exactIds,current,reply);else reply.done(null);
            }));
        }
        public void send(boolean edit, int id, byte[] bytes, byte[] previous,
            java.util.function.BooleanSupplier current, PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply) {
            PurpleSyncChat.underLock(env.context,engine,env.operationKey(account.account,account.userId),true,engine.borrowedAuthority(),current,status->queue(()->{
                if(!current.getAsBoolean())return;
                if(status.selfPostable())network.send(edit,id,bytes,previous,current,new PurpleSyncTransport.PostAuthority(){
                    final java.util.function.BooleanSupplier lock=engine.borrowedAuthority();
                    public boolean current(){return current.getAsBoolean() && lock.getAsBoolean();}
                    public void check(Consumer<Boolean> done){
                        PurpleSyncChat.underLock(env.context,engine,env.operationKey(account.account,account.userId),true,
                                lock,this::current,fresh->done.accept(fresh.selfPostable() && current()));
                    }
                },reply);else reply.done(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
            }));
        }
        public void retire(int id, byte[] exact, java.util.function.BooleanSupplier current,
            PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply) {
            PurpleSyncChat.underLock(env.context,engine,env.operationKey(account.account,account.userId),true,engine.borrowedAuthority(),current,status->queue(()->{
                if(!current.getAsBoolean())return;
                if(status.selfPostable())network.retire(id,exact,current,reply);else reply.done(PurpleSyncAutoCore.Outcome.DeletionRefused);
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
