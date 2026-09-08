/*
 * This is the source code of Purple Telegram for Android.
 *
 * The one colour the fork paints outside its own screens: the tint of a
 * notification's small icon. Everything else on a notification comes from
 * the message; this is the only place the app's own identity shows.
 */

package org.telegram.messenger.purple;

import org.telegram.ui.LauncherIconController;

public final class PurpleAccent {

    /** Upstream's notification tint, kept for the stock icon. */
    private static final int STOCK = 0xff11acfa;

    /** The purple icon's centre colour, from icon_purple_background_sa.xml. */
    private static final int PURPLE = 0xff8e44e8;

    // Asked on every notification, and answering means a package-manager
    // call, so the answer is kept until the icon is chosen again. Null is
    // "not asked yet".
    private static volatile Integer cached;

    private PurpleAccent() {
    }

    /**
     * The colour a notification's icon is tinted with: purple under the
     * fork's own launcher icon, upstream's blue under any other. A phone
     * showing the purple icon on the home screen and a blue one in the
     * shade looked like two apps, which is the thing the icon exists to
     * avoid.
     */
    public static int notification() {
        Integer value = cached;
        if (value == null) {
            boolean purple;
            try {
                purple = LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.PURPLE);
            } catch (Exception e) {
                purple = false;
            }
            value = purple ? PURPLE : STOCK;
            cached = value;
        }
        return value;
    }

    /** Called when the launcher icon changes, so the next notification asks again. */
    public static void invalidate() {
        cached = null;
    }
}
