package org.telegram.messenger;
public final class AndroidUtilities {
    public static final java.util.List<Runnable> pending=new java.util.ArrayList<>();
    public static void runOnUIThread(Runnable task){pending.add(task);}
}
