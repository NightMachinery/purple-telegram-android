package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatIOCases {
    static PurpleSyncChatIO io(Peer chat) { return new PurpleSyncChatIO(ORIGIN,chat,info(chat).peer); }
    static TLRPC.TL_message doc(Peer chat,int id,long size) {
        TLRPC.TL_message row=mark(chat,id,50);row.message=PurpleSyncPost.CAPTION;
        row.media=new TLRPC.TL_messageMediaDocument();row.media.document=new TLRPC.TL_document();
        row.flags|=1<<9;row.media.flags|=1;
        row.media.document.id=100+id;row.media.document.size=size;row.media.document.file_reference=new byte[0];row.media.document.mime_type="application/json";
        TLRPC.TL_documentAttributeFilename name=new TLRPC.TL_documentAttributeFilename();name.file_name=PurpleSyncPost.RECORD_FILE_NAME;row.media.document.attributes.add(name);
        return row;
    }
    static PurpleSyncClient.Message meta(TLRPC.Message row) {
        TLRPC.Document doc=row.media!=null?row.media.document:null;
        return new PurpleSyncClient.Message(row.id,row.getClass()==TLRPC.TL_message.class,row.fwd_from!=null,
                doc!=null,row.message,PurpleSyncPost.RECORD_FILE_NAME,PurpleSyncChatFacts.token(row.peer_id,11),
                doc!=null?doc.id:0,doc!=null?doc.size:0,row.edit_date,row);
    }
    static TLRPC.messages_Messages page(Peer chat,int count,TLRPC.Message... rows) {
        TLRPC.messages_Messages value=chat.kind==PeerKind.Channel?new TLRPC.TL_messages_channelMessages():new TLRPC.TL_messages_messagesSlice();
        value.count=count;value.messages.addAll(Arrays.asList(rows));return value;
    }
    static final class Client implements PurpleSyncClient {
        final PurpleSyncChatIO io;
        final File payload;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        final ArrayDeque<TLObject> pages=new ArrayDeque<>();
        final ArrayDeque<TLObject> exact=new ArrayDeque<>();
        int reads,downloads;boolean active=true;
        Client(Peer chat,File payload){io=io(chat);this.payload=payload;}
        public Peer chat(){return io.chat;}public PurpleSyncChatOrigin origin(){return ORIGIN;}
        public long userId(){return 11;}public boolean isActive(){return active;}
        public void runOnMain(Runnable task){tasks.add(task);}public void runOnWorker(Runnable task){tasks.add(task);}public void log(String line){}
        public int requestHistory(int offset,int limit,Reply<History> done){done.onReply(io.history(pages.isEmpty()?page(chat(),1):pages.removeFirst(),null,PurpleSyncChatIOCases::meta));return 1;}
        public int requestMessage(int id,Reply<Lookup> done){++reads;done.onReply(io.lookup(exact.isEmpty()?messages():exact.removeFirst(),null,id,PurpleSyncChatIOCases::meta));return reads;}
        public void cancelRequest(int token){}public Object download(Message message,Reply<File> done){++downloads;done.onReply(payload);return null;}public void cancelDownload(Object token){}
        public void localCopies(Reply<List<LocalCopy>> done){done.onReply(Collections.emptyList());}public File filesDirectory(){return payload.getParentFile();}public File cacheDirectory(){return payload.getParentFile();}public long nowMillis(){return 1;}
        public void send(File f,String caption,String mime,Receipt receipt){throw new AssertionError("Source-only, no posting");}
        void drain(){int cap=10000;while(!tasks.isEmpty()){check(--cap>0,"bounded typed transport");tasks.removeFirst().run();}}
        PurpleSyncInventory inventory(){
            PurpleSyncChatRecordClient owned=new PurpleSyncChatRecordClient(this,row->io.ownership((TLRPC.Message)row.remote));
            PurpleSyncTransport.CheckResult[] result={null};new PurpleSyncTelegramTransport(owned).check(null,value->result[0]=value);drain();
            check(result[0]!=null&&result[0].status==PurpleSyncTransport.CheckStatus.Finished,"real typed scanner/reader finished");return result[0].inventory;
        }
    }
    static void run(File root)throws Exception {
        int before=checks;
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}) {
            PurpleSyncChatIO io=io(chat);
            TLRPC.TL_messages_getHistory history=PurpleSyncChatIO.history(chat,inputPeer(chat),17,100);
            SerializedData bytes=wire(history);check(bytes.readInt32(true)==TLRPC.TL_messages_getHistory.constructor,"typed history constructor");
            TLRPC.InputPeer actual=TLRPC.InputPeer.TLdeserialize(bytes,bytes.readInt32(true),true);
            check(PurpleSyncChatRequests.matches(chat,actual)&&bytes.readInt32(true)==17,"typed history peer/hash/offset "+chat.token());
            TLObject request=PurpleSyncChatRequests.exact(chat,7,inputPeer(chat));bytes=wire(request);
            check(bytes.readInt32(true)==(chat.kind==PeerKind.Channel?TLRPC.TL_channels_getMessages.constructor:TLRPC.TL_messages_getMessages.constructor),"typed exact namespace "+chat.token());
            TLRPC.TL_message row=doc(chat,7,5);
            check(io.lookup(page(chat,1,row),null,7,PurpleSyncChatIOCases::meta).status==(chat.kind==PeerKind.Channel?PurpleSyncClient.LookupStatus.Found:PurpleSyncClient.LookupStatus.Failed),"channel exact constructor accepted only on channel");
            check(io.lookup(messages(row),null,7,PurpleSyncChatIOCases::meta).status==PurpleSyncClient.LookupStatus.Found,"ordinary exact metadata "+chat.token());
            check(io.lookup(messages(doc(Peer.of(PeerKind.User,99),7,5)),null,7,PurpleSyncChatIOCases::meta).status==PurpleSyncClient.LookupStatus.Failed,"same id wrong chat held");
            TLRPC.TL_messageEmpty absent=new TLRPC.TL_messageEmpty();absent.id=7;absent.flags=1;absent.peer_id=peer(chat);
            check(io.lookup(messages(absent),null,7,PurpleSyncChatIOCases::meta).status==PurpleSyncClient.LookupStatus.Missing,"matching empty control");
            absent.peer_id=peer(Peer.of(PeerKind.User,99));check(io.lookup(messages(absent),null,7,PurpleSyncChatIOCases::meta).status==PurpleSyncClient.LookupStatus.Failed,"empty wrong peer held");
            absent.peer_id=null;check(io.lookup(messages(absent),null,7,PurpleSyncChatIOCases::meta).status==PurpleSyncClient.LookupStatus.Failed,"empty dangling flag held");
            check(io.history(page(chat,1,row),null,PurpleSyncChatIOCases::meta).messages!=null,"typed counted history "+chat.token());
            TLRPC.messages_Messages flood=page(chat,1,row);flood.inexact=true;
            check(io.history(flood,null,PurpleSyncChatIOCases::meta).messages==null&&io.lookup(flood,null,7,PurpleSyncChatIOCases::meta).status==PurpleSyncClient.LookupStatus.Failed,"inexact never complete");
            check(io.history(new TLRPC.TL_messages_messagesNotModified(),null,PurpleSyncChatIOCases::meta).messages==null,"NotModified unknown");
            check(io.history(messages(doc(Peer.of(PeerKind.User,99),7,5)),null,PurpleSyncChatIOCases::meta).messages==null,"wrong scope history unknown");
            check(PurpleSyncChatIO.history(chat,inputPeer(Peer.of(PeerKind.User,99)),0,100)==null,"input mismatch no fallback");
            byte[] state=("{\"version\":2,\"chat\":\""+chat.token()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            PurpleSyncInventory inventory=new PurpleSyncInventory.Builder(11,chat,ORIGIN).build(true);
            check(inventory.matchesState(state),"ledger chat matches state");
            check(!inventory.matchesState("{\"version\":2,\"chat\":\"user:99\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"other typed ledger id refused");
            check(inventory.matchesState(null)==chat.equals(SELF),"unbound nonself still A3 held");
            check(inventory.matchesState("{\"version\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8))==chat.equals(SELF),"v1 implicit self");
        }
        PurpleAccountSyncCore.Result record=PurpleAccountSyncCore.buildConfigRecord(PurpleSyncChatServiceCases.bytes("version=2\n"),new byte[0][],PurpleAccountSyncCore.formatSpaceId(new byte[16]).id,PurpleAccountSyncCore.formatInstallId(new byte[16]).id,"synthetic","Android","synthetic",1,1700000000);
        check(record.isValid(),"typed readback canonical core record");File payload=new File(root,"typed-record.json");Files.write(payload.toPath(),record.record);
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}) {
            Client client=new Client(chat,payload);TLRPC.TL_message row=doc(chat,7,payload.length());
            client.pages.add(page(chat,1,row));client.pages.add(page(chat,1));client.exact.add(messages(row));client.exact.add(messages(row));
            PurpleSyncInventory result=client.inventory();
            check(result.chat.equals(chat)&&result.origin.equals(ORIGIN)&&client.reads==2,"typed inventory origin and metadata/download/metadata");
            check(PurpleSyncChat.recordPresence(result,11,chat)==RecordPresence.Present,"typed complete candidate Present");
            check(PurpleSyncChat.recordPresence(result,11,Peer.of(PeerKind.User,99))==RecordPresence.Unknown,"cross-chat inventory never Empty/Present");
            for(String change:new String[]{"edited","vanished","wrongpeer","unknown","other"}) {
                client=new Client(chat,payload);row=doc(chat,7,payload.length());client.pages.add(page(chat,1,row));client.pages.add(page(chat,1));client.exact.add(messages(row));
                TLRPC.TL_message changed=doc(change.equals("wrongpeer")?Peer.of(PeerKind.User,99):chat,7,payload.length());
                if(change.equals("edited"))changed.edit_date=100;
                if(change.equals("unknown"))changed.media=new TLRPC.TL_messageMediaDocument_layer187();
                if(change.equals("other"))changed.fwd_from=new TLRPC.TL_messageFwdHeader();
                client.exact.add(change.equals("vanished")?messages():messages(changed));
                check(PurpleSyncChat.recordPresence(client.inventory(),11,chat)==RecordPresence.Unknown,"post-download metadata changed held "+chat.token()+" "+change);
            }
        }
        System.out.println("TYPED_IO_CHECKS="+(checks-before));
    }
}
