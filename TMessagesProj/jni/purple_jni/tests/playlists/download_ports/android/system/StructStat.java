package android.system;
public final class StructStat {
    public long st_dev, st_ino, st_nlink, st_size;
    public int st_uid, st_mode;
    public StructTimespec st_mtim = new StructTimespec(), st_ctim = new StructTimespec();
}
