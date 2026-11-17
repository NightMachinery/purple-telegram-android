package org.telegram.messenger;
import java.util.ArrayDeque;
public final class AndroidUtilities {
 public static final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
 public static void runOnUIThread(Runnable task,long delay){if(delay<=0)throw new AssertionError("Resolver must yield");tasks.add(task);}
 public static void flush(){int n=0;while(!tasks.isEmpty()){if(++n>10000)throw new AssertionError("Unbounded queue");tasks.remove().run();}}
}