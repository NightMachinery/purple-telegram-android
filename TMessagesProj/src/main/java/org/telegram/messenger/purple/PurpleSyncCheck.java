/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.List;

final class PurpleSyncCheck implements PurpleSyncTelegramTransport.Task,
        PurpleSyncClient.Operation.Owner {
    private final PurpleSyncClient.Operation operation;
    private final PurpleSyncTransport.Progress progress;
    private final PurpleSyncTransport.CheckDone done;
    private PurpleSyncScanner scanner;
    private PurpleSyncReader reader;
    private PurpleSyncInventory inventory;
    private boolean queuedAtStart;

    PurpleSyncCheck(PurpleSyncClient.Operation operation,
            PurpleSyncTransport.Progress progress,
            PurpleSyncTransport.CheckDone done) {
        this.operation = operation;
        this.progress = progress;
        this.done = done;
        operation.attach(this);
    }

    @Override
    public void start() {
        operation.run(() -> operation.client.localCopies(
                operation.guard(this::onQueueAtStart)));
    }

    private void onQueueAtStart(List<PurpleSyncClient.LocalCopy> copies) {
        if (!operation.proceed()) {
            return;
        }
        queuedAtStart = holds(copies);
        scanner = new PurpleSyncScanner(operation, progress, this::onScanned);
        scanner.start();
    }

    private boolean holds(List<PurpleSyncClient.LocalCopy> copies) {
        return copies == null
                || PurpleSyncPost.holdsSyncRecord(operation.client.userId(),
                        copies);
    }

    @Override
    public void cancel() {
        stopWork();
        finish(new PurpleSyncTransport.CheckResult(
                PurpleSyncTransport.CheckStatus.Cancelled, null, false));
    }

    @Override
    public void onAccountLost() {
        stopWork();
        finish(new PurpleSyncTransport.CheckResult(
                PurpleSyncTransport.CheckStatus.AccountChanged, null, false));
    }

    @Override
    public void onFailure(RuntimeException failure) {
        stopWork();
        finish(new PurpleSyncTransport.CheckResult(
                PurpleSyncTransport.CheckStatus.Finished,
                new PurpleSyncInventory.Builder(operation.client.userId())
                        .build(false),
                true));
    }

    private void onScanned(PurpleSyncScanner.Result scan) {
        scanner = null;
        operation.client.log("generation " + operation.generation
                + ": scanned " + scan.scanned + " messages, "
                + (scan.complete ? scan.candidates.length + " candidates"
                        : "scan incomplete"));
        if (!scan.complete) {
            askQueue(new PurpleSyncInventory.Builder(operation.client.userId())
                    .build(false));
            return;
        }
        reader = new PurpleSyncReader(operation, scan.candidates, progress,
                this::onRead);
        reader.start();
    }

    private void onRead(List<PurpleSyncReader.Row> rows) {
        reader = null;
        final PurpleSyncInventory.Builder builder =
                new PurpleSyncInventory.Builder(operation.client.userId());
        for (PurpleSyncReader.Row row : rows) {
            if (row.outcome == PurpleSyncInventory.FETCHED) {
                builder.fetched(row.id, row.documentId, row.editDate, row.bytes);
            } else {
                builder.failed(row.id, row.outcome, row.documentId, row.editDate);
            }
        }
        askQueue(builder.build(true));
    }

    private void askQueue(PurpleSyncInventory result) {
        if (!operation.proceed()) {
            return;
        }
        inventory = result;
        operation.client.localCopies(operation.guard(this::onQueue));
    }

    private void onQueue(List<PurpleSyncClient.LocalCopy> copies) {
        final boolean queuedAtEnd = holds(copies);
        final boolean queued = queuedAtStart || queuedAtEnd;
        if (!operation.proceed()) {
            return;
        }
        operation.client.log("generation " + operation.generation + ": "
                + inventory.summary() + " sendQueued=" + queued
                + (copies == null ? " (queue unreadable)" : "")
                + (queuedAtStart && !queuedAtEnd
                        ? " (a queued copy left the queue during the check)"
                        : ""));
        finish(new PurpleSyncTransport.CheckResult(
                PurpleSyncTransport.CheckStatus.Finished, inventory, queued));
    }

    private void stopWork() {
        if (scanner != null) {
            scanner.cancel();
            scanner = null;
        }
        if (reader != null) {
            reader.cancel();
            reader = null;
        }
    }

    private void finish(PurpleSyncTransport.CheckResult result) {
        if (operation.finish() == PurpleSyncClient.Operation.ALREADY_FINISHED) {
            return;
        }
        operation.client.runOnMain(() -> done.onCheckDone(result));
    }
}
