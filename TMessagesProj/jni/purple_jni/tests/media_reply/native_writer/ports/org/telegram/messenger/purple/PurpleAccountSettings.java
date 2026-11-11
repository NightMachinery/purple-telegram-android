package org.telegram.messenger.purple;
import java.nio.file.*;
public class PurpleAccountSettings {
 static long version=1;
 public static class Effective {final long handle;final PurpleAccountFiles.Identity identity;Effective(long handle,int slot){this.handle=handle;identity=PurpleAccountFiles.capture(slot);}boolean current(){return identity.current();}}
 public static long generation(){return version;}
 public static native long epochNative();
 static native void clearNative();
 static native long createNative(long epoch,byte[] global,String gp,byte[] gg,byte[] own,String ap,byte[] ag);
 public static void invalidate(){++version;clearNative();Runnable hook=NativeWriterFixture.afterRefresh;NativeWriterFixture.afterRefresh=null;if(hook!=null)hook.run();}
 public static Effective forAccount(int account){try{java.io.File global=PurpleAccountFiles.resolve("0-101","0-101").settings,own=PurpleAccountFiles.resolve(PurpleAccountFiles.capture(account).key,"0-101").settings;return new Effective(createNative(epochNative(),Files.readAllBytes(global.toPath()),global.getPath(),null,Files.readAllBytes(own.toPath()),own.getPath(),null),account);}catch(Exception e){throw new AssertionError(e);}}
}
