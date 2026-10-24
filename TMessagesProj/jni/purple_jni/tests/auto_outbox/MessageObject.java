package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessageObject {
    public static final int MESSAGE_SEND_STATE_SENDING=1, MESSAGE_SEND_STATE_SEND_ERROR=2, MESSAGE_SEND_STATE_EDITING=3;
    public int currentAccount;
    public TLRPC.Message messageOwner;
    public int getId(){return messageOwner.id;}
    public long getDialogId(){return messageOwner.dialog_id;}
    public static long getDialogId(TLRPC.Message message){return message.dialog_id;}
}
