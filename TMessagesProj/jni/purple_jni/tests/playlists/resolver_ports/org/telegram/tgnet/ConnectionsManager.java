package org.telegram.tgnet;
import java.util.*;
public final class ConnectionsManager {
 public interface Callback{void run(TLObject value,TLRPC.TL_error error);}
 public static final ConnectionsManager[] slots={new ConnectionsManager(),new ConnectionsManager()};
 public final ArrayList<TLObject> requests=new ArrayList<>();public final ArrayList<Callback> replies=new ArrayList<>();public final ArrayList<Integer> cancelled=new ArrayList<>();
 public static ConnectionsManager getInstance(int slot){return slots[slot];}
 public int sendRequest(TLObject value,Callback callback){requests.add(value);replies.add(callback);return requests.size();}
 public void cancelRequest(int id,boolean server){cancelled.add(id);}
}