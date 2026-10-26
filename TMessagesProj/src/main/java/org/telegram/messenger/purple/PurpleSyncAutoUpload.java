package org.telegram.messenger.purple;

import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.telegram.tgnet.TLRPC;

final class PurpleSyncAutoUpload {
    interface Port {
        void worker(Runnable task);
        int upload(long id, byte[] bytes, PurpleSyncClient.Reply<Boolean> reply);
        void exact(int id, PurpleSyncClient.Reply<byte[]> reply);
        default void cancelReads() {}
        int send(int id, TLRPC.TL_inputFile file, PurpleSyncClient.Reply<Result> reply);
        void cancel(int token);
        void beforePost(PurpleSyncClient.Reply<Boolean> done);
    }
    static final class Result {
        final int id;
        final String error;
        Result(int id, String error) { this.id = id; this.error = error; }
    }
    private volatile Port port;
    private volatile BooleanSupplier current;
    private byte[] bytes, previous;
    private PurpleSyncClient.Reply<Result> reply;
    private final Object token = new Object();
    private final int account, messageId;
    private final PurpleAccountFiles.Identity origin;
    private final java.util.concurrent.atomic.AtomicInteger request = new java.util.concurrent.atomic.AtomicInteger();
    private int serverId;
    private volatile int phase;
    private String error;
    private volatile boolean finished;
    private boolean authorizing;
    PurpleSyncAutoUpload(int account, int messageId, byte[] bytes, byte[] previous, BooleanSupplier current,
        Port port, PurpleSyncClient.Reply<Result> reply) {
        this.account = account; this.messageId = messageId; this.bytes = bytes!=null?bytes.clone():null; this.previous = previous!=null?previous.clone():null;
        this.origin = PurpleAccountFiles.capture(account);
        this.current = current; this.port = port; this.reply = reply;
    }
    synchronized boolean live() { return !finished && current != null && current.getAsBoolean(); }
    void start() {
        PurpleSyncAutoEdits.register(account, token, this::live, outcome -> close(outcome));
        Port issued = port; byte[] data = bytes;
        if (!live() || issued == null || messageId<0 || !PurpleSyncPost.validRecord(data)) { fail(null); return; }
        long id = randomId();
        final TLRPC.TL_inputFile file = new TLRPC.TL_inputFile();
        file.id = id; file.parts = 1; file.name = PurpleSyncPost.RECORD_FILE_NAME;
        try { file.md5_checksum = hex(java.security.MessageDigest.getInstance("MD5").digest(data)); }
        catch (Exception e) { fail(null); return; }
        int sentRequest = issued.upload(id, data, ok -> queue(() -> {
            if (phase != 0) return;
            request.set(0); phase = 1;
            if (!live() || !ok) { fail(null); return; }
            Port active = port;
            if (active == null) { fail(null); return; }
            if (messageId > 0) active.exact(messageId, observed -> queue(() -> {
                if (!live() || !Arrays.equals(previous, observed)) { fail(null); return; }
                authorize(file);
            }));
            else authorize(file);
        }));
        track(issued, sentRequest, 0);
    }
    private void queue(Runnable task) {
        Port value = port;
        if (value != null) value.worker(() -> {
            if(finished){stale();return;}
            try{task.run();}catch(RuntimeException error){fail(null);}
        });
        else stale();
    }
    private void stale() {
        if (!PurpleSyncAutoEdits.finished(account, token, true, null) && origin != null)
            PurpleSyncAuto.reconcile(origin);
    }
    private void authorize(TLRPC.TL_inputFile file) {
        Port issued=port;
        if(phase!=1 || authorizing)return;
        if(!live() || issued==null){fail(null);return;}
        authorizing=true;
        issued.beforePost(allowed->queue(()->{
            if(phase!=1)return;
            if(!Boolean.TRUE.equals(allowed) || !live()){fail(null);return;}
            dispatch(file);
        }));
    }
    private void dispatch(TLRPC.TL_inputFile file) {
        if (phase != 1) return;
        Port issued = port;
        if (!live() || issued == null) { fail(null); return; }
        phase = 2;
        int sentRequest = issued.send(messageId, file, result -> queue(() -> {
            request.set(0);
            if (result == null || !live()) { fail(null); return; }
            serverId = result.id; error = result.error;
            PurpleSyncAutoEdits.finished(account, token, result.error != null || result.id <= 0, result.error);
        }));
        track(issued, sentRequest, 2);
    }
    private void track(Port issued, int token, int expectedPhase) {
        if (token == 0) return;
        if (finished || phase != expectedPhase) { issued.cancel(token); return; }
        request.set(token);
        if (finished && request.compareAndSet(token, 0)) issued.cancel(token);
    }
    private void fail(String error) { this.error = error; PurpleSyncAutoEdits.finished(account, token, true, error); }
    private synchronized void close(PurpleSyncAutoCore.Outcome outcome) {
        if (finished) return;
        finished = true;
        Port old = port; port = null;
        int pending = request.getAndSet(0);
        if (pending != 0 && old != null) old.cancel(pending);
        if (old != null) old.cancelReads();
        current = null; bytes = null; previous = null;
        PurpleSyncClient.Reply<Result> callback = reply; reply = null;
        if (callback != null) callback.onReply(new Result(outcome == PurpleSyncAutoCore.Outcome.Sent ? serverId : 0,
            outcome == PurpleSyncAutoCore.Outcome.EditRefused ? error : outcome == PurpleSyncAutoCore.Outcome.Sent ? null : "OutcomeUnknown"));
    }
    static long randomId() {
        long id;
        do { id = new java.security.SecureRandom().nextLong(); } while (id == 0);
        return id;
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return result.toString();
    }
}
