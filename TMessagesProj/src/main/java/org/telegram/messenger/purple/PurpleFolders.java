package org.telegram.messenger.purple;

import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Keeps the current main-list filtering rule at its existing call points.
 * The caller supplies the actor account, folder id and model list it already
 * selected. Only folder zero is filtered; archive lists keep their original
 * object and folder tabs keep their existing caller-owned bypasses.
 */
public final class PurpleFolders {

    private PurpleFolders() {
    }

    public static ArrayList<TLRPC.Dialog> filterMain(
            int account, int folderId, ArrayList<TLRPC.Dialog> dialogs) {
        return folderId == 0 ? PurpleGate.filter(account, dialogs) : dialogs;
    }

    public static boolean hiddenFromLists(int currentAccount, MessagesController owner,
            TLRPC.Dialog dialog) {
        return !owner.isPromoDialog(dialog.id, false)
                && PurpleGate.hiddenEverywhere(currentAccount, dialog);
    }

    public static ArrayList<MessagesController.DialogFilter> shownFilters(int currentAccount,
            ArrayList<MessagesController.DialogFilter> raw) {
        return PurpleGate.shownFilters(raw);
    }

    public static boolean hidingArchive(int currentAccount) {
        return PurpleGate.hidingArchive();
    }

    public static boolean hidingEverywhere(int currentAccount) {
        return PurpleGate.hidingEverywhere();
    }

    public static boolean foldersRestricted(int currentAccount) {
        return PurpleGate.foldersRestricted();
    }

    public static void refillViewPinsIfNeeded(int currentAccount) {
        PurpleGate.refillViewPinsIfNeeded();
    }
}
