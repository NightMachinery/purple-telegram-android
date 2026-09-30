/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class PurpleSyncPost implements PurpleSyncTelegramTransport.Task,
        PurpleSyncClient.Operation.Owner {
    static final String RECORD_FILE_NAME = "Purple settings sync.json";
    static final String CAPTION = "#purplesync";
    static final String MIME = "application/json";
    static final String STAGING_DIRECTORY = "purple-sync-records";
    static final String NO_MEDIA = ".nomedia";
    static final int MAX_STAGED_BYTES = 256 * 1024;
    static final long STAGING_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    static final int SEND_STATE_SENDING = 1;
    static final int SEND_STATE_FAILED = 2;

    private final PurpleSyncClient.Operation operation;
    private final byte[] staged;
    private final PurpleSyncTransport.PostDone done;
    private File copy;
    private PurpleSyncReader reader;
    private int messageId;

    PurpleSyncPost(PurpleSyncClient.Operation operation, byte[] staged,
            PurpleSyncTransport.PostDone done) {
        this.operation = operation;
        this.staged = staged != null ? staged.clone() : null;
        this.done = done;
        operation.attach(this);
    }

    static boolean validRecord(byte[] record) {
        if (record == null || record.length == 0
                || record.length > MAX_STAGED_BYTES) {
            return false;
        }
        final PurpleAccountSyncCore.Result inspected =
                PurpleAccountSyncCore.inspectConfigRecord(record);
        return inspected.isValid() && Arrays.equals(inspected.record, record);
    }

    static boolean holdsSyncRecord(long userId,
            List<PurpleSyncClient.LocalCopy> copies) {
        for (PurpleSyncClient.LocalCopy copy : copies) {
            if (copy.id < 0 && copy.dialogId == userId
                    && (copy.sendState == SEND_STATE_SENDING
                            || copy.sendState == SEND_STATE_FAILED)
                    && copy.fileNames.contains(RECORD_FILE_NAME)) {
                return true;
            }
        }
        return false;
    }

    static void prune(File root, long now) {
        final File[] directories = root.listFiles();
        if (directories == null) {
            return;
        }
        final long oldest = now - STAGING_MAX_AGE_MS;
        for (File directory : directories) {
            if (!directory.isDirectory() || directory.lastModified() >= oldest) {
                continue;
            }
            final File record = new File(directory, RECORD_FILE_NAME);
            if (record.isFile() && record.lastModified() < oldest) {
                if (record.delete()) {
                    directory.delete();
                }
            } else if (!record.exists()) {
                directory.delete();
            }
        }
    }

    @Override
    public void start() {
        operation.run(() -> {
            if (!validRecord(staged)) {
                finish(result(PurpleSyncTransport.PostStatus.InvalidRecord));
                return;
            }
            if (!operation.proceed()) {
                return;
            }
            copy = stage();
            if (copy == null) {
                operation.client.log("generation " + operation.generation
                        + ": could not stage the record");
                finish(result(PurpleSyncTransport.PostStatus.OutcomeUnknown));
                return;
            }
            operation.client.runOnMain(this::handOff);
        });
    }

    @Override
    public void cancel() {
        stopWork();
        finishUnsent(PurpleSyncTransport.PostStatus.Cancelled);
    }

    @Override
    public void onAccountLost() {
        stopWork();
        finishUnsent(PurpleSyncTransport.PostStatus.Cancelled);
    }

    @Override
    public void onFailure(RuntimeException failure) {
        stopWork();
        finishUnsent(PurpleSyncTransport.PostStatus.OutcomeUnknown);
    }

    private File stage() {
        final long now = operation.client.nowMillis();
        final File cache = operation.client.cacheDirectory();
        if (cache != null) {
            final File former = new File(cache, STAGING_DIRECTORY);
            prune(former, now);
            former.delete();
        }
        final File files = operation.client.filesDirectory();
        if (files == null) {
            return null;
        }
        final File root = new File(files, STAGING_DIRECTORY);
        prune(root, now);
        final File directory = new File(root, UUID.randomUUID().toString());
        if (!directory.mkdirs()) {
            return null;
        }
        hideFromMediaScanner(root);
        final File file = new File(directory, RECORD_FILE_NAME);
        if (!write(file, staged) || !Arrays.equals(
                PurpleSyncReader.readAtMost(file, MAX_STAGED_BYTES + 1), staged)) {
            remove(file);
            return null;
        }
        return file;
    }

    private void handOff() {
        final boolean sending = operation.client.isActive()
                && operation.handOff();
        if (!sending) {
            operation.client.runOnWorker(() -> {
                remove(copy);
                operation.run(() -> finish(
                        result(PurpleSyncTransport.PostStatus.Cancelled)));
            });
            return;
        }
        operation.client.log("generation " + operation.generation
                + ": handing the record to Telegram");
        try {
            operation.client.send(copy, CAPTION, MIME,
                    (serverId, preparationFailed) -> operation.client.runOnWorker(
                            () -> operation.run(
                                    () -> onReceipt(serverId, preparationFailed))));
        } catch (RuntimeException failure) {
            operation.client.log("generation " + operation.generation
                    + ": the hand-off threw " + failure);
            operation.client.runOnWorker(
                    () -> operation.run(() -> onReceipt(0, true)));
        }
    }

    private void onReceipt(int serverId, boolean preparationFailed) {
        if (preparationFailed) {
            operation.client.log("generation " + operation.generation
                    + ": Telegram could not prepare the record");
            remove(copy);
            finish(result(PurpleSyncTransport.PostStatus.OutcomeUnknown));
            return;
        }
        if (serverId <= 0) {
            operation.client.log("generation " + operation.generation
                    + ": the send failed or its outcome is unknown");
            finish(result(PurpleSyncTransport.PostStatus.OutcomeUnknown));
            return;
        }
        messageId = serverId;
        final File directory = copy.getParentFile();
        if (!copy.exists() && directory != null) {
            directory.delete();
        }
        if (!operation.proceed()) {
            return;
        }
        reader = new PurpleSyncReader(operation, new int[] { serverId }, null,
                this::onReadBack);
        reader.start();
    }

    private void onReadBack(List<PurpleSyncReader.Row> rows) {
        reader = null;
        final PurpleSyncReader.Row row = rows.size() == 1 ? rows.get(0) : null;
        if (row != null && row.id == messageId
                && row.outcome == PurpleSyncInventory.FETCHED
                && Arrays.equals(row.bytes, staged)) {
            if (!operation.proceed()) {
                return;
            }
            finish(new PurpleSyncTransport.PostResult(
                    PurpleSyncTransport.PostStatus.Confirmed, messageId,
                    row.bytes));
            return;
        }
        final boolean unknown = row == null
                || row.outcome == PurpleSyncInventory.INACCESSIBLE
                || row.outcome == PurpleSyncInventory.REQUEST_FAILED
                || row.outcome == PurpleSyncInventory.CANCELLED;
        operation.client.log("generation " + operation.generation
                + ": read-back of message " + messageId + " gave outcome "
                + (row != null ? row.outcome : -1));
        finish(result(unknown ? PurpleSyncTransport.PostStatus.OutcomeUnknown
                : PurpleSyncTransport.PostStatus.NeedsReview));
    }

    private void stopWork() {
        if (reader != null) {
            reader.cancel();
            reader = null;
        }
    }

    private PurpleSyncTransport.PostResult result(
            PurpleSyncTransport.PostStatus status) {
        return new PurpleSyncTransport.PostResult(status, messageId, null);
    }

    private void finishUnsent(PurpleSyncTransport.PostStatus unsent) {
        final int finished = operation.finish();
        if (finished == PurpleSyncClient.Operation.ALREADY_FINISHED) {
            return;
        }
        deliver(result(finished == PurpleSyncClient.Operation.FINISHED_AFTER_HAND_OFF
                ? PurpleSyncTransport.PostStatus.OutcomeUnknown : unsent));
    }

    private void finish(PurpleSyncTransport.PostResult result) {
        if (operation.finish() == PurpleSyncClient.Operation.ALREADY_FINISHED) {
            return;
        }
        deliver(result);
    }

    private void deliver(PurpleSyncTransport.PostResult result) {
        operation.client.log("generation " + operation.generation
                + ": post finished with " + result.status);
        operation.client.runOnMain(() -> done.onPostDone(result));
    }

    private static void hideFromMediaScanner(File root) {
        try {
            new File(root, NO_MEDIA).createNewFile();
        } catch (IOException | SecurityException ignored) {
        }
    }

    private static boolean write(File file, byte[] bytes) {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.flush();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static void remove(File file) {
        if (file == null) {
            return;
        }
        file.delete();
        final File directory = file.getParentFile();
        if (directory != null) {
            directory.delete();
        }
    }
}
