package org.telegram.tgnet;
import java.util.*;
public class ConnectionsManager {
 public static final int RequestFlagFailOnServerErrors=2;
 public static int now=1000;
 public static final ArrayList<Integer> cancelled=new ArrayList<>();
 public static final ArrayList<TLRPC.TL_messages_sendMessage> requests=new ArrayList<>();
 public static final ArrayList<RequestDelegate> callbacks=new ArrayList<>();
 public static ConnectionsManager getInstance(int account){return new ConnectionsManager();}
 public int getCurrentTime(){return now;}
 public void cancelRequest(int token,boolean notifyServer){cancelled.add(token);}
 public int sendRequest(TLObject object,RequestDelegate callback,int flags){
  TLRPC.TL_messages_sendMessage request=(TLRPC.TL_messages_sendMessage)object;
  java.io.File journal=new java.io.File(System.getProperty("scratch"),"media-reply-journal.json");
  if(!journal.isFile()&&android.util.AtomicFile.silentFault.isEmpty())throw new AssertionError("transport before durable journal");
  if(request.allow_paid_stars!=0||request.allow_paid_floodskip)throw new AssertionError("paid approval");
  if(flags!=RequestFlagFailOnServerErrors)throw new AssertionError("unbounded native error policy");
  requests.add(request);callbacks.add(callback);return requests.size();
 }
}
