/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

interface PurpleSyncClient {
    final class Message {
        final int id;
        final boolean isMessage;
        final boolean forwarded;
        final boolean isDocument;
        final String caption;
        final String fileName;
        final boolean selfDialog;
        final long documentId;
        final long size;
        final long editDate;
        final Object remote;

        Message(int id, boolean isMessage, boolean forwarded,
                boolean isDocument, String caption, String fileName,
                boolean selfDialog, long documentId, long size, long editDate,
                Object remote) {
            this.id = id;
            this.isMessage = isMessage;
            this.forwarded = forwarded;
            this.isDocument = isDocument;
            this.caption = caption != null ? caption : "";
            this.fileName = fileName;
            this.selfDialog = selfDialog;
            this.documentId = documentId;
            this.size = size;
            this.editDate = editDate;
            this.remote = remote;
        }
    }

    final class History {
        final List<Message> messages;
        final int total;
        final String error;

        private History(List<Message> messages, int total, String error) {
            this.messages = messages;
            this.total = total;
            this.error = error;
        }

        static History of(List<Message> messages, int total) {
            return new History(
                    Collections.unmodifiableList(new ArrayList<>(messages)),
                    total, null);
        }

        static History failed(String error) {
            return new History(null, 0, error != null ? error : "Failed");
        }
    }

    enum LookupStatus { Found, Missing, Failed }

    final class Lookup {
        final LookupStatus status;
        final Message message;
        final String error;

        private Lookup(LookupStatus status, Message message, String error) {
            this.status = status;
            this.message = message;
            this.error = error;
        }

        static Lookup found(Message message) {
            if (message == null) {
                throw new IllegalArgumentException("found without a message");
            }
            return new Lookup(LookupStatus.Found, message, null);
        }

        static Lookup missing() {
            return new Lookup(LookupStatus.Missing, null, null);
        }

        static Lookup failed(String error) {
            return new Lookup(LookupStatus.Failed, null,
                    error != null ? error : "Failed");
        }
    }

    final class LocalCopy {
        final int id;
        final long dialogId;
        final int sendState;
        final List<String> fileNames;

        LocalCopy(int id, long dialogId, int sendState, List<String> fileNames) {
            this.id = id;
            this.dialogId = dialogId;
            this.sendState = sendState;
            this.fileNames = Collections.unmodifiableList(
                    new ArrayList<>(fileNames));
        }
    }

    interface Reply<T> {
        void onReply(T value);
    }

    interface Receipt {
        void onReceipt(int serverId, boolean preparationFailed);
    }

    long userId();

    boolean isActive();

    void runOnMain(Runnable task);

    void runOnWorker(Runnable task);

    void log(String line);

    int requestHistory(int offsetId, int limit, Reply<History> reply);

    int requestMessage(int id, Reply<Lookup> reply);

    void cancelRequest(int token);

    Object download(Message message, Reply<File> reply);

    void cancelDownload(Object download);

    void localCopies(Reply<List<LocalCopy>> reply);

    File filesDirectory();

    File cacheDirectory();

    long nowMillis();

    void send(File file, String caption, String mime, Receipt receipt);

    final class Operation {
        interface Owner {
            void onAccountLost();

            void onFailure(RuntimeException failure);
        }

        static final int ALREADY_FINISHED = -1;
        static final int FINISHED_BEFORE_HAND_OFF = 0;
        static final int FINISHED_AFTER_HAND_OFF = 1;

        final PurpleSyncClient client;
        final int generation;
        private final AtomicInteger current;
        private Owner owner;
        private boolean finished;
        private boolean handedOff;

        Operation(PurpleSyncClient client, AtomicInteger current) {
            this.client = client;
            this.current = current;
            this.generation = current.get();
        }

        void attach(Owner owner) {
            this.owner = owner;
        }

        synchronized boolean live() {
            return !finished && current.get() == generation;
        }

        synchronized int finish() {
            if (finished) {
                return ALREADY_FINISHED;
            }
            finished = true;
            return handedOff ? FINISHED_AFTER_HAND_OFF : FINISHED_BEFORE_HAND_OFF;
        }

        synchronized boolean handOff() {
            if (!live()) {
                return false;
            }
            handedOff = true;
            return true;
        }

        synchronized boolean handedOff() {
            return handedOff;
        }

        boolean proceed() {
            if (!live()) {
                return false;
            }
            if (!client.isActive()) {
                client.log("generation " + generation + ": account changed");
                owner.onAccountLost();
                return false;
            }
            return true;
        }

        void run(Runnable step) {
            if (!live()) {
                return;
            }
            try {
                step.run();
            } catch (RuntimeException failure) {
                client.log("generation " + generation + ": " + failure);
                owner.onFailure(failure);
            }
        }

        <T> Reply<T> guard(Reply<T> reply) {
            return value -> client.runOnWorker(() -> run(() -> reply.onReply(value)));
        }

        void progress(PurpleSyncTransport.Progress progress,
                PurpleSyncTransport.Phase phase, int done, int total) {
            if (progress == null || !live()) {
                return;
            }
            client.runOnMain(() -> progress.onProgress(phase, done, total));
        }
    }
}
