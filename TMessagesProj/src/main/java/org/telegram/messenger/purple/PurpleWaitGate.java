package org.telegram.messenger.purple;

public final class PurpleWaitGate {
    public enum Outcome { WAITING, READY, ABORTED, HELD, FINISHED }
    public static boolean presetWait(boolean normal, String before, String after) {
        String next = after == null || after.isEmpty() ? "normal" : after;
        return !normal && !next.equalsIgnoreCase(before);
    }

    public static boolean modeWait(int before, int after) {
        return before != after && before != 2;
    }

    public static boolean sourceWait(boolean normal, boolean entersNormal) {
        return !normal && entersNormal;
    }

    private final long deadline;
    private Outcome outcome = Outcome.WAITING;

    public PurpleWaitGate(long now, int seconds) {
        long duration = Math.max(0L, seconds) * 1000L;
        deadline = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    public long remaining(long now) { return Math.max(0L, deadline - now); }

    public Outcome poll(long now, boolean current, boolean foreground, boolean locked) {
        if (outcome != Outcome.WAITING) return Outcome.FINISHED;
        if (!current || !foreground || locked) return abort();
        return remaining(now) == 0 ? Outcome.READY : Outcome.WAITING;
    }

    public Outcome complete(long now, boolean current, boolean foreground, boolean locked, boolean writable) {
        Outcome decision = poll(now, current, foreground, locked);
        if (decision != Outcome.READY) return decision;
        outcome = writable ? Outcome.READY : Outcome.HELD;
        return outcome;
    }

    public Outcome abort() {
        if (outcome != Outcome.WAITING) return Outcome.FINISHED;
        outcome = Outcome.ABORTED;
        return outcome;
    }
}
