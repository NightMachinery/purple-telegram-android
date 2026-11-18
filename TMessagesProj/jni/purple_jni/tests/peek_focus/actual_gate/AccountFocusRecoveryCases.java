package org.telegram.messenger.purple;
import java.lang.reflect.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import android.os.SystemClock;
public final class AccountFocusRecoveryCases {
 static int checks;
 static byte[] b(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static void check(boolean ok,String why){++checks;if(!ok)throw new AssertionError(why);}
 static void set(Class<?> c,String n,Object v)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(null,v);}
 public static void main(String[] args)throws Exception {
  for(String name:new String[]{"PurpleGate","PurpleWorkMode","PurpleCore","PurplePeekForeground"}){
   Class<?> c=Class.forName("org.telegram.messenger.purple."+name,false,AccountFocusRecoveryCases.class.getClassLoader());
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
  UserConfig.selectedAccount=1;PurplePeekForeground.visibility(true);SystemClock.now=2000;PurplePeekForeground.beforeUse();
  check(!views[0].clock.peeking&&views[1].clock.peeking,"actual Gate locally withdraws only expired A");
  check((PurpleWorkModeModel.visibleNative(handles[0],55,0)&15)==4,"retained native A handle hides");
  check((PurpleWorkModeModel.visibleNative(handles[1],55,0)&15)==0,"retained native B handle stays revealing");
  check(PurpleWorkMode.visible(0,55,0)==4&&PurpleWorkMode.visible(1,55,0)==0,"actual account routing preserves independent packed visibility");
  check(views[0].modes.get(55)==null&&views[0].stories.get(55)==null,"A row and story caches invalidated");
  check(views[1].modes.get(55)!=null&&views[1].stories.get(55)!=null,"B row and story caches preserved");
  check(!PurpleWorkModeModel.storyShownNative(handles[0],55,0,false,-1,false)&&PurpleWorkModeModel.storyShownNative(handles[1],55,0,false,-1,false),"actual native stories isolated");
  check(Arrays.equals(PurpleState.data,saved)&&PurpleAccountOwner.refusals>0,"actual reload routing refusal preserves saved bytes");
  check(!PurpleGate.state(0).clock.peeking&&PurpleGate.state(1).clock.peeking,"actual Gate projections keep B live during A cleanup refusal");
  int refusals=PurpleAccountOwner.refusals;for(int i=0;i<100;++i)PurpleGate.state(1);
  check(PurpleAccountOwner.refusals==refusals,"actual Gate hot paths do not spin failed cleanup");
  check(PurpleState.writes==0,"unavailable state writer committed nothing");
  PurpleState.failed=false;SystemClock.now=3000;PurplePeekForeground.beforeUse();
  JSONObject savedA=new JSONObject(PurpleAccountPeekNative.changeNative(PurpleState.data,handles[0],PurpleAccountPeekNative.READ,0));
  JSONObject savedB=new JSONObject(PurpleAccountPeekNative.changeNative(PurpleState.data,handles[1],PurpleAccountPeekNative.READ,0));
  check(!savedA.getBoolean("refused")&&!savedA.getBoolean("peeking"),"durable recovery clears A despite refused settings routing");
  check(!savedB.getBoolean("refused")&&savedB.getBoolean("peeking"),"durable recovery preserves B's saved Peek");
  check(PurpleState.writes==1&&PurplePeekForeground.canDisable(),"one verified scoped state commit discharges the pending end");
  check(PurpleWorkMode.visible(0,55,0)==4&&PurpleWorkMode.visible(1,55,0)==0,"recovery preserves isolated native visibility");
  byte[] recovered=PurpleState.data.clone();
  PurpleState.failed=true;
  PurpleGate.stopPeek(1);
  check(!views[1].clock.peeking&&(PurpleWorkModeModel.visibleNative(handles[1],55,0)&15)==4,"actual manual END hides B despite unavailable routing/settings");
  check(Arrays.equals(PurpleState.data,recovered),"failed manual cleanup never overwrites stored state");
  PurpleWorkModeModel.retireNative(handles);
  System.out.println("ACCOUNT_FOCUS_RECOVERY_CHECKS="+checks);
 }
}
