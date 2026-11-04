package org.telegram.messenger.purple;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.util.*;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;

final class PurpleSyncChatWireFixtures {
    static final Peer SELF=Peer.self(), CHANNEL=Peer.of(PeerKind.Channel,44), GROUP=Peer.of(PeerKind.Chat,33), USER=Peer.of(PeerKind.User,22);
    static TLRPC.InputPeer inputPeer(Peer target) {
        if(target==null)return null;
        TLRPC.InputPeer input;
        switch(target.kind){
            case Self: return new TLRPC.TL_inputPeerSelf();
            case User: input=new TLRPC.TL_inputPeerUser();input.user_id=target.id;break;
            case Chat: input=new TLRPC.TL_inputPeerChat();input.chat_id=target.id;break;
            default:input=new TLRPC.TL_inputPeerChannel();input.channel_id=target.id;
        }
        input.access_hash=77;return input;
    }
    static SerializedData wire(TLObject value){SerializedData output=new SerializedData();value.serializeToStream(output);return new SerializedData(output.toByteArray());}
    static TLRPC.TL_message row(Peer target,int id,int date){TLRPC.TL_message value=message(target);value.id=id;value.date=date;value.out=target.kind!=PeerKind.Self;if(target.kind==PeerKind.Chat){value.from_id=peer(SELF);value.flags|=1<<8;}return value;}
    static TLRPC.messages_Messages messages(TLRPC.Message... rows){TLRPC.TL_messages_messages value=new TLRPC.TL_messages_messages();value.messages.addAll(Arrays.asList(rows));return value;}
    static PurpleSyncChatClient.Info info(Peer chat){return info(chat,input(chat));}
    static PurpleSyncChatClient.Info info(Peer chat,PurpleSyncChatFacts.Input raw){PurpleSyncChatFacts.MappedPeer mapped=PurpleSyncChatFacts.peer(ORIGIN,raw);return new PurpleSyncChatClient.Info(ORIGIN,chat,mapped,PurpleSyncChatClient.Error.None,"synthetic",mapped.memberIds,true);}
}
