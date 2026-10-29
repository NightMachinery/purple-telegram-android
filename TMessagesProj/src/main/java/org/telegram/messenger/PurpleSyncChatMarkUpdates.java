package org.telegram.messenger;

import org.telegram.messenger.purple.PurpleSyncChatCore;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public final class PurpleSyncChatMarkUpdates {
    public static void apply(int slot,PurpleSyncChatCore.Peer chat,TLObject response,java.util.function.BooleanSupplier current){
        if(!current.getAsBoolean())return;
        MessagesController controller=MessagesController.getInstance(slot);
        if(response instanceof TLRPC.Updates){controller.processUpdates((TLRPC.Updates)response,false);return;}
        if(!(response instanceof TLRPC.TL_messages_affectedMessages))throw new IllegalArgumentException("DeleteUncertain");
        TLRPC.TL_messages_affectedMessages affected=(TLRPC.TL_messages_affectedMessages)response;
        int pts=affected.pts,count=affected.pts_count;
        MessagesStorage.getInstance(slot).getStorageQueue().postRunnable(()->{
            if(!current.getAsBoolean())return;
            if(chat.kind==PurpleSyncChatCore.PeerKind.Channel)controller.processNewChannelDifferenceParams(pts,count,chat.id);
            else controller.processNewDifferenceParams(-1,pts,-1,count);
        });
    }
    private PurpleSyncChatMarkUpdates(){}
}
