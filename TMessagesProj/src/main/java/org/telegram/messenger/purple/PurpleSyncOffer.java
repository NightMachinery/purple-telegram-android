/*
 * This is the source code of Purple Telegram for Android.
 *
 * What is left of the launch-time offer to import a newer settings.toml out of
 * Saved Messages: the watermark of the newest settings.toml this account sent.
 * The offer and its "check Saved Messages" button searched the chat, and Purple
 * sends no search requests (Magic Purple Sync), so both are gone; the chat's
 * own Import menu item and Sync across devices remain.
 */

package org.telegram.messenger.purple;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;

public final class PurpleSyncOffer {

    /** Newest settings.toml message this account has already been offered. */
    private static final String OFFERED_KEY = "purple_sync_offered_id";

    private PurpleSyncOffer() {
    }

    public static void noteSentMessage(int account, int serverId) {
        if (serverId <= 0) {
            return;
        }
        final SharedPreferences prefs = MessagesController.getMainSettings(account);
        if (serverId > prefs.getInt(OFFERED_KEY, 0)) {
            prefs.edit().putInt(OFFERED_KEY, serverId).apply();
        }
    }
}
