package android.system;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
public final class Os {
    public static void mkdir(String path, int mode) throws ErrnoException {
        try { Files.createDirectory(Paths.get(path), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))); }
        catch (FileAlreadyExistsException error) { throw new ErrnoException("mkdir", 17); }
        catch (java.io.IOException error) { throw new ErrnoException("mkdir", 5); }
    }
    public static int getuid() {
        try { return ((Number) Files.getAttribute(Paths.get(System.getProperty("java.io.tmpdir")), "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue(); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public static void chmod(String path, int mode) throws ErrnoException {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = new java.util.HashSet<>();
            java.nio.file.attribute.PosixFilePermission[] all = java.nio.file.attribute.PosixFilePermission.values();
            for (int i = 0; i < all.length; ++i) if ((mode & (1 << (8-i))) != 0) permissions.add(all[i]);
            Files.setPosixFilePermissions(Paths.get(path), permissions);
        } catch (java.io.IOException e) { throw new ErrnoException("chmod", 5); }
    }
    private static StructTimespec time(FileTime value) {
        long nanos = value.to(TimeUnit.NANOSECONDS); StructTimespec t = new StructTimespec();
        t.tv_sec = Math.floorDiv(nanos, 1000000000L); t.tv_nsec = Math.floorMod(nanos, 1000000000L); return t;
    }
    public static StructStat lstat(String path) throws ErrnoException {
        try {
        Map<String,Object> attrs = Files.readAttributes(Paths.get(path), "unix:*", LinkOption.NOFOLLOW_LINKS);
        StructStat s = new StructStat();
        s.st_dev = ((Number)attrs.get("dev")).longValue(); s.st_ino = ((Number)attrs.get("ino")).longValue();
        s.st_nlink = ((Number)attrs.get("nlink")).longValue(); s.st_size = ((Number)attrs.get("size")).longValue();
        s.st_uid = ((Number)attrs.get("uid")).intValue(); s.st_mode = ((Number)attrs.get("mode")).intValue();
        s.st_mtim = time((FileTime)attrs.get("lastModifiedTime")); s.st_ctim = time((FileTime)attrs.get("ctime")); return s;
        } catch (java.nio.file.NoSuchFileException e) { throw new ErrnoException("lstat", 2); }
        catch (java.io.IOException e) { throw new ErrnoException("lstat", 5); }
    }
}
