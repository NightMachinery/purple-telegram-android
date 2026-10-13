package org.telegram.messenger.purple;

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
}
