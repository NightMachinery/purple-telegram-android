/*
 * This is the source code of Purple Telegram for Android.
 *
 * The log line that says whether whole-chat translation is offered for a
 * chat, and why. Local Premium unlocks the feature (docs/purple/premium.md in
 * the desktop repository), and from outside the app nothing shows whether it
 * did: the translate bar appears only after language detection, which upstream
 * never runs when the feature is withheld.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;

import java.util.HashMap;
import java.util.HashSet;

public final class PurpleTranslate {

    /** The dialogs already logged, per account, so each is logged once. */
    private static final HashMap<Integer, HashSet<Long>> logged = new HashMap<>();

    private PurpleTranslate() {
    }

    /**
     * Logs whether whole-chat translation is offered for a chat that has just
     * been opened, once per dialog and account. Called on the UI thread, from
     * the first onResume of the chat's {@code PurpleChatHooks}.
     *
     * It asks {@link TranslateController#isFeatureAvailable(long)} rather than
     * {@link TranslateController#isDialogTranslatable}, because that predicate
     * cannot report the withheld case: with the feature unavailable upstream
     * never runs language detection, so the dialog never becomes translatable
     * and the line would only ever appear in one direction - an absence
     * standing in for evidence, which is the trap the desktop repository's
     * docs/remote-build-and-test/readme.md warns about.
     */
    public static void logAvailability(int account, long dialogId) {
        HashSet<Long> dialogs = logged.get(account);
        if (dialogs == null) {
            dialogs = new HashSet<>();
            logged.put(account, dialogs);
        }
        if (!dialogs.add(dialogId)) {
            return;
        }
        final TranslateController translate =
                MessagesController.getInstance(account).getTranslateController();
        final boolean available = translate.isFeatureAvailable(dialogId);
        FileLog.d("Purple: translate for " + dialogId + ": "
                + (available
                    ? (UserConfig.getInstance(account).isPremium()
                        ? "available." : "available (local premium).")
                    : "withheld.")
                + " chat translate " + (translate.isChatTranslateEnabled() ? "on" : "off") + ".");
    }
}
