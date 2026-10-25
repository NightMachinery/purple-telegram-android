package android.os;
public final class Looper {
 private static final Looper main=new Looper();private static final ThreadLocal<Boolean> isMain=new ThreadLocal<>();
 public static Looper getMainLooper(){return main;}public static Looper myLooper(){return Boolean.TRUE.equals(isMain.get())?main:null;}public static void mainForFixture(boolean value){isMain.set(value);}
}
