package org.telegram.ui;
import java.lang.reflect.*;import java.util.*;import android.content.*;import android.app.*;import android.os.SystemClock;import org.telegram.messenger.*;import org.telegram.messenger.purple.*;import org.telegram.ui.ActionBar.BaseFragment;
public class PersistenceProbe {
 static class DurablePrefs extends PeekPreferences {
  Map<String,Object> disk=new HashMap<>();boolean loseCounter;
  @Override public SharedPreferences.Editor edit(){SharedPreferences.Editor real=super.edit();final String[] streak={null};final Object[] proxy={null};
   proxy[0]=Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.Editor.class},(o,m,a)->{
    if(m.getName().equals("putString")&&a[0].equals("streak"))streak[0]=(String)a[1];
    Object result;try{result=m.invoke(real,a);}catch(InvocationTargetException e){throw e.getCause();}
    if(m.getName().equals("commit")){
     if(loseCounter&&streak[0]!=null&&PurplePeekStreakModel.decode(streak[0]).count>0)return false;
     disk.clear();disk.putAll(values);return result;
    }
    return result instanceof SharedPreferences.Editor?proxy[0]:result;
   });return (SharedPreferences.Editor)proxy[0];
  }
 }
 static class App extends Application {final DurablePrefs durable=new DurablePrefs();@Override public SharedPreferences getSharedPreferences(String n,int m){return durable;}}
 static void set(Class<?> c,String n,Object value)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(null,value);}
 static BaseFragment setup()throws Exception {Method m=PeekFocusCases.class.getDeclaredMethod("setup",boolean.class,boolean.class,boolean.class);m.setAccessible(true);m.invoke(null,false,false,false);Field f=PeekFocusCases.class.getDeclaredField("fragment");f.setAccessible(true);return (BaseFragment)f.get(null);}
 static void complete()throws Exception {Field f=PurplePeekControls.class.getDeclaredField("pending");f.setAccessible(true);Object p=f.get(null);Method m=p.getClass().getDeclaredMethod("complete");m.setAccessible(true);m.invoke(p);}
 public static void main(String[] args)throws Exception {
  BaseFragment f=setup();App app=new App();ApplicationLoader.applicationContext=app;set(PurplePeekLocalSettings.class,"current",null);set(PurplePeekStreak.class,"state",null);
  if(args[0].equals("failed-success")) {
   PurplePeekControls.start(f,60);app.durable.loseCounter=true;SystemClock.now=2000;complete();
   System.out.println("successfulPeek="+PurpleGate.peeking()+" persistedCount="+PurplePeekStreakModel.decode((String)app.durable.disk.get("streak")).count+" processRefuses="+(PurplePeekStreak.delay(2,PurpleGate.state())==null));
   app.durable.values.clear();app.durable.values.putAll(app.durable.disk);app.durable.loseCounter=false;
   set(PurplePeekStreak.class,"state",null);set(PurplePeekStreak.class,"failed",false);set(PurplePeekStreak.class,"boot",null);set(PurplePeekLocalSettings.class,"current",null);
   PurplePeekStreak.Delay d=PurplePeekStreak.delay(2,PurpleGate.state());System.out.println("afterProcessRestart extra="+d.extra+" effective="+d.seconds+" expectedExtra=10");
  } else {
   app.durable.values.put("step",-1);app.durable.values.put("max_extra",-1);app.durable.values.put("reset_minutes",-1);app.durable.values.put("streak","1;5;0;0;0;0;0;count-1");
   PurplePeekStreak.Delay d=PurplePeekStreak.delay(2,PurpleGate.state());PurplePeekLocalSettings.Snapshot s=PurplePeekLocalSettings.snapshot();
   System.out.println("malformedPolicy accepted: step="+s.step+" maxExtra="+s.maxExtra+" resetMinutes="+s.resetMinutes+" delay="+d.seconds+" extra="+d.extra);
  }
 }
}
