package org.telegram.messenger;
public final class AndroidUtilities {
 public static final java.util.ArrayList<String> clipboard=new java.util.ArrayList<>();
 public static final java.util.ArrayDeque<Runnable> queued=new java.util.ArrayDeque<>();
 public static boolean addToClipboard(CharSequence text){clipboard.add(text.toString());return true;}
 public static void runOnUIThread(Runnable work){queued.add(work);}
 public static void flush(){while(!queued.isEmpty())queued.remove().run();}
}
