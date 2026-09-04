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
