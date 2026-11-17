package org.telegram.messenger;
public final class AndroidUtilities { public static void runOnUIThread(Runnable action){action.run();}  public static void runOnUIThread(Runnable action,long delay){runOnUIThread(action);}
}
