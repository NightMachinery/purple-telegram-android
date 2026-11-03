package android.os;

/**
 * The host clock for the modern suite: the Android settings editor reads it when the account sync store moves a
 * Saved Messages state into the magic chat as it opens (PurpleAccountSyncStore.moveToBound), which the automatic
 * migration cases run.
 */
public final class SystemClock {
    public static long elapsedRealtime() { return System.nanoTime() / 1000000L; }
    public static long uptimeMillis() { return System.nanoTime() / 1000000L; }
}
