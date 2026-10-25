package android.content;
public abstract class ContentProvider {
 public ContentProvider(){} public Context getContext(){return org.telegram.messenger.ApplicationLoader.applicationContext;}
 public abstract boolean onCreate();public abstract String getType(android.net.Uri uri);
 public abstract android.database.Cursor query(android.net.Uri u,String[] p,String s,String[] a,String o);
 public abstract android.os.ParcelFileDescriptor openFile(android.net.Uri u,String m)throws java.io.FileNotFoundException;
 public abstract android.net.Uri insert(android.net.Uri u,ContentValues v);public abstract int update(android.net.Uri u,ContentValues v,String s,String[] a);public abstract int delete(android.net.Uri u,String s,String[] a);
}
