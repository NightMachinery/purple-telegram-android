package org.telegram.messenger.purple;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatRecordClient implements PurpleSyncClient {
    static final int MAX_HISTORY_PAGES=1000;
    private int historyPages;
    private long historyCount,historyTicket;
    private Integer historyTotal;
    private final PurpleSyncClient client;
    private final Function<Message,Ownership> ownership;
    PurpleSyncChatRecordClient(PurpleSyncClient client, Function<Message,Ownership> ownership) { this.client = client; this.ownership = ownership; }
    static Ownership selfOwnership(PurpleSyncChatOrigin origin, TLRPC.Message message) {
        if (origin == null) return Ownership.Unknown;
        PurpleSyncChatFacts.MappedPeer self = new PurpleSyncChatFacts.MappedPeer(origin,Peer.self(),Kind.Self,Truth.Unknown,Truth.Unknown,null,null);
        return PurpleSyncChatCore.ownership(PurpleSyncChatFacts.message(origin,self,message),Options.restricted());
    }
    private boolean candidate(Message message) {
        if (message == null || !message.isDocument) return false;
        PurpleSyncCore.Page page = PurpleSyncCore.classifyHistoryPage(0,new int[]{message.id},new boolean[]{message.isMessage},new boolean[]{message.forwarded},
            new boolean[]{message.isDocument},new String[]{message.caption},new String[]{message.fileName});
        return !page.isValid() || page.candidates.length != 0;
    }
    private Message ignored(Message m) { return new Message(m.id,false,m.forwarded,false,"",null,m.selfDialog,0,0,m.editDate,null); }
    @Override public int requestHistory(int offset, int limit, Reply<History> done) {
        if(offset==0){historyPages=0;historyCount=0;historyTotal=null;}
        final long ticket=++historyTicket;
        if(++historyPages>MAX_HISTORY_PAGES){done.onReply(History.failed("HistoryLimit"));return 0;}
        return client.requestHistory(offset,limit,history -> {
            if(ticket!=historyTicket)return;
            if (history.messages == null) { done.onReply(history); return; }
            if(history.total<0||history.messages.size()>limit
                ||history.declaredTotal!=null&&(history.declaredTotal<0||historyTotal!=null&&!historyTotal.equals(history.declaredTotal))){done.onReply(History.failed("IncompleteHistory"));return;}
            if(history.declaredTotal!=null)historyTotal=history.declaredTotal;
            long after=historyCount+history.messages.size();
            if(historyTotal!=null&&(after>historyTotal||history.messages.isEmpty()&&after!=historyTotal)){done.onReply(History.failed("IncompleteHistory"));return;}
            historyCount=after;
            List<Message> rows = new ArrayList<>();
            for (Message message : history.messages) {
                if (!candidate(message)) { rows.add(message); continue; }
                Ownership own = ownership.apply(message);
                if (own == null || own == Ownership.Unknown) { done.onReply(History.failed("UnknownOwnership")); return; }
                rows.add(own == Ownership.Own ? message : ignored(message));
            }
            done.onReply(History.page(rows,history.declaredTotal));
        });
    }
    @Override public int requestMessage(int id, Reply<Lookup> done) {
        return client.requestMessage(id,lookup -> {
            if (lookup.status != LookupStatus.Found || !candidate(lookup.message)) { done.onReply(lookup); return; }
            Ownership own = ownership.apply(lookup.message);
            done.onReply(own == Ownership.Own ? lookup : Lookup.failed(own == Ownership.Other ? "OwnershipChanged" : "UnknownOwnership"));
        });
    }
    static History history(TLObject response,TLRPC.TL_error error,long self,Function<TLRPC.Message,Message> meta) {
        if(error!=null)return History.failed("HistoryRequestFailed");
        if(response==null||response.getClass()!=TLRPC.TL_messages_messages.class&&response.getClass()!=TLRPC.TL_messages_messagesSlice.class)return History.failed("UnknownHistory");
        TLRPC.messages_Messages page=(TLRPC.messages_Messages)response;
        if(page.inexact||page.search_flood!=null||(page.flags&((1<<1)|(1<<3)))!=0||page.messages.size()>100
            ||response.getClass()==TLRPC.TL_messages_messagesSlice.class&&page.count<page.messages.size())return History.failed("IncompleteHistory");
        List<Message> rows=new ArrayList<>();
        for(TLRPC.Message message:page.messages){
            if(message==null||message.id<=0||message.getClass()!=TLRPC.TL_message.class&&message.getClass()!=TLRPC.TL_messageService.class&&message.getClass()!=TLRPC.TL_messageEmpty.class
                ||message.getClass()!=TLRPC.TL_messageEmpty.class&&!Peer.self().equals(PurpleSyncChatFacts.token(message.peer_id,self)))return History.failed("UnknownHistoryMessage");
            rows.add(meta.apply(message));
        }
        return History.page(rows,response.getClass()==TLRPC.TL_messages_messagesSlice.class?Integer.valueOf(page.count):null);
    }
    @Override public boolean hasQueuedSync() { return client.hasQueuedSync(); }
    @Override public long userId() { return client.userId(); }
    @Override public boolean isActive() { return client.isActive(); }
    @Override public void runOnMain(Runnable task) { client.runOnMain(task); }
    @Override public void runOnWorker(Runnable task) { client.runOnWorker(task); }
    @Override public void log(String line) { client.log(line); }
    @Override public void cancelRequest(int token) { client.cancelRequest(token); }
    @Override public Object download(Message message, Reply<File> done) { return client.download(message,done); }
    @Override public void cancelDownload(Object download) { client.cancelDownload(download); }
    @Override public void localCopies(Reply<List<LocalCopy>> done) { client.localCopies(done); }
    @Override public File filesDirectory() { return client.filesDirectory(); }
    @Override public File cacheDirectory() { return client.cacheDirectory(); }
    @Override public long nowMillis() { return client.nowMillis(); }
    @Override public void send(File file,String caption,String mime,Receipt receipt) { client.send(file,caption,mime,receipt); }
    @Override public void edit(int id,File file,byte[] expected,BooleanSupplier current,Reply<PurpleSyncAutoCore.Outcome> done) { client.edit(id,file,expected,current,done); }
    @Override public void deleteOwn(int id,BooleanSupplier current,Reply<PurpleSyncAutoCore.Outcome> done) { client.deleteOwn(id,current,done); }
}
