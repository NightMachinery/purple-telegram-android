/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PurpleSyncTelegramClient implements PurpleSyncClient {
    private static final long DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1000L;
    private static DispatchQueue queue;

    private final int account;
    private final long userId;

    private PurpleSyncTelegramClient(int account, long userId) {
        this.account = account;
        this.userId = userId;
    }

    static PurpleSyncClient create(int account, long userId) {
        return new PurpleSyncTelegramClient(account, userId);
    }

    private static synchronized DispatchQueue queue() {
        if (queue == null) {
            queue = new DispatchQueue("purpleSyncTransport");
        }
        return queue;
    }

    private static final class Remote {
        final TLRPC.Message message;
        final TLRPC.Document document;

        Remote(TLRPC.Message message, TLRPC.Document document) {
            this.message = message;
            this.document = document;
        }
    }

    @Override
    public long userId() {
        return userId;
    }

    @Override
    public boolean isActive() {
        return userId > 0 && PurpleAccountBinding.isSameActiveUser(account, userId);
    }

    @Override
    public void runOnMain(Runnable task) {
        AndroidUtilities.runOnUIThread(task);
    }

    @Override
    public void runOnWorker(Runnable task) {
        queue().postRunnable(task);
    }

    @Override
    public void log(String line) {
        FileLog.d("Purple: sync transport: account " + account + ": " + line);
    }

    @Override
    public int requestHistory(int offsetId, int limit, Reply<History> reply) {
        final TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.offset_id = offsetId;
        request.limit = limit;
        return ConnectionsManager.getInstance(account).sendRequest(request,
                (response, error) -> {
                    History history;
                    try {
                        history = history(response, error);
                    } catch (RuntimeException e) {
                        FileLog.e(e);
                        history = History.failed("UnreadableResponse");
                    }
                    reply.onReply(history);
                });
    }

    @Override
    public int requestMessage(int id, Reply<Lookup> reply) {
        final TLRPC.TL_messages_getMessages request = new TLRPC.TL_messages_getMessages();
        request.id.add(id);
        return ConnectionsManager.getInstance(account).sendRequest(request,
                (response, error) -> {
                    Lookup lookup;
                    try {
                        lookup = lookup(response, error);
                    } catch (RuntimeException e) {
                        FileLog.e(e);
                        lookup = Lookup.failed("UnreadableResponse");
                    }
                    reply.onReply(lookup);
                });
    }

    @Override
    public void cancelRequest(int token) {
        ConnectionsManager.getInstance(account).cancelRequest(token, true);
    }

    @Override
    public Object download(Message message, Reply<File> reply) {
        final Download download = new Download((Remote) message.remote, reply);
        AndroidUtilities.runOnUIThread(download::start);
        return download;
    }

    @Override
    public void cancelDownload(Object download) {
        AndroidUtilities.runOnUIThread(((Download) download)::stop);
    }

    @Override
    public void localCopies(Reply<List<LocalCopy>> reply) {
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(
                () -> reply.onReply(queryLocalCopies(storage)));
    }

    @Override
    public File filesDirectory() {
        return ApplicationLoader.applicationContext.getExternalFilesDir(null);
    }

    @Override
    public File cacheDirectory() {
        return FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
    }

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void send(File file, String caption, String mime, Receipt receipt) {
        SendMessagesHelper.prepareSendingPurpleDocument(
                AccountInstance.getInstance(account), file.getAbsolutePath(),
                caption, userId, mime, receipt::onReceipt);
    }

    private History history(TLObject response, TLRPC.TL_error error) {
        if (error != null) {
            return History.failed(error.text);
        }
        if (!(response instanceof TLRPC.messages_Messages)
                || response instanceof TLRPC.TL_messages_messagesNotModified) {
            return History.failed("InvalidResponse");
        }
        final TLRPC.messages_Messages page = (TLRPC.messages_Messages) response;
        final List<Message> messages = new ArrayList<>(page.messages.size());
        for (TLRPC.Message message : page.messages) {
            messages.add(meta(message));
        }
        return History.of(messages, Math.max(page.count, messages.size()));
    }

    private Lookup lookup(TLObject response, TLRPC.TL_error error) {
        if (error != null) {
            return Lookup.failed(error.text);
        }
        if (!(response instanceof TLRPC.messages_Messages)) {
            return Lookup.failed("InvalidResponse");
        }
        final TLRPC.messages_Messages found = (TLRPC.messages_Messages) response;
        if (response instanceof TLRPC.TL_messages_messagesNotModified
                || found.messages.size() != 1
                || found.messages.get(0) == null
                || found.messages.get(0) instanceof TLRPC.TL_messageEmpty) {
            return Lookup.missing();
        }
        return Lookup.found(meta(found.messages.get(0)));
    }

    private Message meta(TLRPC.Message message) {
        final boolean isMessage = message instanceof TLRPC.TL_message;
        final TLRPC.Document document = isMessage
                && message.media instanceof TLRPC.TL_messageMediaDocument
                ? message.media.document : null;
        final boolean isDocument = document instanceof TLRPC.TL_document;
        return new Message(
                message.id,
                isMessage,
                isMessage && message.fwd_from != null,
                isDocument,
                isMessage ? message.message : "",
                isDocument ? firstFileName(document) : null,
                message.peer_id != null
                        && MessageObject.getDialogId(message) == userId,
                isDocument ? document.id : 0,
                isDocument ? document.size : 0,
                isMessage ? message.edit_date : 0,
                isDocument ? new Remote(message, document) : null);
    }

    private static String firstFileName(TLRPC.Document document) {
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                return attribute.file_name;
            }
        }
        return null;
    }

    private static List<String> fileNames(TLRPC.Message message) {
        final List<String> names = new ArrayList<>();
        if (message.media instanceof TLRPC.TL_messageMediaDocument
                && message.media.document != null) {
            for (TLRPC.DocumentAttribute attribute : message.media.document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeFilename
                        && attribute.file_name != null) {
                    names.add(attribute.file_name);
                }
            }
        }
        return names;
    }

    private List<LocalCopy> queryLocalCopies(MessagesStorage storage) {
        SQLiteCursor cursor = null;
        try {
            final SQLiteDatabase database = storage.getDatabase();
            if (database == null) {
                return null;
            }
            final List<LocalCopy> copies = new ArrayList<>();
            cursor = database.queryFinalized(String.format(Locale.US,
                    "SELECT uid, mid, send_state, data FROM messages_v2 WHERE uid = %d AND mid < 0",
                    userId));
            while (cursor.next()) {
                final NativeByteBuffer data = cursor.byteBufferValue(3);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message;
                try {
                    message = TLRPC.Message.TLdeserialize(
                            data, data.readInt32(false), false);
                } catch (RuntimeException e) {
                    FileLog.e(e);
                    message = null;
                } finally {
                    data.reuse();
                }
                if (message == null) {
                    continue;
                }
                copies.add(new LocalCopy(cursor.intValue(1), cursor.longValue(0),
                        cursor.intValue(2), fileNames(message)));
            }
            return copies;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    private final class Download implements NotificationCenter.NotificationCenterDelegate {
        private final Remote remote;
        private final Reply<File> reply;
        private final String name;
        private final Runnable timeout = () -> finish(null);
        private boolean observing;
        private boolean finished;

        Download(Remote remote, Reply<File> reply) {
            this.remote = remote;
            this.reply = reply;
            this.name = FileLoader.getAttachFileName(remote.document);
        }

        void start() {
            if (finished) {
                return;
            }
            try {
                final FileLoader loader = FileLoader.getInstance(account);
                final File cached = loader.getPathToAttach(remote.document, true);
                if (cached.isFile() && cached.length() == remote.document.size) {
                    finish(cached);
                    return;
                }
                final NotificationCenter center = NotificationCenter.getInstance(account);
                center.addObserver(this, NotificationCenter.fileLoaded);
                center.addObserver(this, NotificationCenter.fileLoadFailed);
                observing = true;
                AndroidUtilities.runOnUIThread(timeout, DOWNLOAD_TIMEOUT_MS);
                loader.loadFile(remote.document,
                        new MessageObject(account, remote.message, false, false),
                        FileLoader.PRIORITY_NORMAL_UP, ImageLoader.CACHE_TYPE_CACHE);
            } catch (RuntimeException e) {
                FileLog.e(e);
                finish(null);
            }
        }

        void stop() {
            finished = true;
            release();
        }

        @Override
        public void didReceivedNotification(int id, int notifiedAccount, Object... args) {
            if (finished || args.length == 0 || !name.equals(args[0])) {
                return;
            }
            if (id == NotificationCenter.fileLoaded) {
                finish(args.length > 1 && args[1] instanceof File
                        ? (File) args[1]
                        : FileLoader.getInstance(account).getPathToAttach(remote.document, true));
            } else if (id == NotificationCenter.fileLoadFailed) {
                finish(null);
            }
        }

        private void finish(File file) {
            if (finished) {
                return;
            }
            finished = true;
            release();
            reply.onReply(file);
        }

        private void release() {
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (observing) {
                observing = false;
                final NotificationCenter center = NotificationCenter.getInstance(account);
                center.removeObserver(this, NotificationCenter.fileLoaded);
                center.removeObserver(this, NotificationCenter.fileLoadFailed);
            }
        }
    }
}
