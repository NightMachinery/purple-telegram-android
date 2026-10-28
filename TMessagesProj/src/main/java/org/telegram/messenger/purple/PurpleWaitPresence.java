package org.telegram.messenger.purple;

public final class PurpleWaitPresence {
    public enum Lock { UNKNOWN, UNLOCKED, LOCKED }
    private enum State { FOREGROUND, AWAY, LOCKED, RETURNING, CANCELLED }
    public static final long LOCK_AFTER_AWAY_MILLIS = 300, RETURN_MILLIS = 2000;
    private State state = State.FOREGROUND;
    private long awayAt, returnAt;
    private boolean returnedToOwner;

    public boolean update(long now, boolean foreground, Lock lock) {
        return update(now, foreground, lock, false, false);
    }

    public boolean update(long now, boolean foreground, Lock lock, boolean ownerResumed, boolean ownerDeparted) {
        if (state == State.CANCELLED) return false;
        if (lock == Lock.UNKNOWN) { state = State.CANCELLED; return false; }
        if (state == State.AWAY) {
            if (now - awayAt >= LOCK_AFTER_AWAY_MILLIS) state = State.CANCELLED;
            else if (lock == Lock.LOCKED) state = State.LOCKED;
            else if (foreground) state = State.CANCELLED;
        } else if (state == State.LOCKED) {
            if (lock == Lock.UNLOCKED) {
                state = foreground ? State.FOREGROUND : State.RETURNING;
                returnAt = now;
                returnedToOwner = ownerResumed;
            }
        } else if (state == State.RETURNING) {
            if (now - returnAt >= RETURN_MILLIS) state = State.CANCELLED;
            else if (lock == Lock.LOCKED) state = returnedToOwner ? State.LOCKED : State.CANCELLED;
            else if (ownerDeparted && returnedToOwner) { state = State.AWAY; awayAt = now; }
            else {
                returnedToOwner |= ownerResumed;
                if (foreground) state = State.FOREGROUND;
            }
        } else if (lock == Lock.LOCKED) {
            state = State.LOCKED;
        } else if (!foreground) {
            state = State.AWAY;
            awayAt = now;
        }
        return state != State.CANCELLED;
    }

    public boolean returningFromLock() { return state == State.LOCKED || state == State.RETURNING; }

    public boolean suspended() { return state != State.FOREGROUND; }
}
