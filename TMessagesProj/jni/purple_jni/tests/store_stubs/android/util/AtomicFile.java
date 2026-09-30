package android.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class AtomicFile {
    public static boolean corruptAfterFinish;
    public static int failWriteCalls;
    public static int finishWriteCalls;
    private final File base;
    private final File temporary;

    public AtomicFile(File base) {
        this.base = base;
        temporary = new File(base.getPath() + ".new");
    }

    public FileOutputStream startWrite() throws IOException {
        return new FileOutputStream(temporary);
    }

    public void finishWrite(FileOutputStream stream) throws IOException {
        finishWriteCalls++;
        stream.getFD().sync();
        stream.close();
        Files.move(temporary.toPath(), base.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        if (corruptAfterFinish) {
            corruptAfterFinish = false;
            Files.write(base.toPath(), new byte[] { 1 });
        }
    }

    public void failWrite(FileOutputStream stream) {
        failWriteCalls++;
        try {
            stream.close();
        } catch (IOException ignored) {
        }
        temporary.delete();
    }
}
