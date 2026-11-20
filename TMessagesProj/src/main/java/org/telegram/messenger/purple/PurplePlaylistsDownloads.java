package org.telegram.messenger.purple;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class PurplePlaylistsDownloads implements PurplePlaylistsWorker.DownloadOwner {
    interface Port extends AutoCloseable {
        void read(PurplePlaylistsDownloadTransfer.Document document, long offset,
                PurplePlaylistsDownloadTransfer.Completion done);
        void close();
    }
    interface Factory extends AutoCloseable {
        Port open(PurplePlaylistsStore.Current current, PurplePlaylistsDownloadTransfer.Dispatcher dispatcher);
        void refresh(Item item, PurplePlaylistsStore.Current current,
                Consumer<PurplePlaylistsDownloadTransfer.Document> done);
        void cancel();
        void close();
    }
    static final class Item {
        final PurplePlaylistsView.Song song;
        final PurplePlaylistsView.Source source;
        final PurplePlaylistsDownloadTransfer.Document document;
        final String extension;
        final PurplePlaylistsOfflineMusic.Descriptor playback;
        static String extension(String mime) {
            return "audio/mpeg".equals(mime) ? "mp3" : "audio/ogg".equals(mime) ? "ogg"
                    : "audio/flac".equals(mime) ? "flac" : "audio/mp4".equals(mime) ? "m4a" : "audio";
        }
        Item(PurplePlaylistsResolver.Result result, String extension) {
            if (result == null || !result.playable() || result.message == null || result.message.media == null)
                throw new IllegalArgumentException("UnresolvedDownloadSource");
            song = result.song; source = result.source;
            document = new PurplePlaylistsDownloadTransfer.Document(result.message.media.document);
            this.extension = extension;
            playback = PurplePlaylistsOfflineMusic.capture(result);
            if (song == null || source == null || !song.documentId.equals(Long.toString(document.id)))
                throw new IllegalArgumentException("ChangedDownloadSource");
        }
    }
    static final class Completed {
        final Item item;
        final PurplePlaylistsPhysical.MediaFile proof;
        final PurplePlaylistsDownloadTransfer.Document document;
        private Completed(Item item, PurplePlaylistsPhysical.MediaFile proof, PurplePlaylistsDownloadTransfer.Document document) {
            this.item = item; this.proof = proof; this.document = document;
        }
    }
    enum Status { Queued, Running, Downloaded, Partial, Canceled, Busy, Changed, Failed, Wait }
    static final class Control {
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final PurplePlaylistsDownloads owner;
        private final Consumer<Snapshot> changed;
        private long revision;
        volatile Snapshot snapshot;
        private void publish() {
            Snapshot next = new Snapshot(this, ++revision); snapshot = next;
            try { changed.accept(next); } catch (RuntimeException ignored) { }
        }
        volatile Status status = Status.Queued;
        volatile PurplePlaylistsDownloadsCore.Progress progress;
        volatile List<Completed> completed = Collections.emptyList();
        volatile int waitSeconds;
        volatile int nativeReady;
        private Control(PurplePlaylistsDownloads owner, Consumer<Snapshot> changed) { this.owner = owner; this.changed = changed; }
        void cancel() {
            if (canceled.compareAndSet(false, true)) owner.worker.signalDownloadOwner(owner);
        }
    }
    static final class Snapshot {
        final Control control;
        final long revision;
        final Status status;
        final PurplePlaylistsDownloadsCore.Progress progress;
        final int completed, nativeReady, waitSeconds;
        Snapshot(Control control, long revision) {
            this.control = control; this.revision = revision; status = control.status;
            progress = control.progress; completed = control.completed.size();
            nativeReady = control.nativeReady; waitSeconds = control.waitSeconds;
        }
    }
    @Override public boolean active() { return batch != null && !batch.terminal; }
    @Override public void wake() {
        if (batch != null && batch.control.canceled.get()) batch.stop(Status.Canceled);
    }
    private static final class Running {
        final Item item;
        final PurplePlaylistsDownloadsCore.Ticket ticket;
        PurplePlaylistsDownloadJournal.Entry entry;
        PurplePlaylistsCache.Entry cached;
        long offset() { return cached != null ? cached.offset() : entry.offset(); }
        boolean complete() { return cached != null ? cached.complete() : entry.complete(); }
        PurplePlaylistsDownloadTransfer.Document document;
        Port port;
        boolean refreshed;
        Running(Item item, PurplePlaylistsDownloadsCore.Ticket ticket) {
            this.item = item; this.ticket = ticket; document = item.document;
        }
    }
    private final PurplePlaylistsWorker worker;
    private final Factory factory;
    private Batch batch;
    private volatile boolean closed;

    PurplePlaylistsDownloads(PurplePlaylistsWorker worker, Factory factory) {
        if (worker == null || factory == null) throw new IllegalArgumentException("MissingDownloadWorker");
        this.worker = worker; this.factory = factory;
    }
    Control start(PurplePlaylistsWorker.Snapshot expected, String playlist, List<Item> items) {
        return start(expected, playlist, items, value -> { });
    }
    Control start(PurplePlaylistsWorker.Snapshot expected, String playlist, List<Item> items, Consumer<Snapshot> changed) {
        if (expected == null || playlist == null || items == null || items.size() > 1000000)
            throw new IllegalArgumentException("MissingDownloadBatch");
        List<Item> captured = Collections.unmodifiableList(new ArrayList<>(items));
        if (captured.contains(null)) throw new IllegalArgumentException("MissingDownloadItem");
        if (changed == null) throw new IllegalArgumentException("MissingDownloadObserver");
        Control control = new Control(this, changed);
        boolean admitted = worker.postStore(expected, (store, self) -> {
            if (closed || control.canceled.get()) { control.status = Status.Canceled; control.publish(); return; }
            if (batch != null && !batch.terminal) { control.status = Status.Busy; control.publish(); return; }
            worker.bindDownloadOwner(this);
            batch = new Batch(store, self, playlist, captured, control);
            batch.pump();
        }, () -> { control.status = Status.Changed; control.publish(); });
        if (!admitted) { control.status = Status.Busy; control.publish(); }
        return control;
    }
    private boolean post(Runnable task) {
        return worker.postStore(null, (store, self) -> task.run(), () -> { });
    }
    private final class Batch {
        final PurplePlaylistsDownloadJournal journal;
        final PurplePlaylistsCache cache;
        final boolean keep;
        final PurplePlaylistsStore store;
        final long self;
        final PurplePlaylistsDownloadsCore.Job job;
        final List<Item> items;
        final Map<Integer, Running> running = new HashMap<>();
        final List<Completed> completed = new ArrayList<>();
        final Control control;
        final String playlist;
        volatile boolean terminal;
        boolean pumpQueued;
        int registered;
        Batch(PurplePlaylistsStore store, long self, String playlist, List<Item> items, Control control)
                throws Exception {
            this.playlist = playlist; this.items = items; this.control = control;
            this.store = store; this.self = self;
            keep = PurplePlaylistsCache.keeps(store.read(), self, worker.accountSlot(), playlist);
            journal = keep ? new PurplePlaylistsDownloadJournal(store, self, this::current) : null;
            PurplePlaylistsCache opened = keep ? null : new PurplePlaylistsCache(store, self, worker, playlist, this::current);
            boolean acquired = false;
            try { job = new PurplePlaylistsDownloadsCore.Job(items.size()); acquired = true; }
            finally { if (!acquired && opened != null) opened.close(); }
            cache = opened;
        }
        boolean current() { return !closed && !terminal && !control.canceled.get() && worker.downloadCurrent(); }
        void policy() throws IOException {
            if (PurplePlaylistsCache.keeps(store.read(), self, worker.accountSlot(), playlist) != keep)
                throw new IOException("DownloadPolicyChanged");
        }
        boolean closeCache() {
            if (cache == null) return true;
            try { cache.close(); return true; }
            catch (IOException error) { return false; }
        }
        void capture() {
            control.progress = job.progress();
            if (control.completed.size() != completed.size())
                control.completed = Collections.unmodifiableList(new ArrayList<>(completed));
        }
        void update() { capture(); control.publish(); }
        void queuePump() {
            if (!current() || pumpQueued) return;
            pumpQueued = true;
            if (!post(() -> { pumpQueued = false; pump(); })) stop(Status.Busy);
        }
        void pump() {
            if (!current()) { stop(Status.Canceled); return; }
            control.status = Status.Running;
            for (PurplePlaylistsDownloadsCore.Ticket ticket : job.admit()) {
                if (terminal) return;
                Running value = new Running(items.get(ticket.index), ticket);
                running.put(ticket.index, value);
                try {
                    policy();
                    if (keep) value.entry = journal.prepare(playlist, value.item.song, value.item.source,
                            value.document.id, value.item.extension, value.document.size, value.item.playback);
                    else value.cached = cache.prepare(value.item);
                    if (value.complete()) finish(value, PurplePlaylistsDownloadsCore.Phase.Downloaded);
                    else {
                        value.port = factory.open(this::current, PurplePlaylistsDownloads.this::post);
                        next(value);
                    }
                } catch (Exception | LinkageError e) { finish(value, PurplePlaylistsDownloadsCore.Phase.Failed); }
            }
            if (terminal) return;
            update();
            if (control.progress.terminal && running.isEmpty()) {
                if (registered < completed.size()) {
                    Completed value = completed.get(registered++);
                    try {
                        if (PurplePlaylistsRetained.publish(store, self, worker, playlist, value.item.song,
                                value.item.source, value.document, this::current)) ++control.nativeReady;
                    } catch (Exception | LinkageError ignored) { }
                }
                if (!current()) { stop(Status.Canceled); return; }
                if (registered < completed.size()) { update(); queuePump(); return; }
                control.status = control.progress.failed != 0 || control.progress.busy != 0
                        || control.progress.unavailable != 0 ? Status.Partial : Status.Downloaded;
                if (!closeCache()) control.status = Status.Partial;
                terminal = true; job.close(); control.publish();
            }
        }
        void next(Running value) {
            if (!current()) { stop(Status.Canceled); return; }
            if (running.get(value.ticket.index) != value) return;
            if (value.offset() == value.document.size) {
                try {
                    policy();
                    if (keep) journal.finish(value.entry); else cache.finish(value.cached, value.document);
                    finish(value, PurplePlaylistsDownloadsCore.Phase.Downloaded);
                }
                catch (Exception | LinkageError e) { finish(value, PurplePlaylistsDownloadsCore.Phase.Failed); }
                return;
            }
            long offset = value.offset() / PurplePlaylistsDownloadTransfer.CHUNK * PurplePlaylistsDownloadTransfer.CHUNK;
            try {
                value.port.read(value.document, offset, (bytes, failure, waitSeconds) -> {
                    if (!current() || running.get(value.ticket.index) != value) return;
                    if (failure == PurplePlaylistsDownloadTransfer.Failure.Reference && !value.refreshed) {
                        value.refreshed = true;
                        factory.refresh(value.item, this::current, document -> post(() -> {
                            if (!current() || running.get(value.ticket.index) != value) return;
                            if (document == null || document.id != value.document.id || document.size != value.document.size) {
                                finish(value, PurplePlaylistsDownloadsCore.Phase.Unavailable); return;
                            }
                            value.document = document; next(value);
                        }));
                        return;
                    }
                    if (failure == PurplePlaylistsDownloadTransfer.Failure.Wait) {
                        control.waitSeconds = waitSeconds; stop(Status.Wait); return;
                    }
                    if (failure != null) { finish(value, PurplePlaylistsDownloadsCore.Phase.Failed); return; }
                    try {
                        policy();
                        if (keep) journal.append(value.entry, offset, bytes); else cache.append(value.cached, offset, bytes);
                        next(value);
                    }
                    catch (Exception | LinkageError e) { finish(value, PurplePlaylistsDownloadsCore.Phase.Failed); }
                });
            } catch (RuntimeException e) { finish(value, PurplePlaylistsDownloadsCore.Phase.Failed); }
        }
        void finish(Running value, PurplePlaylistsDownloadsCore.Phase phase) {
            if (running.remove(value.ticket.index) != value) return;
            if (value.port != null) {
                try { value.port.close(); }
                catch (RuntimeException e) { phase = PurplePlaylistsDownloadsCore.Phase.Failed; }
            }
            if (value.cached != null) {
                try { cache.close(value.cached); }
                catch (IOException error) { phase = PurplePlaylistsDownloadsCore.Phase.Failed; }
            }
            if (value.entry != null) {
                if (phase == PurplePlaylistsDownloadsCore.Phase.Downloaded && value.entry.complete())
                    completed.add(new Completed(value.item, value.entry.proof(), value.document));
                try { journal.close(value.entry); }
                catch (IOException e) { if (phase != PurplePlaylistsDownloadsCore.Phase.Downloaded) phase = PurplePlaylistsDownloadsCore.Phase.Failed; }
            }
            if (!job.finish(value.ticket, phase)) { stop(Status.Changed); return; }
            update(); queuePump();
        }
        void stop(Status status) {
            if (terminal) return;
            job.cancel();
            for (Running value : running.values()) {
                if (value.port != null) try { value.port.close(); } catch (RuntimeException ignored) { }
                if (value.entry != null) try { journal.close(value.entry); } catch (IOException ignored) { }
                if (value.cached != null) try { cache.close(value.cached); } catch (IOException ignored) { }
            }
            running.clear(); closeCache(); capture(); terminal = true; job.close(); factory.cancel();
            control.status = status; control.publish();
        }
    }
    @Override public void close() {
        closed = true;
        if (batch != null) batch.stop(Status.Canceled);
        factory.close();
    }
}
