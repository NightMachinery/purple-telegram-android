package org.telegram.messenger.purple;

import android.os.Looper;
import org.telegram.messenger.AndroidUtilities;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

public final class PurplePlaylistsDownloadControls {
    public enum Status { Idle, Resolving, Queued, Running, Canceling, Downloaded, Partial, Canceled, Busy, Changed, Failed, Wait }
    public interface Listener { void downloadsChanged(State state); }
    interface Environment {
        PurplePlaylistsResolver.Port source(PurplePlaylistsAccount.Captured account, BooleanSupplier current);
        PurplePlaylistsDownloads.Factory transfers(PurplePlaylistsAccount.Captured account);
        boolean unrestricted();
    }
    public static final class State {
        public final int account, waitSeconds, nativeReady;
        public final long revision, total, downloaded, unavailable, failed, busy, active;
        public final String playlistId;
        public final Status status;
        public final boolean canCancel, canRetry;
        private State(PurplePlaylistsDownloadControls owner, Status status, PurplePlaylistsDownloads.Snapshot progress) {
            account = owner.account.identity.slot; playlistId = owner.playlist;
            revision = ++owner.revision; this.status = status;
            PurplePlaylistsDownloadsCore.Progress counters = progress == null ? null : progress.progress;
            total = owner.songs.size(); downloaded = counters == null ? 0 : counters.downloaded;
            unavailable = owner.unavailable + (counters == null ? 0 : counters.unavailable);
            failed = counters == null ? 0 : counters.failed; busy = counters == null ? 0 : counters.busy;
            active = counters == null ? 0 : counters.active;
            nativeReady = progress == null ? 0 : progress.nativeReady;
            waitSeconds = progress == null ? owner.waitSeconds : progress.waitSeconds;
            canCancel = status == Status.Resolving || status == Status.Queued || status == Status.Running;
            canRetry = status == Status.Partial || status == Status.Canceled || status == Status.Busy
                    || status == Status.Changed || status == Status.Failed || status == Status.Wait;
        }
    }
    public final class Observation implements AutoCloseable {
        private final WeakReference<Listener> listener;
        private boolean closed;
        private Observation(Listener listener) { this.listener = new WeakReference<>(listener); }
        private void deliver(State expected) {
            if (closed || snapshot() != expected) return;
            Listener target = listener.get();
            if (target == null) { close(); return; }
            target.downloadsChanged(expected);
        }
        @Override public void close() {
            main(); closed = true; listener.clear(); observers.remove(this);
        }
    }
    private static final Map<Integer, PurplePlaylistsDownloadControls> accounts = new HashMap<>();
    private static final Environment TELEGRAM = new Environment() {
        @Override public PurplePlaylistsResolver.Port source(PurplePlaylistsAccount.Captured account, BooleanSupplier current) {
            return new PurplePlaylistsTelegram(account, current);
        }
        @Override public PurplePlaylistsDownloads.Factory transfers(PurplePlaylistsAccount.Captured account) {
            return new PurplePlaylistsDownloadsTelegram(account, this::unrestricted);
        }
        @Override public boolean unrestricted() { return PurpleCopyPolicy.unrestricted(); }
    };
    static PurplePlaylistsDownloadControls acquire(PurplePlaylistsAccount.Captured account, PurplePlaylistsWorker worker) {
        main();
        if (account == null || worker == null || !worker.matches(account)) return null;
        PurplePlaylistsDownloadControls value = accounts.get(account.identity.slot);
        if (value != null && value.worker == worker && value.account.same(account) && value.current()) return value;
        if (value != null) value.retire();
        value = new PurplePlaylistsDownloadControls(account, worker, TELEGRAM,
                task -> AndroidUtilities.runOnUIThread(task, 1));
        accounts.put(account.identity.slot, value);
        return value;
    }
    private final PurplePlaylistsAccount.Captured account;
    private final PurplePlaylistsWorker worker;
    private final Environment environment;
    private final Executor ui;
    private final PurplePlaylistsDownloads downloads;
    private final ArrayList<Observation> observers = new ArrayList<>();
    private PurplePlaylistsResolver resolver;
    private PurplePlaylistsDownloads.Control control;
    private PurplePlaylistsDownloads.Snapshot progress;
    private List<PurplePlaylistsView.Song> songs = java.util.Collections.emptyList();
    private String playlist = "";
    private int unavailable, waitSeconds;
    private long generation, revision, progressRevision;
    private State published;
    private boolean retired;

    PurplePlaylistsDownloadControls(PurplePlaylistsAccount.Captured account, PurplePlaylistsWorker worker,
            Environment environment, Executor ui) {
        main();
        if (account == null || worker == null || environment == null || ui == null || !worker.matches(account))
            throw new IllegalArgumentException("MissingDownloadControls");
        this.account = account; this.worker = worker; this.environment = environment; this.ui = ui;
        downloads = new PurplePlaylistsDownloads(worker, environment.transfers(account));
        published = new State(this, Status.Idle, null);
    }
    private static void main() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Download controls require main thread");
    }
    private boolean current() { return !retired && account.current() && worker.downloadCurrent() && worker.matches(account); }
    public State snapshot() {
        main(); if (!current() && !retired) retire(); return published;
    }
    public Observation observe(Listener listener) {
        main();
        if (listener == null) throw new IllegalArgumentException("MissingDownloadListener");
        Observation observation = new Observation(listener); observers.add(observation);
        State initial = snapshot(); ui.execute(() -> observation.deliver(initial)); return observation;
    }
    private void publish(Status status) {
        State next = new State(this, status, progress); published = next;
        for (Observation observation : new ArrayList<>(observers)) ui.execute(() -> observation.deliver(next));
    }
    boolean start(PurplePlaylistsWorker.Snapshot snapshot, State expected) {
        main();
        if (!current() || snapshot == null || snapshot.downloadStatus != PurplePlaylistsDownloadsCore.Status.Ready
                || snapshot.view.detail == null || snapshot.view.detail.row.deleted
                || snapshot.view.detail.row.partial || snapshot.view.detail.songs.isEmpty()
                || published.canCancel || published.status == Status.Canceling
                || (expected != null && (published != expected || !expected.canRetry))) return false;
        if (resolver != null) resolver.close();
        control = null; progress = null; progressRevision = 0; unavailable = waitSeconds = 0;
        songs = new ArrayList<>(snapshot.view.detail.songs); playlist = snapshot.view.detail.row.id;
        long run = ++generation; publish(Status.Resolving);
        BooleanSupplier scope = () -> current() && run == generation;
        try {
            resolver = new PurplePlaylistsResolver(environment.source(account, scope), account.identity.userId,
                    environment.unrestricted(), songs, results -> resolved(snapshot, run, results));
            resolver.start();
        } catch (RuntimeException | LinkageError e) {
            if (resolver != null) resolver.close(); resolver = null; publish(Status.Failed);
        }
        return true;
    }
    private void resolved(PurplePlaylistsWorker.Snapshot snapshot, long run, List<PurplePlaylistsResolver.Result> results) {
        main(); if (!current() || generation != run) return;
        resolver = null;
        ArrayList<PurplePlaylistsDownloads.Item> items = new ArrayList<>();
        for (PurplePlaylistsResolver.Result result : results) {
            if (result.status == PurplePlaylistsResolver.Status.Wait) waitSeconds = Math.max(waitSeconds, result.waitSeconds);
            if (!result.playable()) { ++unavailable; continue; }
            try {
                String mime = result.message.media.document.mime_type;
                String extension = PurplePlaylistsDownloads.Item.extension(mime);
                items.add(new PurplePlaylistsDownloads.Item(result, extension));
            } catch (RuntimeException e) { ++unavailable; }
        }
        if (waitSeconds > 0) { publish(Status.Wait); return; }
        if (items.isEmpty()) { publish(Status.Partial); return; }
        publish(Status.Queued);
        try {
            ProgressDelivery delivery = new ProgressDelivery(run);
            control = downloads.start(snapshot, playlist, items, delivery::submit);
        } catch (RuntimeException | LinkageError e) { publish(Status.Failed); }
    }
    private final class ProgressDelivery {
        private final long run;
        private final java.util.concurrent.atomic.AtomicReference<PurplePlaylistsDownloads.Snapshot> pending = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
        ProgressDelivery(long run) { this.run = run; }
        void submit(PurplePlaylistsDownloads.Snapshot value) { pending.set(value); wake(); }
        private void wake() {
            if (!queued.compareAndSet(false, true)) return;
            ui.execute(() -> {
                PurplePlaylistsDownloads.Snapshot value = pending.getAndSet(null);
                if (value != null) accept(run, value);
                queued.set(false);
                if (pending.get() != null) wake();
            });
        }
    }
    private void accept(long run, PurplePlaylistsDownloads.Snapshot value) {
        main();
        if (!current()) { if (!retired) retire(); return; }
        if (run != generation || value.control != control || value.revision <= progressRevision) return;
        if (published.status == Status.Canceling && (value.status == PurplePlaylistsDownloads.Status.Running
                || value.status == PurplePlaylistsDownloads.Status.Queued)) return;
        progress = value; progressRevision = value.revision;
        Status status = Status.valueOf(value.status.name());
        if (status == Status.Downloaded && unavailable > 0) status = Status.Partial;
        publish(status);
    }
    public boolean cancel(State expected) {
        main();
        if (!current() || published != expected || !expected.canCancel) return false;
        if (resolver != null) {
            ++generation; resolver.close(); resolver = null; publish(Status.Canceled);
        } else if (control != null) { control.cancel(); publish(Status.Canceling); }
        else return false;
        return true;
    }
    private void retire() {
        retired = true; ++generation;
        if (resolver != null) resolver.close(); resolver = null;
        if (control != null) control.cancel();
        control = null; progress = null; songs = java.util.Collections.emptyList();
        publish(Status.Changed);
    }
}
