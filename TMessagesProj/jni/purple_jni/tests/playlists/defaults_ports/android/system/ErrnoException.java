package android.system;
public final class ErrnoException extends Exception {
    public final int errno;
    public ErrnoException(String operation, int errno) { super(operation + ":" + errno); this.errno = errno; }
}
