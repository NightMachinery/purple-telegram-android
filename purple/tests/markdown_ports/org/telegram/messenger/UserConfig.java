package org.telegram.messenger;
public final class UserConfig {
 public static final int MAX_ACCOUNT_COUNT=4;
 public static int selectedAccount;
 private static final UserConfig[] accounts={new UserConfig(),new UserConfig(),new UserConfig(),new UserConfig()};
 public long user=7; public boolean active=true;
 public static UserConfig getInstance(int slot){return accounts[slot];}
 public long getClientUserId(){return user;}
 public boolean isClientActivated(){return active;}
}
