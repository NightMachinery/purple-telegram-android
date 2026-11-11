package org.telegram.messenger.purple;

public final class PurpleLongRoundQueue {
    public final int count;
    public int attempted, queued;
    private boolean confirmed, stopped;
    public PurpleLongRoundQueue(int count) {
        if (count < 1 || count > 6) throw new IllegalArgumentException("count");
        this.count = count;
    }
    public boolean confirm() {
        if (confirmed || stopped) return false;
        confirmed = true; return true;
    }
    public int begin() {
        if (!confirmed || stopped || attempted != queued || attempted == count) return -1;
        return attempted++;
    }
    public boolean waiting(int index) { return !stopped && index == queued && attempted == queued + 1; }
    public boolean acknowledge(int index) {
        if (!waiting(index)) return false;
        queued++; return true;
    }
    public void stop() { stopped = true; }
}
