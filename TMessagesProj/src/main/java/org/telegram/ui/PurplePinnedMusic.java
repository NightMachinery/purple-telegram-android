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
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

final class PurplePinnedMusic {
    private static final int PAGE_SIZE = 100;
    private static final int ALBUM_WINDOW = 100;

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
            Job job = new Job(chat, account, dialogId, topicId,
                    topicId == 0 ? mergeDialogId : 0, before.getValue(), after.getValue(), albums.isChecked());
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

    private static final class Job {
        private final WeakReference<ChatActivity> chat;
        private final int account;
        private final long dialogId;
        private final long topicId;
        private final long mergeDialogId;
        private final int before;
        private final int after;
        private final boolean albums;
        private final ArrayList<Anchor> anchors = new ArrayList<>();
        private final Set<String> queued = new HashSet<>();
        private long pinnedDialogId;
        private int lastPinnedOffset;
        private int anchorIndex;
        private boolean finished;

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
            loadPinned(0);
        }

        private TLRPC.InputPeer peer(long id) {
            return MessagesController.getInstance(account).getInputPeer(id);
        }

        private void request(TLObject request, Response callback) {
            ConnectionsManager connection = ConnectionsManager.getInstance(account);
            boolean[] completed = { false };
            int[] requestId = { 0 };
            Runnable timeout = () -> {
                if (completed[0] || finished) {
                    return;
                }
                completed[0] = true;
                connection.cancelRequest(requestId[0], false);
                finish(R.string.PurplePinnedMusicFailed);
            };
            requestId[0] = connection.sendRequest(request, (response, error) ->
                    AndroidUtilities.runOnUIThread(() -> {
                        if (completed[0] || finished) {
                            return;
                        }
                        completed[0] = true;
                        AndroidUtilities.cancelRunOnUIThread(timeout);
                        if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                            finish(R.string.PurplePinnedMusicFailed);
                        } else {
                            callback.accept((TLRPC.messages_Messages) response);
                        }
                    }));
            AndroidUtilities.runOnUIThread(timeout, 60000);
        }

        private interface Response {
            void accept(TLRPC.messages_Messages response);
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
                            if (music(message)) {
                                anchors.add(new Anchor(pinnedDialogId, message));
                            }
                        }
                        int next = response.messages.isEmpty() ? 0 :
                                response.messages.get(response.messages.size() - 1).id;
                        if (response.messages.size() == PAGE_SIZE && next != 0 && next != lastPinnedOffset) {
                            lastPinnedOffset = next;
                            loadPinned(next);
                        } else if (pinnedDialogId == dialogId && mergeDialogId != 0) {
                            pinnedDialogId = mergeDialogId;
                            lastPinnedOffset = 0;
                            loadPinned(0);
                        } else if (anchors.isEmpty()) {
                            finish(R.string.PurplePinnedMusicNone);
                        } else {
                            nextAnchor();
                        }
                    });
        }

        private boolean music(TLRPC.Message message) {
            return new MessageObject(account, message, false, false).isMusic();
        }

        private void queue(long peerId, TLRPC.Message message) {
            if (!music(message)) {
                return;
            }
            TLRPC.Document document = MessageObject.getDocument(message);
            if (document == null || !queued.add(peerId + ":" + message.id)) {
                return;
            }
            FileLoader.getInstance(account).loadFile(document, message,
                    FileLoader.PRIORITY_NORMAL, ImageLoader.CACHE_TYPE_CACHE);
        }

        private void nextAnchor() {
            if (anchorIndex == anchors.size()) {
                finish(R.string.PurplePinnedMusicQueued);
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

        private void finish(int stringId) {
            if (finished) {
                return;
            }
            finished = true;
            ChatActivity fragment = chat.get();
            if (fragment != null && !fragment.isFinished && fragment.getParentActivity() != null) {
                String text = stringId == R.string.PurplePinnedMusicQueued
                        ? fragment.getParentActivity().getString(stringId, queued.size())
                        : fragment.getParentActivity().getString(stringId);
                Toast.makeText(fragment.getParentActivity(), text, Toast.LENGTH_LONG).show();
            }
        }
    }
}
