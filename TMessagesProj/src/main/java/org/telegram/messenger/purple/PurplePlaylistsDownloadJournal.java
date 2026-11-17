package org.telegram.messenger.purple;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

final class PurplePlaylistsDownloadJournal {
    static final class Entry {
        final String playlist, token, extension;
        final long document, size;
        final PurplePlaylistsView.Song song;
        final PurplePlaylistsView.Source source;
        private String identity, sha256;
        private long offset;
        private boolean complete, closed;
        private final PurplePlaylistsDownloadJournal owner;
        private Entry(PurplePlaylistsDownloadJournal owner, String playlist, String token, long document,
                String extension, long size, PurplePlaylistsView.Song song, PurplePlaylistsView.Source source) {
            this.owner = owner; this.playlist = playlist; this.token = token; this.document = document;
            this.extension = extension; this.size = size; this.song = song; this.source = source;
        }
        long offset() { return offset; }
        boolean complete() { return complete; }
        PurplePlaylistsPhysical.MediaFile proof() {
            return new PurplePlaylistsPhysical.MediaFile(identity, size, sha256);
        }
    }
    private final Thread thread = Thread.currentThread();
    private final PurplePlaylistsStore store;
    private final PurplePlaylistsPhysical files;
    private final long self;
    private final PurplePlaylistsStore.Current current;
    private boolean busy;

    PurplePlaylistsDownloadJournal(PurplePlaylistsStore store, long self, PurplePlaylistsStore.Current current)
            throws IOException {
        if (store == null || self <= 0 || current == null) throw new IOException("MissingDownloadAuthority");
        this.store = store; this.files = store.media(); this.self = self; this.current = current;
    }
    private void enter() throws IOException {
        if (Thread.currentThread() != thread || busy) throw new IOException("DownloadJournalBusy");
        if (!current.current()) throw new IOException("DownloadAuthorityChanged");
        busy = true;
    }
    private void owns(Entry entry) throws IOException {
        if (entry == null || entry.owner != this || entry.closed) throw new IOException("DownloadEntryChanged");
    }
    private static boolean same(PurplePlaylistsView.Source a, PurplePlaylistsView.Source b) {
        return a != null && b != null && a.messageId == b.messageId && a.peer.equals(b.peer);
    }
    private PurplePlaylistsStore.Read read(Entry entry) throws IOException {
        if (!current.current()) throw new IOException("DownloadAuthorityChanged");
        PurplePlaylistsStore.Read value = store.read();
        if (!value.ready()) throw new IOException("DownloadStoreUnavailable");
        PurplePlaylistsView view = PurplePlaylistsView.project(value.library(), value.state(), self, entry.playlist);
        boolean member = false;
        if (view.valid() && view.detail != null && !view.detail.row.deleted) {
            for (PurplePlaylistsView.Song song : view.detail.songs) {
                if (!song.documentId.equals(Long.toString(entry.document))) continue;
                member |= same(song.source, entry.source);
                for (PurplePlaylistsView.Source source : song.alternatives) member |= same(source, entry.source);
            }
        }
        if (!member || !current.current()) throw new IOException("DownloadSourceChanged");
        return value;
    }
    private PurplePlaylistsStore.Current guard(PurplePlaylistsStore.Read expected) {
        return () -> current.current() && store.stillCurrent(expected);
    }
    private PurplePlaylistsStore.Read save(Entry entry, PurplePlaylistsStore.Read expected,
            PurplePlaylistsDownloadsCore.Result update) throws IOException {
        if (!update.ready() || !guard(expected).current()) throw new IOException("DownloadInventoryChanged");
        byte[] local = update.local();
        PurplePlaylistsStore.Write written = store.write(expected, expected.library(), local, PurplePlaylistsStore.Kind.LocalEdit);
        if (!written.ready()) throw new IOException("DownloadCheckpointUnconfirmed");
        PurplePlaylistsStore.Read actual = read(entry);
        if (!Arrays.equals(actual.library(), expected.library()) || !Arrays.equals(actual.state(), local))
            throw new IOException("DownloadCheckpointReadbackChanged");
        return actual;
    }
    private PurplePlaylistsDownloadsCore.Record record(PurplePlaylistsStore.Read read, long document) throws IOException {
        PurplePlaylistsDownloadsCore.Record found = null;
        for (PurplePlaylistsDownloadsCore.Record value : PurplePlaylistsDownloadsCore.records(read.state(), self)) {
            if (value.document != document) continue;
            if (found != null) throw new IOException("DuplicateDownloadInventory");
            found = value;
        }
        return found;
    }
    private void exact(Entry entry, PurplePlaylistsDownloadsCore.Record record) throws IOException {
        if (record == null || record.document != entry.document || record.size != entry.size
                || !record.extension.equals(entry.extension)
                || (!record.complete && (!record.playlist.equals(entry.playlist) || !record.token.equals(entry.token)))
                || (entry.identity != null && !record.identity.equals(entry.identity))
                || (entry.sha256 != null && !record.sha256.equals(entry.sha256)))
            throw new IOException("DownloadInventoryChanged");
    }
    Entry prepare(String playlist, PurplePlaylistsView.Song song, PurplePlaylistsView.Source source,
            long document, String extension, long size) throws IOException {
        enter();
        try {
            if (playlist == null || song == null || source == null || document <= 0 || size <= 0
                    || !Long.toString(document).equals(song.documentId)) throw new IOException("InvalidDownloadSource");
            Entry provisional = new Entry(this, playlist, "", document, extension, size, song, source);
            PurplePlaylistsStore.Read state = read(provisional);
            PurplePlaylistsDownloadsCore.Record old = record(state, document);
            String token = old != null && !old.complete ? old.token : UUID.randomUUID().toString().replace("-", "");
            Entry entry = new Entry(this, playlist, token, document, extension, size, song, source);
            if (old != null) {
                exact(entry, old); entry.identity = old.identity; entry.sha256 = old.sha256;
                if (old.complete) {
                    files.inspectMedia(document, extension, entry.proof(), guard(state));
                    entry.offset = size; entry.complete = true; return entry;
                }
                if (!old.sha256.isEmpty()) {
                    boolean published;
                    try { files.inspectMedia(document, extension, entry.proof(), guard(state)); published = true; }
                    catch (IOException e) { published = false; }
                    if (published) { complete(entry, state); return entry; }
                }
            } else {
                state = save(entry, state, PurplePlaylistsDownloadsCore.reserve(state.state(), self,
                        playlist, token, document, extension, size));
                exact(entry, record(state, document));
                entry.identity = ""; entry.sha256 = "";
            }
            try {
                PurplePlaylistsPhysical.MediaFile made = files.beginMedia(token, size, entry.identity, guard(state));
                entry.identity = made.identity; entry.offset = made.size;
                state = save(entry, state, PurplePlaylistsDownloadsCore.checkpoint(state.state(), self,
                        playlist, token, document, extension, size, entry.identity, entry.sha256));
                exact(entry, record(state, document));
                return entry;
            } catch (IOException | RuntimeException e) {
                files.closeMedia(token);
                throw e;
            }
        } finally { busy = false; }
    }
    long append(Entry entry, long offset, byte[] bytes) throws IOException {
        enter();
        try {
            owns(entry);
            if (entry.complete) throw new IOException("DownloadAlreadyComplete");
            PurplePlaylistsStore.Read state = read(entry); exact(entry, record(state, entry.document));
            entry.offset = files.appendMediaOverlap(entry.token, entry.identity, offset, bytes, guard(state));
            return entry.offset;
        } finally { busy = false; }
    }
    void finish(Entry entry) throws IOException {
        enter();
        try {
            owns(entry);
            if (entry.complete) return;
            PurplePlaylistsStore.Read state = read(entry); exact(entry, record(state, entry.document));
            PurplePlaylistsPhysical.MediaFile sealed = files.sealMedia(entry.token, entry.identity, guard(state));
            if (!entry.sha256.isEmpty() && !entry.sha256.equals(sealed.sha256)) throw new IOException("DownloadDigestChanged");
            state = save(entry, state, PurplePlaylistsDownloadsCore.checkpoint(state.state(), self,
                    entry.playlist, entry.token, entry.document, entry.extension, entry.size, entry.identity, sealed.sha256));
            entry.sha256 = sealed.sha256; exact(entry, record(state, entry.document));
            files.publishMedia(entry.token, entry.document, entry.extension, entry.proof(), guard(state));
            complete(entry, state);
        } finally { busy = false; }
    }
    private void complete(Entry entry, PurplePlaylistsStore.Read state) throws IOException {
        state = save(entry, state, PurplePlaylistsDownloadsCore.complete(state.state(), self,
                entry.playlist, entry.token, entry.document, entry.extension, entry.size, entry.identity, entry.sha256));
        PurplePlaylistsDownloadsCore.Record actual = record(state, entry.document); exact(entry, actual);
        if (!actual.complete) throw new IOException("DownloadCompletionNotCommitted");
        files.inspectMedia(entry.document, entry.extension, entry.proof(), guard(state));
        entry.complete = true; entry.offset = entry.size;
    }
    void close(Entry entry) throws IOException {
        if (Thread.currentThread() != thread || busy) throw new IOException("DownloadJournalBusy");
        owns(entry);
        files.closeMedia(entry.token); entry.closed = true;
    }
}
