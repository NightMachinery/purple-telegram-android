package org.telegram.messenger.purple;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleSyncAutoNetwork {
    private final java.util.Set<Pending<?>> pendingOperations =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    final PurpleSyncClient client;
    volatile PurpleSyncTelegramTransport transport;
    final String session;
    final BooleanSupplier current;
    PurpleSyncAutoNetwork(PurpleSyncClient client, String session, BooleanSupplier current) {
        this.client = new PurpleSyncAutoClient(client, current);
        this.session = session;
        this.current = current;
        transport = new PurpleSyncTelegramTransport(this.client);
    }
    private final class Pending<T> {
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        final PurpleSyncAutoEngine.Reply<T> reply;
        final Runnable timeout;
        final T failure;
        Runnable cancel;
        final PurpleSyncReadScope reads;
        long readRevision,localDeadline;
        Pending(PurpleSyncAutoEngine.Reply<T> reply,T failure,Runnable cancel,BooleanSupplier guard){
            this.reply=reply;this.failure=failure;this.cancel=cancel;
            reads=new PurpleSyncReadScope(client,guard);
            readRevision=reads.managedReads().revision;localDeadline=PurpleRpcRetry.add(client.nowMillis(),300000);
            timeout=()->client.runOnWorker(this::expired);
            pendingOperations.add(this);
            client.runOnMain(()->{if(!done.get())org.telegram.messenger.AndroidUtilities.runOnUIThread(timeout,300000);});
        }
        void expired(){
            if(done.get())return;
            long now=client.nowMillis();
            PurpleSyncClient.ManagedReads state=reads.managedReads();
            if(reads.isActive()){
                if(state.active){
                    long retryAt=client.transientRetryAt();
                    arm(PurpleRpcRetry.add(Math.max(0,retryAt-now),60000));return;
                }
                if(readRevision!=state.revision){
                    readRevision=state.revision;localDeadline=PurpleRpcRetry.add(state.completedAt,300000);
                }
                if(localDeadline>now){arm(localDeadline-now);return;}
            }
            if(done.compareAndSet(false,true)){
                pendingOperations.remove(this);reads.cancel();if(cancel!=null)cancel.run();reply.done(failure);
            }
        }
        private void arm(long delay){client.runOnMain(()->{if(!done.get())org.telegram.messenger.AndroidUtilities.runOnUIThread(timeout,delay);});}
        void finish(T value) {
            if (done.compareAndSet(false, true)) {
                pendingOperations.remove(this);
                reads.cancel();
                client.runOnMain(() -> org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(timeout));
                reply.done(value);
            }
        }
    }
    void cancel() {
        for (Pending<?> operation : pendingOperations)
            if (operation.done.compareAndSet(false, true)) {
                operation.reads.cancel();
                if(operation.cancel!=null)operation.cancel.run();
                client.runOnMain(() -> org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(operation.timeout));
            }
        pendingOperations.clear();
        PurpleSyncTelegramTransport check = transport;
        if (check != null)
            check.cancel();
    }
    void check(
        int[] exactIds, BooleanSupplier guard, PurpleSyncAutoEngine.Reply<PurpleSyncAutoEngine.Observed> finished) {
        BooleanSupplier scoped = () -> current.getAsBoolean() && guard.getAsBoolean();
        Pending<PurpleSyncAutoEngine.Observed> pending = new Pending<>(finished, null, null, scoped);
        PurpleSyncTelegramTransport checker = new PurpleSyncTelegramTransport(pending.reads);
        pending.cancel=checker::cancel;
        transport = checker;
        PurpleSyncAutoEngine.Reply<PurpleSyncAutoEngine.Observed> reply = pending::finish;
        if (!scoped.getAsBoolean()) {
            reply.done(null);
            return;
        }
        checker.check(null, result -> {
            if (pending.done.get() || !scoped.getAsBoolean() || result.status != PurpleSyncTransport.CheckStatus.Finished) {
                reply.done(null);
                return;
            }
            java.util.TreeSet<Integer> all = new java.util.TreeSet<>();
            for (int id : exactIds) all.add(id);
            for (int id : result.inventory.ids) all.add(id);
            if (all.size() > 10000) {
                reply.done(null);
                return;
            }
            int[] requested = new int[all.size()];
            int index = 0;
            for (int id : all) requested[index++] = id;
            JSONArray reads = new JSONArray();
            readMany(pending, requested, 0, reads,
                () -> !pending.done.get() && scoped.getAsBoolean(),
                () -> reply.done(null),
                () -> reply.done(new PurpleSyncAutoEngine.Observed(
                    result.inventory, reads, client.nowMillis() / 1000, result.sendQueued)));
        });
    }
    private void readMany(
        Pending<?> pending, int[] ids, int index, JSONArray reads, BooleanSupplier active, Runnable cancelled, Runnable done) {
        if (!current.getAsBoolean() || !active.getAsBoolean()) {
            cancelled.run();
            return;
        }
        if (index == ids.length) {
            done.run();
            return;
        }
        exact(pending.reads, ids[index], active, row -> {
            reads.put(row);
            readMany(pending, ids, index + 1, reads, active, cancelled, done);
        });
    }
    void exact(int id, BooleanSupplier guard, PurpleSyncAutoEngine.Reply<JSONObject> reply) {
        Pending<JSONObject> pending=new Pending<>(reply,row(id,0,null,false),null,guard);
        exact(pending.reads,id,()->!pending.done.get()&&guard.getAsBoolean(),pending::finish);
    }
    private void exact(PurpleSyncClient client,int id, BooleanSupplier guard, PurpleSyncAutoEngine.Reply<JSONObject> reply) {
        if (!current.getAsBoolean() || !guard.getAsBoolean() || !client.isActive()) {
            reply.done(row(id, 0, null, false));
            return;
        }
        client.requestMessage(id, lookup -> client.runOnWorker(() -> {
            if (!current.getAsBoolean() || !guard.getAsBoolean() || !client.isActive()) {
                reply.done(row(id, 0, null, false));
                return;
            }
            if (lookup == null || lookup.status == PurpleSyncClient.LookupStatus.Failed) {
                reply.done(row(id, 0, null, false));
                return;
            }
            if (lookup.status == PurpleSyncClient.LookupStatus.Missing) {
                reply.done(row(id, 2, null, false));
                return;
            }
            PurpleSyncClient.Message message = lookup.message;
            if (message == null || message.id != id || !message.isMessage || !client.contains(message) || message.forwarded
                || !message.isDocument || message.size <= 0 || message.size > PurpleSyncPost.MAX_STAGED_BYTES) {
                reply.done(row(id, 1, null, false));
                return;
            }
            PurpleSyncCore.Page candidate=PurpleSyncCore.classifyHistoryPage(0,new int[]{id},new boolean[]{message.isMessage},
                    new boolean[]{message.forwarded},new boolean[]{message.isDocument},new String[]{message.caption},new String[]{message.fileName});
            if(!candidate.isValid() || candidate.candidates.length!=1 || candidate.candidates[0]!=id){reply.done(row(id,1,null,false));return;}
            client.download(message, file -> client.runOnWorker(() -> {
                if (!current.getAsBoolean() || !guard.getAsBoolean() || !client.isActive()) {
                    reply.done(row(id, 0, null, false));
                    return;
                }
                byte[] bytes =
                    file != null ? PurpleSyncReader.readAtMost(file, PurpleSyncPost.MAX_STAGED_BYTES + 1) : null;
                if (bytes == null || bytes.length != message.size || !PurpleSyncPost.validRecord(bytes)) {
                    reply.done(row(id, 0, null, false));
                    return;
                }
                client.requestMessage(id,fresh->client.runOnWorker(()->{
                    if(!current.getAsBoolean() || !guard.getAsBoolean() || !client.isActive()){
                        reply.done(row(id,0,null,false));return;
                    }
                    boolean same=fresh!=null && fresh.status==PurpleSyncClient.LookupStatus.Found
                            && client.contains(fresh.message) && PurpleSyncChatIO.same(message,fresh.message);
                    reply.done(row(id,same?1:0,same?bytes:null,same));
                }));
            }));
        }));
    }
    static JSONObject row(int id, int presence, byte[] bytes, boolean owned) {
        try {
            return new JSONObject()
                .put("id", id)
                .put("presence", presence)
                .put("record", PurpleSyncAutoCore.encode(bytes))
                .put("owned", owned)
                .put("fresh", true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    void send(boolean edit, int id, byte[] bytes, byte[] previous, BooleanSupplier guard,
        PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> finished) {
        send(edit,id,bytes,previous,guard,new PurpleSyncTransport.PostAuthority(){
            public boolean current(){return guard.getAsBoolean();}
            public void check(java.util.function.Consumer<Boolean> done){done.accept(current());}
        },finished);
    }
    void send(boolean edit,int id,byte[] bytes,byte[] previous,BooleanSupplier guard,
            PurpleSyncTransport.PostAuthority authority,PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> finished) {
        Pending<PurpleSyncAutoCore.Outcome> pending =
            new Pending<>(finished, PurpleSyncAutoCore.Outcome.OutcomeUnknown, null, guard);
        PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply = pending::finish;
        BooleanSupplier live = () -> !pending.done.get() && guard.getAsBoolean();
        if (!live.getAsBoolean()) {
            reply.done(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
            return;
        }
        if (!edit) {
            PurpleSyncClient guarded = new PurpleSyncAutoClient(client, live);
            new PurpleSyncTelegramTransport(guarded).post(bytes,authority,
                result
                -> reply.done(result.status == PurpleSyncTransport.PostStatus.Confirmed
                        ? PurpleSyncAutoCore.Outcome.Sent
                        : PurpleSyncAutoCore.Outcome.OutcomeUnknown));
            return;
        }
        exact(pending.reads, id, live, read -> {
            if (!live.getAsBoolean() || read.optInt("presence") != 1 || !read.optBoolean("owned")
                || !Arrays.equals(previous, PurpleSyncAutoCore.decode(read.optString("record")))) {
                reply.done(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
                return;
            }
            File root = new File(client.filesDirectory(), PurpleSyncPost.STAGING_DIRECTORY + "/" + UUID.randomUUID());
            File file = new File(root, PurpleSyncPost.RECORD_FILE_NAME);
            try {
                if (!root.mkdirs() || !PurpleSyncPost.validRecord(bytes))
                    throw new IllegalStateException("automatic edit stage unavailable");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                if (!Arrays.equals(bytes, PurpleSyncReader.readAtMost(file, PurpleSyncPost.MAX_STAGED_BYTES + 1))
                    || !live.getAsBoolean())
                    throw new IllegalStateException("automatic edit stage changed");
                client.edit(id, file, previous, () -> live.getAsBoolean()
                    && Arrays.equals(bytes, PurpleSyncReader.readAtMost(file, PurpleSyncPost.MAX_STAGED_BYTES + 1)),authority,
                    outcome -> reply.done(outcome));
            } catch (Exception e) {
                reply.done(PurpleSyncAutoCore.Outcome.EditRefused);
            }
        });
    }
    void retire(
        int id, byte[] bytes, BooleanSupplier guard, PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> finished) {
        Pending<PurpleSyncAutoCore.Outcome> pending =
            new Pending<>(finished, PurpleSyncAutoCore.Outcome.OutcomeUnknown, null, guard);
        PurpleSyncAutoEngine.Reply<PurpleSyncAutoCore.Outcome> reply = pending::finish;
        BooleanSupplier live = () -> !pending.done.get() && guard.getAsBoolean();
        exact(pending.reads, id, live, read -> {
            if (!live.getAsBoolean() || read.optInt("presence") != 1 || !read.optBoolean("owned")
                || !Arrays.equals(bytes, PurpleSyncAutoCore.decode(read.optString("record")))) {
                reply.done(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
                return;
            }
            client.deleteOwn(id, live, reply::done);
        });
    }
}
