/*
 * This is the source code of Purple Telegram for Android.
 *
 * Reloads settings.toml when it changes on disk, so an edit lands without a
 * restart. The desktop fork's purple_config.cpp does the same job with a
 * QFileSystemWatcher; this is that, in Android's shape.
 */

package org.telegram.messenger.purple;

import android.os.FileObserver;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.File;

public final class PurpleWatcher {

    /**
     * Held deliberately. A FileObserver that is garbage collected stops
     * delivering events, silently, which is the classic way this feature
     * appears to work in testing and then does nothing in the field.
     */
    private static FileObserver observer;

    /**
     * CREATE and MOVED_TO cover a file arriving; CLOSE_WRITE covers one edited
     * in place; DELETE and MOVED_FROM cover it going away, which matters as
     * much as the rest - it is what takes down the "running from the last good
     * copy" warning once the real file comes back, and what puts it up when the
     * file vanishes.
     */
    private static final int EVENTS = FileObserver.CLOSE_WRITE
            | FileObserver.MOVED_TO
            | FileObserver.MOVED_FROM
            | FileObserver.CREATE
            | FileObserver.DELETE;

    /**
     * How long to let the writes settle. One save is several events - a
     * truncate, a write, a close, or a temp file and a rename - and reloading
     * on each would parse a half-written file and log a parse error the user
     * never caused.
     */
    private static final long QUIET_MS = 400;

    private static final Runnable RELOAD =
            () -> PurpleGate.reload("settings.toml changed on disk");

    private PurpleWatcher() {
    }

    /**
     * Starts watching, once. Safe to call from anywhere; later calls do nothing.
     */
    public static synchronized void start() {
        if (observer != null) {
            return;
        }
        final File dir = PurpleSettings.dir();
        try {
            // The directory, never the file. An import - and most editors -
            // replace settings.toml through a temp file and a rename rather
            // than rewriting it in place, and a watch on the old inode goes
            // deaf the moment that happens, without saying so.
            //
            // The String constructor rather than the File one because that is
            // the one this app's minimum API has.
            observer = new FileObserver(dir.getAbsolutePath(), EVENTS) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null || !PurpleSettings.FILE_NAME.equals(path)) {
                        // state.toml, settings.toml.good and the ".tmp" siblings
                        // of both are ours. reload() writes the second of those,
                        // so reacting to it would be a loop.
                        return;
                    }
                    // Posting to the UI thread both debounces (the handler drops
                    // the pending one) and serialises this against the reload an
                    // import or a preset switch is doing. An import reloads
                    // twice as a result, once for its own write and once for
                    // this - idempotent, and the log line says which is which.
                    AndroidUtilities.cancelRunOnUIThread(RELOAD);
                    AndroidUtilities.runOnUIThread(RELOAD, QUIET_MS);
                }
            };
            observer.startWatching();
        } catch (Exception e) {
            // A watch is a convenience: without it the file is still read at
            // startup, on import and on a preset switch, which is what this
            // build did before. Losing it must not take the app with it.
            observer = null;
            FileLog.e(e);
        }
    }
}
