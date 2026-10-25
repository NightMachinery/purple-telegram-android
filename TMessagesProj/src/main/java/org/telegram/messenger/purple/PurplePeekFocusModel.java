package org.telegram.messenger.purple;

public final class PurplePeekFocusModel {
    private long session = -1;
    private long deadline = -1;
    private long generation;

    public boolean observe(long now, boolean enabled, boolean foreground, boolean locked, boolean active, long token) {
        if (!enabled || !active || foreground && !locked) {
            clear();
            return false;
        }
        if (session != token || deadline < 0) {
            session = token;
            deadline = now > Long.MAX_VALUE - 2000 ? Long.MAX_VALUE : now + 2000;
            ++generation;
        }
        return locked || now >= deadline;
    }

    public void clear() {
        if (deadline >= 0) ++generation;
        session = -1;
        deadline = -1;
    }
    public long deadline() { return deadline; }
    public long generation() { return generation; }
    public boolean expired(long now) { return deadline >= 0 && now >= deadline; }
}
