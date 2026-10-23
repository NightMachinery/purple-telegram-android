import org.telegram.messenger.purple.PurpleMediaSaveStream;
import org.telegram.messenger.purple.PurpleMediaSaveSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MediaSaveStreamTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (int size : new int[]{0, 1, 65535, 65536, 65537, 200003}) {
            byte[] bytes = data(size);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PurpleMediaSaveStream.copy(new ByteArrayInputStream(bytes), out, bytes.length, true);
            check(Arrays.equals(bytes, out.toByteArray()), "exact encrypted reads " + size);
        }
        byte[] bytes = data(150000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PurpleMediaSaveStream.copy(shortReads(bytes), out, bytes.length, false);
        check(Arrays.equals(bytes, out.toByteArray()), "ordinary short reads preserve all bytes");

        out.reset();
        PurpleMediaSaveStream.copy(new ByteArrayInputStream(bytes), out, 7, false);
        check(Arrays.equals(Arrays.copyOf(bytes, 7), out.toByteArray()), "bounded export does not overread");

        expectFailure(shortReads(bytes), bytes.length, true, "short encrypted read refuses offset drift");
        expectFailure(new ByteArrayInputStream(bytes), bytes.length + 1L, false, "truncated input refuses publication");
        expectFailure(new ByteArrayInputStream(bytes), -1, false, "invalid length refused");
        expectFailure(new InputStream() {
            public int read() { return 0; }
            public int read(byte[] b, int off, int len) { return 0; }
        }, 1, false, "zero progress does not spin");
        Path directory = Files.createTempDirectory("purple-media-source-");
        Path plain = directory.resolve("backup.enc");
        Files.write(plain, bytes);
        PurpleMediaSaveSource selected = PurpleMediaSaveSource.find(plain.toFile(), true, "backup.enc", "application/octet-stream");
        check(selected != null && !selected.encrypted && selected.name.equals("backup.enc"), "user .enc filename stays plaintext");
        out.reset();
        PurpleMediaSaveStream.copy(shortReads(Files.readAllBytes(selected.file.toPath())), out, bytes.length, selected.encrypted);
        check(Arrays.equals(bytes, out.toByteArray()), "plain .enc export preserves bytes and supports ordinary short reads");
        Path original = directory.resolve("media.mp4");
        Path encrypted = directory.resolve("media.mp4.enc");
        Files.write(encrypted, bytes);
        selected = PurpleMediaSaveSource.find(original.toFile(), true, "media.mp4", "video/mp4");
        check(selected != null && selected.encrypted && selected.file.equals(encrypted.toFile()), "appended encrypted cache candidate is explicit");
        Files.write(original, bytes);
        selected = PurpleMediaSaveSource.find(original.toFile(), true, "media.mp4", "video/mp4");
        check(selected != null && !selected.encrypted && selected.file.equals(original.toFile()), "ordinary source wins over appended cache candidate");
        System.out.println("Media save stream: " + checks + " checks passed");
    }

    private static byte[] data(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + i / 251);
        }
        return bytes;
    }

    private static InputStream shortReads(byte[] bytes) {
        return new FilterInputStream(new ByteArrayInputStream(bytes)) {
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 31));
            }
        };
    }

    private static void expectFailure(InputStream input, long size, boolean encrypted, String name) throws Exception {
        try {
            PurpleMediaSaveStream.copy(input, new ByteArrayOutputStream(), size, encrypted);
            throw new AssertionError(name);
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void check(boolean value, String name) {
        if (!value) {
            throw new AssertionError(name);
        }
        checks++;
    }
}
