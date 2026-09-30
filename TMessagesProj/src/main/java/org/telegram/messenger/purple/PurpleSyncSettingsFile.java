/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * settings.toml as account sync sees it.
 *
 * Sync reads the file under stricter rules than the editor: a regular file,
 * never a symlink, at most 256 KiB (the editor allows 4 MiB). A file that
 * breaks a rule is Invalid, which sync treats as "cannot use", never as
 * absent. Writes go through {@link PurpleSettings}'s own replacement, so the
 * usual backup and gate reload happen, and are then read back under the same
 * rules. An import is recorded for the auto-send; a restore never arms it.
 *
 * Nothing calls this yet.
 */
public final class PurpleSyncSettingsFile {
    public static final int MAX_BYTES = 256 * 1024;

    public enum Status { Present, Absent, Invalid }

    public static final class Contents {
        public final Status status;
        /** The exact bytes when Present; empty otherwise. */
        public final byte[] bytes;
        public final boolean usingLastGood;

        Contents(Status status, byte[] bytes) {
            this(status, bytes, false);
        }

        Contents(Status status, byte[] bytes, boolean usingLastGood) {
            this.status = status;
            this.bytes = bytes;
            this.usingLastGood = usingLastGood;
        }

        /** Both describe the same usable file, or both say it is absent. */
        public boolean sameAs(Contents other) {
            return other != null
                    && status == other.status
                    && status != Status.Invalid
                    && Arrays.equals(bytes, other.bytes);
        }
    }

    public enum WriteStatus {
        /** The file now holds exactly the bytes. */
        Written,
        /** Refused or failed before the file changed. */
        WriteFailed,
        /** The replacement ran but the file did not read back as written. */
        ReadBackMismatch,
        Changed,
    }

    public static final class WriteResult {
        public final WriteStatus status;
        public final Contents readBack;

        WriteResult(WriteStatus status, Contents readBack) {
            this.status = status;
            this.readBack = readBack;
        }

        public boolean wrote() {
            return status == WriteStatus.Written
                    || status == WriteStatus.ReadBackMismatch;
        }
    }

    interface Writer {
        boolean store(byte[] bytes, String reason, boolean fromImport);
    }

    private PurpleSyncSettingsFile() {
    }

    public static Contents read() {
        final Contents file = read(PurpleSettings.settingsFile());
        return new Contents(file.status, file.bytes, PurpleGate.usedLastGood());
    }

    /**
     * Replaces settings.toml and reads it back.
     *
     * @param fromImport true when the bytes came from another device, so the
     *                   auto-send does not post them straight back; false for
     *                   a restore of this device's own copy
     */
    public static WriteResult write(byte[] bytes, String reason,
            boolean fromImport) {
        return write(PurpleSettings.settingsFile(), bytes, reason, fromImport,
                PurpleSettings::storeForSync);
    }

    public static WriteResult replace(Contents expected, byte[] bytes,
            String reason, boolean fromImport) {
        return replace(PurpleSettings.settingsFile(), expected, bytes, reason,
                fromImport, PurpleSettings::storeForSync);
    }

    static WriteResult replace(File file, Contents expected, byte[] bytes,
            String reason, boolean fromImport, Writer writer) {
        if (expected == null || !read(file).sameAs(expected)) {
            return new WriteResult(WriteStatus.Changed, null);
        }
        return write(file, bytes, reason, fromImport, writer);
    }

    static Contents read(File file) {
        final StructStat stat;
        try {
            stat = Os.lstat(file.getPath());
        } catch (ErrnoException e) {
            return (e.errno == OsConstants.ENOENT)
                    ? new Contents(Status.Absent, new byte[0])
                    : invalid();
        }
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size > MAX_BYTES) {
            return invalid();
        }
        try (FileInputStream stream = new FileInputStream(file)) {
            final byte[] buffer = new byte[MAX_BYTES + 1];
            int size = 0;
            while (size < buffer.length) {
                final int count = stream.read(buffer, size,
                        buffer.length - size);
                if (count < 0) {
                    break;
                }
                size += count;
            }
            if (size > MAX_BYTES) {
                return invalid();
            }
            return new Contents(Status.Present, Arrays.copyOf(buffer, size));
        } catch (IOException | SecurityException e) {
            return invalid();
        }
    }

    static WriteResult write(File file, byte[] bytes, String reason,
            boolean fromImport, Writer writer) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            return new WriteResult(WriteStatus.WriteFailed, null);
        }
        try {
            if (!writer.store(bytes, reason, fromImport)) {
                return new WriteResult(WriteStatus.WriteFailed, null);
            }
        } catch (RuntimeException e) {
            // Thrown after the replacement, by the reload or the auto-send
            // bookkeeping, as easily as before it: only the file can say
            // which, so it is read back like any finished write.
            FileLog.e(e);
        }
        final Contents readBack = read(file);
        return new WriteResult(
                (readBack.status == Status.Present
                        && Arrays.equals(readBack.bytes, bytes))
                        ? WriteStatus.Written
                        : WriteStatus.ReadBackMismatch,
                readBack);
    }

    private static Contents invalid() {
        return new Contents(Status.Invalid, new byte[0]);
    }
}
