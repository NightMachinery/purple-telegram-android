package org.telegram.messenger.purple;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
public final class PurplePlaylistsKeepSettingsCases {
 private static int checks;
 private static void check(boolean value, String reason) { ++checks; if (!value) throw new AssertionError(reason); }
 private static byte[] bytes(String text) { return text == null ? null : text.getBytes(StandardCharsets.UTF_8); }
 private static Object call(String name, Class<?>[] types, Object... values) throws Exception {
  Method method = PurpleAccountSettings.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(null, values);
 }
 private static PurpleAccountSettings.Effective effective(String global, String globalGood, String own, String ownGood,
         boolean globalReadError, boolean ownReadError) throws Exception {
  long epoch = (Long)call("epochNative", new Class<?>[]{});
  long handle = (Long)call("createNative", new Class<?>[]{long.class, byte[].class, String.class, byte[].class, byte[].class, String.class, byte[].class},
          epoch, bytes(global), "settings.toml", bytes(globalGood), bytes(own), "accounts/0-202/settings.toml", bytes(ownGood));
  check(handle != 0, "real shared layered-settings handle exists");
  try {
   JSONObject json = new JSONObject((String)call("describeNative", new Class<?>[]{long.class}, handle));
   Constructor<?> constructor = PurpleAccountSettings.Effective.class.getDeclaredConstructors()[0]; constructor.setAccessible(true);
   return (PurpleAccountSettings.Effective)constructor.newInstance(null, 0L, 0L, json, globalReadError, ownReadError, false, false);
  } finally { call("releaseNative", new Class<?>[]{long.class}, handle); }
 }
 public static void main(String[] args) throws Exception {
  System.load(System.getProperty("purple.test.library"));
  String off="version = 2\n[playlists]\nkeep_downloaded_p = false\n";
  String on="version = 2\n[playlists]\nkeep_downloaded_p = true\n";
  PurpleAccountSettings.Effective value=effective(null,null,null,null,false,false);
  check(value.playlistsReady && value.playlistsKeepDownloaded, "shared core default is true for absent settings");
  value=effective(off,null,on,null,false,false);
  check(value.playlistsReady && !value.playlistsKeepDownloaded, "account-local override cannot change app-wide keep default");
  value=effective(on,null,off,null,false,false);
  check(value.playlistsReady && value.playlistsKeepDownloaded, "global app-wide On survives account-local Off");
  value=effective("[broken",off,null,null,false,false);
  check(value.playlistsReady && value.globalGood && !value.playlistsKeepDownloaded, "global last-good provenance supplies shared keep default");
  value=effective("[broken",null,null,null,false,false);
  check(!value.playlistsReady, "malformed current without last-good never becomes confident default On");
  value=effective(off,null,"[broken",null,false,false);
  check(!value.playlistsReady, "malformed account layer without last-good remains unavailable");
  value=effective(null,null,null,null,true,false);
  check(!value.playlistsReady, "unreadable settings do not become a confident default On");
  value=effective(off,null,null,null,false,true);
  check(!value.playlistsReady, "unreadable account layer is reported unavailable");
  System.out.println("PLAYLIST_KEEP_SETTINGS_CHECKS="+checks);
 }
}
