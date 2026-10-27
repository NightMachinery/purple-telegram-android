/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;

public final class PurpleSyncPublisher {
    public static final class Result {
        public final PurpleSyncCore.PublishStatus status;
        public final PurpleAccountSyncStore.Status storeStatus;
        public final int messageId;
        public final int posts;
        public final PurpleRpcRetry.Transient waiting;

        Result(PurpleSyncCore.PublishStatus status,
                PurpleAccountSyncStore.Status storeStatus, int messageId,
                int posts) {
            this(status,storeStatus,messageId,posts,null);
        }
        Result(PurpleSyncCore.PublishStatus status,PurpleAccountSyncStore.Status storeStatus,int messageId,int posts,PurpleRpcRetry.Transient waiting){
            this.waiting=waiting;
            this.status = status;
            this.storeStatus = storeStatus;
            this.messageId = messageId;
            this.posts = posts;
        }
    }

    interface Finished {
        void onFinished(Result result);
    }

    private final PurpleSyncRunner.Session session;
    private final PurpleSyncInventory inventory;
    private final boolean sendQueued;
    private final PurpleSyncCore.PostRequest request;
    private final PurpleSyncTransport transport;
    private final PurpleSyncRunner.Poster queue;
    private final Finished finished;
    private final java.util.function.BooleanSupplier effects;
    private volatile boolean abandoned;
    private PurpleAccountSyncStore store;
    private boolean started;
    private boolean done;
    private boolean posting;
    private int posts;
    private final PurpleSyncTransport.PostAuthority authority;

    PurpleSyncPublisher(PurpleSyncRunner.Session session,
            PurpleSyncInventory inventory, boolean sendQueued,
            PurpleSyncCore.PostRequest request, PurpleSyncTransport transport,
            PurpleSyncRunner.Poster queue, Finished finished) {
        this(session,inventory,sendQueued,request,transport,queue,()->true,finished);
    }
    PurpleSyncPublisher(PurpleSyncRunner.Session session,PurpleSyncInventory inventory,boolean sendQueued,
            PurpleSyncCore.PostRequest request,PurpleSyncTransport transport,PurpleSyncRunner.Poster queue,
            java.util.function.BooleanSupplier effects,Finished finished) {
        this(session,inventory,sendQueued,request,transport,queue,effects,null,finished);
    }
    PurpleSyncPublisher(PurpleSyncRunner.Session session,PurpleSyncInventory inventory,boolean sendQueued,
            PurpleSyncCore.PostRequest request,PurpleSyncTransport transport,PurpleSyncRunner.Poster queue,
            java.util.function.BooleanSupplier effects,PurpleSyncTransport.PostAuthority authority,Finished finished) {
        this.authority=authority;
        this.effects=effects;
        this.session = session;
        this.inventory = inventory;
        this.sendQueued = sendQueued;
        this.request = request;
        this.transport = transport;
        this.queue = queue;
        this.finished = finished;
    }

    private boolean current() { return !abandoned&&(authority==null||authority.current())
            &&session.available()&&(request.pendingOnly||effects.getAsBoolean()); }

    void start() {
        if (started || done) {
            return;
        }
        started = true;
        if (abandoned) {
            finish(PurpleSyncCore.PublishStatus.Cancelled);
            return;
        } else if (!current()
                || inventory.accountUserId != session.userId) {
            finish(PurpleSyncCore.PublishStatus.AccountUnavailable);
            return;
        }
        final PurpleSyncRunner.Env env = session.env;
        if (!env.hasState()) {
            finish(PurpleSyncCore.PublishStatus.StoreError,
                    PurpleAccountSyncStore.Status.Uninitialized);
            return;
        }
        store = env.store().require(this::current);
        final PurpleAccountSyncStore.Result opened =
                store.open(true, session.account, env.device());
        if (opened.status == PurpleAccountSyncStore.Status.AccountUnbound) {
            finish(PurpleSyncCore.PublishStatus.AccountUnbound);
            return;
        } else if (opened.status != PurpleAccountSyncStore.Status.Ready) {
            finish(PurpleSyncCore.PublishStatus.StoreError, opened.status);
            return;
        }
        final byte[] state = store.stateBytes();
        final PurpleSyncApply.Pending pending = PurpleSyncApply.readPending(store);
        if (state == null || !pending.ok()) {
            finish(PurpleSyncCore.PublishStatus.StoreError, state == null
                    ? PurpleAccountSyncStore.Status.InvalidState : pending.status);
            return;
        }
        final PurpleSyncCore.EntryPlan entry =
                PurpleSyncCore.planPostEntry(request, pending.staged != null);
        if (!entry.isValid() || entry.entry == PurpleSyncCore.PostEntry.Refuse) {
            finish(PurpleSyncCore.PublishStatus.NeedsReview);
            return;
        }
        final PurpleSyncSettingsFile.Contents local =
                (entry.entry == PurpleSyncCore.PostEntry.NewContent)
                        ? env.readSettings() : PurpleSyncApply.noFile();
        final long nowSeconds = env.serverTimeMillis(session.account) / 1000L;
        if (!current()) {
            finish(PurpleSyncCore.PublishStatus.AccountUnavailable);
            return;
        }
        final PurpleSyncCore.PostPlan plan = PurpleSyncCore.planPost(
                session.account, inventory, state, pending.staged, local, request,
                nowSeconds, sendQueued);
        if (!plan.isValid()) {
            finish(planFailure(plan.error));
            return;
        }
        switch (plan.step) {
            case Finish:
                finish(plan.status);
                return;
            case ConfirmFound:
                confirm(plan.record, plan.messageId);
                return;
            case Stage:
                stage(plan);
                return;
            case Post:
                post(plan.record);
                return;
        }
        finish(PurpleSyncCore.PublishStatus.NeedsReview);
    }

    void abandon() {
        abandoned = true;
    }

    void close() {
        abandoned = true;
        if (!done) {
            done = true;
            if (posting) {
                transport.cancel();
            }
        }
        closeStore();
    }

    private void confirm(byte[] record, int messageId) {
        if (!current()) {
            finish(PurpleSyncCore.PublishStatus.AccountUnavailable);
            return;
        }
        final PurpleAccountSyncStore.Result confirmed =
                store.confirmConfigReadBack(inventory.chat,record, messageId);
        if (confirmed.status == PurpleAccountSyncStore.Status.Ready) {
            finish(new Result(PurpleSyncCore.PublishStatus.Confirmed, null,
                    messageId, posts));
        } else {
            finish(PurpleSyncCore.PublishStatus.StoreError, confirmed.status);
        }
    }

    private void stage(PurpleSyncCore.PostPlan plan) {
        if (!current()) {
            finish(PurpleSyncCore.PublishStatus.AccountUnavailable);
            return;
        }
        final PurpleAccountSyncCore.Result reserved =
                PurpleAccountSyncCore.reserveConfigRecord(session.account,
                        store.stateBytes(), plan.record);
        if (!reserved.isValid() || reserved.state == null) {
            final PurpleSyncCore.PublishStatus status = planFailure(reserved.error);
            if (status == PurpleSyncCore.PublishStatus.NeedsReview) {
                finish(PurpleSyncCore.PublishStatus.StoreError,
                        PurpleAccountSyncStore.Status.InvalidRecord);
            } else {
                finish(status);
            }
            return;
        } else if (!plan.key.equals(reserved.key)) {
            finish(PurpleSyncCore.PublishStatus.StoreError,
                    PurpleAccountSyncStore.Status.InvalidRecord);
            return;
        }
        final PurpleAccountSyncStore.Result staged =
                store.stageConfig(plan.record, reserved.state,this::current);
        if (staged.status != PurpleAccountSyncStore.Status.Ready
                || staged.staged == null) {
            finish(PurpleSyncCore.PublishStatus.StoreError, staged.status);
            return;
        } else if (!current()) {
            finish(PurpleSyncCore.PublishStatus.AccountUnavailable);
            return;
        }
        final PurpleSyncCore.PostPlan checked = PurpleSyncCore.checkStagedPost(
                session.account, inventory, store.stateBytes(), staged.staged);
        if (!checked.isValid()) {
            finish(planFailure(checked.error));
        } else if (checked.step != PurpleSyncCore.PostStep.Post) {
            finish(checked.status);
        } else {
            post(checked.record);
        }
    }

    private void post(byte[] staged) {
        if (abandoned) {
            finish(PurpleSyncCore.PublishStatus.Cancelled);
            return;
        } else if (!current()) {
            finish(PurpleSyncCore.PublishStatus.AccountUnavailable);
            return;
        }
        posting = true;
        ++posts;
        FileLog.d("Purple: posting a settings sync record of " + staged.length
                + " bytes.");
        PurpleSyncTransport.PostAuthority gate=authority!=null?authority:new PurpleSyncTransport.PostAuthority(){
            public boolean current(){return PurpleSyncPublisher.this.current();}
            public void check(java.util.function.Consumer<Boolean> done){done.accept(current());}
        };
        transport.post(staged,gate,result -> queue.post(() -> onPost(result)));
    }

    private void onPost(PurpleSyncTransport.PostResult result) {
        if (done) {
            return;
        }
        posting = false;
        if (result.status == PurpleSyncTransport.PostStatus.Confirmed) {
            if (!current() || !inventory.chat.equals(result.chat)
                    || !java.util.Objects.equals(inventory.origin,result.origin)) {
                finish(PurpleSyncCore.PublishStatus.OutcomeUnknown);
                return;
            }
            confirm(result.readBack, result.messageId);
        } else if (result.status == PurpleSyncTransport.PostStatus.NeedsReview
                || result.status == PurpleSyncTransport.PostStatus.InvalidRecord) {
            finish(PurpleSyncCore.PublishStatus.NeedsReview);
        } else {
            finish(new Result(PurpleSyncCore.PublishStatus.OutcomeUnknown,null,0,posts,result.waiting));
        }
    }

    private static PurpleSyncCore.PublishStatus planFailure(String error) {
        if ("AccountChanged".equals(error) || "AccountUnavailable".equals(error)) {
            return PurpleSyncCore.PublishStatus.AccountUnavailable;
        } else if ("BindingUnavailable".equals(error)
                || "InvalidBindingToken".equals(error)) {
            return PurpleSyncCore.PublishStatus.AccountUnbound;
        }
        FileLog.e("Purple: settings sync post plan failed: " + error);
        return PurpleSyncCore.PublishStatus.NeedsReview;
    }

    private void finish(PurpleSyncCore.PublishStatus status) {
        finish(new Result(status, null, 0, posts));
    }

    private void finish(PurpleSyncCore.PublishStatus status,
            PurpleAccountSyncStore.Status storeStatus) {
        finish(new Result(status, storeStatus, 0, posts));
    }

    private void finish(Result result) {
        if (done) {
            return;
        }
        done = true;
        closeStore();
        FileLog.d("Purple: settings sync publish finished: " + result.status
                + (result.storeStatus != null ? " (" + result.storeStatus + ")" : ""));
        finished.onFinished(result);
    }

    private void closeStore() {
        if (store != null) {
            store.close();
            store = null;
        }
    }
}
