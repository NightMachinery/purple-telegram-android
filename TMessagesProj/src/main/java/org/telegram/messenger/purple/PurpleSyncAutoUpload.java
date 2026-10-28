package org.telegram.messenger.purple;

import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.telegram.tgnet.TLRPC;

final class PurpleSyncAutoUpload {
    interface Port {
        void worker(Runnable task);
        int upload(long id, byte[] bytes, PurpleSyncClient.Reply<Boolean> reply);
        default int uploadResult(long id,byte[] bytes,PurpleSyncClient.Reply<Result> reply){return upload(id,bytes,ok->reply.onReply(new Result(Boolean.TRUE.equals(ok)?1:0,null)));}
        void exact(int id, PurpleSyncClient.Reply<byte[]> reply);
        default void cancelReads() {}
        int send(int id, TLRPC.TL_inputFile file, PurpleSyncClient.Reply<Result> reply);
        void cancel(int token);
        void beforePost(PurpleSyncClient.Reply<Boolean> done);
        default boolean managedWait(){return false;}
        default void renewCarry(PurpleSyncChatMoveIO.Permit previous,PurpleSyncClient.Reply<PurpleSyncChatMoveIO.Permit> done) { done.onReply(null); }
        default int sendCarry(PurpleSyncChatMoveIO.Permit permit,TLRPC.TL_inputFile file,PurpleSyncClient.Reply<Result> done) { done.onReply(new Result(0,"CarryPortUnavailable")); return 0; }
    }
    static final class Result {
        final int id;
        final String error;
        final int code;
        final PurpleRpcRetry.Transient waiting;
        Result(int id,String error){this(id,error,0,null);}
        Result(int id,String error,int code){this(id,error,code,null);}
        Result(int id,String error,int code,PurpleRpcRetry.Transient waiting){this.id=id;this.error=error;this.code=code;this.waiting=waiting;}
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
    private PurpleRpcRetry.Transient waiting;
    private volatile boolean finished;
    private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean authorizing,started;
    private PurpleSyncChatMoveIO.Permit carry;
    private long authorizationTicket;
    PurpleSyncAutoUpload(int account, int messageId, byte[] bytes, byte[] previous, BooleanSupplier current,
        Port port, PurpleSyncClient.Reply<Result> reply) {
        this.account = account; this.messageId = messageId; this.bytes = bytes!=null?bytes.clone():null; this.previous = previous!=null?previous.clone():null;
        this.origin = PurpleAccountFiles.capture(account);
        this.current = current; this.port = port; this.reply = reply;
    }
    PurpleSyncAutoUpload(PurpleSyncChatMoveIO.Permit permit,BooleanSupplier current,Port port,PurpleSyncClient.Reply<Result> reply) {
        this(permit.origin.slot,0,permit.payload(),null,current,port,reply);
        if ((!permit.kind.equals("own")&&!permit.kind.equals("pending")) || !permit.claimUpload() || origin==null || !origin.key.equals(permit.origin.accountKey())) throw new IllegalArgumentException("OwnPermitRequired");
        carry=permit;
    }
    boolean live() {
        BooleanSupplier captured=current;
        return !finished && captured!=null && captured.getAsBoolean() && (carry==null || carry.current()) && !finished && current==captured;
    }
    void start() {
        if(started)return;started=true;
        PurpleSyncAutoEdits.register(account,token,this::live,()->{
            Port active=port;return active!=null&&active.managedWait();
        },outcome->close(outcome));
        Port issued = port; byte[] data = bytes;
        if (!live() || issued == null || messageId<0 || !PurpleSyncPost.validRecord(data)) { fail(null); return; }
        long id=carry!=null?carry.fileId:issued.managedWait()?identity("upload",data):randomId();
        final TLRPC.TL_inputFile file = new TLRPC.TL_inputFile();
        file.id = id; file.parts = 1; file.name = PurpleSyncPost.RECORD_FILE_NAME;
        try { file.md5_checksum = hex(java.security.MessageDigest.getInstance("MD5").digest(data)); }
        catch (Exception e) { fail(null); return; }
        int sentRequest = issued.uploadResult(id,data,result->queue(()->{
            if (phase != 0) return;
            request.set(0); phase = 1;
            if(result!=null)waiting=result.waiting;
            if(!live()||result==null||result.id<=0){fail(result!=null?result.error:null);return;}
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
        if (carry!=null) {
            PurpleSyncChatMoveIO.Permit opening=carry;long ticket=++authorizationTicket;
            issued.renewCarry(opening,renewed->queue(()->{
                if(phase!=1 || ticket!=authorizationTicket)return;
                ++authorizationTicket;
                if(!PurpleSyncChatMoveIO.successor(opening,renewed)) { fail("StalePermit");return; }
                carry=renewed;if(!live()){fail(null);return;}dispatch(file);
            }));
            return;
        }
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
        PurpleSyncClient.Reply<Result> completed = result -> queue(() -> {
            if(phase!=2)return;phase=3;
            request.set(0);
            if (result == null || !live()) { fail(null); return; }
            serverId = result.id; error = result.error; waiting=result.waiting;
            PurpleSyncAutoEdits.finished(account, token, result.error != null || result.id <= 0, result.error);
        });
        int sentRequest=carry!=null?issued.sendCarry(carry,file,completed):issued.send(messageId,file,completed);
        track(issued, sentRequest, 2);
    }
    private void track(Port issued, int token, int expectedPhase) {
        if (token == 0) return;
        if (finished || phase != expectedPhase) { issued.cancel(token); return; }
        request.set(token);
        if (finished && request.compareAndSet(token, 0)) issued.cancel(token);
    }
    void cancel(){fail("Cancelled");}
    private void fail(String error) { this.error = error; PurpleSyncAutoEdits.finished(account, token, true, error); }
    private void close(PurpleSyncAutoCore.Outcome outcome) {
        if (!closing.compareAndSet(false,true)) return;
        finished = true;
        Port old = port; port = null;
        int pending = request.getAndSet(0);
        current = null; bytes = null; previous = null;
        PurpleSyncClient.Reply<Result> callback = reply; reply = null;
        Result result = new Result(outcome == PurpleSyncAutoCore.Outcome.Sent ? serverId : 0,
                outcome == PurpleSyncAutoCore.Outcome.Sent?null:error!=null?error:"OutcomeUnknown",0,waiting);
        try {
            if (pending != 0 && old != null) old.cancel(pending);
        } finally {
            try { if (old != null) old.cancelReads(); }
            finally { if (callback != null) callback.onReply(result); }
        }
    }
    static long identity(String purpose,byte[] bytes){
        try {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            digest.update(("purple-config-post-v1/"+purpose+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long value=java.nio.ByteBuffer.wrap(digest.digest(bytes)).getLong();return value!=0?value:1;
        }catch(Exception error){throw new IllegalStateException(error);}
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
