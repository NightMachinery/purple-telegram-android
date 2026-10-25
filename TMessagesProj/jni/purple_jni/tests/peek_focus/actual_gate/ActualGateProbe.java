import java.lang.reflect.*;import android.app.*;import android.os.SystemClock;import org.telegram.messenger.*;import org.telegram.messenger.purple.*;
public class ActualGateProbe {
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
  if(a[0].equals("background")) {app.stop(activity);SystemClock.now=2000;AndroidUtilities.due();if(PurpleGate.peeking())throw new AssertionError("revealing at2s");System.out.println("at2s=false");app.start(activity);if(PurpleGate.peeking())throw new AssertionError("revealing on late resume");System.out.println("lateResume=false");}
  else {SharedConfig.appLocked=true;PurpleGate.locked();if(PurpleGate.peeking())throw new AssertionError("revealing on lock");System.out.println("locked=false");SharedConfig.appLocked=false;app.resume(activity);if(PurpleGate.peeking())throw new AssertionError("revealing after unlock");System.out.println("unlockResume=false");}
  System.out.println("actual reload caught source failures="+PurpleAccountOwner.refusals);
 }
}