package org.telegram.messenger;
public final class AndroidUtilities {
    public static boolean needPasscode;
    public static boolean needShowPasscode(boolean reset){return needPasscode;}
    public static final java.util.List<Runnable> pending=new java.util.ArrayList<>();
    public static final java.util.Map<Runnable,Long> delayed=new java.util.LinkedHashMap<>();
    private static final java.util.Map<Runnable,String> origins=new java.util.WeakHashMap<>();
    private static String caller() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name=frame.getClassName();
            if (!name.equals(Thread.class.getName()) && !name.equals(AndroidUtilities.class.getName())) return name;
        }
        return "unknown";
    }
    public static synchronized int pendingFrom(String caller) {
        int count=0;
        for (Runnable task : pending) if (caller.equals(origins.get(task))) count++;
        for (Runnable task : delayed.keySet()) if (caller.equals(origins.get(task))) count++;
        return count;
    }
    public static synchronized void runOnUIThread(Runnable task){origins.put(task,caller());pending.add(task);}
    public static synchronized void runOnUIThread(Runnable task,long delay){origins.put(task,caller());delayed.put(task,delay);}
    public static synchronized void cancelRunOnUIThread(Runnable task){pending.remove(task);delayed.remove(task);origins.remove(task);}
}
