/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.Arrays;
import java.util.List;

final class PurpleSyncScanner {
    static final int PAGE_SIZE = 100;
    private static final java.util.concurrent.atomic.AtomicLong reads = new java.util.concurrent.atomic.AtomicLong();
    static long readSequence() { return reads.get(); }

    static final class Result {
        final boolean complete;
        final int[] candidates;
        final long scanned;
        final long readSequence;

        private final PurpleSyncChatOrigin origin;
        private final PurpleSyncChatCore.Peer chat;
        private final int[] sealedIds;
        private Result(boolean complete, int[] candidates, long scanned, PurpleSyncChatOrigin origin, PurpleSyncChatCore.Peer chat,long readSequence) {
            this.readSequence=readSequence;
            this.origin=origin;this.chat=chat;sealedIds=candidates.clone();
            this.complete = complete;
            this.candidates = candidates;
            this.scanned = scanned;
        }
        boolean matches(PurpleSyncChatOrigin origin,PurpleSyncChatCore.Peer chat,int[] ids) {
            return complete && this.origin!=null && this.origin.equals(origin) && this.chat.equals(chat) && Arrays.equals(sealedIds,ids);
        }
    }

    interface Done {
        void onScanned(Result result);
    }

    private final PurpleSyncClient.Operation operation;
    private final PurpleSyncTransport.Progress progress;
    private final Done done;
    private final PurpleSyncChatOrigin origin;
    private final PurpleSyncChatCore.Peer chat;
    private final long readSequence=reads.incrementAndGet();
    private int offset;
    private long scanned;
    private int[] candidates = new int[16];
    private int candidateCount;
    private boolean requesting;
    private int token;

    PurpleSyncScanner(PurpleSyncClient.Operation operation,
            PurpleSyncTransport.Progress progress, Done done) {
        this.operation = operation;
        origin=operation.client.origin();chat=operation.client.chat();
        this.progress = progress;
        this.done = done;
    }

    void start() {
        requestNext();
    }

    void cancel() {
        if (requesting) {
            requesting = false;
            operation.client.cancelRequest(token);
        }
    }

    private void requestNext() {
        if (!operation.proceed()) {
            return;
        }
        requesting = true;
        token = operation.client.requestHistory(offset, PAGE_SIZE,
                operation.guard(this::onHistory));
    }

    private void onHistory(PurpleSyncClient.History history) {
        requesting = false;
        if (!java.util.Objects.equals(origin,operation.client.origin()) || !chat.equals(operation.client.chat()) || history.messages == null) {
            operation.client.log("history page after " + offset
                    + " failed: " + history.error);
            finish(false);
            return;
        }
        final List<PurpleSyncClient.Message> messages = history.messages;
        final int count = messages.size();
        final int[] ids = new int[count];
        final boolean[] isMessage = new boolean[count];
        final boolean[] forwarded = new boolean[count];
        final boolean[] isDocument = new boolean[count];
        final String[] captions = new String[count];
        final String[] fileNames = new String[count];
        for (int i = 0; i != count; ++i) {
            final PurpleSyncClient.Message message = messages.get(i);
            ids[i] = message.id;
            isMessage[i] = message.isMessage;
            forwarded[i] = message.forwarded;
            isDocument[i] = message.isDocument;
            captions[i] = message.caption;
            fileNames[i] = message.fileName;
        }
        final PurpleSyncCore.Page page = PurpleSyncCore.classifyHistoryPage(
                offset, ids, isMessage, forwarded, isDocument, captions,
                fileNames);
        if (!page.isValid()) {
            operation.client.log("history page after " + offset
                    + " not classified: " + page.error);
            finish(false);
            return;
        }
        if (page.status == PurpleSyncCore.PageStatus.Complete) {
            finish(true);
            return;
        }
        if (page.status == PurpleSyncCore.PageStatus.Stalled) {
            operation.client.log("history page after " + offset + " stalled");
            finish(false);
            return;
        }
        scanned += page.count;
        for (int id : page.candidates) {
            if (candidateCount == candidates.length) {
                candidates = Arrays.copyOf(candidates, candidateCount * 2);
            }
            candidates[candidateCount++] = id;
        }
        offset = page.offset;
        final int done = (int) Math.min(Integer.MAX_VALUE, scanned);
        operation.progress(progress, PurpleSyncTransport.Phase.Scanning, done,
                Math.max(done, history.total));
        requestNext();
    }

    private void finish(boolean complete) {
        done.onScanned(new Result(complete,
                complete ? Arrays.copyOf(candidates, candidateCount) : new int[0],
                scanned,origin,chat,readSequence));
    }
}
