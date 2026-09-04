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
}
