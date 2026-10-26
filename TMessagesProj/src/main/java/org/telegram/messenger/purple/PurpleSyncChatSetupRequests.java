package org.telegram.messenger.purple;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;
import java.util.ArrayList;
import java.util.List;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatSetupRequests {
    static final String TITLE="Purple sync";
    static final String ABOUT="Purple Telegram keeps sync data for your devices here.";
    static final String MARK="This account's Purple Telegram apps keep their sync data in this chat: settings and playlists. Deleting this message makes them stop using it.\n#purplechatsync v1";
    static TLRPC.TL_channels_createChannel create() {
        TLRPC.TL_channels_createChannel request=new TLRPC.TL_channels_createChannel();
        request.broadcast=true;request.title=TITLE;request.about=ABOUT;return request;
    }
    static TLRPC.TL_messages_sendMessage mark(PurpleSyncChatClient.Info info,TLRPC.InputPeer peer,long random) {
        if(info==null||!new PurpleSyncChatScreen.Candidate(info.origin,info.chat,info).allowed
                ||!PurpleSyncChatRequests.matches(info.chat,peer)||random==0)return null;
        TLRPC.TL_messages_sendMessage request=new TLRPC.TL_messages_sendMessage();
        request.peer=peer;request.message=MARK;request.random_id=random;request.silent=true;request.no_webpage=true;return request;
    }
    static TLRPC.TL_messages_updatePinnedMessage pin(Cache mark,TLRPC.InputPeer peer) {
        if(mark==null||mark.markId<=0||mark.markDate<=0||mark.chat.kind!=PeerKind.Channel||!PurpleSyncChatRequests.matches(mark.chat,peer))return null;
        TLRPC.TL_messages_updatePinnedMessage request=new TLRPC.TL_messages_updatePinnedMessage();
        request.peer=peer;request.id=mark.markId;request.silent=true;return request;
    }
    static TL_account.updateNotifySettings mute(Peer chat,TLRPC.InputPeer peer) {
        if(chat==null||chat.kind!=PeerKind.Channel||!PurpleSyncChatRequests.matches(chat,peer))return null;
        TL_account.updateNotifySettings request=new TL_account.updateNotifySettings();
        TLRPC.TL_inputNotifyPeer notify=new TLRPC.TL_inputNotifyPeer();notify.peer=peer;request.peer=notify;
        request.settings=new TLRPC.TL_inputPeerNotifySettings();request.settings.flags=4;request.settings.mute_until=Integer.MAX_VALUE;return request;
    }
    static TLRPC.TL_folders_editPeerFolders archive(Peer chat,TLRPC.InputPeer peer) {
        if(chat==null||chat.kind!=PeerKind.Channel||!PurpleSyncChatRequests.matches(chat,peer))return null;
        TLRPC.TL_folders_editPeerFolders request=new TLRPC.TL_folders_editPeerFolders();
        TLRPC.TL_inputFolderPeer folder=new TLRPC.TL_inputFolderPeer();folder.peer=peer;folder.folder_id=1;request.folder_peers.add(folder);return request;
    }
    static PurpleSyncChatSetup.Error error(TLRPC.TL_error error) {
        if(error==null)return PurpleSyncChatSetup.Error.None;
        if("CHANNELS_TOO_MUCH".equals(error.text))return PurpleSyncChatSetup.Error.Limit;
        if(floodSeconds(error)>0)return PurpleSyncChatSetup.Error.Flood;
        if("USER_RESTRICTED".equals(error.text))return PurpleSyncChatSetup.Error.Restricted;
        return PurpleSyncChatSetup.Error.Unknown;
    }
    static int floodSeconds(TLRPC.TL_error error) {
        if(error==null||error.text==null||!error.text.matches("FLOOD_(?:PREMIUM_)?WAIT_[0-9]+"))return 0;
        try { return Math.max(1,Integer.parseInt(error.text.substring(error.text.lastIndexOf('_')+1))); }
        catch(NumberFormatException failure) { return 0; }
    }
    static PurpleSyncChatSetup.Created created(TLObject response) {
        if(!(response instanceof TLRPC.Updates)||response.getClass()!=TLRPC.TL_updates.class&&response.getClass()!=TLRPC.TL_updatesCombined.class)return null;
        TLRPC.Updates updates=(TLRPC.Updates)response;Peer created=null;
        for(TLRPC.Chat chat:updates.chats) {
            if(chat==null||chat.getClass()!=TLRPC.TL_channel.class||chat.id<=0||!chat.creator||!chat.broadcast||chat.megagroup||chat.min||chat.left||chat.kicked
                    ||chat.username!=null&&!chat.username.isEmpty())return null;
            for(TLRPC.TL_username name:chat.usernames)if(name!=null&&name.active)return null;
            if(created!=null)return null;created=Peer.of(PeerKind.Channel,chat.id);
        }
        return created!=null?new PurpleSyncChatSetup.Created(created,updates,null):null;
    }
    static PurpleSyncChatSetup.Posted posted(PurpleSyncChatClient.Info info,TLObject response,long random) {
        if(info==null||!info.readable()||!(response instanceof TLRPC.Updates))return null;
        PurpleSyncChatIO route=new PurpleSyncChatIO(info.origin,info.chat,info.peer);
        int id=PurpleSyncChatUpload.receipt(route,response,random,0);if(id<=0)return null;
        TLRPC.Updates updates=(TLRPC.Updates)response;
        if(updates.getClass()==TLRPC.TL_updateShortSentMessage.class)return new PurpleSyncChatSetup.Posted(new Cache(info.chat,id,updates.date),updates,random);
        List<TLRPC.Update> rows=new ArrayList<>();
        if(updates.getClass()==TLRPC.TL_updateShort.class)rows.add(updates.update);else rows.addAll(updates.updates);
        for(TLRPC.Update update:rows) {
            TLRPC.Message message=update instanceof TL_update.TL_updateNewMessage?((TL_update.TL_updateNewMessage)update).message
                    :update instanceof TL_update.TL_updateNewChannelMessage?((TL_update.TL_updateNewChannelMessage)update).message:null;
            if(message!=null&&message.id==id&&MARK.equals(message.message)&&route.matches(message)
                    &&(message.media==null||message.media.getClass()==TLRPC.TL_messageMediaEmpty.class)&&route.ownership(message)==Ownership.Own)
                return new PurpleSyncChatSetup.Posted(new Cache(info.chat,id,message.date),updates,random);
        }
        return null;
    }
    private PurpleSyncChatSetupRequests() { }
}
