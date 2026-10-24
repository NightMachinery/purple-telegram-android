package org.telegram.messenger;
public final class UserConfig {
    public static final int MAX_ACCOUNT_COUNT=4;
    public static int selectedAccount;
    private static final UserConfig[] accounts=new UserConfig[MAX_ACCOUNT_COUNT];
    static { for(int i=0;i<accounts.length;++i)accounts[i]=new UserConfig(); }
    public boolean active;
    public long user;
    public static UserConfig getInstance(int slot){return accounts[slot];}
    public boolean isClientActivated(){return active;}
    public long getClientUserId(){return user;}
}
