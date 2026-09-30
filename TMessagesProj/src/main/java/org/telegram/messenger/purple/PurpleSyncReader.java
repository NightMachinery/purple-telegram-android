/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PurpleSyncReader {
    static final int MAX_RECORD_BYTES = 4 * 1024 * 1024;

    static final class Row {
        final int id;
        final int outcome;
        final long documentId;
        final long editDate;
        final byte[] bytes;

        Row(int id, int outcome, long documentId, long editDate, byte[] bytes) {
            this.id = id;
            this.outcome = outcome;
            this.documentId = documentId;
            this.editDate = editDate;
            this.bytes = bytes;
        }
    }

    interface Done {
        void onRead(List<Row> rows);
    }

    private final PurpleSyncClient.Operation operation;
    private final int[] ids;
    private final PurpleSyncTransport.Progress progress;
    private final Done done;
    private final List<Row> rows = new ArrayList<>();
    private int next;
    private boolean requesting;
    private int token;
    private Object download;

    PurpleSyncReader(PurpleSyncClient.Operation operation, int[] ids,
            PurpleSyncTransport.Progress progress, Done done) {
        this.operation = operation;
        this.ids = ids.clone();
        this.progress = progress;
        this.done = done;
    }

    void start() {
        operation.progress(progress, PurpleSyncTransport.Phase.Reading, 0,
                ids.length);
        readNext();
    }

    void cancel() {
        if (requesting) {
            requesting = false;
            operation.client.cancelRequest(token);
        }
        if (download != null) {
            final Object running = download;
            download = null;
            operation.client.cancelDownload(running);
        }
    }

    private void readNext() {
        if (next == ids.length) {
            done.onRead(Collections.unmodifiableList(new ArrayList<>(rows)));
            return;
        }
        if (!operation.proceed()) {
            return;
        }
        final int id = ids[next];
        requesting = true;
        token = operation.client.requestMessage(id,
                operation.guard(lookup -> onLookup(id, lookup)));
    }

    private void onLookup(int id, PurpleSyncClient.Lookup lookup) {
        requesting = false;
        if (lookup.status == PurpleSyncClient.LookupStatus.Failed) {
            operation.client.log("message " + id + " request failed: "
                    + lookup.error);
            complete(new Row(id, PurpleSyncInventory.REQUEST_FAILED, 0, 0, null));
            return;
        }
        if (lookup.status == PurpleSyncClient.LookupStatus.Missing) {
            complete(new Row(id, PurpleSyncInventory.VANISHED, 0, 0, null));
            return;
        }
        final PurpleSyncClient.Message message = lookup.message;
        if (message.id != id || !message.isMessage || !message.selfDialog) {
            complete(new Row(id, PurpleSyncInventory.CHANGED, 0, 0, null));
            return;
        }
        final PurpleSyncCore.Page page = PurpleSyncCore.classifyHistoryPage(0,
                new int[] { message.id },
                new boolean[] { message.isMessage },
                new boolean[] { message.forwarded },
                new boolean[] { message.isDocument },
                new String[] { message.caption },
                new String[] { message.fileName });
        if (!page.isValid() || page.status != PurpleSyncCore.PageStatus.More) {
            operation.client.log("message " + id + " not classified: "
                    + page.error);
            complete(new Row(id, PurpleSyncInventory.INACCESSIBLE, 0, 0, null));
            return;
        }
        if (page.candidates.length != 1 || page.candidates[0] != id) {
            complete(new Row(id, PurpleSyncInventory.CHANGED, 0, 0, null));
            return;
        }
        final long documentId = message.documentId;
        final long editDate = message.editDate > 0 ? message.editDate : 0;
        if (message.size <= 0) {
            complete(new Row(id, PurpleSyncInventory.INVALID, documentId,
                    editDate, null));
            return;
        }
        if (message.size > MAX_RECORD_BYTES) {
            complete(new Row(id, PurpleSyncInventory.OVERSIZED, documentId,
                    editDate, null));
            return;
        }
        if (!operation.proceed()) {
            return;
        }
        download = operation.client.download(message,
                operation.guard(file -> onFile(message, file)));
    }

    private void onFile(PurpleSyncClient.Message message, File file) {
        download = null;
        final long documentId = message.documentId;
        final long editDate = message.editDate > 0 ? message.editDate : 0;
        final byte[] bytes = file != null
                ? readAtMost(file, MAX_RECORD_BYTES + 1) : null;
        final int outcome;
        if (bytes == null) {
            operation.client.log("message " + message.id
                    + " document not downloaded");
            outcome = PurpleSyncInventory.INACCESSIBLE;
        } else if (bytes.length > MAX_RECORD_BYTES) {
            outcome = PurpleSyncInventory.OVERSIZED;
        } else if (bytes.length != message.size) {
            operation.client.log("message " + message.id + " document has "
                    + bytes.length + " bytes, expected " + message.size);
            outcome = PurpleSyncInventory.INACCESSIBLE;
        } else {
            outcome = PurpleSyncInventory.FETCHED;
        }
        complete(new Row(message.id, outcome, documentId, editDate,
                outcome == PurpleSyncInventory.FETCHED ? bytes : null));
    }

    private void complete(Row row) {
        rows.add(row);
        ++next;
        operation.progress(progress, PurpleSyncTransport.Phase.Reading, next,
                ids.length);
        readNext();
    }

    static byte[] readAtMost(File file, int limit) {
        if (!file.isFile()) {
            return null;
        }
        try (InputStream input = new FileInputStream(file)) {
            final ByteArrayOutputStream output = new ByteArrayOutputStream(
                    (int) Math.min(limit, Math.max(0, file.length())));
            final byte[] buffer = new byte[64 * 1024];
            int total = 0;
            while (total < limit) {
                final int read = input.read(buffer, 0,
                        Math.min(buffer.length, limit - total));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
