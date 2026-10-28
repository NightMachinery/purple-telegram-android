/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PurpleSyncRunner {
    static final long UI_WRITE_TIMEOUT_MS = 30000L;

    public enum Start { Started, Busy, Stale, AccountUnavailable, Closed }

    public enum CheckStatus { Finished, Cancelled, AccountUnavailable, SyncChatHeld }

    public interface Listener<T> {
        void onResult(T result);
    }

    interface Poster {
        void post(Runnable task);
    }

    interface Task<T> {
        T run();
    }

    interface Env {
        default boolean current(int account, long userId) { return PurpleAccountBinding.isSameActiveUser(account, userId); }
        default String operationKey(int account, long userId) { return account + ":" + userId; }
        default PurpleAccountSyncCore.Result initialize(int account,String install,String device,String space) {
            return PurpleAccountSyncCore.initializeBoundLocalState(account,install,device,space);
        }
        default void resolveChat(boolean beforePost,Object owner,String key,java.util.function.BooleanSupplier current,java.util.function.Consumer<Boolean> done) { done.accept(true); }
        default void cancelChat(Object owner) { }
        default void prepareApply(Object owner,String key,java.util.function.BooleanSupplier current,java.util.function.Consumer<Boolean> done) { done.accept(true); }
        default void waiting(java.util.function.Consumer<PurpleRpcRetry.Transient> observer){}
        default boolean workerEffects(){return false;}
        default void checkEffects(java.util.function.BooleanSupplier effects,java.util.function.Consumer<Boolean> done){done.accept(effects.getAsBoolean());}
        default PurpleSyncChatScreen.Candidate recipient() { return null; }
        default boolean consent(PurpleSyncInventory inventory) { return true; }
        default boolean requiresRecovery() { return false; }
        default PurpleSyncRecoveryModel.Offer recovery(PurpleSyncInventory inventory,PurpleSyncSettingsFile.Contents local) { return null; }
        default boolean sendQueued() { return false; }
        String device();

        long serverTimeMillis(int account);

        boolean hasState();

        PurpleAccountSyncStore store();

        PurpleSyncHistory history();

        PurpleSyncSettingsFile.Contents readSettings();

        PurpleSyncSettingsFile.WriteResult replaceSettings(
                PurpleSyncSettingsFile.Contents expected, byte[] bytes,
                String reason, boolean fromImport);
    }

    static final class Session {
        final int account;
        final long userId;
        final Env env;
        final PurpleAccountFiles.Identity origin;

        final java.util.function.BooleanSupplier admission;

        Session(int account, long userId, Env env) { this(account,userId,env,()->true); }
        Session(int account,long userId,Env env,java.util.function.BooleanSupplier admission) {
            this.admission=admission;
            this.account = account;
            this.userId = userId;
            this.env = env;
            this.origin = env instanceof Platform ? ((Platform) env).context.origin : null;
        }

        boolean writable() {
            return available() && (!(env instanceof Platform) || ((Platform) env).context.editable());
        }

        boolean available() {
            return admission.getAsBoolean() && userId > 0 && env.current(account, userId)
                    && (!(env instanceof Platform) || origin != null && origin.userId == userId && origin.current());
        }
    }

    public enum RecoveryAction { None, Relink, StartAgain, ResumeArchive, Held }
    public static final class RecoveryOutcome {
        public final boolean ok;
        public final boolean startAgain;
        public final Check next;
        RecoveryOutcome(boolean ok,boolean startAgain,Check next) { this.ok=ok;this.startAgain=startAgain;this.next=next; }
    }
    public static final class Check {
        public final PurpleSyncCore.Review review;
        public final PurpleSyncSettingsFile.Contents local;
        public final PurpleSyncApply.ApplyResult adoptFailure;
        public final boolean sendQueued;
        final PurpleSyncInventory inventory;
        final long generation;
        final PurpleSyncRecoveryModel.Offer recovery;
        public final RecoveryAction recoveryAction;
        public final boolean setupOnlyJoin;

        Check(long generation, PurpleSyncApply.Reviewed reviewed,
                PurpleSyncInventory inventory, boolean sendQueued) {
            this.generation = generation;
            setupOnlyJoin=PurpleSyncApply.setupOnlyJoin(inventory,reviewed.review,reviewed.local);
            recovery=reviewed.recovery;
            recoveryAction=recovery==null?RecoveryAction.None
                    :recovery.resuming()&&recovery.plan.start==PurpleSyncRecoveryCore.Start.Ready?RecoveryAction.ResumeArchive
                    :recovery.plan.status==PurpleSyncRecoveryCore.Status.RootRelink?RecoveryAction.Relink
                    :recovery.plan.offerStartAgain?RecoveryAction.StartAgain:RecoveryAction.Held;
            this.review = reviewed.review;
            this.local = reviewed.local;
            this.adoptFailure = reviewed.adoptFailure;
            this.inventory = inventory;
            this.sendQueued = sendQueued;
        }

        public PurpleSyncCore.Action action() { return setupOnlyJoin?PurpleSyncCore.Action.Join:review.action; }
        public boolean requiresChoice(PurpleSyncCore.Action action) {
            return action!=PurpleSyncCore.Action.FinishSending&&!(action==PurpleSyncCore.Action.Join&&setupOnlyJoin);
        }

        public int records() {
            return inventory.count();
        }

        public long bytes() {
            return inventory.totalBytes();
        }
    }

    public static final class CheckOutcome {
        public final CheckStatus status;
        public final Check check;

        CheckOutcome(CheckStatus status, Check check) {
            this.status = status;
            this.check = check;
        }
    }

    public static final class PostTicket {
        public final PurpleSyncCore.PostRequest request;
        public final boolean freshCheckFirst;
        final PurpleSyncInventory inventory;
        final boolean sendQueued;
        final long generation;
        final java.util.function.BooleanSupplier effects;
        final Session owner;

        PostTicket(PurpleSyncCore.PostRequest request, boolean freshCheckFirst,
                PurpleSyncInventory inventory, boolean sendQueued,
                long generation,java.util.function.BooleanSupplier effects,Session owner) {
            this.effects=effects;this.owner=owner;
            this.request = request;
            this.freshCheckFirst = freshCheckFirst;
            this.inventory = inventory;
            this.sendQueued = sendQueued;
            this.generation = generation;
        }
    }

    public static final class ApplyOutcome {
        public final PurpleSyncApply.ApplyResult result;
        public final PurpleSyncCore.ApplyFailure failure;
        public final Check next;
        public final PostTicket post;

        ApplyOutcome(PurpleSyncApply.ApplyResult result,
                PurpleSyncCore.ApplyFailure failure, Check next, PostTicket post) {
            this.result = result;
            this.failure = failure;
            this.next = next;
            this.post = post;
        }
    }

    public static final class RestoreOutcome {
        public final PurpleSyncApply.RestoreResult result;
        public final boolean undoFinished;
        public final Check next;

        RestoreOutcome(PurpleSyncApply.RestoreResult result, boolean undoFinished,
                Check next) {
            this.result = result;
            this.undoFinished = undoFinished;
            this.next = next;
        }
    }

    public static final class UndoOffer {
        public final String historyId;
        public final PurpleSyncCore.DeviceName device;

        UndoOffer(String historyId, PurpleSyncCore.DeviceName device) {
            this.historyId = historyId;
            this.device = device;
        }
    }

    public static final class Preview {
        public final PurpleSyncHistory.Entry entry;
        public final byte[] text;
        public final PurpleSyncSettingsFile.Contents current;
        public final PurpleSyncCore.Diff diff;
        public final String impactNotes;
        public final boolean impactReady, dangerous;

        Preview(PurpleSyncHistory.Entry entry, byte[] text,
                PurpleSyncSettingsFile.Contents current, PurpleSyncCore.Diff diff) {
            this.entry = entry;
            this.text = text;
            this.current = current;
            this.diff = diff;
            org.json.JSONObject impact = null;
            try { if (current != null && current.mutation != null) impact = current.mutation.impact.analyze(text); }
            catch (Exception | LinkageError e) { impact = null; }
            impactReady = PurpleConfigImpact.ready(impact);
            dangerous = impact != null && impact.optBoolean("dangerous");
            PurpleAccountEditor.Session editor = current != null && current.mutation != null ? current.mutation.editor : null;
            impactNotes = editor != null ? PurpleConfigImpactText.describe(impact, editor.global, editor.accountKey, editor.globalKey) : "";
        }
        boolean current() { return current != null && current.current(); }
    }

    private static final class Holder<T> {
        T value;
    }

    private static DispatchQueue sharedQueue;

    private final Session session;
    private final PurpleSyncTransport transport;
    private final Poster queue;
    private final Poster ui;
    private volatile long generation;
    private boolean busy;
    private boolean checking;
    private volatile boolean closed;
    private Check current;
    private volatile Object operationOwner = new Object();
    private volatile PurpleSyncOperations.Capability operationCapability;
    private PurpleSyncPublisher publisher;
    private volatile java.util.function.Consumer<PurpleRpcRetry.Transient> waitObserver=ignored->{};
    public void transientWait(java.util.function.Consumer<PurpleRpcRetry.Transient> observer){waitObserver=observer!=null?observer:ignored->{};}
    private void waiting(PurpleRpcRetry.Transient value){ui.post(()->{if(!closed&&busy&&session.available())waitObserver.accept(value);});}

    public PurpleSyncRunner(int account, PurpleSyncTransport transport) {
        this(account, PurpleAccountBinding.activeUserId(account), transport,
                new Platform(account), sharedQueue(), AndroidUtilities::runOnUIThread);
    }

    PurpleSyncRunner(int account, long userId, PurpleSyncTransport transport,
            Env env, Poster queue, Poster ui) {
        if (transport == null || env == null || queue == null || ui == null) {
            throw new IllegalArgumentException("incomplete sync runner");
        }
        this.session = new Session(account, userId, env);
        this.transport = transport;
        this.queue = queue;
        this.ui = ui;env.waiting(this::waiting);
    }

    public PurpleSyncChatScreen.Candidate recipient() { return session.env.recipient(); }

    public String accountLabel() {
        return session.origin != null ? PurpleConfigImpactText.label(session.origin.key) : Long.toString(session.userId);
    }

    public String impactNotes(Check check, PurpleSyncCore.Choice choice) {
        if (check == null || choice == null || check.local == null || check.local.mutation == null) return "";
        PurpleAccountEditor.Session editor = check.local.mutation.editor;
        StringBuilder text = new StringBuilder();
        for (PurpleSyncCore.ChoiceImpact change : choice.impacts) {
            if (text.length() != 0) text.append("\n\n");
            if (change.replacedKey != null) for (PurpleSyncCore.Head head : check.review.heads) {
                if (change.replacedKey.equals(head.key)) text.append(org.telegram.messenger.LocaleController.formatString(
                        org.telegram.messenger.R.string.PurpleSyncImpactVersion, PurpleSyncApply.deviceLabel(head.name))).append("\n\n");
            }
            text.append(PurpleConfigImpactText.describe(change.impact, editor.global, editor.accountKey, editor.globalKey));
        }
        return text.toString();
    }

    public int account() {
        return session.account;
    }

    public long userId() {
        return session.userId;
    }

    public boolean accountAvailable() {
        return session.available();
    }

    public boolean busy() {
        return busy || PurpleSyncOperations.busy(operationKey());
    }

    public boolean checking() {
        return checking;
    }

    public boolean closed() {
        return closed;
    }

    public Check current() {
        return closed ? null : current;
    }

    public UndoOffer undoOffer() {
        return (busy() || closed || !session.available()) ? null : PurpleSyncOperations.undo(operationKey());
    }

    public Start check(PurpleSyncTransport.Progress progress,
            Listener<CheckOutcome> done) {
        final Start refused = refusal(true);
        if (refused != null) {
            return refused;
        }
        final long started = ++generation;
        if (!begin()) return Start.Busy;
        checking = true;
        current = null;
        checkTransport(started,(phase, count, total) -> {
            if (live(started) && progress != null) {
                progress.onProgress(phase, count, total);
            }
        }, result -> onChecked(started, result, done));
        return Start.Started;
    }

    public Start recover(Check shown,boolean archive,Listener<RecoveryOutcome> done) {
        Start refused=refusal(true);
        if(refused!=null)return refused;
        if(shown==null||shown!=current||shown.generation!=generation||shown.recovery==null||!shown.recovery.model.retained())return Start.Stale;
        if(archive?shown.recoveryAction!=RecoveryAction.StartAgain&&shown.recoveryAction!=RecoveryAction.ResumeArchive:shown.recoveryAction!=RecoveryAction.Relink)return Start.Stale;
        if(!begin())return Start.Busy;
        long started=++generation;checking=true;current=null;
        checkTransport(started,null,result->ui.post(()->{
            if(!live(started))return;
            checking=false;
            if(result.status!=PurpleSyncTransport.CheckStatus.Finished) { end();done.onResult(new RecoveryOutcome(false,archive,null));return; }
            queue.post(()->{
                boolean ok=false;
                PurpleSyncApply.Reviewed next=null;
                if(session.available()&&shown.recovery.model.retained()) {
                    PurpleSyncSettingsFile.Contents local=session.env.readSettings();
                    PurpleSyncRecoveryModel.Offer fresh=session.env.recovery(result.inventory,local);
                    if(fresh!=null&&shown.recovery.model.retained()) {
                        if(archive)ok=fresh.model.startAgain(fresh,result.sendQueued||session.env.sendQueued(),()->!session.env.sendQueued());
                        else if(fresh.plan.status==PurpleSyncRecoveryCore.Status.RootRelink)ok=fresh.model.relink(fresh);
                    }
                    if(ok&&archive)PurpleSyncOperations.undo(operationKey(),null);
                    if(ok&&session.available())next=PurpleSyncApply.reviewWithAdopt(session,result.inventory);
                }
                final boolean completed=ok;final PurpleSyncApply.Reviewed reviewed=next;
                ui.post(()->{
                    if(!live(started))return;end();
                    Check issued=reviewed!=null?issue(reviewed,result.inventory,result.sendQueued):null;
                    done.onResult(new RecoveryOutcome(completed,archive,issued));
                });
            });
        }));
        return Start.Started;
    }

    public boolean cancel() {
        if (closed || !checking) {
            return false;
        }
        ++generation;
        end();
        checking = false;
        current = null;
        transport.cancel();
        return true;
    }

    public Start apply(Check shown, String chosenKey,
            Listener<ApplyOutcome> done) {
        final Start refused = refusal(true);
        if (refused != null) {
            return refused;
        } else if (shown == null || shown != current
                || shown.generation != generation || shown.local == null || !session.env.workerEffects()&&!shown.local.current()) {
            return Start.Stale;
        }
        if (!session.env.consent(shown.inventory)) return Start.Stale;
        final long started = ++generation;
        if (!begin()) return Start.Busy;
        current = null;
        final PurpleSyncCore.Verdict verdict = shown.review.verdict;
        final Object owner=operationOwner;
        final PurpleSyncOperations.Capability capability=operationCapability;
        final java.util.function.BooleanSupplier admitted=()->capability.current()&&operationCapability==capability
                &&operationOwner==owner&&live(started);
        final Session applying=new Session(session.account,session.userId,session.env,admitted);
        session.env.prepareApply(owner,operationKey(),admitted,allowed->{
        if(!admitted.getAsBoolean())return;
        queue.post(() -> {
            if(!applying.available())return;
            final PurpleSyncApply.ApplyResult result = Boolean.TRUE.equals(allowed)
                    ? PurpleSyncApply.apply(applying,shown.inventory,shown.review,chosenKey,shown.local)
                    : PurpleSyncApply.refused(PurpleSyncCore.ApplyStatus.NeedsRecheck);
            final PurpleSyncCore.ApplyFailure failure =
                    PurpleSyncCore.describeApplyFailure(result.status,
                            result.joined, result.wroteFile, result.historyKept(),
                            result.undoAvailable, result.otherVersionsRemain);
            final boolean posts = result.status == PurpleSyncCore.ApplyStatus.Applied
                    && result.publishNeeded && publishes(verdict);
            final PurpleSyncApply.Reviewed next =
                    (!posts && result.status == PurpleSyncCore.ApplyStatus.Applied)
                            ? PurpleSyncApply.reviewWithAdopt(applying, shown.inventory)
                            : null;
            ui.post(() -> {
                if (!closed && result.wroteFile) {
                    PurpleSyncOperations.undo(operationKey(), result.undoAvailable
                            ? new UndoOffer(result.historyId, result.source != null ? result.source.name : null) : null);
                }
                if (!live(started)) {
                    return;
                }
                end();
                PostTicket ticket = null;
                Check check = null;
                if (posts) {
                    ticket = new PostTicket(
                            PurpleSyncCore.PostRequest.newContent(result.fingerprint,
                                    result.expectedParents),
                            freshCheckFirst(verdict), shown.inventory,
                            shown.sendQueued, ++generation, shown.local.mutation!=null?shown.local.mutation::effectCurrent:()->true,session);
                } else if (next != null) {
                    check = issue(next, shown.inventory, shown.sendQueued);
                }
                done.onResult(new ApplyOutcome(result, failure, check, ticket));
            });
        });
        });
        return Start.Started;
    }

    public Start publish(Check fresh, PurpleSyncCore.PostRequest request,
            Listener<PurpleSyncPublisher.Result> done) {
        final Start refused = refusal(true);
        if (refused != null) {
            return refused;
        } else if (fresh == null || request == null || fresh != current
                || fresh.generation != generation) {
            return Start.Stale;
        }
        if(!request.pendingOnly&&(fresh.local==null||fresh.local.mutation==null||!session.env.workerEffects()&&(!fresh.local.current()||!fresh.local.mutation.effectCurrent())))return Start.Stale;
        if(!request.pendingOnly) {
            boolean ready=false;for(PurpleSyncCore.Choice choice:fresh.review.choices)if(choice.key==null&&choice.impactReady)ready=true;
            if(!ready)return Start.Stale;
        }
        if (!session.env.consent(fresh.inventory)) return Start.Stale;
        return startPublish(fresh.inventory, fresh.sendQueued, request, request.pendingOnly?()->true:fresh.local.mutation::effectCurrent, done);
    }

    public Start publish(Check fresh,PostTicket ticket,Listener<PurpleSyncPublisher.Result> done) {
        if(ticket==null||ticket.owner!=session||!session.env.workerEffects()&&!ticket.effects.getAsBoolean())return Start.Stale;
        Start refused=refusal(true);if(refused!=null)return refused;
        if(fresh==null||fresh!=current||fresh.generation!=generation||fresh.local==null||fresh.local.mutation==null||!session.env.workerEffects()&&!fresh.local.current())return Start.Stale;
        boolean ready=false;for(PurpleSyncCore.Choice choice:fresh.review.choices)if(choice.key==null&&choice.impactReady)ready=true;
        if(!ready)return Start.Stale;
        return startPublish(fresh.inventory,fresh.sendQueued,ticket.request,
                ()->ticket.effects.getAsBoolean()&&fresh.local.mutation.effectCurrent(),done);
    }

    public Start publish(PostTicket ticket,
            Listener<PurpleSyncPublisher.Result> done) {
        final Start refused = refusal(true);
        if (refused != null) {
            return refused;
        } else if (ticket == null || ticket.owner!=session || ticket.freshCheckFirst
                || ticket.generation != generation || !session.env.workerEffects()&&!ticket.effects.getAsBoolean()) {
            return Start.Stale;
        }
        return startPublish(ticket.inventory, ticket.sendQueued, ticket.request, ticket.effects,
                done);
    }

    public Start undo(UndoOffer offer, Preview preview, Listener<RestoreOutcome> done) {
        final UndoOffer undo = PurpleSyncOperations.undo(operationKey());
        final Start refused = refusal(false);
        if (refused != null) {
            return refused;
        } else if (offer == null || undo == null
                || !undo.historyId.equals(offer.historyId) || preview == null || !preview.current()
                || !preview.impactReady || !offer.historyId.equals(preview.entry.id)) {
            return Start.Stale;
        }
        return startRestore(offer.historyId, PurpleSyncHistory.Reason.BeforeUndo,
                preview, done);
    }

    public Start restore(Preview preview, Listener<RestoreOutcome> done) {
        String historyId = preview != null && preview.entry != null ? preview.entry.id : null;
        final Start refused = refusal(false);
        if (refused != null) {
            return refused;
        } else if (historyId == null || !preview.current() || !preview.impactReady) {
            return Start.Stale;
        }
        return startRestore(historyId, PurpleSyncHistory.Reason.BeforeRestore,
                preview, done);
    }

    public boolean history(Listener<List<PurpleSyncHistory.Entry>> done) {
        if (closed || !session.available()) return false;
        queue.post(() -> {
            if (!session.available()) return;
            final List<PurpleSyncHistory.Entry> entries = session.env.history().list();
            ui.post(() -> {
                if (!closed && session.available()) {
                    done.onResult(Collections.unmodifiableList(entries));
                }
            });
        });
        return true;
    }

    public boolean preview(String historyId, Listener<Preview> done) {
        if (closed || historyId == null || !session.available()
               ) {
            return false;
        }
        queue.post(() -> {
            if (!session.available()) return;
            final PurpleSyncHistory history = session.env.history();
            PurpleSyncHistory.Entry found = null;
            for (PurpleSyncHistory.Entry entry : history.list()) {
                if (entry.id.equals(historyId)) {
                    found = entry;
                    break;
                }
            }
            final byte[] text = (found != null) ? history.read(historyId) : null;
            final PurpleSyncSettingsFile.Contents local = session.env.readSettings();
            final PurpleSyncCore.Diff diff = (text != null
                    && local.status != PurpleSyncSettingsFile.Status.Invalid)
                    ? PurpleSyncCore.diff(local.bytes, text) : null;
            final Preview preview = (text != null)
                    ? new Preview(found, text, local, diff) : null;
            ui.post(() -> {
                if (!closed && session.available()) {
                    done.onResult(preview);
                }
            });
        });
        return true;
    }

    public boolean diff(byte[] before, byte[] after,
            Listener<PurpleSyncCore.Diff> done) {
        if (closed) {
            return false;
        }
        queue.post(() -> {
            final PurpleSyncCore.Diff diff = PurpleSyncCore.diff(before, after);
            ui.post(() -> {
                if (!closed && session.available()) {
                    done.onResult(diff);
                }
            });
        });
        return true;
    }

    public void suspend(){stop(false);}
    public void close(){stop(true);}
    private void stop(boolean permanently){
        if(closed)return;
        if(permanently)closed=true;
        ++generation;
        end();
        current = null;
        if (checking) {
            checking = false;
            transport.cancel();
        }
        final PurpleSyncPublisher running = publisher;
        publisher = null;
        if (running != null) {
            running.abandon();
            queue.post(running::close);
        }
    }

    static <T> T onThread(Poster poster, Task<T> task, T abandoned,
            long timeoutMs) {
        final AtomicBoolean claimed = new AtomicBoolean();
        final CountDownLatch finished = new CountDownLatch(1);
        final Holder<T> holder = new Holder<>();
        poster.post(() -> {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                holder.value = task.run();
            } catch (RuntimeException e) {
                FileLog.e(e);
            } finally {
                finished.countDown();
            }
        });
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (finished.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                        return holder.value;
                    }
                    if (claimed.compareAndSet(false, true)) {
                        return abandoned;
                    }
                    finished.await();
                    return holder.value;
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (claimed.compareAndSet(false, true)) {
                        return abandoned;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static PurpleSyncSettingsFile.WriteResult replaceOn(Poster ui,
            Task<PurpleSyncSettingsFile.WriteResult> replace,
            Task<PurpleSyncSettingsFile.Contents> readBack) {
        final PurpleSyncSettingsFile.WriteResult result = onThread(ui, replace,
                new PurpleSyncSettingsFile.WriteResult(
                        PurpleSyncSettingsFile.WriteStatus.WriteFailed, null),
                UI_WRITE_TIMEOUT_MS);
        return (result != null) ? result : new PurpleSyncSettingsFile.WriteResult(
                PurpleSyncSettingsFile.WriteStatus.ReadBackMismatch,
                readBack.run());
    }

    private String operationKey() { return session.env.operationKey(session.account, session.userId); }
    private boolean begin() {
        Object owner=new Object(),previousOwner=operationOwner;
        PurpleSyncOperations.Capability previous=operationCapability;
        long run=generation;
        PurpleSyncOperations.Capability capability=PurpleSyncOperations.acquire(operationKey(),owner,()->!closed&&session.available(),()->ui.post(this::suspend));
        if(capability==null)return false;
        boolean installed=false;
        try {
            if(closed||busy||generation!=run||operationOwner!=previousOwner||operationCapability!=previous||!session.available())return false;
            operationOwner=owner;operationCapability=capability;busy=true;installed=true;return true;
        } finally {
            if(!installed){capability.revoke();PurpleSyncOperations.end(capability.key,owner,capability);}
        }
    }
    private void end() {
        Object owner=operationOwner;
        PurpleSyncOperations.Capability capability=operationCapability;
        if(capability!=null)capability.revoke();
        try {session.env.cancelChat(owner);}
        finally {busy=false;if(capability!=null)PurpleSyncOperations.end(capability.key,owner,capability);}
    }

    private Start refusal(boolean needsAccount) {
        if (closed) {
            return Start.Closed;
        } else if (!session.available()) {
            return Start.AccountUnavailable;
        } else if (busy()) {
            return Start.Busy;
        } else if (needsAccount && !session.available()) {
            return Start.AccountUnavailable;
        }
        return null;
    }

    private boolean live(long started) {
        return !closed && started == generation && session.available()
               ;
    }

    private Check issue(PurpleSyncApply.Reviewed reviewed,
            PurpleSyncInventory inventory, boolean sendQueued) {
        current = new Check(++generation, reviewed, inventory, sendQueued);
        return current;
    }

    private void checkTransport(long started,PurpleSyncTransport.Progress progress,PurpleSyncTransport.CheckDone done) {
        session.env.resolveChat(false,operationOwner,operationKey(),()->live(started),ready->{
            if(!live(started))return;
            if(ready)transport.check(progress,done);
            else done.onCheckDone(new PurpleSyncTransport.CheckResult(PurpleSyncTransport.CheckStatus.SyncChatHeld,null,false));
        });
    }

    private void onChecked(long started, PurpleSyncTransport.CheckResult result,
            Listener<CheckOutcome> done) {
        if (!live(started)) {
            return;
        }
        if (result.status != PurpleSyncTransport.CheckStatus.Finished) {
            checking = false;
            end();
            done.onResult(new CheckOutcome(
                    result.status == PurpleSyncTransport.CheckStatus.AccountChanged
                            ? CheckStatus.AccountUnavailable : result.status==PurpleSyncTransport.CheckStatus.SyncChatHeld ? CheckStatus.SyncChatHeld : CheckStatus.Cancelled,
                    null));
            return;
        }
        final PurpleSyncInventory inventory = result.inventory;
        final boolean sendQueued = result.sendQueued;
        FileLog.d("Purple: settings sync check read " + inventory.summary()
                + (sendQueued ? ", with a sync post still in Telegram's queue."
                        : "."));
        final Object owner=operationOwner;
        final PurpleSyncOperations.Capability capability=operationCapability;
        final Session reviewing=new Session(session.account,session.userId,session.env,
                ()->capability.current()&&operationCapability==capability&&operationOwner==owner&&live(started));
        queue.post(() -> {
            if(!reviewing.available())return;
            final PurpleSyncApply.Reviewed reviewed =
                    PurpleSyncApply.reviewWithAdopt(reviewing, inventory);
            ui.post(() -> {
                if (!live(started)) {
                    return;
                }
                checking = false;
                end();
                done.onResult(new CheckOutcome(CheckStatus.Finished,
                        issue(reviewed, inventory, sendQueued)));
            });
        });
    }

    private Start startPublish(PurpleSyncInventory inventory, boolean sendQueued,
            PurpleSyncCore.PostRequest request, java.util.function.BooleanSupplier effects,
            Listener<PurpleSyncPublisher.Result> done) {
        final long started = ++generation;
        if (!begin()) return Start.Busy;
        current = null;
        final Object owner=operationOwner;
        final PurpleSyncOperations.Capability capability=operationCapability;
        final Holder<PurpleSyncPublisher> self = new Holder<>();
        final java.util.concurrent.atomic.AtomicBoolean sourceAdmitted=new java.util.concurrent.atomic.AtomicBoolean();
        final PurpleSyncTransport.PostAuthority authority=new PurpleSyncTransport.PostAuthority(){
            public void waiting(PurpleRpcRetry.Transient value){if(live(started))PurpleSyncRunner.this.waiting(value);}
            public boolean current(){return capability.current()&&operationCapability==capability&&operationOwner==owner&&live(started)&&session.available()
                    &&(request.pendingOnly||(session.env.workerEffects()?sourceAdmitted.get():effects.getAsBoolean()));}
            public void check(java.util.function.Consumer<Boolean> done){
                session.env.checkEffects(effects,source->{
                    sourceAdmitted.set(Boolean.TRUE.equals(source));
                    if(!live(started)||!Boolean.TRUE.equals(source)){done.accept(false);return;}
                    session.env.resolveChat(true,operationOwner,operationKey(),()->live(started),ready->{
                        if(!Boolean.TRUE.equals(ready)||!live(started)){sourceAdmitted.set(false);done.accept(false);return;}
                        session.env.checkEffects(effects,fresh->{sourceAdmitted.set(Boolean.TRUE.equals(fresh));done.accept(Boolean.TRUE.equals(fresh)&&current());});
                    });
                });
            }
        };
        final PurpleSyncPublisher running = new PurpleSyncPublisher(session,
                inventory, sendQueued, request, transport, queue, effects,authority,result -> ui.post(() -> {
                    if (publisher == self.value) {
                        publisher = null;
                    }
                    if (!live(started)) {
                        return;
                    }
                    end();
                    done.onResult(result);
                }));
        self.value = running;
        publisher = running;
        authority.check(ready->{
            if(!live(started))return;
            if(Boolean.TRUE.equals(ready))queue.post(running::start);
            else { publisher=null;end();if(live(started))done.onResult(new PurpleSyncPublisher.Result(PurpleSyncCore.PublishStatus.NeedsReview,null,0,0)); }
        });
        return Start.Started;
    }

    private Start startRestore(String historyId, PurpleSyncHistory.Reason reason,
            Preview preview, Listener<RestoreOutcome> done) {
        final PurpleSyncInventory inventory =
                (current != null) ? current.inventory : null;
        final boolean sendQueued = current != null && current.sendQueued;
        final long started = ++generation;
        if (!begin()) return Start.Busy;
        current = null;
        final Object owner=operationOwner;
        final PurpleSyncOperations.Capability capability=operationCapability;
        final Session restoring=new Session(session.account,session.userId,session.env,
                ()->capability.current()&&operationCapability==capability&&operationOwner==owner&&live(started));
        queue.post(() -> {
            if (!restoring.available()) {
                ui.post(() -> { if (!closed && generation == started) end(); });
                return;
            }
            final PurpleSyncApply.RestoreResult result =
                    PurpleSyncApply.restore(restoring, historyId, reason, preview.current, preview.text);
            final boolean finished = reason == PurpleSyncHistory.Reason.BeforeUndo
                    && undoFinished(result.status);
            final PurpleSyncApply.Reviewed next =
                    (inventory != null && session.available())
                            ? PurpleSyncApply.reviewWithAdopt(restoring, inventory)
                            : null;
            ui.post(() -> {
                if (!live(started)) return;
                if (!closed) {
                    if (reason == PurpleSyncHistory.Reason.BeforeUndo) {
                        UndoOffer undo = PurpleSyncOperations.undo(operationKey());
                        if (finished && undo != null
                                && undo.historyId.equals(historyId)) {
                            PurpleSyncOperations.undo(operationKey(), null);
                        }
                    } else if (result.status == PurpleSyncCore.RestoreStatus.Restored) {
                        PurpleSyncOperations.undo(operationKey(), null);
                    }
                }
                if (!live(started)) {
                    return;
                }
                end();
                final Check check = (next != null)
                        ? issue(next, inventory, sendQueued) : null;
                done.onResult(new RestoreOutcome(result, finished, check));
            });
        });
        return Start.Started;
    }

    private static boolean undoFinished(PurpleSyncCore.RestoreStatus status) {
        final PurpleSyncCore.UndoCheck checked = PurpleSyncCore.undoFinished(status);
        return checked.isValid() && checked.finished;
    }

    private static boolean publishes(PurpleSyncCore.Verdict verdict) {
        switch (verdict) {
            case Choose:
            case Conflict:
            case Empty:
            case LocalChanges:
                return true;
            default:
                return false;
        }
    }

    private static boolean freshCheckFirst(PurpleSyncCore.Verdict verdict) {
        return verdict == PurpleSyncCore.Verdict.Choose
                || verdict == PurpleSyncCore.Verdict.Conflict;
    }

    private static synchronized Poster sharedQueue() {
        if (sharedQueue == null) {
            sharedQueue = new DispatchQueue("purpleSyncQueue");
        }
        final DispatchQueue queue = sharedQueue;
        return queue::postRunnable;
    }

    static class Platform implements Env {
        final PurpleSyncFileContext context;

        Platform(int account) { context = new PurpleSyncFileContext(account); }
        Platform(PurpleSyncFileContext context) { this.context = context; }
        public void resolveChat(boolean beforePost,Object owner,String key,java.util.function.BooleanSupplier current,java.util.function.Consumer<Boolean> done) {
            PurpleSyncChat.underLock(context,owner,key,beforePost,current,status->done.accept(beforePost?status.selfPostable():status.selfReadable()));
        }
        public void cancelChat(Object owner) { if(context.origin!=null)PurpleSyncChat.cancelOperation(context.origin.slot,owner); }

        @Override
        public boolean current(int account, long userId) {
            return context.current() && context.origin.slot == account && context.origin.userId == userId;
        }
        @Override
        public String operationKey(int account, long userId) {
            return context.origin != null && context.files != null
                    ? context.origin.key + "\n" + context.files.syncDirectory.getPath() : "unavailable:" + account;
        }

        @Override
        public boolean requiresRecovery() { return true; }
        @Override
        public PurpleSyncRecoveryModel.Offer recovery(PurpleSyncInventory inventory,PurpleSyncSettingsFile.Contents local) {
            return PurpleSyncRecovery.check(context,inventory,local);
        }
        @Override
        public boolean sendQueued() { return context.current()&&PurpleSendReceipts.hasModernWork(context.origin.slot); }

        @Override
        public String device() {
            return PurpleDevice.id();
        }

        @Override
        public long serverTimeMillis(int account) {
            return ConnectionsManager.getInstance(account).getCurrentTimeMillis();
        }

        @Override
        public boolean hasState() {
            return context.current() && PurpleAccountSyncStore.hasState(context.files.syncDirectory);
        }

        @Override
        public PurpleAccountSyncStore store() {
            return new PurpleAccountSyncStore(context);
        }

        @Override
        public PurpleSyncHistory history() {
            return new PurpleSyncHistory(context);
        }

        @Override
        public PurpleSyncSettingsFile.Contents readSettings() {
            return PurpleSyncSettingsFile.read(context);
        }

        @Override
        public PurpleSyncSettingsFile.WriteResult replaceSettings(
                PurpleSyncSettingsFile.Contents expected, byte[] bytes,
                String reason, boolean fromImport) {
            return replaceOn(AndroidUtilities::runOnUIThread,
                    () -> PurpleSyncSettingsFile.replace(context, expected, bytes, reason,
                            fromImport),
                    () -> PurpleSyncSettingsFile.read(context));
        }
    }
}
