package org.telegram.tgnet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
public final class ConnectionsManager {
 public static final int ConnectionTypeDownload=2;
 public static final ConnectionsManager[] instances={new ConnectionsManager(),new ConnectionsManager(),new ConnectionsManager(),new ConnectionsManager()};
 public static ConnectionsManager getInstance(int slot){return instances[slot];}
 public boolean test; public boolean isTestBackend(){return test;}
 public static final class Sent {
  public final TLObject request;public final RequestDelegate done;public final int token,dc;
  Sent(TLObject request,RequestDelegate done,int token,int dc){this.request=request;this.done=done;this.token=token;this.dc=dc;}
 }
 public volatile java.util.function.Consumer<Sent> beforeSend;
 public final BlockingQueue<Sent> sent=new LinkedBlockingQueue<>();
 public final CopyOnWriteArraySet<Integer> canceled=new CopyOnWriteArraySet<>();
 private final AtomicInteger next=new AtomicInteger(1);
 public int sendRequest(TLObject request,RequestDelegate done,QuickAckDelegate ack,WriteToSocketDelegate write,int flags,int dc,int type,boolean immediate){
  int token=next.getAndIncrement();Sent value=new Sent(request,done,token,dc);if(beforeSend!=null)beforeSend.accept(value);sent.add(value);return token;
 }
 public int sendRequest(TLObject request,RequestDelegate done){return sendRequest(request,done,null,null,0,0,0,true);}
 public void cancelRequest(int token,boolean notify){canceled.add(token);}
}
