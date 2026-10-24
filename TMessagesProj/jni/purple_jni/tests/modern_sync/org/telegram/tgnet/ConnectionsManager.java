package org.telegram.tgnet;
public final class ConnectionsManager {
    private static final ConnectionsManager[] accounts=new ConnectionsManager[8];
    static { for(int i=0;i<accounts.length;++i)accounts[i]=new ConnectionsManager(); }
    public boolean test;
    public static ConnectionsManager getInstance(int account){return accounts[account];}
    public boolean isTestBackend(){return test;}
    public long getCurrentTimeMillis(){return 1800000000000L;}
}
