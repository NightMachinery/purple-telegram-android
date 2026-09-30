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

    public enum CheckStatus { Finished, Cancelled, AccountUnavailable }

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

        Session(int account, long userId, Env env) {
            this.account = account;
            this.userId = userId;
            this.env = env;
        }

        boolean available() {
            return userId > 0 && PurpleAccountBinding.isSameActiveUser(account, userId);
        }
    }

    public static final class Check {
        public final PurpleSyncCore.Review review;
        public final PurpleSyncSettingsFile.Contents local;
        public final PurpleSyncApply.ApplyResult adoptFailure;
        public final boolean sendQueued;
        final PurpleSyncInventory inventory;
        final long generation;

        Check(long generation, PurpleSyncApply.Reviewed reviewed,
                PurpleSyncInventory inventory, boolean sendQueued) {
            this.generation = generation;
            this.review = reviewed.review;
            this.local = reviewed.local;
            this.adoptFailure = reviewed.adoptFailure;
            this.inventory = inventory;
            this.sendQueued = sendQueued;
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

        PostTicket(PurpleSyncCore.PostRequest request, boolean freshCheckFirst,
                PurpleSyncInventory inventory, boolean sendQueued,
                long generation) {
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

        Preview(PurpleSyncHistory.Entry entry, byte[] text,
                PurpleSyncSettingsFile.Contents current, PurpleSyncCore.Diff diff) {
            this.entry = entry;
            this.text = text;
            this.current = current;
            this.diff = diff;
        }
    }

    private static final class Holder<T> {
        T value;
    }

    private static DispatchQueue sharedQueue;

    private final Session session;
    private final PurpleSyncTransport transport;
    private final Poster queue;
    private final Poster ui;
    private long generation;
    private boolean busy;
    private boolean checking;
    private boolean closed;
    private Check current;
    private UndoOffer undo;
    private PurpleSyncPublisher publisher;

    public PurpleSyncRunner(int account, PurpleSyncTransport transport) {
        this(account, PurpleAccountBinding.activeUserId(account), transport,
                new Platform(), sharedQueue(), AndroidUtilities::runOnUIThread);
    }

    PurpleSyncRunner(int account, long userId, PurpleSyncTransport transport,
            Env env, Poster queue, Poster ui) {
        if (transport == null || env == null || queue == null || ui == null) {
            throw new IllegalArgumentException("incomplete sync runner");
        }
        this.session = new Session(account, userId, env);
        this.transport = transport;
        this.queue = queue;
        this.ui = ui;
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
        return busy;
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
        return (busy || closed) ? null : undo;
    }

    public Start check(PurpleSyncTransport.Progress progress,
            Listener<CheckOutcome> done) {
        final Start refused = refusal(true);
        if (refused != null) {
            return refused;
        }
        final long started = ++generation;
        busy = true;
        checking = true;
        current = null;
        transport.check((phase, count, total) -> {
            if (live(started) && progress != null) {
                progress.onProgress(phase, count, total);
            }
        }, result -> onChecked(started, result, done));
        return Start.Started;
    }

    public boolean cancel() {
        if (closed || !checking) {
            return false;
        }
        ++generation;
        busy = false;
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
                || shown.generation != generation) {
            return Start.Stale;
        }
        final long started = ++generation;
        busy = true;
        current = null;
        final PurpleSyncCore.Verdict verdict = shown.review.verdict;
        queue.post(() -> {
            final PurpleSyncApply.ApplyResult result = PurpleSyncApply.apply(
                    session, shown.inventory, shown.review, chosenKey);
            final PurpleSyncCore.ApplyFailure failure =
                    PurpleSyncCore.describeApplyFailure(result.status,
                            result.joined, result.wroteFile, result.historyKept(),
                            result.undoAvailable, result.otherVersionsRemain);
            final boolean posts = result.status == PurpleSyncCore.ApplyStatus.Applied
                    && result.publishNeeded && publishes(verdict);
            final PurpleSyncApply.Reviewed next =
                    (!posts && result.status == PurpleSyncCore.ApplyStatus.Applied)
                            ? PurpleSyncApply.reviewWithAdopt(session, shown.inventory)
                            : null;
            ui.post(() -> {
                if (!closed && result.wroteFile) {
                    undo = result.undoAvailable
                            ? new UndoOffer(result.historyId,
                                    result.source != null ? result.source.name : null)
                            : null;
                }
                if (!live(started)) {
                    return;
                }
                busy = false;
                PostTicket ticket = null;
                Check check = null;
                if (posts) {
                    ticket = new PostTicket(
                            PurpleSyncCore.PostRequest.newContent(result.fingerprint,
                                    result.expectedParents),
                            freshCheckFirst(verdict), shown.inventory,
                            shown.sendQueued, ++generation);
                } else if (next != null) {
                    check = issue(next, shown.inventory, shown.sendQueued);
                }
                done.onResult(new ApplyOutcome(result, failure, check, ticket));
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
        return startPublish(fresh.inventory, fresh.sendQueued, request, done);
    }

    public Start publish(PostTicket ticket,
            Listener<PurpleSyncPublisher.Result> done) {
        final Start refused = refusal(true);
        if (refused != null) {
            return refused;
        } else if (ticket == null || ticket.freshCheckFirst
                || ticket.generation != generation) {
            return Start.Stale;
        }
        return startPublish(ticket.inventory, ticket.sendQueued, ticket.request,
                done);
    }

    public Start undo(UndoOffer offer, Listener<RestoreOutcome> done) {
        final Start refused = refusal(false);
        if (refused != null) {
            return refused;
        } else if (offer == null || undo == null
                || !undo.historyId.equals(offer.historyId)) {
            return Start.Stale;
        }
        return startRestore(offer.historyId, PurpleSyncHistory.Reason.BeforeUndo,
                done);
    }

    public Start restore(String historyId, Listener<RestoreOutcome> done) {
        final Start refused = refusal(false);
        if (refused != null) {
            return refused;
        } else if (historyId == null) {
            return Start.Stale;
        }
        return startRestore(historyId, PurpleSyncHistory.Reason.BeforeRestore,
                done);
    }

    public boolean history(Listener<List<PurpleSyncHistory.Entry>> done) {
        if (closed) {
            return false;
        }
        queue.post(() -> {
            final List<PurpleSyncHistory.Entry> entries = session.env.history().list();
            ui.post(() -> {
                if (!closed) {
                    done.onResult(Collections.unmodifiableList(entries));
                }
            });
        });
        return true;
    }

    public boolean preview(String historyId, Listener<Preview> done) {
        if (closed || historyId == null) {
            return false;
        }
        queue.post(() -> {
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
                if (!closed) {
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
                if (!closed) {
                    done.onResult(diff);
                }
            });
        });
        return true;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ++generation;
        busy = false;
        current = null;
        undo = null;
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

    private Start refusal(boolean needsAccount) {
        if (closed) {
            return Start.Closed;
        } else if (busy) {
            return Start.Busy;
        } else if (needsAccount && !session.available()) {
            return Start.AccountUnavailable;
        }
        return null;
    }

    private boolean live(long started) {
        return !closed && started == generation;
    }

    private Check issue(PurpleSyncApply.Reviewed reviewed,
            PurpleSyncInventory inventory, boolean sendQueued) {
        current = new Check(++generation, reviewed, inventory, sendQueued);
        return current;
    }

    private void onChecked(long started, PurpleSyncTransport.CheckResult result,
            Listener<CheckOutcome> done) {
        if (!live(started)) {
            return;
        }
        checking = false;
        if (result.status != PurpleSyncTransport.CheckStatus.Finished) {
            busy = false;
            done.onResult(new CheckOutcome(
                    result.status == PurpleSyncTransport.CheckStatus.AccountChanged
                            ? CheckStatus.AccountUnavailable : CheckStatus.Cancelled,
                    null));
            return;
        }
        final PurpleSyncInventory inventory = result.inventory;
        final boolean sendQueued = result.sendQueued;
        FileLog.d("Purple: settings sync check read " + inventory.summary()
                + (sendQueued ? ", with a sync post still in Telegram's queue."
                        : "."));
        queue.post(() -> {
            final PurpleSyncApply.Reviewed reviewed =
                    PurpleSyncApply.reviewWithAdopt(session, inventory);
            ui.post(() -> {
                if (!live(started)) {
                    return;
                }
                busy = false;
                done.onResult(new CheckOutcome(CheckStatus.Finished,
                        issue(reviewed, inventory, sendQueued)));
            });
        });
    }

    private Start startPublish(PurpleSyncInventory inventory, boolean sendQueued,
            PurpleSyncCore.PostRequest request,
            Listener<PurpleSyncPublisher.Result> done) {
        final long started = ++generation;
        busy = true;
        current = null;
        final Holder<PurpleSyncPublisher> self = new Holder<>();
        final PurpleSyncPublisher running = new PurpleSyncPublisher(session,
                inventory, sendQueued, request, transport, queue,
                result -> ui.post(() -> {
                    if (publisher == self.value) {
                        publisher = null;
                    }
                    if (!live(started)) {
                        return;
                    }
                    busy = false;
                    done.onResult(result);
                }));
        self.value = running;
        publisher = running;
        queue.post(running::start);
        return Start.Started;
    }

    private Start startRestore(String historyId, PurpleSyncHistory.Reason reason,
            Listener<RestoreOutcome> done) {
        final PurpleSyncInventory inventory =
                (current != null) ? current.inventory : null;
        final boolean sendQueued = current != null && current.sendQueued;
        final long started = ++generation;
        busy = true;
        current = null;
        queue.post(() -> {
            final PurpleSyncApply.RestoreResult result =
                    PurpleSyncApply.restore(session.env, historyId, reason);
            final boolean finished = reason == PurpleSyncHistory.Reason.BeforeUndo
                    && undoFinished(result.status);
            final PurpleSyncApply.Reviewed next =
                    (inventory != null && session.available())
                            ? PurpleSyncApply.reviewWithAdopt(session, inventory)
                            : null;
            ui.post(() -> {
                if (!closed) {
                    if (reason == PurpleSyncHistory.Reason.BeforeUndo) {
                        if (finished && undo != null
                                && undo.historyId.equals(historyId)) {
                            undo = null;
                        }
                    } else if (result.status == PurpleSyncCore.RestoreStatus.Restored) {
                        undo = null;
                    }
                }
                if (!live(started)) {
                    return;
                }
                busy = false;
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

    static final class Platform implements Env {
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
            return PurpleAccountSyncStore.hasState();
        }

        @Override
        public PurpleAccountSyncStore store() {
            return new PurpleAccountSyncStore();
        }

        @Override
        public PurpleSyncHistory history() {
            return new PurpleSyncHistory();
        }

        @Override
        public PurpleSyncSettingsFile.Contents readSettings() {
            return PurpleSyncSettingsFile.read();
        }

        @Override
        public PurpleSyncSettingsFile.WriteResult replaceSettings(
                PurpleSyncSettingsFile.Contents expected, byte[] bytes,
                String reason, boolean fromImport) {
            return replaceOn(AndroidUtilities::runOnUIThread,
                    () -> PurpleSyncSettingsFile.replace(expected, bytes, reason,
                            fromImport),
                    PurpleSyncSettingsFile::read);
        }
    }
}
