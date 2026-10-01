/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.Locale;

/**
 * The decisions behind the Saved Messages import offer, kept free of Android
 * so that the host test can check them.
 */
final class PurpleSyncOfferRules {
    private PurpleSyncOfferRules() {
    }

    static final int NO_ACCOUNT = -1;

    enum Verdict {
        OFFER,
        INACTIVE_ACCOUNT,
        ALREADY_OFFERED,
        NOT_NEWER
    }

    static Verdict judge(int account, int activeAccount, boolean manual,
                         int messageId, long messageDate, int lastOffered, long localStamp) {
        if (account != activeAccount) {
            return Verdict.INACTIVE_ACCOUNT;
        }
        if (!manual && messageId <= lastOffered) {
            return Verdict.ALREADY_OFFERED;
        }
        if (messageDate <= localStamp) {
            return Verdict.NOT_NEWER;
        }
        return Verdict.OFFER;
    }

    static boolean mayImport(int sourceAccount, int activeAccount) {
        return sourceAccount == NO_ACCOUNT || sourceAccount == activeAccount;
    }

    /**
     * The name, with the public username when there is one, and null when
     * there is neither. Never a phone number.
     */
    static String accountLabel(String name, String username, String withUsername) {
        final String trimmedName = name == null ? "" : name.trim();
        final boolean hasUsername = username != null && !username.isEmpty();
        if (trimmedName.isEmpty()) {
            return hasUsername ? "@" + username : null;
        }
        return hasUsername
                ? String.format(Locale.ROOT, withUsername, trimmedName, username)
                : trimmedName;
    }
}
