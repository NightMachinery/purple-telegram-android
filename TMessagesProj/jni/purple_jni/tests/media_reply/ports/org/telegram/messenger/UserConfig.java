package org.telegram.messenger;
public class UserConfig {
 public static final int MAX_ACCOUNT_COUNT=2;
 public static UserConfig getInstance(int slot){return new UserConfig();}
 public boolean isClientActivated(){return true;}
}
