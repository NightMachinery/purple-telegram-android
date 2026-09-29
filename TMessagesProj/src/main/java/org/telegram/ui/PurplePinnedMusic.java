package org.telegram.ui;

import android.app.Activity;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FilePathDatabase;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class PurplePinnedMusic {
    private static final int PAGE_SIZE = 100;
    private static final int ALBUM_WINDOW = 100;
    private static final int MAX_ACTIVE = 2;
    private static final int MAX_AUTO_RETRIES = 3;
    private static final long STALLED_CHECK_MS = 120000;
    private static final long STALLED_VISIBLE_MS = 300000;
    private static final long VERIFY_FILES_MS = 30000;
    private static final long[] RETRY_LIMIT_DELAYS = { 30000, 60000, 120000 };
    private static final Map<String, Job> jobs = new HashMap<>();
    private static final Map<Integer, Cooldown> cooldowns = new HashMap<>();
    private static final Map<String, ArrayList<WeakReference<Listener>>> listeners = new HashMap<>();

    interface Listener {
        void onPinnedMusicChanged(Snapshot snapshot);
    }

    static final class Snapshot {
        final boolean scanning;
        final boolean scanFailed;
        final boolean finished;
        final boolean paused;
        final boolean retrying;
        final int pinned;
        final int selected;
        final int completed;
        final int active;
        final int failed;
        final int retryable;
        final long cooldownUntilMs;

        Snapshot(Job job) {
            scanning = job.scanning;
            scanFailed = job.scanFailed;
            paused = job.paused;
            pinned = job.anchors.size();
            selected = job.transfers.size();
            int done = 0;
            int running = 0;
            int errors = 0;
            int canRetry = 0;
            boolean waitingToRetry = false;
            for (Transfer transfer : job.transfers.values()) {
                if (transfer.state == Transfer.COMPLETE) {
                    done++;
                } else if (transfer.state == Transfer.ACTIVE) {
                    running++;
                } else if (transfer.state == Transfer.FAILED) {
                    errors++;
                    if (transfer.retryable()) {
                        canRetry++;
                    }
                } else if (transfer.attempts > 0) {
                    waitingToRetry = true;
                }
            }
            completed = done;
            active = running;
            failed = errors;
            retryable = canRetry;
            cooldownUntilMs = job.cooldown().untilMs;
            retrying = waitingToRetry || running == 0 && cooldownUntilMs > System.currentTimeMillis()
                    && selected > done + errors;
            finished = !scanning && running == 0 && selected == done + errors;
        }
    }

    private static final class Cooldown {
        long untilMs;
        int level;
    }

    private static String key(int account, long dialogId, long topicId) {
        return account + ":" + dialogId + ":" + topicId;
    }

    static Snapshot getSnapshot(int account, long dialogId, long topicId) {
        Job job = jobs.get(key(account, dialogId, topicId));
        return job == null ? null : new Snapshot(job);
    }

    static ArrayList<Item> getItems(int account, long dialogId, long topicId) {
        Job job = jobs.get(key(account, dialogId, topicId));
        if (job == null) {
            return new ArrayList<>();
        }
        ArrayList<Item> result = new ArrayList<>(job.transfers.size());
        for (Transfer transfer : job.transfers.values()) {
            result.add(new Item(transfer));
        }
        return result;
    }

    static void retryItem(int account, long dialogId, long topicId, String fileName) {
        Job job = jobs.get(key(account, dialogId, topicId));
        if (job != null) {
            job.retryItem(fileName);
        }
    }

    static final class Item {
        final String fileName;
        final long peerId;
        final int messageId;
        final TLRPC.Message message;
        final String title;
        final String artist;
        final int state;
        final long downloaded;
        final long total;
        final int failureReason;
        final boolean retryable;
        final boolean waiting;
        final int attempts;
        final boolean promoting;

        Item(Transfer transfer) {
            fileName = transfer.fileName;
            peerId = transfer.peerId;
            messageId = transfer.message.id;
            message = transfer.message;
            title = transfer.document == null ? LocaleController.getString(R.string.AudioUnknownTitle)
                    : MessageObject.getMusicTitle(transfer.document, true);
            artist = transfer.document == null ? null
                    : MessageObject.getMusicAuthor(transfer.document, false);
            state = transfer.state;
            downloaded = transfer.downloaded;
            total = transfer.total;
            failureReason = transfer.failureReason;
            retryable = transfer.retryable();
            attempts = transfer.attempts;
            promoting = transfer.promoting;
            waiting = state == Transfer.ACTIVE
                    && System.currentTimeMillis() - transfer.lastActivityMs >= STALLED_VISIBLE_MS;
        }
    }

    static void addListener(int account, long dialogId, long topicId, Listener listener) {
        String key = key(account, dialogId, topicId);
        Job job = jobs.get(key);
        if (job != null && job.verifyCompleted(true)) {
            job.pump();
        }
        ArrayList<WeakReference<Listener>> subscribers = listeners.computeIfAbsent(key,
                ignored -> new ArrayList<>());
        for (WeakReference<Listener> reference : subscribers) {
            if (reference.get() == listener) {
                listener.onPinnedMusicChanged(getSnapshot(account, dialogId, topicId));
                return;
            }
        }
        subscribers.add(new WeakReference<>(listener));
        listener.onPinnedMusicChanged(getSnapshot(account, dialogId, topicId));
    }

    static void removeListener(int account, long dialogId, long topicId, Listener listener) {
        String key = key(account, dialogId, topicId);
        ArrayList<WeakReference<Listener>> subscribers = listeners.get(key);
        if (subscribers != null) {
            subscribers.removeIf(reference -> reference.get() == null || reference.get() == listener);
            if (subscribers.isEmpty()) {
                listeners.remove(key);
            }
        }
    }

    private static void notifyListeners(String key, Snapshot snapshot) {
        ArrayList<WeakReference<Listener>> subscribers = listeners.get(key);
        if (subscribers == null) {
            return;
        }
        subscribers.removeIf(reference -> reference.get() == null);
        for (WeakReference<Listener> reference : new ArrayList<>(subscribers)) {
            Listener listener = reference.get();
            if (listener != null) {
                listener.onPinnedMusicChanged(snapshot);
            }
        }
        if (subscribers.isEmpty() && listeners.get(key) == subscribers) {
            listeners.remove(key);
        }
    }

    private static int activeTransfers(int account) {
        int active = 0;
        for (Job job : jobs.values()) {
            if (job.account != account) {
                continue;
            }
            for (Transfer transfer : job.transfers.values()) {
                if (transfer.state == Transfer.ACTIVE) {
                    active++;
                }
            }
        }
        return active;
    }

    private static void pumpAccount(int account) {
        for (Job job : new ArrayList<>(jobs.values())) {
            if (job.account == account) {
                job.pump();
            }
        }
    }

    static void retryFailed(int account, long dialogId, long topicId) {
        Job job = jobs.get(key(account, dialogId, topicId));
        if (job != null) {
            job.retryFailed();
        }
    }

    static void setPaused(int account, long dialogId, long topicId, boolean paused) {
        Job job = jobs.get(key(account, dialogId, topicId));
        if (job != null) {
            job.paused = paused;
            job.pump();
            job.emit();
        }
    }

    static void dismiss(int account, long dialogId, long topicId) {
        String key = key(account, dialogId, topicId);
        Job job = jobs.get(key);
        if (job != null && new Snapshot(job).finished) {
            jobs.remove(key);
            job.close();
        }
    }

    static void show(ChatActivity chat, int account, long dialogId, long topicId, long mergeDialogId) {
        Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        NumberPicker before = picker(activity, layout, R.string.PurplePinnedMusicBefore);
        NumberPicker after = picker(activity, layout, R.string.PurplePinnedMusicAfter);
        CheckBox albums = new CheckBox(activity);
        albums.setText(R.string.PurplePinnedMusicAlbum);
        albums.setChecked(true);
        layout.addView(albums);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, chat.getResourceProvider());
        builder.setTitle(activity.getString(R.string.PurplePinnedMusicAction));
        builder.setView(layout);
        builder.setNegativeButton(activity.getString(R.string.Cancel), null);
        builder.setPositiveButton(activity.getString(R.string.PurplePinnedMusicDownload), (ignored, which) -> {
            before.clearFocus();
            after.clearFocus();
            String key = key(account, dialogId, topicId);
            Job existing = jobs.get(key);
            if (existing != null) {
                if (!new Snapshot(existing).finished) {
                    Toast.makeText(activity, R.string.PurplePinnedMusicAlreadyRunning, Toast.LENGTH_SHORT).show();
                    return;
                }
                existing.close();
            }
            Job job = new Job(chat, account, dialogId, topicId,
                    topicId == 0 ? mergeDialogId : 0, before.getValue(), after.getValue(), albums.isChecked());
            jobs.put(key, job);
            job.start();
        });
        chat.showDialog(builder.create());
    }

    private static NumberPicker picker(Activity activity, LinearLayout layout, int label) {
        TextView title = new TextView(activity);
        title.setText(label);
        title.setGravity(Gravity.CENTER_VERTICAL);
        layout.addView(title);
        NumberPicker picker = new NumberPicker(activity);
        picker.setMinValue(0);
        picker.setMaxValue(20);
        picker.setValue(1);
        layout.addView(picker);
        return picker;
    }

    private static final class Anchor {
        final long dialogId;
        final TLRPC.Message message;

        Anchor(long dialogId, TLRPC.Message message) {
            this.dialogId = dialogId;
            this.message = message;
        }
    }

    private static final class Transfer {
        static final int QUEUED = 0;
        static final int ACTIVE = 1;
        static final int COMPLETE = 2;
        static final int FAILED = 3;
        static final int CACHE_ONLY = 4;

        final TLRPC.Document document;
        final TLRPC.Message message;
        final String fileName;
        final long peerId;
        int state = QUEUED;
        int attempts;
        long nextAttemptMs;
        long lastActivityMs;
        long downloaded;
        long total;
        int failureReason;
        File verifiedFile;
        boolean promoting;
        boolean finalizing;

        Transfer(TLRPC.Document document, TLRPC.Message message, String fileName, long peerId) {
            this.document = document;
            this.message = message;
            this.fileName = fileName;
            this.peerId = peerId;
        }

        boolean retryable() {
            return state == FAILED && !fileName.startsWith("invalid:");
        }
    }

    private static final class Job implements NotificationCenter.NotificationCenterDelegate {
        private final WeakReference<ChatActivity> chat;
        private final int account;
        private final long dialogId;
        private final long topicId;
        private final long mergeDialogId;
        private final int before;
        private final int after;
        private final boolean albums;
        private final ArrayList<Anchor> anchors = new ArrayList<>();
        private final Set<String> pinnedIds = new HashSet<>();
        private final Set<String> selectedMessages = new HashSet<>();
        private final Map<String, Transfer> transfers = new LinkedHashMap<>();
        private final Runnable wake = this::pump;
        private long lastFileVerificationMs;
        private long pinnedDialogId;
        private int anchorIndex;
        private boolean scanning = true;
        private boolean scanFailed;
        private boolean paused;
        private boolean completionToastShown;
        private boolean scanFailureToastShown;
        private Runnable failedRequest;

        Job(ChatActivity chat, int account, long dialogId, long topicId, long mergeDialogId,
                int before, int after, boolean albums) {
            this.chat = new WeakReference<>(chat);
            this.account = account;
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.mergeDialogId = mergeDialogId;
            this.before = before;
            this.after = after;
            this.albums = albums;
            pinnedDialogId = dialogId;
        }

        void start() {
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(this, NotificationCenter.fileLoaded);
            center.addObserver(this, NotificationCenter.fileLoadFailed);
            center.addObserver(this, NotificationCenter.fileLoadProgressChanged);
            emit();
            loadPinned(0);
        }

        void close() {
            AndroidUtilities.cancelRunOnUIThread(wake);
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.removeObserver(this, NotificationCenter.fileLoaded);
            center.removeObserver(this, NotificationCenter.fileLoadFailed);
            center.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
            notifyListeners(key(account, dialogId, topicId), null);
        }

        private Cooldown cooldown() {
            Cooldown cooldown = cooldowns.get(account);
            if (cooldown == null) {
                cooldown = new Cooldown();
                cooldowns.put(account, cooldown);
            }
            return cooldown;
        }

        private void emit() {
            verifyCompleted(false);
            Snapshot snapshot = new Snapshot(this);
            notifyListeners(key(account, dialogId, topicId), snapshot);
            if (scanFailed) {
                if (!scanFailureToastShown) {
                    scanFailureToastShown = true;
                    ChatActivity fragment = chat.get();
                    if (fragment != null && !fragment.isFinished && fragment.getParentActivity() != null) {
                        Toast.makeText(fragment.getParentActivity(),
                                R.string.PurplePinnedMusicFailed, Toast.LENGTH_LONG).show();
                    }
                }
                return;
            }
            if (snapshot.finished && !completionToastShown) {
                completionToastShown = true;
                ChatActivity fragment = chat.get();
                if (fragment != null && !fragment.isFinished && fragment.getParentActivity() != null) {
                    String text = anchors.isEmpty()
                            ? fragment.getParentActivity().getString(R.string.PurplePinnedMusicNone)
                            : fragment.getParentActivity().getString(
                                    R.string.PurplePinnedMusicCompleted, snapshot.completed, snapshot.selected);
                    Toast.makeText(fragment.getParentActivity(), text, Toast.LENGTH_LONG).show();
                }
            }
        }

        private TLRPC.InputPeer peer(long id) {
            return MessagesController.getInstance(account).getInputPeer(id);
        }

        private interface Response {
            void accept(TLRPC.messages_Messages response);
        }

        private void request(TLObject request, Response callback) {
            requestAttempt(request, callback, 0);
        }

        private void requestAttempt(TLObject request, Response callback, int attempt) {
            ConnectionsManager connection = ConnectionsManager.getInstance(account);
            boolean[] completed = { false };
            int[] requestId = { 0 };
            Runnable failed = () -> {
                if (attempt < 2) {
                    AndroidUtilities.runOnUIThread(() -> requestAttempt(request, callback, attempt + 1),
                            attempt == 0 ? 1000 : 3000);
                } else {
                    failedRequest = () -> request(request, callback);
                    scanFailed = true;
                    scanning = false;
                    emit();
                    pump();
                }
            };
            Runnable timeout = () -> {
                if (completed[0]) {
                    return;
                }
                completed[0] = true;
                connection.cancelRequest(requestId[0], false);
                failed.run();
            };
            requestId[0] = connection.sendRequest(request, (response, error) ->
                    AndroidUtilities.runOnUIThread(() -> {
                        if (completed[0]) {
                            return;
                        }
                        completed[0] = true;
                        AndroidUtilities.cancelRunOnUIThread(timeout);
                        if (error != null || !(response instanceof TLRPC.messages_Messages)
                                || response instanceof TLRPC.TL_messages_messagesNotModified) {
                            failed.run();
                        } else {
                            callback.accept((TLRPC.messages_Messages) response);
                        }
                    }));
            AndroidUtilities.runOnUIThread(timeout, 60000);
        }

        private void search(long peerId, TLRPC.MessagesFilter filter, int offsetId,
                int addOffset, int limit, Response callback) {
            TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
            request.peer = peer(peerId);
            request.q = "";
            request.filter = filter;
            request.offset_id = offsetId;
            request.add_offset = addOffset;
            request.limit = limit;
            if (topicId != 0 && peerId == dialogId) {
                request.flags |= 2;
                request.top_msg_id = (int) topicId;
            }
            request(request, callback);
        }

        private void loadPinned(int offsetId) {
            search(pinnedDialogId, new TLRPC.TL_inputMessagesFilterPinned(), offsetId, 0,
                    PAGE_SIZE, response -> {
                        for (TLRPC.Message message : response.messages) {
                            String id = pinnedDialogId + ":" + message.id;
                            if (music(message) && pinnedIds.add(id)) {
                                anchors.add(new Anchor(pinnedDialogId, message));
                            }
                        }
                        emit();
                        if (!response.messages.isEmpty()) {
                            int next = response.messages.get(response.messages.size() - 1).id;
                            if (next <= 0 || offsetId != 0 && next >= offsetId) {
                                scanFailed = true;
                                scanning = false;
                                failedRequest = () -> loadPinned(offsetId);
                                emit();
                                return;
                            }
                            loadPinned(next);
                        } else if (pinnedDialogId == dialogId && mergeDialogId != 0) {
                            pinnedDialogId = mergeDialogId;
                            loadPinned(0);
                        } else if (anchors.isEmpty()) {
                            scanning = false;
                            emit();
                        } else {
                            nextAnchor();
                        }
                    });
        }

        private boolean music(TLRPC.Message message) {
            return new MessageObject(account, message, false, false).isMusic();
        }

        private int mediaType(Transfer transfer) {
            if (MessageObject.isVoiceDocument(transfer.document)) {
                return FileLoader.MEDIA_DIR_AUDIO;
            }
            return MessageObject.isVideoDocument(transfer.document)
                    ? FileLoader.MEDIA_DIR_VIDEO : FileLoader.MEDIA_DIR_DOCUMENT;
        }

        private File cacheFile(Transfer transfer) {
            return new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), transfer.fileName);
        }

        private File mediaFile(Transfer transfer) {
            return new File(FileLoader.getDirectory(mediaType(transfer)), transfer.fileName);
        }

        private File recordedFile(Transfer transfer) {
            String path = FileLoader.getInstance(account).getFileDatabase().getPath(
                    transfer.document.id, transfer.document.dc_id, mediaType(transfer), true);
            return path == null ? null : new File(path);
        }

        private boolean validFile(Transfer transfer, File file) {
            return file != null && file.isFile() && file.length() > 0
                    && !file.getName().endsWith(".temp") && !file.getName().endsWith(".temp.enc")
                    && (transfer.document.size <= 0 || file.length() == transfer.document.size);
        }

        private boolean expectedFile(Transfer transfer, File file) {
            return validFile(transfer, file)
                    && (file.equals(mediaFile(transfer)) || file.equals(recordedFile(transfer)));
        }

        private boolean usable(Transfer transfer) {
            File recorded = recordedFile(transfer);
            File target = recorded != null ? recorded : mediaFile(transfer);
            if (validFile(transfer, transfer.verifiedFile)
                    && (transfer.verifiedFile.equals(target)
                            || transfer.verifiedFile.equals(mediaFile(transfer)))) {
                return true;
            }
            if (validFile(transfer, target)) {
                transfer.verifiedFile = target;
                return true;
            }
            transfer.verifiedFile = null;
            return false;
        }

        private boolean cacheOnly(Transfer transfer) {
            return validFile(transfer, cacheFile(transfer));
        }

        private void saveMetadata(Transfer transfer, File file) {
            FilePathDatabase.FileMeta metadata = new FilePathDatabase.FileMeta();
            metadata.dialogId = transfer.peerId;
            metadata.messageId = transfer.message.id;
            metadata.messageType = MessageObject.TYPE_MUSIC;
            metadata.messageSize = transfer.document.size;
            FileLoader.getInstance(account).getFileDatabase().saveFileDialogId(file, metadata);
        }

        private boolean verifyCompleted(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastFileVerificationMs < VERIFY_FILES_MS) {
                return false;
            }
            lastFileVerificationMs = now;
            boolean changed = false;
            for (Transfer transfer : transfers.values()) {
                if (transfer.state == Transfer.COMPLETE && !usable(transfer)) {
                    transfer.state = cacheOnly(transfer) ? Transfer.CACHE_ONLY : Transfer.FAILED;
                    transfer.failureReason = transfer.state == Transfer.FAILED ? 3 : 0;
                    changed = true;
                }
            }
            return changed;
        }

        private File promotionTarget(Transfer transfer) {
            File recorded = recordedFile(transfer);
            if (recorded != null && recorded.getParentFile() != null
                    && recorded.getParentFile().canWrite()) {
                return recorded;
            }
            return mediaFile(transfer);
        }

        private boolean promote(Transfer transfer, File source, File target) {
            if (validFile(transfer, target)) {
                return true;
            }
            if (target.exists() || !validFile(transfer, source)) {
                return false;
            }
            File directory = target.getParentFile();
            if (directory == null || !directory.isDirectory() && !directory.mkdirs()) {
                return false;
            }
            if (!target.exists() && source.renameTo(target)) {
                return validFile(transfer, target);
            }
            File temporary = new File(directory, target.getName() + ".purple-" + System.nanoTime() + ".tmp");
            try (FileInputStream input = new FileInputStream(source);
                    FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            } catch (IOException e) {
                temporary.delete();
                return false;
            }
            if (!validFile(transfer, temporary) || target.exists()
                    || !temporary.renameTo(target)) {
                temporary.delete();
                return false;
            }
            if (!validFile(transfer, target)) {
                return false;
            }
            source.delete();
            return true;
        }

        private void startPromotion(Transfer transfer, long now) {
            File source = cacheFile(transfer);
            File target = promotionTarget(transfer);
            transfer.state = Transfer.ACTIVE;
            transfer.promoting = true;
            transfer.lastActivityMs = now;
            Utilities.globalQueue.postRunnable(() -> {
                boolean moved = promote(transfer, source, target);
                AndroidUtilities.runOnUIThread(() -> {
                    if (jobs.get(key(account, dialogId, topicId)) != this
                            || transfer.state != Transfer.ACTIVE || !transfer.promoting) {
                        return;
                    }
                    if (!moved) {
                        transfer.promoting = false;
                        transfer.state = Transfer.FAILED;
                        transfer.failureReason = 6;
                        emit();
                        pumpAccount(account);
                        return;
                    }
                    completeWithMetadata(transfer, target, true);
                });
            });
        }

        private void completeWithMetadata(Transfer transfer, File file, boolean notifyFileLoaded) {
            transfer.finalizing = true;
            FilePathDatabase database = FileLoader.getInstance(account).getFileDatabase();
            File recorded = recordedFile(transfer);
            boolean updatePath = recorded != null && !recorded.equals(file);
            if (updatePath) {
                database.putPath(transfer.document.id, transfer.document.dc_id,
                        mediaType(transfer), 0, file.getAbsolutePath());
            }
            saveMetadata(transfer, file);
            database.afterPendingWrites(() -> AndroidUtilities.runOnUIThread(() -> {
                if (jobs.get(key(account, dialogId, topicId)) != this
                        || transfer.state != Transfer.ACTIVE) {
                    return;
                }
                transfer.finalizing = false;
                transfer.promoting = false;
                if (!validFile(transfer, file)) {
                    transfer.state = Transfer.FAILED;
                    transfer.failureReason = 3;
                } else {
                    transfer.state = Transfer.COMPLETE;
                    transfer.verifiedFile = file;
                    transfer.downloaded = transfer.document.size;
                    transfer.total = transfer.document.size;
                    if (notifyFileLoaded || updatePath) {
                        NotificationCenter.getInstance(account).postNotificationName(
                                NotificationCenter.fileLoaded, transfer.fileName, file);
                    }
                }
                emit();
                pumpAccount(account);
            }));
        }

        private void queue(long peerId, TLRPC.Message message) {
            if (!music(message) || !selectedMessages.add(peerId + ":" + message.id)) {
                return;
            }
            TLRPC.Document document = MessageObject.getDocument(message);
            if (document == null) {
                Transfer invalid = new Transfer(null, message, "invalid:" + peerId + ":" + message.id, peerId);
                invalid.state = Transfer.FAILED;
                invalid.failureReason = 4;
                transfers.put(invalid.fileName, invalid);
                emit();
                return;
            }
            String fileName = FileLoader.getAttachFileName(document);
            if (fileName == null || fileName.contains(String.valueOf(Integer.MIN_VALUE))
                    || fileName.startsWith("0_0")) {
                Transfer invalid = new Transfer(document, message, "invalid:" + peerId + ":" + message.id, peerId);
                invalid.state = Transfer.FAILED;
                invalid.failureReason = 4;
                transfers.put(invalid.fileName, invalid);
                emit();
                return;
            }
            if (transfers.containsKey(fileName)) {
                return;
            }
            Transfer transfer = new Transfer(document, message, fileName, peerId);
            transfer.state = usable(transfer) ? Transfer.COMPLETE
                    : cacheOnly(transfer) ? Transfer.CACHE_ONLY : Transfer.QUEUED;
            if (transfer.state == Transfer.COMPLETE) {
                saveMetadata(transfer, transfer.verifiedFile);
            }
            transfers.put(fileName, transfer);
            emit();
            pump();
        }

        private void nextAnchor() {
            if (anchorIndex == anchors.size()) {
                scanning = false;
                emit();
                pump();
                return;
            }
            Anchor anchor = anchors.get(anchorIndex++);
            queue(anchor.dialogId, anchor.message);
            if (before == 0) {
                loadAfter(anchor);
                return;
            }
            search(anchor.dialogId, new TLRPC.TL_inputMessagesFilterMusic(), anchor.message.id,
                    0, before, response -> {
                        for (TLRPC.Message message : response.messages) {
                            queue(anchor.dialogId, message);
                        }
                        loadAfter(anchor);
                    });
        }

        private void loadAfter(Anchor anchor) {
            if (after == 0) {
                loadAlbum(anchor);
                return;
            }
            search(anchor.dialogId, new TLRPC.TL_inputMessagesFilterMusic(), anchor.message.id + 1,
                    -after, after, response -> {
                        for (TLRPC.Message message : response.messages) {
                            queue(anchor.dialogId, message);
                        }
                        loadAlbum(anchor);
                    });
        }

        private void loadAlbum(Anchor anchor) {
            long groupId = anchor.message.grouped_id;
            if (!albums || groupId == 0) {
                nextAnchor();
                return;
            }
            TLObject request;
            if (topicId != 0) {
                TLRPC.TL_messages_getReplies replies = new TLRPC.TL_messages_getReplies();
                replies.peer = peer(anchor.dialogId);
                replies.msg_id = (int) topicId;
                replies.offset_id = anchor.message.id;
                replies.add_offset = -ALBUM_WINDOW / 2;
                replies.limit = ALBUM_WINDOW;
                request = replies;
            } else {
                TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
                history.peer = peer(anchor.dialogId);
                history.offset_id = anchor.message.id;
                history.add_offset = -ALBUM_WINDOW / 2;
                history.limit = ALBUM_WINDOW;
                request = history;
            }
            request(request, response -> {
                for (TLRPC.Message message : response.messages) {
                    if (message.grouped_id == groupId) {
                        queue(anchor.dialogId, message);
                    }
                }
                nextAnchor();
            });
        }

        private void retryFailed() {
            boolean retried = false;
            if (failedRequest != null) {
                Runnable retry = failedRequest;
                failedRequest = null;
                scanFailed = false;
                scanFailureToastShown = false;
                scanning = true;
                retry.run();
                retried = true;
            }
            for (Transfer transfer : transfers.values()) {
                if (transfer.retryable()) {
                    resetForRetry(transfer);
                    retried = true;
                }
            }
            if (!retried) {
                return;
            }
            completionToastShown = false;
            emit();
            pumpAccount(account);
        }

        private void retryItem(String fileName) {
            Transfer transfer = transfers.get(fileName);
            if (transfer == null || !transfer.retryable()) {
                return;
            }
            resetForRetry(transfer);
            completionToastShown = false;
            emit();
            pumpAccount(account);
        }

        private void resetForRetry(Transfer transfer) {
            transfer.state = Transfer.QUEUED;
            transfer.attempts = 0;
            transfer.nextAttemptMs = 0;
            transfer.failureReason = 0;
            transfer.downloaded = 0;
            transfer.total = 0;
        }

        private void retryLimit() {
            Cooldown cooldown = cooldown();
            long now = System.currentTimeMillis();
            if (now >= cooldown.untilMs) {
                cooldown.level = Math.min(cooldown.level + 1, RETRY_LIMIT_DELAYS.length);
                cooldown.untilMs = now + RETRY_LIMIT_DELAYS[cooldown.level - 1];
            }
        }

        private void failed(Transfer transfer, int reason) {
            transfer.failureReason = reason;
            if (reason == 2) {
                retryLimit();
            }
            if (transfer.attempts <= MAX_AUTO_RETRIES && reason != -1 && reason != 1 && reason != 6) {
                transfer.state = Transfer.QUEUED;
                transfer.nextAttemptMs = reason == 2 ? cooldown().untilMs
                        : System.currentTimeMillis() + 5000L * transfer.attempts;
            } else {
                transfer.state = Transfer.FAILED;
            }
            emit();
            pumpAccount(account);
        }

        private void pump() {
            AndroidUtilities.cancelRunOnUIThread(wake);
            long now = System.currentTimeMillis();
            for (Transfer transfer : transfers.values()) {
                if (transfer.state == Transfer.ACTIVE
                        && !transfer.promoting
                        && !transfer.finalizing
                        && now - transfer.lastActivityMs >= STALLED_CHECK_MS
                        && !FileLoader.getInstance(account).isLoadingFile(transfer.fileName)) {
                    failed(transfer, 5);
                    return;
                }
            }
            if (paused) {
                return;
            }
            long nextWake = Long.MAX_VALUE;
            int active = activeTransfers(account);
            for (Transfer transfer : transfers.values()) {
                if (transfer.state != Transfer.QUEUED && transfer.state != Transfer.CACHE_ONLY) {
                    continue;
                }
                long availableAt = Math.max(transfer.nextAttemptMs, cooldown().untilMs);
                if (active >= MAX_ACTIVE) {
                    continue;
                }
                if (availableAt > now) {
                    nextWake = Math.min(nextWake, availableAt);
                    continue;
                }
                if (usable(transfer)) {
                    transfer.state = Transfer.COMPLETE;
                    saveMetadata(transfer, transfer.verifiedFile);
                    continue;
                }
                if (cacheOnly(transfer) && !promotionTarget(transfer).exists()) {
                    if (FileLoader.getInstance(account).isLoadingFile(transfer.fileName)) {
                        nextWake = Math.min(nextWake, now + 5000);
                    } else {
                        startPromotion(transfer, now);
                        active++;
                    }
                    continue;
                }
                transfer.state = Transfer.ACTIVE;
                transfer.attempts++;
                transfer.lastActivityMs = now;
                transfer.failureReason = 0;
                active++;
                FileLoader.getInstance(account).loadFile(transfer.document,
                        new MessageObject(account, transfer.message, false, false),
                        FileLoader.PRIORITY_NORMAL, 0);
            }
            if (active > 0) {
                nextWake = Math.min(nextWake, now + STALLED_CHECK_MS);
            }
            if (nextWake > now && nextWake != Long.MAX_VALUE) {
                AndroidUtilities.runOnUIThread(wake, nextWake - now);
            }
            emit();
        }

        @Override
        public void didReceivedNotification(int id, int notificationAccount, Object... args) {
            if (notificationAccount != account) {
                return;
            }
            Transfer transfer = transfers.get(args[0]);
            if (transfer == null || transfer.state != Transfer.ACTIVE
                    || transfer.promoting || transfer.finalizing) {
                return;
            }
            if (id == NotificationCenter.fileLoaded) {
                File loaded = (File) args[1];
                boolean expected = expectedFile(transfer, loaded);
                if (expected || usable(transfer)) {
                    completeWithMetadata(transfer, expected ? loaded : transfer.verifiedFile, false);
                } else {
                    failed(transfer, 0);
                }
            } else if (id == NotificationCenter.fileLoadFailed) {
                failed(transfer, (Integer) args[1]);
            } else if (id == NotificationCenter.fileLoadProgressChanged) {
                transfer.downloaded = (Long) args[1];
                transfer.total = (Long) args[2];
                transfer.lastActivityMs = System.currentTimeMillis();
                emit();
            }
        }
    }
}
