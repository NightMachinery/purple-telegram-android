package android.os;

public final class Looper {
    private static final Looper MAIN = new Looper();
    public static Thread mainThread;
    public static Looper getMainLooper() { return mainThread == null ? null : MAIN; }
    public static Looper myLooper() { return Thread.currentThread() == mainThread ? MAIN : null; }
}
