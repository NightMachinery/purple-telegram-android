package org.telegram.messenger.purple;
import java.util.*;import java.util.function.Consumer;
public class PurpleRecordingRegistry {
 public static Set<String> bin=new HashSet<>();public static int retained,acknowledged;
 public static void retainRound(int a,int g,String p,long[] c,Consumer<String> done){String id="item"+(++retained);bin.add(id);done.accept(id);}
 public static void pendingRound(String id,Consumer<Boolean> done){done.accept(bin.contains(id));}
 public static void queuedRound(String id,Runnable done){if(bin.remove(id))acknowledged++;done.run();}
}
