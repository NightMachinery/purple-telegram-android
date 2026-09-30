package org.telegram.tgnet;

public class ConnectionsManager {
    public static volatile long currentTimeMillis = 1800000000000L;
    private static final ConnectionsManager INSTANCE = new ConnectionsManager();

    public static ConnectionsManager getInstance(int num) {
        return INSTANCE;
    }

    public long getCurrentTimeMillis() {
        return currentTimeMillis;
    }
}
