package org.telegram.messenger;
import java.util.ArrayList;
public class AndroidUtilities {
    public static final ArrayList<Runnable> delayed = new ArrayList<>();
    public static int dp(int n) { return n; }
    public static void runOnUIThread(Runnable r, long delay) { delayed.add(r); }
    public static void flush() { for (Runnable r : new ArrayList<>(delayed)) r.run(); delayed.clear(); }
}
