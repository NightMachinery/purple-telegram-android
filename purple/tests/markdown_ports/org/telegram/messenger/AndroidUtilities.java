package org.telegram.messenger;
public final class AndroidUtilities {
 public static final java.util.ArrayList<String> clipboard=new java.util.ArrayList<>();
 public static final java.util.ArrayDeque<Runnable> queued=new java.util.ArrayDeque<>();
 public static final java.util.ArrayList<Runnable> delayed=new java.util.ArrayList<>();
 public static void runOnUIThread(Runnable work,long delay){delayed.add(work);}
 public static void cancelRunOnUIThread(Runnable work){queued.remove(work);delayed.remove(work);}
 public static boolean addToClipboard(CharSequence text){clipboard.add(text.toString());return true;}
 public static void runOnUIThread(Runnable work){queued.add(work);}
 public static void flush(){while(!queued.isEmpty())queued.remove().run();}
}
