package org.telegram.messenger.purple;

import java.util.Arrays;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatMoveRequests {
    static byte[] snapshot(TLObject value) {
        try {
            if (value instanceof TLRPC.messages_Messages) value=wireMessages((TLRPC.messages_Messages)value);
            return serialized(value);
        } catch (RuntimeException error) { return null; }
    }
    private static byte[] serialized(TLObject value) {
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
    private static final class SnapshotData extends SerializedData {
        private final byte[] bytes;
        SnapshotData(byte[] bytes){super(bytes);this.bytes=bytes;}
        private void require(int count){if(count<0||count>remaining())throw new IllegalArgumentException("TruncatedSnapshot");}
        private void encodedBytes(){
            require(1);int offset=bytes.length-remaining(),first=bytes[offset]&255,length=first,header=1;
            if(first>=254){
                require(4);if(first!=254)throw new IllegalArgumentException("InvalidSnapshotLength");
                header=4;length=(bytes[offset+1]&255)|((bytes[offset+2]&255)<<8)|((bytes[offset+3]&255)<<16);
                if(length<254)throw new IllegalArgumentException("InvalidSnapshotLength");
            }
            int padded=(header+length+3)&~3;require(padded);
            for(int i=header+length;i<padded;++i)if(bytes[offset+i]!=0)throw new IllegalArgumentException("InvalidSnapshotPadding");
        }
        @Override public int getPosition(){return bytes.length-remaining();}
        @Override public int readInt32(boolean exception){require(4);return super.readInt32(true);}
        @Override public long readInt64(boolean exception){require(8);return super.readInt64(true);}
        @Override public byte readByte(boolean exception){require(1);return super.readByte(true);}
        @Override public void readBytes(byte[] value,boolean exception){require(value.length);super.readBytes(value,true);}
        @Override public byte[] readData(int count,boolean exception){require(count);return super.readData(count,true);}
        @Override public void skip(int count){require(count);super.skip(count);}
        @Override public String readString(boolean exception){encodedBytes();return super.readString(true);}
        @Override public byte[] readByteArray(boolean exception){encodedBytes();return super.readByteArray(true);}
    }
    private static TLRPC.messages_Messages wireMessages(TLRPC.messages_Messages source) {
        TLRPC.messages_Messages result;
        if(source.getClass()==TLRPC.TL_messages_messages.class)result=new TLRPC.TL_messages_messages();
        else if(source.getClass()==TLRPC.TL_messages_channelMessages.class)result=new TLRPC.TL_messages_channelMessages();
        else if(source.getClass()==TLRPC.TL_messages_messagesSlice.class)result=new TLRPC.TL_messages_messagesSlice();
        else return null;
        result.flags=source.flags;result.inexact=source.inexact;result.pts=source.pts;result.count=source.count;
        result.next_rate=source.next_rate;result.offset_id_offset=source.offset_id_offset;result.search_flood=source.search_flood;
        result.topics.addAll(source.topics);result.chats.addAll(source.chats);result.users.addAll(source.users);
        for(TLRPC.Message message:source.messages){
            byte[] bytes=wireMessage(message);if(bytes==null)return null;
            result.messages.add(new TLRPC.Message(){
                @Override public void serializeToStream(OutputSerializedData stream){stream.writeBytes(bytes);}
            });
        }
        return result;
    }
    /**
     * Android appends local attachment metadata after a message's wire fields.
     * Parse the wire prefix, then validate the complete canonical local suffix
     * before removing it. The real remaining-byte count defines the boundary;
     * upstream SerializedData.getPosition undercounts string payloads.
     */
    private static byte[] wireMessage(TLRPC.Message message) {
        if(message==null||message.getClass()!=TLRPC.TL_message.class&&message.getClass()!=TLRPC.TL_messageService.class
                &&message.getClass()!=TLRPC.TL_messageEmpty.class)return null;
        byte[] bytes=serialized(message);if(bytes==null)return null;
        SerializedData data=new SnapshotData(bytes);
        try {
            TLRPC.Message decoded=TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);
            if(decoded==null||decoded.getClass()!=message.getClass())return null;
            int length=bytes.length-data.remaining();
            if(data.remaining()>0){
                if(message.getClass()==TLRPC.TL_messageEmpty.class)return null;
                SerializedData tail=new SerializedData();
                try {
                    tail.writeByteArray(data.readByteArray(true));
                    if(message.id<0&&(message.flags&4)!=0)tail.writeInt32(data.readInt32(true));
                    if(data.remaining()!=0||!Arrays.equals(tail.toByteArray(),Arrays.copyOfRange(bytes,length,bytes.length)))return null;
                } finally {tail.cleanup();}
            }
            return Arrays.copyOf(bytes,length);
        } catch (RuntimeException error) {return null;}
        finally {data.cleanup();}
    }
    static TLRPC.messages_Messages messages(byte[] bytes) {
        if (bytes == null || bytes.length > 4194304) return null;
        SerializedData data = new SnapshotData(bytes);
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
