package android.os;

/** Host port: the test thread is the main thread, as the UI thread is on a device. */
public final class Looper {
    private static final Looper MAIN = new Looper();

    private Looper() {
    }

    public static Looper myLooper() {
        return MAIN;
    }

    public static Looper getMainLooper() {
        return MAIN;
    }
}
