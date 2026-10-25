package android.content;
public class ContentResolver {
 public final java.util.Map<String,java.io.ByteArrayOutputStream> files=new java.util.concurrent.ConcurrentHashMap<>();
 public java.io.OutputStream openOutputStream(android.net.Uri uri,String mode)throws java.io.FileNotFoundException{if(!"wt".equals(mode))throw new AssertionError("explicit truncate mode");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();files.put(uri.toString(),out);return out;}
 public int delete(android.net.Uri uri,String where,String[] args){return files.remove(uri.toString())==null?0:1;}
}
