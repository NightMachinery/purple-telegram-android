package org.telegram.messenger.purple;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatMoveRequests {
    static byte[] snapshot(TLObject value) {
        if (value == null) return null;
        SerializedData sizing = new SerializedData(true);
        try { value.serializeToStream(sizing); if (sizing.length() > 4194304) return null; }
        catch (RuntimeException error) { return null; }
        finally { sizing.cleanup(); }
        SerializedData data = new SerializedData();
        try { value.serializeToStream(data); return data.toByteArray(); }
        catch (RuntimeException error) { return null; }
        finally { data.cleanup(); }
    }
    static TLRPC.messages_Messages messages(byte[] bytes) {
        if (bytes == null || bytes.length > 4194304) return null;
        SerializedData data = new SerializedData(bytes);
        try {
            TLRPC.messages_Messages result = TLRPC.messages_Messages.TLdeserialize(data,data.readInt32(true),true);
            return data.remaining() == 0 ? result : null;
        } catch (RuntimeException error) { return null; }
        finally { data.cleanup(); }
    }
    static TLRPC.InputPeer peer(TLRPC.InputPeer input) {
        byte[] bytes = snapshot(input); if (bytes == null) return null;
        SerializedData data = new SerializedData(bytes);
        try { return TLRPC.InputPeer.TLdeserialize(data,data.readInt32(true),true); }
        finally { data.cleanup(); }
    }
    static TLRPC.TL_messages_forwardMessages forward(Peer source,TLRPC.InputPeer from,Peer target,TLRPC.InputPeer to,int id,long random) {
        if (!PurpleSyncChatRequests.matches(source,from) || !PurpleSyncChatRequests.matches(target,to) || id <= 0 || random == 0) return null;
        TLRPC.TL_messages_forwardMessages request = new TLRPC.TL_messages_forwardMessages();
        request.from_peer = peer(from); request.to_peer = peer(to); request.id.add(id); request.random_id.add(random);
        request.silent = true; request.drop_author = true; request.drop_media_captions = false;
        return request;
    }
    static TLRPC.TL_messages_sendMessage mark(Peer chat,TLRPC.InputPeer to,String text,long random) {
        if (!PurpleSyncChatRequests.matches(chat,to) || random == 0
                || !PurpleSyncChatSetupRequests.MARK.equals(text)) return null;
        TLRPC.TL_messages_sendMessage request = new TLRPC.TL_messages_sendMessage();
        request.peer = peer(to); request.message = text; request.random_id = random; request.silent = true; request.no_webpage = true;
        return request;
    }
    static TLObject exact(Peer chat,TLRPC.InputPeer to,int id) { return PurpleSyncChatRequests.exact(chat,id,peer(to)); }
    static TLObject delete(Peer chat,TLRPC.InputPeer to,int id) {
        if (!PurpleSyncChatRequests.matches(chat,to) || id <= 0) return null;
        if (chat.kind == PeerKind.Channel) {
            TLRPC.TL_channels_deleteMessages request = new TLRPC.TL_channels_deleteMessages();
            request.channel = PurpleSyncChatRequests.channel(peer(to)); request.id.add(id); return request;
        }
        TLRPC.TL_messages_deleteMessages request = new TLRPC.TL_messages_deleteMessages();
        request.id.add(id); request.revoke = true; return request;
    }
    static boolean cleanupEnabled() { return PurpleSyncChatMoveCleanup.ACTIVATED; }
    static TLObject effect(PurpleSyncChatMoveIO.Permit permit,TLRPC.TL_inputFile file) {
        if (permit == null || !permit.current()) return null;
        if (permit.kind.equals("forward")) return forward(permit.source,permit.sourcePeer(),permit.target,permit.targetPeer(),permit.sourceId,permit.randomId);
        if (permit.kind.equals("mark")) return mark(permit.target,permit.targetPeer(),permit.markText(),permit.randomId);
        if ((!permit.kind.equals("own")&&!permit.kind.equals("pending")) || file == null || file.id != permit.fileId) return null;
        return PurpleSyncChatUpload.request(permit.target,permit.targetPeer(),0,file,permit.randomId);
    }
    private PurpleSyncChatMoveRequests() { }
}
