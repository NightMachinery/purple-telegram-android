package org.telegram.messenger.purple;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PurpleAccountFileSnapshot {
    enum Status { Missing, Present, ReadError }
    final Status status;
    final byte[] bytes;
    private int limit = LIMIT;
    private static final int LIMIT = 4 * 1024 * 1024;

    private PurpleAccountFileSnapshot(Status status, byte[] bytes) {
        this.status = status;
        this.bytes = bytes;
    }

    static PurpleAccountFileSnapshot read(File file) { return read(file, LIMIT); }

    static PurpleAccountFileSnapshot read(File file, int limit) {
        PurpleAccountFileSnapshot result = readBounded(file, limit);
        result.limit = limit;
        return result;
    }

    private static PurpleAccountFileSnapshot readBounded(File file, int limit) {
        try {
            if (!plainParents(file.getParentFile())) return error();
            StructStat stat = Os.lstat(file.getPath());
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size > limit) return error();
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() > limit - count) return error();
                    out.write(buffer, 0, count);
                }
                byte[] bytes = out.toByteArray();
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
                return new PurpleAccountFileSnapshot(Status.Present, bytes);
            }
        } catch (ErrnoException e) {
            return e.errno == OsConstants.ENOENT
                    ? new PurpleAccountFileSnapshot(Status.Missing, null) : error();
        } catch (Exception e) {
            return error();
        }
    }

    static boolean plainParents(File dir) throws Exception {
        File base = PurpleAccountFiles.root().getParentFile();
        while (dir != null && !dir.equals(base)) {
            try {
                if (!OsConstants.S_ISDIR(Os.lstat(dir.getPath()).st_mode)) return false;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.ENOENT) return false;
            }
            dir = dir.getParentFile();
        }
        return dir != null;
    }

    static int directory(File dir) {
        try {
            if (!plainParents(dir.getParentFile())) return -1;
            return OsConstants.S_ISDIR(Os.lstat(dir.getPath()).st_mode) ? 1 : -1;
        } catch (ErrnoException e) {
            return e.errno == OsConstants.ENOENT ? 0 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    boolean same(PurpleAccountFileSnapshot other) {
        return other != null && status != Status.ReadError && status == other.status
                && Arrays.equals(bytes, other.bytes);
    }

    boolean matches(File file) { return same(read(file, limit)); }
    boolean sameRead(File file) {
        PurpleAccountFileSnapshot now = read(file, limit);
        return status == Status.ReadError ? now.status == Status.ReadError : same(now);
    }

    static boolean replace(File file, PurpleAccountFileSnapshot expected, byte[] bytes) {
        if (expected == null || bytes == null || bytes.length > expected.limit || !expected.matches(file)) return false;
        File temp = null;
        try {
            File parent = file.getParentFile();
            if (!plainParents(parent)) return false;
            if (!parent.isDirectory() && !parent.mkdirs()) return false;
            Os.chmod(PurpleAccountFiles.root().getPath(), 0700);
            Os.chmod(parent.getPath(), 0700);
            temp = File.createTempFile("owner-", ".tmp", parent);
            Os.chmod(temp.getPath(), 0600);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (!expected.matches(file) || !temp.renameTo(file)) return false;
            PurpleAccountFileSnapshot actual = read(file, expected.limit);
            return actual.status == Status.Present && Arrays.equals(bytes, actual.bytes);
        } catch (Exception e) {
            return false;
        } finally {
            if (temp != null) temp.delete();
        }
    }

    private static PurpleAccountFileSnapshot error() {
        return new PurpleAccountFileSnapshot(Status.ReadError, null);
    }
}
