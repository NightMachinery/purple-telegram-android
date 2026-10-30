package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

public final class PurpleSyncChatConsecutiveChangeCases {
    static int checks;
    static void must(boolean value,String reason){++checks;if(!value)throw new AssertionError(reason);}
    static final class Flow implements PurpleSyncChatChange.Port,AutoCloseable {
        final PurpleSyncChatMoveRaceCases.Install install;
        final PurpleSyncChatChangeCases.Screen screen=new PurpleSyncChatChangeCases.Screen();
        final PurpleSyncChatScreen.Control control;
        final List<PurpleSyncChatChange.State> states=new ArrayList<>();
        final List<PurpleSyncChatMove.Command> commands=new ArrayList<>();
        PurpleSyncChatChange change;
        boolean live=true;
        Flow(PurpleSyncChatMoveRaceCases.Install install)throws Exception {
            this.install=install;
            screen.frame=frame();screen.target=info(install.target,0,false);
            control=new PurpleSyncChatScreen.Control(screen);
        }
        PurpleSyncChatScreen.Frame frame()throws Exception {
            byte[] state=Files.readAllBytes(new File(install.files.root,"state.json").toPath());
            Peer bound=Peer.parse(json(state).getString("chat"),777);
            PurpleSyncChatCache.Loaded cache=PurpleSyncChatCache.load(PurpleSyncChatCache.file(install.files.cache),ORIGIN,AUTH);
            Resolution resolved=PurpleSyncChatCore.resolve(install.cloud.resolution(bound));
            return new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Use,resolved,"",true,false,0)),
                    new PurpleSyncChatService.Binding(true,true,bound,install.files.binding,hash(state)),cache,info(bound,0,false),Collections.emptyList());
        }
        public PurpleSyncChatOrigin origin(){return ORIGIN;}
        public String authority(){return AUTH;}
        public boolean current(){return live;}
        public boolean foreground(){return live;}
        public void worker(Runnable task){install.worker(task);}
        public void resolve(Consumer<PurpleSyncChatScreen.Frame> done){worker(()->{try{done.accept(frame());}catch(Exception e){throw new RuntimeException(e);}});}
        public void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done){worker(()->done.accept(info(target,0,false)));}
        public PurpleSyncChatSetup.Env provision(){throw new AssertionError("existing target must not provision");}
        public JSONObject begin(PurpleSyncChatScreen.ChangeSelection selection,PurpleSyncChatClient.Info target)throws Exception {
            return copy(beginning).put("preferenceToken",blob(install.files.binding.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .put("markText",PurpleSyncChatSetupRequests.MARK);
        }
        public PurpleSyncChatMove.Port carry(){
            PurpleSyncChatMove.Port delegate=install;
            return new PurpleSyncChatMove.Port(){
                public PurpleSyncChatOrigin origin(){return delegate.origin();}public String authority(){return delegate.authority();}
                public String operationKey(){return delegate.operationKey();}public boolean current(){return live;}public boolean foreground(){return live;}
                public void worker(Runnable task){delegate.worker(task);}public void quiesce(Runnable task){delegate.quiesce(task);}
                public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception{return delegate.open(current,interrupted);}
                public void dispatch(PurpleSyncChatMove.Command command,Consumer<PurpleSyncChatMove.Reply> done){commands.add(command);delegate.dispatch(command,done);}
                public void cancel(PurpleSyncChatMove.Command command){delegate.cancel(command);}
                public void status(PurpleSyncChatMove.Status status){delegate.status(status);}
            };
        }
        public void decorate(String effect,PurpleSyncChatMove.Committed proof,Consumer<Boolean> done){throw new AssertionError("existing target must not decorate");}
        public void cancel(){}
        public void status(PurpleSyncChatChange.State state){states.add(state);}
        void start()throws Exception {
            PurpleSyncChatScreen.ChangeOffer[] offer={null};PurpleSyncChatScreen.ChangeSelection[] selection={null};
            android.os.Looper.mainThread=Thread.currentThread();
            try {
                control.offerChange(install.target,false,value->offer[0]=value);
                must(offer[0]!=null&&offer[0].source.equals(install.source),"screen offers current durable source");
                control.acceptChange(offer[0],true,true,false,value->selection[0]=value);
                must(selection[0]!=null,"screen freshly accepts new target");
            } finally {android.os.Looper.mainThread=null;}
            change=new PurpleSyncChatChange(this,selection[0]);change.start();PurpleSyncChatMoveRaceCases.pump(install);
        }
        String reason(){return states.isEmpty()?"none":states.get(states.size()-1).reason;}
        void completed()throws Exception {
            must(states.stream().anyMatch(state->state.phase.equals("Complete")&&state.committed),"screen Change completes "+install.source.token()+" -> "+install.target.token()+": "+reason());
            must(states.stream().noneMatch(state->state.phase.equals("Held")),"no held screen state");
            must(install.journal().getBoolean("cache_completed")&&install.journal().getBoolean("state_committed"),"both durable commits complete");
            must(frame().binding.chat.equals(install.target)&&frame().opening.data.location.chat.equals(install.target),"reopened state and cache bind target");
            must(!PurpleSyncOperations.carryHeld(install.operationKey()),"terminal move releases account carry lease");
            for(PurpleSyncChatMove.Command command:commands)must(command.source.equals(install.source.token())&&command.target.equals(install.target.token()),"request adapter receives new move scope");
            must(commands.get(0).journal()==null,"first Fresh has no previous operation journal");
            PurpleSyncChatMoveRaceCases.ledger(install);
        }
        public void close(){if(change!=null)change.close();PurpleSyncChatMoveRaceCases.pump(install);control.close();live=false;install.close();PurpleSyncChatMoveRaceCases.pump(install);}
    }
    static void run()throws Exception {
        must(!PurpleSyncChatChange.ACTIVATED,"production activation remains OFF");
        PurpleSyncChatMoveRaceCases.random=new Random(271828);
        PurpleSyncChatMoveRaceCases.Cloud cloud=new PurpleSyncChatMoveRaceCases.Cloud();
        PurpleSyncChatMoveRaceCases.Install first=new PurpleSyncChatMoveRaceCases.Install(cloud,0,O,PurpleSyncChatMoveRaceCases.N1,"Switch");
        PurpleModernSyncCases.directory(new File(first.files.root,"pending"));
        org.telegram.messenger.UserConfig.getInstance(0).active=true;
        org.telegram.messenger.UserConfig.getInstance(0).user=777;
        org.telegram.messenger.MessagesController.getMainSettings(0).edit().putString("purple_sync_binding_777",first.files.binding).commit();
        PurpleSyncChatClient.Info old=info(O,0,false);
        PurpleSyncChatCache.Data initial=new PurpleSyncChatCache.Data(new Cache(O,1,1001),"initial",PurpleSyncChatCache.ReadersSnapshot.from(old),Collections.singleton("config"),new JSONObject(),false);
        PurpleModernSyncCases.write(first.files.cache,PurpleSyncChatCache.encode(ORIGIN,AUTH,initial));
        byte[] retained;
        try(Flow flow=new Flow(first)){flow.start();flow.completed();retained=Files.readAllBytes(new File(first.files.root,"carry.json").toPath());}
        PurpleSyncChatMoveRaceCases.Install restarted=new PurpleSyncChatMoveRaceCases.Install(first,PurpleSyncChatMoveRaceCases.N1,PurpleSyncChatMoveRaceCases.N2,"Switch");
        must(restarted.files==first.files,"normal restart reuses same install and literal files");
        try(PurpleAccountSyncStore startup=new PurpleAccountSyncStore(restarted.files.root,PurpleAccountSyncStore.NATIVE,restarted.source)){
            PurpleAccountSyncStore.Result opened=startup.open(true,0,restarted.files.device);
            must(opened.status==PurpleAccountSyncStore.Status.Ready,"ordinary startup admits completed carry: "+opened.status);
        }
        must(Arrays.equals(retained,Files.readAllBytes(new File(first.files.root,"carry.json").toPath())),"ordinary startup retains terminal wrapper");
        cloud.markDate=3000;
        try(Flow flow=new Flow(restarted)){
            must(flow.screen.frame.canChange()&&flow.screen.frame.binding.chat.equals(PurpleSyncChatMoveRaceCases.N1),"startup projects committed N1 into second screen");
            flow.start();flow.completed();
            File[] history=new File(first.files.root,"carry-history").listFiles();
            must(history!=null&&history.length==1&&Arrays.equals(retained,Files.readAllBytes(history[0].toPath())),"second move archives exact first wrapper");
            must(!json(retained).getJSONObject("journal").getString("operation").equals(restarted.journal().getString("operation")),"second move has distinct operation identity");
            must(cloud.effects.stream().filter(effect->effect.startsWith("mark:")).count()==2,"one mark per accepted move");
            must(cloud.find(O,10)!=null&&cloud.find(O,11)!=null,"ordinary and legacy messages survive both moves");
        }
        System.out.println("SYNC_CHAT_CONSECUTIVE_CHANGE_CHECKS="+checks);
    }
}
