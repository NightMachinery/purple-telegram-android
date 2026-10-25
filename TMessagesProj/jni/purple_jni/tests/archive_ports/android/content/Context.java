package android.content;
public class Context {
 public static final String STORAGE_SERVICE="storage";
 public final ContentResolver resolver=new ContentResolver(); public final android.os.storage.StorageManager storage=new android.os.storage.StorageManager();
 public String getPackageName(){return "org.telegram.fixture";} public ContentResolver getContentResolver(){return resolver;}
 public Object getSystemService(String name){return STORAGE_SERVICE.equals(name)?storage:null;}
}
