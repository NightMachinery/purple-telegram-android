package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatRecordCases {
    static TLRPC.TL_message document(int id,boolean oldMedia){
        TLRPC.TL_message result=mark(SELF,id,50);result.message="#purplesync";
        result.media=oldMedia?new TLRPC.TL_messageMediaDocument_layer187():new TLRPC.TL_messageMediaDocument();
        result.media.document=new TLRPC.TL_document();result.media.document.id=id+100;return result;
    }
    static PurpleSyncClient.Message meta(TLRPC.Message value,long size){return new PurpleSyncClient.Message(value.id,value instanceof TLRPC.TL_message,value.fwd_from!=null,value.media instanceof TLRPC.TL_messageMediaDocument,value.message,"Purple settings sync.json",SELF.equals(PurpleSyncChatFacts.token(value.peer_id,11)),value.id+100,size,0,value);}
    static final class Client implements PurpleSyncClient {
        final ArrayDeque<Runnable> work=new ArrayDeque<>();final List<TLObject> pages=new ArrayList<>();final Map<Integer,TLRPC.Message> fresh=new HashMap<>();
        final File payload;int history,sent,downloads;boolean active=true,failed,cap,deferHistory;final List<Runnable> historyReplies=new ArrayList<>();
        Client(File payload){this.payload=payload;}
        public long userId(){return 11;}public boolean isActive(){return active;}public void runOnMain(Runnable task){work.add(task);}public void runOnWorker(Runnable task){work.add(task);}public void log(String text){}
        public int requestHistory(int offset,int limit,Reply<History> reply){
            TLObject response;
            if(cap){TLRPC.TL_messages_messagesSlice page=new TLRPC.TL_messages_messagesSlice();page.count=5000;TLRPC.TL_message row=mark(SELF,10000-history,50);row.message="ordinary";page.messages.add(row);response=page;}
            else response=history<pages.size()?pages.get(history):messages();
            ++history;TLRPC.TL_error error=null;if(failed){error=new TLRPC.TL_error();error.text="FLOOD_WAIT_1";}
            History normalized=PurpleSyncChatRecordClient.history(response,error,11,message->meta(message,payload.length()));Runnable complete=()->reply.onReply(normalized);if(deferHistory)historyReplies.add(complete);else complete.run();return history;
        }
        public int requestMessage(int id,Reply<Lookup> reply){TLRPC.Message message=fresh.get(id);reply.onReply(message!=null?Lookup.found(meta(message,payload.length())):Lookup.missing());return ++sent;}
        public void cancelRequest(int token){}public Object download(Message message,Reply<File> reply){++downloads;reply.onReply(payload);return null;}public void cancelDownload(Object token){}
        public void localCopies(Reply<List<LocalCopy>> reply){reply.onReply(Collections.emptyList());}public File filesDirectory(){return payload.getParentFile();}public File cacheDirectory(){return payload.getParentFile();}public long nowMillis(){return 50000;}public void send(File file,String caption,String mime,Receipt receipt){throw new AssertionError("no posting in source cases");}
        void drain(){int remaining=20000;while(!work.isEmpty()){check(remaining-->0,"bounded inventory work queue");work.removeFirst().run();}}
    }
    static PurpleSyncTransport.CheckResult inventory(Client nativeClient){
        PurpleSyncChatRecordClient owned=new PurpleSyncChatRecordClient(nativeClient,message->PurpleSyncChatRecordClient.selfOwnership(ORIGIN,(TLRPC.Message)message.remote));
        PurpleSyncTelegramTransport transport=new PurpleSyncTelegramTransport(owned);PurpleSyncTransport.CheckResult[] result={null};transport.check(null,value->result[0]=value);nativeClient.drain();check(result[0]!=null,"full real scanner/reader completed");return result[0];
    }
    static RecordPresence presence(Client client){PurpleSyncTransport.CheckResult result=inventory(client);return result.status==PurpleSyncTransport.CheckStatus.Finished?PurpleSyncChat.recordPresence(result.inventory,11):RecordPresence.Unknown;}
    static PurpleSyncChatDiscovery.Status hintResult(RecordPresence presence){return hintResult(presence,SettingsHint.Found);}
    static PurpleSyncChatDiscovery.Status hintResult(RecordPresence presence,SettingsHint hint){
        PurpleSyncChatModelCases.World world=new PurpleSyncChatModelCases.World();world.hint=hint;world.records.put(SELF,presence);
        PurpleSyncChatDiscovery model=PurpleSyncChatModelCases.model(null);model.start(1000);world.drain(model,1000);return model.status();
    }
    static SettingsHint hint(TLRPC.Message first,int total){
        PurpleSyncChatRequestCases.Native platform=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,platform);SettingsHint[] found={null};
        client.hint(value->found[0]=value);TLRPC.TL_messages_messagesSlice response=new TLRPC.TL_messages_messagesSlice();response.count=total;response.messages.add(first);platform.reply(1,response);client.cancel();check(found[0]!=null,"literal native Q callback completed");return found[0];
    }
    static void run(File root)throws Exception{
        PurpleAccountSyncCore.Result built=PurpleAccountSyncCore.buildConfigRecord(PurpleSyncChatServiceCases.bytes("version=2\n"),new byte[0][],PurpleAccountSyncCore.formatSpaceId(new byte[16]).id,PurpleAccountSyncCore.formatInstallId(new byte[16]).id,"synthetic-device","Android","synthetic",1,1700000000);
        check(built.isValid()&&built.record!=null,"genuine core config envelope "+built.status+" "+built.error);File payload=new File(root,"synthetic-record.json");Files.write(payload.toPath(),built.record);
        Client client=new Client(payload);client.pages.add(messages());check(presence(client)==RecordPresence.Empty,"exact complete empty history is Empty control");
        check(hintResult(RecordPresence.Empty).phase==PurpleSyncChatDiscovery.Phase.Unset,"complete empty inventory can reject Q hint");
        client=new Client(payload);TLRPC.TL_message valid=document(7,false);client.pages.add(messages(valid));client.fresh.put(7,valid);check(presence(client)==RecordPresence.Present&&client.downloads==1,"valid record full fresh metadata and bytes Present");check(hintResult(RecordPresence.Present,hint(valid,1)).phase==PurpleSyncChatDiscovery.Phase.Use,"valid first Q control resolves after full inventory");
        client=new Client(payload);TLRPC.TL_message forwarded=document(9,false);forwarded.fwd_from=new TLRPC.TL_messageFwdHeader();client.pages.add(messages(forwarded,valid));client.fresh.put(7,valid);
        RecordPresence recovered=presence(client);check(recovered==RecordPresence.Present&&client.downloads==1,"excluded newer Q hit does not hide older owned record");check(hintResult(recovered,hint(forwarded,2)).phase==PurpleSyncChatDiscovery.Phase.Use,"excluded first Q plus older owned inventory never Unset");
        client=new Client(payload);client.pages.add(messages(document(7,true)));check(presence(client)==RecordPresence.Unknown&&client.downloads==0,"C0 Unknown scan cannot become ignored/Empty");
        client=new Client(payload);client.pages.add(messages(valid));client.fresh.put(7,document(7,true));check(presence(client)==RecordPresence.Unknown&&client.downloads==0,"C0 Unknown fresh metadata cannot become absent/Empty");
        client=new Client(payload);client.pages.add(messages(valid));TLRPC.TL_message changed=document(7,false);changed.from_scheduled=true;client.fresh.put(7,changed);check(presence(client)==RecordPresence.Unknown,"fresh excluded metadata is changed/held not Empty");
        client=new Client(payload);client.pages.add(messages(valid));check(presence(client)==RecordPresence.Unknown,"vanished candidate is held not Empty");
        for(String failure:new String[]{"inexact","flood","transport","partial","count","notmodified","channel","wrongpeer","cap"}){
            client=new Client(payload);TLRPC.TL_messages_messagesSlice page=new TLRPC.TL_messages_messagesSlice();page.count=2;
            if(failure.equals("inexact"))page.inexact=true;
            if(failure.equals("flood"))page.flags=8;
            if(failure.equals("transport"))client.failed=true;
            if(failure.equals("partial")){TLRPC.TL_message ordinary=mark(SELF,8,50);ordinary.message="ordinary";page.messages.add(ordinary);}
            if(failure.equals("count"))page.count=-1;
            if(failure.equals("wrongpeer"))page.messages.add(mark(USER,8,50));
            client.pages.add(failure.equals("notmodified")?new TLRPC.TL_messages_messagesNotModified():failure.equals("channel")?new TLRPC.TL_messages_channelMessages():page);
            if(failure.equals("partial")){TLRPC.TL_messages_messagesSlice tail=new TLRPC.TL_messages_messagesSlice();tail.count=2;client.pages.add(tail);}
            if(failure.equals("cap"))client.cap=true;
            RecordPresence observed=presence(client);check(observed==RecordPresence.Unknown,"raw history-to-inventory Unknown "+failure);
            PurpleSyncChatDiscovery.Status result=hintResult(observed);check(!result.complete&&result.postingHeld,"incomplete inventory-to-resolution holds "+failure);
            if(failure.equals("cap"))check(client.history==PurpleSyncChatRecordClient.MAX_HISTORY_PAGES,"cap no extra native history request");
        }
        client=new Client(payload);client.deferHistory=true;TLRPC.TL_message ordinaryA=mark(SELF,10,50),ordinaryB=mark(SELF,9,50);client.pages.add(messages(ordinaryA,ordinaryB));TLRPC.TL_messages_messagesSlice partial=new TLRPC.TL_messages_messagesSlice();partial.count=2;client.pages.add(partial);
        PurpleSyncChatRecordClient generation=new PurpleSyncChatRecordClient(client,message->Ownership.Own);List<PurpleSyncClient.History> obsolete=new ArrayList<>(),current=new ArrayList<>();generation.requestHistory(0,100,obsolete::add);generation.requestHistory(0,100,current::add);client.historyReplies.get(0).run();client.historyReplies.get(1).run();
        check(obsolete.isEmpty()&&current.size()==1&&current.get(0).messages==null,"late obsolete scan cannot inflate new scan coverage into Empty");
        PurpleSyncChatModelCases.World ineligible=new PurpleSyncChatModelCases.World();ineligible.hint=hint(valid,1);ineligible.records.put(SELF,RecordPresence.Present);PurpleSyncChatFacts.Input ttl=input(SELF);ttl.userFull.ttl_period=10;ineligible.peers.put(SELF,info(SELF,ttl));PurpleSyncChatDiscovery paused=PurpleSyncChatModelCases.model(null);paused.start(1000);ineligible.drain(paused,1000);
        check(paused.status().phase==PurpleSyncChatDiscovery.Phase.Paused&&paused.status().postingHeld,"ineligible first Q cannot become Unset");
        for(boolean vanished:new boolean[]{false,true}){
            client=new Client(payload);TLRPC.TL_messages_messagesSlice head=new TLRPC.TL_messages_messagesSlice();head.count=1;head.messages.add(ordinaryA);client.pages.add(head);
            TLRPC.TL_messages_messagesSlice grown=new TLRPC.TL_messages_messagesSlice();grown.count=2;client.pages.add(vanished?messages():grown);
            check(presence(client)==(vanished?RecordPresence.Empty:RecordPresence.Unknown),"declared total retained across constructors / stable count growth guard");
        }
        check(PurpleSyncChat.recordPresence(new PurpleSyncInventory.Builder(22).build(true),11)==RecordPresence.Unknown,"wrong inventory account held");
        check(PurpleSyncChat.recordPresence(new PurpleSyncInventory.Builder(11).build(false),11)==RecordPresence.Unknown,"partial empty inventory held");
        check(PurpleSyncChat.recordPresence(new PurpleSyncInventory.Builder(11).fetched(7,107,0,PurpleSyncChatServiceCases.bytes("invalid")).build(true),11)==RecordPresence.Unknown,"unvalidated bytes cannot prove Present");
        PurpleSyncChatModelCases.World world=new PurpleSyncChatModelCases.World();world.add(mark(SELF,7,50));world.add(mark(CHANNEL,8,60));
        PurpleSyncChatCache.Data original=PurpleSyncChatModelCases.cache(SELF,7,50);world.reads.put("self/7",DirectRead.Unknown);PurpleSyncChatDiscovery model=PurpleSyncChatModelCases.model(original);model.start(1000);world.drain(model,1000);check(model.cache()==original&&model.status().postingHeld,"Unknown evidence keeps original cache");
    }
}
