package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import android.util.Base64;
import java.util.function.BooleanSupplier;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatSetupReceipts {
    static final int LIMIT=2*1024*1024;
    interface IO {
        byte[] read(String effect)throws Exception;
        boolean replace(String effect,byte[] expected,byte[] next,BooleanSupplier current);
    }
    static final class Retained {
        final byte[] request;
        final TLRPC.Updates updates;
        Retained(byte[] request,TLRPC.Updates updates){this.request=request;this.updates=updates;}
    }
    final PurpleSyncChatOrigin origin;
    final String authority;
    final IO io;
    PurpleSyncChatSetupReceipts(PurpleSyncChatOrigin origin,String authority,IO io){this.origin=origin;this.authority=authority;this.io=io;}
    static byte[] bytes(TLObject value)throws Exception {
        if(value==null||value.getObjectSize()<4||value.getObjectSize()>LIMIT/2)throw new IllegalArgumentException("SetupReceiptSize");
        SerializedData data=new SerializedData(value.getObjectSize());
        try{value.serializeToStream(data);return data.toByteArray();}finally{data.cleanup();}
    }
    boolean retain(PurpleSyncChatSetup.Command command,TLObject request,TLRPC.Updates updates,BooleanSupplier current) {
        if(command==null||!origin.equals(command.origin)||command.effect!=PurpleSyncChatSetup.Effect.Create&&command.effect!=PurpleSyncChatSetup.Effect.Mark)return false;
        try {
            String effect=command.effect.name();byte[] expected=io.read(effect);
            byte[] requested=bytes(request),response=bytes(updates);
            if(expected!=null) {
                JSONObject prior=parse(expected,command.operation);
                if(!effect.equals(prior.getString("effect")))return false;
                if(!Arrays.equals(Base64.decode(prior.getString("request"),Base64.NO_WRAP),requested))return false;
                return Arrays.equals(Base64.decode(prior.getString("response"),Base64.NO_WRAP),response)&&current.getAsBoolean();
            }
            byte[] next=json("version",1,"account",origin.accountKey(),"authority",authority,"operation",command.operation,
                    "effect",effect,"request",Base64.encodeToString(requested,Base64.NO_WRAP),"response",Base64.encodeToString(response,Base64.NO_WRAP)).toString().getBytes(StandardCharsets.UTF_8);
            return next.length<=LIMIT&&current.getAsBoolean()&&io.replace(effect,null,next,current)&&current.getAsBoolean();
        } catch(Exception|LinkageError error){return false;}
    }
    Retained read(String effect,String operation)throws Exception {
        byte[] raw=io.read(effect);if(raw==null)return null;JSONObject value=parse(raw,operation);
        if(!effect.equals(value.getString("effect")))return null;
        byte[] request=Base64.decode(value.getString("request"),Base64.NO_WRAP),response=Base64.decode(value.getString("response"),Base64.NO_WRAP);
        if(request.length<4||request.length>LIMIT/2||response.length<4||response.length>LIMIT/2)return null;
        SerializedData data=new SerializedData(response);
        try {
            int constructor=data.readInt32(true);
            if(constructor!=TLRPC.TL_updates.constructor&&constructor!=TLRPC.TL_updatesCombined.constructor
                    &&(!effect.equals("Mark")||constructor!=TLRPC.TL_updateShort.constructor&&constructor!=TLRPC.TL_updateShortSentMessage.constructor))return null;
            TLRPC.Updates updates=TLRPC.Updates.TLdeserialize(data,constructor,true);
            return updates!=null&&data.remaining()==0?new Retained(request,updates):null;
        } finally {data.cleanup();}
    }
    private JSONObject parse(byte[] raw,String operation)throws Exception {
        if(raw.length>LIMIT)throw new IllegalArgumentException("SetupReceiptSize");
        JSONObject value=new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString());
        if(!Integer.valueOf(1).equals(value.get("version"))||!origin.accountKey().equals(value.getString("account"))
                ||!authority.equals(value.getString("authority"))||!operation.equals(value.getString("operation")))throw new IllegalArgumentException("SetupReceiptAuthority");
        return value;
    }
    PurpleSyncChatSetup.Created created(String operation)throws Exception {
        Retained retained=read("Create",operation);
        if(retained==null||!Arrays.equals(retained.request,bytes(PurpleSyncChatSetupRequests.create())))return null;
        PurpleSyncChatSetup.Created value=PurpleSyncChatSetupRequests.created(retained.updates);
        return value!=null?new PurpleSyncChatSetup.Created(value.chat,value.updates,operation):null;
    }
    PurpleSyncChatSetup.Posted mark(String operation,Peer chat,long random,PurpleSyncChatClient.Info info)throws Exception {
        Retained retained=read("Mark",operation);if(retained==null)return null;
        SerializedData data=new SerializedData(retained.request);
        try {
            if(data.readInt32(true)!=TLRPC.TL_messages_sendMessage.constructor)return null;
            if(data.readInt32(true)!=((1<<1)|(1<<5)))return null;
            int constructor=data.readInt32(true);
            int expected=chat.kind==PeerKind.Self?TLRPC.TL_inputPeerSelf.constructor:chat.kind==PeerKind.User?TLRPC.TL_inputPeerUser.constructor
                    :chat.kind==PeerKind.Chat?TLRPC.TL_inputPeerChat.constructor:TLRPC.TL_inputPeerChannel.constructor;
            if(constructor!=expected)return null;
            TLRPC.InputPeer peer=TLRPC.InputPeer.TLdeserialize(data,constructor,true);
            String message=data.readString(true);long savedRandom=data.readInt64(true);
            if(data.remaining()!=0||savedRandom!=random||!PurpleSyncChatSetupRequests.MARK.equals(message)
                    ||!PurpleSyncChatRequests.matches(chat,peer))return null;
            return PurpleSyncChatSetupRequests.posted(info,retained.updates,random);
        } finally {data.cleanup();}
    }
    static IO file(File directory) {
        return new IO(){
            File path(String effect){if(!effect.equals("Create")&&!effect.equals("Mark"))throw new IllegalArgumentException("SetupReceiptEffect");return new File(directory,"sync-chat-setup-"+effect.toLowerCase(java.util.Locale.ROOT)+".json");}
            public byte[] read(String effect)throws Exception{
                PurpleAccountFileSnapshot snapshot=PurpleAccountFileSnapshot.read(path(effect),LIMIT);
                if(snapshot.status==PurpleAccountFileSnapshot.Status.ReadError)throw new java.io.IOException("SetupReceiptUnavailable");
                return snapshot.bytes;
            }
            public boolean replace(String effect,byte[] expected,byte[] next,BooleanSupplier current){
                File path=path(effect);PurpleAccountFileSnapshot snapshot=PurpleAccountFileSnapshot.read(path,LIMIT);
                return snapshot.status!=PurpleAccountFileSnapshot.Status.ReadError&&next.length<=LIMIT&&Arrays.equals(snapshot.bytes,expected)
                        &&current.getAsBoolean()&&PurpleAccountFileSnapshot.replace(path,snapshot,next)&&current.getAsBoolean();
            }
        };
    }
}
