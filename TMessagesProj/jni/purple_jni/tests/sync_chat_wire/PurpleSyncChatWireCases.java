package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

public final class PurpleSyncChatWireCases {
    static int checks;
    static void must(boolean ok,String reason){++checks;if(!ok)throw new AssertionError(reason);}
    static final class Factory implements PurpleSyncChatFlows.Factory {
        PurpleSyncChatChangeCases.Flow change;
        PurpleSyncChatMoveRecoveryCases.Flow recovery;
        PurpleSyncChatMoveConsentCases.Flow consent;
        int constructed;
        public PurpleSyncChatMoveRecovery.Port change(Consumer<PurpleSyncChatChange.State> changed){
            ++constructed;
            PurpleSyncChatChange.Port d=recovery!=null?recovery:change;
            return new PurpleSyncChatMoveRecovery.Port(){
                public PurpleSyncChatOrigin origin(){return d.origin();}public String authority(){return d.authority();}
                public boolean current(){return d.current();}public boolean foreground(){return d.foreground();}
                public void worker(Runnable r){d.worker(r);}public void resolve(Consumer<PurpleSyncChatScreen.Frame> done){d.resolve(done);}
                public void inspect(Peer p,Consumer<PurpleSyncChatClient.Info> done){d.inspect(p,done);}
                public PurpleSyncChatSetup.Env provision(){return d.provision();}
                public JSONObject begin(PurpleSyncChatScreen.ChangeSelection s,PurpleSyncChatClient.Info i)throws Exception{return PurpleSyncChatFlowPort.beginning(change!=null?change.carry.files.binding:recovery.f.carry.files.binding);}
                public JSONObject repair(PurpleSyncChatScreen.Frame f,PurpleSyncChatClient.Info i)throws Exception{return PurpleSyncChatFlowPort.beginning(recovery.f.carry.files.binding);}
                public PurpleSyncChatMove.Port carry(){return d.carry();}
                public void decorate(String e,PurpleSyncChatMove.Committed p,Consumer<Boolean> done){d.decorate(e,p,done);}
                public void cancel(){d.cancel();}public void status(PurpleSyncChatChange.State state){d.status(state);changed.accept(state);}
            };
        }
        public PurpleSyncChatMoveConsent.Port consent(Consumer<PurpleSyncChatMoveConsent.State> changed){
            ++constructed;PurpleSyncChatMoveConsent.Port d=consent;
            return new PurpleSyncChatMoveConsent.Port(){
                public PurpleSyncChatOrigin origin(){return d.origin();}public String authority(){return d.authority();}
                public boolean current(){return d.current();}public boolean foreground(){return d.foreground();}
                public void worker(Runnable r){d.worker(r);}public void resolve(String stream,Consumer<PurpleSyncChatScreen.Frame> done){d.resolve(stream,done);}
                public void inspect(Peer p,Consumer<PurpleSyncChatClient.Info> done){d.inspect(p,done);}
                public JSONObject begin(PurpleSyncChatMoveConsent.Selection s)throws Exception{return PurpleSyncChatFlowPort.beginning(consent.carry.files.binding);}
                public PurpleSyncChatMove.Port carry(){return d.carry();}public void cancel(){d.cancel();}
                public void status(PurpleSyncChatMoveConsent.State state){d.status(state);changed.accept(state);}
            };
        }
    }
    static void gates()throws Exception {
        must(!PurpleSyncChatChange.ACTIVATED,"Change remains OFF");must(!PurpleSyncChatMoveConsent.ACTIVATED,"consent remains OFF");
        must(!PurpleSyncChatMoveRecovery.ACTIVATED,"recovery remains OFF");must(!PurpleSyncChatMoveCleanup.ACTIVATED,"cleanup remains OFF");
        must(!PurpleSyncChatMarkActions.ACTIVATED,"mark removal remains OFF");
        Factory factory=new Factory();PurpleSyncChatFlows flows=new PurpleSyncChatFlows();List<String> reasons=new ArrayList<>();
        flows.change(false,false,factory,null,s->reasons.add(s.reason));flows.consent(false,false,factory,null,s->reasons.add(s.reason));
        flows.recover(false,false,factory,null,null,null,s->reasons.add(s.reason));
        int[] publicCalls={0};
        PurpleSyncChat.change(0,null,()->true,state->{must("QualificationHeld".equals(state.reason),"public Change OFF before account or selection");++publicCalls[0];});
        PurpleSyncChat.followFill(0,null,()->true,state->{must("QualificationHeld".equals(state.reason),"public consent OFF before account or selection");++publicCalls[0];});
        PurpleSyncChat.recover(0,null,null,null,()->true,state->{must("QualificationHeld".equals(state.reason),"public recovery OFF before account or selection");++publicCalls[0];});
        must(publicCalls[0]==3,"public OFF callbacks exactly once without account construction");
        must(factory.constructed==0&&reasons.size()==3&&reasons.stream().allMatch("QualificationHeld"::equals),"OFF never constructs controller ports");flows.close();
        PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();
        PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(f.screen);PurpleSyncChatScreen.ChangeOffer[] offer={null};
        control.offerChange(f.screen.target.chat,false,o->offer[0]=o);
        must(offer[0]!=null&&!offer[0].cleanupAvailable&&!offer[0].cleanupDefault&&!offer[0].oldMarkAvailable,"OFF cleanup is hidden and unticked");
        int[] accepted={0};control.acceptChange(offer[0],true,true,true,true,s->++accepted[0]);must(accepted[0]==0,"OFF cannot accept synthetic cleanup consent");
        control.close();f.close();
    }
    static void change()throws Exception {
        for(boolean create:new boolean[]{false,true}){
            Factory factory=new Factory();factory.change=new PurpleSyncChatChangeCases.Flow();PurpleSyncChatChangeCases.Flow f=factory.change;
            PurpleSyncChatFlows flows=new PurpleSyncChatFlows();List<PurpleSyncChatChange.State> states=new ArrayList<>();
            PurpleSyncChatFlows.Handle handle=flows.change(true,true,factory,PurpleSyncChatChangeCases.selection(f.screen,create,false),states::add);f.drain();
            must(handle!=null&&factory.constructed==1&&f.carries==1,"ON constructs real Change and carry");
            must(states.stream().anyMatch(s->s.phase.equals("Complete")&&s.committed),"ON Change reaches real durable commit "+create);
            must(f.provision.creates==(create?1:0)&&f.decorations==(create?3:0),"provision/decorate only after new chat commit");
            flows.close();f.drain();must(f.cancels==1&&!PurpleSyncOperations.carryHeld(f.carry.operationKey()),"owner close releases Change");f.close();
        }
        Factory factory=new Factory();factory.change=new PurpleSyncChatChangeCases.Flow();PurpleSyncChatChangeCases.Flow f=factory.change;
        PurpleSyncChatFlows flows=new PurpleSyncChatFlows();List<PurpleSyncChatChange.State> states=new ArrayList<>();
        flows.change(true,false,factory,PurpleSyncChatChangeCases.selection(f.screen,false,true),states::add);f.drain();
        PurpleSyncChatChange.State last=states.get(states.size()-1);
        must(last.committed&&last.reason.equals("CleanupDisabled")&&last.cleanup.pending&&last.cleanup.canAct,"cleanup OFF holds committed move with actionable progress");
        must(f.carry.commands.stream().noneMatch(c->c.action.startsWith("Cleanup")),"cleanup OFF dispatches no read or delete");
        last.cleanup.skip(true);f.drain();must(states.stream().anyMatch(s->s.phase.equals("Complete")),"explicit Skip completes without deletion");
        flows.close();f.drain();f.close();
    }
    static void consent()throws Exception {
        for(String mode:new String[]{"Follow","Fill"}){
            Factory factory=new Factory();factory.consent=new PurpleSyncChatMoveConsentCases.Flow(mode);PurpleSyncChatMoveConsentCases.Flow f=factory.consent;
            PurpleSyncChatFlows flows=new PurpleSyncChatFlows();List<PurpleSyncChatMoveConsent.State> states=new ArrayList<>();
            flows.consent(true,true,factory,f.accept(f.offer("config"),true),states::add);f.drain();
            must(factory.constructed==1&&f.executors==1&&states.stream().anyMatch(s->s.committed),"ON real "+mode+" controller and durable carry");
            flows.close();f.drain();must(f.cancels==1&&!PurpleSyncOperations.carryHeld(f.carry.operationKey()),"consent owner close");f.close();
        }
    }
    static void followCleanupOff()throws Exception {
        Factory factory=new Factory();factory.consent=new PurpleSyncChatMoveConsentCases.Flow("Follow");PurpleSyncChatMoveConsentCases.Flow f=factory.consent;
        PurpleSyncChatFlows flows=new PurpleSyncChatFlows();List<PurpleSyncChatMoveConsent.State> states=new ArrayList<>();
        flows.consent(true,false,factory,f.accept(f.offer("config"),true),states::add);f.drain();
        PurpleSyncChatMoveConsent.State held=states.get(states.size()-1);
        must(held.committed&&held.reason.equals("CleanupDisabled")&&held.cleanup.pending,"Follow implicit cleanup obeys independent OFF switch");
        must(f.carry.commands.stream().noneMatch(c->c.action.startsWith("Cleanup")),"OFF Follow sends no cleanup command");
        held.cleanup.skip(true);f.drain();must(states.stream().anyMatch(s->s.phase.equals("Complete")),"Follow cleanup can be explicitly skipped");
        flows.close();f.drain();f.close();
    }
    static void recovery()throws Exception {
        for(PurpleSyncChatMoveRecovery.ActionChoice choice:PurpleSyncChatMoveRecovery.ActionChoice.values()){
            Factory factory=new Factory();factory.recovery=new PurpleSyncChatMoveRecoveryCases.Flow(choice==PurpleSyncChatMoveRecovery.ActionChoice.RepairMark,DirectRead.MarkGone);
            PurpleSyncChatMoveRecoveryCases.Flow f=factory.recovery;PurpleSyncChatFlows flows=new PurpleSyncChatFlows();List<PurpleSyncChatChange.State> states=new ArrayList<>();
            PurpleSyncChatScreen.ChangeSelection selection=choice==PurpleSyncChatMoveRecovery.ActionChoice.RepairMark?null:PurpleSyncChatChangeCases.selection(f.f.screen,choice==PurpleSyncChatMoveRecovery.ActionChoice.Create,false);
            flows.recover(true,true,factory,f.opening,choice,selection,states::add);f.f.drain();
            must(states.stream().anyMatch(s->s.phase.equals("Complete")&&s.committed),"ON real recovery "+choice+" "+(states.isEmpty()?"empty":states.get(states.size()-1).reason));
            flows.close();f.f.drain();f.close();
        }
    }
    static void cancellation()throws Exception {
        for(String why:new String[]{"close","logout","foreground","observer"}){
            Factory factory=new Factory();factory.change=new PurpleSyncChatChangeCases.Flow();PurpleSyncChatChangeCases.Flow f=factory.change;
            f.carry.stop="Source";PurpleSyncChatFlows flows=new PurpleSyncChatFlows();
            PurpleSyncChatFlows.Handle h=flows.change(true,true,factory,PurpleSyncChatChangeCases.selection(f.screen,false,false),s->{if(why.equals("observer")&&s.phase.equals("Checking"))flows.close();});f.drain();
            if(why.equals("logout"))f.live=false;if(why.equals("foreground"))f.foreground=false;
            flows.close();f.drain();int before=f.carry.effects;h.retry();f.drain();
            must(f.cancels==1&&f.carry.effects==before&&!PurpleSyncOperations.carryHeld(f.carry.operationKey()),"late retry fenced on "+why);f.close();
        }
    }
    static final class StartupPort implements PurpleSyncChatStartup.Port {
        boolean enabled=true,live=true,fail;Peer pending;int ordinary,inspects,carries,cancels;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();PurpleSyncChatMoveExecutorCases.Frame frame;
        public boolean enabled(){return enabled;}public boolean current(){return live;}public void worker(Runnable task){tasks.add(task);}
        public Peer pending(){if(fail)throw new IllegalStateException("MissingJournal");return pending;}
        public void inspect(Peer p,Consumer<PurpleSyncChatClient.Info> done){++inspects;tasks.add(()->done.accept(frame.targetInfo));}
        public PurpleSyncChatMove.Port carry(Consumer<PurpleSyncChatMove.Status> changed){
            ++carries;PurpleSyncChatMove.Port d=frame;
            return new PurpleSyncChatMove.Port(){
                public PurpleSyncChatOrigin origin(){return d.origin();}public String authority(){return d.authority();}public String operationKey(){return d.operationKey();}
                public boolean current(){return live&&d.current();}public boolean foreground(){return true;}public void worker(Runnable r){d.worker(r);}
                public void quiesce(Runnable r){d.quiesce(r);}public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception{return d.open(current,interrupted);}
                public void dispatch(PurpleSyncChatMove.Command c,Consumer<PurpleSyncChatMove.Reply> done){d.dispatch(c,done);}public void cancel(PurpleSyncChatMove.Command c){d.cancel(c);}
                public void status(PurpleSyncChatMove.Status s){changed.accept(s);}
            };
        }
        public void ordinary(){++ordinary;}public void cancel(){++cancels;}
        void drain(){int cap=10000;while(!tasks.isEmpty()||frame!=null&&!frame.tasks.isEmpty()){must(--cap>0,"startup bounded");if(!tasks.isEmpty())tasks.remove().run();else frame.tasks.remove().run();}}
    }
    static void startup()throws Exception {
        for(String path:new String[]{"off","none","missing","logout"}){
            StartupPort p=new StartupPort();p.enabled=!path.equals("off");p.fail=path.equals("missing");p.live=!path.equals("logout");
            PurpleSyncChatStartup startup=new PurpleSyncChatStartup(p,new PurpleSyncChatFlows(),false);startup.start();p.drain();
            must(p.ordinary==(path.equals("off")||path.equals("none")?1:0)&&p.carries==0&&p.inspects==0,"startup "+path+" never creates a move");
            startup.close();p.drain();
        }
        PurpleSyncChatMoveExecutorCases.Frame original=new PurpleSyncChatMoveExecutorCases.Frame(false,false);original.stop="Destination";original.start();
        must(original.deferred!=null,"fixture stopped with durable pending move");original.close();
        StartupPort p=new StartupPort();p.frame=new PurpleSyncChatMoveExecutorCases.Frame(original);p.frame.targetInfo=info(p.frame.target,0,false);p.pending=p.frame.target;
        PurpleSyncChatStartup startup=new PurpleSyncChatStartup(p,new PurpleSyncChatFlows(),true);startup.start();
        must(p.ordinary==0,"ordinary blocked before pending check");p.drain();
        must(p.carries==1&&p.inspects==1&&p.ordinary==1,"resume finishes durable journal before ordinary sync");
        must(!PurpleSyncOperations.carryHeld(p.frame.operationKey()),"startup resume releases lease");startup.close();p.drain();p.frame.close();
    }
    static void productionBoundaries()throws Exception {
        for(String state:new String[]{"pending","complete","changed"}){
            PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();if(state.equals("pending"))f.carry.stop="Destination";
            f.start(true,false);
            PurpleSyncChatSetup.JournalIO disk=PurpleSyncChatSetup.provisionFile(f.provision.file);
            byte[] prior=disk.read();PurpleSyncChatSetup.JournalIO reusable=PurpleSyncChatSetupEnvironment.moveProvisionJournal(disk,ORIGIN,AUTH,f.carry.files.root);
            byte[] opening=reusable.read();
            if(state.equals("pending"))must(Arrays.equals(prior,opening),"unfinished move retains exact provision child");
            else {
                must(opening==null,"only proven matching terminal move permits next creation");
                byte[] next=prior.clone();next[next.length-1]=' ';
                if(state.equals("changed"))must(disk.save(prior,next,()->true),"change child after retirement read");
                must(reusable.save(null,prior,()->true)==!state.equals("changed"),"child retirement uses exact old bytes CAS");
            }
            f.close();
        }
        for(String effect:new String[]{"Pin","Mute","Archive"})for(boolean allow:new boolean[]{false,true}){
            PurpleSyncChatRequestCases.Native platform=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram telegram=new PurpleSyncChatTelegram(ORIGIN,platform);
            Cache mark=new Cache(Peer.of(PeerKind.Channel,20),90,2000);int[] admitted={0},finished={0};
            telegram.decorate(effect,mark,(request,grant)->{++admitted[0];grant.accept(allow);},(reply,error)->++finished[0]);
            must(admitted[0]==1&&platform.sent==(allow?1:0),"production decoration admission "+effect+" "+allow);
            if(allow){
                org.telegram.tgnet.TLObject request=platform.requests.get(0);
                must(effect.equals("Pin")?request instanceof org.telegram.tgnet.TLRPC.TL_messages_updatePinnedMessage
                        :effect.equals("Mute")?request instanceof org.telegram.tgnet.tl.TL_account.updateNotifySettings
                        :request instanceof org.telegram.tgnet.TLRPC.TL_folders_editPeerFolders,"decoration uses real TL builder "+effect);
                platform.reply(1,new org.telegram.tgnet.TLRPC.TL_boolTrue());
            }
            must(finished[0]==1,"decoration completes once "+effect);telegram.cancel();
        }
    }
    static void ownerThreads()throws Exception {
        PurpleSyncChatFlows flows=new PurpleSyncChatFlows();
        java.lang.reflect.Method own=PurpleSyncChatFlows.class.getDeclaredMethod("own",AutoCloseable.class,Runnable.class,Object.class);own.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch go=new java.util.concurrent.CountDownLatch(1);
        Runnable run=()->{try{go.await();for(int i=0;i<200;++i){Object token=new Object();own.invoke(flows,(AutoCloseable)()->{if(Thread.holdsLock(flows))failure.set(new AssertionError("callback under flow monitor"));flows.cancel(token);},(Runnable)()->{},token);}}catch(Throwable error){failure.set(error);}};
        Thread a=new Thread(run),b=new Thread(run);a.setDaemon(true);b.setDaemon(true);a.start();b.start();go.countDown();a.join(5000);b.join(5000);
        must(!a.isAlive()&&!b.isAlive()&&failure.get()==null,"concurrent replacement/close and reentrant cleanup complete");flows.close();
        must(java.lang.management.ManagementFactory.getThreadMXBean().findDeadlockedThreads()==null,"no owner lock nesting deadlock");
    }
    static void startupSubscriptions()throws Exception {
        StartupPort p=new StartupPort();p.fail=true;PurpleSyncChatStartup startup=new PurpleSyncChatStartup(p,new PurpleSyncChatFlows(),false);
        int[] first={0},second={0};Runnable old=startup.observe(()->true,s->++first[0]);p.drain();
        Runnable next=startup.observe(()->true,s->++second[0]);old.run();
        android.os.Looper.mainThread=Thread.currentThread();
        try{while(!org.telegram.messenger.AndroidUtilities.pending.isEmpty())org.telegram.messenger.AndroidUtilities.pending.remove(0).run();}
        finally{android.os.Looper.mainThread=null;}
        must(first[0]==0&&second[0]>0,"stale screen subscription cannot detach replacement observer");
        int count=second[0];next.run();startup.retry();p.drain();
        android.os.Looper.mainThread=Thread.currentThread();
        try{while(!org.telegram.messenger.AndroidUtilities.pending.isEmpty())org.telegram.messenger.AndroidUtilities.pending.remove(0).run();}
        finally{android.os.Looper.mainThread=null;}
        must(second[0]==count,"detached screen gets no later startup progress");startup.close();p.drain();
    }
    static void independentResume()throws Exception {
        for(boolean atCleanup:new boolean[]{false,true}){
            PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();f.carry.stop=atCleanup?"Cleanup":"Source";f.start(false,true);
            byte[] journal=f.carry.opened.journalBytes();
            must(!PurpleSyncChatStartup.resumeEnabled(journal,ORIGIN,AUTH,false,false,false,false),"all OFF refuses persisted move resume");
            must(PurpleSyncChatStartup.resumeEnabled(journal,ORIGIN,AUTH,false,false,false,true)==atCleanup,"cleanup alone resumes only core Cleanup");
            must(PurpleSyncChatStartup.resumeEnabled(journal,ORIGIN,AUTH,false,false,true,false),"Recovery resumes the Switch journals it creates");f.close();
        }
    }
    static void closeFailureAndStaleHandle()throws Exception {
        PurpleSyncChatFlows flows=new PurpleSyncChatFlows();
        java.lang.reflect.Method own=PurpleSyncChatFlows.class.getDeclaredMethod("own",AutoCloseable.class,Runnable.class,Object.class);own.setAccessible(true);
        Object first=new Object(),second=new Object();int[] retried={0};
        PurpleSyncChatFlows.Handle a=(PurpleSyncChatFlows.Handle)own.invoke(flows,(AutoCloseable)()->{throw new java.io.IOException("cleanup");},(Runnable)()->{},first);
        a.close();java.lang.reflect.Field active=PurpleSyncChatFlows.class.getDeclaredField("active");active.setAccessible(true);
        must(active.get(flows)==null,"throwing close releases exact owner reference in finally");
        PurpleSyncChatFlows.Handle b=(PurpleSyncChatFlows.Handle)own.invoke(flows,(AutoCloseable)()->{},(Runnable)()->++retried[0],second);
        a.close();flows.cancel(first);b.retry();must(retried[0]==1&&active.get(flows)==b,"stale handle and old port cancellation preserve replacement");flows.close();
    }
    static void decorationRetry()throws Exception {
        PurpleSyncChatRequestCases.Native platform=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram telegram=new PurpleSyncChatTelegram(ORIGIN,platform);
        int[] admitted={0},finished={0};Cache mark=new Cache(Peer.of(PeerKind.Channel,20),90,2000);
        telegram.decorate("Pin",mark,(request,grant)->{++admitted[0];grant.accept(true);},(reply,error)->++finished[0]);
        org.telegram.tgnet.TLRPC.TL_error flood=new org.telegram.tgnet.TLRPC.TL_error();flood.code=420;flood.text="FLOOD_WAIT_1";
        platform.replies.get(1).accept(null,flood);must(finished[0]==0&&platform.timers.size()==1,"decoration flood stays transient in shared controller");
        Runnable retry=platform.timers.keySet().iterator().next();long delay=platform.timers.remove(retry);platform.clock+=delay;retry.run();
        must(admitted[0]==2&&platform.sent==2,"decoration rechecks admission after flood");platform.reply(2,new org.telegram.tgnet.TLRPC.TL_boolTrue());
        must(finished[0]==1&&platform.timers.isEmpty(),"decoration retry completes once and releases timer");telegram.cancel();
    }
    public static void main(String[] args)throws Exception {
        rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));
        for(JSONObject row:rows)if(row.getString("op").equals("begin")&&row.getJSONObject("expected").getString("status").equals("Valid")){beginning=row.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        PurpleModernSyncCases.directory(new File(args[1]));root=new PurpleModernSyncCases.Frame(new File(args[1]),"wire",true).root;
        gates();change();consent();recovery();cancellation();startup();productionBoundaries();ownerThreads();startupSubscriptions();independentResume();followCleanupOff();closeFailureAndStaleHandle();decorationRetry();PurpleSyncChatWireRenewalCases.run();System.out.println("SYNC_CHAT_WIRE_CHECKS="+checks);
    }
}
