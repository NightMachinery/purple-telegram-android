package org.telegram.messenger.purple;
import java.lang.reflect.*;import android.app.*;import android.os.SystemClock;import org.telegram.messenger.*;import org.telegram.messenger.purple.*;
public class NativeAccountProbe {
 static byte[] b(String s){return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 static void set(String n,Object v)throws Exception{Field f=PurpleGate.class.getDeclaredField(n);f.setAccessible(true);f.set(null,v);}
 public static void main(String[] a)throws Exception {
  String origin=PurpleGate.class.getProtectionDomain().getCodeSource().getLocation().toString();System.out.println("production Gate origin="+origin);
  Application app=new Application();ApplicationLoader.applicationContext=app;app.prefs.values.put("end_on_focus_loss",true);
  Activity activity=new Activity(app);new org.telegram.ui.Components.ForegroundDetector(app);PurplePeekForeground.init(app);app.start(activity);
  byte[] settings=b("version=1\n[peek]\nstart_delay_seconds=2\n[presets.work]\n");
  PurpleState.bytes=b("active_preset=\"work\"\npeek_active=false\n");
  PurpleCore.Loaded first=PurpleCore.load(settings,PurpleState.read(),"fixture","android","mobile");
  PurplePeekForeground.resolve(first,PurpleState.read(),s->PurpleCore.load(settings,s,"fixture","android","mobile"));
  PurpleState.bytes=b(PurpleCore.startPeek(PurpleState.read(),0).text);
  PurpleCore.Loaded live=PurpleCore.load(settings,PurpleState.read(),"fixture","android","mobile");
  live=PurplePeekForeground.resolve(live,PurpleState.read(),s->PurpleCore.load(settings,s,"fixture","android","mobile"));
  set("loaded",live);set("filtering",true);set("everLoaded",true);
  if(!PurpleGate.peeking())throw new AssertionError("fixture Peek must start active");
  org.json.JSONObject made=new org.json.JSONObject(PurpleWorkModeModel.createNative(new byte[0],"0-101","0-101",settings,"synthetic-global",null,null,"synthetic-own",null));
  long handle=Long.parseLong(made.getString("handle"));
  if(PurpleWorkModeModel.visibleNative(handle,55,0)!=0)throw new AssertionError("fixture must reveal");
  PurpleWorkMode.View view=new PurpleWorkMode.View(new PurpleAccountFiles.Identity(),0,handle);
  view.modes.put(55,0);view.stories.put(55,3);
  Field publicationField=PurpleWorkMode.class.getDeclaredField("publication");publicationField.setAccessible(true);Object publication=publicationField.get(null);
  Class<?> generation=Class.forName("org.telegram.messenger.purple.PurpleWorkMode$Generation");Constructor<?> ctor=generation.getDeclaredConstructors()[0];ctor.setAccessible(true);
  PurpleWorkMode.View[] views=new PurpleWorkMode.View[4];views[0]=view;
  Field current=publication.getClass().getDeclaredField("current");current.setAccessible(true);current.set(publication,ctor.newInstance(views,0L));
  Method packed=PurpleGate.class.getDeclaredMethod("packedFor",int.class,long.class);packed.setAccessible(true);
  if((Integer)packed.invoke(null,0,55L)!=0)throw new AssertionError("revealing cache fixture");
  if(a[0].equals("background")) {app.stop(activity);SystemClock.now=2000;AndroidUtilities.due();System.out.println("at2s="+PurpleGate.peeking());app.start(activity);System.out.println("lateResume="+PurpleGate.peeking());}
  else {SharedConfig.appLocked=true;PurpleGate.locked();System.out.println("locked="+PurpleGate.peeking());SharedConfig.appLocked=false;app.resume(activity);System.out.println("unlockResume="+PurpleGate.peeking());}
  if(PurpleWorkModeModel.visibleNative(handle,55,0)!=4)throw new AssertionError("native retained handle must hide");
  if(PurpleWorkMode.visible(0,55,0)!=4)throw new AssertionError("account visible must hide");
  if((Integer)packed.invoke(null,0,55L)!=4)throw new AssertionError("packed row must hide after cache invalidation");
  if(view.stories.get(55)!=null)throw new AssertionError("story cache retained a revealing answer");
  if(PurpleWorkModeModel.storyShownNative(handle,55,0,false,-1,false))throw new AssertionError("native story must hide");
  if(!PurpleGate.state().preset.equals("work")||PurpleGate.state().normal)throw new AssertionError("policy changed beyond Peek");
  System.out.println("retained handle, account visibility, row cache, story cache and native story PASS");
  System.out.println("actual reload caught source failures="+PurpleAccountOwner.refusals);
  System.out.println("javaPeeking="+PurpleGate.peeking()+" accountPacked="+PurpleWorkModeModel.visibleNative(handle,55,0)+" unpeekedPacked="+PurpleWorkModeModel.visibleUnpeekedNative(handle,55,0));
 }
}