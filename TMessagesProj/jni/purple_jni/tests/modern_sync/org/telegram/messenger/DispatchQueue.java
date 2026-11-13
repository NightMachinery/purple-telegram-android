package org.telegram.messenger;
public class DispatchQueue {
    public static final java.util.ArrayDeque<Runnable> pending = new java.util.ArrayDeque<>();
    public DispatchQueue(String name) { }
    public boolean postRunnable(Runnable task) { pending.add(task);return true; }
    public boolean postRunnable(Runnable task,long delay) { pending.add(task);return true; }
    public void cancelRunnable(Runnable task) { pending.remove(task); }
    public static void flush() { int left=1000;while(!pending.isEmpty()){if(--left==0)throw new AssertionError("worker loop");pending.remove().run();} }
}
