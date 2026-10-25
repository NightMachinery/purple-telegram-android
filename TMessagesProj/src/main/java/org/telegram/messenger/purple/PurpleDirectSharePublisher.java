package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class PurpleDirectSharePublisher<T> {
    public static final class Target<T> {
        public final String id, signature;
        public final T value;
        public final int rank;
        public final boolean conversation, visible, current, observed, dynamic;
        public Target(String id, String signature, T value, int rank, boolean conversation, boolean visible, boolean current) {
            this(id, signature, value, rank, conversation, visible, current, true, true);
        }
        private Target(String id, String signature, T value, int rank, boolean conversation, boolean visible, boolean current, boolean observed, boolean dynamic) {
            this.id = id; this.signature = signature; this.value = value; this.rank = rank;
            this.conversation = conversation; this.visible = visible; this.current = current; this.observed = observed; this.dynamic = dynamic;
        }
        public Target<T> dynamic(boolean dynamic) { return new Target<>(id, signature, value, rank, conversation, visible, current, observed, dynamic); }
        public Target<T> observed(boolean observed) { return new Target<>(id, signature, value, rank, conversation, visible, current, observed, dynamic); }
        public Target<T> current(boolean current) { return new Target<>(id, signature, value, rank, conversation, visible, current, observed, dynamic); }
        Target<T> ranked(int rank) { return new Target<>(id, signature, value, rank, conversation, visible, current); }
        String version() { return signature + ":" + rank; }
    }
    public interface Port<T> {
        List<Target<T>> current();
        void remove(List<String> ids, List<String> longLived);
        void add(List<Target<T>> targets);
        void update(List<Target<T>> targets);
    }
    private long generation;
    private final LinkedHashMap<String, Target<T>> retained = new LinkedHashMap<>();
    public synchronized long invalidate() { return ++generation; }
    public synchronized long generation() { return generation; }
    public synchronized void supersede(String id) { retained.remove(id); }
    public synchronized List<Target<T>> retained() { return new ArrayList<>(retained.values()); }
    public static boolean direct(String id) { return id.equals("compose") || id.startsWith("pdid_") || id.startsWith("did3_"); }
    public synchronized boolean publish(long ticket, List<List<Target<T>>> accounts, Target<T> compose,
            List<Target<T>> conversations, int limit, Port<T> port, BooleanSupplier current) {
        if (ticket != generation || !current.getAsBoolean()) return false;
        LinkedHashMap<String, Target<T>> existing = new LinkedHashMap<>(), desired = new LinkedHashMap<>();
        for (Target<T> value : port.current()) existing.put(value.id, value);
        LinkedHashMap<String, Target<T>> unique = new LinkedHashMap<>();
        for (Target<T> value : conversations) {
            Target<T> prior = unique.get(value.id);
            if (prior == null || !value.current || !value.visible) unique.put(value.id, value);
        }
        ArrayList<String> remove = new ArrayList<>(), longLived = new ArrayList<>();
        int foreign = 0;
        for (Target<T> value : existing.values()) if (!direct(value.id) && value.dynamic) ++foreign;
        for (Target<T> value : unique.values()) {
            if (!value.current || !value.visible) {
                if (existing.containsKey(value.id)) { remove.add(value.id); if (existing.get(value.id).dynamic) --foreign; }
                if (value.observed || existing.containsKey(value.id)) longLived.add(value.id);
                if (value.current) {
                    retained.put(value.id, value);
                    while (retained.size() > 64) retained.remove(retained.keySet().iterator().next());
                } else retained.remove(value.id);
            } else if (existing.containsKey(value.id) && existing.get(value.id).dynamic) retained.remove(value.id);
        }
        int room = Math.max(0, Math.min(64, limit) - foreign);
        if (compose != null && room > 0) { desired.put(compose.id, compose.ranked(0)); --room; }
        for (Target<T> value : unique.values()) {
            if (room == 0) break;
            if (value.current && value.visible && (retained.containsKey(value.id) || !value.observed) && (!existing.containsKey(value.id) || !existing.get(value.id).dynamic)) {
                desired.put(value.id, value); --room;
            }
        }
        int directRank = 1;
        for (int rank = 0; room > 0; ++rank) {
            boolean found = false;
            for (List<Target<T>> account : accounts) {
                if (rank >= account.size()) continue;
                found = true; Target<T> value = account.get(rank);
                if (value.current && value.visible && !desired.containsKey(value.id)) {
                    desired.put(value.id, value.ranked(directRank++)); --room;
                    if (room == 0) break;
                }
            }
            if (!found) break;
        }
        for (Target<T> value : existing.values()) if (direct(value.id) && !desired.containsKey(value.id)) {
            remove.add(value.id); longLived.add(value.id);
        }
        ArrayList<Target<T>> add = new ArrayList<>(), update = new ArrayList<>();
        for (Target<T> value : desired.values()) {
            Target<T> old = existing.get(value.id);
            if (old == null || !old.dynamic) add.add(value);
            else if (!old.version().equals(value.version())) update.add(value);
        }
        if (ticket != generation || !current.getAsBoolean()) return false;
        if (!remove.isEmpty() || !longLived.isEmpty()) port.remove(remove, longLived);
        if (ticket != generation || !current.getAsBoolean()) return false;
        if (!update.isEmpty()) port.update(update);
        if (ticket != generation || !current.getAsBoolean()) return false;
        if (!add.isEmpty()) port.add(add);
        for (Target<T> value : add) if (value.conversation) retained.remove(value.id);
        return true;
    }
}
