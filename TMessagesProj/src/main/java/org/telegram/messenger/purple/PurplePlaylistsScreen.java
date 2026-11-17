package org.telegram.messenger.purple;

import android.os.Looper;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class PurplePlaylistsScreen implements AutoCloseable {
    public enum Status { Ready, Canceled, Closed, Changed, Unavailable, InvalidRemote, InvalidEdit, Failed }
    public interface Listener {
        void playlistsChanged(State state);
        default void downloadRefused() { }
        default void syncCompleted(PurplePlaylistsSyncResult result) { }
    }
    public enum PlaylistAction { Delete, Restore, DeleteForever }

    public static final class State {
        public final Status status;
        public final String storageStatus, detail, editError, localError, editedPlaylistId;
        public final PurplePlaylistsView view;
        public final PurplePlaylistsDownloadsCore.Status downloadStatus;
        public final PurplePlaylistsDownloadsCore.Preference keepPreference;
        public final Boolean keepDefault;
        public final PurplePlaylistsRemoval.Result cleanup;
        public final boolean libraryCommitted, stateCommitted, libraryMayHaveChanged, stateMayHaveChanged, canUndo;
        public final int added, relinked, removed, unchanged;
        private State(PurplePlaylistsWorker.Outcome result) {
            status = Status.valueOf(result.status.name());
            storageStatus = result.storeStatus == null ? "" : result.storeStatus.name();
            cleanup = result.cleanup;
            detail = result.detail; view = result.snapshot == null ? null : result.snapshot.view;
            downloadStatus = result.snapshot == null ? null : result.snapshot.downloadStatus;
            keepPreference = result.snapshot == null ? null : result.snapshot.preference;
            keepDefault = result.snapshot == null ? null : result.snapshot.keepDefault;
            libraryCommitted = result.libraryCommitted; stateCommitted = result.stateCommitted;
            libraryMayHaveChanged = result.libraryMayHaveChanged; stateMayHaveChanged = result.stateMayHaveChanged;
            canUndo = result.undo != null && result.snapshot != null;
            PurplePlaylistsEdits.Result edit = result.edit;
            editError = edit == null ? "" : edit.error; localError = edit == null ? "" : edit.localError;
            editedPlaylistId = edit == null ? "" : edit.playlistId;
            added = edit == null ? 0 : edit.added; relinked = edit == null ? 0 : edit.relinked;
            removed = edit == null ? 0 : edit.removed; unchanged = edit == null ? 0 : edit.unchanged;
        }
        public boolean ready() { return status == Status.Ready && view != null; }
        public boolean partial() {
            return !ready() && (libraryCommitted || stateCommitted || libraryMayHaveChanged || stateMayHaveChanged
                    || cleanup != null && (cleanup.filesMayHaveChanged || cleanup.removed > 0));
        }
    }

    private static final class Scope {
        final int account;
        final AtomicBoolean alive = new AtomicBoolean(true);
        final AtomicLong generation = new AtomicLong();
        Scope(int account) { this.account = account; }
        boolean current(long expected) {
            return alive.get() && generation.get() == expected && UserConfig.selectedAccount == account;
        }
    }

    private final Scope scope;
    private final WeakReference<Listener> listener;
    private final PurplePlaylistsWorker worker;
    private final Executor dispatcher;
    private PurplePlaylistsWorker.Snapshot snapshot;
    private PurplePlaylistsWorker.Undo undo;
    private PurplePlaylistsWorker.Request pending;
    private String selectedId = "";
    private final AutoCloseable syncObservation;
    private boolean syncDirty;
    private AutoCloseable manualSync;
    private boolean syncing;
    private long syncGeneration;

    public static PurplePlaylistsScreen open(int account, Listener listener) {
        main();
        if (listener == null) throw new IllegalArgumentException("Missing playlist screen listener");
        if (UserConfig.selectedAccount != account) return null;
        PurplePlaylistsWorker worker = PurplePlaylistsWorkers.acquire(account);
        return worker == null ? null : new PurplePlaylistsScreen(account, listener, worker, AndroidUtilities::runOnUIThread);
    }

    PurplePlaylistsScreen(int account, Listener listener, PurplePlaylistsWorker worker, Executor dispatcher) {
        main();
        if (listener == null || worker == null || dispatcher == null)
            throw new IllegalArgumentException("Missing playlist screen endpoint");
        scope = new Scope(account); this.listener = new WeakReference<>(listener);
        this.worker = worker; this.dispatcher = dispatcher;
        WeakReference<PurplePlaylistsScreen> weak = new WeakReference<>(this);
        syncObservation = worker.observeSync(() -> dispatcher.execute(() -> {
            PurplePlaylistsScreen screen = weak.get();
            if (screen == null || !screen.available()) return;
            if (screen.pending != null || screen.syncing) screen.syncDirty = true;
            else screen.refresh();
        }));
    }

    private static void main() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Playlist screen requires main thread");
    }
    private boolean available() { return scope.current(scope.generation.get()); }
    private PurplePlaylistsWorker.Target<PurplePlaylistsScreen> target() {
        Scope captured = scope;
        long generation = captured.generation.incrementAndGet();
        return new PurplePlaylistsWorker.Target<>(this, dispatcher, () -> captured.current(generation), PurplePlaylistsScreen::complete);
    }
    private boolean admit(PurplePlaylistsWorker.Request request) {
        if (request.admission != PurplePlaylistsWorker.Admission.Accepted) return false;
        pending = request; return true;
    }

    public boolean refresh() {
        main();
        if (!available()) return false;
        if (pending != null) pending.cancel();
        pending = null; snapshot = null; undo = null;
        return admit(worker.read(selectedId, target()));
    }
    public boolean select(String playlistId) {
        main();
        if (!available() || pending != null) return false;
        if (playlistId == null) throw new IllegalArgumentException("Missing playlist selection");
        String previous = selectedId; selectedId = playlistId;
        if (refresh()) return true;
        selectedId = previous; return false;
    }
    private boolean edit(PurplePlaylistsEdits.Command command) {
        main();
        return available() && pending == null && snapshot != null && admit(worker.edit(snapshot, command, target()));
    }
    public boolean create(String title) { main(); return edit(PurplePlaylistsEdits.Command.create(title, System.currentTimeMillis())); }
    public boolean rename(String id, String title) { main(); return edit(PurplePlaylistsEdits.Command.rename(id, title, System.currentTimeMillis())); }
    public boolean playlist(PlaylistAction action, String id) {
        main();
        if (action == null) throw new IllegalArgumentException("Missing playlist action");
        return edit(PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.valueOf(action.name()), id, System.currentTimeMillis()));
    }
    public boolean copy(String id) {
        main();
        return snapshot != null && edit(PurplePlaylistsEdits.Command.copyCurrent(id, System.currentTimeMillis()));
    }
    public boolean remove(String id, String[] documents) {
        main(); return edit(PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.RemoveSongs, id, documents, System.currentTimeMillis()));
    }
    public boolean add(String id, PurplePlaylistsAdd.Selection selection) {
        main();
        if (!available() || pending != null || snapshot == null || selection == null || selection.account != scope.account
                || !selection.available() || !worker.matches(selection.captured)) return false;
        if (!edit(selection.command(id, System.currentTimeMillis()))) return false;
        selection.consume();
        return true;
    }
    public boolean reorder(String id, String[] documents) {
        main(); return edit(PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.ReorderSongs, id, documents, System.currentTimeMillis()));
    }
    public boolean undo() {
        main();
        return available() && pending == null && snapshot != null && undo != null
                && admit(worker.undo(snapshot, undo, System.currentTimeMillis(), target()));
    }
    public boolean play(String documentId) {
        main();
        if (!available() || pending != null || snapshot == null || snapshot.view.detail == null
                || snapshot.view.detail.row.deleted) return false;
        PurplePlaylistsAccount.Captured account = PurplePlaylistsAccount.capture(scope.account);
        if (account == null || !worker.matches(account)) return false;
        java.util.List<PurplePlaylistsView.Song> songs = snapshot.view.detail.songs;
        for (int index = 0; index < songs.size(); ++index) {
            if (songs.get(index).documentId.equals(documentId))
                return org.telegram.messenger.MediaController.getInstance().purplePlaylists().start(account, songs, index, snapshot.view.detail.row.id);
        }
        return false;
    }

    public PurplePlaylistsDownloadControls downloads() {
        main();
        PurplePlaylistsAccount.Captured account = PurplePlaylistsAccount.capture(scope.account);
        return account == null || !available() ? null : PurplePlaylistsDownloadControls.acquire(account, worker);
    }
    public boolean download(PurplePlaylistsDownloadControls.State expected) {
        main();
        PurplePlaylistsDownloadControls controls = downloads();
        if (controls == null || pending != null || snapshot == null
                || snapshot.downloadStatus != PurplePlaylistsDownloadsCore.Status.Ready || selectedId.isEmpty()
                || controls.snapshot().canCancel || controls.snapshot().status == PurplePlaylistsDownloadControls.Status.Canceling
                || (expected != null && (controls.snapshot() != expected || !expected.canRetry))) return false;
        Scope captured = scope; long generation = captured.generation.incrementAndGet();
        return admit(worker.read(selectedId, new PurplePlaylistsWorker.Target<>(this, dispatcher,
                () -> captured.current(generation), (screen, result) -> {
                    screen.complete(result);
                    if (captured.current(generation) && result.ready() && !controls.start(result.snapshot, expected)
                            && captured.current(generation)) {
                        Listener target = screen.listener.get();
                        if (target != null) target.downloadRefused();
                    }
                })));
    }

    public boolean retry(PurplePlaylistsPlayer.State expected) {
        main();
        if (!available() || expected == null || expected.account != scope.account) return false;
        PurplePlaylistsAccount.Captured account = PurplePlaylistsAccount.capture(scope.account);
        return account != null && worker.matches(account)
                && org.telegram.messenger.MediaController.getInstance().purplePlaylists().retry(expected);
    }

    public boolean removeDownloads(State expected, boolean prune) {
        main();
        return available() && pending == null && snapshot != null && expected != null
                && expected.view == snapshot.view && snapshot.downloadStatus == PurplePlaylistsDownloadsCore.Status.Ready
                && admit(worker.removeDownloads(snapshot, prune, target()));
    }

    public boolean keepPreference(PurplePlaylistsDownloadsCore.Preference value) {
        main();
        return available() && pending == null && snapshot != null
                && snapshot.downloadStatus == PurplePlaylistsDownloadsCore.Status.Ready
                && admit(worker.preference(snapshot, value, target()));
    }
    public boolean keepDefaultCurrent(State expected) {
        main();
        return available() && pending == null && snapshot != null && expected != null
                && expected.view == snapshot.view && snapshot.keepSettingsCurrent();
    }

    public boolean sync() {
        main();
        if (!available() || pending != null || syncing || snapshot == null) return false;
        syncing = true;
        long generation = ++syncGeneration;
        AutoCloseable request = PurplePlaylistsSyncRuntime.manual(worker, result -> dispatcher.execute(() -> {
            if (!syncing || generation != syncGeneration) return;
            syncing = false; manualSync = null;
            if (!available()) return;
            if (result.changed()) { snapshot = null; undo = null; }
            syncDirty = true;
            Listener target = listener.get();
            if (target != null) target.syncCompleted(result);
            if (pending == null && available()) { syncDirty = false; refresh(); }
        }));
        if (syncing) manualSync = request;
        return true;
    }

    public boolean setSync(boolean enabled) {
        main();
        return available() && pending == null && snapshot != null && admit(worker.setSync(snapshot, enabled, target()));
    }
    private void complete(PurplePlaylistsWorker.Outcome result) {
        main();
        pending = null; snapshot = result.snapshot; undo = result.undo;
        Listener target = listener.get();
        if (target != null) target.playlistsChanged(new State(result));
        if (syncDirty && !syncing && available()) { syncDirty = false; refresh(); }
    }
    @Override public void close() {
        main();
        scope.alive.set(false); scope.generation.incrementAndGet();
        if (manualSync != null) try { manualSync.close(); } catch (Exception ignored) { }
        manualSync = null; syncing = false; ++syncGeneration;
        try { syncObservation.close(); } catch (Exception ignored) { }
        if (pending != null) pending.cancel();
        pending = null; snapshot = null; undo = null; listener.clear();
    }
}
