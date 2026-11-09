package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class ChatObject {
 public static boolean canSendMessages(TLRPC.Chat c){return c.allowed;}
 public static boolean isChannel(TLRPC.Chat c){return c.channel;}
}
