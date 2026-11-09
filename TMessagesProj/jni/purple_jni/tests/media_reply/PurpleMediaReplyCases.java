package org.telegram.messenger.purple;
import org.telegram.tgnet.*;
import org.telegram.messenger.*;
import java.nio.file.*;
public final class PurpleMediaReplyCases {
 static int checks;
 static void check(boolean value,String message){++checks;if(!value)throw new AssertionError(message);}
 static TLRPC.Message message(int id,int media,boolean own){TLRPC.Message m=new TLRPC.Message();m.id=id;m.date=1001;m.mediaKind=media;m.out=own;m.peer_id=new TLRPC.Peer();m.peer_id.user_id=17;return m;}
 static void observe(TLRPC.Message m,boolean ack,boolean history){PurpleMediaReply.observe(0,m,ack,history);AndroidUtilities.drain();}
 static TLRPC.Message fresh(int id){TLRPC.Message m=message(id,1,false);m.date=ConnectionsManager.now;return m;}
 static void account(String key,long lifetime){PurpleAccountFiles.identity=new PurpleAccountFiles.Identity(key,lifetime);}
 static void policy(String text){PurpleAccountSettings.policy(text);PurpleMediaReply.invalidate();AndroidUtilities.drain();}
 static String journal()throws Exception{return new String(Files.readAllBytes(Paths.get(System.getProperty("scratch"),"media-reply-journal.json")),java.nio.charset.StandardCharsets.UTF_8);}
 public static void main(String[] args)throws Exception {
  check(new java.io.File(PurpleMediaReply.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new java.io.File(args[1]).getCanonicalFile()),"actual AGP adapter class");
  System.load(args[0]);Files.createDirectories(Paths.get(System.getProperty("scratch")));
  policy("[media_reply.private]\nvoice='both'\n[media_reply.groups]\nvoice='both'\n");
  TLRPC.Message cutoff=message(100,1,false);cutoff.date=1000;observe(cutoff,false,false);
  check(ConnectionsManager.requests.isEmpty(),"activation cutoff");
  observe(message(99,1,false),false,false);check(ConnectionsManager.requests.isEmpty(),"future timestamp is excluded");
  ConnectionsManager.now=1001;
  TLRPC.Message incoming=message(101,1,false);incoming.peer_id.user_id=42;incoming.from_id=new TLRPC.Peer();incoming.from_id.user_id=17;
  observe(incoming,false,false);check(ConnectionsManager.requests.size()==1,"new voice request");
  TLRPC.TL_messages_sendMessage sent=ConnectionsManager.requests.get(0);
  check(sent.peer.id==17&&sent.reply_to.reply_to_msg_id==101,"native inbound chat resolver and exact reply");
  check(sent.message.equals("@TranscribeThisAI")&&sent.random_id==0x5055524100000065L,"shared text and dedup ID");
  check(journal().contains("101")&&journal().contains("@TranscribeThisAI"),"frozen reservation before transport");
  observe(incoming,false,false);observe(incoming,true,false);
  check(ConnectionsManager.requests.size()==1,"repeat update and incoming ACK suppressed");
  observe(message(102,3,false),false,false);observe(message(103,1,false),false,true);
  observe(message(-1,1,true),true,false);observe(message(104,2,true),true,true);
  TLRPC.Message unsent=message(105,1,true);unsent.send_state=1;observe(unsent,true,false);
  check(ConnectionsManager.requests.size()==1,"ordinary video history local unsent scheduled controls");
  TLRPC.Message outgoing=message(106,2,true);outgoing.peer_id.user_id=0;outgoing.peer_id.channel_id=17;outgoing.reply_to=new TLRPC.MessageReplyHeader();outgoing.reply_to.reply_to_top_id=91;
  observe(outgoing,true,false);
  check(ConnectionsManager.requests.size()==2&&ConnectionsManager.requests.get(1).reply_to.top_msg_id==91&&ConnectionsManager.requests.get(1).peer.id==-17,"ACK round retains original forum peer and topic");
  observe(outgoing,false,false);observe(outgoing,true,false);
  check(ConnectionsManager.requests.size()==2,"outgoing ACK and update share dedup");
  PurpleMediaReply.observe(0,message(107,1,false),false,false);
  PurpleAccountSettings.policy("[media_reply]\ntext='changed'\n[media_reply.private]\nvoice='both'\n");PurpleMediaReply.invalidate();AndroidUtilities.drain();
  check(ConnectionsManager.requests.size()==2,"rule replacement revokes queued callback");
  check(ConnectionsManager.cancelled.size()==2&&ConnectionsManager.cancelled.contains(1)&&ConnectionsManager.cancelled.contains(2),"replacement cancels exact owned enqueued tokens");
  ConnectionsManager.callbacks.get(0).run(new TLRPC.Updates(),null);ConnectionsManager.callbacks.get(1).run(null,new TLRPC.TL_error());AndroidUtilities.drain();
  check(ConnectionsManager.requests.size()==2,"late success/error after cancellation do not renew send");
  observe(incoming,false,false);check(ConnectionsManager.requests.size()==2,"cancelled uncertain acceptance remains reserved");
  ConnectionsManager.now=1002;PurpleMediaReply.invalidate();ConnectionsManager.now=1003;
  PurpleMediaReply.observe(0,fresh(108),false,false);account("0-43",2);PurpleMediaReply.invalidate();AndroidUtilities.drain();
  check(ConnectionsManager.requests.size()==2,"account replacement revokes queued callback");
  int reconciled=MessagesController.reconciled;ConnectionsManager.callbacks.get(0).run(new TLRPC.Updates(),null);AndroidUtilities.drain();
  check(MessagesController.reconciled==reconciled,"late response does not reconcile another account");
  account("0-42",3);ConnectionsManager.now=1004;policy("[media_reply.private]\nvoice='both'\n");ConnectionsManager.now=1005;
  TLRPC.Message resumed=fresh(108);observe(resumed,false,false);
  check(ConnectionsManager.requests.size()==3,"new lifetime loads journal and admits new message");
  String duplicate=PurpleMediaReply.planNative(PurpleAccountSettings.effective.handle,"0-42","user:17",0,101,0,1001,1,false,false,1000,1);
  check(duplicate!=null&&PurpleMediaReply.reserveNative(journal(),duplicate)==null,"restart retains uncertain reservation");
  account("0-43",4);PurpleMediaReply.invalidate();AndroidUtilities.drain();
  check(ConnectionsManager.cancelled.contains(3),"account replacement cancels its frozen connection token");
  reconciled=MessagesController.reconciled;ConnectionsManager.callbacks.get(2).run(new TLRPC.Updates(),null);ConnectionsManager.callbacks.get(2).run(null,new TLRPC.TL_error());AndroidUtilities.drain();
  check(ConnectionsManager.requests.size()==3&&MessagesController.reconciled==reconciled,"late replaced-account outcomes stay inert");
  account("0-42",5);ConnectionsManager.now=1006;policy("[media_reply.private]\nvoice='both'\n");ConnectionsManager.now=1007;
  observe(fresh(111),false,false);check(ConnectionsManager.requests.size()==4,"fresh action after cancellation");
  int before=ConnectionsManager.requests.size();
  for(int i=0;i<9;++i)PurpleMediaReply.observe(0,fresh(200+i),false,false);AndroidUtilities.drain();
  check(ConnectionsManager.requests.size()==before+7,"in-flight queue is bounded at eight");
  ConnectionsManager.now=1008;PurpleMediaReply.invalidate();AndroidUtilities.drain();ConnectionsManager.now=1009;
  check(ConnectionsManager.cancelled.size()==11,"cancellation retires all and only eleven owned tokens");
  observe(fresh(300),false,false);check(ConnectionsManager.requests.size()==12,"cancellation releases local capacity once");
  policy("[media_reply.private]\nvoice='off'\n");
  check(ConnectionsManager.cancelled.size()==12&&ConnectionsManager.cancelled.contains(12),"disable after enqueue cancels its exact token");
  ConnectionsManager.callbacks.get(11).run(new TLRPC.Updates(),null);ConnectionsManager.callbacks.get(11).run(null,new TLRPC.TL_error());AndroidUtilities.drain();
  observe(fresh(300),false,false);check(ConnectionsManager.requests.size()==12,"late disabled outcomes never renew send");
  ConnectionsManager.now=1010;policy("[media_reply.private]\nvoice='both'\n");ConnectionsManager.now=1011;
  android.system.Os.fault="sync";observe(fresh(301),false,false);
  check(ConnectionsManager.requests.size()==12,"journal write failure prevents transport");
  android.system.Os.fault="";observe(fresh(302),false,false);
  check(ConnectionsManager.requests.size()==12,"journal failure remains fail closed");
  check(PurpleMediaReply.status(0).reason==PurpleMediaReply.Reason.StorageRefused,"storage refusal visible for frozen account");
  account("0-43",6);check(PurpleMediaReply.status(0).reason==PurpleMediaReply.Reason.None,"replacement account cannot inherit failure status");
  System.out.println("MEDIA_REPLY_ADAPTER_CHECKS="+checks);
 }
}
