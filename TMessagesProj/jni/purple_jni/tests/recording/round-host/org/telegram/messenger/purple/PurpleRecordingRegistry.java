package org.telegram.messenger.purple;
import java.util.*;import java.util.function.Consumer;
public class PurpleRecordingRegistry {
 public static long durationMs=1000; public static boolean incomplete;public static int attempts,failed;public static Set<String> bin=new HashSet<>();public static int retained,acknowledged;
 public static void retainRound(int a,int g,String p,long[] c,Consumer<String> done){attempts++;if(incomplete){done.accept(null);return;}String id="item"+(++retained);bin.add(id);done.accept(id);}
 public static void retainFailedRound(int a,int g,String p,long[] c,Consumer<String> done){failed++;String id="item"+(++retained);bin.add(id);done.accept(id);}
 public static void retainedRound(String id,Consumer<PurpleRecordingEntry> done){PurpleRecordingEntry item=new PurpleRecordingEntry();item.durationMs=durationMs;done.accept(bin.contains(id)?item:null);}
 public static void pendingRound(String id,Consumer<Boolean> done){done.accept(bin.contains(id));}
 public static void queuedRound(String id,Runnable done){if(bin.remove(id))acknowledged++;done.run();}
}
