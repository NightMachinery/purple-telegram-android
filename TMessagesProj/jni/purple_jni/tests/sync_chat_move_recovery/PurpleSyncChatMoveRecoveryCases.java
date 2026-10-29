package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.json.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

public final class PurpleSyncChatMoveRecoveryCases {
    static int checks;
    static void must(boolean value,String reason){++checks;if(!value)throw new AssertionError(reason);}
    static PurpleSyncChatScreen.Frame frame(PurpleSyncChatScreen.Frame old,DirectRead read,PurpleSyncChatClient.Info source){
        ResolveInput r=new ResolveInput(777);r.settingsBound=true;r.settingsChat=O;r.cache=old.opening.data.location;
        r.cacheRead=read;r.cachedEligibility=source.eligibility();r.settingsHint=SettingsHint.Found;r.marks=Collections.emptyList();
        return new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Lost,resolve(r),"",true,true,0)),old.binding,old.opening,source,Collections.emptyList(),Collections.emptyList(),read);
    }
    static final class Flow implements PurpleSyncChatMoveRecovery.Port,AutoCloseable {
        final PurpleSyncChatChangeCases.Flow f;
        final List<PurpleSyncChatChange.State> states=new ArrayList<>();
        PurpleSyncChatMoveRecovery recovery;
        PurpleSyncChatScreen.Frame opening;
        DirectRead read;
        boolean deferResolve,unknownReaders,dirty;
        Consumer<PurpleSyncChatScreen.Frame> resolving;
        PurpleSyncChatClient.Info repairInfo;
        Flow(boolean repair,DirectRead read)throws Exception {
            f=new PurpleSyncChatChangeCases.Flow(repair?O:Peer.of(PeerKind.Channel,20));this.read=read;
            repairInfo=info(O,0,false);if(repair){f.screen.target=repairInfo;f.carry.targetInfo=repairInfo;}
            f.screen.frame=frame(f.screen.frame,read,repairInfo);opening=f.screen.frame;
            PurpleModernSyncCases.write(f.carry.files.cache,opening.opening.bytes);
        }
        public PurpleSyncChatOrigin origin(){return f.origin();}public String authority(){return f.authority();}
        public boolean current(){return f.current();}public boolean foreground(){return f.foreground();}public void worker(Runnable r){f.worker(r);}
        public void resolve(Consumer<PurpleSyncChatScreen.Frame> done){if(deferResolve)resolving=done;else f.resolve(done);}
        public void inspect(Peer chat,Consumer<PurpleSyncChatClient.Info> done){worker(()->done.accept(chat.equals(O)?repairInfo:f.screen.target));}
        public PurpleSyncChatSetup.Env provision(){return f.provision();}
        public JSONObject begin(PurpleSyncChatScreen.ChangeSelection s,PurpleSyncChatClient.Info info)throws Exception{JSONObject b=f.begin(s,info);if(dirty)b.put("ownVersion",obj("text",blob(bytes("unsynced desired text")),"key","wrong","parents",new JSONArray(),"lineage",new JSONArray(),"platform","android","app","test","at","1"));return b;}
        public JSONObject repair(PurpleSyncChatScreen.Frame frame,PurpleSyncChatClient.Info info)throws Exception{return f.carry.begin();}
        public void decorate(String effect,PurpleSyncChatMove.Committed proof,Consumer<Boolean> done){f.decorate(effect,proof,done);}
        public void cancel(){f.cancel();}public void status(PurpleSyncChatChange.State s){states.add(s);}
        public PurpleSyncChatMove.Port carry(){
            PurpleSyncChatMove.Port d=f.carry();
            return new PurpleSyncChatMove.Port(){
                public PurpleSyncChatOrigin origin(){return d.origin();}public String authority(){return d.authority();}
                public String operationKey(){return d.operationKey();}public boolean current(){return d.current();}public boolean foreground(){return d.foreground();}
                public void worker(Runnable r){d.worker(r);}public void quiesce(Runnable r){d.quiesce(r);}
                public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception{return d.open(current,interrupted);}
                public void dispatch(PurpleSyncChatMove.Command c,Consumer<PurpleSyncChatMove.Reply> done){
                    d.dispatch(c,r->{
                        PurpleSyncChatMoveIO.Fresh x=r.fresh;ResolveInput input=x.resolution;input.cacheRead=read;
                        if(!f.carry.ownWinner)input.marks=Collections.emptyList();
                        r.fresh=new PurpleSyncChatMoveIO.Fresh(x.origin,x.loginAuthority,x.source,x.sourcePeer,x.target,x.targetPeer,input,true,read);
                        done.accept(r);
                    });
                }
                public void cancel(PurpleSyncChatMove.Command c){d.cancel(c);}public void status(PurpleSyncChatMove.Status s){d.status(s);}
            };
        }
        void start(PurpleSyncChatMoveRecovery.ActionChoice choice)throws Exception {
            PurpleSyncChatScreen.ChangeSelection selection=choice==PurpleSyncChatMoveRecovery.ActionChoice.RepairMark?null:PurpleSyncChatChangeCases.selection(f.screen,choice==PurpleSyncChatMoveRecovery.ActionChoice.Create,false);
            recovery=new PurpleSyncChatMoveRecovery(this,opening,choice,selection);recovery.start();f.drain();
        }
        boolean done(){return states.stream().anyMatch(s->s.phase.equals("Complete"));}
        String reason(){return states.isEmpty()?"none":states.get(states.size()-1).reason;}
        @Override public void close(){if(recovery!=null)recovery.close();f.drain();f.carry.close();f.close();}
    }
    static void repair()throws Exception {
        try(Flow q=new Flow(true,DirectRead.MarkGone)){
            byte[] state=Files.readAllBytes(new File(q.f.carry.files.root,"state.json").toPath());q.start(PurpleSyncChatMoveRecovery.ActionChoice.RepairMark);
            must(q.done(),"real RepairMark completes "+q.reason());
            must(q.f.carry.effects==1&&q.f.provision.creates==0,"repair only posts mark, no records or channel");
            must(Arrays.equals(state,Files.readAllBytes(new File(q.f.carry.files.root,"state.json").toPath())),"repair state binding and ledgers unchanged byte for byte");
            must(q.f.carry.diskJournal().getInt("mode")==3&&q.f.carry.diskJournal().optJSONObject("plan")==null,"core RepairMark only, no O to O Switch");
            must(!PurpleSyncOperations.carryHeld(q.f.carry.operationKey()),"repair releases carry hold at core Done");
        }
        for(DirectRead read:new DirectRead[]{DirectRead.ChatGone,DirectRead.Valid,DirectRead.Unknown})try(Flow q=new Flow(true,read)){
            q.start(PurpleSyncChatMoveRecovery.ActionChoice.RepairMark);must(!q.done()&&q.f.carry.effects==0&&q.f.provision.creates==0,"repair refuses non MarkGone "+read+" "+q.reason());
        }
        for(String why:new String[]{"protected","unknown","roster","rights"})try(Flow q=new Flow(true,DirectRead.MarkGone)){
            PurpleSyncChatClient.Info i=info(O,0,why.equals("protected"));
            if(why.equals("unknown"))i=new PurpleSyncChatClient.Info(ORIGIN,O,i.peer,i.error,"unknown",null,false);
            if(why.equals("roster"))i=new PurpleSyncChatClient.Info(ORIGIN,O,i.peer,i.error,"count only",null,true);
            if(why.equals("rights"))i.peer.facts.canSendFiles=false;
            q.repairInfo=i;q.start(PurpleSyncChatMoveRecovery.ActionChoice.RepairMark);must(!q.done()&&q.f.carry.effects==0,"repair refuses "+why+" "+q.reason());
        }
        try(Flow q=new Flow(true,DirectRead.MarkGone)){
            q.repairInfo=info(O,1,false);q.f.carry.targetInfo=q.repairInfo;q.start(PurpleSyncChatMoveRecovery.ActionChoice.RepairMark);
            must(q.done(),"known reader addition warns and continues "+q.reason());
            must(q.states.stream().anyMatch(x->x.phase.equals("Warning")&&x.reason.equals("NewReaders")),"C6 warning is presented before repair");
            PurpleSyncChatCache.Loaded cache=PurpleSyncChatCache.load(PurpleSyncChatCache.file(q.f.carry.files.cache),ORIGIN,AUTH);
            must(cache.ok&&cache.data.newReaders&&cache.data.readers.otherMembers==0,"original consent baseline and sticky warning survive repair");
        }
    }
    static void control()throws Exception {
        try(Flow q=new Flow(true,DirectRead.MarkGone)){
            must(!PurpleSyncChatMoveRecovery.ACTIVATED&&PurpleSyncChatMoveRecovery.actions(q.opening).isEmpty(),"OFF has zero actionable recovery rows");
            must(PurpleSyncChatMoveRecovery.actions(q.opening,true).size()==3,"injected ON exposes three exact recovery actions");
            int[] calls={0};PurpleSyncChat.recover(0,q.opening,PurpleSyncChatMoveRecovery.ActionChoice.RepairMark,null,()->true,s->{must(s.reason.equals("QualificationHeld"),"public OFF guard");++calls[0];});must(calls[0]==1&&q.f.carry.effects==0,"OFF no effect or confirmation path");
        }
        for(String changed:new String[]{"login","winner","close","timeout"})try(Flow q=new Flow(true,DirectRead.MarkGone)){
            q.deferResolve=true;q.start(PurpleSyncChatMoveRecovery.ActionChoice.RepairMark);must(q.resolving!=null,"fresh resolve queued");
            if(changed.equals("login"))q.f.screen.authority="b".repeat(64);
            if(changed.equals("close"))q.recovery.close();
            if(changed.equals("winner"))q.f.screen.frame=PurpleSyncChatChangeCases.screen(q.f.carry,O);
            if(changed.equals("timeout")){PurpleSyncChatScreen.Frame f=q.opening;q.f.screen.frame=new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Searching,f.status.resolution,"timeout",false,true,30)),f.binding,f.opening,f.current.info,f.refused);}
            q.resolving.accept(q.f.screen.frame);q.f.drain();must(q.f.carry.effects==0&&q.f.provision.creates==0,"stale/uncertain recovery never provisions "+changed);
        }
        try(Flow q=new Flow(false,DirectRead.MarkGone)){
            q.start(PurpleSyncChatMoveRecovery.ActionChoice.Create);q.recovery.start();q.recovery.retry();q.f.drain();
            must(q.f.provision.creates==1,"repeated Create starts one provisioning child "+q.reason());
            must(q.done(),"Create uses Carry after provision "+q.reason());
        }
    }
    static void retainedAndPending()throws Exception {
        for(String access:new String[]{"Protected","ChatGone"})try(Flow q=new Flow(false,access.equals("ChatGone")?DirectRead.ChatGone:DirectRead.MarkGone)){
            q.f.carry.access=access;q.dirty=true;q.start(PurpleSyncChatMoveRecovery.ActionChoice.Choose);
            must(q.done(),"different-chat recovery uses verified own base for "+access+" "+q.reason());
            JSONObject plan=q.f.carry.diskJournal().getJSONObject("plan");String record=plan.getString("own_record");
            must(!record.isEmpty(),"protected/gone O never becomes an empty recovered stream");
            must(core("ownVersion",obj("record",blob(bytes(record)))).fields.getJSONObject("ownVersion").getString("text").equals(core("ownVersion",obj("record",blob(base))).fields.getJSONObject("ownVersion").getString("text")),"dirty desired text never substitutes for pinned base");
        }
        for(boolean mismatch:new boolean[]{false,true})try(Flow q=new Flow(false,DirectRead.ChatGone)){
            q.f.carry.access="ChatGone";q.f.carry.omitBase=true;
            if(mismatch){byte[] other=unblob(rows.get(28).getJSONObject("input").getJSONObject("oldInventory").getJSONArray("messages").getJSONObject(1).getString("bytes"));PurpleSyncRetainedFiles.pin(q.f.carry.files.root,other);}
            q.start(PurpleSyncChatMoveRecovery.ActionChoice.Choose);must(!q.done()&&q.f.carry.effects==0&&q.reason().equals("MissingLocalVersion"),"missing/mismatched exact base is visible hold "+mismatch+" "+q.reason());
        }
        PurpleSyncChatMoveExecutorCases.Frame f=new PurpleSyncChatMoveExecutorCases.Frame(true,true,"Switch");
        try{
            f.access="Protected";f.start();must(f.done(),"protected pending stage reconciles through core "+f.reason());
            must(f.commands.stream().noneMatch(c->c.permit!=null&&c.permit.kind.equals("pending")),"protected pending never posts to O");
            JSONObject j=f.diskJournal();must(!j.getString("deferred_record").isEmpty()&&!j.getString("target_pending_record").isEmpty(),"unsent protected stage retained and restaged for N");
        }finally{f.close();}
        try(Flow q=new Flow(true,DirectRead.MarkGone)){
            q.f.carry.omitBase=true;byte[] before=Files.readAllBytes(new File(q.f.carry.files.root,"state.json").toPath());q.start(PurpleSyncChatMoveRecovery.ActionChoice.RepairMark);
            must(q.done()&&Arrays.equals(before,Files.readAllBytes(new File(q.f.carry.files.root,"state.json").toPath())),"RepairMark needs no invented settings base and preserves existing state");
        }
    }
    static void serviceProjection(){
        Peer chat=PurpleSyncChatRequestCases.CHANNEL;
        for(DirectRead read:new DirectRead[]{DirectRead.MarkGone,DirectRead.ChatGone,DirectRead.Unknown}){
            PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();
            env.file.seed(PurpleSyncChatModelCases.cache(chat,7,50));
            env.binding=new PurpleSyncChatService.Binding(true,true,chat,"captured-binding","captured-state");
            env.world.reads.put(chat.token()+"/7",read);
            PurpleSyncChatService service=new PurpleSyncChatService(env);
            try{
                service.ensureResolved(true,"config",null,ignored->{});PurpleSyncChatScreen.Frame shown=service.screen();
                must(shown.oldRead==(shown.status.complete?read:DirectRead.Unknown),"screen uses complete R3 outcome "+read);
                if(read==DirectRead.MarkGone){
                    must(shown.current!=null&&shown.current.chat.equals(chat)&&shown.current.allowed,"Searching exposes cached source facts rather than null read target");
                    must(PurpleSyncChatMoveRecovery.repairHeld(shown).isEmpty(),"actual service MarkGone admits injected repair");
                    for(long delay:new long[]{30000,120000,600000})env.through(env.clock+delay);
                    shown=service.screen();must(shown.status.phase==PurpleSyncChat.Phase.Lost&&shown.oldRead==DirectRead.MarkGone,"Lost retains exact mark-loss proof");
                    must(PurpleSyncChatMoveRecovery.repairHeld(shown).isEmpty(),"Lost retains readable source eligibility");
                }else must(!PurpleSyncChatMoveRecovery.repairHeld(shown).isEmpty(),"gone or unknown source cannot repair "+read);
            }finally{service.close(false);}
        }
        PurpleSyncChatServiceCases.Env env=new PurpleSyncChatServiceCases.Env();
        env.file.seed(PurpleSyncChatModelCases.cache(chat,7,50));env.binding=new PurpleSyncChatService.Binding(true,true,chat,"captured-binding","captured-state");
        env.world.failSearch=true;PurpleSyncChatService service=new PurpleSyncChatService(env);
        try{
            service.ensureResolved(true,"config",null,ignored->{});PurpleSyncChatScreen.Frame shown=service.screen();
            must(!shown.status.complete&&shown.oldRead==DirectRead.Unknown,"failed full search cannot export an otherwise positive MarkGone");
            must(PurpleSyncChatMoveRecovery.actions(shown,true).isEmpty(),"incomplete full resolution exposes no recovery actions");
        }finally{service.close(false);}
    }
    static void retryAndClose()throws Exception {
        try(Flow q=new Flow(true,DirectRead.MarkGone)){
            q.deferResolve=true;q.start(PurpleSyncChatMoveRecovery.ActionChoice.RepairMark);
            PurpleSyncChatScreen.Frame f=q.opening;PurpleSyncChatScreen.Frame timeout=new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Searching,f.status.resolution,"timeout",false,true,30)),f.binding,f.opening,f.current.info,f.refused);
            q.resolving.accept(timeout);q.f.drain();must(q.reason().equals("Searching")&&q.f.carry.effects==0,"timeout remains Searching");
            q.deferResolve=false;q.recovery.retry();q.f.drain();must(q.done(),"explicit Retry resolves afresh after reconnect "+q.reason());
        }
        try(Flow q=new Flow(false,DirectRead.MarkGone)){
            q.f.provision.hold="create";q.start(PurpleSyncChatMoveRecovery.ActionChoice.Create);must(q.f.provision.pending!=null,"create callback captured");
            q.recovery.close();q.f.provision.pending.run();q.f.drain();must(q.f.carry.effects==0&&q.f.carries==0,"closed recovery fences child provisioning callbacks immediately");
        }
        for(int n=0;n<3;++n){ResolveInput r=new ResolveInput(777);r.settingsBound=true;r.settingsChat=O;r.cache=new Cache(O,1,1001);r.cacheRead=DirectRead.MarkGone;r.cachedEligibility=info(O,0,false).eligibility();r.settingsHint=SettingsHint.Found;r.searchRetriesCompleted=n;
            Resolution actual=resolve(r);must(actual.action==Action.Searching&&actual.retryAfterSeconds==new int[]{30,120,600}[n],"Searching retry cadence "+n);
        }
    }
    public static void main(String[] args)throws Exception {
        rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));for(JSONObject row:rows)if(row.getString("op").equals("begin")&&row.getJSONObject("expected").getString("status").equals("Valid")){beginning=row.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        File parent=new File(args[1]);PurpleModernSyncCases.directory(parent);root=new PurpleModernSyncCases.Frame(parent,"routing",true).root;
        repair();control();retainedAndPending();serviceProjection();retryAndClose();System.out.println("MOVE_RECOVERY_CHECKS="+checks);
    }
}
