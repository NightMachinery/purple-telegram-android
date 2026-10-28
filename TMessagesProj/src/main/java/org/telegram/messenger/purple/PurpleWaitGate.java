package org.telegram.messenger.purple;

public final class PurpleWaitGate {
    public enum Outcome { WAITING, READY, ABORTED, HELD, EXPIRED, FINISHED }
    public enum Key { IGNORE, ABORT, CONFIRM }
    public static final long INPUT_GUARD_MILLIS = 500;
    private final long deadline, expires;
    private final boolean delayed;
    private Outcome outcome = Outcome.WAITING;
    private long guardUntil;
    private boolean ready;

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

    public static boolean readyTimeoutWait(int before, int after) { return after > before; }

    private static long add(long start, long duration) {
        return start > Long.MAX_VALUE - duration ? Long.MAX_VALUE : start + duration;
    }

    public PurpleWaitGate(long now, int seconds) { this(now, seconds, 600); }

    public PurpleWaitGate(long now, int seconds, int readyTimeoutSeconds) {
        delayed = seconds > 0;
        deadline = add(now, Math.max(0L, seconds) * 1000L);
        expires = add(deadline, Math.max(10L, readyTimeoutSeconds) * 1000L);
    }

    public long remaining(long now) { return now >= deadline ? 0 : deadline - now; }
    public boolean delayed() { return delayed; }
    public boolean ready() { return ready && outcome == Outcome.WAITING; }
    public boolean guarded(long now) { return delayed && (!ready || now < guardUntil); }
    public void guardInput(long now) { if (ready()) guardUntil = add(now, INPUT_GUARD_MILLIS); }

    public Outcome poll(long now, boolean current, boolean foreground, boolean locked) {
        if (outcome != Outcome.WAITING) return Outcome.FINISHED;
        if (!current) return abort();
        if (delayed && now >= expires) { outcome = Outcome.EXPIRED; return outcome; }
        if (remaining(now) > 0) return Outcome.WAITING;
        if (!ready) { ready = true; guardInput(now); }
        return Outcome.READY;
    }

    public Outcome complete(long now, boolean current, boolean foreground, boolean locked, boolean writable) {
        Outcome decision = poll(now, current, foreground, locked);
        if (decision != Outcome.READY) return decision;
        if (!foreground || locked || guarded(now)) return Outcome.WAITING;
        outcome = writable ? Outcome.READY : Outcome.HELD;
        return outcome;
    }

    public Key key(long now, boolean enter, boolean repeated) {
        if (outcome != Outcome.WAITING || repeated || ready() && guarded(now)) return Key.IGNORE;
        return ready() && enter ? Key.CONFIRM : Key.ABORT;
    }

    public Outcome abort() {
        if (outcome != Outcome.WAITING) return Outcome.FINISHED;
        outcome = Outcome.ABORTED;
        return outcome;
    }
}
