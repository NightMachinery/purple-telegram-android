package android.system;
public final class OsConstants {
 public static final int O_RDONLY=0,O_WRONLY=1,O_TRUNC=512,O_NOFOLLOW=131072,O_CLOEXEC=524288;
 public static boolean S_ISDIR(int mode){return (mode&0170000)==0040000;}
}
