package org.telegram.messenger.purple;

import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

final class PurpleMediaReplyStore {
    static final int LIMIT = 32 * 1024 * 1024;
    static byte[] read(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath()) || new File(file.getPath() + ".bak").exists()) throw new IOException("UnsafeJournal");
        if (!file.exists()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() > LIMIT - count) throw new IOException("JournalTooLarge");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }
    static void replace(File file, byte[] expected, byte[] bytes) throws Exception {
        if (bytes.length > LIMIT || !Arrays.equals(expected, read(file))) throw new IOException("JournalChanged");
        File temporary = File.createTempFile("media-reply-", ".tmp", file.getParentFile());
        try {
            FileDescriptor descriptor = Os.open(temporary.getPath(),
                    OsConstants.O_WRONLY | OsConstants.O_TRUNC | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            try {
                int written = 0;
                while (written < bytes.length) {
                    int count = Os.write(descriptor, bytes, written, bytes.length - written);
                    if (count <= 0) throw new IOException("JournalWriteFailed");
                    written += count;
                }
                Os.fsync(descriptor);
            } finally { Os.close(descriptor); }
            if (!Arrays.equals(expected, read(file))) throw new IOException("JournalChanged");
            Os.rename(temporary.getPath(), file.getPath());
            FileDescriptor directory = Os.open(file.getParent(),
                    OsConstants.O_RDONLY | OsConstants.O_DIRECTORY | OsConstants.O_CLOEXEC, 0);
            try { Os.fsync(directory); } finally { Os.close(directory); }
            if (!Arrays.equals(bytes, read(file))) throw new IOException("JournalReadbackFailed");
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private PurpleMediaReplyStore() { }
}
