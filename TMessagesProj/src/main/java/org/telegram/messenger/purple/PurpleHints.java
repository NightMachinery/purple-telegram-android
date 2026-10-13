package org.telegram.messenger.purple;

import java.util.ArrayList;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

public final class PurpleHints {
    private PurpleHints() {
    }

    public static ArrayList<TLRPC.TL_topPeer> visibleTopPeers(int currentAccount,
            ArrayList<TLRPC.TL_topPeer> hints) {
        final boolean everywhere = PurpleFolders.hidingEverywhere(currentAccount);
        final boolean suggestions = hidingFromSuggestions(currentAccount);
        final ArrayList<MessagesController.DialogFilter> folders = suggestions
                ? PurpleGate.suggestionFolders(currentAccount)
                : null;
        if (!everywhere && !suggestions) {
            return hints;
        }
        final ArrayList<TLRPC.TL_topPeer> result = new ArrayList<>();
        for (int a = 0; a < hints.size(); a++) {
            final long dialogId = DialogObject.getPeerDialogId(hints.get(a).peer);
            if (everywhere && PurpleGate.hiddenEverywhere(currentAccount, dialogId)) {
                continue;
            }
            if (PurpleGate.hiddenFromSuggestions(currentAccount, dialogId, folders)) {
                continue;
            }
            result.add(hints.get(a));
        }
        return result;
    }

    private static boolean hidingFromSuggestions(int currentAccount) {
        return PurpleGate.hidingFromSuggestions();
    }

    public static void removeHiddenTopPeers(int currentAccount,
            ArrayList<TLRPC.TL_topPeer> topPeers) {
        final ArrayList<TLRPC.TL_topPeer> visible = PurpleGate.visibleTopPeers(currentAccount, topPeers);
        topPeers.clear();
        topPeers.addAll(visible);
    }
}
