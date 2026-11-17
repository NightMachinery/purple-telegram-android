package org.telegram.messenger.purple;
public final class PurpleAccountSettings {
 public static final class Effective {
  public boolean playlistsReady = true, playlistsKeepDownloaded = true;
  boolean current() { return available; }
 }
 static boolean available = true;
 static final Effective value = new Effective();
 public static Effective forAccount(int account) { return available ? value : null; }
 public static void invalidate() { }
}
