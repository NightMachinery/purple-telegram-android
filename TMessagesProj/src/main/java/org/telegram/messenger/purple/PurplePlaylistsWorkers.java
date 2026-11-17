package org.telegram.messenger.purple;

import java.util.HashMap;
import java.util.Map;

final class PurplePlaylistsWorkers {
    private static final Map<Integer, PurplePlaylistsWorker> workers = new HashMap<>();

    static synchronized PurplePlaylistsWorker acquire(int slot) {
        PurplePlaylistsAccount.Captured captured = PurplePlaylistsAccount.capture(slot);
        PurplePlaylistsWorker worker = workers.get(slot);
        if (worker != null) {
            if (captured != null && captured.current() && worker.matches(captured)) { PurplePlaylistsSyncRuntime.attach(worker, captured); return worker; }
            worker.close();
            if (worker.termination() != PurplePlaylistsWorker.Termination.Closed) return null;
            workers.remove(slot);
        }
        if (captured == null || !captured.current()) return null;
        worker = new PurplePlaylistsWorker(captured);
        workers.put(slot, worker);
        PurplePlaylistsSyncRuntime.attach(worker, captured);
        return worker;
    }

    static synchronized void stop(int slot) {
        PurplePlaylistsSyncRuntime.stop(slot);
        PurplePlaylistsWorker worker = workers.get(slot);
        if (worker != null) worker.close();
    }

    static synchronized void stopAll() {
        for (java.util.Map.Entry<Integer, PurplePlaylistsWorker> entry : workers.entrySet()) {
            PurplePlaylistsSyncRuntime.stop(entry.getKey()); entry.getValue().close();
        }
    }

    private PurplePlaylistsWorkers() { throw new AssertionError(); }
}
