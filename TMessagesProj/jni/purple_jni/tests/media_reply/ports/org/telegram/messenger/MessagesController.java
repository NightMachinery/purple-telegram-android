package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessagesController {
 public static int reconciled;
 public static MessagesController getInstance(int account){return new MessagesController();}
 public TLRPC.Chat getChat(long id){return new TLRPC.Chat();}
 public TLRPC.InputPeer getInputPeer(long id){TLRPC.InputPeer p=new TLRPC.InputPeer();p.id=id;return p;}
 public void processUpdates(TLRPC.Updates updates,boolean queue){++reconciled;}
}
