package org.telegram.messenger.purple;

import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import org.telegram.messenger.AutoDeleteMediaTask;
import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PurplePlaylistsCache implements AutoCloseable {
    private static final Map<String, PurplePlaylistsRetained.Binding> paths =
            new LinkedHashMap<String, PurplePlaylistsRetained.Binding>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, PurplePlaylistsRetained.Binding> entry) {
            return size() > 1024;
        }
    };
    private static final Map<String, Integer> locks = new java.util.HashMap<>();
    private static void protect(File file) {
        synchronized (locks) {
            String path = file.getAbsolutePath();
            int count = locks.containsKey(path) ? locks.get(path) : 0;
            locks.put(path, count + 1);
            if (count == 0) AutoDeleteMediaTask.lockFile(file);
        }
    }
    private static void unprotect(File file) {
        synchronized (locks) {
            String path = file.getAbsolutePath();
            Integer count = locks.get(path);
            if (count == null) throw new IllegalStateException("UnownedCacheLock");
            if (count > 1) locks.put(path, count - 1);
            else { locks.remove(path); AutoDeleteMediaTask.unlockFile(file); }
        }
    }
    static final class Entry {
        final PurplePlaylistsDownloads.Item item;
        final String token;
        final File part;
        String identity;
        long offset;
        boolean complete, closed;
        PurplePlaylistsPhysical.MediaFile proof;
        private Entry(PurplePlaylistsDownloads.Item item, String token, File part) {
            this.item = item; this.token = token; this.part = part;
        }
        long offset() { return offset; }
        boolean complete() { return complete; }
    }
    private final PurplePlaylistsStore store;
    private final PurplePlaylistsWorker worker;
    private final long self;
    private final String playlist;
    private final PurplePlaylistsStore.Current scope;
    private final boolean requireOff;
    private final File cacheRoot, root, directory;
    private final android.system.StructStat cacheIdentity;
    private final PurplePlaylistsPhysical files;
    private final List<File> protectedFiles = new ArrayList<>();
    private boolean closed;

    static boolean keeps(PurplePlaylistsStore.Read read, long self, int slot, String playlist) throws IOException {
        if (read == null || !read.ready()) throw new IOException("DownloadStoreUnavailable");
        PurplePlaylistsDownloadsCore.Result value = PurplePlaylistsDownloadsCore.read(read.state(), self);
        if (!value.ready()) throw new IOException("DownloadInventoryUnavailable");
        PurplePlaylistsDownloadsCore.Preference preference = value.preference(playlist);
        if (preference == PurplePlaylistsDownloadsCore.Preference.On) return true;
        if (preference == PurplePlaylistsDownloadsCore.Preference.Off) return false;
        PurpleAccountSettings.Effective settings = PurpleAccountSettings.forAccount(slot);
        if (settings == null || !settings.current() || !settings.playlistsReady)
            throw new IOException("DownloadDefaultUnavailable");
        return settings.playlistsKeepDownloaded;
    }
    private static boolean same(PurplePlaylistsView.Source first, PurplePlaylistsView.Source second) {
        return first != null && second != null && first.messageId == second.messageId && first.peer.equals(second.peer);
    }
    private PurplePlaylistsStore.Read authority(PurplePlaylistsDownloads.Item item) throws IOException {
        if (closed || !scope.current() || !worker.downloadCurrent() || !cacheRootCurrent()) throw new IOException("CacheAuthorityChanged");
        PurplePlaylistsStore.Read read = store.read();
        if (requireOff && keeps(read, self, worker.accountSlot(), playlist)) throw new IOException("CachePolicyChanged");
        PurplePlaylistsView view = PurplePlaylistsView.project(read.library(), read.state(), self, playlist);
        boolean member = false;
        if (view.valid() && view.detail != null && !view.detail.row.deleted && !view.detail.row.partial) {
            for (PurplePlaylistsView.Song song : view.detail.songs) {
                if (!song.documentId.equals(item.song.documentId)) continue;
                member |= same(song.source, item.source);
                for (PurplePlaylistsView.Source source : song.alternatives) member |= same(source, item.source);
            }
        }
        if (!member || !scope.current()) throw new IOException("CacheSourceChanged");
        return read;
    }
    PurplePlaylistsCache(PurplePlaylistsStore store, long self, PurplePlaylistsWorker worker,
            String playlist, PurplePlaylistsStore.Current scope) throws Exception {
        this(store, self, worker, playlist, scope, true);
    }
    private PurplePlaylistsCache(PurplePlaylistsStore store, long self, PurplePlaylistsWorker worker,
            String playlist, PurplePlaylistsStore.Current scope, boolean requireOff) throws Exception {
        this.store = store; this.self = self; this.worker = worker; this.playlist = playlist; this.scope = scope;
        this.requireOff = requireOff;
        File cache = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE).getAbsoluteFile();
        cacheRoot = cache;
        android.system.StructStat parent = Os.lstat(cache.getAbsolutePath());
        cacheIdentity = parent;
        if (!OsConstants.S_ISDIR(parent.st_mode) || parent.st_uid != Os.getuid())
            throw new IOException("CacheRootUnavailable");
        root = new File(cache, "purple-playlists");
        try { Os.mkdir(root.getAbsolutePath(), 0700); }
        catch (android.system.ErrnoException error) { if (error.errno != OsConstants.EEXIST) throw error; }
        new PurplePlaylistsRetained.Mark(root, true);
        String owner = worker.accountSlot() + "-" + self;
        File account = new File(root, owner);
        lock(new File(account, "writer.lock")); lock(new File(account, "local.json"));
        PurplePlaylistsPhysical opened = null;
        try {
            opened = new PurplePlaylistsPhysical(root.getAbsolutePath(), owner,
                    () -> !closed && worker.downloadCurrent() && cacheRootCurrent(), true);
            android.system.StructStat after = Os.lstat(cache.getAbsolutePath());
            if (parent.st_dev != after.st_dev || parent.st_ino != after.st_ino || parent.st_mode != after.st_mode)
                throw new IOException("CacheRootChanged");
            files = opened; directory = files.mediaDirectory();
        } catch (Exception | LinkageError error) {
            try { if (opened != null) opened.close(); }
            finally { for (File file : protectedFiles) unprotect(file); }
            throw error;
        }
    }
    private void lock(File file) { protectedFiles.add(file); protect(file); }
    private boolean cacheRootCurrent() {
        try {
            android.system.StructStat now = Os.lstat(cacheRoot.getAbsolutePath());
            return now.st_dev == cacheIdentity.st_dev && now.st_ino == cacheIdentity.st_ino
                    && now.st_mode == cacheIdentity.st_mode && now.st_uid == cacheIdentity.st_uid;
        } catch (Exception | LinkageError error) { return false; }
    }
    private boolean matches(PurplePlaylistsStore.File state) {
        try { return files.matches(PurplePlaylistsStore.Name.Local, state); }
        catch (IOException error) { return false; }
    }
    private PurplePlaylistsStore.Current guard(PurplePlaylistsStore.Read read) {
        return () -> {
            try { return scope.current() && cacheRootCurrent() && store.stillCurrent(read)
                    && (!requireOff || !keeps(read, self, worker.accountSlot(), playlist)); }
            catch (IOException | RuntimeException | LinkageError error) { return false; }
        };
    }
    private static String key(int account, long document, int dc, long size) {
        return account + ":" + document + ":" + dc + ":" + size;
    }
    private static String name(PurplePlaylistsDownloads.Item item) { return item.document.id + "." + item.extension; }
    private JSONObject index(PurplePlaylistsStore.File value) throws Exception {
        if (value.absent()) return new JSONObject().put("format", 1).put("files", new JSONObject());
        JSONObject result = new JSONObject(new String(value.bytes(), StandardCharsets.UTF_8));
        if (!(result.get("format") instanceof Number) || result.getDouble("format") != 1
                || result.getJSONObject("files").length() > 1024)
            throw new IOException("CacheIndexUnavailable");
        return result;
    }
    Entry prepare(PurplePlaylistsDownloads.Item item) throws Exception {
        return prepare(item, true);
    }
    private Entry prepare(PurplePlaylistsDownloads.Item item, boolean create) throws Exception {
        PurplePlaylistsStore.Read read = authority(item);
        PurplePlaylistsStore.File state = files.read(PurplePlaylistsStore.Name.Local);
        JSONObject records = index(state).getJSONObject("files");
        File target = new File(directory, name(item));
        Entry entry = new Entry(item, UUID.randomUUID().toString().replace("-", ""), null);
        if (records.has(name(item)) && target.exists()) {
            JSONObject saved = records.getJSONObject(name(item));
            if (!Long.toString(item.document.size).equals(saved.getString("size"))) throw new IOException("CacheSizeChanged");
            entry.proof = new PurplePlaylistsPhysical.MediaFile(saved.getString("identity"), item.document.size, saved.getString("sha256"));
            files.inspectMedia(item.document.id, item.extension, entry.proof,
                    () -> guard(read).current() && matches(state));
            entry.identity = entry.proof.identity; entry.offset = item.document.size; entry.complete = true;
            bind(entry, item.document); return entry;
        }
        if (!create) return null;
        entry = new Entry(item, entry.token, new File(new File(directory, ".incoming"), entry.token + ".part"));
        lock(entry.part);
        PurplePlaylistsPhysical.MediaFile made = files.beginMedia(entry.token, item.document.size, "",
                guard(read));
        entry.identity = made.identity; entry.offset = made.size;
        return entry;
    }
    void append(Entry entry, long offset, byte[] bytes) throws Exception {
        PurplePlaylistsStore.Read read = authority(entry.item);
        if (entry.closed || entry.complete) throw new IOException("CacheEntryClosed");
        entry.offset = files.appendMediaOverlap(entry.token, entry.identity, offset, bytes,
                guard(read));
    }
    private void capacity(JSONObject records, String incoming, PurplePlaylistsStore.Read read,
            PurplePlaylistsStore.File before) throws Exception {
        if (records.length() < 1024 || records.has(incoming)) return;
        List<String> names = new ArrayList<>();
        java.util.Iterator<String> keys = records.keys();
        while (keys.hasNext()) names.add(keys.next());
        java.util.Collections.sort(names);
        for (String name : names) {
            if (!name.matches("[1-9][0-9]{0,18}\\.(mp3|ogg|flac|m4a|audio)")) continue;
            long document;
            int dot = name.indexOf('.');
            try { document = Long.parseLong(name.substring(0, dot)); }
            catch (NumberFormatException error) { continue; }
            JSONObject saved = records.optJSONObject(name);
            if (saved == null || !saved.optString("identity").matches("[0-9]+:[0-9]+")
                    || !saved.optString("size").matches("[1-9][0-9]*")
                    || !saved.optString("sha256").matches("[0-9a-f]{64}")) continue;
            try {
                files.confirmMediaAbsent(document, name.substring(dot + 1),
                        () -> guard(read).current() && matches(before));
            } catch (IOException error) {
                if (!guard(read).current() || !matches(before)) throw error;
                continue;
            }
            records.remove(name);
            return;
        }
        throw new IOException("CacheIndexFull");
    }
    void finish(Entry entry, PurplePlaylistsDownloadTransfer.Document document) throws Exception {
        PurplePlaylistsStore.Read read = authority(entry.item);
        PurplePlaylistsStore.File before = files.read(PurplePlaylistsStore.Name.Local);
        JSONObject index = index(before), records = index.getJSONObject("files");
        capacity(records, name(entry.item), read, before);
        entry.proof = files.sealMedia(entry.token, entry.identity, () -> guard(read).current() && matches(before));
        records.put(name(entry.item), new JSONObject().put("identity", entry.proof.identity)
                .put("size", Long.toString(entry.proof.size)).put("sha256", entry.proof.sha256));
        PurplePlaylistsStore.Publication written = files.replace(PurplePlaylistsStore.Name.Local, before,
                index.toString().getBytes(StandardCharsets.UTF_8), guard(read));
        if (!written.durable || !guard(read).current() || !matches(written.file)) throw new IOException("CacheIndexUnconfirmed");
        files.publishMedia(entry.token, document.id, entry.item.extension, entry.proof,
                () -> guard(read).current() && matches(written.file));
        entry.complete = true; entry.offset = entry.proof.size; bind(entry, document);
    }
    private void bind(Entry entry, PurplePlaylistsDownloadTransfer.Document document) throws Exception {
        PurplePlaylistsStore.Read read = authority(entry.item);
        File target = new File(directory, name(entry.item));
        List<PurplePlaylistsRetained.Mark> marks = new ArrayList<>();
        for (File current = directory; ; current = current.getParentFile()) {
            if (current == null) throw new IOException("CacheRootChanged");
            marks.add(new PurplePlaylistsRetained.Mark(current, true));
            if (current.equals(root)) break;
        }
        PurplePlaylistsRetained.Mark media = new PurplePlaylistsRetained.Mark(target, false);
        if (!(media.stat.st_dev + ":" + media.stat.st_ino).equals(entry.proof.identity)
                || media.stat.st_size != entry.proof.size) throw new IOException("CacheFileChanged");
        marks.add(media);
        File owner = PurpleAccountFiles.root();
        marks.add(new PurplePlaylistsRetained.Mark(owner, true));
        marks.add(new PurplePlaylistsRetained.Mark(new File(owner, "accounts"), true));
        marks.add(new PurplePlaylistsRetained.Mark(new File(owner, "local.toml"), false));
        marks.add(new PurplePlaylistsRetained.Mark(new File(owner, "accounts/root.toml"), false));
        PurplePlaylistsRetained.Binding binding = new PurplePlaylistsRetained.Binding(target, marks, () -> worker.downloadCurrent() && cacheRootCurrent());
        files.inspectMedia(document.id, entry.item.extension, entry.proof, guard(read));
        if (!guard(read).current() || !binding.valid()) throw new IOException("CacheMappingChanged");
        synchronized (paths) { paths.put(key(worker.accountSlot(), document.id, document.dc, document.size), binding); }
    }
    void close(Entry entry) throws IOException {
        if (entry.closed) return;
        entry.closed = true;
        if (entry.part == null) return;
        try {
            files.closeMedia(entry.token);
            if (!entry.complete) files.discardMedia(entry.token, entry.identity, worker::downloadCurrent);
        } finally { unprotect(entry.part); protectedFiles.remove(entry.part); }
    }
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { files.close(); }
        finally { for (File file : protectedFiles) unprotect(file); protectedFiles.clear(); }
    }
    public static File path(int account, TLObject attachment, boolean forceCache) {
        if (!(attachment instanceof TLRPC.TL_document)) return null;
        TLRPC.Document document = (TLRPC.Document) attachment;
        if (document.id <= 0 || document.dc_id <= 0 || document.size <= 0 || document.key != null || document.iv != null
                || document.localPath != null && !document.localPath.isEmpty()) return null;
        String key = key(account, document.id, document.dc_id, document.size);
        PurplePlaylistsRetained.Binding binding;
        synchronized (paths) { binding = paths.get(key); }
        if (binding == null) return null;
        if (binding.valid()) return binding.file;
        synchronized (paths) { if (paths.get(key) == binding) paths.remove(key); }
        return null;
    }
    static boolean restore(PurplePlaylistsStore store, long self, PurplePlaylistsWorker worker,
            String playlist, PurplePlaylistsResolver.Result result, PurplePlaylistsStore.Current current) throws Exception {
        PurplePlaylistsDownloads.Item item = new PurplePlaylistsDownloads.Item(result,
                PurplePlaylistsDownloads.Item.extension(result.message.media.document.mime_type));
        try (PurplePlaylistsCache cache = new PurplePlaylistsCache(store, self, worker, playlist, current, false)) {
            return cache.prepare(item, false) != null;
        }
    }
}
