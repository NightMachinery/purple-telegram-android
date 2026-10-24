package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;

@androidx.annotation.Keep
final class PurpleArchiveSqlite implements AutoCloseable {
    static final int NULL = 0, INTEGER = 1, TEXT = 2, BLOB = 3;
    static final int OK = 0, DONE = 101, MAX_ROWS = 128, MAX_BYTES = 16 * 1024 * 1024;
    @androidx.annotation.Keep
    static final class Value {
        final int type;
        final long integer;
        final byte[] bytes;
        Value(int type, long integer, byte[] bytes) { this.type = type; this.integer = integer; this.bytes = bytes; }
        static Value nil() { return new Value(NULL, 0, null); }
        static Value integer(long value) { return new Value(INTEGER, value, null); }
        static Value text(String value) { return new Value(TEXT, 0, value.getBytes(StandardCharsets.UTF_8)); }
        static Value blob(byte[] value) { return new Value(BLOB, 0, value.clone()); }
        String text() {
            if (type != TEXT) throw new IllegalStateException("archive column type");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    @androidx.annotation.Keep
    static final class Result {
        final int code, extended, reset, finalized;
        final long rows, directChanges, totalChanges;
        final Value[][] values;
        Result(int code, int extended, int reset, int finalized, long rows, long directChanges, long totalChanges, Value[][] values) {
            this.code = code; this.extended = extended; this.reset = reset; this.finalized = finalized;
            this.rows = rows; this.directChanges = directChanges; this.totalChanges = totalChanges; this.values = values;
        }
        boolean done() { return code == DONE && reset == OK && finalized == OK; }
        boolean counts(long minRows, long maxRows, long minChanges, long maxChanges, boolean total) {
            long changes = total ? totalChanges : directChanges;
            return done() && rows >= minRows && (maxRows < 0 || rows <= maxRows)
                && changes >= minChanges && (maxChanges < 0 || changes <= maxChanges);
        }
    }
    private long handle;
    private final Thread owner = Thread.currentThread();
    PurpleArchiveSqlite(String path, boolean create) { this(path, create, false); }
    PurpleArchiveSqlite(String path, boolean create, boolean readOnly) { handle = openNative(path.getBytes(StandardCharsets.UTF_8), create, readOnly); }
    private void check() {
        if (Thread.currentThread() != owner || handle == 0) throw new IllegalStateException("archive connection ownership");
    }
    Result run(String sql, Value... binds) {
        check();
        int[] types = new int[binds.length]; long[] integers = new long[binds.length]; byte[][] bytes = new byte[binds.length][];
        for (int i = 0; i < binds.length; ++i) {
            Value value = binds[i];
            if (value == null) throw new IllegalArgumentException("archive bind");
            types[i] = value.type; integers[i] = value.integer; bytes[i] = value.bytes;
        }
        return runNative(handle, sql.getBytes(StandardCharsets.UTF_8), types, integers, bytes, MAX_ROWS, MAX_BYTES);
    }
    boolean autocommit() { check(); return autocommitNative(handle); }
    @Override public void close() {
        check(); int code = closeNative(handle);
        if (code != OK) throw new IllegalStateException("archive close " + code);
        handle = 0;
    }
    private static native long openNative(byte[] path, boolean create, boolean readOnly);
    private static native Result runNative(long handle, byte[] sql, int[] types, long[] integers, byte[][] bytes, int maxRows, int maxBytes);
    private static native boolean autocommitNative(long handle);
    private static native int closeNative(long handle);
}
