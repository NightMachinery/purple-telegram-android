package org.telegram.messenger.purple;

import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class PurpleSettings {
    static volatile int calls;
    static volatile String lastReason;
    static volatile boolean lastFromImport;
    static volatile String lastThread;
    static volatile boolean refuse;
    static volatile byte[] writeInstead;
    static volatile Runnable afterWrite;
    static volatile Runnable onSettingsFile;

    private PurpleSettings() {
    }

    public static File settingsFile() {
        final Runnable hook = onSettingsFile;
        if (hook != null) {
            hook.run();
        }
        return path();
    }

    static File path() {
        final File dir = new File(ApplicationLoader.getFilesDirFixed(), "purple");
        dir.mkdirs();
        return new File(dir, "settings.toml");
    }

    static boolean storeForSync(byte[] bytes, String reason, boolean fromImport) {
        ++calls;
        lastReason = reason;
        lastFromImport = fromImport;
        lastThread = Thread.currentThread().getName();
        if (refuse) {
            return false;
        }
        final File target = path();
        final File temp = new File(target.getParentFile(), "settings.toml.tmp");
        try {
            final byte[] instead = writeInstead;
            Files.write(temp.toPath(), instead != null ? instead : bytes);
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return false;
        }
        final Runnable hook = afterWrite;
        if (hook != null) {
            hook.run();
        }
        return true;
    }
}
