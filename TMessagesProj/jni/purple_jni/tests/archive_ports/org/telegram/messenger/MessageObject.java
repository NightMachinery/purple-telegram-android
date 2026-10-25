package org.telegram.messenger;
public final class MessageObject {
    public int currentAccount;
    public org.telegram.tgnet.TLRPC.Message messageOwner;
    public MessageObject(int account, org.telegram.tgnet.TLRPC.Message message) { currentAccount = account; messageOwner = message; }
    public long getDialogId() { return messageOwner.dialog_id; }
    public int getId() { return messageOwner.id; }
}
