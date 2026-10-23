package org.telegram.messenger.purple;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class PurpleMediaSaveStream {
    private PurpleMediaSaveStream() {
    }

    public static void copy(InputStream input, OutputStream output, long size, boolean encrypted) throws IOException {
        if (size < 0) {
            throw new IOException("Invalid media length");
        }
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int requested = (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, requested);
            if (read <= 0 || encrypted && read != requested) {
                throw new IOException("Media ended during save");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }
}
