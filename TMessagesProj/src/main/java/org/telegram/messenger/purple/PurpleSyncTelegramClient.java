/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
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
    private final PurpleAccountFiles.Identity origin;
    private PurpleSyncChatIO io;
    private java.util.function.BooleanSupplier scopeCurrent=()->true;

    private PurpleSyncTelegramClient(int account, long userId) {
        this.account = account;
        this.userId = userId;
        origin = PurpleAccountFiles.capture(account);
    }

    static PurpleSyncClient create(int account, long userId) {
        PurpleSyncTelegramClient client = new PurpleSyncTelegramClient(account, userId);
        PurpleSyncChatOrigin facts = client.origin != null && client.origin.userId == userId
            ? new PurpleSyncChatOrigin(account,client.origin.key.startsWith("1-") ? 1 : 0,userId,java.util.UUID.randomUUID().toString()) : null;
        if(facts!=null)client.io=new PurpleSyncChatIO(facts,PurpleSyncChatCore.Peer.self(),
                new PurpleSyncChatFacts.MappedPeer(facts,PurpleSyncChatCore.Peer.self(),PurpleSyncChatCore.Kind.Self,
                        PurpleSyncChatCore.Truth.Unknown,PurpleSyncChatCore.Truth.Unknown,null,null));
        return owned(client);
    }

    static PurpleSyncClient create(PurpleSyncChatClient.Info info,java.util.function.BooleanSupplier current) {
        if(info==null || !info.readable())throw new IllegalArgumentException("UnreadableChat");
        PurpleSyncTelegramClient client=new PurpleSyncTelegramClient(info.origin.slot,info.origin.userId);
        client.io=new PurpleSyncChatIO(info.origin,info.chat,info.peer);
        client.scopeCurrent=current;
        return owned(client);
    }

    private static PurpleSyncClient owned(PurpleSyncTelegramClient client) {
        return new PurpleSyncChatRecordClient(client,message -> client.isActive() && message.remote instanceof Remote
                && client.contains(message) ? client.io.ownership(((Remote)message.remote).message) : PurpleSyncChatCore.Ownership.Unknown);
    }

    private TLRPC.InputPeer input() {
        return io!=null ? PurpleSyncChatTelegram.platform(io.origin,this::isActive).input(io.chat) : null;
    }

    @Override public PurpleSyncChatCore.Peer chat() { return io!=null?io.chat:PurpleSyncChatCore.Peer.self(); }
    @Override public PurpleSyncChatOrigin origin() { return io!=null?io.origin:null; }

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
    public boolean hasQueuedSync() { return PurpleSendReceipts.hasModernWork(account)||PurpleSyncAutoEdits.busy(account); }
    @Override
    public long userId() {
        return userId;
    }

    private boolean loginCurrent() {
        return origin!=null && origin.userId==userId && origin.current()
                && io!=null && io.origin.environment==(origin.key.startsWith("1-")?1:0);
    }

    @Override
    public boolean isActive() {
        return io != null && scopeCurrent.getAsBoolean() && userId > 0 && origin != null
                && io.origin.environment==(origin.key.startsWith("1-")?1:0) && origin.userId == userId && origin.current()
                && PurpleAccountOwner.modernOperation(account)
                && PurpleAccountBinding.isSameActiveUser(account, userId);
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
        if (!isActive()) { reply.onReply(History.failed("AccountUnavailable")); return 0; }
        final TLRPC.TL_messages_getHistory request = PurpleSyncChatIO.history(chat(),input(),offsetId,limit);
        if(request==null){reply.onReply(History.failed("ChatUnavailable"));return 0;}
        return ConnectionsManager.getInstance(account).sendRequest(request,
                (response, error) -> {
                    History history;
                    try {
                        history = history(response, error);
                    } catch (RuntimeException e) {
                        FileLog.e(e);
                        history = History.failed("UnreadableResponse");
                    }
                    reply.onReply(isActive()?history:History.failed("AccountUnavailable"));
                });
    }

    @Override
    public int requestMessage(int id, Reply<Lookup> reply) {
        if (!isActive()) { reply.onReply(Lookup.failed("AccountUnavailable")); return 0; }
        final TLObject request=PurpleSyncChatRequests.exact(chat(),id,input());
        if(request==null){reply.onReply(Lookup.failed("ChatUnavailable"));return 0;}
        return ConnectionsManager.getInstance(account).sendRequest(request,
                (response, error) -> {
                    Lookup lookup;
                    try {
                        lookup = lookup(response, error, id);
                    } catch (RuntimeException e) {
                        FileLog.e(e);
                        lookup = Lookup.failed("UnreadableResponse");
                    }
                    reply.onReply(isActive()?lookup:Lookup.failed("AccountUnavailable"));
                });
    }

    @Override
    public void cancelRequest(int token) {
        ConnectionsManager.getInstance(account).cancelRequest(token, true);
    }

    @Override
    public Object download(Message message, Reply<File> reply) {
        if (!isActive() || !contains(message) || !(message.remote instanceof Remote)
                || !io.matches(((Remote)message.remote).message)
                || io.ownership(((Remote)message.remote).message)!=PurpleSyncChatCore.Ownership.Own) { reply.onReply(null); return null; }
        final Download download = new Download((Remote) message.remote, reply);
        AndroidUtilities.runOnUIThread(download::start);
        return download;
    }

    @Override
    public void cancelDownload(Object download) {
        if (download != null) AndroidUtilities.runOnUIThread(((Download) download)::stop);
    }

    @Override
    public void localCopies(Reply<List<LocalCopy>> reply) {
        if (!isActive()) { reply.onReply(java.util.Collections.emptyList()); return; }
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(
                () -> reply.onReply(isActive() ? queryLocalCopies(storage) : java.util.Collections.emptyList()));
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
        PurpleSyncTransport.PostAuthority authority=receipt.authority();
        if(!isActive() || !PurpleSyncChatCore.Peer.self().equals(chat()) || authority==null
                || !authority.current() || !receipt.current() || !PurpleSyncPost.CAPTION.equals(caption)
                || !PurpleSyncPost.MIME.equals(mime)){receipt.onReceipt(0,true);return;}
        runOnWorker(()->{
            byte[] bytes=PurpleSyncReader.readAtMost(file,PurpleSyncPost.MAX_STAGED_BYTES+1);
            if(!java.util.Arrays.equals(bytes,receipt.expectedBytes())){receipt.onReceipt(0,true);return;}
            uploadModern(0,bytes,null,()->receipt.current() && authority.current()
                    && java.util.Arrays.equals(bytes,PurpleSyncReader.readAtMost(file,PurpleSyncPost.MAX_STAGED_BYTES+1)),
                    authority,result->receipt.onReceipt(result.id,result.id<=0));
        });
    }

    @Override
    public void edit(int id, File file, byte[] expected, java.util.function.BooleanSupplier current,
            Reply<PurpleSyncAutoCore.Outcome> reply) {
        reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
    }

    @Override public void edit(int id,File file,byte[] expected,java.util.function.BooleanSupplier current,
            PurpleSyncTransport.PostAuthority authority,Reply<PurpleSyncAutoCore.Outcome> reply) {
        if(id<=0 || !isActive() || !PurpleSyncChatCore.Peer.self().equals(chat()) || authority==null || !authority.current()){
            reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown);return;
        }
        runOnWorker(() -> uploadModern(id, PurpleSyncReader.readAtMost(file, PurpleSyncPost.MAX_STAGED_BYTES + 1),
            expected, ()->current.getAsBoolean() && authority.current(),authority,
            result -> reply.onReply(result.id > 0 ? PurpleSyncAutoCore.Outcome.Sent
                : result.error != null && (result.error.equals("MESSAGE_EDIT_TIME_EXPIRED")
                    || result.error.equals("MESSAGE_AUTHOR_REQUIRED") || result.error.equals("MESSAGE_ID_INVALID")
                    || result.error.equals("MEDIA_NEW_INVALID")) ? PurpleSyncAutoCore.Outcome.EditRefused
                    : PurpleSyncAutoCore.Outcome.OutcomeUnknown)));
    }

    private void uploadModern(int messageId, byte[] bytes, byte[] previous,
        java.util.function.BooleanSupplier guard,PurpleSyncTransport.PostAuthority authority,Reply<PurpleSyncAutoUpload.Result> reply) {
        PurpleSyncAutoUpload[] operation = new PurpleSyncAutoUpload[1];
        PurpleSyncAutoUpload.Port port = new PurpleSyncAutoUpload.Port() {
            public void worker(Runnable task) { runOnWorker(task); }
            public void cancel(int token) { cancelRequest(token); }
            public void beforePost(Reply<Boolean> done) {
                if(!operation[0].live() || authority==null || !authority.current()){done.onReply(false);return;}
                authority.check(ready->done.onReply(Boolean.TRUE.equals(ready) && operation[0].live() && authority.current()));
            }
            public int upload(long id, byte[] data, Reply<Boolean> callback) {
                if (!operation[0].live()) { callback.onReply(false); return 0; }
                try {
                    TLRPC.TL_upload_saveFilePart request = new TLRPC.TL_upload_saveFilePart();
                    request.file_id = id; request.file_part = 0;
                    request.bytes = new NativeByteBuffer(data.length); request.bytes.writeBytes(data);
                    return ConnectionsManager.getInstance(account).sendRequest(request,
                        (response, error) -> callback.onReply(error == null && response instanceof TLRPC.TL_boolTrue),
                        ConnectionsManager.RequestFlagFailOnServerErrors, ConnectionsManager.ConnectionTypeUpload);
                } catch (Exception e) { callback.onReply(false); return 0; }
            }
            public void exact(int id, Reply<byte[]> callback) {
                new PurpleSyncAutoNetwork(owned(PurpleSyncTelegramClient.this), "", PurpleSyncTelegramClient.this::isActive)
                    .exact(id, operation[0]::live, row -> callback.onReply(row.optInt("presence") == 1 && row.optBoolean("owned")
                        ? PurpleSyncAutoCore.decode(row.optString("record")) : null));
            }
            public int send(int id, TLRPC.TL_inputFile file, Reply<PurpleSyncAutoUpload.Result> callback) {
                if (!operation[0].live()) { callback.onReply(new PurpleSyncAutoUpload.Result(0, "OutcomeUnknown")); return 0; }
                final long randomId = PurpleSyncAutoUpload.randomId();
                TLObject request=PurpleSyncChatUpload.request(chat(),input(),id,file,randomId);
                if(request==null){callback.onReply(new PurpleSyncAutoUpload.Result(0,"ChatUnavailable"));return 0;}
                return ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
                    int resultId=error==null?PurpleSyncChatUpload.receipt(io,response,randomId,id)
                            : id>0 && "MESSAGE_NOT_MODIFIED".equals(error.text)?id:0;
                    if(error==null && response instanceof TLRPC.Updates && loginCurrent()){
                        org.telegram.messenger.Utilities.stageQueue.postRunnable(()->{
                            if(loginCurrent())org.telegram.messenger.MessagesController.getInstance(account)
                                    .processUpdates((TLRPC.Updates)response,false);
                        });
                    }
                    callback.onReply(new PurpleSyncAutoUpload.Result(resultId, resultId>0 ? null : error!=null?error.text:"OutcomeUnknown"));
                }, ConnectionsManager.RequestFlagFailOnServerErrors);
            }
        };
        operation[0] = new PurpleSyncAutoUpload(account, messageId, bytes, previous,
            () -> isActive() && guard.getAsBoolean(), port, reply);
        operation[0].start();
    }
    @Override
    public void deleteOwn(int id,java.util.function.BooleanSupplier current,Reply<PurpleSyncAutoCore.Outcome> reply){
        runOnMain(()->{
            if(!PurpleSyncChatCore.Peer.self().equals(chat())){reply.onReply(PurpleSyncAutoCore.Outcome.DeletionRefused);return;}
            if(id<=0||!isActive()||!current.getAsBoolean()){reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown);return;}
            TLRPC.TL_messages_deleteMessages request=new TLRPC.TL_messages_deleteMessages();request.id.add(id);request.revoke=true;
            ConnectionsManager.getInstance(account).sendRequest(request,(response,error)->{
                if(!isActive()||!current.getAsBoolean()){reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown);return;}
                reply.onReply(error==null&&response instanceof TLRPC.TL_messages_affectedMessages?PurpleSyncAutoCore.Outcome.Deleted
                    :error!=null&&("MESSAGE_DELETE_FORBIDDEN".equals(error.text)||"CHAT_ADMIN_REQUIRED".equals(error.text))?PurpleSyncAutoCore.Outcome.DeletionRefused:PurpleSyncAutoCore.Outcome.OutcomeUnknown);
            }, ConnectionsManager.RequestFlagFailOnServerErrors);
        });
    }

    private History history(TLObject response, TLRPC.TL_error error) {
        return io.history(response,error,this::meta);
    }

    private Lookup lookup(TLObject response, TLRPC.TL_error error, int expectedId) {
        return io.lookup(response,error,expectedId,this::meta);
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
                PurpleSyncChatFacts.token(message.peer_id,userId),
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
                    dialogId()));
            while (cursor.next()) {
                final NativeByteBuffer data = cursor.byteBufferValue(3);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message;
                try {
                    message = TLRPC.Message.TLdeserialize(
                            data, data.readInt32(false), false);
                    if (message != null) message.readAttachPath(data, userId);
                } catch (RuntimeException e) {
                    FileLog.e(e);
                    message = null;
                } finally {
                    data.reuse();
                }
                if (message == null) {
                    continue;
                }
                if (PurpleSyncAutoOutbox.staged(message)) continue;
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
        private final Runnable timeout = () -> {
            cancelLoad();
            finish(null);
        };
        private boolean observing;
        private boolean ownsLoad;
        private boolean finished;

        Download(Remote remote, Reply<File> reply) {
            this.remote = remote;
            this.reply = reply;
            this.name = FileLoader.getAttachFileName(remote.document);
        }

        void start() {
            if (!isActive()) { finish(null); return; }
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
                ownsLoad = !loader.isLoadingFile(name);
                loader.loadFile(remote.document,
                        new MessageObject(account, remote.message, false, false),
                        FileLoader.PRIORITY_NORMAL_UP, ImageLoader.CACHE_TYPE_CACHE);
            } catch (RuntimeException e) {
                FileLog.e(e);
                finish(null);
            }
        }

        void stop() {
            if (!finished) {
                cancelLoad();
            }
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
            reply.onReply(isActive()?file:null);
        }

        private void cancelLoad() {
            if (ownsLoad) {
                ownsLoad = false;
                FileLoader.getInstance(account).cancelLoadFile(remote.document);
            }
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
