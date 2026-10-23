package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PurpleGlobalChoiceFiles {
    static final int LIMIT = 4 * 1024 * 1024;
    interface Current { boolean current(); }
    interface Physical {
        Mark mark(File file, boolean directory) throws Exception;
        boolean write(File temporary, Mark owned, byte[] bytes) throws Exception;
    }
    static final class Mark {
        final String identity;
        final String revision;
        final int mode;
        Mark(String identity, String revision, int mode) { this.identity = identity; this.revision = revision; this.mode = mode; }
        boolean missing() { return identity.equals("missing"); }
        boolean same(Mark other) { return other != null && identity.equals(other.identity) && mode == other.mode && revision.equals(other.revision); }
    }
    static final class Snapshot {
        final File file;
        final boolean directory;
        final int limit;
        final Mark mark;
        final byte[] bytes;
        Snapshot(File file, boolean directory, int limit, Physical physical) throws Exception {
            this.file = file;
            this.directory = directory;
            this.limit = limit;
            mark = physical.mark(file, directory);
            bytes = directory || mark.missing() ? null : read(file, limit);
            if (!mark.same(physical.mark(file, directory))) throw new IllegalStateException("input changed during read");
        }
        boolean current(Physical physical) {
            try {
                Snapshot now = new Snapshot(file, directory, limit, physical);
                return mark.same(now.mark) && Arrays.equals(bytes, now.bytes);
            } catch (Exception e) { return false; }
        }
    }
    static final class Publication {
        boolean changed;
        Snapshot local;
    }
    static byte[] read(File file, int limit) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() > limit - n) throw new IllegalStateException("input exceeds bound");
                out.write(buffer, 0, n);
            }
            byte[] bytes = out.toByteArray();
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return bytes;
        }
    }
    static Publication replace(File target, byte[] bytes, Physical physical, Current current) {
        Publication result = new Publication();
        File temporary = null;
        Mark owned = null;
        try {
            if (bytes == null || bytes.length > LIMIT || !current.current()) return result;
            temporary = File.createTempFile("choice-", ".tmp", target.getParentFile());
            owned = physical.mark(temporary, false);
            if (owned.missing() || !current.current() || !physical.write(temporary, owned, bytes)) return result;
            Snapshot written = new Snapshot(temporary, false, LIMIT, physical);
            if (!owned.identity.equals(written.mark.identity) || (written.mark.mode & 0777) != 0600 || !Arrays.equals(bytes, written.bytes)
                    || !current.current() || !written.current(physical) || !temporary.renameTo(target)) return result;
            result.changed = true;
            Snapshot actual = new Snapshot(target, false, LIMIT, physical);
            if (owned.identity.equals(actual.mark.identity) && Arrays.equals(bytes, actual.bytes)) result.local = actual;
        } catch (Exception e) {
            return result;
        } finally {
            if (temporary != null && !result.changed && owned != null) {
                try { if (owned.identity.equals(physical.mark(temporary, false).identity)) temporary.delete(); }
                catch (Exception e) { }
            }
        }
        return result;
    }
    static native boolean writeNative(String path, String ownedIdentity, byte[] bytes);
    private PurpleGlobalChoiceFiles() { }
}
