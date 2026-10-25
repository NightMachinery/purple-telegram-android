package android.os;
public final class ParcelFileDescriptor implements java.io.Closeable {
 public static final int MODE_READ_ONLY=268435456;
 public static final java.util.Map<java.io.FileDescriptor,java.io.ByteArrayOutputStream> writers=new java.util.concurrent.ConcurrentHashMap<>();
 public ProxyFileDescriptorCallback callback;public Handler handler;public java.io.ByteArrayOutputStream pipe;public volatile boolean closed;public volatile String error;private ParcelFileDescriptor peer;
 private final java.io.FileDescriptor fd=new java.io.FileDescriptor();
 public ParcelFileDescriptor(){} public java.io.FileDescriptor getFileDescriptor(){return fd;}
 public static ParcelFileDescriptor[] createReliablePipe(){ParcelFileDescriptor a=new ParcelFileDescriptor(),b=new ParcelFileDescriptor();a.pipe=b.pipe=new java.io.ByteArrayOutputStream();a.peer=b;b.peer=a;writers.put(b.fd,b.pipe);return new ParcelFileDescriptor[]{a,b};}
 public void close()throws java.io.IOException{closed=true;if(peer!=null)peer.closed=true;writers.remove(fd);}
 public void closeWithError(String s)throws java.io.IOException{error=s;if(peer!=null)peer.error=s;close();}
}
