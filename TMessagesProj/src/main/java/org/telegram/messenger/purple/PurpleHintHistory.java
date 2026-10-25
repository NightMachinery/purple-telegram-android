package org.telegram.messenger.purple;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class PurpleHintHistory {
    public enum Source { CACHE_LOAD, SERVER_START, SERVER_REPLACE, SERVER_NOT_MODIFIED_OR_ERROR, SERVER_DISABLED, LOCAL_INCREMENT, REMOVE }

    public static final class Event {
        public final int account;
        public final long time;
        public final Source source;
        public final int slots;
        public final int before;
        public final int after;
        public final double added;

        Event(int account, long time, Source source, int slots, int before, int after, double added) {
            this.account = account;
            this.time = time;
            this.source = source;
            this.slots = slots;
            this.before = before;
            this.after = after;
            this.added = added;
        }
    }

    private static final ArrayDeque<Event> events = new ArrayDeque<>();
    private static final java.util.HashSet<Integer> inFlight = new java.util.HashSet<>();
    private static long generation;
    private static final java.util.HashMap<Integer, Long> loginGenerations = new java.util.HashMap<>();

    private PurpleHintHistory() {
    }

    public static synchronized void record(int account, long time, Source source, int slots,
            int before, int after, double added) {
        if (source == Source.SERVER_START) inFlight.add(account);
        else if (source == Source.SERVER_REPLACE || source == Source.SERVER_NOT_MODIFIED_OR_ERROR
                || source == Source.SERVER_DISABLED) inFlight.remove(account);
        if (events.size() == 50) events.removeFirst();
        events.addLast(new Event(account, time, source, slots, before, after, added));
    }

    public static synchronized boolean inFlight(int account) {
        return inFlight.contains(account);
    }

    public static synchronized List<Event> snapshot() {
        return new ArrayList<>(events);
    }

    public static synchronized long generation() {
        return generation;
    }

    public static synchronized long loginGeneration(int account) {
        Long value = loginGenerations.get(account);
        return value == null ? 0 : value;
    }

    public static synchronized void reset(int account, long time) {
        inFlight.remove(account);
        loginGenerations.put(account, loginGeneration(account) + 1);
        record(account, time, Source.REMOVE, 0, 0, 0, 0);
    }

    public static synchronized void clear() {
        events.clear();
        ++generation;
    }
}
