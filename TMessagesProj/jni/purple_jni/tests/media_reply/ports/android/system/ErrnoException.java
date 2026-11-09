package android.system;
public class ErrnoException extends Exception { public final int errno; public ErrnoException(String op,int errno){super(op);this.errno=errno;} }
