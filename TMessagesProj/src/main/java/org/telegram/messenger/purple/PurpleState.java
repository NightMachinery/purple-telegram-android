/*
 * This is the source code of Purple Telegram for Android.
 *
 * state.toml is the machine-owned half of the Work Mode configuration: which
 * preset is active, what put it there, and the last resolution that worked. It
 * is a separate file from settings.toml on purpose - state churns constantly,
 * and rewriting it must never touch the mtime of the file the user edits by
 * hand.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.IOException;

public final class PurpleState {

    public static final String FILE_NAME = "state.toml";

    /**
     * Largest state.toml we are willing to read. The file is ours and stays
     * small; anything past this is a corruption we should not try to parse.
     */
    public static final long MAX_SIZE = 256 * 1024;

    private PurpleState() {
    }

    /** Where the state lives, beside the settings the user imported. */
    public static File stateFile() {
        return new File(PurpleSettings.dir(), FILE_NAME);
    }

    /**
     * The current state.toml.
     *
     * @return its bytes, or null when the file is absent, too big, or unreadable
     */
    public static byte[] read() {
        final File file = stateFile();
        if (!file.exists() || file.length() > MAX_SIZE) {
            return null;
        }
        try {
            return PurpleSettings.readAll(file);
        } catch (IOException e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Replaces state.toml with {@code utf8}, atomically.
     *
     * No backup, unlike settings.toml: state is derived from the settings and
     * from what the user last chose, so a lost copy costs a preset selection
     * rather than a file that was typed by hand.
     *
     * @return whether the file now holds those bytes
     */
    public static boolean write(byte[] utf8) {
        if (utf8 == null) {
            return false;
        }
        return PurpleSettings.writeAtomic(stateFile(), utf8);
    }
}
