package org.telegram.messenger.purple;
import org.telegram.tgnet.*;
import org.telegram.messenger.*;
import java.nio.file.*;
public final class PurpleMediaReplyCases {
 static int checks;
 static void check(boolean value,String message){++checks;if(!value)throw new AssertionError(message);}
 static TLRPC.Message message(int id,int media,boolean own){TLRPC.Message m=new TLRPC.Message();m.id=id;m.date=1001;m.mediaKind=media;m.out=own;m.peer_id=new TLRPC.Peer();m.peer_id.user_id=17;return m;}
 static void observe(TLRPC.Message m,boolean ack,boolean history){PurpleMediaReply.observe(0,m,ack,history);AndroidUtilities.drain();}
 public static void main(String[] args)throws Exception {
  check(new java.io.File(PurpleMediaReply.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new java.io.File(args[1]).getCanonicalFile()),"actual AGP adapter class");
  System.load(args[0]);
  Files.createDirectories(Paths.get(System.getProperty("scratch")));
  PurpleAccountSettings.policy("[media_reply.private]\nvoice='both'\n");
  PurpleMediaReply.invalidate();
  TLRPC.Message cutoff=message(100,1,false);cutoff.date=1000;observe(cutoff,false,false);
  check(ConnectionsManager.requests.isEmpty(),"activation cutoff");
  TLRPC.Message incoming=message(101,1,false);observe(incoming,false,false);
  check(ConnectionsManager.requests.size()==1,"new voice request");
  TLRPC.TL_messages_sendMessage sent=ConnectionsManager.requests.get(0);
  check(sent.peer.id==17&&sent.reply_to.reply_to_msg_id==101,"exact native reply");
  check(sent.message.equals("@TranscribeThisAI")&&sent.random_id==0x5055524100000065L,"shared text and dedup ID");
  String saved=new String(Files.readAllBytes(Paths.get(System.getProperty("scratch"),"media-reply-journal.json")),java.nio.charset.StandardCharsets.UTF_8);
  check(saved.contains("101")&&saved.contains("@TranscribeThisAI"),"frozen reservation precedes transport");
  observe(incoming,false,false);observe(incoming,true,false);
  check(ConnectionsManager.requests.size()==1,"repeat update and incoming ACK suppressed");
  observe(message(102,3,false),false,false);observe(message(103,1,false),false,true);
  observe(message(-1,1,true),true,false);observe(message(104,2,true),true,true);
  TLRPC.Message unsent=message(105,1,true);unsent.send_state=1;observe(unsent,true,false);
  check(ConnectionsManager.requests.size()==1,"ordinary video history local unsent scheduled negative controls");
  TLRPC.Message outgoing=message(106,2,true);outgoing.reply_to=new TLRPC.MessageReplyHeader();outgoing.reply_to.reply_to_top_id=91;
  observe(outgoing,true,false);
  check(ConnectionsManager.requests.size()==2&&ConnectionsManager.requests.get(1).reply_to.top_msg_id==91,"ACK round retains topic");
  observe(outgoing,false,false);observe(outgoing,true,false);
  check(ConnectionsManager.requests.size()==2,"outgoing ACK and update share dedup");
  TLRPC.Message pending=message(107,1,false);PurpleMediaReply.observe(0,pending,false,false);PurpleMediaReply.invalidate();AndroidUtilities.drain();
  check(ConnectionsManager.requests.size()==2,"settings revoke queued callback");
  ConnectionsManager.now=1002;PurpleMediaReply.invalidate();
  TLRPC.Message late=message(108,1,false);late.date=1003;PurpleMediaReply.observe(0,late,false,false);
  PurpleAccountFiles.identity=new PurpleAccountFiles.Identity("0-43",2);AndroidUtilities.drain();
  check(ConnectionsManager.requests.size()==2,"account replacement revokes queued callback");
  ConnectionsManager.callbacks.get(0).run(new TLRPC.Updates(),null);AndroidUtilities.drain();
  check(MessagesController.reconciled==0,"late response does not reconcile another account");
  PurpleAccountFiles.identity=new PurpleAccountFiles.Identity("0-42",3);PurpleAccountSettings.policy("[media_reply.private]\nvoice='both'\n");
  observe(late,false,false);
  check(ConnectionsManager.requests.size()==3,"new account lifetime loads existing journal");
  String persisted=new String(Files.readAllBytes(Paths.get(System.getProperty("scratch"),"media-reply-journal.json")),java.nio.charset.StandardCharsets.UTF_8);
  String duplicate=PurpleMediaReply.planNative(PurpleAccountSettings.effective.handle,"0-42","user:17",0,101,0,1001,1,false,false,1000,1);
  check(duplicate!=null&&PurpleMediaReply.reserveNative(persisted,duplicate)==null,"restart keeps uncertain action reserved");
  ConnectionsManager.callbacks.get(2).run(null,new TLRPC.TL_error());AndroidUtilities.drain();observe(late,false,false);
  check(ConnectionsManager.requests.size()==3,"inflight or uncertain result never becomes newly sendable");
  android.util.AtomicFile.failWrites=true;TLRPC.Message denied=message(109,1,false);denied.date=1003;observe(denied,false,false);
  check(ConnectionsManager.requests.size()==3,"journal write failure prevents transport");
  android.util.AtomicFile.failWrites=false;denied.id=110;observe(denied,false,false);
  check(ConnectionsManager.requests.size()==3,"journal failure remains fail closed");
  System.out.println("MEDIA_REPLY_ADAPTER_CHECKS="+checks);
 }
}
