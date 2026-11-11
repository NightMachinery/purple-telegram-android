package org.telegram.messenger.purple;

import android.os.Handler;
import android.os.SystemClock;

public final class PurpleLegacyRoundBudget {
    public final int account;
    public final int guid;
    private final long stopMs;
    private long elapsedMs;
    private long startedMs;
    private boolean active;
    private boolean ended;
    private boolean reached;
    private Handler handler;
    private Runnable deadline;
    private Runnable onLimit;

    public PurpleLegacyRoundBudget(int account, int guid, long maximumMs) {
        this.account = account;
        this.guid = guid;
        stopMs = PurpleRoundLimits.composerStopMs(maximumMs);
    }

    public synchronized void resume(Handler handler, Runnable onLimit) {
        if (ended || reached || active) return;
        this.handler = handler;
        this.onLimit = onLimit;
        startedMs = SystemClock.elapsedRealtime();
        active = true;
        Runnable next = new Runnable() {
            @Override public void run() {
                synchronized (PurpleLegacyRoundBudget.this) {
                    if (deadline != this || !active) return;
                    long remaining = stopMs - durationMs();
                    if (remaining > 0) handler.postDelayed(this, remaining);
                    else limit();
                }
            }
        };
        deadline = next;
        handler.postDelayed(next, Math.max(0, stopMs - elapsedMs));
    }

    public synchronized long durationMs() {
        return Math.min(stopMs, elapsedMs + (active
                ? Math.max(0, SystemClock.elapsedRealtime() - startedMs) : 0));
    }

    public synchronized void pause() {
        elapsedMs = durationMs();
        active = false;
        cancelDeadline();
    }

    public synchronized void stop() {
        pause();
        ended = true;
    }

    public synchronized boolean canResume() {
        return !ended && !reached && durationMs() < stopMs;
    }

    public synchronized boolean video(long presentationNs) {
        if (!active || ended || reached) return false;
        if (presentationNs < 0 || presentationNs >= stopMs * 1_000_000L
                || durationMs() >= stopMs) {
            limit();
            return false;
        }
        return true;
    }

    public synchronized int audioBytes(long presentationUs, int bytes, int sampleRate) {
        long remaining = stopMs * 1000L - presentationUs;
        int admitted = presentationUs < 0 ? 0 : (int) Math.min(bytes,
                Math.max(0, remaining) * sampleRate / 1_000_000L * 2L);
        admitted -= admitted % 2;
        if (admitted < bytes || active && durationMs() >= stopMs) limit();
        return admitted;
    }

    public long audioTimeUs(long presentationUs) {
        return Math.max(0, Math.min(stopMs * 1000L, presentationUs));
    }

    private void limit() {
        if (!active || ended || reached) return;
        elapsedMs = stopMs;
        reached = true;
        active = false;
        cancelDeadline();
        onLimit.run();
    }

    private void cancelDeadline() {
        if (deadline != null) handler.removeCallbacks(deadline);
        deadline = null;
    }
}
