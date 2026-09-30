package android.system;

public final class OsConstants {
    public static final int ENOENT = 2;
    public static final int EIO = 5;
    public static final int EEXIST = 17;
    public static final int ENOTDIR = 20;
    public static final int S_IFMT = 0170000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFDIR = 0040000;
    public static final int S_IFLNK = 0120000;
    public static final int S_IRWXU = 0700;
    public static final int S_IRWXG = 0070;
    public static final int S_IRWXO = 0007;

    public static boolean S_ISDIR(int mode) {
        return (mode & S_IFMT) == S_IFDIR;
    }

    public static boolean S_ISREG(int mode) {
        return (mode & S_IFMT) == S_IFREG;
    }

    public static boolean S_ISLNK(int mode) {
        return (mode & S_IFMT) == S_IFLNK;
    }
}
