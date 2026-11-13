package org.telegram.messenger.purple;

final class PurpleSyncAutoBackstop {
    static final long INTERVAL = 15 * 60_000L;
    interface Port {
        boolean eligible();
        long now();
        void post(Runnable task, long delay);
        void cancel(Runnable task);
        void check();
    }
    private final Port port;
    private Runnable pending;
    private long deadline, checked = -1;
    private boolean closed;
    PurpleSyncAutoBackstop(Port port) { this.port = port; }
    void resume() {
        if (closed || !port.eligible()) { suspend(); return; }
        if (deadline == 0 || deadline <= port.now()) deadline = next(port.now());
        arm();
    }
    void checked(long at) {
        if (closed || at < 0 || at == checked) return;
        checked = at;
        deadline = next(at);
        suspend();
        arm();
    }
    private static long next(long at) { return at > Long.MAX_VALUE - INTERVAL ? Long.MAX_VALUE : at + INTERVAL; }
    private void arm() {
        if (closed || pending != null || !port.eligible()) return;
        if (deadline == 0) deadline = next(port.now());
        Runnable task = new Runnable() {
            @Override public void run() {
                if (pending != this || closed) return;
                pending = null;
                if (!port.eligible()) return;
                if (deadline <= port.now()) {
                    deadline = next(port.now());
                    port.check();
                }
                arm();
            }
        };
        pending = task;
        port.post(task, Math.max(1, Math.min(INTERVAL, deadline - port.now())));
    }
    void suspend() { if (pending != null) port.cancel(pending); pending = null; }
    void close() { closed = true; suspend(); }
}
