package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class PurplePeekSnapshot {
    public enum Status { Present, Missing, Invalid }

    public final Status status;
    private final byte[] contents;
    private final int maxBytes;

    private PurplePeekSnapshot(Status status, byte[] contents, int maxBytes) {
        this.status = status;
        this.contents = contents;
        this.maxBytes = maxBytes;
    }

    public static PurplePeekSnapshot read(File file, int maxBytes) {
        try {
            if (!file.exists()) return new PurplePeekSnapshot(Status.Missing, null, maxBytes);
            if (!file.isFile() || file.length() > maxBytes) return invalid(maxBytes);
            try (FileInputStream input = new FileInputStream(file)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() > maxBytes - count) return invalid(maxBytes);
                    output.write(buffer, 0, count);
                }
                return new PurplePeekSnapshot(Status.Present, output.toByteArray(), maxBytes);
            }
        } catch (IOException | SecurityException e) {
            return invalid(maxBytes);
        }
    }

    private static PurplePeekSnapshot invalid(int maxBytes) {
        return new PurplePeekSnapshot(Status.Invalid, null, maxBytes);
    }

    public byte[] bytes() {
        return contents == null ? null : contents.clone();
    }

    public boolean sameAs(PurplePeekSnapshot other) {
        return status == Status.Present && other != null
                && other.status == Status.Present && Arrays.equals(contents, other.contents);
    }

    public boolean matches(File file) {
        return sameAs(read(file, maxBytes));
    }

    public static int waitSeconds(int oldDelay, Integer newDelay) {
        return newDelay == null ? Math.max(0, oldDelay) : Math.max(oldDelay, newDelay);
    }

    public boolean replace(File file, byte[] bytes) {
        if (bytes == null || bytes.length > maxBytes || !matches(file)) return false;
        File temp = null;
        try {
            temp = File.createTempFile(file.getName(), ".peek.tmp", file.getParentFile());
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes);
                output.flush();
            }
            return matches(file) && temp.renameTo(file);
        } catch (IOException | SecurityException e) {
            return false;
        } finally {
            if (temp != null) temp.delete();
        }
    }
}
