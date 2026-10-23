package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PurpleSaveDebounces {
    private final PurpleSaveDebounce.Scheduler scheduler;
    private final Map<String, PurpleSaveDebounce> accounts = new LinkedHashMap<>();
    PurpleSaveDebounces(PurpleSaveDebounce.Scheduler scheduler) { this.scheduler = scheduler; }
    synchronized PurpleSaveDebounce of(String key) {
        PurpleSaveDebounce result = accounts.get(key);
        if (result == null) { result = new PurpleSaveDebounce(scheduler); accounts.put(key, result); }
        return result;
    }
    void refresh(Runnable reload) {
        List<PurpleSaveDebounce> pending;
        synchronized (this) { pending = new ArrayList<>(accounts.values()); }
        refresh(pending, 0, reload);
    }
    private void refresh(List<PurpleSaveDebounce> pending, int index, Runnable reload) {
        if (index == pending.size()) reload.run();
        else pending.get(index).refresh(() -> refresh(pending, index + 1, reload));
    }
}
