package org.telegram.messenger.purple;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PurpleArchiveMediaDataSource extends InputStream {
    final PurpleArchiveMediaAccess.Capability capability;
    private final AtomicBoolean closed = new AtomicBoolean();
    private long position;
    private static final class Page {
        final long index; byte[] data;
        Page(long index, byte[] data) { this.index = index; this.data = data; }
        synchronized void clear() { if (data != null) Arrays.fill(data, (byte) 0); data = null; }
    }
    private final java.util.concurrent.atomic.AtomicReference<Page> cached = new java.util.concurrent.atomic.AtomicReference<>();
    public PurpleArchiveMediaDataSource(PurpleArchiveMediaAccess.Capability capability) throws IOException {
        if (capability == null || !capability.openStream()) throw new IOException("Archive stream unavailable"); this.capability = capability;
        capability.sources.add(this); if (!capability.available()) { close(); throw new IOException("Archive stream revoked"); }
    }
    private static void ioThread() throws IOException {
        if (android.os.Looper.getMainLooper() != null && android.os.Looper.myLooper() == android.os.Looper.getMainLooper() || Thread.currentThread().getName().startsWith("Binder:"))
            throw new IOException("Archive reads require the owned IO thread");
    }
    public long size() throws IOException { if (closed.get() || !capability.available()) throw new IOException("Archive stream revoked"); return capability.bytes; }
    public int readAt(long at, byte[] buffer, int offset, int length) throws IOException {
        ioThread();
        if (buffer == null || at < 0 || offset < 0 || length < 0 || offset > buffer.length - length || closed.get()) throw new IOException("Archive read range refused");
        if (length == 0) return 0;
        if (at >= size()) { capability.validate(() -> !closed.get()); return -1; }
        long index = at / PurpleArchiveMediaAccess.CHUNK; Page page = cached.get();
        if (page == null || page.index != index) {
            byte[] bytes = capability.read(index * PurpleArchiveMediaAccess.CHUNK, PurpleArchiveMediaAccess.CHUNK, () -> !closed.get());
            page = new Page(index, bytes); Page previous = cached.getAndSet(page); if (previous != null) previous.clear();
        } else capability.validate(() -> !closed.get());
        synchronized (page) {
            if (closed.get() || !capability.available() || page.data == null) {
                cached.compareAndSet(page, null); page.clear(); throw new IOException("Archive stream revoked");
            }
            int start = (int) (at % PurpleArchiveMediaAccess.CHUNK); int count = Math.min(length, page.data.length - start);
            System.arraycopy(page.data, start, buffer, offset, count); return count == 0 ? -1 : count;
        }
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = readAt(position, buffer, offset, length); if (count > 0) position = Math.addExact(position, count); return count;
    }
    @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            Page previous = cached.getAndSet(null); if (previous != null) previous.clear();
            capability.sources.remove(this); capability.closeStream();
        }
    }
}
