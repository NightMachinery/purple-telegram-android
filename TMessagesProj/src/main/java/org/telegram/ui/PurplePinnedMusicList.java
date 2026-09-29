package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

final class PurplePinnedMusicList extends BaseFragment
        implements PurplePinnedMusic.Listener, NotificationCenter.NotificationCenterDelegate {
    private static final long REFRESH_DELAY_MS = 250;
    private static final int STATE_COMPLETE = 2;
    private static final int STATE_ACTIVE = 1;
    private static final int STATE_FAILED = 3;

    private final WeakReference<ChatActivity> chat;
    private final long dialogId;
    private final long topicId;
    private final long mergeDialogId;
    private final HashMap<String, String> searchText = new HashMap<>();
    private final HashMap<String, Long> stableIds = new HashMap<>();
    private final Runnable refresh = this::refresh;
    private PurplePinnedMusic.Snapshot snapshot;
    private ArrayList<PurplePinnedMusic.Item> allItems = new ArrayList<>();
    private ArrayList<PurplePinnedMusic.Item> items = new ArrayList<>();
    private boolean refreshScheduled;
    private String query = "";
    private RecyclerListView listView;
    private EmptyTextProgressView emptyView;
    private Adapter adapter;

    PurplePinnedMusicList(ChatActivity chat, long dialogId, long topicId, long mergeDialogId) {
        this.chat = new WeakReference<>(chat);
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.mergeDialogId = mergeDialogId;
        setCurrentAccount(chat.getCurrentAccount());
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter center = getNotificationCenter();
        center.addObserver(this, NotificationCenter.messagePlayingDidStart);
        center.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        center.addObserver(this, NotificationCenter.messagePlayingDidReset);
        PurplePinnedMusic.addListener(currentAccount, dialogId, topicId, this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(refresh);
        PurplePinnedMusic.removeListener(currentAccount, dialogId, topicId, this);
        NotificationCenter center = getNotificationCenter();
        center.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        center.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        center.removeObserver(this, NotificationCenter.messagePlayingDidReset);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PurplePinnedMusicFiles));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        ActionBarMenuItem searchItem = actionBar.createMenu().addItem(0, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchCollapse() {
                        setQuery("");
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        setQuery(editText.getText().toString());
                    }
                });
        searchItem.setSearchFieldHint(getString(R.string.PurplePinnedMusicListSearch));
        searchItem.setContentDescription(getString(R.string.Search));

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        emptyView = new EmptyTextProgressView(context);
        emptyView.showTextView();
        emptyView.setShowAtCenter(true);
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setItemAnimator(null);
        listView.setVerticalScrollBarEnabled(true);
        listView.setVerticalScrollbarPosition(LocaleController.isRTL
                ? RecyclerListView.SCROLLBAR_POSITION_LEFT : RecyclerListView.SCROLLBAR_POSITION_RIGHT);
        listView.setEmptyView(emptyView);
        listView.setAccessibilityEnabled(false);
        adapter = new Adapter(context);
        adapter.setHasStableIds(true);
        listView.setAdapter(adapter);
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && getParentActivity() != null) {
                    AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                }
            }
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frame;
        applyFilter();
        return fragmentView;
    }

    @Override
    public void onPinnedMusicChanged(PurplePinnedMusic.Snapshot snapshot) {
        boolean appeared = this.snapshot == null || snapshot == null;
        this.snapshot = snapshot;
        if (appeared) {
            refresh();
        } else if (!refreshScheduled) {
            refreshScheduled = true;
            AndroidUtilities.runOnUIThread(refresh, REFRESH_DELAY_MS);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (listView == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof Row) {
                ((Row) child).updatePlayback(true);
            }
        }
    }

    private void refresh() {
        refreshScheduled = false;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        allItems = snapshot == null ? new ArrayList<>()
                : PurplePinnedMusic.getItems(currentAccount, dialogId, topicId);
        applyFilter();
    }

    private void setQuery(String text) {
        String normalized = normalize(text.trim());
        if (normalized.equals(query)) {
            return;
        }
        query = normalized;
        applyFilter();
        if (listView != null) {
            listView.scrollToPosition(0);
        }
    }

    private void applyFilter() {
        if (query.isEmpty()) {
            items = allItems;
        } else {
            ArrayList<PurplePinnedMusic.Item> matches = new ArrayList<>();
            for (PurplePinnedMusic.Item item : allItems) {
                if (searchText(item).contains(query)) {
                    matches.add(item);
                }
            }
            items = matches;
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateHeader();
    }

    private void updateHeader() {
        if (actionBar == null || emptyView == null) {
            return;
        }
        if (snapshot == null) {
            actionBar.setSubtitle(null);
            emptyView.setText(getString(R.string.PurplePinnedMusicListGone));
            return;
        }
        actionBar.setSubtitle(formatString(R.string.PurplePinnedMusicBarProgress, snapshot.completed, snapshot.selected));
        if (!query.isEmpty()) {
            emptyView.setText(getString(R.string.PurplePinnedMusicListNoMatch));
        } else {
            emptyView.setText(getString(snapshot.scanning
                    ? R.string.PurplePinnedMusicBarSearching : R.string.PurplePinnedMusicBarNone));
        }
    }

    private static String key(PurplePinnedMusic.Item item) {
        return item.peerId + ":" + item.messageId;
    }

    private String searchText(PurplePinnedMusic.Item item) {
        String key = key(item);
        String text = searchText.get(key);
        if (text == null) {
            TLRPC.Document document = MessageObject.getDocument(item.message);
            String fileName = document == null ? null : FileLoader.getDocumentFileName(document);
            text = normalize(item.title + "\n" + (item.artist == null ? "" : item.artist)
                    + "\n" + (fileName == null ? "" : fileName));
            searchText.put(key, text);
        }
        return text;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('\u064a', '\u06cc').replace('\u0643', '\u06a9')
                .replace('\u200c', ' ');
    }

    private boolean isCurrent(PurplePinnedMusic.Item item, MessageObject playing) {
        return playing != null && playing.eventId == 0 && playing.getId() == item.messageId
                && playing.getDialogId() == item.peerId;
    }

    private void togglePlayback(PurplePinnedMusic.Item item) {
        MediaController controller = MediaController.getInstance();
        MessageObject playing = controller.getPlayingMessageObject();
        if (isCurrent(item, playing)) {
            if (controller.isMessagePaused()) {
                controller.playMessage(playing);
            } else {
                controller.pauseMessage(playing);
            }
            return;
        }
        File file = getFileLoader().getPathToMessage(item.message);
        if (file == null || !file.exists()) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PurplePinnedMusicListMissing)).show();
            return;
        }
        String selected = key(item);
        ArrayList<MessageObject> playlist = new ArrayList<>();
        MessageObject current = null;
        for (PurplePinnedMusic.Item candidate : items) {
            if (candidate.state != STATE_COMPLETE) {
                continue;
            }
            MessageObject messageObject = new MessageObject(currentAccount, candidate.message, false, false);
            playlist.add(messageObject);
            if (key(candidate).equals(selected)) {
                current = messageObject;
            }
        }
        if (current != null) {
            controller.setPlaylist(playlist, current, mergeDialogId, false, null);
        }
    }

    private void showInChat(PurplePinnedMusic.Item item) {
        ChatActivity target = chat.get();
        int loadIndex = item.peerId == dialogId ? 0 : item.peerId == mergeDialogId && mergeDialogId != 0 ? 1 : -1;
        if (target == null || target.isFinished || loadIndex < 0) {
            return;
        }
        finishFragment();
        target.scrollToMessageId(item.messageId, 0, true, loadIndex, true, 0);
    }

    private String status(PurplePinnedMusic.Item item) {
        switch (item.state) {
            case 0:
                return item.attempts > 0
                        ? getString(R.string.PurplePinnedMusicFileRetryQueued) + ": " + reason(item.failureReason)
                        : getString(R.string.PurplePinnedMusicFileQueued);
            case STATE_ACTIVE: {
                String text = getString(item.waiting ? R.string.PurplePinnedMusicFileWaiting
                        : item.promoting ? R.string.PurplePinnedMusicFilePromoting
                        : R.string.PurplePinnedMusicFileDownloading);
                return item.total > 0 && !item.promoting
                        ? text + " " + item.downloaded * 100 / item.total + "%" : text;
            }
            case STATE_COMPLETE: {
                TLRPC.Document document = MessageObject.getDocument(item.message);
                int seconds = document == null ? 0 : (int) MessageObject.getDocumentDuration(document);
                return seconds > 0 ? AndroidUtilities.formatShortDuration(seconds) : null;
            }
            case 4:
                return getString(R.string.PurplePinnedMusicFileCacheOnly);
            default:
                return reason(item.failureReason);
        }
    }

    private static String reason(int reason) {
        int string = reason == -1 ? R.string.PurplePinnedMusicFileNoSpace
                : reason == 1 ? R.string.PurplePinnedMusicFileCancelled
                : reason == 2 ? R.string.PurplePinnedMusicFileRateLimited
                : reason == 3 ? R.string.PurplePinnedMusicFileRemoved
                : reason == 4 ? R.string.PurplePinnedMusicFileInvalid
                : reason == 5 ? R.string.PurplePinnedMusicFileStopped
                : reason == 6 ? R.string.PurplePinnedMusicFilePromotionFailed
                : R.string.PurplePinnedMusicFileFailed;
        return getString(string);
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public long getItemId(int position) {
            String key = key(items.get(position));
            Long id = stableIds.get(key);
            if (id == null) {
                id = (long) stableIds.size();
                stableIds.put(key, id);
            }
            return id;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Row row = new Row(context);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(row);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((Row) holder.itemView).bind(items.get(position));
        }
    }

    private final class Row extends LinearLayout {
        private final StateView state;
        private final TextView title;
        private final TextView detail;
        private final TextView retry;
        private PurplePinnedMusic.Item item;

        Row(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(64));
            setBackground(Theme.getSelectorDrawable(false, getResourceProvider()));
            setOnClickListener(view -> {
                if (item != null) {
                    showInChat(item);
                }
            });
            ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    getString(R.string.ShowInChat), null);

            state = new StateView(context);
            state.setOnClickListener(view -> {
                if (item != null && item.state == STATE_COMPLETE) {
                    togglePlayback(item);
                }
            });

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            title = text(context, 16, Theme.key_windowBackgroundWhiteBlackText);
            texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            detail = text(context, 13, Theme.key_windowBackgroundWhiteGrayText2);
            texts.addView(detail, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            retry = new TextView(context);
            retry.setText(R.string.Retry);
            retry.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            retry.setTypeface(AndroidUtilities.bold());
            retry.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText4));
            retry.setGravity(Gravity.CENTER);
            retry.setMinWidth(dp(48));
            retry.setPadding(dp(12), 0, dp(12), 0);
            retry.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
            retry.setOnClickListener(view -> {
                if (item != null && item.retryable) {
                    PurplePinnedMusic.retryItem(currentAccount, dialogId, topicId, item.fileName);
                }
            });

            if (LocaleController.isRTL) {
                addView(retry, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
                addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 16, 10, 0, 10));
                addView(state, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 8, 0, 12, 0));
            } else {
                addView(state, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 12, 0, 8, 0));
                addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 10, 16, 10));
                addView(retry, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
            }
        }

        void bind(PurplePinnedMusic.Item item) {
            boolean sameItem = this.item != null && key(this.item).equals(key(item));
            this.item = item;
            title.setText(item.title);
            String status = status(item);
            if (TextUtils.isEmpty(item.artist)) {
                detail.setText(status);
            } else {
                detail.setText(status == null ? item.artist : item.artist + " · " + status);
            }
            detail.setVisibility(TextUtils.isEmpty(detail.getText()) ? GONE : VISIBLE);
            retry.setVisibility(item.retryable ? VISIBLE : GONE);
            retry.setContentDescription(getString(R.string.Retry) + ", " + item.title);
            updatePlayback(sameItem);
        }

        void updatePlayback(boolean animated) {
            if (item == null) {
                return;
            }
            boolean playable = item.state == STATE_COMPLETE;
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            boolean playingNow = playable && isCurrent(item, playing) && !MediaController.getInstance().isMessagePaused();
            float progress = item.state == STATE_ACTIVE && item.total > 0 ? item.downloaded / (float) item.total : 0f;
            state.set(playable, playingNow, item.state == STATE_FAILED, progress, animated);
            state.setClickable(playable);
            state.setImportantForAccessibility(playable
                    ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
            state.setContentDescription(playable
                    ? getString(playingNow ? R.string.AccActionPause : R.string.AccActionPlay) + ", " + item.title
                    : null);
        }

        private TextView text(Context context, int sizeDp, int colorKey) {
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
            view.setTextColor(getThemedColor(colorKey));
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            return view;
        }
    }

    private final class StateView extends View {
        private final PlayPauseDrawable playPause = new PlayPauseDrawable(16);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean playable;
        private boolean failed;
        private float progress;

        StateView(Context context) {
            super(context);
            playPause.setParent(this);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(dp(2));
            arc.setStrokeCap(Paint.Cap.ROUND);
            setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(20)));
        }

        void set(boolean playable, boolean playing, boolean failed, float progress, boolean animated) {
            this.playable = playable;
            this.failed = failed;
            this.progress = progress;
            playPause.setPause(playing, animated);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = dp(20);
            if (playable) {
                fill.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                canvas.drawCircle(cx, cy, radius, fill);
                playPause.setColor(getThemedColor(Theme.key_featuredStickers_buttonText));
                playPause.setBounds((int) (cx - radius), (int) (cy - radius), (int) (cx + radius), (int) (cy + radius));
                playPause.draw(canvas);
                return;
            }
            float inner = radius - dp(3);
            int track = getThemedColor(failed ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayIcon);
            ring.setColor(track);
            ring.setAlpha(failed ? 255 : 90);
            canvas.drawCircle(cx, cy, inner, ring);
            if (progress > 0) {
                arc.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                rect.set(cx - inner, cy - inner, cx + inner, cy + inner);
                canvas.drawArc(rect, -90, 360 * progress, false, arc);
            }
        }
    }
}
