package org.telegram.messenger.purple;

public final class PurpleLongRoundPlan {
    public static final long CIRCLE_MS = 60_000;
    public static final long PART_MS = 59_500;
    public static final long MAX_MS = 305_000;
    public final long durationMs;
    public final int count;

    public PurpleLongRoundPlan(long durationMs) {
        if (durationMs <= CIRCLE_MS || durationMs > MAX_MS) throw new IllegalArgumentException("duration");
        this.durationMs = durationMs;
        count = (int) ((durationMs + PART_MS - 1) / PART_MS);
    }

    public long startUs(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        return durationMs * 1000 * index / count;
    }

    public long endUs(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        return durationMs * 1000 * (index + 1) / count;
    }

    public void validatePart(int index, long actualMs) {
        final long expectedMs = (endUs(index) - startUs(index) + 999) / 1000;
        if (actualMs <= 0 || actualMs > CIRCLE_MS || Math.abs(actualMs - expectedMs) > 150) {
            throw new IllegalArgumentException("part duration");
        }
    }
}
