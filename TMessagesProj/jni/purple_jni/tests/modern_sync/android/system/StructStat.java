package android.system;
public final class StructStat {
    public final int st_mode;
    public final long st_size,st_dev,st_ino,st_nlink,st_mtime,st_ctime;
    public StructStat(int mode,long size,long dev,long ino,long nlink,long mtime,long ctime){st_mode=mode;st_size=size;st_dev=dev;st_ino=ino;st_nlink=nlink;st_mtime=mtime;st_ctime=ctime;}
}
