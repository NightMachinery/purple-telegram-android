package org.telegram.messenger.purple;

public final class PurpleWaitLayout {
    public static int readyTop(int countdownAbortTop, int gap, int readyHeight) {
        return countdownAbortTop - Math.max(0, gap) - Math.max(0, readyHeight);
    }
    private PurpleWaitLayout() { }
}
