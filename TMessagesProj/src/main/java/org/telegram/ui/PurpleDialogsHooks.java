package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleFolders;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleListMenu;
import org.telegram.messenger.purple.PurpleStripWatch;
import org.telegram.messenger.purple.PurpleSyncOffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns Purple menu state for one actual DialogsActivity, including archive and
 * picker instances. Account-bearing hooks carry the actor already in scope;
 * shared app-wide Gate policy remains unchanged. The caller keeps private
 * selection hiding, page views, filter tabs, pin loops and its own mute reads.
 * Archive-only pin flags still end the existing main-list run until A17.
 */
public final class PurpleDialogsHooks {

    public static final int SELECTION_ID = 0x70750007;

    private static final long PREVIEW_DISMISS_MS = 300;

    private final DialogsActivity fragment;
    private final PurpleStripWatch stripWatch = new PurpleStripWatch();
    private ActionBarMenuSubItem workModeItem;

    public PurpleDialogsHooks(DialogsActivity fragment) {
        this.fragment = fragment;
    }

    /**
     * Resolves the preset before the first list is built, keeping native loading
     * and settings reads off the adapter's background diff path. The unchanged
     * sync offer is checked at this same activated-account creation point;
     * its own once-per-process/account and asynchronous bulletin behavior stay.
     */
    public void onFragmentCreate() {
        PurpleGate.ensureLoaded();
        org.telegram.messenger.purple.PurpleSyncAuto.sessionReady(fragment.getCurrentAccount());
        PurpleSyncOffer.checkOnLaunch(fragment, fragment.getCurrentAccount());
    }

    public void addEndPeek(org.telegram.ui.ActionBar.ActionBarMenu menu) {
        PurplePeekControls.addEndButton(menu);
    }

    public void addSelectionItem(ActionBarMenuItem otherItem) {
        workModeItem = otherItem.addSubItem(SELECTION_ID, R.drawable.msg_customize, LocaleController.getString(R.string.PurpleLists));
    }

    public void onSelectionItem(List<Long> selectedDialogs, Runnable hideActionMode) {
        if (!selectedDialogs.isEmpty()) {
            final long dialogId = selectedDialogs.get(0);
            hideActionMode.run();
            PurpleListBox.show(fragment, fragment.getCurrentAccount(), dialogId);
        }
    }

    public void updateSelectionItem(int count) {
        if (workModeItem != null) {
            workModeItem.setVisibility(count == 1 && PurpleListMenu.available()
                    ? View.VISIBLE
                    : View.GONE);
        }
    }

    /**
     * Adds the same list box entry above Pin in the avatar long-press preview.
     * The preview must close before the dialog is shown, so its existing 300ms
     * delay remains. The account is read from this owner inside the delayed
     * callback, exactly when the old fragment-field expression was evaluated.
     */
    public void addPreviewItem(
            ActionBarPopupWindow.ActionBarPopupWindowLayout previewMenu, long dialogId) {
        if (PurpleListMenu.available()) {
            ActionBarMenuSubItem workModeItem = new ActionBarMenuSubItem(fragment.getParentActivity(), true, false);
            workModeItem.setTextAndIcon(LocaleController.getString(R.string.PurpleLists), R.drawable.msg_customize);
            workModeItem.setMinimumWidth(160);
            workModeItem.setOnClickListener(e -> {
                fragment.finishPreviewFragment();
                AndroidUtilities.runOnUIThread(() ->
                        PurpleListBox.show(fragment, fragment.getCurrentAccount(), dialogId),
                        PREVIEW_DISMISS_MS);
            });
            previewMenu.addView(workModeItem);
        }
    }

    /**
     * Samples the same state only after the caller's updateFilterTabs guard.
     * Changed membership selects All chats through the caller's private views;
     * ordinary peek, schedule and list reloads leave identical tabs alone.
     * The caller also retains its already-selected-tab check before animation.
     */
    public boolean changed(int account) {
        return stripWatch.changed(account, PurpleGate.state());
    }

    /**
     * A scroll onto an already-selected tab can report indicator progress with
     * no incoming page. The original visibility/search predicate suppresses
     * every such frame, including the last one; the unchanged stock guard is
     * kept after this hook and the caller retains all page translation logic.
     */
    public boolean skipPageScroll(int account, int incomingVisibility, boolean searching) {
        return incomingVisibility != View.VISIBLE && !searching;
    }

    public ArrayList<TLRPC.Dialog> filterMain(
            int account, int folderId, ArrayList<TLRPC.Dialog> dialogs) {
        return PurpleFolders.filterMain(account, folderId, dialogs);
    }

    public int tabCount(int account, MessagesController.DialogFilter filter, int stockCount) {
        return PurpleGate.tabCount(account, filter, stockCount);
    }

    public boolean filtering(int account) {
        return PurpleGate.filtering(account);
    }

    public boolean foldersRestricted(int account) {
        return PurpleGate.foldersRestricted(account);
    }

    public boolean hidingArchive(int account) {
        return PurpleGate.hidingArchive(account);
    }

    public String menuLabel(int account) {
        return PurpleGate.menuLabel(account);
    }

    public void showPresetPicker() {
        PurplePresetPicker.show(fragment);
    }
}
