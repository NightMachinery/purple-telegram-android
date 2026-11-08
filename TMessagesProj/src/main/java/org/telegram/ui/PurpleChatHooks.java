/*
 * This is the source code of Purple Telegram for Android.
 *
 * Everything Purple adds to ChatActivity, behind one object per chat.
 *
 * ChatActivity is upstream's busiest file (27 of the 43 upstream commits
 * since 2025-12 touched it), so it keeps only one-line calls into this class:
 * the menu rows (Work Mode lists, pinned music, keep media, chat storage, Last
 * Seen Peek), the
 * pinned-music bar, Screen Time's open/close/input and its budget cover, the
 * translate-availability log line and the Local Premium sponsored-message
 * check. The calls are `purple.x()` on a field, so ChatActivity imports
 * nothing for them.
 *
 * This class sits in org.telegram.ui because the pinned-music classes are
 * package-private there and ChatActivity's protected currentUser, currentChat
 * and currentEncryptedChat are reachable from the same package. BaseFragment's
 * protected members are not (it is in org.telegram.ui.ActionBar), so this uses
 * getCurrentAccount(), getActionBar() and a supplier for isFinishing().
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.CacheByChatsController;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleLastSeen;
import org.telegram.messenger.purple.PurpleListMenu;
import org.telegram.messenger.purple.PurpleScreenTime;
import org.telegram.messenger.purple.PurpleSettingsMessage;
import org.telegram.messenger.purple.PurpleTranslate;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ChatActivityTopPanelLayout;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

final class PurpleChatHooks {

    static final int SAVE_TO_GALLERY = org.telegram.messenger.purple.PurpleMediaSave.GALLERY;
    static final int SAVE_TO_DOWNLOADS = org.telegram.messenger.purple.PurpleMediaSave.DOWNLOADS;
    static final int VIEW_EDIT_HISTORY = 0x70750330;
    static final int COPY_AS_MARKDOWN = PurpleMarkdownCopy.OPTION;
    static final int COPY_SELECTION_AS_MARKDOWN = 0x70750008;

    // Header menu rows, from Purple's id range (AGENTS.md, "How a hook looks").
    private static final int PEEK_LAST_SEEN = 0x70750001;
    private static final int DOWNLOAD_PINNED_MUSIC = 0x70750002;
    private static final int KEEP_MEDIA = 0x70750003;
    private static final int CHAT_STORAGE = 0x70750004;
    private static final int DELETED_MESSAGES = 0x70750400;
    private static final int WORK_MODE_LISTS = 0x7075000a;

    private final ChatActivity chat;
    private final PurpleScreenTimeCover.Host screenTime;
    private final PurpleMarkdownCopy markdownCopy;
    private final PurpleRoundRecording roundRecording;

    private PurplePinnedMusicBar pinnedMusicBar;
    private ChatActivityTopPanelLayout topPanel;
    private ActionBarMenuItem.Item keepMediaItem;
    private boolean resumed;
    @androidx.annotation.Keep
    private final org.telegram.messenger.purple.PurpleRecordingDock.Host recordingHost = new org.telegram.messenger.purple.PurpleRecordingDock.Host() {
        public long[] target() { return recordingTarget(); }
        public boolean scheduled() { return chat.getChatMode() == ChatActivity.MODE_SCHEDULED; }
        public void roundStarted() { roundRecording.started(); }
        public void roundFinishing() { roundRecording.beginFinalization(); }
        public boolean roundVisible() { return !chat.isPaused(); }
        public void roundPause() { roundRecording.pause(); }
        public void roundPreview() { roundRecording.preview(); }
        public void roundPreviewReady(Object[] args) { roundRecording.ready(args); }
        public void roundSend(boolean notify, int date, long stars) { roundRecording.send(notify, date, stars); }
        public void roundDiscard() { roundRecording.discard(); }
        public void roundAbandon() { roundRecording.abandon(); }
        public void roundRetarget(long dialog, MessageObject top) { roundRecording.retarget(dialog, top); }
        public void release() {
            if (chat.chatActivityEnterView != null) chat.chatActivityEnterView.purpleReleaseRecordUi();
        }
    };

    /**
     * @param finishing the chat's {@code isFinishing()}, which this class
     *     cannot call itself; see {@link PurpleScreenTimeCover.Host}.
     */
    PurpleChatHooks(ChatActivity chat, Utilities.Callback0Return<Boolean> finishing, PurpleMarkdownCopy.Lookup lookup) {
        this.chat = chat;
        roundRecording = new PurpleRoundRecording(chat);
        this.screenTime = new PurpleScreenTimeCover.Host(chat, finishing);
        markdownCopy = new PurpleMarkdownCopy(chat, lookup);
        // F10: a recording this chat starts is registered with its target.
        org.telegram.messenger.purple.PurpleRecordingRegistry.watchChat(chat.getClassGuid(), this::recordingTarget);
        org.telegram.messenger.purple.PurpleRecordingDock.watch(chat.getClassGuid(), recordingHost);
    }

    /**
     * {account, dialog, topic, reply, client user id, flags} of a recording
     * this chat starts, read when it starts and again when it stops, for the
     * Recording bin's "Send to the original chat". The flags carry the enter
     * view's view-once toggle.
     */
    private long[] recordingTarget() {
        final MessageObject reply = chat.getReplyMessage();
        final boolean once = chat.chatActivityEnterView != null && chat.chatActivityEnterView.voiceOnce;
        // getTopicId() is the thread id in saved sublists, quick replies and
        // suggestions too; only a forum topic's is a topic to send into.
        final boolean quickReply = chat.getChatMode() == ChatActivity.MODE_QUICK_REPLIES;
        return new long[]{chat.getCurrentAccount(), chat.getDialogId(), chat.isTopic ? chat.getTopicId() : 0, reply == null ? 0 : reply.getId(),
                org.telegram.messenger.UserConfig.getInstance(chat.getCurrentAccount()).getClientUserId(),
                (once ? org.telegram.messenger.purple.PurpleRecordingSessions.FLAG_ONCE : 0)
                        | (quickReply ? org.telegram.messenger.purple.PurpleRecordingSessions.FLAG_QUICK_REPLY : 0)};
    }

    View addSelectionCopy(org.telegram.ui.ActionBar.ActionBarMenu menu, org.telegram.ui.Components.AnimatedTextView count) {
        ActionBarMenuItem item = menu.addItemWithWidth(COPY_SELECTION_AS_MARKDOWN, R.drawable.formatting_code, dp(48), getString(R.string.PurpleCopyAsMarkdown));
        item.setContentDescription(getString(R.string.PurpleCopyAsMarkdown));
        PurpleSelectionActions.install(menu, count);
        return item;
    }

    void updateSelectionCopy(org.telegram.ui.ActionBar.ActionBarMenu menu, boolean visible) {
        menu.setItemVisibility(COPY_SELECTION_AS_MARKDOWN, visible ? View.VISIBLE : View.GONE);
    }

    void updateSelectionCopy(org.telegram.ui.ActionBar.ActionBarMenu menu, ActionBarMenuItem copy) {
        updateSelectionCopy(menu, PurpleSelectionActions.visible(menu, copy));
    }

    void copySelectionAsMarkdown(android.util.SparseArray<MessageObject>[] selected, boolean encrypted) {
        ArrayList<MessageObject> messages = new ArrayList<>();
        for (int index = selected.length - 1; index >= 0; index--) {
            ArrayList<Integer> ids = new ArrayList<>();
            for (int i = 0; i < selected[index].size(); i++) ids.add(selected[index].keyAt(i));
            if (encrypted) java.util.Collections.sort(ids, java.util.Collections.reverseOrder());
            else java.util.Collections.sort(ids);
            for (Integer id : ids) {
                MessageObject message = selected[index].get(id);
                if (PurpleMarkdownCopy.canCopy(message)) messages.add(message);
            }
        }
        markdownCopy.copySelection(messages);
    }

    boolean isSettings(MessageObject message) {
        return PurpleSettingsMessage.is(message);
    }

    boolean importSettings(MessageObject message) {
        return PurpleSettingsImport.importSettings(chat, message);
    }

    boolean importSettingsButton(MessageObject message, int type) {
        return PurpleSettingsImport.importButton(chat, message, type);
    }

    void relabelImport(MessageObject message, ArrayList<CharSequence> items, ArrayList<Integer> options, int optionId) {
        PurpleSettingsImport.relabel(message, items, options, optionId);
    }

    void addCopyOptions(MessageObject message, MessageObject.GroupedMessages group,
            ArrayList<CharSequence> items, ArrayList<Integer> options, ArrayList<Integer> icons, int copy) {
        PurpleMarkdownCopy.addMenuOptions(message, group, items, options, icons, copy);
        if (android.os.Build.VERSION.SDK_INT >= 23 && message != null && !message.scheduled && !message.isQuickReply() && (message.getId() > 0 || message.messageOwner.random_id != 0)) {
            items.add(getString(R.string.PurpleArchiveViewHistory)); options.add(VIEW_EDIT_HISTORY); icons.add(R.drawable.msg_edit);
        }
    }

    void addSaveOptions(MessageObject message, ArrayList<CharSequence> items,
            ArrayList<Integer> options, ArrayList<Integer> icons) {
        org.telegram.messenger.purple.PurpleMediaSave.addMenuOptions(message, items, options, icons);
    }

    void saveMedia(MessageObject message, int option) {
        org.telegram.messenger.purple.PurpleMediaSave.save(chat.getParentActivity(), message, option == SAVE_TO_GALLERY);
    }

    void viewEditHistory(MessageObject message) {
        if (message != null) chat.presentFragment(new PurpleEditHistoryActivity(chat.getCurrentAccount(), message.messageOwner));
    }

    void copyAsMarkdown(MessageObject message, MessageObject.GroupedMessages group) {
        markdownCopy.copy(message, group);
    }

    static boolean canCopyMessage(MessageObject message) {
        return PurpleMarkdownCopy.canCopy(message);
    }

    // ---- the header menu -----------------------------------------------------

    /**
     * Handles a tap on one of Purple's header menu rows. Returns false for
     * any other id, so upstream's dispatch goes on.
     */
    boolean onMenuItem(int id) {
        if (id == WORK_MODE_LISTS) {
            PurpleListBox.show(chat, chat.getCurrentAccount(), chat.getDialogId());
        } else if (id == PEEK_LAST_SEEN) {
            final TLRPC.User user = lastSeenPeekUser();
            if (user != null) {
                PurpleLastSeenTrade.show(chat, chat.getCurrentAccount(), user.id);
            }
        } else if (id == DOWNLOAD_PINNED_MUSIC) {
            PurplePinnedMusic.show(chat, chat.getCurrentAccount(), chat.getDialogId(),
                    chat.getTopicId(), chat.getMergeDialogId());
        } else if (id == KEEP_MEDIA) {
            showKeepMedia();
        } else if (id == CHAT_STORAGE) {
            openChatStorage();
        } else if (id == DELETED_MESSAGES) {
            openDeletedMessages();
        } else {
            return false;
        }
        return true;
    }

    /**
     * The header menu is about to open: the lists row follows the running
     * preset, the peek row the user's current status and the keep-media row
     * the current setting.
     */
    void onShowSubMenu() {
        final ActionBarMenuItem headerItem = chat.getHeaderItem();
        if (headerItem.hasSubItem(WORK_MODE_LISTS)) {
            headerItem.setSubItemShown(WORK_MODE_LISTS, PurpleListMenu.available());
        }
        if (headerItem.hasSubItem(PEEK_LAST_SEEN)) {
            headerItem.setSubItemShown(
                    PEEK_LAST_SEEN, PurpleLastSeen.peekEligible(chat.getCurrentAccount(), lastSeenPeekUser()));
        }
        updateKeepMediaItem();
    }

    /**
     * The rows at the top of the header menu: the Work Mode lists box (the one
     * the chat list's menus open, shown when PurpleListMenu offers it), download
     * pinned music (not in a secret chat), and keep media and chat storage
     * where the chat has a keep-media bucket.
     */
    void addHeaderItems(ActionBarMenuItem headerItem) {
        if (chat.getChatMode() == ChatActivity.MODE_DEFAULT) {
            PurpleGate.ensureLoaded();
            headerItem.lazilyAddSubItem(WORK_MODE_LISTS, R.drawable.msg_customize, getString(R.string.PurpleLists));
            headerItem.setSubItemShown(WORK_MODE_LISTS, PurpleListMenu.available());
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 && chat.getChatMode() == ChatActivity.MODE_DEFAULT && (!chat.isThreadChat() || chat.isTopic))
            headerItem.lazilyAddSubItem(DELETED_MESSAGES,R.drawable.msg_delete,getString(R.string.PurpleArchiveDeletedMessages));
        if (chat.currentEncryptedChat == null) {
            headerItem.lazilyAddSubItem(DOWNLOAD_PINNED_MUSIC, R.drawable.msg_download,
                    getString(R.string.PurplePinnedMusicAction));
        }
        final int keepMediaType = keepMediaType();
        if (keepMediaType >= 0) {
            keepMediaItem = headerItem.lazilyAddSubItem(KEEP_MEDIA,
                    R.drawable.msg_autodelete, keepMediaLabel(keepMediaType));
            headerItem.lazilyAddSubItem(CHAT_STORAGE, R.drawable.msg2_data,
                    getString(R.string.PurpleChatStorage));
        }
    }

    /** The Last Seen Peek row, above Call in a user chat's header menu. */
    void addPeekItem(ActionBarMenuItem headerItem) {
        headerItem.lazilyAddSubItem(PEEK_LAST_SEEN, R.drawable.msg_view_file,
                getString(R.string.PurplePeekAction));
        headerItem.setSubItemShown(
                PEEK_LAST_SEEN, PurpleLastSeen.peekEligible(chat.getCurrentAccount(), lastSeenPeekUser()));
    }

    /**
     * The chat's user as the messages controller has it now, since the copy
     * the chat opened with can carry a stale status.
     */
    private TLRPC.User lastSeenPeekUser() {
        final TLRPC.User user = chat.currentUser;
        if (user == null) {
            return null;
        }
        final TLRPC.User canonical = chat.getMessagesController().getUser(user.id);
        return canonical != null ? canonical : user;
    }

    // ---- the pinned-music bar ------------------------------------------------

    /**
     * Binds the pinned-music bar in the chat's top panel, called from
     * {@code updateTopPanel}. The bar is made again when the panel is a new
     * one, which happens when the chat's views are rebuilt.
     */
    void bindPinnedMusicBar(ChatActivityTopPanelLayout topPanelLayout, boolean animated) {
        topPanel = topPanelLayout;
        final ActionBarMenuItem headerItem = chat.getHeaderItem();
        if (headerItem == null || !headerItem.hasSubItem(DOWNLOAD_PINNED_MUSIC) || chat.getContext() == null) {
            return;
        }
        if (pinnedMusicBar == null || pinnedMusicBar.getParent() != topPanelLayout) {
            if (pinnedMusicBar != null) {
                pinnedMusicBar.unbind();
            }
            final PurplePinnedMusicBar bar = new PurplePinnedMusicBar(chat.getContext(), chat.themeDelegate);
            topPanelLayout.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            topPanelLayout.setPriority(bar, 9);
            topPanelLayout.setDebugName(bar, "purple pinned music");
            bar.setOnShownChanged((shown, shownAnimated) -> {
                if (topPanel != null && bar.getParent() == topPanel) {
                    topPanel.setViewVisible(bar, shown, shownAnimated);
                }
            });
            bar.setOnOpenList(this::openPinnedMusicList);
            pinnedMusicBar = bar;
        }
        pinnedMusicBar.bind(chat.getCurrentAccount(), chat.getDialogId(), chat.getTopicId(), animated);
    }

    void updateColors() {
        if (pinnedMusicBar != null) {
            pinnedMusicBar.updateColors();
        }
    }

    private void openPinnedMusicList() {
        final long topicId = chat.getTopicId();
        chat.presentFragment(new PurplePinnedMusicList(chat, chat.getDialogId(), topicId,
                topicId == 0 ? chat.getMergeDialogId() : 0));
    }

    // ---- keep media and chat storage -----------------------------------------

    private ArchiveScopeWatcher archiveScope;
    private static final class ArchiveScopeWatcher implements org.telegram.messenger.NotificationCenter.NotificationCenterDelegate {
        final java.lang.ref.WeakReference<ChatActivity> owner;
        final org.telegram.messenger.purple.PurpleArchiveQueries.Scope opening;
        final org.telegram.messenger.NotificationCenter notifications;
        final java.util.concurrent.atomic.AtomicBoolean permitted = new java.util.concurrent.atomic.AtomicBoolean(true);
        final int[] events = {org.telegram.messenger.NotificationCenter.updateInterfaces, org.telegram.messenger.NotificationCenter.chatInfoDidLoad,
            org.telegram.messenger.NotificationCenter.userInfoDidLoad, org.telegram.messenger.NotificationCenter.encryptedChatUpdated,
            org.telegram.messenger.NotificationCenter.closeChats};
        Runnable revoked;
        boolean closed;
        ArchiveScopeWatcher(ChatActivity chat, org.telegram.messenger.purple.PurpleArchiveQueries.Scope scope) {
            owner = new java.lang.ref.WeakReference<>(chat); opening = scope;
            notifications = org.telegram.messenger.NotificationCenter.getInstance(chat.getCurrentAccount());
            for (int event : events) notifications.addObserver(this,event);
        }
        void invalidate() { if (permitted.compareAndSet(true,false) && revoked != null) revoked.run(); }
        void verify() {
            if (closed) return;
            ChatActivity current = owner.get();
            if (current == null || current.isFinished) { invalidate(); return; }
            try {
                if (!opening.samePurgeScope(org.telegram.messenger.purple.PurpleArchiveQueries.Scope.capture(current.getCurrentAccount(), current.getDialogId(),
                    current.getCurrentUser(), current.getCurrentChat(), current.getCurrentEncryptedChat(), current.getCurrentChatInfo(), 0))) invalidate();
            } catch (Exception | LinkageError e) { invalidate(); }
        }
        @Override public void didReceivedNotification(int id, int account, Object... args) { verify(); }
        void close() {
            if (closed) return; closed = true; invalidate();
            for (int event : events) notifications.removeObserver(this,event);
            revoked = null;
        }
    }
    private void openDeletedMessages() {
        try {
            org.telegram.messenger.purple.PurpleArchiveQueries.Scope scope = org.telegram.messenger.purple.PurpleArchiveQueries.Scope.capture(chat.getCurrentAccount(),chat.getDialogId(),
                chat.getCurrentUser(),chat.getCurrentChat(),chat.getCurrentEncryptedChat(),chat.getCurrentChatInfo(),chat.isTopic ? chat.getTopicId() : 0);
            if (archiveScope != null) archiveScope.close();
            ArchiveScopeWatcher watcher = new ArchiveScopeWatcher(chat,scope); archiveScope = watcher;
            org.telegram.messenger.purple.PurpleArchiveQueries.Scope guarded = scope.withAuthority(watcher.permitted::get);
            PurpleDeletedMessagesActivity deleted = new PurpleDeletedMessagesActivity(chat.getCurrentAccount(),guarded,watcher::close);
            java.lang.ref.WeakReference<PurpleDeletedMessagesActivity> view = new java.lang.ref.WeakReference<>(deleted);
            watcher.revoked = () -> { PurpleDeletedMessagesActivity current = view.get(); if (current != null && !current.isFinished) current.scopeRevoked(); };
            watcher.verify();
            if (!chat.presentFragment(deleted)) watcher.close();
        } catch (Exception | LinkageError e) {
            if (archiveScope != null) archiveScope.close();
            PurpleWriterUI.explain(chat,getString(R.string.PurpleArchiveDeletedMessages),getString(R.string.PurpleArchiveQueryUnavailable),"file_unreadable",null,null);
        }
    }

    private void openChatStorage() {
        if (keepMediaType() < 0) {
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("dialog_id", chat.getDialogId());
        args.putLong("merge_dialog_id", chat.getMergeDialogId());
        chat.presentFragment(new CacheControlActivity(args));
    }

    /**
     * The keep-media bucket this chat belongs to, or -1 where the rows do not
     * apply: secret chats, scheduled and saved views, and threads other than
     * forum topics.
     */
    private int keepMediaType() {
        final long dialogId = chat.getDialogId();
        if (chat.getChatMode() != ChatActivity.MODE_DEFAULT || chat.currentEncryptedChat != null
                || dialogId == 0 || chat.isThreadChat() && !chat.isTopic) {
            return -1;
        }
        if (dialogId > 0) {
            return chat.currentUser != null ? CacheByChatsController.KEEP_MEDIA_TYPE_USER : -1;
        }
        if (chat.currentChat == null) {
            return -1;
        }
        return ChatObject.isChannel(chat.currentChat) ? CacheByChatsController.KEEP_MEDIA_TYPE_CHANNEL
                : CacheByChatsController.KEEP_MEDIA_TYPE_GROUP;
    }

    private String keepMediaLabel(int type) {
        final CacheByChatsController controller = chat.getMessagesController().getCacheByChatsController();
        final CacheByChatsController.KeepMediaException exception =
                controller.getKeepMediaExceptionsByDialogs().get(chat.getDialogId());
        final int keepMedia = exception != null ? exception.keepMedia : controller.getKeepMedia(type);
        final String duration = CacheByChatsController.getDaysInSeconds(keepMedia) == Long.MAX_VALUE
                ? getString(R.string.KeepMediaForever)
                : CacheByChatsController.getKeepMediaString(keepMedia);
        return formatString(exception != null ? R.string.PurpleKeepMediaThisChat : R.string.PurpleKeepMediaDefault,
                duration);
    }

    private void updateKeepMediaItem() {
        final int type = keepMediaType();
        if (keepMediaItem != null && type >= 0) {
            keepMediaItem.setText(keepMediaLabel(type));
        }
    }

    private void showKeepMedia() {
        final int type = keepMediaType();
        final ActionBarMenuItem headerItem = chat.getHeaderItem();
        if (type < 0 || headerItem == null || chat.getParentActivity() == null) {
            return;
        }
        final boolean hasException = chat.getMessagesController().getCacheByChatsController()
                .getKeepMediaExceptionsByDialogs().get(chat.getDialogId()) != null;
        final KeepMediaPopupView layout = new KeepMediaPopupView(chat, chat.getParentActivity());
        layout.updateForDialog(!hasException);
        layout.setCallback((ignored, keepMedia) -> setKeepMedia(type, keepMedia));
        layout.measure(View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST));
        final ActionBarPopupWindow window = AlertsCreator.createSimplePopup(chat, layout, headerItem,
                headerItem.getWidth() - layout.getMeasuredWidth() / 2f, layout.getMeasuredHeight() / 2f);
        if (window != null) {
            layout.setParentWindow(window);
        }
    }

    /**
     * Gives this chat its own keep-media period, or drops its exception. The
     * exception is kept in the chat's own bucket only, and removed from the
     * other two, so a chat that changed kind does not end up in two of them.
     */
    private void setKeepMedia(int type, int keepMedia) {
        final long dialogId = chat.getDialogId();
        final CacheByChatsController controller = chat.getMessagesController().getCacheByChatsController();
        final boolean keep = keepMedia != CacheByChatsController.KEEP_MEDIA_DELETE;
        for (int bucket = CacheByChatsController.KEEP_MEDIA_TYPE_USER;
                bucket <= CacheByChatsController.KEEP_MEDIA_TYPE_CHANNEL; bucket++) {
            final ArrayList<CacheByChatsController.KeepMediaException> exceptions =
                    controller.getKeepMediaExceptions(bucket);
            boolean changed = false;
            boolean kept = false;
            for (int i = exceptions.size() - 1; i >= 0; i--) {
                final CacheByChatsController.KeepMediaException exception = exceptions.get(i);
                if (exception.dialogId != dialogId) {
                    continue;
                }
                if (bucket == type && keep && !kept) {
                    exception.keepMedia = keepMedia;
                    kept = true;
                } else {
                    exceptions.remove(i);
                }
                changed = true;
            }
            if (bucket == type && keep && !kept) {
                exceptions.add(new CacheByChatsController.KeepMediaException(dialogId, keepMedia));
                changed = true;
            }
            if (changed) {
                controller.saveKeepMediaExceptions(bucket, exceptions);
            }
        }
        updateKeepMediaItem();
    }

    // ---- Screen Time and the lifecycle ---------------------------------------

    /**
     * The chat came to the front: Screen Time's Open and budget check, and on
     * the first resume the translate-availability log line.
     */
    void onResume() {
        org.telegram.messenger.purple.PurpleRecordingDock.foreground();
        if (archiveScope != null) archiveScope.verify();
        PurplePeekControls.chatResumed(chat);
        screenTime.onResume();
        if (!resumed) {
            resumed = true;
            PurpleTranslate.logAvailability(chat.getCurrentAccount(), chat.getDialogId());
        }
    }

    boolean roundCameraVisible(boolean open, boolean fromPaused) {
        return org.telegram.ui.Components.PurpleRoundBubble.animation(chat.instantCameraView, open, fromPaused);
    }

    boolean roundRecordingTouch(android.view.MotionEvent event) {
        return org.telegram.ui.Components.PurpleRoundBubble.background(chat.instantCameraView, event);
    }

    void attachRoundCamera(org.telegram.ui.Components.InstantCameraViewBase view, org.telegram.ui.Components.PurpleRoundBubble.Visibility visibility) {
        org.telegram.ui.Components.PurpleRoundBubble.attach(view, small -> { visibility.small(small); roundRecording.resized(small); });
    }

    boolean dockRecording(boolean invoked) {
        roundRecording.beforeLeave();
        return invoked && org.telegram.messenger.purple.PurpleRecordingDock.dock(chat.getCurrentAccount(), chat.getClassGuid());
    }

    void onPause() {
        roundRecording.beforeLeave();
        org.telegram.ui.Components.PurpleRoundBubble.pause(chat.instantCameraView);
        markdownCopy.cancel();
        screenTime.onPause();
    }

    boolean sendRoundMedia(org.telegram.messenger.MediaController.PhotoEntry entry, org.telegram.messenger.VideoEditedInfo info, boolean notify, int date, int repeat, long stars) {
        return roundRecording.sendMedia(entry, info, notify, date, repeat, stars);
    }

    void onDestroy() {
        roundRecording.destroy();
        org.telegram.messenger.purple.PurpleRecordingDock.unwatch(chat.getClassGuid());
        org.telegram.messenger.purple.PurpleRecordingRegistry.unwatchChat(chat.getClassGuid());
        if (archiveScope != null) { archiveScope.close(); archiveScope = null; }
        PurplePeekControls.chatDestroyed(chat);
        markdownCopy.onDestroy();
        if (pinnedMusicBar != null) {
            pinnedMusicBar.unbind();
        }
    }

    /**
     * A touch or a scroll in the chat. Input is what "idle" is the absence of,
     * and a long read with no touch would otherwise look like an idle one. It
     * is not written down - a line per MotionEvent would drown the log - and
     * only resets the watchdog; see {@link PurpleScreenTime#input()}.
     */
    void input() {
        PurpleScreenTime.input();
    }

    /**
     * Whether a round that just started playing off the visible part of the
     * list goes to the floating round instead of the chat scrolling to it.
     *
     * It is asked only at playback start: the block it sits in runs only for
     * {@code updateTextureViewPosition(true, needScroll)}, which only
     * {@code messagePlayingDidStart} calls. A reply-quote, search or
     * scroll-to-bottom jump while a round plays never reaches it; that stays
     * upstream's (it floats the round only if the visible part is measured
     * again after the jump's scroll animation), and is on the device checklist.
     *
     * Upstream floats a starting round only when your finger moved the list
     * ({@code checkTextureViewPosition}) or a PiP already exists. With
     * {@code needScroll} (the queue advanced to a round within five messages)
     * it otherwise scrolls the chat to the round, and without it leaves the
     * round playing with its frame off screen. This term floats it in both
     * cases, except that an automatic advance still follows the queue while
     * you are reading it: when a message within those five is on screen.
     * Scrolled away (for example while a voice message played), the next round
     * plays in the PiP and the chat stays where you are. Ordinary videos are
     * not rounds and keep upstream's choice: the floating view is round-only.
     *
     * It is the one F1 hook that widens an existing line rather than adding
     * one: the PiP-or-scroll choice is a single if/else-if with no place for an
     * early return. See README "Media playback" and docs/media-design.md, F1.
     */
    boolean roundLeavesToPip(MessageObject message, boolean needScroll) {
        return org.telegram.messenger.purple.PurpleRoundPip.leavesToPip(
                message.isRoundVideo(), needScroll, needScroll && queueOnScreen(message));
    }

    /** Whether a message within upstream's five-message follow range of {@code message} is on screen. */
    private boolean queueOnScreen(MessageObject message) {
        final int index = chat.messages.indexOf(message);
        final org.telegram.ui.Components.RecyclerListView list = chat.getChatListView();
        if (index < 0 || list == null) return false;
        for (int i = 0; i < list.getChildCount(); i++) {
            final View child = list.getChildAt(i);
            if (!(child instanceof org.telegram.ui.Cells.ChatMessageCell)) continue;
            final MessageObject shown = ((org.telegram.ui.Cells.ChatMessageCell) child).getMessageObject();
            final int other = shown == null ? -1 : chat.messages.indexOf(shown);
            if (other >= 0 && Math.abs(other - index) < 5) return true;
        }
        return false;
    }

    java.util.ArrayList<MessageObject> voicePlaylist(MessageObject start, boolean unread, long mergeDialog) {
        return org.telegram.messenger.purple.PurpleVoicePlayback.playlist(start, chat.messages,
                org.telegram.messenger.DialogObject.isEncryptedDialog(start.getDialogId()), unread, mergeDialog);
    }

    /**
     * Whether {@code createVoiceMessagesPlaylist} leaves a message out of the
     * queue a manual play on {@code start} builds: your own messages, when the
     * play started on the other person's message in a one-to-one chat. See
     * {@link org.telegram.messenger.purple.PurpleVoiceQueue}.
     */
    boolean skipsInVoicePlaylist(MessageObject start, MessageObject candidate) {
        return org.telegram.messenger.purple.PurpleVoiceQueue.skipsInPlaylist(start, candidate);
    }


}
