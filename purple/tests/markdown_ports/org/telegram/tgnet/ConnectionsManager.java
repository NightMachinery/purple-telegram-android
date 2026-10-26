package org.telegram.tgnet;
import java.util.concurrent.Executor;
import org.telegram.messenger.Utilities;
public final class ConnectionsManager {
 private static final ConnectionsManager[] accounts={new ConnectionsManager(),new ConnectionsManager(),new ConnectionsManager(),new ConnectionsManager()};
 public static ConnectionsManager getInstance(int slot){return accounts[slot];}
 public boolean isTestBackend(){return false;}
 public final java.util.ArrayList<Pending> requests=new java.util.ArrayList<>();
 public static final class Pending {final Executor executor;final Utilities.Callback2 callback;public boolean cancelled;Pending(Executor executor,Utilities.Callback2 callback){this.executor=executor;this.callback=callback;}}
 public <T extends TLObject> int sendRequestTyped(TLMethod<T> method,Executor executor,Utilities.Callback2<T,TLRPC.TL_error> completion){requests.add(new Pending(executor,completion));return requests.size();}
 public void bindRequestToGuid(int request,int guid){}
 public void cancelRequest(int request,boolean notify){if(request>0&&request<=requests.size())requests.get(request-1).cancelled=true;}
 public void deliver(int index,TLObject value){Pending p=requests.get(index);p.executor.execute(()->p.callback.run(value,null));}
}
