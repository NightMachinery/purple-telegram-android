package org.telegram.messenger;
public final class AndroidUtilities {
    public static boolean needPasscode;
    public static boolean needShowPasscode(boolean reset){return needPasscode;}
    public static final java.util.List<Runnable> pending=new java.util.ArrayList<>();
    public static final java.util.Map<Runnable,Long> delayed=new java.util.LinkedHashMap<>();
    public static synchronized void runOnUIThread(Runnable task){pending.add(task);}
    public static synchronized void runOnUIThread(Runnable task,long delay){delayed.put(task,delay);}
    public static synchronized void cancelRunOnUIThread(Runnable task){pending.remove(task);delayed.remove(task);}
}
