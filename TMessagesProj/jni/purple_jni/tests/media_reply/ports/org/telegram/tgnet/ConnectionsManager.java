package org.telegram.tgnet;
import java.util.*;
public class ConnectionsManager {
 public static final int RequestFlagFailOnServerErrors=1;
 public static int now=1000;
 public static final ArrayList<TLRPC.TL_messages_sendMessage> requests=new ArrayList<>();
 public static final ArrayList<RequestDelegate> callbacks=new ArrayList<>();
 public static ConnectionsManager getInstance(int account){return new ConnectionsManager();}
 public int getCurrentTime(){return now;}
 public int sendRequest(TLObject object,RequestDelegate callback,int flags){
  TLRPC.TL_messages_sendMessage request=(TLRPC.TL_messages_sendMessage)object;
  java.io.File journal=new java.io.File(System.getProperty("scratch"),"media-reply-journal.json");
  if(!journal.isFile())throw new AssertionError("transport before durable journal");
  if(request.allow_paid_stars!=0||request.allow_paid_floodskip)throw new AssertionError("paid approval");
  requests.add(request);callbacks.add(callback);return requests.size();
 }
}
