package org.telegram.messenger.purple;

public final class PurpleLongRoundSelection {
    public static final class Invalid extends IllegalArgumentException { }
    public final long startUs, endUs;
    public PurpleLongRoundSelection(long sourceMs, long requestedStartUs, long requestedEndUs) {
        if (sourceMs <= 0 || sourceMs > PurpleLongRoundPlan.MAX_MS || requestedStartUs < -1 || requestedEndUs < -1) throw new Invalid();
        startUs = requestedStartUs < 0 ? 0 : requestedStartUs;
        endUs = requestedEndUs < 0 ? sourceMs * 1000 : requestedEndUs;
        if (startUs < 0 || endUs > sourceMs * 1000 || endUs - startUs < 800_000) throw new Invalid();
    }
    public long durationMs() { return (endUs - startUs + 999) / 1000; }
    public boolean full(long sourceMs) { return startUs == 0 && endUs == sourceMs * 1000; }
    public long startUs(int index, int count) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        return startUs + (endUs - startUs) * index / count;
    }
    public long endUs(int index, int count) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        return startUs + (endUs - startUs) * (index + 1) / count;
    }
}
