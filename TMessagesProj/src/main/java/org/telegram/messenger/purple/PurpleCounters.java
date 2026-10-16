package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;

public final class PurpleCounters {
    private PurpleCounters() {
    }

    public static void recountAll(int currentAccount) {
        recountAll(currentAccount, MessagesStorage.getInstance(currentAccount));
    }

    public static void recountAll(int currentAccount, MessagesStorage owner) {
        owner.getStorageQueue().postRunnable(() -> owner.resetAllUnreadCounters(false));
    }

    public static void logUncounted(PurpleUnreadMirror mirror) {
        if (mirror.uncounted() > 0) {
            FileLog.d("Purple: " + mirror.uncounted()
                    + " chats under a hide until left out of the folder counts");
        }
    }

    public static boolean holdMainDelta(int currentAccount, PurpleUnreadMirror mirror) {
        logUncounted(mirror);
        final boolean hold = mirror.unshown() > 0;
        if (hold) {
            PurpleGate.recountSoon(currentAccount, mirror.unshown());
        }
        return hold;
    }

    public static void logCounters(int currentAccount, PurpleUnreadMirror mirror,
            int unreadCount, int flags) {
        final int[][] contacts = mirror.bucket(0);
        final int[][] nonContacts = mirror.bucket(1);
        final int[][] bots = mirror.bucket(2);
        final int[][] channels = mirror.bucket(3);
        final int[][] groups = mirror.bucket(4);
        final int[][] communities = mirror.bucket(5);
        int stock = contacts[0][0] + nonContacts[0][0]
                + groups[0][0] + channels[0][0] + bots[0][0]
                + communities[0][0] + communities[1][0];
        if ((flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) == 0) {
            stock += contacts[0][1] + nonContacts[0][1]
                    + groups[0][1] + channels[0][1] + bots[0][1]
                    + communities[0][1] + communities[1][1];
        }
        PurpleGate.logCounters(unreadCount, stock);
    }
}
