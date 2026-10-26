package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

final class PurpleComposePending {
    static final int MAX_ENTRIES = 64, MAX_BYTES = 2 * 1024 * 1024;
    static final long TIMEOUT = 5L * 60 * 1_000_000_000;

    static final class Scope {
        final int slot;
        final long dialog;
        final BooleanSupplier authority;
        final Object context;

        Scope(int slot, long dialog, BooleanSupplier authority) {
            this(slot, dialog, authority, null);
        }

        Scope(int slot, long dialog, BooleanSupplier authority, Object context) {
            this.slot = slot; this.dialog = dialog; this.authority = authority; this.context = context;
        }

        boolean current() {
            try { return authority.getAsBoolean(); }
            catch (RuntimeException | LinkageError e) { return false; }
        }
    }

    final class Transfer implements AutoCloseable {
        final Scope scope;
        final PurpleMarkdownSource.Candidate source;
        final long created;
        int localId, serverId;
        long randomId;
        boolean closed;

        Transfer(Scope scope, PurpleMarkdownSource.Candidate source) {
            this.scope = scope; this.source = source; created = clock.getAsLong();
        }

        boolean current() {
            synchronized (PurpleComposePending.this) {
                return !closed && clock.getAsLong() - created < TIMEOUT && scope.current();
            }
        }

        @Override public void close() {
            synchronized (PurpleComposePending.this) {
                closed = true;
                source.close();
                retained.remove(this);
                prepared.values().removeIf(value -> value == this);
                bound.values().removeIf(value -> value == this);
            }
        }
    }

    private final LongSupplier clock;
    private final Map<Object, Transfer> prepared = new WeakHashMap<>();
    private final Map<Object, Transfer> bound = new IdentityHashMap<>();
    private final Map<Transfer, Boolean> retained = new IdentityHashMap<>();

    PurpleComposePending(LongSupplier clock) { this.clock = clock; }

    synchronized boolean prepare(Object params, Scope scope, PurpleMarkdownSource.Candidate source) {
        prune();
        if (params == null || source == null || source.kind != 0 || scope == null || !scope.current() || prepared.containsKey(params)
                || retained.size() >= MAX_ENTRIES || source.memoryBytes() == 0
                || source.memoryBytes() > MAX_BYTES - retainedBytes()) return false;
        for (Transfer transfer : retained.keySet()) if (transfer.source == source) return false;
        Transfer transfer = new Transfer(scope, source);
        prepared.put(params, transfer); retained.put(transfer, false);
        return true;
    }

    synchronized boolean bind(int slot, Object params, Object owner, int localId, long randomId, long dialog) {
        prune();
        Transfer transfer = prepared.get(params);
        if (transfer == null || transfer.scope.slot != slot || transfer.scope.dialog != dialog) return false;
        if (owner == null || localId >= 0 || randomId == 0 || bound.containsKey(owner) || !transfer.current()) {
            transfer.close(); return false;
        }
        prepared.remove(params);
        transfer.localId = localId; transfer.randomId = randomId;
        bound.put(owner, transfer);
        return true;
    }

    synchronized Transfer confirm(int slot, Object owner, int localId, int serverId, long randomId, long dialog, byte[] normalizedBody) {
        prune();
        Transfer transfer = bound.get(owner);
        if (transfer == null || transfer.scope.slot != slot || transfer.scope.dialog != dialog) return null;
        bound.remove(owner);
        if (serverId <= 0 || localId != transfer.localId || randomId != transfer.randomId
                || !transfer.current() || !transfer.source.matchesCompose(normalizedBody)) {
            transfer.close(); return null;
        }
        transfer.serverId = serverId;
        retained.put(transfer, true);
        return transfer;
    }

    synchronized void cancel(int slot, Object owner) {
        Transfer transfer = bound.get(owner);
        if (transfer != null && transfer.scope.slot == slot) transfer.close();
    }

    synchronized void cancelLocal(int slot, int local, long dialog) {
        for (Transfer transfer : new ArrayList<>(bound.values()))
            if (transfer.scope.slot == slot && transfer.localId == local && transfer.scope.dialog == dialog) transfer.close();
    }

    synchronized void cleanup(int slot) {
        for (Transfer transfer : new ArrayList<>(retained.keySet())) if (slot < 0 || transfer.scope.slot == slot) transfer.close();
    }

    synchronized void prune() {
        prepared.size();
        for (Transfer transfer : new ArrayList<>(retained.keySet())) {
            boolean executing = retained.get(transfer);
            if (!transfer.current() || !executing && !prepared.containsValue(transfer) && !bound.containsValue(transfer)) transfer.close();
        }
    }

    synchronized int retainedBytes() {
        int bytes = 0;
        for (Transfer transfer : retained.keySet()) bytes += transfer.source.memoryBytes();
        return bytes;
    }
}
