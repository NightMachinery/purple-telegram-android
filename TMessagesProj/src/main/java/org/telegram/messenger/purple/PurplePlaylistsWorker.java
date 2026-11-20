package org.telegram.messenger.purple;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class PurplePlaylistsWorker implements AutoCloseable {
    enum Admission { Accepted, Busy, Closed }
    enum Status { Ready, Canceled, Closed, Changed, Unavailable, InvalidRemote, InvalidEdit, Failed }
    enum Termination { Running, Closed, CloseFailed }
    private enum Operation { Read, Sync, Merge, Edit, Preference, RemoveDownloads, PruneDownloads }

    interface Completion<T> { void complete(T view, Outcome outcome); }
    interface Opener {
        PurplePlaylistsStore open(PurplePlaylistsAccount.Captured captured, PurplePlaylistsStore.Current current)
                throws Exception;
    }

    static final class Target<T> {
        private final WeakReference<T> view;
        private final Executor dispatcher;
        private final PurplePlaylistsStore.Current current;
        private final Completion<T> completion;

        Target(T view, Executor dispatcher, PurplePlaylistsStore.Current current, Completion<T> completion) {
            if (view == null || dispatcher == null || current == null || completion == null)
                throw new IllegalArgumentException("Missing playlist completion target");
            this.view = new WeakReference<>(view);
            this.dispatcher = dispatcher;
            this.current = current;
            this.completion = completion;
        }

        private void dispatch(PurplePlaylistsWorker worker, long revision, Outcome outcome, Request request) {
            try {
                dispatcher.execute(() -> {
                    T value = view.get();
                    boolean canceledWithChanges = outcome.status == Status.Canceled
                            && (outcome.libraryMayHaveChanged || outcome.stateMayHaveChanged
                                || (outcome.cleanup != null && outcome.cleanup.filesMayHaveChanged));
                    if (value != null && worker.alive.get() && worker.captured.current()
                            && current.current() && (!request.canceled.get() || canceledWithChanges)
                            && (outcome.snapshot == null || !outcome.snapshot.request.canceled.get())) {
                        if (worker.revision.get() == revision) completion.complete(value, outcome);
                        else if (revision < worker.syncRevision.get())
                            completion.complete(value, failure(Status.Changed, null, "SyncSnapshotChanged"));
                    }
                });
            } catch (RuntimeException ignored) { }
        }
    }

    static final class Snapshot {
        private final PurplePlaylistsWorker owner;
        private final long revision;
        private final PurplePlaylistsStore.Read read;
        private final Request request;
        final PurplePlaylistsView view;
        final PurplePlaylistsDownloadsCore.Status downloadStatus;
        final PurplePlaylistsDownloadsCore.Preference preference;
        final Boolean keepDefault;
        final PurplePlaylistsRemoval.Result cleanup;
        private final PurpleAccountSettings.Effective keepSettings;
        boolean keepSettingsCurrent() { return keepSettings != null && keepSettings.current() && keepSettings.playlistsReady; }

        private Snapshot(PurplePlaylistsWorker owner, long revision, PurplePlaylistsStore.Read read, Request request) {
            this.owner = owner;
            this.revision = revision;
            this.read = read;
            this.request = request;
            view = PurplePlaylistsView.project(read.library(), read.state(), owner.captured.identity.userId, request.selectedId);
            if (!view.valid()) throw new IllegalStateException("Validated store projection refused");
            PurplePlaylistsDownloadsCore.Result downloads = PurplePlaylistsDownloadsCore.read(read.state(), owner.captured.identity.userId);
            downloadStatus = downloads.status;
            cleanup = downloads.ready() ? PurplePlaylistsRemoval.pending(read.library(), read.state(),
                    owner.captured.identity.userId, request.selectedId) : null;
            preference = downloads.ready() ? downloads.preference(request.selectedId) : null;
            keepSettings = PurpleAccountSettings.forAccount(owner.captured.identity.slot);
            keepDefault = keepSettingsCurrent() ? keepSettings.playlistsKeepDownloaded : null;
        }

        byte[] library() { return read.library(); }
        byte[] state() { return read.state(); }
    }

    static final class Undo {
        private final PurplePlaylistsWorker owner;
        final PurplePlaylistsEdits.Kind kind;
        final boolean redo;
        private Undo(PurplePlaylistsWorker owner, PurplePlaylistsEdits.Kind kind, boolean redo) {
            this.owner = owner;
            this.kind = kind;
            this.redo = redo;
        }
    }

    static final class Outcome {
        final Status status;
        final PurplePlaylistsStore.Status storeStatus;
        final String detail;
        final Snapshot snapshot;
        final boolean libraryCommitted;
        final boolean stateCommitted;
        final boolean libraryMayHaveChanged;
        final boolean stateMayHaveChanged;
        final PurplePlaylistsEdits.Result edit;
        final Undo undo;
        final PurplePlaylistsRemoval.Result cleanup;

        private Outcome(Status status, PurplePlaylistsStore.Status storeStatus, String detail,
                Snapshot snapshot, PurplePlaylistsStore.Write write) {
            this(status, storeStatus, detail, snapshot, write, null, null);
        }

        private Outcome(Status status, PurplePlaylistsStore.Status storeStatus, String detail,
                Snapshot snapshot, PurplePlaylistsStore.Write write, PurplePlaylistsEdits.Result edit, Undo undo) {
            this(status, storeStatus, detail, snapshot, write, edit, undo, null);
        }
        private Outcome(Status status, PurplePlaylistsStore.Status storeStatus, String detail,
                Snapshot snapshot, PurplePlaylistsStore.Write write, PurplePlaylistsEdits.Result edit, Undo undo,
                PurplePlaylistsRemoval.Result cleanup) {
            this.cleanup = cleanup != null ? cleanup : snapshot == null ? null : snapshot.cleanup;
            this.edit = edit;
            this.undo = undo;
            this.status = status;
            this.storeStatus = storeStatus;
            this.detail = detail;
            this.snapshot = snapshot;
            libraryCommitted = write != null && write.libraryCommitted;
            stateCommitted = write != null && write.stateCommitted;
            libraryMayHaveChanged = write != null && write.libraryMayHaveChanged;
            stateMayHaveChanged = write != null && write.stateMayHaveChanged || cleanup != null && cleanup.stateMayHaveChanged;
        }

        boolean ready() { return status == Status.Ready && snapshot != null; }
    }

    static final class Request {
        final Admission admission;
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final Operation operation;
        private final Snapshot expected;
        private final boolean enabled;
        private final PurplePlaylistsDownloadsCore.Preference preference;
        private byte[] remote;
        private final Target<?> target;
        private final PurplePlaylistsEdits.Command command;
        private final Undo undo;
        private final String selectedId;

        private Request(Admission admission, Operation operation, Snapshot expected,
                boolean enabled, byte[] remote, Target<?> target, PurplePlaylistsEdits.Command command, Undo undo, String selectedId, PurplePlaylistsDownloadsCore.Preference preference) {
            this.selectedId = selectedId; this.preference = preference;
            this.command = command;
            this.undo = undo;
            this.admission = admission;
            this.operation = operation;
            this.expected = expected;
            this.enabled = enabled;
            this.remote = remote;
            this.target = target;
        }

        void cancel() { canceled.set(true); }
    }

    interface StoreAction { void run(PurplePlaylistsStore store, long self) throws Exception; }
    private static final class StoreTask {
        final Snapshot expected;
        final StoreAction action;
        final Runnable refused;
        final boolean invalidateSnapshot;
        StoreTask(Snapshot expected, StoreAction action, Runnable refused, boolean invalidateSnapshot) {
            this.expected = expected; this.action = action; this.refused = refused; this.invalidateSnapshot = invalidateSnapshot;
        }
    }
    private final ArrayDeque<StoreTask> storeQueue = new ArrayDeque<>();
    interface DownloadOwner extends AutoCloseable { void wake(); default boolean active() { return false; } }
    private DownloadOwner downloadOwner;
    private boolean downloadSignaled;

    void signalDownloadOwner(DownloadOwner owner) {
        synchronized (lock) {
            if (!alive.get() || downloadOwner != owner) return;
            downloadSignaled = true; lock.notifyAll();
        }
    }

    boolean postStore(Snapshot expected, StoreAction action, Runnable refused) {
        return postStore(expected, action, refused, true);
    }
    boolean postStore(Snapshot expected, StoreAction action, Runnable refused, boolean invalidateSnapshot) {
        if (action == null || refused == null) throw new IllegalArgumentException("MissingStoreAction");
        synchronized (lock) {
            if (!alive.get() || storeQueue.size() >= 4) return false;
            storeQueue.addLast(new StoreTask(expected, action, refused, invalidateSnapshot));
            lock.notifyAll(); return true;
        }
    }
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> editObservers = new java.util.concurrent.CopyOnWriteArrayList<>();
    AutoCloseable observeEdits(Runnable listener) {
        if (listener == null) throw new IllegalArgumentException("MissingPlaylistEditObserver");
        editObservers.add(listener); return () -> editObservers.remove(listener);
    }
    private void edited() {
        for (Runnable listener : editObservers) { try { listener.run(); } catch (RuntimeException ignored) { } }
    }
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> syncObservers = new java.util.concurrent.CopyOnWriteArrayList<>();
    AutoCloseable observeSync(Runnable listener) {
        if (listener == null) throw new IllegalArgumentException("MissingPlaylistSyncObserver");
        syncObservers.add(listener);
        return () -> syncObservers.remove(listener);
    }
    void syncChanged(PurplePlaylistsStore.Write write) {
        if (Thread.currentThread() != thread) throw new IllegalStateException("PlaylistSyncThread");
        if (write == null || !write.libraryMayHaveChanged && !write.stateMayHaveChanged) return;
        invalidate();
        syncRevision.set(revision.get());
        if (write.libraryMayHaveChanged) forgetUndo();
        for (Runnable listener : syncObservers) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }
    int accountSlot() { return captured.identity.slot; }
    boolean downloadCurrent() { return alive.get() && captured.current(); }
    void bindDownloadOwner(DownloadOwner owner) {
        synchronized (lock) {
            if (Thread.currentThread() != thread || owner == null || (downloadOwner != null && downloadOwner != owner))
                throw new IllegalStateException("DownloadOwnerChanged");
            downloadOwner = owner;
        }
    }

    private static final int QUEUE_CAPACITY = 16;
    private final PurplePlaylistsAccount.Captured captured;
    private final Opener opener;
    private final Object lock = new Object();
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong syncRevision = new AtomicLong();
    private final Thread thread;
    private volatile Request active;
    private PurplePlaylistsStore store;
    private Snapshot latest;
    private PurplePlaylistsEdits editor;
    private Undo latestUndo;
    private volatile Termination termination = Termination.Running;

    PurplePlaylistsWorker(PurplePlaylistsAccount.Captured captured) {
        this(captured, PurplePlaylistsAccount::open);
    }

    PurplePlaylistsWorker(PurplePlaylistsAccount.Captured captured, Opener opener) {
        if (captured == null || !captured.current()) throw new IllegalArgumentException("Stale playlist account");
        if (opener == null) throw new IllegalArgumentException("Missing playlist store opener");
        this.captured = captured;
        this.opener = opener;
        thread = new Thread(this::run, "PurplePlaylists-" + captured.identity.slot);
        thread.start();
    }

    Request read(Target<?> target) { return read("", target); }
    Request read(String selectedId, Target<?> target) {
        if (selectedId == null) throw new IllegalArgumentException("Missing playlist selection");
        return submit(Operation.Read, null, false, null, target, null, null, selectedId);
    }
    Request removeDownloads(Snapshot expected, boolean prune, Target<?> target) {
        return submit(prune ? Operation.PruneDownloads : Operation.RemoveDownloads, expected, false, null, target);
    }
    Request setSync(Snapshot expected, boolean enabled, Target<?> target) {
        return submit(Operation.Sync, expected, enabled, null, target);
    }
    Request preference(Snapshot expected, PurplePlaylistsDownloadsCore.Preference value, Target<?> target) {
        if (value == null) throw new IllegalArgumentException("Missing keep preference");
        return submit(Operation.Preference, expected, false, null, target, null, null,
                expected == null ? "" : expected.view.selectedId, value);
    }
    Request merge(Snapshot expected, byte[] remote, Target<?> target) {
        if (remote == null) throw new IllegalArgumentException("Missing remote playlist library");
        return submit(Operation.Merge, expected, false, remote, target);
    }

    Request edit(Snapshot expected, PurplePlaylistsEdits.Command command, Target<?> target) {
        if (command == null || command.kind == PurplePlaylistsEdits.Kind.Undo)
            throw new IllegalArgumentException("Use an owned Undo ticket");
        return submit(Operation.Edit, expected, false, null, target, command, null);
    }
    Request undo(Snapshot expected, Undo undo, long now, Target<?> target) {
        return submit(Operation.Edit, expected, false, null, target, PurplePlaylistsEdits.Command.undo(now), undo);
    }

    private Request submit(Operation operation, Snapshot expected, boolean enabled, byte[] remote, Target<?> target) {
        return submit(operation, expected, enabled, remote, target, null, null);
    }

    private Request submit(Operation operation, Snapshot expected, boolean enabled, byte[] remote, Target<?> target,
            PurplePlaylistsEdits.Command command, Undo undo) {
        return submit(operation, expected, enabled, remote, target, command, undo,
                expected == null ? "" : expected.view.selectedId);
    }

    private Request submit(Operation operation, Snapshot expected, boolean enabled, byte[] remote, Target<?> target,
            PurplePlaylistsEdits.Command command, Undo undo, String selectedId) {
        return submit(operation, expected, enabled, remote, target, command, undo, selectedId, null);
    }
    private Request submit(Operation operation, Snapshot expected, boolean enabled, byte[] remote, Target<?> target,
            PurplePlaylistsEdits.Command command, Undo undo, String selectedId, PurplePlaylistsDownloadsCore.Preference preference) {
        if (target == null) throw new IllegalArgumentException("Missing playlist completion target");
        synchronized (lock) {
            Admission admission = !alive.get() ? Admission.Closed
                    : queue.size() >= QUEUE_CAPACITY ? Admission.Busy : Admission.Accepted;
            Request request = new Request(admission, operation, expected, enabled,
                    admission == Admission.Accepted && remote != null ? remote.clone() : null, target,
                    admission == Admission.Accepted ? command : null, admission == Admission.Accepted ? undo : null, selectedId, preference);
            if (admission == Admission.Accepted) {
                queue.addLast(request);
                lock.notifyAll();
            }
            return request;
        }
    }

    private boolean current() {
        Request request = active;
        return alive.get() && (request == null || !request.canceled.get());
    }

    private boolean owns(Snapshot snapshot) {
        return snapshot != null && snapshot.owner == this && snapshot == latest
                && snapshot.revision == revision.get() && !snapshot.request.canceled.get();
    }

    private void invalidate() {
        latest = null;
        revision.incrementAndGet();
    }

    private Snapshot snapshot(Request request, PurplePlaylistsStore.Read read) {
        if (!read.displayable()) return null;
        latest = new Snapshot(this, revision.get(), read, request);
        return latest;
    }

    private Snapshot refresh(Request request) {
        invalidate();
        return snapshot(request, store.read());
    }

    private Outcome perform(Request request) throws Exception {
        if (!current()) return failure(Status.Canceled, null, "RequestCanceled");
        if (!captured.current()) {
            invalidate();
            return failure(Status.Changed, null, "AccountChanged");
        }
        if (request.operation != Operation.Read && !owns(request.expected))
            return failure(Status.Changed, null, "SnapshotChanged");
        if (store == null) store = opener.open(captured, this::current);
        if (request.operation == Operation.Read) {
            invalidate();
            PurplePlaylistsStore.Read read = store.read();
            Snapshot snapshot = snapshot(request, read);
            return new Outcome(read.ready() ? Status.Ready : Status.Unavailable,
                    read.status, read.detail, snapshot, null, null, snapshot == null ? null : latestUndo);
        }
        if (request.operation == Operation.Edit) return performEdit(request);
        if (!request.expected.read.ready()) {
            PurplePlaylistsStore.Read read = request.expected.read;
            return new Outcome(Status.Failed, read.status, read.detail, latest, null, null, latestUndo);
        }
        if (request.operation == Operation.RemoveDownloads || request.operation == Operation.PruneDownloads)
            return performRemoval(request);
        if (request.operation == Operation.Preference) return performPreference(request);
        PurplePlaylistsStore.Read expected = request.expected.read;
        byte[] joined = null;
        if (request.operation == Operation.Merge) {
            PurplePlaylistsCore.Result result = PurplePlaylistsCore.join(expected.library(), request.remote,
                    captured.identity.userId);
            if (!result.valid()) return failure(Status.InvalidRemote, null, result.error);
            joined = result.canonicalBytes();
        }
        invalidate();
        PurplePlaylistsStore.Write write = request.operation == Operation.Sync
                ? store.setSync(expected, request.enabled)
                : store.write(expected, joined, expected.state(), PurplePlaylistsStore.Kind.Merge);
        if (request.operation == Operation.Merge && write.libraryMayHaveChanged) forgetUndo();
        Snapshot snapshot = current() && captured.current() ? refresh(request) : null;
        Status status = !current() ? Status.Canceled : !write.ready() ? Status.Failed
                : snapshot == null || !snapshot.read.ready() ? Status.Unavailable : Status.Ready;
        PurplePlaylistsRemoval.Result retention = null;
        if (status == Status.Ready && request.operation == Operation.Merge) {
            retention = cleanup(snapshot.read, null);
            if (current() && captured.current()) snapshot = refresh(request);
        }
        return new Outcome(status, write.status, "WriteResult", snapshot, write, null, latestUndo, retention);
    }

    private PurplePlaylistsRemoval.Result cleanup(PurplePlaylistsStore.Read expected, String playlist) {
        return PurplePlaylistsRemoval.run(store, captured.identity.userId, expected, playlist,
                downloadOwner != null && downloadOwner.active(), () -> current() && captured.current());
    }
    private Outcome performRemoval(Request request) {
        boolean prune = request.operation == Operation.PruneDownloads;
        if (!prune && (request.expected.view.detail == null || request.expected.view.detail.row.partial))
            return failure(Status.InvalidEdit, null, "PlaylistUnavailable");
        invalidate();
        PurplePlaylistsRemoval.Result result = cleanup(request.expected.read, prune ? null : request.expected.view.selectedId);
        Snapshot actual = current() && captured.current() ? refresh(request) : null;
        Status status = !current() ? Status.Canceled : !result.ready() ? Status.Failed
                : actual == null ? Status.Unavailable : Status.Ready;
        return new Outcome(status, null, "DownloadRemoval", actual, null, null, latestUndo, result);
    }

    private Outcome performPreference(Request request) {
        Snapshot expected = request.expected;
        if (expected.downloadStatus != PurplePlaylistsDownloadsCore.Status.Ready)
            return failure(Status.Unavailable, null, "DownloadInventory" + expected.downloadStatus.name());
        if (expected.view.detail == null || expected.view.detail.row.deleted || expected.view.detail.row.partial)
            return new Outcome(Status.InvalidEdit, null, "PlaylistUnavailable", latest, null, null, latestUndo);
        PurplePlaylistsDownloadsCore.Result update = PurplePlaylistsDownloadsCore.preference(expected.state(),
                captured.identity.userId, expected.view.detail.row.id, request.preference);
        if (!update.ready()) return failure(Status.Unavailable, null, "PreferenceUnavailable");
        invalidate();
        PurplePlaylistsStore.Write write = store.write(expected.read, expected.library(), update.local(), PurplePlaylistsStore.Kind.LocalEdit);
        Snapshot actual = current() && captured.current() ? refresh(request) : null;
        boolean exact = actual != null && java.util.Arrays.equals(actual.library(), expected.library())
                && java.util.Arrays.equals(actual.state(), update.local()) && actual.preference == request.preference;
        Status status = !current() ? Status.Canceled : !write.ready() ? Status.Failed
                : !exact ? Status.Unavailable : Status.Ready;
        return new Outcome(status, write.status, "PreferenceWriteResult", actual, write, null, latestUndo);
    }

    private void forgetUndo() {
        latestUndo = null;
        if (editor != null) editor.forgetUndo();
    }

    private Outcome performEdit(Request request) {
        if (request.command.kind == PurplePlaylistsEdits.Kind.Undo
                && (request.undo == null || request.undo.owner != this || request.undo != latestUndo))
            return failure(Status.Changed, null, "UndoChanged");
        PurplePlaylistsStore.Read expected = store.revalidate(request.expected.read);
        if (expected == null) {
            forgetUndo();
            Snapshot snapshot = current() && captured.current() ? refresh(request) : null;
            return new Outcome(Status.Changed, PurplePlaylistsStore.Status.Changed, "EditFilesChanged",
                    snapshot, null);
        }
        if (!expected.ready()) {
            invalidate();
            return new Outcome(Status.Failed, expected.status, expected.detail,
                    snapshot(request, expected), null, null, latestUndo);
        }
        if (editor == null) editor = new PurplePlaylistsEdits(captured.identity.userId);
        PurplePlaylistsEdits.Result edit = editor.prepare(expected.library(), expected.state(), request.command);
        if (!edit.valid()) return new Outcome(Status.InvalidEdit, null, edit.error + ":" + edit.localError,
                latest, null, edit, latestUndo);
        if (!edit.changed && !edit.stateChanged) {
            editor.finish(false, false, false);
            return new Outcome(Status.Ready, PurplePlaylistsStore.Status.Ready, "NoChange", latest, null, edit, latestUndo);
        }
        invalidate();
        PurplePlaylistsStore.Write write = store.writeEdit(expected, edit);
        boolean confirmed = write.ready() || write.libraryCommitted;
        boolean uncertain = write.libraryMayHaveChanged && !write.libraryCommitted;
        boolean available = editor.finish(confirmed, write.ready(), uncertain);
        if (uncertain || !available) latestUndo = null;
        else if (edit.changed && confirmed) {
            latestUndo = request.undo == null ? new Undo(this, request.command.kind, false)
                    : new Undo(this, request.undo.kind, !request.undo.redo);
        }
        Snapshot snapshot = current() && captured.current() ? refresh(request) : null;
        Status status = !current() ? Status.Canceled : !write.ready() ? Status.Failed
                : snapshot == null || !snapshot.read.ready() ? Status.Unavailable : Status.Ready;
        PurplePlaylistsRemoval.Result retention = null;
        if (status == Status.Ready && edit.changed && (request.command.kind == PurplePlaylistsEdits.Kind.RemoveSongs
                || request.command.kind == PurplePlaylistsEdits.Kind.DeleteForever || request.command.kind == PurplePlaylistsEdits.Kind.Undo)) {
            retention = cleanup(snapshot.read, null);
            if (current() && captured.current()) snapshot = refresh(request);
        }
        return new Outcome(status, write.status, "EditWriteResult", snapshot, write, edit, latestUndo, retention);
    }

    private static Outcome failure(Status status, PurplePlaylistsStore.Status storeStatus, String detail) {
        return new Outcome(status, storeStatus, detail, null, null);
    }

    private void run() {
        try {
            while (alive.get()) {
                Request request = null;
                StoreTask storeTask = null;
                DownloadOwner wake = null;
                synchronized (lock) {
                    while (queue.isEmpty() && storeQueue.isEmpty() && !downloadSignaled && alive.get()) lock.wait();
                    if (!alive.get()) break;
                    if (downloadSignaled) { downloadSignaled = false; wake = downloadOwner; }
                    else if (!queue.isEmpty()) request = queue.removeFirst();
                    else storeTask = storeQueue.removeFirst();
                }
                if (wake != null) { wake.wake(); continue; }
                if (storeTask != null) {
                    try {
                        if (!downloadCurrent() || (storeTask.expected != null && !owns(storeTask.expected)))
                            throw new IllegalStateException("StoreTaskChanged");
                        if (store == null) store = opener.open(captured, this::current);
                        if (storeTask.invalidateSnapshot) invalidate();
                        storeTask.action.run(store, captured.identity.userId);
                    } catch (Exception | LinkageError e) {
                        try { storeTask.refused.run(); } catch (RuntimeException ignored) { }
                    }
                    continue;
                }
                active = request;
                Outcome result;
                try {
                    result = perform(request);
                } catch (Exception | LinkageError e) {
                    invalidate();
                    result = failure(Status.Unavailable, null, "OperationUnavailable");
                } finally {
                    request.remote = null;
                    active = null;
                }
                if ((request.operation == Operation.Edit || request.operation == Operation.Merge || request.operation == Operation.Sync)
                        && (result.libraryCommitted || request.operation == Operation.Sync && result.stateCommitted)) edited();
                request.target.dispatch(this, revision.get(), result, request);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            alive.set(false);
            invalidate();
            synchronized (lock) {
                for (Request request : queue) request.remote = null;
                queue.clear();
                for (StoreTask task : storeQueue) {
                    try { task.refused.run(); } catch (RuntimeException ignored) { }
                }
                storeQueue.clear();
            }
            Termination result = Termination.Closed;
            if (downloadOwner != null) {
                try { downloadOwner.close(); }
                catch (Exception | LinkageError e) { result = Termination.CloseFailed; }
                downloadOwner = null;
            }
            syncObservers.clear(); editObservers.clear();
            latestUndo = null;
            if (editor != null) {
                try { editor.close(); }
                catch (RuntimeException | LinkageError e) { result = Termination.CloseFailed; }
                editor = null;
            }
            if (store != null) {
                try { store.close(); }
                catch (Exception e) { result = Termination.CloseFailed; }
                store = null;
            }
            termination = result;
        }
    }

    Termination termination() { return termination; }

    boolean matches(PurplePlaylistsAccount.Captured value) {
        return alive.get() && captured.same(value);
    }

    @Override public void close() {
        alive.set(false);
        synchronized (lock) { lock.notifyAll(); }
    }
}
