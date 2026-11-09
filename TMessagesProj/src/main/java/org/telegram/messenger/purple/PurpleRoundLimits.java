package org.telegram.messenger.purple;

import org.telegram.messenger.MessagesController;

public final class PurpleRoundLimits {
    private static final String KEY = "purple_local_round_minutes";
    public static final int[] MINUTES = {1, 3, 5};
    private PurpleRoundLimits() { }

    public static int minutes(int account) {
        return normalize(MessagesController.getMainSettings(account).getInt(KEY, 1));
    }

    public static int normalize(int value) {
        return value == 3 || value == 5 ? value : 1;
    }

    public static void setMinutes(int account, int minutes) {
        MessagesController.getMainSettings(account).edit().putInt(KEY, normalize(minutes)).apply();
    }

    public static long maximumDurationMs(int account) {
        return minutes(account) * 60_000L;
    }

    public static long composerStopMs(long maximumDurationMs) {
        return maximumDurationMs - 500L;
    }
}
