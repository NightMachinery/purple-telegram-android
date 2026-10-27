package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

final class PurpleSyncChatManualIntegrationCases {
    static final class Port implements PurpleSyncChatManual.Port,PurpleSyncTransport {
        final Frame files;
        final PurpleSyncFileContext context;
        final PurpleSyncChatOrigin origin;
        final Peer target;
        final Cache mark;
        final PurpleSyncChatCache.IO cache;
        final String authority=PurpleSyncChatServiceCases.AUTH;
        PurpleSyncChatScreen.Frame frame;
        PurpleSyncChatService.Binding binding;
        boolean worker,foreground=true,active=true,complete=true,defer,holdPost;
        int gates,scans,posts,cancels;
        final List<Object> owners=new ArrayList<>();
        final List<Runnable> pending=new ArrayList<>();
        final List<byte[]> records=new ArrayList<>();
        byte[] posted;
        PostDone postedCallback;
        Port(File root,Peer target)throws Exception{
            files=new Frame(root,"manual-"+target.kind+"-"+UUID.randomUUID(),true);
            PurpleSyncChatSetupCacheConsentIntegrationCases.field("started",true);
            PurpleSyncChatSetupCacheConsentIntegrationCases.field("ready",true);
            PurpleSyncChatSetupCacheConsentIntegrationCases.field("owner",A);
            PurpleSyncChatSetupCacheConsentIntegrationCases.field("global",A);
            context=new PurpleSyncFileContext(1);this.target=target;origin=new PurpleSyncChatOrigin(1,0,202,"manual-fixture");mark=new Cache(target,7,50);
            cache=PurpleSyncChatCache.file(new File(files.root,"sync-chat/"+origin.accountKey()+".json"));
            PurpleSyncChatClient.Info info=info();
            PurpleSyncChatCache.Data data=new PurpleSyncChatCache.Data(mark,"synthetic target",PurpleSyncChatCache.ReadersSnapshot.from(info),Collections.singleton("config"),new JSONObject(),false);
            PurpleModernSyncCases.check(PurpleSyncChatCache.save(cache,new PurpleSyncChatCache.Loaded(true,null,null),origin,authority,data,()->true),"seed explicitly consented setup cache");
            PurpleSyncChatSetup.Journal journal=new PurpleSyncChatSetup.Journal();journal.chat=target;journal.mark=mark;journal.random=123;journal.step=PurpleSyncChatSetup.Step.Complete;
            journal.readers=new PurpleSyncChatScreen.Candidate(origin,target,info).readers;
            write(new File(context.files.syncDirectory,"sync-chat-setup.json"),journal.json(origin,PurpleSyncChatSetup.authority(authority)).toString());
        }
        PurpleSyncChatClient.Info info(){
            PurpleSyncChatClient.Info old=PurpleSyncChatRequestCases.info(target);
            PurpleSyncChatFacts.MappedPeer mapped=new PurpleSyncChatFacts.MappedPeer(origin,target,old.peer.kind,old.peer.creator,old.peer.soleAdmin,old.peer.facts,old.members);
            return new PurpleSyncChatClient.Info(origin,target,mapped,old.error,old.title,old.members,true);
        }
        public PurpleSyncFileContext context(){return context;}public PurpleSyncChatOrigin origin(){return origin;}public String authority(){return authority;}
        public String key(){return context.origin.key+"\n"+context.files.syncDirectory.getPath();}public String device(){return PurpleModernSyncCases.device(1);}
        public PurpleSyncSettingsFile.Contents settings(){PurpleModernSyncCases.check(worker,"settings capture stays on worker");return files.env(1).readSettings();}
        public boolean current(){PurpleModernSyncCases.check(worker,"persisted manual authority validation stays on worker");return active&&context.current();}
        public void worker(Runnable task){boolean before=worker;worker=true;try{task.run();}finally{worker=before;}}
        public void ui(Runnable task){boolean before=worker;worker=false;try{task.run();}finally{worker=before;}}
        public PurpleSyncChatCache.IO cache(){return cache;}
        public PurpleSyncChatService.Binding binding(){
            PurpleModernSyncCases.check(worker,"binding disk read stays on worker");
            PurpleAccountFileSnapshot state=PurpleAccountFileSnapshot.read(new File(context.files.syncDirectory,"state.json"));
            if(state.status==PurpleAccountFileSnapshot.Status.Missing)binding=new PurpleSyncChatService.Binding(true,false,Peer.self());
            else{
                PurpleAccountSyncCore.Result inspected=PurpleAccountSyncCore.inspectState(state.bytes);
                try{JSONObject value=new JSONObject(new String(state.bytes,StandardCharsets.UTF_8));Peer chat=Peer.parse(value.optString("chat","self"),origin.userId);
                    binding=new PurpleSyncChatService.Binding(inspected.isValid(),true,chat,inspected.install+":"+inspected.space);
                }catch(Exception error){binding=new PurpleSyncChatService.Binding(false,false,Peer.self());}
            }
            return binding;
        }
        public PurpleSyncChatScreen.Frame frame(){return frame;}
        public void resolve(boolean beforePost,PurpleSyncChatService.Borrowed borrowed,Consumer<PurpleSyncChatDiscovery.Status> done){
            PurpleModernSyncCases.check(PurpleSyncOperations.owned(key(),borrowed.owner),"manual service borrows the actual runner lock owner");owners.add(borrowed.owner);gates++;
            Runnable work=()->worker(()->{
                binding();PurpleSyncChatCache.Loaded opening=PurpleSyncChatCache.load(cache,origin,authority);
                ResolveInput input=new ResolveInput(origin.userId);input.cache=mark;input.cacheRead=DirectRead.Valid;input.cachedEligibility=info().eligibility();
                PurpleSyncChatDiscovery.Status status=new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Use,PurpleSyncChatCore.resolve(input),
                        borrowed.setupReleased.getAsBoolean()?"":"SetupPostingHeld",true,!borrowed.setupReleased.getAsBoolean(),0);
                frame=new PurpleSyncChatScreen.Frame(origin,new PurpleSyncChat.Status(status),binding,opening,info(),Collections.emptyList());
                ui(()->done.accept(status));
            });
            if(defer)pending.add(work);else work.run();
        }
        public void cancel(Object owner){cancels++;}
        public PurpleSyncTransport records(PurpleSyncChatClient.Info info,BooleanSupplier current){PurpleModernSyncCases.check(!worker,"transport binds captured RAM info on GUI");PurpleModernSyncCases.check(origin.equals(info.origin)&&target.equals(info.chat),"native transport captures exact typed origin");return this;}
        PurpleSyncInventory inventory(){PurpleSyncInventory.Builder b=new PurpleSyncInventory.Builder(origin.userId,target,origin);int id=20;for(byte[] bytes:records)b.fetched(++id,id+1000,50,bytes);return b.build(complete);}
        public void check(Progress progress,CheckDone done){scans++;done.onCheckDone(new CheckResult(CheckStatus.Finished,inventory(),false));}
        public void post(byte[] bytes,PostDone done){throw new AssertionError("unscoped post");}
        public void post(byte[] bytes,PostAuthority authority,PostDone done){
            PurpleModernSyncCases.check(authority.current()&&authority.permits(origin,target),"native send requires exact manual typed capability");
            PurpleModernSyncCases.check(!authority.permits(new PurpleSyncChatOrigin(origin.slot,origin.environment,origin.userId,"replaced"),target),"other login cannot reuse manual capability");
            PurpleModernSyncCases.check(!authority.permits(origin,Peer.of(PeerKind.Channel,999)),"another target cannot reuse manual capability");
            authority.check(ok->{PurpleModernSyncCases.check(Boolean.TRUE.equals(ok),"effect gate revalidates before native send");authority.issued();posts++;posted=bytes.clone();records.add(bytes.clone());
                if(holdPost)postedCallback=done;else done.onPostDone(new PostResult(PostStatus.Confirmed,21,bytes.clone(),target,origin));});
        }
        public void cancel(){}
        PurpleSyncRunner runner(){PurpleSyncChatManual manual=new PurpleSyncChatManual(this,()->foreground);return new PurpleSyncRunner(1,202,manual,manual,this::worker,this::ui);}
    }
    static PurpleSyncRunner.Check reviewed(PurpleSyncRunner runner){PurpleSyncRunner.CheckOutcome[] result={null};PurpleModernSyncCases.check(runner.check(null,value->result[0]=value)==PurpleSyncRunner.Start.Started,"manual check starts");PurpleModernSyncCases.check(result[0]!=null&&result[0].status==PurpleSyncRunner.CheckStatus.Finished,"manual typed inventory reaches settings review");return result[0].check;}
    static void controls(File root)throws Exception{
        for(String fault:new String[]{"unfinished","corrupt","authority","occupied","source","existing-self","other-typed","inactive"}){
            Port p=new Port(root,PurpleSyncChatRequestCases.CHANNEL);
            byte[] original=null;
            if(fault.equals("existing-self")||fault.equals("other-typed")){
                original=p.files.initialize(1);
                if(fault.equals("other-typed")){
                    JSONObject value=new JSONObject(new String(original,StandardCharsets.UTF_8));value.put("version",2).put("chat","chat:999");
                    PurpleAccountSyncCore.Result typed=PurpleAccountSyncCore.inspectState(value.toString().getBytes(StandardCharsets.UTF_8));
                    PurpleModernSyncCases.check(typed.isValid(),"existing other typed fixture is valid");original=typed.state;
                    write(new File(p.context.files.syncDirectory,"state.json"),original);
                }
            }
            if(fault.equals("occupied")){
                PurpleAccountSyncCore.Result record=PurpleAccountSyncCore.buildConfigRecord(OWN,new byte[0][],p.files.newSpace(),install(2),device(2),"Desktop","fixture",1,1700000000);
                PurpleModernSyncCases.check(record.isValid(),"occupied inventory is a genuine canonical record");p.records.add(record.record);
            }
            PurpleSyncRunner runner=p.runner();PurpleSyncRunner.Check shown=reviewed(runner);
            File journal=new File(p.context.files.syncDirectory,"sync-chat-setup.json");
            if(fault.equals("unfinished")||fault.equals("authority")){
                JSONObject changed=new JSONObject(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));
                changed.put(fault.equals("unfinished")?"step":"authority",fault.equals("unfinished")?"PeerInfo":"0".repeat(64));write(journal,changed.toString());
            }
            if(fault.equals("corrupt"))write(journal,"{}");
            if(fault.equals("source"))write(p.files.settings(1),REMOTE);
            if(fault.equals("inactive"))p.active=false;
            PurpleSyncRunner.ApplyOutcome[] applied={null};PurpleSyncRunner.Start start=runner.apply(shown,null,value->applied[0]=value);
            boolean early=(fault.equals("existing-self")||fault.equals("other-typed"))&&start==PurpleSyncRunner.Start.Stale;
            PurpleModernSyncCases.check(early||start==PurpleSyncRunner.Start.Started,"explicit refused flow starts safely "+fault);
            PurpleModernSyncCases.check((early||applied[0]!=null&&applied[0].post==null&&applied[0].result.status!=PurpleSyncCore.ApplyStatus.Applied)&&p.posts==0,"refused activation never initializes/posts "+fault+" status="+(applied[0]!=null?applied[0].result.status:null)+" bound="+shown.review.bound+" records="+shown.inventory.count()+" post="+(applied[0]!=null&&applied[0].post!=null));
            if(original!=null)PurpleModernSyncCases.check(Arrays.equals(original,Files.readAllBytes(new File(p.context.files.syncDirectory,"state.json").toPath())),"existing stream remains byte-identical "+fault);
            else PurpleModernSyncCases.check(!new File(p.context.files.syncDirectory,"state.json").exists(),"fresh refusal leaves no state "+fault);
            runner.close();PurpleModernSyncCases.check(!PurpleSyncOperations.busy(p.key()),"refusal releases operation "+fault);p.files.unchangedA();
        }
        Port p=new Port(root,PurpleSyncChatRequestCases.USER);PurpleSyncRunner runner=p.runner();p.defer=true;
        PurpleSyncRunner.CheckOutcome[] checked={null};runner.check(null,value->checked[0]=value);Object first=p.owners.get(0);p.foreground=false;runner.suspend();
        for(Runnable work:new ArrayList<>(p.pending))work.run();p.pending.clear();
        PurpleModernSyncCases.check(checked[0]==null&&!PurpleSyncOperations.busy(p.key()),"background revokes queued gate without retaining account ownership");
        p.foreground=true;p.defer=false;PurpleSyncRunner.Check shown=reviewed(runner);
        PurpleModernSyncCases.check(first!=p.owners.get(p.owners.size()-1),"reopen uses a fresh actual operation owner");
        PurpleSyncRunner.ApplyOutcome[] applied={null};runner.apply(shown,null,value->applied[0]=value);p.holdPost=true;
        runner.publish(applied[0].post,value->{throw new AssertionError("cancelled presentation callback");});
        PurpleModernSyncCases.check(p.posts==1&&p.postedCallback!=null,"ambiguous settings effect retains a pending stage");
        runner.suspend();p.postedCallback.onPostDone(new PurpleSyncTransport.PostResult(PurpleSyncTransport.PostStatus.Confirmed,21,p.posted,p.target,p.origin));
        PurpleModernSyncCases.check(new File(p.context.files.syncDirectory,"pending/config.json").isFile(),"late cancelled receipt cannot confirm or discard pending");
        shown=reviewed(runner);PurpleSyncPublisher.Result[] finished={null};runner.publish(shown,PurpleSyncCore.PostRequest.finishSending(),value->finished[0]=value);
        PurpleModernSyncCases.check(finished[0]!=null&&finished[0].status==PurpleSyncCore.PublishStatus.Confirmed&&p.posts==1,"restart reconciliation confirms found exact pending bytes without another send");
        runner.close();
    }
    static void initializer(File root)throws Exception{
        Frame frame=new Frame(root,"initializer-controls",true);byte[] entropy=new byte[16];Arrays.fill(entropy,(byte)7);
        PurpleAccountSyncCore.Result fresh=PurpleAccountSyncCore.initializeBoundLocalState(install(1),device(1),frame.newSpace(),entropy);
        PurpleModernSyncCases.check(fresh.isValid(),"fresh native local initializer control");
        for(Peer target:new Peer[]{Peer.self(),PurpleSyncChatRequestCases.USER,PurpleSyncChatRequestCases.GROUP,PurpleSyncChatRequestCases.CHANNEL}){
            PurpleAccountSyncCore.Result typed=PurpleAccountSyncCore.freshTypedState(fresh,target);
            PurpleModernSyncCases.check(typed.isValid()&&PurpleSyncChatIO.ledgerMatches(target,typed.state),"native fresh typed initializer "+target.token());
            JSONObject value=new JSONObject(new String(typed.state,StandardCharsets.UTF_8));
            PurpleModernSyncCases.check(value.getInt("version")== (target.equals(Peer.self())?1:2),"Self v1 and nonSelf v2 are kept separate");
            PurpleModernSyncCases.check(PurpleAccountSyncCore.checkAccountBinding(typed.state,fresh.bindingToken).verdict.equals("Bound"),"typed initializer preserves exact account token");
        }
        for(String field:new String[]{"library","seen","issued"}){
            JSONObject value=new JSONObject(new String(fresh.state,StandardCharsets.UTF_8));JSONObject config=value.getJSONObject("streams").getJSONObject("config");
            if(field.equals("library")){JSONObject library=value.getJSONObject("streams").getJSONObject("library");library.put("seq",1).put("confirmed_seq",1).put("own_hash","0".repeat(64));}
            else if(field.equals("seen"))config.getJSONObject("seen_seq").put(install(2),1);
            else{
                PurpleAccountSyncCore.Result built=PurpleAccountSyncCore.buildConfigRecord(OWN,new byte[0][],fresh.space,fresh.install,device(1),"Android","fixture",1,1700000000);
                PurpleAccountSyncCore.Result reserved=PurpleAccountSyncCore.reserveConfigRecord(fresh.state,built.record,fresh.bindingToken);
                PurpleModernSyncCases.check(reserved.isValid(),"real issued stream control");value=new JSONObject(new String(reserved.state,StandardCharsets.UTF_8));
            }
            PurpleAccountSyncCore.Result existing=PurpleAccountSyncCore.inspectState(value.toString().getBytes(StandardCharsets.UTF_8));
            PurpleModernSyncCases.check(existing.isValid(),"nonfresh stream fixture is natively valid "+field);
            PurpleModernSyncCases.check(!PurpleAccountSyncCore.freshTypedState(existing,PurpleSyncChatRequestCases.CHANNEL).isValid(),"initializer never converts an existing stream "+field);
        }
    }
    static void run(File root)throws Exception{
        int before=checks;
        for(Peer target:new Peer[]{PurpleSyncChatRequestCases.USER,PurpleSyncChatRequestCases.GROUP,PurpleSyncChatRequestCases.CHANNEL}){
            Port p=new Port(root,target);
            PurpleSyncChatCache.Loaded discovered=PurpleSyncChatCache.load(p.cache,p.origin,p.authority);
            PurpleSyncChatCache.Data first=new PurpleSyncChatCache.Data(p.mark,"synthetic discovery",discovered.data.readers,
                    Collections.singleton("library"),new JSONObject(),true);
            PurpleModernSyncCases.check(PurpleSyncChatCache.save(p.cache,discovered,p.origin,p.authority,first,()->true),"discovery commits independent consent first");
            String baseline=first.readers.json().toString();
            PurpleSyncRunner runner=p.runner();PurpleSyncRunner.Check shown=reviewed(runner);
            PurpleModernSyncCases.check(runner.recipient()!=null&&runner.recipient().chat.equals(target),"settings review exposes selected recipient disclosure");
            PurpleSyncRunner.ApplyOutcome[] applied={null};PurpleModernSyncCases.check(runner.apply(shown,null,value->applied[0]=value)==PurpleSyncRunner.Start.Started,"explicit settings and readers confirmation starts typed initialization");
            PurpleModernSyncCases.check(applied[0]!=null&&applied[0].result.status==PurpleSyncCore.ApplyStatus.Applied&&applied[0].post!=null,"fresh Empty target initializes and produces a pending publish ticket");
            PurpleSyncPublisher.Result[] sent={null};PurpleModernSyncCases.check(runner.publish(applied[0].post,value->sent[0]=value)==PurpleSyncRunner.Start.Started,"manual first typed post starts");
            PurpleModernSyncCases.check(sent[0]!=null&&sent[0].status==PurpleSyncCore.PublishStatus.Confirmed&&p.posts==1,"first nonSelf settings post confirms exact typed readback");
            byte[] state=Files.readAllBytes(new File(p.context.files.syncDirectory,"state.json").toPath());
            PurpleModernSyncCases.check(PurpleSyncChatIO.ledgerMatches(target,state)&&new JSONObject(new String(state,StandardCharsets.UTF_8)).getInt("version")==2,"first settings ledger is canonical v2 exact typed chat");
            PurpleModernSyncCases.check(PurpleAccountSyncCore.inspectConfigRecord(p.posted).isValid(),"wire record remains shared canonical BuildConfigRecord output");
            PurpleModernSyncCases.check(p.scans>=2&&p.gates>=4,"fresh target inventory and resumed effect gates precede publish");
            PurpleModernSyncCases.check(p.owners.get(0)!=p.owners.get(p.owners.size()-1),"each operation receives a distinct actual borrowed owner");
            PurpleModernSyncCases.check(!new File(p.context.files.syncDirectory,"pending/config.json").exists(),"confirmed typed readback retires pending stage");
            PurpleModernSyncCases.check(Files.readAllBytes(new File(p.context.files.syncDirectory,"sync-chat-setup.json").toPath()).length>0,"activation never deletes the Complete setup journal");
            PurpleSyncChatCache.Data committed=PurpleSyncChatCache.load(p.cache,p.origin,p.authority).data;
            PurpleModernSyncCases.check(committed.accepted("config")&&committed.accepted("library")&&committed.newReaders
                    &&committed.journal.length()==0&&baseline.equals(committed.readers.json().toString()),"manual explicit consent unions discovery receipts and preserves original baseline/journal/sticky warning");
            runner.close();PurpleModernSyncCases.check(!PurpleSyncOperations.busy(p.key()),"completed manual flow releases account capability");p.files.unchangedA();
        }
        controls(root);initializer(root);
        System.out.println("MANUAL_TYPED_FIRST_POST_CHECKS="+(checks-before));
    }
}
