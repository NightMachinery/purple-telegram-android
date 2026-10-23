package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;

public final class PurpleSettingsRow {

    public static final int ROW_ID = 0x70750005;

    private PurpleSettingsRow() {
    }

    /**
     * Labels the Settings entry with the running preset or the feature text.
     * Settings can ask first, so the gate is loaded before reading its state.
     * The gray row between Folders and Devices opens all Purple settings,
     * while the chat-list menu remains the one-tap preset picker.
     */
    public static CharSequence subtitle(int account) {
        PurpleGate.ensureLoaded();
        if (org.telegram.messenger.purple.PurpleWorkMode.stock(account)) return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PurpleWorkModeOffAccount);
        final PurpleCore.Loaded state = PurpleGate.state();
        if (state == null || state.normal) {
            return LocaleController.getString(R.string.PurpleWorkModeInfo);
        }
        return state.title;
    }
}
