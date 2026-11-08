package org.telegram.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;

public final class PurpleSettingsRow {

    public static final int ROW_ID = 0x70750005;
    private static final int ALIGNMENT_TAG = 0x70750524;

    public static org.telegram.ui.Components.UItem entry(int account) {
        return SettingsActivity.SettingCell.Factory.of(ROW_ID,
                org.telegram.ui.Components.IconBackgroundColors.PURPLE.top,
                org.telegram.ui.Components.IconBackgroundColors.PURPLE.bottom, R.drawable.purple_settings,
                LocaleController.getString(R.string.PurpleSettingsTitle), subtitle(account));
    }

    public static void alignText(int icon, TextView title, TextView subtitle) {
        for (TextView text : new TextView[]{title, subtitle}) {
            final int[] nativeAlignment = (int[]) text.getTag(ALIGNMENT_TAG);
            if (icon != R.drawable.purple_settings) {
                if (nativeAlignment != null) {
                    text.setGravity(nativeAlignment[0]);
                    text.setTextAlignment(nativeAlignment[1]);
                    text.setTag(ALIGNMENT_TAG, null);
                }
                continue;
            }
            if (nativeAlignment == null) {
                text.setTag(ALIGNMENT_TAG, new int[]{text.getGravity(), text.getTextAlignment()});
            }
            text.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            text.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        }
    }

    private PurpleSettingsRow() {
    }

    /**
     * Labels the Settings entry with the running preset or the feature text.
     * Settings can ask first, so the gate is loaded before reading its state.
     * The row between Folders and Devices opens all Purple settings,
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
