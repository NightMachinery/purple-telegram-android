/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.concurrent.atomic.AtomicInteger;

public final class PurpleSyncTelegramTransport implements PurpleSyncTransport {
    interface Task {
        void start();

        void cancel();
    }

    private final PurpleSyncClient client;
    private final AtomicInteger generation = new AtomicInteger();
    private final Object lock = new Object();
    private Task current;

    public PurpleSyncTelegramTransport(int account, long userId) {
        this(PurpleSyncTelegramClient.create(account, userId));
    }

    PurpleSyncTelegramTransport(PurpleSyncClient client) {
        if (client == null) {
            throw new IllegalArgumentException("no Telegram client");
        }
        this.client = client;
    }

    @Override
    public void check(Progress progress, CheckDone done) {
        if (done == null) {
            throw new IllegalArgumentException("no check callback");
        }
        final Task previous;
        final Task task;
        synchronized (lock) {
            previous = current;
            generation.incrementAndGet();
            task = new PurpleSyncCheck(
                    new PurpleSyncClient.Operation(client, generation),
                    progress, done);
            current = task;
        }
        launch(previous, task);
    }

    @Override
    public void post(byte[] staged, PostDone done) {
        if (done == null) {
            throw new IllegalArgumentException("no post callback");
        }
        final Task previous;
        final Task task;
        synchronized (lock) {
            previous = current;
            generation.incrementAndGet();
            task = new PurpleSyncPost(
                    new PurpleSyncClient.Operation(client, generation),
                    staged, done);
            current = task;
        }
        launch(previous, task);
    }

    @Override
    public void cancel() {
        final Task task;
        synchronized (lock) {
            task = current;
            current = null;
            generation.incrementAndGet();
        }
        if (task != null) {
            client.runOnWorker(task::cancel);
        }
    }

    private void launch(Task previous, Task task) {
        client.runOnWorker(() -> {
            if (previous != null) {
                previous.cancel();
            }
            task.start();
        });
    }
}
