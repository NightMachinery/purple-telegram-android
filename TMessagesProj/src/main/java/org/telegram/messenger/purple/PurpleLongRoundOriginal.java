package org.telegram.messenger.purple;

import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PurpleLongRoundOriginal {
    public final long[] target;
    public final String path;
    public final AtomicBoolean busy = new AtomicBoolean();
    public volatile boolean reserved, saved, released;
    public String binId;
    public final String generation = java.util.UUID.randomUUID().toString();
    public final long device, inode;
    String writerPath;
    private StructStat writerIdentity;
    private final FileInputStream input;
    private final StructStat identity;
    private final long size;
    private boolean closed;
    public PurpleLongRoundOriginal(File file, long[] target) throws Exception {
        this.target = target.clone(); path = file.getAbsolutePath();
        input = new FileInputStream(file);
        try {
            identity = Os.fstat(input.getFD());
            device = identity.st_dev; inode = identity.st_ino;
            size = input.getChannel().size();
            if (size <= 0 || size > 256L * 1024 * 1024) throw new IOException("size");
        } catch (Exception failure) { input.close(); throw failure; }
    }
    public boolean matches(File file) {
        try {
            final StructStat current = Os.stat(file.getAbsolutePath());
            return path.equals(file.getAbsolutePath()) && identity.st_ino == current.st_ino
                    && identity.st_dev == current.st_dev && identity.st_size == current.st_size;
        } catch (Exception missing) { return false; }
    }
    public void writer(File file) {
        if (file == null) return;
        try { writerIdentity = Os.stat(file.getAbsolutePath()); writerPath = file.getAbsolutePath(); }
        catch (Exception missing) { writerIdentity = null; }
    }
    public boolean protectsWriter(File file) {
        if (saved || writerIdentity == null || !file.getAbsolutePath().equals(writerPath)) return false;
        try {
            final StructStat current = Os.stat(writerPath);
            return current.st_dev == writerIdentity.st_dev && current.st_ino == writerIdentity.st_ino;
        } catch (Exception missing) { return false; }
    }
    public static boolean matchesReservation(PurpleRecordingEntry entry, File file) {
        if (entry.originalGeneration == null || !file.getAbsolutePath().equals(entry.snapshotSource)) return false;
        try {
            final StructStat current = Os.stat(file.getAbsolutePath());
            return current.st_dev == entry.originalDevice && current.st_ino == entry.originalInode;
        } catch (Exception missing) { return false; }
    }
    public FileChannel channel() throws IOException {
        if (closed || input.getChannel().size() != size) throw new IOException("original changed");
        return input.getChannel();
    }
    public void verify(File copy) throws Exception {
        if (copy.length() != size || !Arrays.equals(digest(channel()), digest(copy))) throw new IOException("original copy");
    }
    public void closeSaved() {
        if (!saved || closed) return;
        try { input.close(); closed = true; }
        catch (IOException failure) { PurpleDiagLog.event("recordings", "original_close", PurpleDiagLog.reason("io_failed")); }
    }
    private static byte[] digest(File file) throws Exception {
        try (FileInputStream stream = new FileInputStream(file)) { return digest(stream.getChannel()); }
    }
    private static byte[] digest(FileChannel channel) throws Exception {
        final MessageDigest hash = MessageDigest.getInstance("SHA-256");
        final ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
        long offset = 0;
        int count;
        while ((count = channel.read(bytes, offset)) > 0) {
            hash.update(bytes.array(), 0, count); offset += count; bytes.clear();
        }
        return hash.digest();
    }
}
