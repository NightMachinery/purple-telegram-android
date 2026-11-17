package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;import java.util.*;
public final class MessagesController {
 public static final MessagesController[] slots={new MessagesController(),new MessagesController()};
 public final Map<Long,TLRPC.Chat> chats=new HashMap<>(); public int writes;
 public static MessagesController getInstance(int slot){return slots[slot];}
 public TLRPC.Chat getChat(long id){return chats.get(id);}
 public void putUsers(ArrayList<TLRPC.User> users,boolean cache){writes++;}
 public void putChats(ArrayList<TLRPC.Chat> values,boolean cache){writes++;for(TLRPC.Chat c:values)chats.put(c.id,c);}
 public String getRestrictionReason(ArrayList<TLRPC.RestrictionReason> reasons){return reasons.isEmpty()?null:"restricted";}
}