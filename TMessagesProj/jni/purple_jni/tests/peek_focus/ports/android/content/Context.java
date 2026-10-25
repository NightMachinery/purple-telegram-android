package android.content;
public class Context {
 public static final int MODE_PRIVATE=0; public static final String POWER_SERVICE="power",KEYGUARD_SERVICE="keyguard";
 public final org.telegram.ui.PeekPreferences prefs=new org.telegram.ui.PeekPreferences();
 public final android.os.PowerManager power=new android.os.PowerManager();public final android.app.KeyguardManager keyguard=new android.app.KeyguardManager();
 public android.content.ContentResolver getContentResolver(){return null;}
 public Object getSystemService(String name){return POWER_SERVICE.equals(name)?power:keyguard;}
 public SharedPreferences getSharedPreferences(String name,int mode){if(!name.equals("purple_peek_local"))throw new AssertionError("unexpected preference file");return prefs;}
}
