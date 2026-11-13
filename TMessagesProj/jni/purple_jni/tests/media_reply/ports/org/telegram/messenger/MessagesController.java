package org.telegram.messenger;
import java.util.HashMap;
import org.telegram.tgnet.TLRPC;
public class MessagesController {
 public static int reconciled;
 public static final HashMap<Long, TLRPC.Chat> chats=new HashMap<>();
 public static MessagesController getInstance(int account){return new MessagesController();}
 public TLRPC.Chat getChat(Long id){
  if(chats.containsKey(id))return chats.get(id);
  TLRPC.Chat chat=new TLRPC.Chat();chat.channel=true;chat.megagroup=true;return chat;
 }
 public TLRPC.InputPeer getInputPeer(long id){TLRPC.InputPeer p=new TLRPC.InputPeer();p.id=id;return p;}
 public void processUpdates(TLRPC.Updates updates,boolean queue){++reconciled;}
}
