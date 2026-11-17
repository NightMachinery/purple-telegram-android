package android.system;
public final class OsConstants {
    public static final int ENOENT = 2, EEXIST = 17;
    public static boolean S_ISDIR(int mode) { return (mode & 0170000) == 0040000; }
    public static boolean S_ISREG(int mode) { return (mode & 0170000) == 0100000; }
}
