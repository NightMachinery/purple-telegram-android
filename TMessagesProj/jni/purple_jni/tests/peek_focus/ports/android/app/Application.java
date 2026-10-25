package android.app;
import android.os.Bundle;import java.util.*;
public class Application extends android.content.Context {
 public interface ActivityLifecycleCallbacks {
 void onActivityCreated(Activity a,Bundle b);void onActivityStarted(Activity a);void onActivityResumed(Activity a);void onActivityPaused(Activity a);void onActivityStopped(Activity a);void onActivitySaveInstanceState(Activity a,Bundle b);void onActivityDestroyed(Activity a);
 }
 public final List<ActivityLifecycleCallbacks> callbacks=new ArrayList<>();
 public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){callbacks.add(c);}
 public void start(Activity a){for(ActivityLifecycleCallbacks c:new ArrayList<>(callbacks))c.onActivityStarted(a);}
 public void stop(Activity a){for(ActivityLifecycleCallbacks c:new ArrayList<>(callbacks))c.onActivityStopped(a);}
 public void resume(Activity a){for(ActivityLifecycleCallbacks c:new ArrayList<>(callbacks))c.onActivityResumed(a);}
 public void pause(Activity a){for(ActivityLifecycleCallbacks c:new ArrayList<>(callbacks))c.onActivityPaused(a);}
 public void destroy(Activity a){for(ActivityLifecycleCallbacks c:new ArrayList<>(callbacks))c.onActivityDestroyed(a);}
}
