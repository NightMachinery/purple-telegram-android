/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.Locale;

/**
 * Account guards and display labels for settings document imports.
 */
final class PurpleSettingsImportRules {
    private PurpleSettingsImportRules() {
    }

    static final int NO_ACCOUNT = -1;

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
