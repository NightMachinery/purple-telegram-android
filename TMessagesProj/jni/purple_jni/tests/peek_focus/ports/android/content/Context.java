package android.content;
public class Context {
 public static final int MODE_PRIVATE=0,RECEIVER_NOT_EXPORTED=4; public static final String POWER_SERVICE="power",KEYGUARD_SERVICE="keyguard";
 public final org.telegram.ui.PeekPreferences prefs=new org.telegram.ui.PeekPreferences();
 public final android.os.PowerManager power=new android.os.PowerManager();public final android.app.KeyguardManager keyguard=new android.app.KeyguardManager();
 public final java.util.List<BroadcastReceiver> receivers=new java.util.ArrayList<>();
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){receivers.add(r);return null;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){return registerReceiver(r,f);}
 public boolean throwUnregister;public void unregisterReceiver(BroadcastReceiver r){if(!receivers.remove(r))throw new IllegalArgumentException("foreign receiver");if(throwUnregister){throwUnregister=false;throw new IllegalStateException("unregister fixture");}}
 public void broadcast(Intent intent){for(BroadcastReceiver r:new java.util.ArrayList<>(receivers))r.onReceive(this,intent);}
 public android.content.ContentResolver getContentResolver(){return null;}
 public Object getSystemService(String name){return POWER_SERVICE.equals(name)?power:keyguard;}
 public SharedPreferences getSharedPreferences(String name,int mode){if(!name.equals("purple_peek_local"))throw new AssertionError("unexpected preference file");return prefs;}
}
