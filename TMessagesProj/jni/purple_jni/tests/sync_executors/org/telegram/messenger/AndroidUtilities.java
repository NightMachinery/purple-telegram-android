package org.telegram.messenger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AndroidUtilities {
    public static final String UI_THREAD = "ui";
    public static final ExecutorService UI = Executors.newSingleThreadExecutor(task -> {
        final Thread thread = new Thread(task, UI_THREAD);
        thread.setDaemon(true);
        return thread;
    });

    private AndroidUtilities() {
    }

    public static void runOnUIThread(Runnable runnable) {
        UI.execute(runnable);
    }
}
