package org.telegram.messenger.purple;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class PurplePlaylistsRetained {
    private static final Map<String, Binding> paths = new LinkedHashMap<String, Binding>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Binding> entry) { return size() > 1024; }
    };
    static final class Mark {
        final File file;
        final StructStat stat;
        final boolean directory;
        Mark(File file, boolean directory) throws Exception {
            this.file = file; this.directory = directory; stat = Os.lstat(file.getAbsolutePath());
            if (stat.st_uid != Os.getuid() || (stat.st_mode & 07777) != (directory ? 0700 : 0600)
                    || (directory ? !OsConstants.S_ISDIR(stat.st_mode)
                        : !OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1))
                throw new IOException("RetainedPermissionsChanged");
        }
        boolean same() throws Exception {
            Mark now = new Mark(file, directory);
            StructStat value = now.stat;
            return value.st_dev == stat.st_dev && value.st_ino == stat.st_ino
                    && (directory || (value.st_size == stat.st_size
                        && value.st_mtim.tv_sec == stat.st_mtim.tv_sec && value.st_mtim.tv_nsec == stat.st_mtim.tv_nsec
                        && value.st_ctim.tv_sec == stat.st_ctim.tv_sec && value.st_ctim.tv_nsec == stat.st_ctim.tv_nsec));
        }
    }
    static final class Binding {
        final File file;
        final List<Mark> marks;
        final PurplePlaylistsStore.Current current;
        Binding(File file, List<Mark> marks, PurplePlaylistsStore.Current current) {
            this.file = file; this.marks = marks; this.current = current;
        }
        boolean valid() {
            try {
                if (!current.current()) return false;
                for (Mark mark : marks) if (!mark.same()) return false;
                return current.current();
            } catch (Exception | LinkageError e) { return false; }
        }
    }
    private static String key(int account, long document, int dc, long size) {
        return account + ":" + document + ":" + dc + ":" + size;
    }
    public static File path(int account, TLObject attachment, boolean forceCache) {
        if (forceCache || !(attachment instanceof TLRPC.TL_document)) return null;
        TLRPC.Document document = (TLRPC.Document) attachment;
        if (document.id <= 0 || document.dc_id <= 0 || document.size <= 0 || document.key != null || document.iv != null
                || (document.localPath != null && !document.localPath.isEmpty())) return null;
        String key = key(account, document.id, document.dc_id, document.size);
        Binding value;
        synchronized (paths) { value = paths.get(key); }
        if (value == null) return null;
        if (value.valid()) return value.file;
        synchronized (paths) { if (paths.get(key) == value) paths.remove(key); }
        return null;
    }
    public static boolean protects(File file) {
        if (file == null) return false;
        String root = PurpleAccountFiles.root().getAbsoluteFile().toPath().normalize().toString() + File.separator;
        String value = file.getAbsoluteFile().toPath().normalize().toString();
        if (!value.startsWith(root)) return false;
        String relative = value.substring(root.length());
        return relative.startsWith("playlists/files/") || relative.contains("/playlists/files/");
    }
    private static boolean same(PurplePlaylistsView.Source first, PurplePlaylistsView.Source second) {
        return first != null && second != null && first.messageId == second.messageId && first.peer.equals(second.peer);
    }
    private static PurplePlaylistsDownloadsCore.Record completeRecord(PurplePlaylistsStore.Read read,
            long self, long document) {
        PurplePlaylistsDownloadsCore.Record found = null;
        for (PurplePlaylistsDownloadsCore.Record record : PurplePlaylistsDownloadsCore.records(read.state(), self)) {
            if (record.document != document) continue;
            if (found != null || !record.complete) return null;
            found = record;
        }
        return found;
    }
    static boolean publish(PurplePlaylistsStore store, long self, PurplePlaylistsWorker worker, String playlist,
            PurplePlaylistsView.Song selected, PurplePlaylistsView.Source source,
            PurplePlaylistsDownloadTransfer.Document document, PurplePlaylistsStore.Current scope) throws Exception {
        return publish(store, store.read(), self, worker, playlist, selected, source, document, scope);
    }
    private static boolean publish(PurplePlaylistsStore store, PurplePlaylistsStore.Read read, long self,
            PurplePlaylistsWorker worker, String playlist, PurplePlaylistsView.Song selected,
            PurplePlaylistsView.Source source, PurplePlaylistsDownloadTransfer.Document document,
            PurplePlaylistsStore.Current scope) throws Exception {
        if (document == null || selected == null || source == null || !scope.current()
                || !worker.downloadCurrent() || !read.ready() || !store.stillCurrent(read)) return false;
        PurplePlaylistsView view = PurplePlaylistsView.project(read.library(), read.state(), self, playlist);
        boolean member = false;
        if (view.valid() && view.detail != null && !view.detail.row.deleted) {
            for (PurplePlaylistsView.Song song : view.detail.songs) {
                if (!song.documentId.equals(Long.toString(document.id)) || !song.documentId.equals(selected.documentId)) continue;
                member |= same(song.source, source);
                for (PurplePlaylistsView.Source alternative : song.alternatives) member |= same(alternative, source);
            }
        }
        if (!member) return false;
        PurplePlaylistsDownloadsCore.Record found = completeRecord(read, self, document.id);
        if (found == null || found.size != document.size) return false;
        PurplePlaylistsPhysical files = store.media();
        File directory = files.mediaDirectory();
        File file = new File(directory, document.id + "." + found.extension);
        List<Mark> marks = new ArrayList<>();
        File anchor = PurpleAccountFiles.root().getAbsoluteFile();
        File parent = directory.getAbsoluteFile();
        while (true) {
            marks.add(new Mark(parent, true));
            if (parent.equals(anchor)) break;
            parent = parent.getParentFile();
            if (parent == null) throw new IOException("RetainedRootChanged");
        }
        marks.add(new Mark(new File(anchor, "accounts"), true));
        marks.add(new Mark(new File(anchor, "local.toml"), false));
        marks.add(new Mark(new File(anchor, "accounts/root.toml"), false));
        marks.add(new Mark(new File(directory.getParentFile(), "library.json"), false));
        marks.add(new Mark(new File(directory.getParentFile(), "local.json"), false));
        Mark media = new Mark(file, false); marks.add(media);
        if (!(media.stat.st_dev + ":" + media.stat.st_ino).equals(found.identity)
                || media.stat.st_size != found.size) return false;
        PurplePlaylistsStore.Current current = worker::downloadCurrent;
        Binding binding = new Binding(file, marks, current);
        PurplePlaylistsPhysical.MediaFile proof = new PurplePlaylistsPhysical.MediaFile(found.identity, found.size, found.sha256);
        files.inspectMedia(document.id, found.extension, proof, () -> current.current() && scope.current() && store.stillCurrent(read));
        if (!scope.current() || !binding.valid() || !store.stillCurrent(read)) return false;
        synchronized (paths) {
            if (!scope.current() || !binding.valid()) return false;
            paths.put(key(worker.accountSlot(), document.id, document.dc, document.size), binding);
        }
        return true;
    }
    static AutoCloseable prepareOffline(PurplePlaylistsAccount.Captured account, String playlist,
            PurplePlaylistsView.Song song, java.util.function.BooleanSupplier scope,
            Consumer<PurplePlaylistsResolver.Result> done) {
        AtomicBoolean canceled = new AtomicBoolean(), delivered = new AtomicBoolean();
        PurplePlaylistsWorker worker = PurplePlaylistsWorkers.acquire(account.identity.slot);
        PurplePlaylistsStore.Current current = () -> !canceled.get() && scope.getAsBoolean() && account.current();
        PurplePlaylistsResolver.Result unavailable = new PurplePlaylistsResolver.Result(song, song.source,
                PurplePlaylistsResolver.Status.Failed, null, 0);
        Consumer<PurplePlaylistsResolver.Result> deliver = result -> {
            if (delivered.compareAndSet(false, true))
                AndroidUtilities.runOnUIThread(() -> { if (current.current()) done.accept(result); });
        };
        Runnable refused = () -> deliver.accept(unavailable);
        if (worker == null || !worker.matches(account) || !worker.postStore(null, (store, self) -> {
            PurplePlaylistsResolver.Result result = null;
            try {
                PurplePlaylistsStore.Read read = store.read();
                if (read.ready() && current.current()) {
                    PurplePlaylistsDownloadsCore.Record record = completeRecord(read, self, Long.parseLong(song.documentId));
                    if (record != null) {
                        result = PurplePlaylistsOfflineMusic.restore(read.state(), self, record, song, account.identity.slot);
                        if (result != null) {
                            PurplePlaylistsDownloadTransfer.Document document =
                                    new PurplePlaylistsDownloadTransfer.Document(result.message.media.document);
                            if (!publish(store, read, self, worker, playlist, song, result.source, document, current))
                                result = null;
                        }
                    }
                }
            } catch (Exception | LinkageError ignored) { result = null; }
            deliver.accept(result != null ? result : unavailable);
        }, refused, false)) refused.run();
        return () -> canceled.set(true);
    }

    static AutoCloseable prepare(PurplePlaylistsAccount.Captured account, String playlist,
            PurplePlaylistsResolver.Result result, java.util.function.BooleanSupplier scope,
            Consumer<PurplePlaylistsResolver.Result> done) {
        AtomicBoolean canceled = new AtomicBoolean();
        PurplePlaylistsWorker worker = PurplePlaylistsWorkers.acquire(account.identity.slot);
        PurplePlaylistsStore.Current current = () -> !canceled.get() && scope.getAsBoolean() && account.current();
        AtomicBoolean delivered = new AtomicBoolean();
        Runnable deliver = () -> {
            if (delivered.compareAndSet(false, true))
                AndroidUtilities.runOnUIThread(() -> { if (current.current()) done.accept(result); });
        };
        if (worker == null || !worker.matches(account) || result == null || !result.playable()) {
            deliver.run(); return () -> canceled.set(true);
        }
        final PurplePlaylistsDownloadTransfer.Document document;
        try { document = new PurplePlaylistsDownloadTransfer.Document(result.message.media.document); }
        catch (RuntimeException e) { deliver.run(); return () -> canceled.set(true); }
        if (!worker.postStore(null, (store, self) -> {
            boolean retained = false;
            try {
                if (current.current()) {
                    PurplePlaylistsStore.Read read = store.read();
                    retained = publish(store, read, self, worker, playlist, result.song, result.source, document, current);
                    if (retained) {
                        byte[] local = read.state();
                        byte[] updated = PurplePlaylistsOfflineMusic.record(local, self, document.id, result.source,
                                PurplePlaylistsOfflineMusic.capture(result));
                        if (!Arrays.equals(local, updated)) {
                            store.write(read, read.library(), updated, PurplePlaylistsStore.Kind.LocalEdit, current);
                            publish(store, self, worker, playlist, result.song, result.source, document, current);
                        }
                    }
                }
            } catch (Exception | LinkageError ignored) { }
            if (!retained && current.current()) {
                try { PurplePlaylistsCache.restore(store, self, worker, playlist, result, current); }
                catch (Exception | LinkageError ignored) { }
            }
            deliver.run();
        }, deliver)) deliver.run();
        return () -> canceled.set(true);
    }
    private PurplePlaylistsRetained() { throw new AssertionError(); }
}
