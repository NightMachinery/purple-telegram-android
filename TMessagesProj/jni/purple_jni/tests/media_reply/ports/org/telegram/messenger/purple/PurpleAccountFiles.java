package org.telegram.messenger.purple;
import java.io.File;
public class PurpleAccountFiles {
 public static Identity identity=new Identity("0-42",1);
 public static class Identity {public final int slot=0;public final String key;public final long lifetime;public Identity(String key,long lifetime){this.key=key;this.lifetime=lifetime;}public boolean current(){return identity==this;}}
 public static Identity capture(int account){return identity;}
 public final File directory=new File(System.getProperty("scratch"));
 public static PurpleAccountFiles resolve(String key,String owner){return key.equals(identity.key)?new PurpleAccountFiles():null;}
}
