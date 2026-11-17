package android.os;
public final class Looper {
 private static final Looper main = new Looper();
 public static boolean off;
 public static Looper myLooper() { return off ? null : main; }
 public static Looper getMainLooper() { return main; }
}
