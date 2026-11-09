package org.telegram.messenger.purple;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
public final class PurpleMediaReplyStatusCases {
 static int checks;
 static void check(boolean value,String why){++checks;if(!value)throw new AssertionError(why);}
 static void observe(int id){TLRPC.Message message=new TLRPC.Message();message.id=id;message.date=ConnectionsManager.now;message.mediaKind=1;message.peer_id=new TLRPC.Peer();message.peer_id.user_id=17;PurpleMediaReply.observe(0,message,false,false);AndroidUtilities.drain();}
 public static void main(String[] args)throws Exception {
  System.load(args[0]);Path root=Paths.get(System.getProperty("scratch"));Files.createDirectories(root);
  PurpleAccountSettings.policy("[media_reply.private]\nvoice='both'\n");PurpleMediaReply.invalidate();AndroidUtilities.drain();ConnectionsManager.now=1001;
  if(args[1].equals("errors")){
   String[] types={"CHAT_WRITE_FORBIDDEN","FLOOD_WAIT_12","ALLOW_PAYMENT_REQUIRED_1","RPC_CALL_FAIL","RANDOM_ID_DUPLICATE"};
   PurpleMediaReply.Reason[] reasons={PurpleMediaReply.Reason.Permission,PurpleMediaReply.Reason.Flood,PurpleMediaReply.Reason.ApprovalRequired,PurpleMediaReply.Reason.Uncertain,PurpleMediaReply.Reason.Uncertain};
   for(int i=0;i<types.length;++i){
    observe(101+i);TLRPC.TL_error error=new TLRPC.TL_error();error.text=types[i];error.code=i==1?420:i==3?500:400;
    ConnectionsManager.callbacks.get(i).run(null,error);AndroidUtilities.drain();
    PurpleMediaReply.Status status=PurpleMediaReply.status(0);
    check(status.reason==reasons[i]&&status.inFlight==0&&status.reserved==i+1,"bounded native refusal status "+types[i]);
    observe(101+i);check(ConnectionsManager.requests.size()==i+1,"terminal error never retries");
   }
   PurpleMediaReply.observe(0,new TLRPC.Message(),false,false);
   PurpleAccountFiles.identity=new PurpleAccountFiles.Identity("0-43",2);
   TLRPC.TL_error late=new TLRPC.TL_error();late.text="ALLOW_PAYMENT_REQUIRED_99999";
   ConnectionsManager.callbacks.get(0).run(null,late);AndroidUtilities.drain();
   check(PurpleMediaReply.status(0).reason==PurpleMediaReply.Reason.None,"late refusal cannot publish another account reason");
  }else{
   Path journal=root.resolve("media-reply-journal.json");
   if(args[1].equals("corrupt"))Files.write(journal,new byte[]{1});
   else {
    JSONObject value=new JSONObject(PurpleMediaReply.planNative(PurpleAccountSettings.effective.handle,"0-42","user:17",0,1,0,1001,1,false,false,1000,1));
    JSONObject entry=value.getJSONArray("reserved").getJSONObject(0);JSONArray entries=new JSONArray();
    for(int i=1;i<=4096;++i){JSONObject copy=new JSONObject(entry.toString());copy.put("id",i);entries.put(copy);}value.put("reserved",entries);
    Files.write(journal,value.toString().getBytes(StandardCharsets.UTF_8));
   }
   observe(9000);PurpleMediaReply.Status status=PurpleMediaReply.status(0);
   check(ConnectionsManager.requests.isEmpty()&&status.failed,"unsafe/full journal refuses transport");
   check(status.reason==(args[1].equals("corrupt")?PurpleMediaReply.Reason.JournalUnreadable:PurpleMediaReply.Reason.JournalFull),"journal refusal is visible and bounded");
   check(status.reserved==(args[1].equals("corrupt")?-1:4096),"capacity is confirmed count or explicitly unknown");
  }
  System.out.println("MEDIA_REPLY_STATUS_CHECKS="+checks);
 }
}
