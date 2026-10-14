package org.telegram.messenger.purple;

import android.os.SystemClock;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

public final class PurpleLocalPremium {
    private PurpleLocalPremium() {
    }

    public static boolean on() {
        return PurpleGate.localPremium();
    }

    public static boolean withholdSponsored(int currentAccount, long dialogId) {
        if (on()) {
            FileLog.d("Purple: sponsored for " + dialogId + ": not requested (local premium).");
            return true;
        }
        FileLog.d("Purple: sponsored for " + dialogId + ": requested.");
        return false;
    }

    public static MessagesController.SponsoredMessagesInfo sponsoredForRemoval(
            int currentAccount, MessagesController owner, long dialogId) {
        if (!on()) {
            return owner.getSponsoredMessages(dialogId);
        }
        final MessagesController.SponsoredMessagesInfo info = owner.getCachedSponsoredMessages(dialogId);
        return info != null && (info.loading
                || Math.abs(SystemClock.elapsedRealtime() - info.loadTime) <= 5 * 60 * 1000)
                ? info : null;
    }

    public static boolean unlockLockedFolders(int currentAccount, MessagesController owner) {
        if (!on() || UserConfig.getInstance(currentAccount).isPremium()) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < owner.dialogFilters.size(); i++) {
            if (owner.dialogFilters.get(i).locked) {
                owner.dialogFilters.get(i).locked = false;
                changed = true;
            }
        }
        logFolderLocks(currentAccount, owner);
        if (changed) {
            NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.dialogFiltersUpdated);
        }
        return true;
    }

    public static void logFolderLocks(int currentAccount, MessagesController owner) {
        if (owner.dialogFilters.size() - 1 > owner.dialogFiltersLimitDefault) {
            int locked = 0;
            for (int i = 0; i < owner.dialogFilters.size(); i++) {
                if (owner.dialogFilters.get(i).locked) {
                    ++locked;
                }
            }
            FileLog.d("Purple: " + (owner.dialogFilters.size() - 1) + " folders against a limit of "
                    + owner.dialogFiltersLimitDefault + ", " + locked + " locked"
                    + (on() ? " (local premium)." : "."));
        }
    }
}
