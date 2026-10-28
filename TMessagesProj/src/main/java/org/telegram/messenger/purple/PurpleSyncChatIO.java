package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatIO {
    final PurpleSyncChatOrigin origin;
    final Peer chat;
    final PurpleSyncChatFacts.MappedPeer facts;

    PurpleSyncChatIO(PurpleSyncChatOrigin origin, Peer chat, PurpleSyncChatFacts.MappedPeer facts) {
        if (origin == null || chat == null || facts == null || !origin.equals(facts.origin)
                || !chat.equals(facts.chat)) throw new IllegalArgumentException("ChatOriginMismatch");
        this.origin=origin;this.chat=chat;this.facts=facts;
    }

    static long dialogId(Peer chat,long self) {
        return chat.kind==PeerKind.Self ? self : chat.kind==PeerKind.User ? chat.id : -chat.id;
    }

    static TLRPC.TL_messages_getHistory history(Peer chat,TLRPC.InputPeer peer,int offset,int limit) {
        if (!PurpleSyncChatRequests.matches(chat,peer) || offset<0 || limit<=0 || limit>100) return null;
        TLRPC.TL_messages_getHistory request=new TLRPC.TL_messages_getHistory();
        request.peer=peer;request.offset_id=offset;request.limit=limit;
        return request;
    }

    Ownership ownership(TLRPC.Message message) {
        return PurpleSyncChatCore.ownership(PurpleSyncChatFacts.message(origin,facts,message),Options.restricted());
    }

    boolean matches(TLRPC.Message message) {
        return message!=null && chat.equals(PurpleSyncChatFacts.token(message.peer_id,origin.userId));
    }

    boolean empty(TLRPC.Message message,int id) {
        return message!=null && message.getClass()==TLRPC.TL_messageEmpty.class && message.id==id
                && message.peer_id!=null && matches(message);
    }

    private boolean exactPage(TLRPC.messages_Messages page) {
        return page!=null && !page.inexact && page.search_flood==null
                && (page.flags&((1<<1)|(1<<3)))==0;
    }

    private boolean channelPage(TLObject response) {
        return chat.kind==PeerKind.Channel && response.getClass()==TLRPC.TL_messages_channelMessages.class;
    }

    PurpleSyncClient.History history(TLObject response,TLRPC.TL_error error,Function<TLRPC.Message,PurpleSyncClient.Message> meta) {
        if(error!=null)return PurpleSyncClient.History.failed("HistoryRequestFailed");
        if(!(response instanceof TLRPC.messages_Messages))return PurpleSyncClient.History.failed("UnknownHistory");
        TLRPC.messages_Messages page=(TLRPC.messages_Messages)response;
        boolean counted=response.getClass()==TLRPC.TL_messages_messagesSlice.class || channelPage(response);
        if(response.getClass()!=TLRPC.TL_messages_messages.class && !counted || !exactPage(page)
                || page.messages.size()>100 || counted && page.count<page.messages.size())
            return PurpleSyncClient.History.failed("IncompleteHistory");
        ArrayList<PurpleSyncClient.Message> rows=new ArrayList<>();
        for(TLRPC.Message message:page.messages) {
            if(message==null || message.id<=0 || message.getClass()!=TLRPC.TL_message.class
                    && message.getClass()!=TLRPC.TL_messageService.class && message.getClass()!=TLRPC.TL_messageEmpty.class
                    || (message.getClass()==TLRPC.TL_messageEmpty.class ? !empty(message,message.id) : !matches(message)))
                return PurpleSyncClient.History.failed("UnknownHistoryMessage");
            rows.add(meta.apply(message));
        }
        return PurpleSyncClient.History.page(rows,counted?Integer.valueOf(page.count):null);
    }

    PurpleSyncClient.Lookup lookup(TLObject response,TLRPC.TL_error error,int id,Function<TLRPC.Message,PurpleSyncClient.Message> meta) {
        if(error!=null)return PurpleSyncClient.Lookup.failed("ExactRequestFailed");
        if(!(response instanceof TLRPC.messages_Messages))return PurpleSyncClient.Lookup.failed("UnknownExact");
        TLRPC.messages_Messages page=(TLRPC.messages_Messages)response;
        if(response.getClass()!=TLRPC.TL_messages_messages.class && !channelPage(response) || !exactPage(page)
                || channelPage(response) && page.count<page.messages.size())return PurpleSyncClient.Lookup.failed("IncompleteExact");
        if(page.messages.isEmpty())return PurpleSyncClient.Lookup.failed("UnscopedAbsence");
        if(page.messages.size()!=1)return PurpleSyncClient.Lookup.failed("AmbiguousExact");
        TLRPC.Message message=page.messages.get(0);
        if(empty(message,id))return PurpleSyncClient.Lookup.missing();
        if(message==null || message.id!=id || !matches(message)
                || message.getClass()!=TLRPC.TL_message.class && message.getClass()!=TLRPC.TL_messageService.class)
            return PurpleSyncClient.Lookup.failed("WrongExactScope");
        return PurpleSyncClient.Lookup.found(meta.apply(message),this,PurpleSyncChatMoveRequests.snapshot(response));
    }

    static boolean ledgerMatches(Peer chat,byte[] state) {
        if(chat==null)return false;
        if(state==null)return Peer.self().equals(chat);
        try {
            org.json.JSONObject value=new org.json.JSONObject(new String(state,java.nio.charset.StandardCharsets.UTF_8));
            Object version=value.opt("version");
            if(!(version instanceof Number))return false;
            double number=((Number)version).doubleValue();
            return number==1 && !value.has("chat") && Peer.self().equals(chat)
                    || number==2 && chat.token().equals(value.opt("chat"));
        } catch(Exception error) { return false; }
    }

    static boolean same(PurpleSyncClient.Message a,PurpleSyncClient.Message b) {
        return a!=null && b!=null && a.id==b.id && Objects.equals(a.chat,b.chat)
                && a.isMessage==b.isMessage && a.forwarded==b.forwarded && a.isDocument==b.isDocument
                && Objects.equals(a.caption,b.caption) && Objects.equals(a.fileName,b.fileName)
                && a.documentId==b.documentId && a.size==b.size && a.editDate==b.editDate;
    }

    private PurpleSyncChatIO() { throw new AssertionError(); }
}
