package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

final class PurplePlaylistsSyncFiles {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    static byte[] read(File file, long size, java.util.function.BooleanSupplier current) {
        if (file == null || size < 0 || size > MAX_BYTES || !current.getAsBoolean()) return null;
        try {
            BasicFileAttributes before = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.isSymbolicLink() || before.size() != size || size > MAX_BYTES) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(size, 65536));
            try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer block = ByteBuffer.allocate(65536);
                while (current.getAsBoolean()) {
                    int count = channel.read(block);
                    if (count < 0) break;
                    if (count == 0 || output.size() + count > MAX_BYTES) return null;
                    output.write(block.array(), 0, count); block.clear();
                }
            }
            BasicFileAttributes after = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return current.getAsBoolean() && output.size() == size && after.isRegularFile() && !after.isSymbolicLink()
                    && Objects.equals(before.fileKey(), after.fileKey()) && before.size() == after.size()
                    && before.lastModifiedTime().equals(after.lastModifiedTime()) ? output.toByteArray() : null;
        } catch (Exception error) { return null; }
    }
    private PurplePlaylistsSyncFiles() { }
}
