/*
 * This is the source code of Purple Telegram for Android.
 *
 * The handful of defaults this fork decides differently from upstream, in one
 * place so they can be found together - see docs/purple/defaults.md.
 *
 * Each of these is a *default* and nothing more: a value the user has never
 * chosen. The moment they choose, their choice is written to a preference and
 * wins from then on, exactly as it does upstream.
 *
 * These are compile-time constants, so they are inlined at every use and cost
 * nothing on the early startup paths that read them.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

public final class PurpleDefaults {

    /**
     * The background connection is on until the user says otherwise.
     *
     * There is no FCM push in this fork and no way to get it back, so the
     * background connection is the only way a notification ever arrives.
     * Upstream defaults it off because upstream has push; here that default
     * means a fresh install is silently unable to notify.
     *
     * This is deliberately *not* expressed as the default of the
     * `backgroundConnection` preference. That preference is Telegram's own:
     * `background_connection` arrives in the server-pushed app config and is
     * written straight into it (MessagesController.applyAppConfig), so a
     * default placed there is overwritten on the first config fetch.
     *
     * Three places have to agree on this, because each works it out for
     * itself: ConnectionsManager.isPushConnectionEnabled(), which decides what
     * actually happens, and the two sites in NotificationsSettingsActivity
     * that draw the checkbox and read it back before writing the toggle. If
     * they disagree the switch renders "off" while the connection runs, and
     * tapping it to turn it on leaves it on.
     */
    public static final boolean PUSH_CONNECTION = true;

    /**
     * The Keep-Alive Service is on until the user says otherwise.
     *
     * Same reason as the background connection: with no push, the process
     * has to be alive for a notification to arrive, and this is the one
     * switch that asks the OS to bring it back after killing it. It is a
     * sticky service and a relaunch broadcast, not a foreground service -
     * NotificationsService never calls startForeground - so it costs no
     * permanent notification, which is the cost it is usually assumed to
     * carry.
     *
     * Upstream's fallback is `keepAliveService`, a flag the server sets for
     * the handful of vendors known to kill background processes, and false
     * for everyone else. It is still honoured, but it can no longer turn the
     * default off. Three places read the `pushService` key with this as the
     * fallback: ApplicationLoader.startPushService(), which decides what
     * happens, and the two sites in NotificationsSettingsActivity that draw
     * the switch and read it back before toggling it.
     */
    public static final boolean KEEP_ALIVE_SERVICE = true;

    /**
     * The Archive row is not at the top of the chat list until the user puts
     * it there.
     *
     * The desktop fork already does this. Unlike the desktop, the Android
     * value is written only when the row is actually swiped, so changing the
     * default here also reaches installs that have never touched it.
     */
    public static final boolean ARCHIVE_HIDDEN = true;

    private PurpleDefaults() {
    }

    /**
     * The app's own name, read from Android resources, where the standalone
     * module's purple_app_name.xml overlays upstream's AppName and AppNameBeta.
     *
     * LocaleController.getStringV2 asks here first. Both of its own sources
     * carry Telegram's name: the server's language pack, and the lookup asset
     * upstream's TelegramStringsTask builds from strings.xml files only. A call
     * may name the string by key, by resource id or both, since upstream is
     * moving callers to getString(R.string.AppName) with no key, so either one
     * matches. Returns null for every other string, and never null for these
     * two, so the cloud lookup is never reached for them.
     */
    public static String ownAppName(String key, int res) {
        final int own;
        if (res == R.string.AppName || res == 0 && "AppName".equals(key)) {
            own = R.string.AppName;
        } else if (res == R.string.AppNameBeta || res == 0 && "AppNameBeta".equals(key)) {
            own = R.string.AppNameBeta;
        } else if ("AppName".equals(key) || "AppNameBeta".equals(key)) {
            own = res;
        } else {
            return null;
        }
        try {
            return ApplicationLoader.applicationContext.getString(own);
        } catch (Exception e) {
            FileLog.e(e);
            return "LOC_ERR:" + (key != null ? key : own);
        }
    }

    public static boolean pushServiceDefault() {
        return KEEP_ALIVE_SERVICE
                || MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false);
    }
}
