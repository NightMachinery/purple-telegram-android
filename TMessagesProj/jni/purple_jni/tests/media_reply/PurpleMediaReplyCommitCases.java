package org.telegram.messenger.purple;
import java.nio.file.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
public final class PurpleMediaReplyCommitCases {
 static int checks;
 static void check(boolean value,String why){++checks;if(!value)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  System.load(args[0]);Files.createDirectories(Paths.get(System.getProperty("scratch")));
  boolean reviewed=args[2].equals("reviewed");String fault=args[3];
  check(new java.io.File(PurpleMediaReply.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalPath().equals(new java.io.File(args[1]).getCanonicalPath()),"exact production bytecode origin");
  android.system.Os.fault=fault;android.util.AtomicFile.silentFault=fault;
  PurpleAccountSettings.policy("[media_reply.private]\nvoice='both'\n");PurpleMediaReply.invalidate();AndroidUtilities.drain();ConnectionsManager.now=1001;
  TLRPC.Message message=new TLRPC.Message();message.id=101;message.date=1001;message.mediaKind=1;message.peer_id=new TLRPC.Peer();message.peer_id.user_id=17;
  PurpleMediaReply.observe(0,message,false,false);AndroidUtilities.drain();
  java.lang.reflect.Field field=PurpleMediaReply.class.getDeclaredField("states");field.setAccessible(true);Object state=((java.util.Map<?,?>)field.get(null)).values().iterator().next();
  java.lang.reflect.Field journal=state.getClass().getDeclaredField("journal");journal.setAccessible(true);
  if(reviewed){
   check(ConnectionsManager.requests.size()==1,"reviewed dispatch admits after native-style log-only failure");
   check(android.util.AtomicFile.loggedFailures==1,"platform-style failure returned normally");
   check(journal.get(state)!=null,"reviewed false memory commit ACK");
   System.out.println("REVIEWED_COMMIT_ADMISSION_VIOLATION="+checks);return;
  }
  PurpleMediaReply.Status status=PurpleMediaReply.status(0);
  if(fault.isEmpty()){
   check(ConnectionsManager.requests.size()==1,"checked successful write admits transport");
   check(android.system.Os.syncs==2&&android.system.Os.renames==1&&android.system.Os.closes==2,"file and directory sync plus checked close/replacement");
   check(status.reserved==1&&journal.get(state)!=null,"memory ACK follows checked commit");
  }else{
   check(ConnectionsManager.requests.isEmpty(),"commit failure stops native request");
   check(status.failed&&status.reason==PurpleMediaReply.Reason.StorageRefused,"bounded visible storage refusal");
   check(status.reserved==0&&journal.get(state)==null,"failed commit has no false memory ACK");
   PurpleMediaReply.observe(0,message,false,false);AndroidUtilities.drain();check(ConnectionsManager.requests.isEmpty(),"commit failure remains terminal in lifetime");
  }
  System.out.println("MEDIA_REPLY_COMMIT_CHECKS="+checks);
 }
}
