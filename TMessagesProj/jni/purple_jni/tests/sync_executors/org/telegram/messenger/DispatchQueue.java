package org.telegram.messenger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DispatchQueue {
    public static final List<DispatchQueue> ALL = new CopyOnWriteArrayList<>();
    public final String name;
    private final ExecutorService executor;

    public DispatchQueue(final String threadName) {
        name = threadName;
        executor = Executors.newSingleThreadExecutor(task -> {
            final Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
        ALL.add(this);
    }

    public boolean postRunnable(Runnable runnable) {
        executor.execute(runnable);
        return true;
    }
}
