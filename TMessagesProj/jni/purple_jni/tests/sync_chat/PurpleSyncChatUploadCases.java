package org.telegram.messenger.purple;

import java.util.*;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatUploadCases {
    static TL_update.TL_updateMessageID mapping(long random,int id) {
        TL_update.TL_updateMessageID value=new TL_update.TL_updateMessageID();value.random_id=random;value.id=id;return value;
    }
    static TLRPC.Update update(Peer chat,int id,boolean edit) {
        TLRPC.Message message=mark(chat,id,50);
        if(chat.kind==PeerKind.Channel) {
            if(edit){TL_update.TL_updateEditChannelMessage row=new TL_update.TL_updateEditChannelMessage();row.message=message;return row;}
            TL_update.TL_updateNewChannelMessage row=new TL_update.TL_updateNewChannelMessage();row.message=message;return row;
        }
        if(edit){TL_update.TL_updateEditMessage row=new TL_update.TL_updateEditMessage();row.message=message;return row;}
        TL_update.TL_updateNewMessage row=new TL_update.TL_updateNewMessage();row.message=message;return row;
    }
    static TLRPC.Message message(TLRPC.Update row) {
        if(row instanceof TL_update.TL_updateNewChannelMessage)return ((TL_update.TL_updateNewChannelMessage)row).message;
        if(row instanceof TL_update.TL_updateEditChannelMessage)return ((TL_update.TL_updateEditChannelMessage)row).message;
        if(row instanceof TL_update.TL_updateNewMessage)return ((TL_update.TL_updateNewMessage)row).message;
        return ((TL_update.TL_updateEditMessage)row).message;
    }
    static TLRPC.Updates updates(boolean combined,TLRPC.Update... rows) {
        TLRPC.Updates value=combined?new TLRPC.TL_updatesCombined():new TLRPC.TL_updates();
        value.updates.addAll(Arrays.asList(rows));return value;
    }
    static TLRPC.Updates serialized(TLRPC.Updates value) {
        SerializedData bytes=wire(value);return TLRPC.Updates.TLdeserialize(bytes,bytes.readInt32(true),true);
    }
    static void run() {
        int before=checks;long random=991;
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}) {
            PurpleSyncChatIO io=PurpleSyncChatIOCases.io(chat);
            TLRPC.TL_inputFile file=new TLRPC.TL_inputFile();file.id=71;file.parts=1;file.name=PurpleSyncPost.RECORD_FILE_NAME;file.md5_checksum="00112233445566778899aabbccddeeff";
            for(int edit:new int[]{0,7}) {
                TLObject request=PurpleSyncChatUpload.request(chat,inputPeer(chat),edit,file,random);SerializedData bytes=wire(request);
                check(bytes.readInt32(true)==(edit>0?TLRPC.TL_messages_editMessage.constructor:TLRPC.TL_messages_sendMedia.constructor),"genuine typed modern request constructor");
                check(bytes.readInt32(true)==(edit>0?(1<<14)|(1<<11):1<<5),"silent send flag / unchanged edit schema");
                TLRPC.InputPeer destination=TLRPC.InputPeer.TLdeserialize(bytes,bytes.readInt32(true),true);
                check(PurpleSyncChatRequests.matches(chat,destination),"typed final wire destination and hash "+chat.token());
                if(edit>0){check(bytes.readInt32(true)==7,"exact edit id");check(bytes.readString(true).equals(PurpleSyncPost.CAPTION),"fixed edit caption");}
                TLRPC.InputMedia media=TLRPC.InputMedia.TLdeserialize(bytes,bytes.readInt32(true),true);
                check(media.getClass()==TLRPC.TL_inputMediaUploadedDocument.class && media.force_file && media.mime_type.equals(PurpleSyncPost.MIME),"uploaded document MIME/force_file");
                check(media.attributes.size()==1 && media.attributes.get(0).file_name.equals(PurpleSyncPost.RECORD_FILE_NAME),"canonical modern filename");
                if(edit==0)check(bytes.readString(true).equals(PurpleSyncPost.CAPTION)&&bytes.readInt64(true)==random,"fixed send caption and random id");
                for(boolean combined:new boolean[]{false,true}) {
                    TLRPC.Update sent=update(chat,7,edit>0);
                    TLRPC.Updates response=updates(combined,mapping(random,7),sent);
                    check(PurpleSyncChatUpload.receipt(io,serialized(response),random,edit)==7,"serialized native receipt control "+chat.token()+" edit="+edit);
                    check(PurpleSyncChatUpload.receipt(io,updates(combined,sent),random,edit)==(edit>0?7:0),"new send requires random mapping, edit requires exact id");
                    check(PurpleSyncChatUpload.receipt(io,updates(combined,mapping(random+1,7),sent),random,edit)==(edit>0?7:0),"other random mapping never adopted");
                    check(PurpleSyncChatUpload.receipt(io,updates(combined,mapping(random,8),sent),random,edit)==(edit>0?7:0),"mapping/message id disagreement held");
                    if(edit==0)check(PurpleSyncChatUpload.receipt(io,updates(combined,mapping(random,7),mapping(random,8),sent),random,edit)==0,"contradictory mapping held");
                    check(PurpleSyncChatUpload.receipt(io,updates(combined,mapping(random,7),update(Peer.of(PeerKind.User,99),7,edit>0)),random,edit)==0,"same id other peer cannot be receipt");
                    check(PurpleSyncChatUpload.receipt(io,updates(combined,mapping(random,7),update(chat,7,edit==0)),random,edit)==0,"new/edit form mismatch held");
                    for(String unknown:new String[]{"forward","via","scheduled","ttl","ownership-unknown"}) {
                        sent=update(chat,7,edit>0);TLRPC.Message row=message(sent);
                        if(unknown.equals("forward"))row.fwd_from=new TLRPC.TL_messageFwdHeader();
                        if(unknown.equals("via"))row.via_bot_id=1;
                        if(unknown.equals("scheduled"))row.from_scheduled=true;
                        if(unknown.equals("ttl"))row.ttl_period=5;
                        if(unknown.equals("ownership-unknown"))row.media=new TLRPC.TL_messageMediaGeo();
                        check(PurpleSyncChatUpload.receipt(io,updates(combined,mapping(random,7),sent),random,edit)==0,"C0 receipt refuses "+unknown);
                    }
                }
            }
            check(PurpleSyncChatUpload.request(chat,inputPeer(chat),0,file,0)==null,"zero send random id held");
            check(PurpleSyncChatUpload.request(chat,inputPeer(Peer.of(PeerKind.User,99)),0,file,random)==null,"wrong final input peer no Self fallback");
            check(PurpleSyncChatUpload.request(chat,inputPeer(chat),-1,file,random)==null,"negative edit id held");
            TLRPC.TL_updateShortSentMessage shortSent=new TLRPC.TL_updateShortSentMessage();shortSent.id=7;shortSent.date=50;shortSent.out=true;
            check(PurpleSyncChatUpload.receipt(io,serialized(shortSent),random,0)==(chat.equals(SELF)?7:0),"wire out flag must be affirmative for nonself shortSent");
            shortSent.flags=1<<1;
            check(PurpleSyncChatUpload.receipt(io,serialized(shortSent),random,0)==(chat.kind==PeerKind.Channel?0:7),"captured nonchannel shortSent direct control");
            shortSent.flags|=1<<25;shortSent.ttl_period=0;
            check(PurpleSyncChatUpload.receipt(io,serialized(shortSent),random,0)==0,"TTL flag even zero refuses shortSent");
            check(PurpleSyncChatUpload.receipt(io,new TLRPC.TL_updatesTooLong(),random,0)==0,"unrecognized receipt unknown");
        }
        System.out.println("TYPED_UPLOAD_WIRE_CHECKS="+(checks-before));
    }
}
