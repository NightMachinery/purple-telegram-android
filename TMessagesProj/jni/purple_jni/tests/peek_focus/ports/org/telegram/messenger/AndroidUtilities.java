package org.telegram.messenger;
import java.util.*;
public final class AndroidUtilities {
 public static final Map<Runnable,Long> delayed=new LinkedHashMap<>();
 public static int dp(float n){return (int)n;}public static void runOnUIThread(Runnable r){r.run();}
 public static void runOnUIThread(Runnable r,long delay){delayed.put(r,android.os.SystemClock.now+delay);}
 public static void cancelRunOnUIThread(Runnable r){delayed.remove(r);}
 public static void due(){for(Map.Entry<Runnable,Long> e:new ArrayList<>(delayed.entrySet()))if(e.getValue()<=android.os.SystemClock.now){delayed.remove(e.getKey());e.getKey().run();}}
}
