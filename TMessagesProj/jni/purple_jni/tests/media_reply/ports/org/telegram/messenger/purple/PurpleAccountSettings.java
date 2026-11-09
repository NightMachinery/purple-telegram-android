package org.telegram.messenger.purple;
import java.nio.charset.StandardCharsets;
public class PurpleAccountSettings {
 public static class Effective {final long handle;final PurpleAccountFiles.Identity identity;Effective(long handle){this.handle=handle;identity=PurpleAccountFiles.identity;}boolean current(){return identity.current();}}
 static Effective effective;
 public static Effective forAccount(int account){return effective;}
 static native long epochNative();
 static native long createNative(long epoch,byte[] global,String gp,byte[] gg,byte[] own,String ap,byte[] ag);
 public static void policy(String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);effective=new Effective(createNative(epochNative(),bytes,"fixture",null,bytes,"fixture",null));if(effective.handle==0)throw new AssertionError("native snapshot");}
}
