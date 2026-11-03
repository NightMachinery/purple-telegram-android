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
    static final int LIMIT = 4 * 1024 * 1024;

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
        File base = anchor();
        if (dir == null || !dir.equals(base) && !below(base.getAbsolutePath(), dir.getAbsolutePath())) return false;
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

    /**
     * The temporary {@link #replace} writes beside its target ({@code File.createTempFile}: the prefix, a number that
     * may be negative on Android, the suffix). A crash before its cleanup can leave one behind.
     */
    static boolean temporary(String name) { return name.matches("owner--?[0-9]+\\.tmp"); }
    private static final String TEMPORARY_PREFIX = "owner-", TEMPORARY_SUFFIX = ".tmp";

    static boolean replace(File file, PurpleAccountFileSnapshot expected, byte[] bytes) {
        if (expected == null || bytes == null || bytes.length > expected.limit || !expected.matches(file)) return false;
        File temp = null;
        try {
            File parent = file.getParentFile();
            if (!plainParents(parent)) return false;
            if (!parent.isDirectory() && !parent.mkdirs()) return false;
            Os.chmod(PurpleAccountFiles.root().getPath(), 0700);
            Os.chmod(parent.getPath(), 0700);
            temp = File.createTempFile(TEMPORARY_PREFIX, TEMPORARY_SUFFIX, parent);
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

    static final class Durable {
        final byte[] token, bytes;
        final String identity, parent;
        Durable(byte[] response) throws Exception {
            org.json.JSONObject value = new org.json.JSONObject(new String(response, StandardCharsets.UTF_8));
            String status = value.getString("status");
            if (!"Ready".equals(status)) throw new java.io.IOException(status);
            token = response;
            identity = value.getString("identity");
            parent = value.optString("parent");
            bytes = "missing".equals(identity) ? null : PurpleSyncRetainedBases.decode(value.getString("bytes"));
        }
        boolean same(Durable other) { return other != null && Arrays.equals(token, other.token); }
    }
    static void worker() throws java.io.IOException {
        if (android.os.Looper.getMainLooper() != null
                && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) throw new java.io.IOException("WrongThread");
    }
    /**
     * Whether the last component of {@code file} is a plain entry, not a symbolic link. Links above it are the
     * platform's: Android's files directory is /data/user/0/..., a link to /data/data/.... Comparing the whole canonical
     * path with the absolute one refused every retained base there (device run A4: the magic publish's commit and every
     * later store open failed with InvalidRetention).
     */
    static boolean notLink(File file) throws java.io.IOException {
        File absolute = file.getAbsoluteFile(), parent = absolute.getParentFile();
        return parent != null && absolute.getCanonicalFile().equals(new File(parent.getCanonicalFile(), absolute.getName()));
    }

    /**
     * The directory every durable path lies below: the app's files directory, the parent of
     * {@link PurpleAccountFiles#root()}. The native walk opens it and goes down from there, never from "/": Android
     * refuses an app any read of the root directory, which failed every durable operation with IoError (native check A3).
     */
    static File anchor() { return PurpleAccountFiles.root().getParentFile(); }

    /** Whether {@code path} names an entry strictly below {@code anchor}, both absolute, with no empty, "." or ".." part. */
    static boolean below(String anchor, String path) {
        if (anchor == null || path == null || anchor.length() < 2 || !anchor.startsWith("/") || anchor.endsWith("/")
                || !path.startsWith(anchor + "/")) return false;
        for (String part : path.substring(1).split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    /**
     * The guard in front of every durable list, open and walk: a path outside the anchor (in particular "/") is refused
     * with status PathOutsideRoot and one diagnostic line ({@code sync_chat path_refused reason=path_outside_root})
     * before anything is opened. The native layer refuses the same paths again with the same status.
     */
    static String inside(File file) throws java.io.IOException {
        File anchor = anchor();
        String path = file == null ? null : file.getAbsolutePath();
        if (anchor != null && below(anchor.getAbsolutePath(), path)) return path;
        PurpleDiagLog.event("sync_chat", "path_refused", PurpleDiagLog.reason("path_outside_root"));
        throw new java.io.IOException("PathOutsideRoot");
    }

    static Durable durable(String operation, File file, Durable expected, byte[] data, int limit, int fault) throws java.io.IOException {
        String path = inside(file);
        worker();
        PurpleCore.ensureLoaded();
        try {
            byte[] response = durableNative(operation, anchor().getAbsolutePath(), path, expected == null ? null : expected.token, data, limit, fault);
            if (response == null) throw new java.io.IOException("IoError");
            return new Durable(response);
        } catch (java.io.IOException e) { throw e; }
        catch (Exception e) { throw new java.io.IOException("IoError", e); }
    }
    static String durableDirectoryIdentity(File directory) throws java.io.IOException {
        String path = inside(directory);
        worker(); PurpleCore.ensureLoaded();
        try {
            byte[] response = durableNative("directoryRead", anchor().getAbsolutePath(), path, null, null, LIMIT, 0);
            org.json.JSONObject value = new org.json.JSONObject(new String(response, StandardCharsets.UTF_8));
            if (!"Ready".equals(value.getString("status"))) throw new java.io.IOException(value.getString("status"));
            return value.getString("identity");
        } catch (java.io.IOException e) { throw e; }
        catch (Exception e) { throw new java.io.IOException("IoError", e); }
    }
    static void durableDirectory(File directory) throws java.io.IOException {
        String path = inside(directory);
        worker(); PurpleCore.ensureLoaded();
        try {
            byte[] response = durableNative("directory", anchor().getAbsolutePath(), path, null, null, LIMIT, 0);
            org.json.JSONObject value = new org.json.JSONObject(new String(response, StandardCharsets.UTF_8));
            if (!"Ready".equals(value.getString("status"))) throw new java.io.IOException(value.getString("status"));
        } catch (java.io.IOException e) { throw e; }
        catch (Exception e) { throw new java.io.IOException("IoError", e); }
    }
    @androidx.annotation.Keep
    private static native byte[] durableNative(String operation, String anchor, String path, byte[] expected, byte[] data, int limit, int fault);

    private static PurpleAccountFileSnapshot error() {
        return new PurpleAccountFileSnapshot(Status.ReadError, null);
    }
}
