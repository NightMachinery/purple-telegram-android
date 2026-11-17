package org.telegram.messenger;
import java.util.*;
public final class AndroidUtilities {
    public static final Map<Runnable,Long> timers = new LinkedHashMap<>();
    public static void runOnUIThread(Runnable task) { task.run(); }
    public static void runOnUIThread(Runnable task, long delay) { timers.put(task, delay); }
    public static void cancelRunOnUIThread(Runnable task) { timers.remove(task); }
    public static Runnable timer(long delay) {
        for (Map.Entry<Runnable,Long> entry : timers.entrySet()) if (entry.getValue() == delay) return entry.getKey();
        throw new AssertionError("Missing timer " + delay);
    }
    public static void fire(long delay) { Runnable task = timer(delay); timers.remove(task); task.run(); }
}
