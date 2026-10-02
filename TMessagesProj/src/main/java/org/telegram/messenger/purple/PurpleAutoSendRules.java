/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

final class PurpleAutoSendRules {
    private PurpleAutoSendRules() {
    }

    static final int NO_ACCOUNT = -1;

    static int firstAccount(boolean[] activated) {
        for (int account = 0; account < activated.length; ++account) {
            if (activated[account]) {
                return account;
            }
        }
        return NO_ACCOUNT;
    }

    static boolean postsFrom(int selectedAccount, boolean[] activated) {
        return selectedAccount != NO_ACCOUNT && selectedAccount == firstAccount(activated);
    }
}
