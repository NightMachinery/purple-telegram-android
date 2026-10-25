package android.os;
public abstract class ProxyFileDescriptorCallback {public abstract long onGetSize()throws android.system.ErrnoException;public abstract int onRead(long at,int n,byte[] b)throws android.system.ErrnoException;public abstract void onRelease();}
