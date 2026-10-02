/*
 * This is the source code of Purple Telegram for Android.
 *
 * Everything Purple adds to ChatActivity, behind one object per chat.
 *
 * ChatActivity is upstream's busiest file (27 of the 43 upstream commits
 * since 2025-12 touched it), so it keeps only one-line calls into this class:
 * the menu rows (pinned music, keep media, chat storage, Last Seen Peek), the
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
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleLastSeen;
import org.telegram.messenger.purple.PurpleScreenTime;
import org.telegram.messenger.purple.PurpleTranslate;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ChatActivityTopPanelLayout;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

final class PurpleChatHooks {

    // Header menu rows, from Purple's id range (AGENTS.md, "How a hook looks").
    private static final int PEEK_LAST_SEEN = 0x70750001;
    private static final int DOWNLOAD_PINNED_MUSIC = 0x70750002;
    private static final int KEEP_MEDIA = 0x70750003;
    private static final int CHAT_STORAGE = 0x70750004;

    private final ChatActivity chat;
    private final PurpleScreenTimeCover.Host screenTime;

    private PurplePinnedMusicBar pinnedMusicBar;
    private ChatActivityTopPanelLayout topPanel;
    private ActionBarMenuItem.Item keepMediaItem;
    private boolean resumed;

    /**
     * @param finishing the chat's {@code isFinishing()}, which this class
     *     cannot call itself; see {@link PurpleScreenTimeCover.Host}.
     */
    PurpleChatHooks(ChatActivity chat, Utilities.Callback0Return<Boolean> finishing) {
        this.chat = chat;
        this.screenTime = new PurpleScreenTimeCover.Host(chat, finishing);
    }

    // ---- the header menu -----------------------------------------------------

    /**
     * Handles a tap on one of Purple's header menu rows. Returns false for
     * any other id, so upstream's dispatch goes on.
     */
    boolean onMenuItem(int id) {
        if (id == PEEK_LAST_SEEN) {
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
        } else {
            return false;
        }
        return true;
    }

    /**
     * The header menu is about to open: the peek row follows the user's
     * current status and the keep-media row the current setting.
     */
    void onShowSubMenu() {
        final ActionBarMenuItem headerItem = chat.getHeaderItem();
        if (headerItem.hasSubItem(PEEK_LAST_SEEN)) {
            headerItem.setSubItemShown(
                    PEEK_LAST_SEEN, PurpleLastSeen.peekEligible(lastSeenPeekUser()));
        }
        updateKeepMediaItem();
    }

    /**
     * The rows at the top of the header menu: download pinned music (not in a
     * secret chat), and keep media and chat storage where the chat has a
     * keep-media bucket.
     */
    void addHeaderItems(ActionBarMenuItem headerItem) {
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
                PEEK_LAST_SEEN, PurpleLastSeen.peekEligible(lastSeenPeekUser()));
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
        screenTime.onResume();
        if (!resumed) {
            resumed = true;
            PurpleTranslate.logAvailability(chat.getCurrentAccount(), chat.getDialogId());
        }
    }

    void onPause() {
        screenTime.onPause();
    }

    void onDestroy() {
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

    // ---- sponsored messages --------------------------------------------------

    /**
     * Whether to skip asking for sponsored messages, called after upstream's
     * own checks have let the request through. With Local Premium on we
     * simply stop asking: the server hands these to whoever requests them,
     * with no Premium check on the request, so the client is the only thing
     * withholding them (docs/purple/premium.md in the desktop repository).
     *
     * Both outcomes are logged, because whether the request went out is not
     * otherwise visible from outside: the network log names no TL
     * constructors.
     */
    boolean withholdSponsored() {
        final long dialogId = chat.getDialogId();
        if (PurpleGate.localPremium()) {
            FileLog.d("Purple: sponsored for " + dialogId + ": not requested (local premium).");
            return true;
        }
        FileLog.d("Purple: sponsored for " + dialogId + ": requested.");
        return false;
    }
}
