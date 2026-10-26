package org.telegram.messenger.purple;

import java.io.File;
import java.util.List;
import java.util.function.BooleanSupplier;

class PurpleSyncAutoClient implements PurpleSyncClient {
    private final PurpleSyncClient delegate;
    private final BooleanSupplier current;
    PurpleSyncAutoClient(PurpleSyncClient delegate, BooleanSupplier current) {
        this.delegate = delegate;
        this.current = current;
    }
    public ManagedReads managedReads(){return delegate.managedReads();}
    public long transientRetryAt(){return delegate.transientRetryAt();}
    public void transientWait(java.util.function.Consumer<PurpleRpcRetry.Transient> observer){delegate.transientWait(observer);}
    public boolean isActive() {
        return current.getAsBoolean() && delegate.isActive();
    }
    public boolean hasQueuedSync() {
        return delegate.hasQueuedSync();
    }
    public PurpleSyncChatCore.Peer chat() { return delegate.chat(); }
    public PurpleSyncChatOrigin origin() { return delegate.origin(); }
    public long userId() {
        return delegate.userId();
    }
    public void runOnMain(Runnable task) {
        delegate.runOnMain(task);
    }
    public void runOnWorker(Runnable task) {
        delegate.runOnWorker(task);
    }
    public void log(String text) {
        delegate.log(text);
    }
    public int requestHistory(int offset, int limit, Reply<History> reply) {
        if (!isActive()) {
            reply.onReply(History.failed("stale automatic run"));
            return 0;
        }
        return delegate.requestHistory(offset, limit, reply);
    }
    public int requestMessage(int id, Reply<Lookup> reply) {
        if (!isActive()) {
            reply.onReply(Lookup.failed("stale automatic run"));
            return 0;
        }
        return delegate.requestMessage(id, reply);
    }
    public void cancelRequest(int token) {
        delegate.cancelRequest(token);
    }
    public Object download(Message message, Reply<File> reply) {
        if (!isActive()) {
            reply.onReply(null);
            return null;
        }
        return delegate.download(message, reply);
    }
    public void cancelDownload(Object token) {
        delegate.cancelDownload(token);
    }
    public void localCopies(Reply<List<LocalCopy>> reply) {
        delegate.localCopies(reply);
    }
    public File filesDirectory() {
        return delegate.filesDirectory();
    }
    public File cacheDirectory() {
        return delegate.cacheDirectory();
    }
    public long nowMillis() {
        return delegate.nowMillis();
    }
    public void edit(
        int id, File file, byte[] expected, BooleanSupplier guard, Reply<PurpleSyncAutoCore.Outcome> reply) {
        if (!isActive() || !guard.getAsBoolean()) {
            reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
            return;
        }
        delegate.edit(id, file, expected, () -> isActive() && guard.getAsBoolean(), reply);
    }
    public void edit(int id,File file,byte[] expected,BooleanSupplier guard,
            PurpleSyncTransport.PostAuthority authority,Reply<PurpleSyncAutoCore.Outcome> reply) {
        if(!isActive() || !guard.getAsBoolean()){reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown);return;}
        delegate.edit(id,file,expected,()->isActive()&&guard.getAsBoolean(),authority,reply);
    }
    public void deleteOwn(int id, BooleanSupplier guard, Reply<PurpleSyncAutoCore.Outcome> reply) {
        if (!isActive() || !guard.getAsBoolean()) {
            reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
            return;
        }
        delegate.deleteOwn(id, () -> isActive() && guard.getAsBoolean(), reply);
    }
    public void send(File file, String caption, String mime, Receipt receipt) {
        if (!isActive()) {
            receipt.onReceipt(0, true);
            return;
        }
        delegate.send(file, caption, mime, new Receipt() {
            public byte[] expectedBytes() { return receipt.expectedBytes(); }
            public PurpleSyncTransport.PostAuthority authority() { return receipt.authority(); }
            public boolean automatic() {
                return true;
            }
            public boolean current() {
                return isActive() && receipt.current();
            }
            public void onReceipt(int id, boolean failed) {
                receipt.onReceipt(id, failed);
            }
        });
    }
}
