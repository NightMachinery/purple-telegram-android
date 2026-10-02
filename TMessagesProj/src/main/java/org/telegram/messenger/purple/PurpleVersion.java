/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

public final class PurpleVersion {
    public static final String VERSION = "1.0.0";

    private PurpleVersion() {
    }

    public static String line() {
        return "Purple " + VERSION + " (" + BuildConfig.PURPLE_GIT_HASH
                + ", core " + BuildConfig.PURPLE_CORE_HASH + ")";
    }

    public static void addTo(BaseFragment fragment, TextView telegramVersion) {
        final CharSequence telegram = telegramVersion.getText();
        telegramVersion.setText(TextUtils.isEmpty(telegram) ? line() : telegram + "\n" + line());
        telegramVersion.setOnLongClickListener(v -> copy(fragment));
    }

    public static TextView footer(BaseFragment fragment, Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(fragment.getThemedColor(Theme.key_windowBackgroundWhiteGrayText4));
        view.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(10),
                AndroidUtilities.dp(21), AndroidUtilities.dp(10));
        view.setGravity(Gravity.CENTER);
        view.setBackground(Theme.createSelectorDrawable(
                fragment.getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        view.setText(line());
        view.setOnClickListener(v -> copy(fragment));
        view.setOnLongClickListener(v -> copy(fragment));
        return view;
    }

    private static boolean copy(BaseFragment fragment) {
        if (AndroidUtilities.addToClipboard(line()) && BulletinFactory.canShowBulletin(fragment)) {
            BulletinFactory.of(fragment)
                    .createCopyBulletin(LocaleController.getString(R.string.TextCopied))
                    .show();
        }
        return true;
    }
}
