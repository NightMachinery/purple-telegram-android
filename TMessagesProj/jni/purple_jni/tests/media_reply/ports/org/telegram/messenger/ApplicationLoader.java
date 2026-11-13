package org.telegram.messenger;

import java.io.File;

public final class ApplicationLoader {
    public static volatile boolean mainInterfacePaused;
    public static File files;

    public static File getFilesDirFixed() {
        File dir = files != null ? files : new File(System.getProperty("scratch"), "diag-files");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }
}
