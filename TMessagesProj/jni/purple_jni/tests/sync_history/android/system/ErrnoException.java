package android.system;

public final class ErrnoException extends Exception {
    private static final long serialVersionUID = 1L;
    public final int errno;

    public ErrnoException(String functionName, int errno) {
        super(functionName + " failed: " + errno);
        this.errno = errno;
    }
}
