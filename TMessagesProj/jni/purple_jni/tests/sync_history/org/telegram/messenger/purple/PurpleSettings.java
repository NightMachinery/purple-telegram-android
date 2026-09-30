package org.telegram.messenger.purple;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class PurpleSettings {
    static File file;
    static int calls;
    static String lastReason;
    static boolean lastFromImport;
    static boolean refuse;
    static byte[] writeInstead;
    static boolean throwAfterWrite;

    public static File settingsFile() {
        return file;
    }

    static boolean storeForSync(byte[] bytes, String reason, boolean fromImport) {
        ++calls;
        lastReason = reason;
        lastFromImport = fromImport;
        if (refuse) {
            return false;
        }
        try {
            Files.write(file.toPath(), writeInstead != null ? writeInstead : bytes);
        } catch (IOException e) {
            return false;
        }
        if (throwAfterWrite) {
            throw new IllegalStateException("reload failed");
        }
        return true;
    }
}
