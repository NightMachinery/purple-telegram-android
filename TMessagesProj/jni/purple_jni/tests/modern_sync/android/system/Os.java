package android.system;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

public final class Os {
    private static final PosixFilePermission[] ORDER = {
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE,
    };

    public static StructStat lstat(String path) throws ErrnoException {
        try {
            java.util.Map<String,Object> a=Files.readAttributes(Paths.get(path),"unix:mode,size,dev,ino,nlink,lastModifiedTime,ctime",LinkOption.NOFOLLOW_LINKS);
            return new StructStat(((Number)a.get("mode")).intValue(),((Number)a.get("size")).longValue(),((Number)a.get("dev")).longValue(),
                    ((Number)a.get("ino")).longValue(),((Number)a.get("nlink")).longValue(),((java.nio.file.attribute.FileTime)a.get("lastModifiedTime")).toMillis()/1000,
                    ((java.nio.file.attribute.FileTime)a.get("ctime")).toMillis()/1000);
        } catch(NoSuchFileException e){throw new ErrnoException("lstat",OsConstants.ENOENT);}
        catch(IOException e){throw new ErrnoException("lstat",OsConstants.EIO);}
    }

    public static void chmod(String path, int mode) throws ErrnoException {
        try {
            Files.setPosixFilePermissions(Paths.get(path), permissions(mode));
        } catch (NoSuchFileException e) {
            throw new ErrnoException("chmod", OsConstants.ENOENT);
        } catch (IOException e) {
            throw new ErrnoException("chmod", OsConstants.EIO);
        }
    }

    public static void mkdir(String path, int mode) throws ErrnoException {
        try {
            final Path created = Files.createDirectory(Paths.get(path));
            Files.setPosixFilePermissions(created, permissions(mode & ~022));
        } catch (FileAlreadyExistsException e) {
            throw new ErrnoException("mkdir", OsConstants.EEXIST);
        } catch (NoSuchFileException e) {
            throw new ErrnoException("mkdir", OsConstants.ENOENT);
        } catch (IOException e) {
            throw new ErrnoException("mkdir", OsConstants.EIO);
        }
    }

    private static int bits(Set<PosixFilePermission> set) {
        int result = 0;
        for (int i = 0; i != ORDER.length; ++i) {
            if (set.contains(ORDER[i])) {
                result |= 1 << (8 - i);
            }
        }
        return result;
    }

    private static Set<PosixFilePermission> permissions(int mode) {
        final Set<PosixFilePermission> result =
                EnumSet.noneOf(PosixFilePermission.class);
        for (int i = 0; i != ORDER.length; ++i) {
            if ((mode & (1 << (8 - i))) != 0) {
                result.add(ORDER[i]);
            }
        }
        return result;
    }
}
