package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

final class PurpleSyncChatUploadIntegrationCases {
    static final class Port implements PurpleSyncAutoUpload.Port {
        PurpleSyncClient.Reply<Boolean> uploaded,allowed;
        PurpleSyncClient.Reply<PurpleSyncAutoUpload.Result> sent;
        byte[] previous;
        Runnable authorize;
        int uploads,reads,gates,sends,cancels;
        boolean throwing,managed;
        public boolean managedWait(){return managed;}
        public void worker(Runnable task){task.run();}
        public int upload(long id,byte[] bytes,PurpleSyncClient.Reply<Boolean> done){++uploads;uploaded=done;return 41;}
        public void exact(int id,PurpleSyncClient.Reply<byte[]> done){++reads;done.onReply(previous);}
        public void beforePost(PurpleSyncClient.Reply<Boolean> done){++gates;if(throwing)throw new IllegalStateException("synthetic");allowed=done;if(authorize!=null)authorize.run();}
        public int send(int id,TLRPC.TL_inputFile file,PurpleSyncClient.Reply<PurpleSyncAutoUpload.Result> done){++sends;sent=done;return 42;}
        public void cancel(int token){++cancels;}
    }
    static void uploads(File root)throws Exception {
        PurpleSyncAutoCases.Port setup=PurpleSyncAutoCases.bound(root,"a2-upload-gates");PurpleSyncAutoCases.initialPublish(setup);
        byte[] bytes=setup.client.messages.values().iterator().next();
        for(boolean edit:new boolean[]{false,true})for(String state:new String[]{"allow","deny","stale","timeout","throw","cleanup"}) {
            Port port=new Port();port.previous=bytes.clone();boolean[] current={true};int[] replies={0};PurpleSyncAutoUpload.Result[] result={null};
            Set<Runnable> timers=new HashSet<>(AndroidUtilities.delayed.keySet());
            PurpleSyncAutoUpload upload=new PurpleSyncAutoUpload(1,edit?501:0,bytes,bytes,()->current[0],port,value->{++replies[0];result[0]=value;});
            upload.start();check(port.uploads==1&&port.gates==0&&port.sends==0,"fresh gate waits for last upload part");
            if(state.equals("throw"))port.throwing=true;
            port.uploaded.onReply(true);
            check(port.gates==1&&port.reads==(edit?1:0)&&port.sends==0,"fresh gate after upload and edit previous-byte exact read");
            if(state.equals("throw")){check(replies[0]==1&&result[0].id==0,"throwing resolver fails closed");continue;}
            if(state.equals("stale"))current[0]=false;
            if(state.equals("timeout"))PurpleSyncAutoReviewCases.timer(timers).run();
            if(state.equals("cleanup"))PurpleSyncAutoEdits.cleanup(1);
            port.allowed.onReply(!state.equals("deny"));
            if(state.equals("allow")) {
                check(port.sends==1&&replies[0]==0,"fresh approval dispatches exactly one final RPC");
                port.allowed.onReply(true);check(port.sends==1,"duplicate resolver callback cannot post twice");
                port.sent.onReply(new PurpleSyncAutoUpload.Result(501,null));
                check(replies[0]==1&&result[0].id==501,"receipt is a server-id hint only");
            } else check(port.sends==0&&replies[0]==1&&result[0].id==0,"no final RPC after "+state);
            check(!PurpleSyncAutoEdits.busy(1),"owned upload guard released after "+state);
        }
        setup.frame.unchangedA();setup.engine.close();
    }
    static void managed(File root)throws Exception {
        PurpleSyncAutoCases.Port setup=PurpleSyncAutoCases.bound(root,"q2b-managed-upload");PurpleSyncAutoCases.initialPublish(setup);
        byte[] bytes=setup.client.messages.values().iterator().next();
        for(boolean cancel:new boolean[]{false,true}){
            Port port=new Port();port.managed=true;boolean[] current={true};List<PurpleSyncAutoUpload.Result> results=new ArrayList<>();
            Set<Runnable> before=new HashSet<>(AndroidUtilities.delayed.keySet());
            PurpleSyncAutoUpload upload=new PurpleSyncAutoUpload(1,0,bytes,null,()->current[0],port,results::add);upload.start();
            Runnable timer=PurpleSyncAutoReviewCases.timer(before);
            AndroidUtilities.delayed.remove(timer);timer.run();
            check(results.isEmpty()&&PurpleSyncAutoEdits.busy(1)&&AndroidUtilities.delayed.containsKey(timer),"managed upload wait survives aggregate watchdog");
            port.uploaded.onReply(true);AndroidUtilities.delayed.remove(timer);timer.run();
            check(results.isEmpty()&&port.gates==1&&port.sends==0,"managed final proof wait retains original upload completion");
            port.allowed.onReply(true);AndroidUtilities.delayed.remove(timer);timer.run();
            check(results.isEmpty()&&port.sends==1,"managed in-flight effect retains original completion");
            if(cancel)upload.cancel();else{current[0]=false;AndroidUtilities.delayed.remove(timer);timer.run();}
            check(results.size()==1&&results.get(0).id==0&&!PurpleSyncAutoEdits.busy(1)&&!AndroidUtilities.delayed.containsKey(timer),"cancelled or retired managed capability releases guard exactly once");
            int calls=results.size();port.sent.onReply(new PurpleSyncAutoUpload.Result(501,null));
            check(results.size()==calls&&port.sends==1,"late receipt after managed cancellation cannot revive delivery or send");
        }
        setup.engine.close();setup.frame.unchangedA();
    }
    static final class Client implements PurpleSyncClient {
        final Frame frame;
        final Port port=new Port();
        byte[] bytes;
        File file;
        Client(Frame frame){this.frame=frame;}
        public long userId(){return 202;}public boolean isActive(){return frame.env(1).current(1,202);}
        public void runOnMain(Runnable task){task.run();}public void runOnWorker(Runnable task){task.run();}public void log(String text){}
        public int requestHistory(int offset,int limit,Reply<History> done){throw new AssertionError("pipeline uses captured account inventory");}
        public int requestMessage(int id,Reply<Lookup> done){
            done.onReply(id==501&&port.sends==1?Lookup.found(new Message(id,true,false,true,PurpleSyncPost.CAPTION,PurpleSyncPost.RECORD_FILE_NAME,true,601,bytes.length,50,null)):Lookup.missing());return 71;
        }
        public void cancelRequest(int token){}public Object download(Message message,Reply<File> done){done.onReply(file);return null;}public void cancelDownload(Object token){}
        public void localCopies(Reply<List<LocalCopy>> done){done.onReply(Collections.emptyList());}
        public File filesDirectory(){return new File(frame.app,"ephemeral");}public File cacheDirectory(){return new File(frame.app,"cache");}public long nowMillis(){return 1800000000000L;}
        public void send(File file,String caption,String mime,Receipt receipt){
            check(receipt.authority()!=null&&receipt.current(),"manual modern source path carries runner authority");
            try{this.file=file;bytes=Files.readAllBytes(file.toPath());}catch(Exception e){throw new RuntimeException(e);}
            check(Arrays.equals(bytes,receipt.expectedBytes()),"exact staged bytes preserved before upload");
            PurpleSyncAutoUpload operation=new PurpleSyncAutoUpload(1,0,bytes,null,receipt::current,port,result->receipt.onReceipt(result.id,result.id<=0));
            port.authorize=()->receipt.authority().check(ready->port.allowed.onReply(ready&&receipt.current()));
            operation.start();
        }
    }
    static void pipeline(File root)throws Exception {
        for(boolean allow:new boolean[]{false,true}) {
            Frame frame=new Frame(root,"manual-post-upload-"+allow,true);frame.initialize(1);
            PurpleSyncChatIntegrationCases.Gated gate=new PurpleSyncChatIntegrationCases.Gated(frame.env(1));Client client=new Client(frame);
            PurpleSyncTelegramTransport nativeTransport=new PurpleSyncTelegramTransport(client);
            PurpleSyncTransport routed=new PurpleSyncTransport(){
                public void check(Progress progress,CheckDone done){frame.clouds[1].check(progress,done);}
                public void post(byte[] bytes,PostDone done){throw new AssertionError("manual must use owned post authority");}
                public void post(byte[] bytes,PostAuthority authority,PostDone done){nativeTransport.post(bytes,authority,done);}
                public void cancel(){nativeTransport.cancel();}
            };
            PurpleSyncRunner runner=new PurpleSyncRunner(1,202,routed,gate,Runnable::run,Runnable::run);
            PurpleSyncRunner.PostTicket ticket=PurpleSyncLifecycleCases.ticket(runner);
            PurpleSyncPublisher.Result[] result={null};int before=gate.gates;
            check(runner.publish(ticket,value->result[0]=value)==PurpleSyncRunner.Start.Started,"manual publisher enters owned upload path");
            check(client.port.uploads==1&&client.port.gates==0&&result[0]==null&&gate.gates==before+1,"initial gate precedes staging, fresh final gate not yet run");
            gate.allow=allow;client.port.uploaded.onReply(true);
            check(gate.gates==before+2,"fresh resolver reruns after uploaded bytes, with original borrowed lock");
            if(allow) {
                check(client.port.sends==1&&result[0]==null,"fresh final approval permits one silent-send port");
                client.port.sent.onReply(new PurpleSyncAutoUpload.Result(501,null));
                check(result[0]!=null&&result[0].status==PurpleSyncCore.PublishStatus.Confirmed,"real post metadata/bytes/metadata precedes ledger confirmation");
                check(!new File(frame.sync(1),"pending/config.json").exists(),"confirmed exact bytes clear pending ledger");
            } else {
                check(client.port.sends==0&&result[0]!=null&&result[0].status==PurpleSyncCore.PublishStatus.OutcomeUnknown,"new denial after upload holds final send");
                check(new File(frame.sync(1),"pending/config.json").isFile(),"uncertain attempt retains staged bytes for reconciliation");
            }
            check(!PurpleSyncOperations.busy(gate.operationKey(1,202)),"manual final gate does not take or release another owner lock");
            frame.unchangedA();runner.close();
        }
    }
    static void borrowed(File root)throws Exception {
        PurpleSyncAutoCases.Port setup=PurpleSyncAutoCases.bound(root,"a2-borrowed-proof");setup.enable();
        setup.client.holdSend=true;setup.saved(OWN);
        check(setup.client.sending!=null,"automatic effect held in same owner/store run");
        BooleanSupplier proof=setup.engine.borrowedAuthority();check(proof.getAsBoolean(),"borrowed lock authority current while effect pending");
        PurpleSyncClient.Receipt receipt=setup.client.sending;check(receipt.current(),"exact effect ticket current control");
        File auto=new File(setup.frame.sync(1),"auto.json");byte[] original=Files.readAllBytes(auto.toPath());
        org.json.JSONObject value=new org.json.JSONObject(new String(original,java.nio.charset.StandardCharsets.UTF_8));
        value.put("ticket",Long.toString(Long.parseLong(value.getString("ticket"))+1));Files.write(auto.toPath(),bytes(value.toString()));
        check(proof.getAsBoolean()&&!receipt.current(),"borrowed run proof survives ticket change while exact effect authority fails");
        Files.write(auto.toPath(),original);check(proof.getAsBoolean()&&receipt.current(),"unchanged run/ticket control restored");
        setup.engine.close();check(!proof.getAsBoolean()&&!receipt.current(),"end owner run invalidates both authorities");setup.frame.unchangedA();
    }
    static void exact(File root)throws Exception {
        PurpleSyncAutoCases.Port setup=PurpleSyncAutoCases.bound(root,"a2-exact-network");PurpleSyncAutoCases.initialPublish(setup);
        byte[] canonical=setup.client.messages.values().iterator().next();File payload=new File(root,"exact-network.json");Files.write(payload.toPath(),canonical);
        for(PurpleSyncChatCore.Peer chat:new PurpleSyncChatCore.Peer[]{PurpleSyncChatRequestCases.SELF,PurpleSyncChatRequestCases.USER,PurpleSyncChatRequestCases.GROUP,PurpleSyncChatRequestCases.CHANNEL}) {
            for(String change:new String[]{"control","edited","vanished","wrongpeer","excluded"}) {
                PurpleSyncChatIOCases.Client client=new PurpleSyncChatIOCases.Client(chat,payload);
                TLRPC.TL_message row=PurpleSyncChatIOCases.doc(chat,7,payload.length());
                TLRPC.TL_message fresh=PurpleSyncChatIOCases.doc(change.equals("wrongpeer")?PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.User,99):chat,7,payload.length());
                if(change.equals("edited"))fresh.edit_date=101;
                if(change.equals("excluded"))fresh.from_scheduled=true;
                client.exact.add(PurpleSyncChatRequestCases.messages(row));
                client.exact.add(change.equals("vanished")?PurpleSyncChatRequestCases.messages():PurpleSyncChatRequestCases.messages(fresh));
                PurpleSyncClient owned=new PurpleSyncChatRecordClient(client,message->client.io.ownership((TLRPC.Message)message.remote));
                PurpleSyncAutoNetwork network=new PurpleSyncAutoNetwork(owned,"synthetic",()->true);
                org.json.JSONObject[] found={null};network.exact(7,()->true,value->found[0]=value);client.drain();
                check(found[0]!=null&&client.reads==2&&client.downloads==1,"exact network metadata/download/metadata "+chat.token());
                check(found[0].optBoolean("owned")==change.equals("control"),"exact network changed metadata never owned "+change);
                if(change.equals("control"))check(Arrays.equals(canonical,PurpleSyncAutoCore.decode(found[0].getString("record"))),"exact staged bytes from correct typed chat");
                network.cancel();
            }
        }
        setup.engine.close();setup.frame.unchangedA();
    }
    static void variants(File root)throws Exception {
        PurpleSyncAutoCases.Port setup=PurpleSyncAutoCases.bound(root,"a2-bot-supergroup");PurpleSyncAutoCases.initialPublish(setup);
        File payload=new File(root,"variant-record.json");Files.write(payload.toPath(),setup.client.messages.values().iterator().next());
        for(boolean group:new boolean[]{false,true})for(boolean changed:new boolean[]{false,true}) {
            PurpleSyncChatCore.Peer chat=group?PurpleSyncChatRequestCases.CHANNEL:PurpleSyncChatRequestCases.USER;
            PurpleSyncChatFacts.Input facts=PurpleSyncChatCases.input(chat);
            if(group){facts.group.broadcast=false;facts.group.megagroup=true;}else facts.user.bot=true;
            PurpleSyncChatClient.Info info=PurpleSyncChatRequestCases.info(chat,facts);
            check(info.peer.kind==(group?PurpleSyncChatCore.Kind.Supergroup:PurpleSyncChatCore.Kind.Bot)&&info.eligibility().allowed(),"typed variant affirmative full info control");
            PurpleSyncChatIO io=new PurpleSyncChatIO(PurpleSyncChatCases.ORIGIN,chat,info.peer);
            PurpleSyncChatIOCases.Client client=new PurpleSyncChatIOCases.Client(chat,payload);
            TLRPC.TL_message row=PurpleSyncChatIOCases.doc(chat,7,payload.length()),fresh=PurpleSyncChatIOCases.doc(chat,7,payload.length());
            if(group){row.from_id=PurpleSyncChatCases.peer(PurpleSyncChatRequestCases.SELF);row.flags|=1<<8;fresh.from_id=PurpleSyncChatCases.peer(PurpleSyncChatRequestCases.SELF);fresh.flags|=1<<8;}
            client.pages.add(PurpleSyncChatIOCases.page(chat,1,row));client.pages.add(PurpleSyncChatIOCases.page(chat,1));
            client.exact.add(PurpleSyncChatRequestCases.messages(row));
            if(changed){if(group)fresh.flags&=~(1<<8);else fresh.out=false;}
            client.exact.add(PurpleSyncChatRequestCases.messages(fresh));
            PurpleSyncClient owned=new PurpleSyncChatRecordClient(client,message->io.ownership((TLRPC.Message)message.remote));
            PurpleSyncTransport.CheckResult[] result={null};new PurpleSyncTelegramTransport(owned).check(null,value->result[0]=value);client.drain();
            check(result[0]!=null&&PurpleSyncChat.recordPresence(result[0].inventory,11,chat)==(changed?PurpleSyncChatCore.RecordPresence.Unknown:PurpleSyncChatCore.RecordPresence.Present),"full typed variant scanner/reader/C0 second read "+info.peer.kind);
            TLRPC.Update update=PurpleSyncChatUploadCases.update(chat,7,false);
            if(group){TLRPC.Message sent=PurpleSyncChatUploadCases.message(update);sent.from_id=PurpleSyncChatCases.peer(PurpleSyncChatRequestCases.SELF);sent.flags|=1<<8;}
            check(PurpleSyncChatUpload.receipt(io,PurpleSyncChatUploadCases.updates(false,PurpleSyncChatUploadCases.mapping(991,7),update),991,0)==7,"typed variant channel/ordinary receipt control");
        }
        setup.engine.close();setup.frame.unchangedA();
    }
    static int run(File root)throws Exception {
        int before=checks;uploads(root);managed(root);pipeline(root);borrowed(root);exact(root);variants(root);return checks-before;
    }
}
