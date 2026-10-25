package org.telegram.messenger.purple;
import android.app.Activity;import android.content.Intent;import android.os.Bundle;import java.lang.reflect.Method;import java.io.File;import org.telegram.messenger.*;
public final class PurpleDirectShareColdStartCases {
 static void check(boolean x,String why){if(!x)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  UserConfig c=UserConfig.getInstance(0);c.active=true;c.user=11;
  boolean bot=args[0].equals("bot");String name=bot?"org.telegram.messenger.OpenAttachedMenuBotReceiver":"org.telegram.messenger.OpenChatReceiver";
  Intent i=PurpleDirectShare.bindShortcut(0,7,new Intent().setAction(bot?"com.tmessages.openshortcutbot7":"com.tmessages.openchat7").putExtra(bot?"botId":"userId",7L));check(i!=null,"created valid persistent pin");
  Class<?> type=Class.forName(name);File origin=new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());check(origin.getCanonicalPath().equals(new File(args[1]).getCanonicalPath()),"receiver must be genuine AGP output");
  Method onCreate=type.getDeclaredMethod("onCreate",Bundle.class);onCreate.setAccessible(true);Activity.incoming=i;
  c.active=false;c.user=0;int before=Activity.starts;int toasts=android.widget.Toast.shown;
  onCreate.invoke(type.getDeclaredConstructor().newInstance(),new Object[]{null});
  check(Activity.starts==before+1,"unloaded inventory must forward before admission");
  check(!c.active&&c.user==0&&android.widget.Toast.shown==toasts,"forwarding neither reads/initializes accounts nor refuses a cold pin");
  Intent forwarded=Activity.last;
  check(forwarded.getStringExtra("purple.shortcut.account").equals(i.getStringExtra("purple.shortcut.account"))&&forwarded.getStringExtra("purple.shortcut.login").equals(i.getStringExtra("purple.shortcut.login")),"forwarding preserves account/login identity unchanged");
  c.active=true;c.user=11;
  check(PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext,forwarded),"the initialized final receiver admits the same valid single-account pin");
  c.user=33;onCreate.invoke(type.getDeclaredConstructor().newInstance(),new Object[]{null});
  check(Activity.starts==before+2&&!PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext,Activity.last),"replacement account is refused only at final admission");
  c.user=11;++c.loginSession;
  check(!PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext,forwarded),"same-user new login still refuses the old pin");
  --c.loginSession;
  Activity.incoming=new Intent().setAction(bot?"com.tmessages.openshortcutbot7":"com.tmessages.openchat7").putExtra(bot?"botId":"userId",7L);
  onCreate.invoke(type.getDeclaredConstructor().newInstance(),new Object[]{null});
  check(!PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext,Activity.last),"an unbound legacy pin keeps its shortcut provenance and refuses at final admission");
  System.out.println("COLD_START: "+args[0]+" valid_after_init=true replacement_refused=true new_login_refused=true unbound_legacy_refused=true receiver_origin="+origin);
 }
}
