package org.telegram.messenger.purple;

final class PurpleSaveDebounce {
    interface Scheduler {
        boolean currentThread();
        void post(Runnable callback, long delay);
        void cancel(Runnable callback);
    }
    interface Current { boolean current(); }
    interface Versions {
        long revision();
        long generation();
        long nativeEpoch();
    }
    interface Receipt {
        boolean current();
        default void beforeRefresh() { }
        default void afterRefresh() { }
    }

    static final class RefreshReceipt implements Receipt {
        private final Current inputs;
        private final Versions versions;
        private long revision;
        private long generation;
        private long nativeEpoch;
        private boolean retired;
        private boolean refreshing;

        RefreshReceipt(Current inputs, Versions versions, long revision, long generation, long nativeEpoch) {
            this.inputs = inputs;
            this.versions = versions;
            this.revision = revision;
            this.generation = generation;
            this.nativeEpoch = nativeEpoch;
        }

        public synchronized boolean current() {
            return !retired && !refreshing && inputs.current() && versions.revision() == revision
                    && versions.generation() == generation && versions.nativeEpoch() == nativeEpoch;
        }

        public synchronized void beforeRefresh() {
            if (!current()) retired = true;
            refreshing = true;
        }

        public synchronized void afterRefresh() {
            if (!refreshing || retired || !inputs.current() || versions.revision() != revision + 1
                    || versions.generation() != generation + 2 || versions.nativeEpoch() != nativeEpoch + 2) {
                retired = true;
            } else {
                ++revision;
                generation += 2;
                nativeEpoch += 2;
            }
            refreshing = false;
        }
    }

    static final long DELAY_MS = 5000L;
    private final Scheduler scheduler;
    private Entry pending;

    private final class Entry implements Runnable {
        final Receipt receipt;
        final Runnable decision;
        Entry(Receipt receipt, Runnable decision) { this.receipt = receipt; this.decision = decision; }
        public void run() {
            synchronized (PurpleSaveDebounce.this) {
                if (pending != this) return;
                pending = null;
            }
            if (receipt.current()) decision.run();
        }
    }

    PurpleSaveDebounce(Scheduler scheduler) { this.scheduler = scheduler; }

    void admit(Receipt receipt, Runnable decision, boolean allowSend) {
        Runnable admission = () -> {
            cancel();
            if (allowSend) replace(receipt, decision);
        };
        if (scheduler.currentThread()) admission.run();
        else scheduler.post(admission, 0);
    }

    synchronized void cancel() {
        if (pending != null) scheduler.cancel(pending);
        pending = null;
    }

    synchronized void replace(Receipt receipt, Runnable decision) {
        cancel();
        if (!receipt.current()) return;
        pending = new Entry(receipt, decision);
        scheduler.post(pending, DELAY_MS);
    }

    synchronized void refresh(Runnable reload) {
        Entry original = pending;
        if (original != null) original.receipt.beforeRefresh();
        try { reload.run(); }
        finally { if (original != null) original.receipt.afterRefresh(); }
    }
}
