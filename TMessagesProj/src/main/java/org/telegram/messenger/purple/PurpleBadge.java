package org.telegram.messenger.purple;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

public final class PurpleBadge {

    private PurpleBadge() {
    }

    public static boolean onBadge(int account, TLRPC.Dialog dialog) {
        return PurpleGate.shown(account, dialog)
                && PurpleGate.countedForBadge(account, dialog.id);
    }

    public static int messages(int account, LongSparseArray<Integer> pushDialogs, int stockTotal) {
        return PurpleGate.badgeRebuilt(account) ? count(account, pushDialogs, true) : stockTotal;
    }

    public static int chats(int account, LongSparseArray<Integer> pushDialogs) {
        return PurpleGate.badgeRebuilt(account) ? count(account, pushDialogs, false) : pushDialogs.size();
    }

    private static int count(int account, LongSparseArray<Integer> pushDialogs, boolean messages) {
        int count = 0;
        for (int a = 0, n = pushDialogs.size(); a < n; a++) {
            final long dialogId = pushDialogs.keyAt(a);
            if (!PurpleGate.countedForBadge(account, dialogId)) {
                continue;
            }
            if (!messages) {
                count++;
                continue;
            }
            final Integer unread = pushDialogs.valueAt(a);
            if (unread == null || MessagesController.getInstance(account).isCommunity(dialogId)) {
                continue;
            } else if (MessagesController.getInstance(account).isForum(dialogId)) {
                count += unread > 0 ? 1 : 0;
            } else {
                count += unread;
            }
        }
        return count;
    }
}
