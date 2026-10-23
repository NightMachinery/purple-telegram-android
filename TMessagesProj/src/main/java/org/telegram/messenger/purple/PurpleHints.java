package org.telegram.messenger.purple;

import java.util.ArrayList;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaDataController;
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
        return PurpleGate.hidingFromSuggestions(currentAccount);
    }

    public static void removeHiddenTopPeers(int currentAccount,
            ArrayList<TLRPC.TL_topPeer> topPeers) {
        final ArrayList<TLRPC.TL_topPeer> visible = PurpleGate.visibleTopPeers(currentAccount, topPeers);
        topPeers.clear();
        topPeers.addAll(visible);
    }

    public static ArrayList<TLRPC.TL_topPeer> visibleHints(int currentAccount) {
        final ArrayList<TLRPC.TL_topPeer> all = MediaDataController.getInstance(currentAccount).hints;
        final boolean everywhere = PurpleGate.hidingEverywhere(currentAccount);
        final boolean suggestions = PurpleGate.hidingFromSuggestions(currentAccount);
        if (!everywhere && !suggestions) {
            return all;
        }
        final ArrayList<MessagesController.DialogFilter> folders =
                suggestions ? PurpleGate.suggestionFolders(currentAccount) : null;
        final ArrayList<TLRPC.TL_topPeer> out = new ArrayList<>(all.size());
        for (int a = 0, n = all.size(); a < n; a++) {
            final TLRPC.TL_topPeer peer = all.get(a);
            final long dialogId = DialogObject.getPeerDialogId(peer.peer);
            if (everywhere && PurpleGate.hiddenEverywhere(currentAccount, dialogId)) {
                continue;
            }
            if (PurpleGate.hiddenFromSuggestions(currentAccount, dialogId, folders)) {
                continue;
            }
            out.add(peer);
        }
        return out;
    }

    public static boolean hiddenSearchResult(int currentAccount, Object obj) {
        return PurpleGate.hiddenEverywhere(currentAccount, searchDialogId(obj));
    }

    private static long searchDialogId(Object obj) {
        if (obj instanceof TLRPC.User) {
            return ((TLRPC.User) obj).id;
        } else if (obj instanceof TLRPC.Chat) {
            return -((TLRPC.Chat) obj).id;
        } else if (obj instanceof TLRPC.EncryptedChat) {
            return DialogObject.makeEncryptedDialogId(((TLRPC.EncryptedChat) obj).id);
        }
        return 0;
    }

    public static ArrayList<MessagesController.DialogFilter> suggestionFolders(int currentAccount) {
        return PurpleGate.suggestionFolders(currentAccount);
    }

    public static boolean hiddenFromSuggestions(int currentAccount, long dialogId, ArrayList<MessagesController.DialogFilter> folders) {
        return PurpleGate.hiddenFromSuggestions(currentAccount, dialogId, folders);
    }

    public static boolean hiddenEverywhere(int currentAccount, long dialogId) {
        return PurpleGate.hiddenEverywhere(currentAccount, dialogId);
    }

    public static boolean hiddenEverywhere(int currentAccount, TLRPC.Dialog dialog) {
        return PurpleGate.hiddenEverywhere(currentAccount, dialog);
    }

    public static boolean recommendedChannelsShown(int currentAccount) {
        if (!PurpleGate.filtering(currentAccount)) {
            return true;
        }
        final PurpleCore.Loaded state = PurpleGate.state();
        return state != null && state.recommendedChannels;
    }
}
