package org.telegram.messenger.purple;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class PurplePlaylistsDownloadTransfer implements PurplePlaylistsDownloads.Port {
    static final int CHUNK = 262144;
    enum Failure { Changed, Reference, Wait, Unavailable, InvalidReply, Failed }
    interface Dispatcher { boolean post(Runnable task); }
    interface Completion { void complete(byte[] bytes, Failure failure, int waitSeconds); }
    static final class Document {
        final long id, size, accessHash;
        final int dc;
        private final byte[] reference;
        Document(TLRPC.Document document) {
            if (!(document instanceof TLRPC.TL_document) || document.id <= 0 || document.size <= 0
                    || document.dc_id <= 0 || document.key != null || document.iv != null
                    || (document.file_reference != null && document.file_reference.length > 1048576))
                throw new IllegalArgumentException("InvalidOwnedDownloadDocument");
            id = document.id; size = document.size; accessHash = document.access_hash; dc = document.dc_id;
            reference = document.file_reference == null ? new byte[0] : document.file_reference.clone();
        }
    }
    private final Thread thread = Thread.currentThread();
    private final PurplePlaylistsAccount.Captured account;
    private final PurplePlaylistsStore.Current scope;
    private final ConnectionsManager connection;
    private final Dispatcher dispatcher;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicInteger request = new AtomicInteger();
    private boolean reading;

    PurplePlaylistsDownloadTransfer(PurplePlaylistsAccount.Captured account,
            PurplePlaylistsStore.Current scope, Dispatcher dispatcher) {
        if (account == null || scope == null || dispatcher == null) throw new IllegalArgumentException("MissingOwnedTransfer");
        this.account = account; this.scope = scope; this.dispatcher = dispatcher;
        connection = ConnectionsManager.getInstance(account.identity.slot);
    }
    private boolean current() {
        return !closed.get() && account.current() && scope.current()
                && connection == ConnectionsManager.getInstance(account.identity.slot);
    }
    @Override public void read(Document document, long offset, Completion done) {
        if (Thread.currentThread() != thread || reading || done == null)
            throw new IllegalStateException("OwnedTransferBusy");
        if (!current()) { done.complete(null, Failure.Changed, 0); return; }
        if (document == null || offset < 0 || offset >= document.size || offset % CHUNK != 0)
            throw new IllegalArgumentException("InvalidOwnedTransferRange");
        TLRPC.TL_inputDocumentFileLocation location = new TLRPC.TL_inputDocumentFileLocation();
        location.id = document.id; location.access_hash = document.accessHash;
        location.file_reference = document.reference.clone(); location.thumb_size = "";
        TLRPC.TL_upload_getFile get = new TLRPC.TL_upload_getFile();
        get.location = location; get.offset = offset; get.limit = CHUNK;
        get.cdn_supported = false;
        long epoch = generation.incrementAndGet(); reading = true;
        AtomicBoolean delivered = new AtomicBoolean();
        try {
            int token = connection.sendRequest(get, (response, error) -> {
                if (!delivered.compareAndSet(false, true)) return;
                byte[] bytes = null; Failure failure = null; int wait = 0;
                if (!current() || generation.get() != epoch) return;
                if (error != null) {
                    String text = error.text == null ? "" : error.text;
                    if (text.startsWith("FILE_REFERENCE_") || text.equals("FILEREF_UPGRADE_NEEDED")) failure = Failure.Reference;
                    else if (text.startsWith("FLOOD_WAIT_") || text.startsWith("FLOOD_PREMIUM_WAIT_")) {
                        failure = Failure.Wait;
                        try { wait = Integer.parseInt(text.substring(text.lastIndexOf('_') + 1)); }
                        catch (NumberFormatException ignored) { wait = 1; }
                        wait = Math.max(1, wait);
                    } else if (text.equals("FILE_ID_INVALID") || text.equals("LOCATION_INVALID")) failure = Failure.Unavailable;
                    else failure = Failure.Failed;
                } else if (!(response instanceof TLRPC.TL_upload_file)) failure = Failure.InvalidReply;
                else {
                    TLRPC.TL_upload_file file = (TLRPC.TL_upload_file) response;
                    try {
                        int count = file.bytes == null ? 0 : file.bytes.remaining();
                        if (count <= 0 || count > CHUNK || count > document.size - offset
                                || (count < CHUNK && count != document.size - offset)) failure = Failure.InvalidReply;
                        else { bytes = new byte[count]; file.bytes.readBytes(bytes, true); }
                    } catch (RuntimeException e) { failure = Failure.InvalidReply; bytes = null; }
                }
                byte[] copied = bytes; Failure outcome = failure; int waitSeconds = wait;
                if (!dispatcher.post(() -> {
                    if (!current() || generation.get() != epoch || !reading) return;
                    generation.incrementAndGet(); request.set(0); reading = false;
                    done.complete(copied, outcome, waitSeconds);
                })) {
                    if (generation.compareAndSet(epoch, epoch + 1)) {
                        closed.set(true);
                        int owned = request.getAndSet(0);
                        if (owned != 0) connection.cancelRequest(owned, true);
                    }
                }
            }, null, null, 0, document.dc, ConnectionsManager.ConnectionTypeDownload, true);
            request.set(token);
            if (closed.get() || generation.get() != epoch) {
                if (request.compareAndSet(token, 0)) connection.cancelRequest(token, true);
            }
        } catch (RuntimeException e) {
            generation.incrementAndGet(); reading = false;
            int owned = request.getAndSet(0); if (owned != 0) connection.cancelRequest(owned, true);
            done.complete(null, Failure.Failed, 0);
        }
    }
    @Override public void close() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("WrongOwnedTransferThread");
        if (closed.getAndSet(true)) return;
        generation.incrementAndGet(); reading = false;
        int owned = request.getAndSet(0);
        if (owned != 0) connection.cancelRequest(owned, true);
    }
}
