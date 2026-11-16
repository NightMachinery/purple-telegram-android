package org.telegram.messenger.purple;

public final class PurpleSleepTimer {
    private static final PurpleSleepTimer INSTANCE = new PurpleSleepTimer();
    public static int checkCalls;
    public static PurpleSleepTimer get() { return INSTANCE; }
    public void check() { checkCalls++; }
}
