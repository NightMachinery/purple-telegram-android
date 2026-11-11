package org.telegram.messenger.purple;
import java.nio.file.*;
import java.util.*;
public class PurpleAccountSettings {
 static long version=1;
 static final Map<String,Effective> cached=new HashMap<>();
 public static class Effective {
  final long handle,generation;final PurpleAccountFiles.Identity identity;
  Effective(long handle,int slot){this.handle=handle;generation=version;identity=PurpleAccountFiles.capture(slot);}
  boolean current(){return generation==version&&identity.current();}
 }
 public static long generation(){return version;}
 public static native long epochNative();
 static native void clearNative();
 static native long createNative(long epoch,byte[] global,String gp,byte[] gg,byte[] own,String ap,byte[] ag);
 public static void invalidate(){++version;cached.clear();clearNative();Runnable hook=NativeWriterFixture.afterRefresh;NativeWriterFixture.afterRefresh=null;if(hook!=null)hook.run();}
 public static Effective forAccount(int account){try{
  PurpleAccountFiles.Identity id=PurpleAccountFiles.capture(account);Effective found=cached.get(id.key);
  if(found!=null&&found.current())return found;
  java.io.File global=PurpleAccountFiles.resolve("0-101","0-101").settings,own=PurpleAccountFiles.resolve(id.key,"0-101").settings;
  found=new Effective(createNative(epochNative(),Files.readAllBytes(global.toPath()),global.getPath(),null,Files.readAllBytes(own.toPath()),own.getPath(),null),account);
  cached.put(id.key,found);return found;
 }catch(Exception e){throw new AssertionError(e);}}
}
