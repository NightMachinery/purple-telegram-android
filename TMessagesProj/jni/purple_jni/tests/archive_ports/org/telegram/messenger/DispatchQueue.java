package org.telegram.messenger;
public final class DispatchQueue {
    public final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
    public boolean postRunnable(Runnable task) { tasks.add(task); return true; }
    public void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
}
