package org.telegram.messenger;

import java.io.File;

public final class ApplicationLoader {
    public static volatile File filesDir;

    private ApplicationLoader() {
    }

    public static File getFilesDirFixed() {
        final File dir = filesDir;
        if (dir == null) {
            throw new AssertionError("no files directory");
        }
        return dir;
    }
}
