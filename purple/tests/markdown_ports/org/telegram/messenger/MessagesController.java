package org.telegram.messenger;
public final class MessagesController {
 public org.telegram.tgnet.TLRPC.InputPeer getInputPeer(long dialog){org.telegram.tgnet.TLRPC.TL_inputPeerUser peer=new org.telegram.tgnet.TLRPC.TL_inputPeerUser();peer.user_id=dialog;return peer;}
}
