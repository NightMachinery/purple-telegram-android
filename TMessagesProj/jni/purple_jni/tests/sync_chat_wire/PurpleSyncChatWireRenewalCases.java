package org.telegram.messenger.purple;

import java.nio.file.Files;
import java.io.File;
import java.util.*;
import java.util.function.*;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

final class PurpleSyncChatWireRenewalCases {
    static int checks;
    static void must(boolean value,String reason){++checks;if(!value)throw new AssertionError(reason);}
    static final class Net extends PurpleSyncChatCarryCases.Net {
        Probe probe;
        Net(PurpleSyncChatMoveExecutorCases.Frame frame)throws Exception {
            super(frame);
            if(frame.mode.equals("Fill"))cache=null;
            if(frame.mode.equals("RepairMark"))oldMarkPresent=false;
            if(cache!=null)PurpleModernSyncCases.write(f.files.cache,PurpleSyncChatCache.encode(ORIGIN,AUTH,cache));
        }
        @Override public PurpleSyncChatService.Binding binding(){
            try{
                File state=new File(f.files.root,"state.json");
                if(!state.isFile())return new PurpleSyncChatService.Binding(true,false,Peer.self(),"binding","missing");
                byte[] bytes=Files.readAllBytes(state.toPath());
                Peer chat=Peer.parse(new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getString("chat"),777);
                return new PurpleSyncChatService.Binding(chat!=null,true,chat,"binding",hash(bytes));
            }catch(Exception error){throw new RuntimeException(error);}
        }
        @Override public PurpleSyncChatCache.Loaded cache(){return PurpleSyncChatCache.load(PurpleSyncChatCache.file(f.files.cache),ORIGIN,AUTH);}
        @Override public int send(TLObject request,PurpleSyncChatTelegram.Reply done){
            if(request instanceof TLRPC.TL_messages_forwardMessages||request instanceof TLRPC.TL_messages_sendMedia||request instanceof TLRPC.TL_messages_sendMessage)
                must(probe!=null&&probe.renewals>0,"real owning Move renewed before the first effect send");
            if(request instanceof TLRPC.TL_messages_deleteMessages||request instanceof TLRPC.TL_channels_deleteMessages)
                must(probe!=null&&probe.cleanupReads>0&&probe.cleanupRenewals>0,"real owning Move read and renewed before cleanup delete");
            return super.send(request,done);
        }
    }
    static final class Probe implements PurpleSyncChatMove.Port {
        final PurpleSyncChatCarryCases.Run run;
        final PurpleSyncChatMove.Port delegate;
        int renewals,cleanupReads,cleanupRenewals;
        boolean refuse;
        Probe(PurpleSyncChatCarryCases.Run run,Net net){this.run=run;delegate=run.port();net.probe=this;}
        public PurpleSyncChatOrigin origin(){return delegate.origin();}public String authority(){return delegate.authority();}
        public String operationKey(){return delegate.operationKey();}public boolean current(){return delegate.current();}public boolean foreground(){return delegate.foreground();}
        public void worker(Runnable task){
            for(StackTraceElement call:Thread.currentThread().getStackTrace())if(call.getClassName().equals(PurpleSyncChatMove.class.getName())){
                if(call.getMethodName().equals("renew")){++renewals;if(refuse)run.f.foreground=false;}
                if(call.getMethodName().equals("beginCleanupRead"))++cleanupReads;
                if(call.getMethodName().equals("renewCleanup"))++cleanupRenewals;
            }
            delegate.worker(task);
        }
        public void quiesce(Runnable done){delegate.quiesce(done);}
        public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception{return delegate.open(current,interrupted);}
        public void dispatch(PurpleSyncChatMove.Command command,Consumer<PurpleSyncChatMove.Reply> done){delegate.dispatch(command,done);}
        public void cancel(PurpleSyncChatMove.Command command){delegate.cancel(command);}
        public void status(PurpleSyncChatMove.Status state){delegate.status(state);}
        void sameOwner(){
            PurpleSyncChatMove first=run.commands.get(0).owner();
            must(first!=null&&run.commands.stream().allMatch(command->command.owner()==first),"controller wrappers retain the real owning engine on every Command");
        }
    }
    static PurpleSyncChatScreen.Frame frame(PurpleSyncChatCarryCases.Run run){
        PurpleSyncChatScreen.Frame[] value={null};run.carry.freshFrame("config",fresh->value[0]=fresh);run.net.drain();
        must(value[0]!=null,"real dispatcher discovery returns opening facts");return value[0];
    }
    static void release(Net net){net.holdWrites=false;List<Runnable> held=new ArrayList<>(net.held.values());net.held.clear();for(Runnable task:held)task.run();net.drain();}
    static boolean committed(PurpleSyncChatMoveExecutorCases.Frame frame)throws Exception{return frame.diskJournal().optBoolean("state_committed");}
    static void consent(String mode,boolean refuse)throws Exception {
        PurpleSyncChatMoveExecutorCases.Frame f=new PurpleSyncChatMoveExecutorCases.Frame(false,false,mode);
        Net net=new Net(f);PurpleSyncChatCarryCases.Run run=new PurpleSyncChatCarryCases.Run(f,net);Probe probe=new Probe(run,net);probe.refuse=refuse;
        PurpleSyncChatChangeCases.Screen screen=new PurpleSyncChatChangeCases.Screen();screen.frame=frame(run);screen.target=net.infoOf(f.target);
        must(screen.frame.status.resolution.action.name().equals(mode),"real discovery offers "+mode);
        PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(screen,true);
        PurpleSyncChatMoveConsent.Offer[] offer={null};control.offerFollowFill("config",value->offer[0]=value);
        PurpleSyncChatMoveConsent.Selection[] selection={null};control.acceptFollowFill(offer[0],true,value->selection[0]=value);
        must(selection[0]!=null,"real foreground "+mode+" selection accepted");
        List<PurpleSyncChatMoveConsent.State> states=new ArrayList<>();
        PurpleSyncChatMoveConsent flow=new PurpleSyncChatMoveConsent(new PurpleSyncChatMoveConsent.Port(){
            public PurpleSyncChatOrigin origin(){return ORIGIN;}public String authority(){return AUTH;}
            public boolean current(){return f.live;}public boolean foreground(){return f.foreground;}
            public void worker(Runnable task){f.worker(task);}
            public void resolve(String stream,Consumer<PurpleSyncChatScreen.Frame> done){run.carry.freshFrame(stream,done);}
            public void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done){f.worker(()->done.accept(net.infoOf(target)));}
            public JSONObject begin(PurpleSyncChatMoveConsent.Selection selected)throws Exception{return PurpleSyncChatFlowPort.beginning(f.files.binding);}
            public PurpleSyncChatMove.Port carry(){return probe;}
            public void cancel(){run.carry.close();}public void status(PurpleSyncChatMoveConsent.State value){states.add(value);}
        },selection[0]);
        net.holdWrites=true;flow.start();net.drain();
        must(probe.renewals>0,mode+" enters the owning Move renewal before its first transport effect");
        if(refuse){
            must(net.count(TLRPC.TL_messages_forwardMessages.class)==0&&!committed(f),mode+" cannot send or commit when the owning engine loses admission during initial renewal");
        }else{
            must(net.count(TLRPC.TL_messages_forwardMessages.class)==1&&!committed(f),mode+" first effect reached real Telegram transport only after renewal, before any direct reply");
            release(net);
            must(states.stream().anyMatch(value->value.phase.equals("Complete")&&value.committed),mode+" completes through the real dispatcher "+states.stream().map(value->value.reason).collect(java.util.stream.Collectors.toList()));
            must(f.diskJournal().getInt("mode")== (mode.equals("Follow")?1:2),"accepted mode is preserved through real dispatch");
            must(net.count(TLRPC.TL_messages_sendMessage.class)==0,mode+" reuses the existing target mark");probe.sameOwner();
        }
        flow.close();control.close();run.close();
    }
    static void recovery(boolean refuse)throws Exception {
        PurpleSyncChatMoveExecutorCases.Frame f=new PurpleSyncChatMoveExecutorCases.Frame(false,false,"RepairMark");
        byte[] before=Files.readAllBytes(new File(f.files.root,"state.json").toPath());
        Net net=new Net(f);PurpleSyncChatCarryCases.Run run=new PurpleSyncChatCarryCases.Run(f,net);Probe probe=new Probe(run,net);probe.refuse=refuse;
        PurpleSyncChatScreen.Frame opening=frame(run);
        must(opening.oldRead==DirectRead.MarkGone&&PurpleSyncChatMoveRecovery.actions(opening,true).contains(PurpleSyncChatMoveRecovery.ActionChoice.RepairMark),"real discovery proves recovery mark missing");
        List<PurpleSyncChatChange.State> states=new ArrayList<>();
        PurpleSyncChatMoveRecovery flow=new PurpleSyncChatMoveRecovery(new PurpleSyncChatMoveRecovery.Port(){
            public PurpleSyncChatOrigin origin(){return ORIGIN;}public String authority(){return AUTH;}
            public boolean current(){return f.live;}public boolean foreground(){return f.foreground;}
            public void worker(Runnable task){f.worker(task);}
            public void resolve(Consumer<PurpleSyncChatScreen.Frame> done){run.carry.freshFrame("config",done);}
            public void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done){f.worker(()->done.accept(net.infoOf(target)));}
            public PurpleSyncChatSetup.Env provision(){throw new AssertionError("repair does not provision");}
            public JSONObject begin(PurpleSyncChatScreen.ChangeSelection selected,PurpleSyncChatClient.Info info){throw new AssertionError("repair does not begin a Switch");}
            public JSONObject repair(PurpleSyncChatScreen.Frame value,PurpleSyncChatClient.Info info)throws Exception{return PurpleSyncChatFlowPort.beginning(f.files.binding);}
            public PurpleSyncChatMove.Port carry(){return probe;}
            public void decorate(String effect,PurpleSyncChatMove.Committed proof,Consumer<Boolean> done){throw new AssertionError("repair does not decorate");}
            public void cancel(){run.carry.close();}public void status(PurpleSyncChatChange.State value){states.add(value);}
        },opening,PurpleSyncChatMoveRecovery.ActionChoice.RepairMark,null);
        net.holdWrites=true;flow.start();net.drain();
        must(probe.renewals>0,"recovery replacement mark enters the owning Move initial renewal");
        if(refuse){
            must(net.count(TLRPC.TL_messages_sendMessage.class)==0&&!committed(f),"recovery cannot send or commit after loss of initial renewal admission");
        }else{
            must(net.count(TLRPC.TL_messages_sendMessage.class)==1&&!committed(f),"recovery replacement mark reaches real transport after initial renewal and before any reply");
            release(net);
            must(states.stream().anyMatch(value->value.phase.equals("Complete")),"recovery completes through real dispatcher "+states.stream().map(value->value.reason).collect(java.util.stream.Collectors.toList()));
            must(f.diskJournal().getInt("mode")==3&&Arrays.equals(before,Files.readAllBytes(new File(f.files.root,"state.json").toPath())),"replacement mark preserves recovery mode and exact settings state");probe.sameOwner();
        }
        flow.close();run.close();
    }
    static void ordinary(boolean own)throws Exception {
        PurpleSyncChatMoveExecutorCases.Frame f=new PurpleSyncChatMoveExecutorCases.Frame(own);f.removeOld=!own;
        Net net=new Net(f);PurpleSyncChatCarryCases.Run run=new PurpleSyncChatCarryCases.Run(f,net);Probe probe=new Probe(run,net);
        net.holdWrites=true;f.move=new PurpleSyncChatMove(probe,true);f.move.start(f.begin().put("cleanupSettings",!own),info(f.target,0,false));net.drain();
        must(probe.renewals>0&&net.count(own?TLRPC.TL_messages_sendMedia.class:TLRPC.TL_messages_forwardMessages.class)==1&&!committed(f),"ordinary "+(own?"own upload":"forward")+" renews before the first request without a direct reply");
        release(net);must(f.done(),"ordinary real transport completes "+f.reason()+" "+run.trace());probe.sameOwner();
        must(run.commands.get(0).owner()==f.move,"ordinary commands belong to the engine that started this move");
        if(!own)must(probe.cleanupReads>0&&probe.cleanupRenewals>0&&net.count(TLRPC.TL_messages_deleteMessages.class)>0&&!net.oldMarkPresent,"cleanup uses the owning engine read and renewal before deletion");
        run.close();
    }
    static void run()throws Exception {
        org.telegram.messenger.UserConfig.getInstance(0).user=777;
        for(String mode:new String[]{"Follow","Fill"})for(boolean refuse:new boolean[]{false,true})consent(mode,refuse);
        for(boolean refuse:new boolean[]{false,true})recovery(refuse);
        ordinary(false);ordinary(true);
        System.out.println("WIRE_RENEWAL_CHECKS="+checks);
    }
}
