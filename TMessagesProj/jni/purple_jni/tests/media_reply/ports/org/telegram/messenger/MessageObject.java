package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessageObject {
 public static long getDialogId(TLRPC.Message m){return m.dialog_id!=0?m.dialog_id:m.peer_id.channel_id!=0?-m.peer_id.channel_id:m.peer_id.chat_id!=0?-m.peer_id.chat_id:m.from_id==null||m.out?m.peer_id.user_id:m.from_id.user_id;}
 public static boolean isVoiceMessage(TLRPC.Message m){return m.mediaKind==1;}
 public static boolean isRoundVideoMessage(TLRPC.Message m){return m.mediaKind==2;}
}
