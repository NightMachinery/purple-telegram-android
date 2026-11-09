package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessageObject {
 public static boolean isVoiceMessage(TLRPC.Message m){return m.mediaKind==1;}
 public static boolean isRoundVideoMessage(TLRPC.Message m){return m.mediaKind==2;}
}
