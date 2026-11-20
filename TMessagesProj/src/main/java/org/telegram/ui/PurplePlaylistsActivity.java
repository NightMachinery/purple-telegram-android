package org.telegram.ui;

import android.app.Activity;
import static org.telegram.messenger.LocaleController.getString;
import android.content.Context;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.messenger.purple.PurplePlaylistsDownloadsCore;
import org.telegram.messenger.purple.PurpleDiagLog;
import org.telegram.messenger.purple.PurplePlaylistsArtwork;
import org.telegram.ui.Components.AudioPlayerAlert;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.purple.PurplePlaylistsPlayer;
import org.telegram.messenger.purple.PurplePlaylistsDownloadControls;
import org.telegram.messenger.purple.PurplePlaylistsScreen;
import org.telegram.messenger.purple.PurplePlaylistsSyncResult;
import org.telegram.messenger.purple.PurplePlaylistsView;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.util.ArrayList;
import java.util.List;

public final class PurplePlaylistsActivity extends UniversalFragment implements PurplePlaylistsScreen.Listener, PurplePlaylistsPlayer.Listener, PurplePlaylistsDownloadControls.Listener {
    private static final int MORE = 0x70750f00, CREATE = MORE + 1, REFRESH = MORE + 2,
            DELETED = MORE + 3, CONTACTS = MORE + 4, HISTORY = MORE + 5, ROW = MORE + 6, SONG = MORE + 7, RETRY = MORE + 8, DOWNLOAD = MORE + 9, DOWNLOAD_CANCEL = MORE + 10, DOWNLOAD_RETRY = MORE + 11, KEEP = MORE + 12, KEEP_DEFAULT = MORE + 13, REMOVE_DOWNLOADS = MORE + 14, RETRY_CLEANUP = MORE + 15, SYNC = MORE + 16, SYNC_ENABLED = MORE + 17;
    private static final int OPTIONS = MORE + 18, ACTIONS = MORE + 19;
    private boolean optionsPage;
    private ActionBarMenuItem menu, createButton;
    private ActionBarMenuSubItem historyMenu;
    private PurplePlaylistCells.Deck deck;
    private PurplePlaylistsArtwork artwork;
    private PurplePlaylistsScreen.State dragState;
    private boolean savingTrackOrder;
    private final View.OnLayoutChangeListener mainTabBarLayout =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> insetMainTabContent(bottom - top);
    private final NotificationCenter.NotificationCenterDelegate musicChanged = (id, account, args) -> {
        if (this.visible && account == currentAccount) update();
    };
    private org.telegram.messenger.purple.PurplePlaylistsAdd.Selection adding;
    private String addResultText;
    private final String playlistId;
    private final boolean recentlyDeleted, mainTab;
    private PurplePlaylistsScreen screen;
    private PurplePlaylistsScreen.State state;
    private PurplePlaylistsPlayer.State playback;
    private PurplePlaylistsPlayer.Observation playbackObservation;
    private PurplePlaylistsDownloadControls downloads;
    private PurplePlaylistsDownloadControls.State downloading;
    private PurplePlaylistsDownloadControls.Observation downloadObservation;
    private boolean visible, busy, syncing;
    private PurplePlaylistsSyncResult syncResult;
    private final Runnable syncWake = () -> { if (visible) { update(); armSyncWake(); } };
    private void armSyncWake() {
        AndroidUtilities.cancelRunOnUIThread(syncWake);
        if (visible && syncResult != null && syncResult.retryAt > System.currentTimeMillis())
            AndroidUtilities.runOnUIThread(syncWake, Math.min(900000, syncResult.retryAt - System.currentTimeMillis()));
    }

    public PurplePlaylistsActivity(int account) { this(account, "", false); }
    public static PurplePlaylistsActivity add(org.telegram.messenger.purple.PurplePlaylistsAdd.Selection selection) {
        if (selection == null || !selection.available()) return null;
        PurplePlaylistsActivity result = new PurplePlaylistsActivity(selection.account);
        result.adding = selection; return result;
    }
    private PurplePlaylistsActivity(int account, String playlistId, boolean recentlyDeleted) {
        this(account, playlistId, recentlyDeleted, false);
    }
    private PurplePlaylistsActivity(int account, String playlistId, boolean recentlyDeleted, boolean mainTab) {
        setCurrentAccount(account); this.playlistId = playlistId; this.recentlyDeleted = recentlyDeleted; this.mainTab = mainTab;
    }
    static PurplePlaylistsActivity mainTab(int account) { return new PurplePlaylistsActivity(account, "", false, true); }
    static String text(String key) { return org.telegram.messenger.LocaleController.getString("PurplePlaylists" + key); }
    @Override protected CharSequence getTitle() {
        if (optionsPage) return text(playlistId.isEmpty() ? "Settings" : "OfflineMusic");
        if (adding != null) return text("Add");
        return state != null && state.view != null && state.view.detail != null && !state.view.detail.row.title.isEmpty() ? state.view.detail.row.title
                : text(recentlyDeleted ? "Deleted" : playlistId.isEmpty() ? "Title" : "Playlist");
    }
    @Override public View createView(Context context) {
        View result = super.createView(context);
        if (mainTab) {
            actionBar.setBackButtonDrawable(null);
            actionBar.addOnLayoutChangeListener(mainTabBarLayout);
            insetMainTabContent(actionBar.getHeight());
            Bulletin.addDelegate(this, new Bulletin.Delegate() {
                @Override
                public int getBottomOffset(int tag) {
                    return AndroidUtilities.isTablet() ? 0 : bottomContentPadding(getBottomInset());
                }
            });
        }
        int background = getThemedColor(optionsPage ? Theme.key_windowBackgroundGray : Theme.key_windowBackgroundWhite);
        result.setBackgroundColor(background);
        listView.setBackgroundColor(background);
        listView.setClipToPadding(false);
        if (!playlistId.isEmpty() && !optionsPage) installTrackDrag();
        onInsets(0, 0, 0, getBottomInset());
        if (playlistId.isEmpty() && !optionsPage && !recentlyDeleted) {
            createButton = actionBar.createMenu().addItem(CREATE, R.drawable.msg_add);
            createButton.setContentDescription(text("Create"));
            createButton.setVisibility(View.GONE);
        }
        menu = actionBar.createMenu().addItem(MORE, R.drawable.ic_ab_other);
        menu.setContentDescription(getString(R.string.AccDescrMoreOptions));
        if (!optionsPage && adding == null && !recentlyDeleted) {
            if (!playlistId.isEmpty()) menu.addSubItem(ACTIONS, R.drawable.msg_edit, text("Actions"));
            menu.addSubItem(OPTIONS, playlistId.isEmpty() ? R.drawable.msg_settings : R.drawable.msg_download,
                    text(playlistId.isEmpty() ? "Settings" : "OfflineMusic"));
        }
        historyMenu = menu.addSubItem(HISTORY, R.drawable.msg_retry, getString(R.string.Undo));
        menu.hideSubItem(HISTORY);
        menu.addSubItem(REFRESH, R.drawable.msg_retry, text("Refresh"));
        if (!optionsPage && adding == null && playlistId.isEmpty() && !recentlyDeleted) {
            menu.addSubItem(DELETED, R.drawable.msg_delete, text("Deleted"));
            menu.addSubItem(CONTACTS, R.drawable.msg_contacts, getString(R.string.Contacts));
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == REFRESH && !busy) refresh();
                else if (id == HISTORY) applyHistory(state);
                else if (id == OPTIONS && visible && !busy) {
                    PurplePlaylistsActivity page = new PurplePlaylistsActivity(currentAccount, playlistId, false);
                    page.optionsPage = true;
                    presentFragment(page);
                } else if (id == ACTIONS && editable() && state.view.detail != null) {
                    playlistActions(state.view.detail.row);
                }
                else if (id == CREATE && editable() && canAdd()) title(null);
                else if (id == DELETED && !busy) presentFragment(new PurplePlaylistsActivity(currentAccount, "", true));
                else if (id == CONTACTS && !busy) PurplePlaylistsUI.contacts(PurplePlaylistsActivity.this, currentAccount);
            }
        });
        return result;
    }
    private void insetMainTabContent(int height) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) listView.getLayoutParams();
        if (params.topMargin != height) {
            params.topMargin = height;
            listView.setLayoutParams(params);
        }
    }
    @Override public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        if (listView != null) listView.setPadding(0, 0, 0, bottomContentPadding(bottom));
    }
    private int bottomContentPadding(int bottom) {
        return bottom + AndroidUtilities.dp(mainTab && !AndroidUtilities.isTablet()
                ? DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 12 : 12);
    }

    private void observeMusic(boolean observe) {
        if (adding != null || recentlyDeleted || optionsPage) return;
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.updateObserver(observe, musicChanged, NotificationCenter.messagePlayingDidStart);
        center.updateObserver(observe, musicChanged, NotificationCenter.messagePlayingDidReset);
        center.updateObserver(observe, musicChanged, NotificationCenter.messagePlayingPlayStateChanged);
    }

    @Override public void onResume() {
        super.onResume();
        if (!visible) observeMusic(true);
        visible = true; state = null;
        if (artwork != null) artwork.close();
        artwork = null;
        if (screen != null) screen.close();
        openScreen();
        if (playbackObservation != null) playbackObservation.close();
        playbackObservation = MediaController.getInstance().purplePlaylists().observe(currentAccount, this);
        update();
    }
    private void openScreen() {
        screen = PurplePlaylistsScreen.open(currentAccount, this);
        if (screen != null && artwork == null && !recentlyDeleted && !optionsPage)
            artwork = PurplePlaylistsArtwork.open(currentAccount);
        busy = screen != null && screen.select(playlistId);
        downloads = screen == null ? null : screen.downloads();
        if (downloadObservation != null) downloadObservation.close();
        downloadObservation = downloads == null ? null : downloads.observe(this);
    }

    @Override public void onPause() { retire(); super.onPause(); }
    @Override public void onFragmentDestroy() {
        if (mainTab) Bulletin.removeDelegate(this);
        PurpleWriterUI.retire(this); retire(); super.onFragmentDestroy(); }
    private void retire() {
        if (visible) observeMusic(false);
        visible = false; deck = null;
        dragState = null; savingTrackOrder = false;
        if (artwork != null) artwork.close();
        artwork = null;
        AndroidUtilities.cancelRunOnUIThread(syncWake);
        if (playbackObservation != null) playbackObservation.close();
        playbackObservation = null; playback = null;
        if (downloadObservation != null) downloadObservation.close();
        downloadObservation = null; downloads = null; downloading = null;
        if (screen != null) screen.close(); screen = null; state = null; busy = syncing = false; syncResult = null;
    }
    private boolean editable() {
        return visible && !busy && screen != null && state != null && state.view != null && !state.partial();
    }
    private boolean canAdd() { return adding == null || adding.available(); }
    private void refresh() {
        if (!visible || busy || dragState != null) return;
        if (screen == null) openScreen();
        else busy = screen.refresh();
        update();
    }
    private void update() {
        if (actionBar != null) {
            actionBar.setTitle(getTitle());
            if (!optionsPage && !playlistId.isEmpty()) {
                PurplePlaylistsView.Detail detail = state == null || state.view == null ? null : state.view.detail;
                actionBar.setSubtitle(detail == null ? null : songCount(detail.songs.size()));
                if (menu != null) {
                    if (detail != null && !detail.row.partial) menu.showSubItem(ACTIONS);
                    else menu.hideSubItem(ACTIONS);
                }
            }
        }
        if (historyMenu != null) {
            if (state != null && !state.historyKind.isEmpty() && !busy) {
                historyMenu.setText(historyButton(state) + " · " + historyDescription(state));
                menu.showSubItem(HISTORY);
            } else menu.hideSubItem(HISTORY);
        }
        if (createButton != null) {
            boolean show = state != null && state.view != null && state.view.playlists != null
                    && !state.view.playlists.isEmpty() && canAdd();
            createButton.setVisibility(show ? View.VISIBLE : View.GONE);
            createButton.setEnabled(editable());
            createButton.setAlpha(editable() ? 1f : .5f);
        }
        if (listView != null && dragState == null && !savingTrackOrder) listView.adapter.update(true);
    }
    @Override public void playlistsChanged(PurplePlaylistsScreen.State value) {
        if (!visible || screen == null) return;
        if (adding != null && value.addOutcome != PurplePlaylistsScreen.AddOutcome.None) {
            switch (value.addOutcome) {
                case Saved: addResultText = addResult(value); break;
                case Partial: addResultText = addResult(value) + " " + text("Partial"); break;
                case Refused: addResultText = text("AddRefused"); break;
                case Unknown: addResultText = text("AddUnknown"); break;
            }
        }
        if (!value.ready()) {
            PurpleDiagLog.event("playlists", "screen_result", PurpleDiagLog.code("status", value.status.name().toLowerCase(java.util.Locale.ROOT)),
                    PurpleDiagLog.code("storage", value.storageStatus.isEmpty() ? "none" : value.storageStatus.toLowerCase(java.util.Locale.ROOT)),
                    PurpleDiagLog.code("add", value.addOutcome.name().toLowerCase(java.util.Locale.ROOT)),
                    PurpleDiagLog.flag("library_readable", value.view != null),
                    PurpleDiagLog.flag("library_committed", value.libraryCommitted));
        }
        state = value; busy = false; savingTrackOrder = false; update();
        if (value.historyChanged && BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, historyDescription(value), historyButton(value),
                    () -> applyHistory(value)).show();
        }
    }
    private static String historyButton(PurplePlaylistsScreen.State value) {
        return getString(value.canRedo ? R.string.Redo : R.string.Undo);
    }
    private static String historyDescription(PurplePlaylistsScreen.State value) {
        return text("History" + value.historyKind);
    }
    private void applyHistory(PurplePlaylistsScreen.State expected) {
        if (!visible || busy || dragState != null || screen == null || expected == null) return;
        submit(screen.applyHistory(expected));
    }
    @Override public void syncCompleted(PurplePlaylistsSyncResult result) {
        if (!visible || screen == null || currentAccount != org.telegram.messenger.UserConfig.selectedAccount) return;
        syncing = false; syncResult = result; armSyncWake(); update();
    }
    private String syncText(PurplePlaylistsView view) {
        if (syncing) return text("SyncRunning");
        if (syncResult != null && syncResult.status != PurplePlaylistsSyncResult.Status.Ready) {
            if (syncResult.retryAt > System.currentTimeMillis())
                return org.telegram.messenger.LocaleController.formatString("PurplePlaylistsSyncCooldown", 0,
                        org.telegram.messenger.LocaleController.formatDateTime(syncResult.retryAt / 1000, true));
            return text("Sync" + syncResult.status.name());
        }
        if (view.unsyncedSinceMs > 0) return text("SyncPending");
        return text("SyncLocal" + view.syncResult);
    }
    private void sync() {
        if (!visible || screen == null || busy || syncing) return;
        syncing = true; syncResult = null;
        if (!screen.sync()) syncing = false;
        update();
    }
    @Override public void downloadRefused() {
        if (visible && screen != null && currentAccount == org.telegram.messenger.UserConfig.selectedAccount
                && BulletinFactory.canShowBulletin(this))
            BulletinFactory.of(this).createErrorBulletin(text("RefreshNeeded")).show();
    }
    private String addResult(PurplePlaylistsScreen.State value) {
        return org.telegram.messenger.LocaleController.formatString("PurplePlaylistsAddResult", 0,
                value.added, value.relinked, value.unchanged, adding.skipped);
    }
    @Override public void playbackChanged(PurplePlaylistsPlayer.State value) {
        if (!visible || screen == null) return;
        playback = value; update();
    }
    @Override public void downloadsChanged(PurplePlaylistsDownloadControls.State value) {
        if (!visible || screen == null || value.account != currentAccount) return;
        boolean finished = downloading != null && (downloading.canCancel
                || downloading.status == PurplePlaylistsDownloadControls.Status.Canceling)
                && !value.canCancel && value.status != PurplePlaylistsDownloadControls.Status.Canceling;
        downloading = value; update();
        if (finished && !busy) refresh();
    }
    private String downloadText() {
        String result = text("Download" + downloading.status.name());
        if (downloading.status == PurplePlaylistsDownloadControls.Status.Wait)
            return org.telegram.messenger.LocaleController.formatString("PurplePlaylistsDownloadWait", 0, downloading.waitSeconds);
        return result + " · " + downloading.downloaded + "/" + downloading.total
                + " · " + org.telegram.messenger.LocaleController.formatString("PurplePlaylistsDownloadUnavailable", 0,
                    downloading.unavailable + downloading.failed + downloading.busy);
    }
    private String playbackText() {
        if (playback.interrupted) return text("PlaybackInterrupted");
        if (playback.status == PurplePlaylistsPlayer.Status.Wait)
            return org.telegram.messenger.LocaleController.formatString("PurplePlaylistsPlaybackWait", 0, playback.waitSeconds);
        return text("Playback" + playback.status.name());
    }
    private static String songCount(int count) {
        return LocaleController.formatPluralString("PurplePlaylistsSongs", count);
    }

    private MessageObject deckSong(boolean allowResolving) {
        if (!visible || adding != null || recentlyDeleted || optionsPage || currentAccount != UserConfig.selectedAccount) return null;
        MediaController media = MediaController.getInstance();
        MessageObject message = media.getPlayingMessageObject();
        if (message == null || message.currentAccount != currentAccount || !message.isMusic()) return null;
        PurplePlaylistsPlayer player = media.purplePlaylists();
        PurplePlaylistsPlayer.State live = player.snapshot();
        if (live.account == currentAccount && live.status != PurplePlaylistsPlayer.Status.Idle) {
            boolean ready = live.status == PurplePlaylistsPlayer.Status.Playing
                    || live.status == PurplePlaylistsPlayer.Status.Paused;
            boolean resolving = allowResolving && live.status == PurplePlaylistsPlayer.Status.Resolving;
            if ((!ready && !resolving) || player.displayedQueue(currentAccount) == null) return null;
        }
        return message;
    }

    private void addPlayback(ArrayList<UItem> items) {
        MessageObject message = deckSong(true);
        if (message != null) {
            if (deck == null) deck = new PurplePlaylistCells.Deck(getContext());
            MediaController media = MediaController.getInstance();
            PurplePlaylistsPlayer.Queue queue = media.purplePlaylists().displayedQueue(currentAccount);
            boolean paused = media.isMessagePaused();
            boolean canToggle = deckSong(false) != null;
            Runnable toggle = canToggle ? () -> {
                if (deckSong(false) != message || queue != media.purplePlaylists().displayedQueue(currentAccount)
                        || paused != media.isMessagePaused()) return;
                if (paused) media.playMessage(message); else media.pauseMessage(message);
            } : null;
            AudioInfo audio = media.getAudioInfo();
            deck.bind(message.getMusicTitle(), message.getMusicAuthor(), Long.hashCode(message.getDialogId()),
                    audio == null ? null : audio.getSmallCover(), paused, this::openDeckPlayer,
                    toggle, getResourceProvider());
            items.add(UItem.asCustom(deck, LayoutHelper.WRAP_CONTENT));
            if (!canToggle) items.add(UItem.asShadow(text("PlaybackResolving")));
        } else if (adding == null && !recentlyDeleted && !optionsPage && playback != null && playback.account == currentAccount
                && playback.status != PurplePlaylistsPlayer.Status.Idle && playback.status != PurplePlaylistsPlayer.Status.Closed) {
            items.add(UItem.asShadow(playback.title.isEmpty() ? playbackText() : playback.title + " · " + playbackText()));
            if (playback.canRetry) {
                UItem retry = UItem.asButton(RETRY, R.drawable.msg_retry, text("PlaybackRetryAction"));
                retry.object2 = playback;
                items.add(retry);
            }
        }
    }

    private void openDeckPlayer() {
        Activity activity = getParentActivity();
        if (activity instanceof LaunchActivity && AndroidUtilities.isContextSafe(activity)
                && deckSong(true) != null)
            new AudioPlayerAlert(activity, getResourceProvider()).show();
    }

    private void addDownloads(ArrayList<UItem> items) {
        if (adding == null && downloading != null && downloading.status != PurplePlaylistsDownloadControls.Status.Idle) {
            items.add(UItem.asShadow(downloadText()));
            if (downloading.canCancel) {
                UItem cancel = UItem.asButton(DOWNLOAD_CANCEL, getString(R.string.Cancel));
                cancel.object2 = downloading; items.add(cancel);
            } else if (downloading.canRetry && downloading.playlistId.equals(playlistId)) {
                UItem retry = UItem.asButton(DOWNLOAD_RETRY, text("DownloadRetry"));
                retry.object2 = downloading; items.add(retry.setEnabled(!busy && state != null
                        && state.downloadStatus == PurplePlaylistsDownloadsCore.Status.Ready));
            }
        }
    }

    private void empty(ArrayList<UItem> items, int icon, String title, String hint, boolean create) {
        boolean allowCreate = create && editable() && canAdd();
        Runnable action = allowCreate ? () -> {
            if (editable() && canAdd()) title(null);
        } : null;
        View view = PurplePlaylistCells.empty(getContext(), getResourceProvider(), icon, text(title), text(hint), action);
        items.add(UItem.asCustom(view, LayoutHelper.WRAP_CONTENT));
    }

    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        addPlayback(items);
        if (adding != null) items.add(UItem.asShadow(addResultText != null ? addResultText
                : adding.submitted() ? text("AddSubmitted")
                : LocaleController.formatString("PurplePlaylistsAddChoose", 0, adding.accepted, adding.skipped)));
        if (state == null) {
            items.add(UItem.asShadow(text(screen == null && visible ? "Unavailable" : "Loading")));
            if (visible && !busy) items.add(UItem.asButton(REFRESH, text("Refresh")));
            return;
        }
        if (state.cleanup != null) {
            items.add(UItem.asShadow(text("Cleanup" + state.cleanup.status.name())));
            if (!state.cleanup.ready()) items.add(UItem.asButton(RETRY_CLEANUP, text("CleanupRetry")).setEnabled(!busy));
        }
        if (!state.ready() && (state.view == null || state.addOutcome == PurplePlaylistsScreen.AddOutcome.None)) {
            items.add(UItem.asShadow(text(state.partial() ? "Partial" : state.storageStatus.startsWith("Newer") ? "Newer"
                    : state.status == PurplePlaylistsScreen.Status.InvalidEdit ? "InvalidEdit"
                    : state.status == PurplePlaylistsScreen.Status.Changed ? "Changed"
                    : state.view == null ? "Unavailable" : "EditFailed")));
        }
        if (state.view == null || state.partial())
            items.add(UItem.asButton(REFRESH, text("Refresh")).setEnabled(!busy));
        PurplePlaylistsView view = state.view;
        if (view == null) return;
        if (view.incompleteCount > 0 || view.hiddenCount > 0)
            items.add(UItem.asShadow(org.telegram.messenger.LocaleController.formatString("PurplePlaylistsSyncHidden", 0,
                    view.incompleteCount, view.hiddenCount)));
        if (optionsPage) {
            if (playlistId.isEmpty()) {
                items.add(UItem.asHeader(text("SyncHeading")));
                UItem enabled = UItem.asCheck(SYNC_ENABLED, text("SyncAutomatic"));
                enabled.checked = view.syncEnabled; enabled.object2 = view;
                items.add(enabled.setEnabled(editable() && !syncing));
                items.add(UItem.asShadow(syncText(view)));
                UItem sync = UItem.asButton(SYNC, text(syncResult != null
                        && syncResult.status == PurplePlaylistsSyncResult.Status.OutcomeUnknown ? "SyncCheck" : "SyncNow"));
                sync.object2 = syncResult == null ? view : syncResult;
                items.add(sync.setEnabled(editable() && !syncing && (syncResult == null || syncResult.retryAt <= System.currentTimeMillis())));

                items.add(UItem.asHeader(text("OfflineMusic")));
                UItem keep = UItem.asCheck(KEEP_DEFAULT, text("KeepDefault"));
                keep.checked = Boolean.TRUE.equals(state.keepDefault);
                items.add(keep.setEnabled(editable() && state.keepDefault != null));
                if (state.keepDefault == null) items.add(UItem.asShadow(text("KeepUnavailable")));
            } else if (view.detail != null) {
                items.add(UItem.asHeader(view.detail.row.title));
                if (state.downloadStatus != null && state.downloadStatus != PurplePlaylistsDownloadsCore.Status.Ready)
                    items.add(UItem.asShadow(text(state.downloadStatus == PurplePlaylistsDownloadsCore.Status.Newer
                            ? "DownloadNewer" : "DownloadInvalid")));
                if (adding == null && !view.detail.row.deleted && !view.detail.row.partial)
                    items.add(UItem.asButton(KEEP, text("KeepTitle"), keepLabel(state)).setEnabled(editable() && state.keepPreference != null));
                if (adding == null && !view.detail.row.deleted && !view.detail.row.partial && !view.detail.songs.isEmpty())
                    items.add(UItem.asButton(DOWNLOAD, text("DownloadNow")).setEnabled(editable() && downloads != null
                            && state.downloadStatus == PurplePlaylistsDownloadsCore.Status.Ready
                            && (downloading == null || !downloading.canCancel
                                && downloading.status != PurplePlaylistsDownloadControls.Status.Canceling)));
                if (adding == null && !view.detail.row.partial)
                    items.add(UItem.asButton(REMOVE_DOWNLOADS, text("RemoveDownloads")).setEnabled(editable()
                            && state.downloadStatus == PurplePlaylistsDownloadsCore.Status.Ready
                            && (downloading == null || !downloading.canCancel
                                && downloading.status != PurplePlaylistsDownloadControls.Status.Canceling)));
            } else {
                items.add(UItem.asShadow(text("Missing")));
            }
            addDownloads(items);
            return;
        }
        addDownloads(items);
        if (playlistId.isEmpty()) {
            List<PurplePlaylistsView.Row> rows = recentlyDeleted ? view.deleted : view.playlists;
            if (rows.isEmpty()) {
                if (recentlyDeleted) empty(items, R.drawable.msg_delete, "DeletedEmptyTitle", "DeletedEmptyHint", false);
                else empty(items, R.drawable.msg_filled_data_music, "LibraryEmptyTitle",
                        adding == null ? "LibraryEmptyHint" : "ChooserEmptyHint", true);
                return;
            }
            if (adding == null && !recentlyDeleted) items.add(UItem.asHeader(text("YourPlaylists")));
            for (PurplePlaylistsView.Row row : rows) {
                String title = row.title.isEmpty() ? text("Incomplete") : row.title;
                String count = songCount(row.songCount);
                if (row.partial) count += " · " + text("Incomplete");
                UItem item = PurplePlaylistCells.RowFactory.of(ROW, row.id, row, title, count, "", row.id.hashCode(), false);
                PurplePlaylistsArtwork loader = artwork;
                item.bind = cell -> ((PurplePlaylistCells.Row) cell).setCover(loader, row.coverSong);
                items.add(item.setEnabled(!busy && (adding == null || canAdd() && !row.partial)));
            }
        } else if (view.detail == null) {
            items.add(UItem.asShadow(text("Missing")));
        } else {
            PurplePlaylistsView.Detail detail = view.detail;
            if (detail.row.partial) items.add(UItem.asShadow(text("Incomplete")));
            if (detail.row.deleted) items.add(UItem.asShadow(text("DeletedDetail")));
            if (detail.songs.isEmpty()) {
                empty(items, R.drawable.msg_filled_data_music, "SongsEmptyTitle",
                        detail.row.deleted ? "DeletedEmptyHint" : "SongsEmptyHint", false);
                return;
            }
            boolean reorder = canDragTracks();
            if (reorder) adapter.reorderSectionStart();
            for (PurplePlaylistsView.Song song : detail.songs) {
                boolean selected = playback != null && playback.account == currentAccount
                        && playlistId.equals(playback.playlistId) && song.documentId.equals(playback.selectedDocumentId);
                String title = song.title.isEmpty() ? text("Untitled") : song.title;
                String artist = song.performer.isEmpty() ? getString(R.string.AudioUnknownArtist) : song.performer;
                String subtitle = selected ? playbackText() + " · " + artist : artist;
                UItem item = PurplePlaylistCells.RowFactory.of(SONG, song.documentId, song, title, subtitle,
                        song.duration > 0 ? AndroidUtilities.formatShortDuration(song.duration) : "", song.documentId.hashCode(), selected);
                PurplePlaylistsArtwork loader = artwork;
                item.bind = cell -> ((PurplePlaylistCells.Row) cell).setCover(loader, song);
                if (editable() && !detail.row.deleted)
                    item.clickCallback2 = cell -> {
                        if (editable() && dragState == null) songActions(song);
                    };
                items.add(item.setEnabled(editable() && !detail.row.deleted));
            }
            if (reorder) adapter.reorderSectionEnd();
        }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == REFRESH) { refresh(); return; }
        if (!visible || screen == null) return;
        if (item.id == RETRY && item.object2 instanceof PurplePlaylistsPlayer.State) {
            if (!screen.retry((PurplePlaylistsPlayer.State) item.object2)
                    && BulletinFactory.canShowBulletin(this)) BulletinFactory.of(this).createErrorBulletin(text("RefreshNeeded")).show();
            return;
        }
        if (item.id == DOWNLOAD_CANCEL && item.object2 instanceof PurplePlaylistsDownloadControls.State) {
            if (downloads != null) downloads.cancel((PurplePlaylistsDownloadControls.State) item.object2);
            return;
        }
        if (busy || dragState != null) return;
        if (item.id == SYNC && editable()) {
            if (item.object2 == state.view || item.object2 == syncResult) sync();
            return;
        }
        if (item.id == SYNC_ENABLED && editable() && !syncing && item.object2 == state.view) {
            submit(screen.setSync(!state.view.syncEnabled)); return;
        }
        if (item.id == REMOVE_DOWNLOADS && editable()) { removeDownloads(); return; }
        if (item.id == RETRY_CLEANUP && state != null) { submit(screen.removeDownloads(state, state.cleanup == null || state.cleanup.prune)); return; }
        if (item.id == KEEP && editable()) { keepPreference(); return; }
        if (item.id == KEEP_DEFAULT && editable()) { keepDefault(); return; }
        if (item.id == DOWNLOAD && editable()) { submit(screen.download(null)); return; }
        if (item.id == DOWNLOAD_RETRY && item.object2 instanceof PurplePlaylistsDownloadControls.State) {
            submit(screen.download((PurplePlaylistsDownloadControls.State) item.object2)); return;
        }
        if (item.id == CREATE && editable() && canAdd()) title(null);
        else if (item.id == ROW && item.object2 instanceof PurplePlaylistsView.Row) {
            PurplePlaylistsView.Row row = (PurplePlaylistsView.Row) item.object2;
            if (adding != null) {
                boolean accepted = screen.add(row.id, adding);
                if (accepted) addResultText = null;
                submit(accepted);
            } else presentFragment(new PurplePlaylistsActivity(currentAccount, row.id, false));
        } else if (item.id == ROW && editable() && state.view.detail != null) playlistActions(state.view.detail.row);
        else if (item.id == SONG && item.object2 instanceof PurplePlaylistsView.Song && editable()) {
            if (!screen.play(((PurplePlaylistsView.Song) item.object2).documentId) && BulletinFactory.canShowBulletin(this))
                BulletinFactory.of(this).createErrorBulletin(text("RefreshNeeded")).show();
        }
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!editable() || adding != null) return false;
        if (item.object2 instanceof PurplePlaylistsView.Row) { playlistActions((PurplePlaylistsView.Row) item.object2); return true; }
        if (item.object2 instanceof PurplePlaylistsView.Song && !canDragTracks()) {
            songActions((PurplePlaylistsView.Song) item.object2);
            return true;
        }
        return false;
    }
    private void removeDownloads() {
        PurplePlaylistsScreen.State expected = state;
        showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(text("RemoveDownloads"))
                .setMessage(text("RemoveDownloadsConfirm"))
                .setPositiveButton(getString(R.string.Remove), (dialog, button) -> {
                    if (unchanged(expected)) submit(screen.removeDownloads(expected, false));
                }).setNegativeButton(getString(R.string.Cancel), null).create());
    }
    private static String keepLabel(PurplePlaylistsScreen.State value) {
        if (value.keepPreference == null) return text("KeepUnavailable");
        if (value.keepPreference != org.telegram.messenger.purple.PurplePlaylistsDownloadsCore.Preference.Inherit)
            return text("Keep" + value.keepPreference.name());
        return text("KeepInherit") + " (" + text(value.keepDefault == null ? "KeepUnavailable"
                : value.keepDefault ? "KeepOn" : "KeepOff") + ")";
    }
    private void keepPreference() {
        PurplePlaylistsScreen.State expected = state;
        if (expected.keepPreference == null) return;
        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity());
        dialog.setTitle(text("KeepTitle"));
        LinearLayout layout = new LinearLayout(getParentActivity()); layout.setOrientation(LinearLayout.VERTICAL);
        ArrayList<org.telegram.ui.Cells.RadioColorCell> radios = new ArrayList<>();
        int[] selected = {expected.keepPreference.ordinal()};
        CharSequence[] labels = {text("KeepInherit") + " (" + text(expected.keepDefault == null ? "KeepUnavailable"
                : expected.keepDefault ? "KeepOn" : "KeepOff") + ")", text("KeepOn"), text("KeepOff")};
        for (int index = 0; index < labels.length; ++index) {
            final int option = index;
            org.telegram.ui.Cells.RadioColorCell cell = new org.telegram.ui.Cells.RadioColorCell(getParentActivity(), getResourceProvider());
            cell.setTextAndValue(labels[index], selected[0] == index);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            cell.setOnClickListener(view -> {
                selected[0] = option;
                for (int i = 0; i < radios.size(); ++i) radios.get(i).setChecked(i == option, true);
            });
            radios.add(cell); layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        dialog.setView(layout);
        dialog.setPositiveButton(getString(R.string.Save), (ignored, index) -> {
            if (unchanged(expected)) submit(screen.keepPreference(
                    org.telegram.messenger.purple.PurplePlaylistsDownloadsCore.Preference.values()[selected[0]]));
        });
        dialog.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(dialog.create());
    }
    private void keepDefault() {
        PurplePlaylistsScreen.State expected = state;
        if (!screen.keepDefaultCurrent(expected) || expected.keepDefault == null) { downloadRefused(); return; }
        org.telegram.messenger.purple.PurpleWriter.Session session =
                PurpleWriterUI.captureOrExplain(this, true, this::refresh);
        if (session == null) return;
        session.guardBeforePublish(() -> screen != null && unchanged(expected) && screen.keepDefaultCurrent(expected));
        String error = org.telegram.messenger.purple.PurpleWriter.setTableBool(session, "playlists", "keep_downloaded_p",
                !expected.keepDefault, "playlist keep default");
        session.retire();
        if (error != null && BulletinFactory.canShowBulletin(this))
            BulletinFactory.of(this).createErrorBulletin(text("RefreshNeeded")).show();
        refresh();
    }
    private boolean unchanged(PurplePlaylistsScreen.State expected) { return editable() && state == expected; }
    private void submit(boolean accepted) {
        busy = accepted; update();
        if (!accepted && BulletinFactory.canShowBulletin(this)) BulletinFactory.of(this).createErrorBulletin(text("RefreshNeeded")).show();
    }
    private void title(PurplePlaylistsView.Row row) {
        if (getParentActivity() == null || !editable()) return;
        PurplePlaylistsScreen.State expected = state;
        EditTextBoldCursor input = new EditTextBoldCursor(getParentActivity());
        input.setTextSize(16); input.setSingleLine(true); input.setBackgroundDrawable(null);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack)); input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setHint(text("Name")); input.setText(row == null ? "" : row.title);
        input.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        LinearLayout box = new LinearLayout(getParentActivity()); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity());
        dialog.setTitle(text(row == null ? "Create" : "Rename")); dialog.setView(box);
        dialog.setNegativeButton(getString(R.string.Cancel), null);
        dialog.setPositiveButton(getString(R.string.Save), (d, which) -> {
            if (!unchanged(expected)) return;
            try { submit(row == null ? screen.create(input.getText().toString()) : screen.rename(row.id, input.getText().toString())); }
            catch (IllegalArgumentException e) { if (BulletinFactory.canShowBulletin(this)) BulletinFactory.of(this).createErrorBulletin(text("InvalidEdit")).show(); }
        });
        showDialog(dialog.create()); input.requestFocus(); AndroidUtilities.showKeyboard(input);
    }
    private void playlistActions(PurplePlaylistsView.Row row) {
        if (row.partial || getParentActivity() == null) return;
        PurplePlaylistsScreen.State expected = state;
        CharSequence[] labels = row.deleted ? new CharSequence[] {text("Restore"), text("DeleteForever")}
                : new CharSequence[] {text("Rename"), text("Copy"), text("Delete")};
        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity()); dialog.setTitle(row.title);
        dialog.setItems(labels, (d, which) -> {
            if (!unchanged(expected)) return;
            if (!row.deleted && which == 0) title(row);
            else if (!row.deleted && which == 1) submit(screen.copy(row.id));
            else if (row.deleted && which == 0) submit(screen.playlist(PurplePlaylistsScreen.PlaylistAction.Restore, row.id));
            else confirmDelete(expected, row);
        });
        showDialog(dialog.create());
    }
    private void confirmDelete(PurplePlaylistsScreen.State expected, PurplePlaylistsView.Row row) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity());
        dialog.setTitle(text(row.deleted ? "DeleteForever" : "Delete")); dialog.setMessage(text(row.deleted ? "PurgeAbout" : "DeleteAbout"));
        dialog.setNegativeButton(getString(R.string.Cancel), null);
        dialog.setPositiveButton(getString(R.string.Delete), (d, which) -> {
            if (unchanged(expected)) submit(screen.playlist(row.deleted ? PurplePlaylistsScreen.PlaylistAction.DeleteForever
                    : PurplePlaylistsScreen.PlaylistAction.Delete, row.id));
        });
        showDialog(dialog.create());
    }
    private boolean canDragTracks() {
        if (!editable() || adding != null || optionsPage || state.view.detail == null) return false;
        PurplePlaylistsView.Detail detail = state.view.detail;
        return !detail.row.deleted && !detail.row.partial && detail.songs.size() > 1;
    }

    private void installTrackDrag() {
        listView.adapter.listenReorder((section, rows) -> saveTrackOrder(rows));
        listView.itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public int getDragDirs(RecyclerView recycler, RecyclerView.ViewHolder holder) {
                return canDragTracks() && listView.adapter.isReorderItem(holder.getAdapterPosition())
                        ? super.getDragDirs(recycler, holder) : 0;
            }

            @Override public void onSelectedChanged(RecyclerView.ViewHolder holder, int action) {
                if (action == ItemTouchHelper.ACTION_STATE_DRAG && canDragTracks()) {
                    dragState = state;
                    listView.cancelClickRunnables(false);
                }
                super.onSelectedChanged(holder, action);
                if (action == ItemTouchHelper.ACTION_STATE_IDLE) {
                    listView.adapter.reorderDone();
                    dragState = null;
                    update();
                }
            }

            @Override public boolean onMove(RecyclerView recycler, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                int source = from.getAdapterPosition(), target = to.getAdapterPosition();
                if (dragState == null || state != dragState || !listView.adapter.isReorderItem(source)
                        || listView.adapter.getReorderSectionId(source) != listView.adapter.getReorderSectionId(target))
                    return false;
                listView.adapter.swapElements(source, target);
                return true;
            }

            @Override public void onSwiped(RecyclerView.ViewHolder holder, int direction) { }
        });
        listView.itemTouchHelper.attachToRecyclerView(listView);
    }

    private void saveTrackOrder(ArrayList<UItem> rows) {
        if (dragState == null || !unchanged(dragState) || !canDragTracks()) return;
        List<PurplePlaylistsView.Song> original = dragState.view.detail.songs;
        if (original.size() != rows.size()) return;
        java.util.HashSet<PurplePlaylistsView.Song> remaining = new java.util.HashSet<>(original);
        String[] order = new String[rows.size()];
        boolean changed = false;
        for (int index = 0; index < order.length; ++index) {
            Object song = rows.get(index).object2;
            if (!remaining.remove(song)) return;
            order[index] = ((PurplePlaylistsView.Song) song).documentId;
            changed |= !order[index].equals(original.get(index).documentId);
        }
        if (changed) {
            savingTrackOrder = screen.reorder(playlistId, order);
            submit(savingTrackOrder);
        }
    }

    private void songActions(PurplePlaylistsView.Song song) {
        if (getParentActivity() == null || state.view.detail == null) return;
        PurplePlaylistsScreen.State expected = state;
        List<PurplePlaylistsView.Song> songs = state.view.detail.songs;
        int index = songs.indexOf(song); if (index < 0) return;
        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity()); dialog.setTitle(song.title);
        dialog.setItems(new CharSequence[] {text("RemoveSong"), text("MoveUp"), text("MoveDown")}, (d, which) -> {
            if (!unchanged(expected)) return;
            if (which == 0) { submit(screen.remove(playlistId, new String[] {song.documentId})); return; }
            int to = which == 1 ? index - 1 : index + 1;
            if (to < 0 || to >= songs.size()) return;
            String[] ids = new String[songs.size()]; for (int i = 0; i < ids.length; ++i) ids[i] = songs.get(i).documentId;
            ids[index] = ids[to]; ids[to] = song.documentId; submit(screen.reorder(playlistId, ids));
        });
        showDialog(dialog.create());
    }
}
