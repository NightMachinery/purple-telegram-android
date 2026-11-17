package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
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
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
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
            DELETED = MORE + 3, CONTACTS = MORE + 4, UNDO = MORE + 5, ROW = MORE + 6, SONG = MORE + 7, RETRY = MORE + 8, DOWNLOAD = MORE + 9, DOWNLOAD_CANCEL = MORE + 10, DOWNLOAD_RETRY = MORE + 11, KEEP = MORE + 12, KEEP_DEFAULT = MORE + 13, REMOVE_DOWNLOADS = MORE + 14, RETRY_CLEANUP = MORE + 15, SYNC = MORE + 16, SYNC_ENABLED = MORE + 17;
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
        return state != null && state.view != null && state.view.detail != null && !state.view.detail.row.title.isEmpty() ? state.view.detail.row.title
                : text(recentlyDeleted ? "Deleted" : "Title");
    }
    @Override public View createView(Context context) {
        View result = super.createView(context);
        if (mainTab) actionBar.setBackButtonDrawable(null);
        ActionBarMenuItem menu = actionBar.createMenu().addItem(MORE, R.drawable.ic_ab_other);
        menu.addSubItem(CREATE, R.drawable.msg_add, text("Create"));
        menu.addSubItem(REFRESH, R.drawable.msg_retry, text("Refresh"));
        if (adding == null) menu.addSubItem(DELETED, R.drawable.msg_delete, text("Deleted"));
        if (adding == null) menu.addSubItem(CONTACTS, R.drawable.msg_contacts, getString(R.string.Contacts));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == REFRESH) refresh();
                else if (id == CREATE && editable() && canAdd()) title(null);
                else if (id == DELETED && !busy) presentFragment(new PurplePlaylistsActivity(currentAccount, "", true));
                else if (id == CONTACTS && !busy) PurplePlaylistsUI.contacts(PurplePlaylistsActivity.this, currentAccount);
            }
        });
        return result;
    }
    @Override public void onResume() {
        super.onResume(); visible = true; state = null;
        if (screen != null) screen.close();
        screen = PurplePlaylistsScreen.open(currentAccount, this);
        if (screen != null) busy = screen.select(playlistId);
        downloads = screen == null ? null : screen.downloads();
        if (downloadObservation != null) downloadObservation.close();
        downloadObservation = downloads == null ? null : downloads.observe(this);
        if (playbackObservation != null) playbackObservation.close();
        playbackObservation = MediaController.getInstance().purplePlaylists().observe(currentAccount, this);
        update();
    }
    @Override public void onPause() { retire(); super.onPause(); }
    @Override public void onFragmentDestroy() {
        PurpleWriterUI.retire(this); retire(); super.onFragmentDestroy(); }
    private void retire() {
        visible = false; AndroidUtilities.cancelRunOnUIThread(syncWake);
        if (playbackObservation != null) playbackObservation.close();
        playbackObservation = null; playback = null;
        if (downloadObservation != null) downloadObservation.close();
        downloadObservation = null; downloads = null; downloading = null;
        if (screen != null) screen.close(); screen = null; state = null; busy = syncing = false; syncResult = null;
    }
    private boolean editable() { return visible && !busy && screen != null && state != null && state.ready(); }
    private boolean canAdd() { return adding == null || adding.available(); }
    private void refresh() { if (visible && screen != null) { busy = screen.refresh(); update(); } }
    private void update() { if (listView != null) listView.adapter.update(true); if (actionBar != null) actionBar.setTitle(getTitle()); }
    @Override public void playlistsChanged(PurplePlaylistsScreen.State value) {
        if (!visible || screen == null) return;
        if (adding != null && adding.submitted() && addResultText == null && !value.editError.isEmpty()
                && (value.ready() || value.libraryCommitted)) addResultText = addResult(value);
        state = value; busy = false; update();
        if (value.ready() && value.libraryCommitted && value.canUndo && BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, adding != null && adding.submitted() ? addResult(value) : text("Saved"), getString(R.string.Undo), () -> {
                if (unchanged(value)) submit(screen.undo());
            }).show();
        }
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
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(REFRESH, text("Refresh")).setEnabled(!busy));
        if (adding != null) items.add(UItem.asShadow(adding.submitted() ? (addResultText == null ? text("AddSubmitted") : addResultText)
                : org.telegram.messenger.LocaleController.formatString("PurplePlaylistsAddChoose", 0, adding.accepted, adding.skipped)));
        if (playback != null && playback.account == currentAccount && playback.status != PurplePlaylistsPlayer.Status.Idle) {
            items.add(UItem.asShadow(playback.title.isEmpty() ? playbackText() : playback.title + " · " + playbackText()));
            if (playback.canRetry) {
                UItem retry = UItem.asButton(RETRY, text("PlaybackRetryAction"));
                retry.object2 = playback; items.add(retry);
            }
        }
        if (adding == null && downloading != null && downloading.status != PurplePlaylistsDownloadControls.Status.Idle) {
            items.add(UItem.asShadow(downloadText()));
            if (downloading.canCancel) {
                UItem cancel = UItem.asButton(DOWNLOAD_CANCEL, getString(R.string.Cancel));
                cancel.object2 = downloading; items.add(cancel);
            } else if (downloading.canRetry && downloading.playlistId.equals(playlistId)) {
                UItem retry = UItem.asButton(DOWNLOAD_RETRY, text("DownloadRetry"));
                retry.object2 = downloading; items.add(retry.setEnabled(!busy && state != null
                        && state.downloadStatus == org.telegram.messenger.purple.PurplePlaylistsDownloadsCore.Status.Ready));
            }
        }
        if (state == null) { items.add(UItem.asShadow(text(screen == null ? "Unavailable" : "Loading"))); return; }
        if (state.cleanup != null) {
            items.add(UItem.asShadow(text("Cleanup" + state.cleanup.status.name())));
            if (!state.cleanup.ready()) items.add(UItem.asButton(RETRY_CLEANUP, text("CleanupRetry")).setEnabled(!busy));
        }
        if (!state.ready()) items.add(UItem.asShadow(text(state.partial() ? "Partial" :
                state.storageStatus.startsWith("Newer") ? "Newer" : state.status == PurplePlaylistsScreen.Status.InvalidEdit ? "InvalidEdit" : "Unavailable")));
        if (state.canUndo) items.add(UItem.asButton(UNDO, getString(R.string.Undo)).setEnabled(!busy));
        PurplePlaylistsView view = state.view;
        if (view == null) return;
        if (view.incompleteCount > 0 || view.hiddenCount > 0)
            items.add(UItem.asShadow(org.telegram.messenger.LocaleController.formatString("PurplePlaylistsSyncHidden", 0,
                    view.incompleteCount, view.hiddenCount)));
        if (adding == null && playlistId.isEmpty() && !recentlyDeleted) {
            UItem enabled = UItem.asCheck(SYNC_ENABLED, text("SyncAutomatic"));
            enabled.checked = view.syncEnabled; enabled.object2 = view;
            items.add(enabled.setEnabled(editable() && !syncing));
            items.add(UItem.asShadow(syncText(view)));
            UItem sync = UItem.asButton(SYNC, text(syncResult != null
                    && syncResult.status == PurplePlaylistsSyncResult.Status.OutcomeUnknown ? "SyncCheck" : "SyncNow"));
            sync.object2 = syncResult == null ? view : syncResult;
            items.add(sync.setEnabled(editable() && !syncing && (syncResult == null || syncResult.retryAt <= System.currentTimeMillis())));
        }
        if (playlistId.isEmpty()) {
            if (!recentlyDeleted) items.add(UItem.asButton(CREATE, text("Create")).setEnabled(editable() && canAdd()));
            if (!recentlyDeleted && adding == null) {
                UItem keep = UItem.asCheck(KEEP_DEFAULT, text("KeepDefault"));
                keep.checked = Boolean.TRUE.equals(state.keepDefault);
                items.add(keep.setEnabled(editable() && state.keepDefault != null));
                if (state.keepDefault == null) items.add(UItem.asShadow(text("KeepUnavailable")));
            }
            List<PurplePlaylistsView.Row> rows = recentlyDeleted ? view.deleted : view.playlists;
            for (PurplePlaylistsView.Row row : rows) {
                UItem item = UItem.asButton(ROW, row.title.isEmpty() ? text("Incomplete") : row.title, Integer.toString(row.songCount));
                item.object2 = row; items.add(item.setEnabled(!busy && (adding == null || canAdd() && !row.partial)));
            }
            if (rows.isEmpty()) items.add(UItem.asShadow(text("Empty")));
        } else if (view.detail == null) items.add(UItem.asShadow(text("Missing")));
        else {
            if (view.detail.row.partial) items.add(UItem.asShadow(text("Incomplete")));
            if (state.downloadStatus != null && state.downloadStatus != org.telegram.messenger.purple.PurplePlaylistsDownloadsCore.Status.Ready)
                items.add(UItem.asShadow(text(state.downloadStatus == org.telegram.messenger.purple.PurplePlaylistsDownloadsCore.Status.Newer
                        ? "DownloadNewer" : "DownloadInvalid")));
            if (adding == null && !view.detail.row.deleted && !view.detail.row.partial)
                items.add(UItem.asButton(KEEP, text("KeepTitle"), keepLabel(state)).setEnabled(editable() && state.keepPreference != null));
            if (adding == null && !view.detail.row.deleted && !view.detail.row.partial && !view.detail.songs.isEmpty())
                items.add(UItem.asButton(DOWNLOAD, text("DownloadNow")).setEnabled(editable() && downloads != null
                        && state.downloadStatus == org.telegram.messenger.purple.PurplePlaylistsDownloadsCore.Status.Ready
                        && (downloading == null || !downloading.canCancel
                            && downloading.status != PurplePlaylistsDownloadControls.Status.Canceling)));
            if (adding == null && !view.detail.row.partial)
                items.add(UItem.asButton(REMOVE_DOWNLOADS, text("RemoveDownloads")).setEnabled(editable()
                        && state.downloadStatus == org.telegram.messenger.purple.PurplePlaylistsDownloadsCore.Status.Ready
                        && (downloading == null || !downloading.canCancel
                            && downloading.status != PurplePlaylistsDownloadControls.Status.Canceling)));
            for (PurplePlaylistsView.Song song : view.detail.songs) {
                UItem item = UItem.asButton(SONG, song.title.isEmpty() ? text("Untitled") : song.title, playback != null && song.documentId.equals(playback.selectedDocumentId) ? playbackText() : song.performer);
                item.object2 = song; items.add(item.setEnabled(editable() && !view.detail.row.deleted));
            }
            if (view.detail.songs.isEmpty()) items.add(UItem.asShadow(text("NoSongs")));
            if (!view.detail.row.partial) items.add(UItem.asButton(ROW, text("Actions")).setEnabled(editable()));
        }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
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
        if (busy) return;
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
        if (item.id == REFRESH) refresh();
        else if (item.id == UNDO && state != null && state.canUndo) submit(screen.undo());
        else if (item.id == CREATE && editable() && canAdd()) title(null);
        else if (item.id == ROW && item.object2 instanceof PurplePlaylistsView.Row) {
            PurplePlaylistsView.Row row = (PurplePlaylistsView.Row) item.object2;
            if (adding != null) submit(screen.add(row.id, adding));
            else presentFragment(new PurplePlaylistsActivity(currentAccount, row.id, false));
        } else if (item.id == ROW && editable() && state.view.detail != null) playlistActions(state.view.detail.row);
        else if (item.id == SONG && item.object2 instanceof PurplePlaylistsView.Song && editable()) {
            if (!screen.play(((PurplePlaylistsView.Song) item.object2).documentId) && BulletinFactory.canShowBulletin(this))
                BulletinFactory.of(this).createErrorBulletin(text("RefreshNeeded")).show();
        }
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!editable() || adding != null) return false;
        if (item.object2 instanceof PurplePlaylistsView.Row) { playlistActions((PurplePlaylistsView.Row) item.object2); return true; }
        if (item.object2 instanceof PurplePlaylistsView.Song) { songActions((PurplePlaylistsView.Song) item.object2); return true; }
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
