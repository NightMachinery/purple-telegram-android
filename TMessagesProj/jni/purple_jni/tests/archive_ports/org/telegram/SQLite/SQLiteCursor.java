package org.telegram.SQLite;
import org.telegram.tgnet.NativeByteBuffer;
public final class SQLiteCursor {
    private final Object[][] rows; private int index = -1;
    public SQLiteCursor(Object[][] rows) { this.rows = rows; }
    public boolean next() { return ++index < rows.length; }
    public long longValue(int column) { return ((Number) rows[index][column]).longValue(); }
    public NativeByteBuffer byteBufferValue(int column) {
        byte[] bytes = (byte[]) rows[index][column]; if (bytes == null) return null;
        NativeByteBuffer result = new NativeByteBuffer(true);
        result.buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN); return result;
    }
    public void dispose() {}
}
