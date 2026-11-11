package org.telegram.messenger.purple;
import java.io.File;
public class PurpleAccountFiles {
 public static Identity[] identities;
 public static class Identity {public final int slot;public final String key;public final long lifetime;Identity(int slot,String key,long lifetime){this.slot=slot;this.key=key;this.lifetime=lifetime;}public boolean current(){return identities[slot]==this;}}
 public final File settings,directory;
 PurpleAccountFiles(File file){settings=file;directory=file.getParentFile();}
 public static File root(){return NativeWriterFixture.root;}
 public static Identity capture(int account){return identities[account];}
 public static PurpleAccountFiles resolve(String key,String owner){return new PurpleAccountFiles(key.equals("0-101")?new File(root(),"settings.toml"):new File(root(),"accounts/"+key+"/settings.toml"));}
}
