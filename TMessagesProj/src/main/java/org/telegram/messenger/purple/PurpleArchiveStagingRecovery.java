package org.telegram.messenger.purple;

import java.util.IdentityHashMap;
import org.json.JSONObject;

final class PurpleArchiveStagingRecovery {
    private static final IdentityHashMap<PurpleArchiveStore, Runner> runners = new IdentityHashMap<>();
    static final class Runner {
        final PurpleArchive.Worker worker;
        final PurpleArchiveStore store;
        final PurpleAccountFiles.Identity identity;
        final long selection = PurpleArchiveViewerModel.selectionGeneration();
        PurpleArchiveFiles.Listing listing;
        String[] page = new String[0]; int index;
        PurpleArchiveStaging job;
        boolean closed;
        Runner(PurpleArchive.Worker worker) { this.worker = worker; store = worker.store; identity = worker.identity; }
        boolean current() { return !closed && !worker.closing && !worker.retired && worker.store == store && identity.current() && selection == PurpleArchiveViewerModel.selectionGeneration(); }
        void run() {
            try {
                if (!current()) { close(); return; }
                store.requireCurrent();
                if (job != null) {
                    int status = job.finishStep();
                    if (status == 0) { PurpleArchiveGaps.record(store, "staging_partial_after_restart", System.currentTimeMillis() / 1000); job.closeFinalizer(); job = null; }
                    else if (status == 2) job = null;
                } else {
                    if (listing == null) {
                        if (PurpleArchiveFiles.markAny(store.directory.file("staging")) == null) { close(); return; }
                        PurpleArchiveFiles.Directory root = store.directory.child("staging", true);
                        if (PurpleArchiveFiles.markAny(root.file("stream")) == null) { close(); return; }
                        listing = root.child("stream", true).listing();
                    }
                    if (index == page.length) { page = listing.next(); index = 0; if (page.length == 0) { close(); return; } }
                    String id = page[index++];
                    if (!id.matches("[0-9a-f]{64}")) throw new PurpleArchiveStore.Failure("Archive retained staging name refused");
                    JSONObject binding = PurpleArchiveStaging.readBinding(store, id); int status = PurpleArchiveStaging.status(store, binding);
                    if (status == 0) {
                        PurpleArchiveStaging retained = PurpleArchiveStaging.retained(store, id, this::current);
                        if (retained.terminal()) { retained.refuse(this::current); PurpleArchiveGaps.record(store, "staging_terminal_after_restart", System.currentTimeMillis() / 1000); worker.control(this::run); return; }
                        try {
                            JSONObject inspection = retained.call("stagingInspect", new JSONObject().put("header", retained.head)); PurpleArchiveStaging.ready(inspection);
                            if (!inspection.getString("covered").equals(binding.getString("total"))) {
                                PurpleArchiveGaps.record(store, "staging_partial_after_restart", System.currentTimeMillis() / 1000);
                            } else { retained.requireConsistency(); retained.recovery = true; job = retained; }
                        }
                        catch (Exception | LinkageError unadmitted) {
                            PurpleArchiveGaps.record(store, "staging_consistency_unadmitted", System.currentTimeMillis() / 1000);
                            retained.closeFinalizer();
                            retained.discardUnadmitted();
                        }
                    }
                    else if (status != 14) PurpleArchiveGaps.record(store, "staging_source_no_longer_admitted", System.currentTimeMillis() / 1000);
                }
            } catch (PurpleArchiveStaging.CacheRebuilding pending) { }
            catch (Exception | LinkageError e) {
                try { if (current() && store.current()) PurpleArchiveGaps.record(store, "staging_recovery_refused", System.currentTimeMillis() / 1000); }
                catch (Exception | LinkageError ignored) {}
                PurpleArchiveStaging failed = job; job = null;
                if (failed != null) {
                    try { failed.refuse(this::current); } catch (Exception | LinkageError ignored) {}
                    try { failed.closeFinalizer(); } catch (Exception | LinkageError ignored) {}
                }
                try { close(); } catch (Exception | LinkageError ignored) {}
            }
            if (!closed) worker.control(this::run);
        }
        void close() throws Exception {
            if (job != null) { job.closeFinalizer(); job = null; }
            if (listing != null) { listing.close(); listing = null; }
            closed = true; synchronized (runners) { runners.remove(store); }
        }
    }
    static void start(PurpleArchive.Worker worker) {
        synchronized (runners) {
            if (worker.store == null || runners.containsKey(worker.store)) return;
            Runner runner = new Runner(worker); runners.put(worker.store, runner); worker.control(runner::run);
        }
    }
    static void close(PurpleArchiveStore store) throws Exception {
        Runner runner; synchronized (runners) { runner = runners.get(store); }
        if (runner != null) runner.close();
    }
    private PurpleArchiveStagingRecovery() {}
}
