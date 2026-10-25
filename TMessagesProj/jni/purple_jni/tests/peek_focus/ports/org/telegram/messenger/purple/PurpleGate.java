package org.telegram.messenger.purple;
import org.telegram.tgnet.TLRPC;import java.nio.charset.StandardCharsets;
public final class PurpleGate {
 public static byte[] settings="version=1\n[peek]\nstart_delay_seconds=2\n[presets.work]\n".getBytes(StandardCharsets.UTF_8);
 public static PurpleCore.Loaded loaded;public static int starts,reloads,legacyLocks;public static PeekSettings snapshot;public static boolean loadedOnce;
 public static final class PeekSettings {public PurpleCore.Loaded state;public PeekSettings(PurpleCore.Loaded s){state=s;}}
 public static boolean everLoaded(){return loadedOnce;}
 public static void reload(String why){if(rejectReload)return;if(Thread.holdsLock(PurplePeekForeground.class))throw new AssertionError("Gate reload under foreground monitor");loadedOnce=true;++reloads;PurpleCore.Loaded before=loaded;PurpleCore.Loaded next=PurpleCore.load(settings,PurpleState.read(),"fixture","android","mobile");loaded=PurplePeekForeground.resolve(next,PurpleState.read(),safe->PurpleCore.load(settings,safe,"fixture","android","mobile"));if(loaded==null)throw new AssertionError("resolution refused");snapshot=new PeekSettings(loaded);PurplePeekForeground.published();org.telegram.ui.PurplePeekControls.onGateReload(before,loaded);}
 public static PeekSettings peekSettings(){return snapshot;}public static boolean currentPeekSettings(PeekSettings s){return s!=null&&s==snapshot;}public static boolean currentPeekEditSettings(PeekSettings s){return currentPeekSettings(s);}
 public static boolean peeking(){return loaded!=null&&loaded.clock.peeking;}public static PurpleCore.Loaded state(){return loaded;}
 public static PurpleCore.PeekChange stopPeek(){PurpleCore.PeekChange c=PurplePeekForeground.stopState(PurpleState.read());reload("end");return c;}
 public static void locked(){PurplePeekForeground.appLocked();}public static void lockedLegacy(){++legacyLocks;if(!peeking())return;PurpleCore.PeekChange c=PurpleCore.locked(PurpleState.read(),false);if(c.text!=null){PurpleState.write(c.text.getBytes(StandardCharsets.UTF_8));reload("legacy lock");}}
 public static PurpleCore.PeekChange startPeek(int n,PeekSettings s){++starts;PurpleCore.PeekChange c=PurpleCore.startPeek(PurpleState.read(),n);PurpleState.write(c.text.getBytes(StandardCharsets.UTF_8));PurplePeekForeground.replaced();reload("start");return c;}
 public static PurpleCore.PeekChange extendPeek(int n,PeekSettings s){return PurpleCore.extendPeek(PurpleState.read(),n);}public static boolean setPreset(String s){return true;}
 public static boolean rejectReload;
 public static void withdrawPeek(){if(loaded==null||!loaded.clock.peeking)return;PurpleCore.Loaded before=loaded;loaded=loaded.withoutPeek();snapshot=new PeekSettings(loaded);org.telegram.ui.PurplePeekControls.onGateReload(before,loaded);}
 public static boolean hidden;public static boolean shownWithoutRecent(int a,TLRPC.Dialog d){return !hidden;}public static boolean hiddenEverywhereWithoutRecent(int a,TLRPC.Dialog d){return hidden;}public static boolean hidingArchive(int a){return false;}public static boolean reachableElsewhere(int a,long d,java.util.ArrayList<org.telegram.messenger.MessagesController.DialogFilter> o){return true;}
}
