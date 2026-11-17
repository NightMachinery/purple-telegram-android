package org.telegram.messenger;
public final class UserConfig {
 public static final int MAX_ACCOUNT_COUNT=4; public static int selectedAccount;
 public static final UserConfig[] instances={new UserConfig(),new UserConfig(),new UserConfig(),new UserConfig()};
 public boolean active; public long user;
 public static UserConfig getInstance(int slot){return instances[slot];}
 public boolean isClientActivated(){return active;} public long getClientUserId(){return user;}
}
