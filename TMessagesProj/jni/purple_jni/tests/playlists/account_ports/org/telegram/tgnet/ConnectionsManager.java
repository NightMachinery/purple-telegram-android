package org.telegram.tgnet;
public final class ConnectionsManager {
 public static final ConnectionsManager[] instances={new ConnectionsManager(),new ConnectionsManager(),new ConnectionsManager(),new ConnectionsManager()};
 public boolean test; public static ConnectionsManager getInstance(int slot){return instances[slot];}
 public boolean isTestBackend(){return test;}
}
