package org.telegram.messenger.purple;

final class PurplePlaylistsSyncSchedule implements AutoCloseable {
    interface Port {
        long now();
        boolean current();
        boolean foreground();
        void post(Runnable task, long delay);
        void cancel(Runnable task);
        void sync();
    }
    private static final long QUIET = 60000, MAX_UNSYNCED = 600000, BACKSTOP = 900000;
    private final Port port;
    private Runnable timer;
    private long due = Long.MAX_VALUE, nextBackstop;
    private boolean busy, closed;
    PurplePlaylistsSyncSchedule(Port port) { this.port = port; nextBackstop = plus(port.now(), BACKSTOP); }
    private static long plus(long value, long delta) { return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta; }
    void edited(long earliest) {
        if (closed) return;
        long now = port.now();
        long age = earliest > 0 ? Math.min(now, earliest) : now;
        due = Math.min(plus(now, QUIET), plus(age, MAX_UNSYNCED));
        arm();
    }
    void chatUpdated() { if (!closed) { due = Math.min(due, port.now()); arm(); } }
    void resume() { arm(); }
    void suspend() { if (timer != null) port.cancel(timer); timer = null; }
    void complete() {
        busy = false;
        nextBackstop = plus(port.now(), BACKSTOP);
        arm();
    }
    private void arm() {
        suspend();
        if (closed || busy || !port.current() || !port.foreground()) return;
        long at = Math.min(due, nextBackstop);
        Runnable task = new Runnable() {
            public void run() {
                if (timer != this || closed) return;
                timer = null;
                if (!port.current() || !port.foreground()) return;
                if (Math.min(due, nextBackstop) > port.now()) { arm(); return; }
                due = Long.MAX_VALUE; busy = true;
                port.sync();
            }
        };
        timer = task; port.post(task, Math.max(1, Math.min(BACKSTOP, at - port.now())));
    }
    @Override public void close() { closed = true; suspend(); }
}
