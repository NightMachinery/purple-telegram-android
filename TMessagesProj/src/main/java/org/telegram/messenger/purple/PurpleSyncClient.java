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
    final class ManagedReads {
        static final ManagedReads NONE=new ManagedReads(false,0,0);
        final boolean active;
        final long revision,completedAt;
        ManagedReads(boolean active,long revision,long completedAt){this.active=active;this.revision=revision;this.completedAt=completedAt;}
    }
    default ManagedReads managedReads(){return ManagedReads.NONE;}
    final class Message {
        final int id;
        final boolean isMessage;
        final boolean forwarded;
        final boolean isDocument;
        final String caption;
        final String fileName;
        final boolean selfDialog;
        final PurpleSyncChatCore.Peer chat;
        final long documentId;
        final long size;
        final long editDate;
        final Object remote;

        Message(int id, boolean isMessage, boolean forwarded,
                boolean isDocument, String caption, String fileName,
                boolean selfDialog, long documentId, long size, long editDate,
                Object remote) {
            this(id,isMessage,forwarded,isDocument,caption,fileName,
                    selfDialog ? PurpleSyncChatCore.Peer.self() : null,documentId,size,editDate,remote);
        }

        Message(int id, boolean isMessage, boolean forwarded,
                boolean isDocument, String caption, String fileName,
                PurpleSyncChatCore.Peer chat, long documentId, long size, long editDate,
                Object remote) {
            this.id = id;
            this.isMessage = isMessage;
            this.forwarded = forwarded;
            this.isDocument = isDocument;
            this.caption = caption != null ? caption : "";
            this.fileName = fileName;
            this.chat = chat;
            this.selfDialog = PurpleSyncChatCore.Peer.self().equals(chat);
            this.documentId = documentId;
            this.size = size;
            this.editDate = editDate;
            this.remote = remote;
        }
    }

    final class History {
        final List<Message> messages;
        final int total;
        final Integer declaredTotal;
        final String error;

        private History(List<Message> messages, int total, String error, Integer declaredTotal) {
            this.messages = messages;
            this.total = total;
            this.declaredTotal = declaredTotal;
            this.error = error;
        }

        static History of(List<Message> messages, int total) {
            return page(messages,total);
        }

        static History page(List<Message> messages, Integer declaredTotal) {
            return new History(Collections.unmodifiableList(new ArrayList<>(messages)),
                    declaredTotal != null ? declaredTotal : messages.size(), null, declaredTotal);
        }

        static History failed(String error) {
            return new History(null, 0, error != null ? error : "Failed", null);
        }
    }

    enum LookupStatus { Found, Missing, Failed }

    final class Lookup {
        final LookupStatus status;
        final Message message;
        final String error;
        final PurpleSyncChatIO route;
        final byte[] response;

        private Lookup(LookupStatus status, Message message, String error) {
            this(status, message, error, null, null);
        }

        private Lookup(LookupStatus status, Message message, String error, PurpleSyncChatIO route, byte[] response) {
            this.route = route; this.response = response != null ? response.clone() : null;
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

        static Lookup found(Message message, PurpleSyncChatIO route, byte[] response) {
            return new Lookup(LookupStatus.Found, message, null, route, response);
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
        default byte[] expectedBytes(){return null;}
        default boolean current(){return true;}
        default boolean automatic(){return false;}
        default PurpleSyncTransport.PostAuthority authority(){return null;}
        default void transientFailure(PurpleRpcRetry.Transient waiting){}
        default void ownSend(Runnable cancel){}
        void onReceipt(int serverId, boolean preparationFailed);
    }

    default long transientRetryAt(){return 0;}
    default void transientWait(java.util.function.Consumer<PurpleRpcRetry.Transient> observer){}
    default boolean hasQueuedSync() { return false; }
    long userId();
    default PurpleSyncChatCore.Peer chat() { return PurpleSyncChatCore.Peer.self(); }
    default PurpleSyncChatOrigin origin() { return null; }
    default long dialogId() { return PurpleSyncChatIO.dialogId(chat(),userId()); }
    default boolean contains(Message message) { return message != null && chat().equals(message.chat); }

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

    default void edit(int id,File file,byte[] expected,java.util.function.BooleanSupplier current,Reply<PurpleSyncAutoCore.Outcome> reply){reply.onReply(PurpleSyncAutoCore.Outcome.EditRefused);}
    default void edit(int id,File file,byte[] expected,java.util.function.BooleanSupplier current,
            PurpleSyncTransport.PostAuthority authority,Reply<PurpleSyncAutoCore.Outcome> reply){edit(id,file,expected,current,reply);}
    default void deleteOwn(int id,java.util.function.BooleanSupplier current,Reply<PurpleSyncAutoCore.Outcome> reply){reply.onReply(PurpleSyncAutoCore.Outcome.DeletionRefused);}

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
