package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

final class PurpleSyncReadScope extends PurpleSyncAutoClient {
    private final PurpleSyncManagedReads reads = new PurpleSyncManagedReads(this::nowMillis);
    private final Set<Read<?>> pending = new HashSet<>();
    private volatile boolean closed;
    PurpleSyncReadScope(PurpleSyncClient client, BooleanSupplier current) { super(client, current); }
    @Override public boolean isActive() { return !closed && super.isActive(); }
    @Override public ManagedReads managedReads() { return reads.state(); }
    @Override public int requestHistory(int offset, int limit, Reply<History> reply) {
        return issue(done -> super.requestHistory(offset, limit, done), reply);
    }
    @Override public int requestMessage(int id, Reply<Lookup> reply) {
        return issue(done -> super.requestMessage(id, done), reply);
    }
    private <T> int issue(Function<Reply<T>, Integer> send, Reply<T> reply) {
        Read<T> read;
        synchronized (this) {
            if (closed) return 0;
            read = new Read<>(reply);
            pending.add(read);
        }
        try {
            int token = send.apply(read::complete);
            read.attach(token);
            return token;
        } catch (RuntimeException | Error error) {
            read.cancel();
            throw error;
        }
    }
    @Override public void cancelRequest(int token) {
        ArrayList<Read<?>> matching = new ArrayList<>();
        synchronized (this) {
            for (Read<?> read : pending) if (read.token == token) matching.add(read);
        }
        for (Read<?> read : matching) read.cancel();
    }
    void cancel() {
        ArrayList<Read<?>> stopped;
        synchronized (this) {
            closed = true;
            stopped = new ArrayList<>(pending);
        }
        for (Read<?> read : stopped) read.cancel();
    }
    private final class Read<T> {
        final Runnable retire = reads.begin();
        final Reply<T> reply;
        int token;
        boolean ended, cancelled;
        Read(Reply<T> reply) { this.reply = reply; }
        void attach(int value) {
            boolean stop;
            synchronized (PurpleSyncReadScope.this) { token = value; stop = cancelled; }
            if (stop && value != 0) PurpleSyncReadScope.super.cancelRequest(value);
        }
        void complete(T value) {
            synchronized (PurpleSyncReadScope.this) {
                if (ended) return;
                ended = true;
                pending.remove(this);
            }
            retire.run();
            if (isActive()) reply.onReply(value);
        }
        void cancel() {
            int stopped;
            synchronized (PurpleSyncReadScope.this) {
                if (ended) return;
                ended = true; cancelled = true;
                pending.remove(this); stopped = token;
            }
            retire.run();
            if (stopped != 0) PurpleSyncReadScope.super.cancelRequest(stopped);
        }
    }
}
