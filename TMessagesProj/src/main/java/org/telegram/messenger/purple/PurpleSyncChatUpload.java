package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatUpload {
    static TLObject request(Peer chat,TLRPC.InputPeer peer,int id,TLRPC.TL_inputFile file,long random) {
        if(!PurpleSyncChatRequests.matches(chat,peer) || id<0 || id==0 && random==0 || file==null)return null;
        TLRPC.TL_inputMediaUploadedDocument media=new TLRPC.TL_inputMediaUploadedDocument();
        media.file=file;media.mime_type=PurpleSyncPost.MIME;media.force_file=true;
        TLRPC.TL_documentAttributeFilename name=new TLRPC.TL_documentAttributeFilename();
        name.file_name=PurpleSyncPost.RECORD_FILE_NAME;media.attributes.add(name);
        if(id>0) {
            TLRPC.TL_messages_editMessage edit=new TLRPC.TL_messages_editMessage();
            edit.peer=peer;edit.id=id;edit.media=media;edit.message=PurpleSyncPost.CAPTION;
            edit.flags=(1<<14)|(1<<11);
            return edit;
        }
        TLRPC.TL_messages_sendMedia send=new TLRPC.TL_messages_sendMedia();
        send.peer=peer;send.media=media;send.message=PurpleSyncPost.CAPTION;send.random_id=random;send.silent=true;
        return send;
    }

    static int receipt(PurpleSyncChatIO route,TLObject response,long random,int edit) {
        if(route==null || edit<0 || edit==0 && random==0 || !(response instanceof TLRPC.Updates))return 0;
        TLRPC.Updates updates=(TLRPC.Updates)response;
        if(updates.getClass()==TLRPC.TL_updateShortSentMessage.class) {
            return edit==0 && route.chat.kind!=PeerKind.Channel && updates.id>0 && updates.date>0
                    && updates.ttl_period==0 && (updates.flags&(1<<25))==0
                    && (route.chat.equals(Peer.self()) || updates.out) ? updates.id : 0;
        }
        List<TLRPC.Update> rows=new ArrayList<>();
        if(updates.getClass()==TLRPC.TL_updates.class || updates.getClass()==TLRPC.TL_updatesCombined.class)rows.addAll(updates.updates);
        else if(updates.getClass()==TLRPC.TL_updateShort.class && updates.update!=null)rows.add(updates.update);
        else return 0;
        int mapped=0;Set<Integer> candidates=new HashSet<>();
        for(TLRPC.Update update:rows) {
            if(update instanceof TL_update.TL_updateMessageID) {
                TL_update.TL_updateMessageID value=(TL_update.TL_updateMessageID)update;
                if(edit==0 && value.random_id==random) {
                    if(value.id<=0 || mapped!=0 && mapped!=value.id)return 0;
                    mapped=value.id;
                }
                continue;
            }
            TLRPC.Message message=null;boolean channel=false,edited=false;
            if(update instanceof TL_update.TL_updateNewMessage)message=((TL_update.TL_updateNewMessage)update).message;
            else if(update instanceof TL_update.TL_updateNewChannelMessage){channel=true;message=((TL_update.TL_updateNewChannelMessage)update).message;}
            else if(update instanceof TL_update.TL_updateEditMessage){edited=true;message=((TL_update.TL_updateEditMessage)update).message;}
            else if(update instanceof TL_update.TL_updateEditChannelMessage){edited=true;channel=true;message=((TL_update.TL_updateEditChannelMessage)update).message;}
            if(channel!=(route.chat.kind==PeerKind.Channel) || edited!=(edit>0) || message==null
                    || message.getClass()!=TLRPC.TL_message.class || message.id<=0 || message.date<=0 || !route.matches(message))continue;
            if(route.ownership(message)!=Ownership.Own)return 0;
            candidates.add(message.id);
        }
        int expected=edit>0?edit:mapped;
        return expected>0 && candidates.contains(expected)?expected:0;
    }

    private PurpleSyncChatUpload() { }
}
