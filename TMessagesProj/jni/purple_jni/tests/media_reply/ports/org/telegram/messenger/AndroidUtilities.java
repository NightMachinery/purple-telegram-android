package org.telegram.messenger;
public class AndroidUtilities {
 public static final java.util.ArrayList<Runnable> tasks=new java.util.ArrayList<>();
 public static synchronized void runOnUIThread(Runnable r){tasks.add(r);}
 public static synchronized void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}
}
