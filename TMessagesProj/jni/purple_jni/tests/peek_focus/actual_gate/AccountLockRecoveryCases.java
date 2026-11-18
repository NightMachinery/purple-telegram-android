package org.telegram.messenger.purple;
import java.lang.reflect.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import android.os.SystemClock;
public final class AccountLockRecoveryCases {
 static int checks;
 static byte[] b(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static void check(boolean ok,String why){++checks;if(!ok)throw new AssertionError(why);}
 static void set(Class<?> c,String n,Object v)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(null,v);}
 public static void main(String[] args)throws Exception {
  for(String name:new String[]{"PurpleGate","PurpleWorkMode","PurpleCore","PurplePeekForeground"}){
   Class<?> c=Class.forName("org.telegram.messenger.purple."+name,false,AccountLockRecoveryCases.class.getClassLoader());
   check(new java.io.File(c.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new java.io.File(args[0]).getCanonicalFile()),"actual production class origin "+name);
  }
  org.telegram.messenger.ApplicationLoader.applicationContext=new android.app.Application();
  PurplePeekLocalSettings.policy=new PurplePeekLocalSettings.Snapshot(true);UserConfig.selectedAccount=0;SystemClock.now=0;
  for(int i=0;i<2;++i)PurpleAccountFiles.ids[i]=new PurpleAccountFiles.Identity(i,"0-"+(i==0?11:22),0);
  byte[] settings=b("version=1\n[presets.work]\ntitle=\"Work\"\nlist_order=[]\n");
  byte[] local=b("[account_work_mode]\n\"0-11\"=\"own\"\n\"0-22\"=\"own\"\n");
  PurpleState.data=b("active_preset=\"work\"\n[[account_peeks]]\naccount=\"0-11\"\nactive=true\ndeadline_unix=0\n[[account_peeks]]\naccount=\"0-22\"\nactive=true\ndeadline_unix=0\n");
  byte[] saved=PurpleState.data.clone();PurpleCore.Loaded loaded=PurpleCore.load(settings,PurpleState.data,"fixture","android","mobile");
  check(loaded!=null&&loaded.ok&&!loaded.normal,"actual global Work gate loaded");
  set(PurpleGate.class,"loaded",loaded);set(PurpleGate.class,"filtering",true);set(PurpleGate.class,"everLoaded",true);
  PurpleWorkMode.View[] views=new PurpleWorkMode.View[4];long[] handles=new long[2];
  for(int slot=0;slot<2;++slot){
   JSONObject made=new JSONObject(PurpleWorkModeModel.createNative(local,PurpleAccountFiles.ids[slot].key,"0-11",settings,"synthetic/global",null,settings,"synthetic/own",null));
   handles[slot]=Long.parseLong(made.getString("handle"));
   views[slot]=new PurpleWorkMode.View(PurpleAccountFiles.ids[slot],made.getInt("mode"),handles[slot],PurpleCore.Clock.fromJson(made.getJSONObject("peekClock")));
   views[slot].modes.put(55,0);views[slot].stories.put(55,3);
   check(views[slot].clock.peeking&&(PurpleWorkModeModel.visibleNative(handles[slot],55,0)&15)==0,"own native account starts revealing");
  }
  Field field=PurpleWorkMode.class.getDeclaredField("publication");field.setAccessible(true);Object publication=field.get(null);
  Class<?> generation=Class.forName("org.telegram.messenger.purple.PurpleWorkMode$Generation");Constructor<?> ctor=generation.getDeclaredConstructors()[0];ctor.setAccessible(true);
  Field current=publication.getClass().getDeclaredField("current");current.setAccessible(true);current.set(publication,ctor.newInstance(views,0L));
  PurplePeekForeground.prepare().published(true);set(PurplePeekForeground.class,"foreground",true);PurplePeekForeground.published();
  PurpleState.failed=true;
  org.telegram.messenger.SharedConfig.appLocked=true;
  PurpleGate.locked();
  check(!PurpleGate.peeking(0)&&!PurpleGate.peeking(1),"lock immediately withdraws both active accounts");
  check((PurpleWorkModeModel.visibleNative(handles[0],55,0)&15)==4
          &&(PurpleWorkModeModel.visibleNative(handles[1],55,0)&15)==4,"retained native handles stop revealing");
  check(views[0].stories.get(55)==null&&views[1].stories.get(55)==null,"lock invalidates both story caches");
  check(Arrays.equals(saved,PurpleState.data)&&PurpleState.writes==0&&!PurplePeekForeground.canDisable(),
          "failed lock cleanup keeps both durable obligations");
  org.telegram.messenger.SharedConfig.appLocked=false;
  PurplePeekForeground.visibility(true);
  check(!PurpleGate.peeking(0)&&!PurpleGate.peeking(1),"unlock cannot resurrect either account");
  PurpleState.failed=false;SystemClock.now=1000;PurplePeekForeground.beforeUse();
  for(int slot=0;slot<2;slot++) {
   JSONObject stored=new JSONObject(PurpleAccountPeekNative.changeNative(PurpleState.data,handles[slot],PurpleAccountPeekNative.READ,0));
   check(!stored.getBoolean("refused")&&!stored.getBoolean("peeking"),"account "+slot+" saved Peek is ended");
  }
  check(PurpleState.writes==2&&PurplePeekForeground.canDisable(),"two scoped writes discharge both saved obligations");
  check(PurpleAccountOwner.refusals>0,"settings routing remains unavailable");
  PurpleWorkModeModel.retireNative(handles);
  System.out.println("ACCOUNT_LOCK_RECOVERY_CHECKS="+checks);
 }
}
