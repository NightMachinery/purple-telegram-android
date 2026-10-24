package android.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class AtomicFile {
    public enum Point { BeforeStream, Empty, Written }

    public static final class Killed extends Error {
        private static final long serialVersionUID = 1L;
    }

    public static boolean corruptAfterFinish;
    public static String corruptName;
    public static int failWriteCalls;
    public static int finishWriteCalls;
    public static boolean legacy;
    public static String killName;
    public static Point killPoint;
    public static String fullName;
    private final File base;
    private final File temporary;
    private final File backup;

    public AtomicFile(File base) {
        this.base = base;
        temporary = new File(base.getPath() + ".new");
        backup = new File(base.getPath() + ".bak");
    }

    public FileOutputStream startWrite() throws IOException {
        if (legacy && base.exists()) {
            if (!backup.exists()) {
                base.renameTo(backup);
            } else {
                base.delete();
            }
        }
        kill(Point.BeforeStream, null);
        final boolean full = base.getName().equals(fullName);
        if (full) {
            fullName = null;
        }
        final FileOutputStream stream = new FileOutputStream(legacy ? base : temporary) {
            @Override
            public void write(byte[] bytes) throws IOException {
                if (full) {
                    throw new IOException("No space left on device");
                }
                super.write(bytes);
            }
        };
        kill(Point.Empty, stream);
        return stream;
    }

    public void finishWrite(FileOutputStream stream) throws IOException {
        finishWriteCalls++;
        kill(Point.Written, stream);
        stream.getFD().sync();
        stream.close();
        if (legacy) {
            backup.delete();
        } else {
            Files.move(temporary.toPath(), base.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        if (corruptAfterFinish || base.getName().equals(corruptName)) {
            corruptAfterFinish = false;
            corruptName = null;
            Files.write(base.toPath(), new byte[] { 1 });
        }
    }

    public void failWrite(FileOutputStream stream) {
        failWriteCalls++;
        try {
            stream.close();
        } catch (IOException ignored) {
        }
        if (legacy) {
            base.delete();
            backup.renameTo(base);
        } else {
            temporary.delete();
        }
    }

    private void kill(Point point, FileOutputStream stream) throws IOException {
        if (point != killPoint || !base.getName().equals(killName)) {
            return;
        }
        killName = null;
        killPoint = null;
        if (stream != null) {
            stream.close();
        }
        throw new Killed();
    }
}
