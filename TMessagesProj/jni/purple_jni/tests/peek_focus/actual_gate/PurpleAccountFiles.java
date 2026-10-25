package org.telegram.messenger.purple;
public class PurpleAccountFiles {
 public static java.io.File root(){return new java.io.File(System.getProperty("java.io.tmpdir"));}
 public static final class Identity {
  public final int slot=0;public final String key="0-101";public final long lifetime=0,userId=101;
  public boolean current(){return true;}boolean matchesSlot(){return true;}
 }
}
