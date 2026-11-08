package org.telegram.messenger;
public final class ApplicationLoader {
    public static volatile boolean mainInterfacePaused;
    public static android.content.Context applicationContext;
    public static java.io.File files;
    public static java.io.File getFilesDirFixed() { return files; }
    public static boolean isAndroidTestEnvironment() { return true; }
}
