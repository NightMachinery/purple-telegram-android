package androidx.core.content.pm;
import android.content.Context; import java.util.*;
public final class ShortcutManagerCompat {
 public static final String EXTRA_SHORTCUT_ID="android.intent.extra.shortcut.ID";
 public static final int FLAG_MATCH_DYNAMIC=2,FLAG_MATCH_PINNED=4,FLAG_MATCH_CACHED=8; public static int limit=5,adds,updates,removes,pushes; public static Runnable onRead; public static final Map<String,ShortcutInfoCompat> targets=new LinkedHashMap<>();
 public static final Map<String,ShortcutInfoCompat> pins=new LinkedHashMap<>();
 public static void pin(ShortcutInfoCompat x){ShortcutInfoCompat copy=new ShortcutInfoCompat.Builder(x).build();copy.dynamic=false;copy.cached=false;pins.put(x.id,copy);}
 public static List<ShortcutInfoCompat> getShortcuts(Context c,int flags){if(onRead!=null){Runnable r=onRead;onRead=null;r.run();}Map<String,ShortcutInfoCompat> result=new LinkedHashMap<>(targets);if((flags&FLAG_MATCH_PINNED)!=0)for(ShortcutInfoCompat pin:pins.values())result.putIfAbsent(pin.id,pin);return new ArrayList<>(result.values());}
 public static List<ShortcutInfoCompat> getDynamicShortcuts(Context c){ArrayList<ShortcutInfoCompat> a=new ArrayList<>();for(ShortcutInfoCompat x:targets.values())if(x.dynamic)a.add(x);return a;}
 public static int getMaxShortcutCountPerActivity(Context c){return limit;}
 public static boolean addDynamicShortcuts(Context c,List<ShortcutInfoCompat> a){++adds;for(ShortcutInfoCompat x:a){x.dynamic=true;targets.put(x.id,x);}check();return true;}
 public static boolean updateShortcuts(Context c,List<ShortcutInfoCompat> a){++updates;for(ShortcutInfoCompat x:a){if(!targets.containsKey(x.id))throw new AssertionError("update absent");targets.put(x.id,x);}check();return true;}
 public static void removeDynamicShortcuts(Context c,List<String> ids){++removes;for(String id:ids)targets.remove(id);}
 public static void removeLongLivedShortcuts(Context c,List<String> ids){++removes;for(String id:ids)targets.remove(id);}
 public static boolean pushDynamicShortcut(Context c,ShortcutInfoCompat x){++pushes;targets.put(x.id,x);return true;}
 public static int calls(){return adds+updates+removes+pushes;}
 static void check(){int n=0;for(ShortcutInfoCompat x:targets.values())if(x.dynamic)++n;if(n>limit)throw new AssertionError("OS limit exceeded");}
}
