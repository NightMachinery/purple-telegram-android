package org.telegram.messenger.purple;
public final class PurpleCore {
 private static boolean loaded;
 public static synchronized void ensureLoaded(){if(!loaded){System.load(System.getProperty("purple.test.core"));loaded=true;}}
}
