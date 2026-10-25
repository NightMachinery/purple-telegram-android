package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@androidx.annotation.Keep
final class PurpleArchiveFiles {
    static final ThreadLocal<java.util.function.Consumer<String>> publicationObserver = new ThreadLocal<>();
    static final class Mark {
        final String identity, stamp;
        final long bytes;
        final int mode;
        Mark(String[] value) {
            if (value == null || value.length != 3) throw new IllegalArgumentException("archive file mark");
            identity = value[0]; stamp = value[1]; bytes = Long.parseLong(value[2]);
            String[] fields = stamp.split(":");
            if (fields.length != 10 || bytes < 0) throw new IllegalArgumentException("archive file mark");
            mode = Integer.parseInt(fields[2]);
        }
    }
    static final class Snapshot {
        final Mark mark;
        final byte[] bytes;
        Snapshot(Mark mark, byte[] bytes) { this.mark = mark; this.bytes = bytes; }
    }
    static final class Directory {
        final File file;
        final String identity;
        final boolean privateRequired;
        Directory(File file, boolean privateRequired) {
            this.file = file.getAbsoluteFile(); this.privateRequired = privateRequired;
            Mark mark = mark(this.file, true);
            if (mark == null || (privateRequired && (mark.mode & 0077) != 0)) throw new IllegalStateException("archive directory unavailable");
            identity = mark.identity;
        }
        void check() {
            Mark now = mark(file, true);
            if (now == null || !identity.equals(now.identity) || (privateRequired && (now.mode & 0077) != 0))
                throw new IllegalStateException("archive directory changed");
        }
        File file(String name) { leaf(name); return new File(file, name); }
        Directory child(String name, boolean privateChild) {
            check(); File next = file(name);
            if (mark(next, true) == null && mkdirNative(path(file), identity, name, false) == null)
                throw new IllegalStateException("archive directory creation failed");
            check(); return new Directory(next, privateChild);
        }
        Directory exclusiveChild(String name) {
            check(); leaf(name);
            if (mkdirNative(path(file), identity, name, true) == null) throw new IllegalStateException("archive exclusive directory creation failed");
            check(); return new Directory(file(name), true);
        }
        Snapshot read(String name, int limit) {
            check(); Mark before = mark(file(name), false);
            if (before == null) return new Snapshot(null, null);
            if ((before.mode & 0077) != 0) throw new IllegalStateException("archive metadata permissions");
            byte[] bytes = readNative(path(file), identity, name, before.stamp, limit);
            Mark after = mark(file(name), false); check();
            if (bytes == null || after == null || !before.stamp.equals(after.stamp)) throw new IllegalStateException("archive metadata changed");
            return new Snapshot(after, bytes);
        }
        Snapshot publish(String name, Snapshot expected, byte[] bytes) {
            check(); leaf(name);
            if (expected == null || bytes == null || bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("archive publication");
            String old = expected.mark == null ? "" : expected.mark.stamp;
            if (!publishNative(path(file), identity, name, old, ".new-" + UUID.randomUUID().toString(), bytes))
                throw new IllegalStateException("archive publication failed");
            Snapshot actual = read(name, bytes.length);
            if (!Arrays.equals(bytes, actual.bytes)) throw new IllegalStateException("archive publication readback failed");
            java.util.function.Consumer<String> observer = publicationObserver.get(); if (observer != null) observer.accept(name);
            return actual;
        }
        void remove(String name, boolean directory) {
            check(); Mark mark = mark(file(name), directory);
            if (!removeNative(path(file), identity, name, mark == null ? "" : mark.stamp, directory))
                throw new IllegalStateException("archive removal not durable");
            check();
        }
        Listing listing() { check(); return new Listing(this); }
    }
    static final class Listing implements AutoCloseable {
        final Directory directory;
        final Thread owner = Thread.currentThread();
        private long handle;
        Listing(Directory directory) {
            this.directory = directory; handle = listOpenNative(path(directory.file), directory.identity);
            if (handle == 0) throw new IllegalStateException("archive listing unavailable");
        }
        String[] next() {
            if (owner != Thread.currentThread() || handle == 0) throw new IllegalStateException("archive listing ownership");
            directory.check(); String[] values = listNextNative(handle); directory.check();
            if (values == null || values.length > 128) throw new IllegalStateException("archive listing failed");
            for (String value : values) leaf(value);
            return values;
        }
        @Override public void close() {
            if (handle == 0) return;
            if (owner != Thread.currentThread() || !listCloseNative(handle)) throw new IllegalStateException("archive listing close failed");
            handle = 0;
        }
    }
    static final class Lock implements AutoCloseable {
        private static final Set<String> claimed = new HashSet<>();
        private final String key;
        private final Thread owner = Thread.currentThread();
        private long handle;
        Lock(Directory directory) {
            directory.check(); key = directory.file.getAbsolutePath() + ":" + directory.identity;
            synchronized (claimed) { if (!claimed.add(key)) throw new IllegalStateException("archive account busy"); }
            try {
                handle = lockNative(path(directory.file), directory.identity);
                if (handle == 0) throw new IllegalStateException("archive process lock unavailable");
                directory.check();
            } catch (RuntimeException | LinkageError e) {
                if (handle != 0) {
                    if (!unlockNative(handle)) throw new IllegalStateException("archive lock cleanup failed", e);
                    handle = 0;
                }
                synchronized (claimed) { claimed.remove(key); } throw e;
            }
        }
        @Override public void close() {
            if (handle == 0) return;
            if (owner != Thread.currentThread() || !unlockNative(handle)) throw new IllegalStateException("archive lock close failed");
            handle = 0; synchronized (claimed) { claimed.remove(key); }
        }
    }
    static Mark mark(File file, boolean directory) {
        String[] value = markNative(path(file), directory); return value == null ? null : new Mark(value);
    }
    static Mark markAny(File file) {
        String[] value = markAnyNative(path(file)); return value == null ? null : new Mark(value);
    }
    static void leaf(String value) {
        if (value == null || value.equals(".") || value.equals("..") || !value.matches("[A-Za-z0-9_.-]{1,160}"))
            throw new IllegalArgumentException("archive leaf name");
    }
    private static byte[] path(File value) { return value.getAbsolutePath().getBytes(StandardCharsets.UTF_8); }
    static native long[] operationsNative(boolean reset);
    private static native String[] markAnyNative(byte[] path);
    private static native String[] markNative(byte[] path, boolean directory);
    private static native String[] mkdirNative(byte[] path, String identity, String name, boolean exclusive);
    private static native byte[] readNative(byte[] path, String identity, String name, String expected, int limit);
    private static native boolean publishNative(byte[] path, String identity, String name, String expected, String temporary, byte[] bytes);
    private static native boolean removeNative(byte[] path, String identity, String name, String expected, boolean directory);
    private static native long listOpenNative(byte[] path, String identity);
    private static native String[] listNextNative(long handle);
    private static native boolean listCloseNative(long handle);
    private static native long lockNative(byte[] path, String identity);
    private static native boolean unlockNative(long handle);
    private PurpleArchiveFiles() {}
}
