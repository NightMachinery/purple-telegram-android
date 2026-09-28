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
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class PurplePinnedMusic {
    private static final int PAGE_SIZE = 100;
    private static final int ALBUM_WINDOW = 100;
    private static final int MAX_ACTIVE = 2;
    private static final int MAX_AUTO_RETRIES = 3;
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
                    if (!transfer.fileName.startsWith("invalid:")) {
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

    static void addListener(int account, long dialogId, long topicId, Listener listener) {
        String key = key(account, dialogId, topicId);
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

        final TLRPC.Document document;
        final TLRPC.Message message;
        final String fileName;
        int state = QUEUED;
        int attempts;
        long nextAttemptMs;

        Transfer(TLRPC.Document document, TLRPC.Message message, String fileName) {
            this.document = document;
            this.message = message;
            this.fileName = fileName;
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
        private final Map<String, Transfer> transfers = new HashMap<>();
        private final Runnable wake = this::pump;
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

        private boolean cached(Transfer transfer) {
            File file = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), transfer.fileName);
            return file.isFile() && file.length() > 0
                    && (transfer.document.size <= 0 || file.length() == transfer.document.size);
        }

        private void queue(long peerId, TLRPC.Message message) {
            if (!music(message) || !selectedMessages.add(peerId + ":" + message.id)) {
                return;
            }
            TLRPC.Document document = MessageObject.getDocument(message);
            if (document == null) {
                Transfer invalid = new Transfer(null, message, "invalid:" + peerId + ":" + message.id);
                invalid.state = Transfer.FAILED;
                transfers.put(invalid.fileName, invalid);
                emit();
                return;
            }
            String fileName = FileLoader.getAttachFileName(document);
            if (fileName == null || fileName.contains(String.valueOf(Integer.MIN_VALUE))
                    || fileName.startsWith("0_0")) {
                Transfer invalid = new Transfer(document, message, "invalid:" + peerId + ":" + message.id);
                invalid.state = Transfer.FAILED;
                transfers.put(invalid.fileName, invalid);
                emit();
                return;
            }
            if (transfers.containsKey(fileName)) {
                return;
            }
            Transfer transfer = new Transfer(document, message, fileName);
            transfer.state = cached(transfer) ? Transfer.COMPLETE : Transfer.QUEUED;
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
                if (transfer.state == Transfer.FAILED && !transfer.fileName.startsWith("invalid:")) {
                    transfer.state = Transfer.QUEUED;
                    transfer.attempts = 0;
                    transfer.nextAttemptMs = 0;
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

        private void retryLimit() {
            Cooldown cooldown = cooldown();
            long now = System.currentTimeMillis();
            if (now >= cooldown.untilMs) {
                cooldown.level = Math.min(cooldown.level + 1, RETRY_LIMIT_DELAYS.length);
                cooldown.untilMs = now + RETRY_LIMIT_DELAYS[cooldown.level - 1];
            }
        }

        private void failed(Transfer transfer, int reason) {
            if (reason == 2) {
                retryLimit();
            }
            if (transfer.attempts <= MAX_AUTO_RETRIES && reason != -1 && reason != 1) {
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
            if (paused) {
                return;
            }
            long now = System.currentTimeMillis();
            long nextWake = Long.MAX_VALUE;
            int active = activeTransfers(account);
            for (Transfer transfer : transfers.values()) {
                if (transfer.state != Transfer.QUEUED) {
                    continue;
                }
                long availableAt = Math.max(transfer.nextAttemptMs, cooldown().untilMs);
                if (active >= MAX_ACTIVE || availableAt > now) {
                    nextWake = Math.min(nextWake, availableAt);
                    continue;
                }
                if (cached(transfer)) {
                    transfer.state = Transfer.COMPLETE;
                    continue;
                }
                transfer.state = Transfer.ACTIVE;
                transfer.attempts++;
                active++;
                FileLoader.getInstance(account).loadFile(transfer.document, transfer.message,
                        FileLoader.PRIORITY_NORMAL, ImageLoader.CACHE_TYPE_CACHE);
            }
            if (nextWake > now && nextWake != Long.MAX_VALUE && active < MAX_ACTIVE) {
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
            if (transfer == null || transfer.state != Transfer.ACTIVE) {
                return;
            }
            if (id == NotificationCenter.fileLoaded) {
                if (cached(transfer)) {
                    transfer.state = Transfer.COMPLETE;
                    emit();
                    pumpAccount(account);
                } else {
                    failed(transfer, 0);
                }
            } else if (id == NotificationCenter.fileLoadFailed) {
                failed(transfer, (Integer) args[1]);
            } else if (id == NotificationCenter.fileLoadProgressChanged) {
                emit();
            }
        }
    }
}
