/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The backups a whole-file write of settings.toml takes before it replaces
 * the file.
 *
 * Kept apart from {@link PurpleSettings}, and free of Android, so that the
 * host test can check the order the backups are taken in.
 */
final class PurpleSettingsBackups {
    private PurpleSettingsBackups() {
    }

    /**
     * Copies {@code target}, when it exists, to {@code backup}, and for an
     * import to {@code importBackup} first.
     *
     * The import backup goes first because an import cannot go ahead without
     * it: when it fails, the write stops with the ordinary backup untouched
     * as well, rather than with one slot already refreshed for a write that
     * never happened. It is written through a temporary sibling and a rename,
     * so a failure leaves the previous import backup whole. The ordinary
     * backup stays a plain copy for every caller.
     *
     * @throws IOException when either backup could not be written; the caller
     *                     must then leave {@code target} alone
     */
    static void take(File target, File backup, File importBackup, boolean fromImport)
            throws IOException {
        if (!target.exists()) {
            return;
        }
        if (fromImport) {
            copyAtomic(target, importBackup);
        }
        copy(target, backup);
    }

    static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    static void copyAtomic(File from, File to) throws IOException {
        final File temp = new File(to.getParentFile(), to.getName() + ".tmp");
        try {
            copy(from, temp);
            if (!temp.renameTo(to)) {
                throw new IOException("Could not replace " + to);
            }
        } finally {
            temp.delete();
        }
    }
}
