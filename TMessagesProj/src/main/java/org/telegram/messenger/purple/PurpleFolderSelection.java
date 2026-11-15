package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class PurpleFolderSelection<T> {
    public enum Kind { DEFAULT, FOLDER, VIEW }
    public enum Refusal { NONE, MISSING, LOCKED, ACCOUNT_CHANGED, DEFINITION_CHANGED }

    public static final class Account {
        public final String key;
        public final long lifetime;
        public final long role;

        public Account(String key, long lifetime, long role) {
            this.key = Objects.requireNonNull(key);
            this.lifetime = lifetime;
            this.role = role;
        }

        private boolean same(Account other) {
            return key.equals(other.key) && lifetime == other.lifetime && role == other.role;
        }
    }

    public static final class Entry<T> {
        public final Kind kind;
        public final int id;
        public final boolean locked;
        public final T value;

        public Entry(Kind kind, int id, boolean locked, T value) {
            this.kind = Objects.requireNonNull(kind);
            this.id = id;
            this.locked = locked;
            this.value = Objects.requireNonNull(value);
        }

        private boolean same(Entry<?> other) {
            return kind == other.kind && (kind == Kind.DEFAULT || id == other.id);
        }
    }

    public static final class Snapshot<T> {
        public final Account account;
        public final long revision;
        public final String definition;
        public final List<Entry<T>> entries;

        public Snapshot(Account account, long revision, String definition, List<Entry<T>> entries) {
            this.account = Objects.requireNonNull(account);
            this.revision = revision;
            this.definition = Objects.requireNonNull(definition);
            ArrayList<Entry<T>> copy = new ArrayList<>(entries);
            HashSet<String> keys = new HashSet<>();
            for (Entry<T> entry : copy) {
                Objects.requireNonNull(entry);
                String key = entry.kind + ":" + (entry.kind == Kind.DEFAULT ? 0 : entry.id);
                if (!keys.add(key)) throw new IllegalArgumentException("duplicate folder identity");
            }
            this.entries = Collections.unmodifiableList(copy);
        }
    }

    public static final class Ticket<T> {
        public final T value;
        public final int position;
        private final PurpleFolderSelection<T> owner;
        private final long sequence;
        private final Snapshot<T> snapshot;
        private final Entry<T> entry;
        private boolean prepared;

        private Ticket(PurpleFolderSelection<T> owner, long sequence,
                Snapshot<T> snapshot, Entry<T> entry, int position) {
            this.owner = owner;
            this.sequence = sequence;
            this.snapshot = snapshot;
            this.entry = entry;
            this.position = position;
            this.value = entry.value;
        }
    }

    public static final class Decision<T> {
        public final Refusal refusal;
        public final Ticket<T> ticket;

        private Decision(Refusal refusal, Ticket<T> ticket) {
            this.refusal = refusal;
            this.ticket = ticket;
        }
    }

    private long sequence;
    private Ticket<T> pending;

    public Decision<T> begin(Snapshot<T> rendered, int position, Snapshot<T> current) {
        retire();
        if (position < 0 || position >= rendered.entries.size()) return refused(Refusal.MISSING);
        Entry<T> requested = rendered.entries.get(position);
        return resolve(rendered, requested, current);
    }

    public Decision<T> rebase(Ticket<T> ticket, Snapshot<T> current) {
        if (!live(ticket)) return refused(Refusal.MISSING);
        Snapshot<T> requested = ticket.snapshot;
        Entry<T> entry = ticket.entry;
        retire();
        return resolve(requested, entry, current);
    }

    public boolean prepared(Ticket<T> ticket, Snapshot<T> current) {
        if (!matches(ticket, current)) {
            cancel(ticket);
            return false;
        }
        ticket.prepared = true;
        return true;
    }

    public boolean finish(Ticket<T> ticket, Snapshot<T> current) {
        if (!matches(ticket, current) || !ticket.prepared) {
            cancel(ticket);
            return false;
        }
        retire();
        return true;
    }

    public boolean cancel(Ticket<T> ticket) {
        if (!live(ticket)) return false;
        retire();
        return true;
    }

    public void retire() {
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("selection sequence exhausted");
        sequence++;
        pending = null;
    }

    private Decision<T> resolve(Snapshot<T> rendered, Entry<T> requested, Snapshot<T> current) {
        if (!rendered.account.same(current.account)) return refused(Refusal.ACCOUNT_CHANGED);
        if (requested.kind == Kind.VIEW && !rendered.definition.equals(current.definition)) {
            return refused(Refusal.DEFINITION_CHANGED);
        }
        if (requested.locked) return refused(Refusal.LOCKED);
        for (int i = 0; i < current.entries.size(); i++) {
            Entry<T> target = current.entries.get(i);
            if (!requested.same(target)) continue;
            if (target.locked) return refused(Refusal.LOCKED);
            pending = new Ticket<>(this, sequence, current, target, i);
            return new Decision<>(Refusal.NONE, pending);
        }
        return refused(Refusal.MISSING);
    }

    private boolean matches(Ticket<T> ticket, Snapshot<T> current) {
        if (!live(ticket) || !ticket.snapshot.account.same(current.account)
                || ticket.snapshot.revision != current.revision
                || ticket.position >= current.entries.size()) return false;
        if (ticket.entry.kind == Kind.VIEW && !ticket.snapshot.definition.equals(current.definition)) return false;
        Entry<T> target = current.entries.get(ticket.position);
        return !target.locked && ticket.entry.same(target) && target.value == ticket.value;
    }

    private boolean live(Ticket<T> ticket) {
        return ticket != null && ticket.owner == this && ticket == pending && ticket.sequence == sequence;
    }

    private Decision<T> refused(Refusal refusal) {
        return new Decision<>(refusal, null);
    }
}
