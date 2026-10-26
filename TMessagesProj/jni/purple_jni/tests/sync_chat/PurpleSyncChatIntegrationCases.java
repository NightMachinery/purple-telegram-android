package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

public final class PurpleSyncChatIntegrationCases {
    static final class Gated implements PurpleSyncRunner.Env {
        final PurpleModernSyncCases.Env env;
        boolean allow=true,defer;int gates,cancels;
        Consumer<Boolean> deferred;
        Gated(PurpleModernSyncCases.Env env){this.env=env;}
        public void resolveChat(boolean post,Object owner,String key,BooleanSupplier current,Consumer<Boolean> done){++gates;check(PurpleSyncOperations.owned(key,owner),"manual gate borrows exact existing runner lock");if(defer)deferred=done;else done.accept(allow);}
        public void cancelChat(Object owner){++cancels;}
        public boolean current(int account,long user){return env.current(account,user);}public String operationKey(int account,long user){return env.operationKey(account,user);}public String device(){return env.device();}
        public long serverTimeMillis(int account){return env.serverTimeMillis(account);}public boolean hasState(){return env.hasState();}public PurpleAccountSyncStore store(){return env.store();}public PurpleSyncHistory history(){return env.history();}
        public PurpleSyncSettingsFile.Contents readSettings(){return env.readSettings();}public PurpleSyncSettingsFile.WriteResult replaceSettings(PurpleSyncSettingsFile.Contents expected,byte[] bytes,String reason,boolean fromImport){return env.replaceSettings(expected,bytes,reason,fromImport);}
    }
    public static int run(File root)throws Exception {
        int before=checks;Frame frame=new Frame(root,"chat-gates",true);frame.initialize(1);Gated gate=new Gated(frame.env(1));
        PurpleSyncRunner runner=new PurpleSyncRunner(1,202,frame.clouds[1],gate,Runnable::run,Runnable::run);
        gate.allow=false;frame.clouds[1].holdCheck=true;PurpleSyncRunner.CheckOutcome[] checked={null};
        check(runner.check(null,result->checked[0]=result)==PurpleSyncRunner.Start.Started,"manual held check admitted under lock");
        check(checked[0]!=null&&checked[0].status==PurpleSyncRunner.CheckStatus.SyncChatHeld&&frame.clouds[1].checking==null,"unresolved manual check never invokes self-only transport");
        check(!PurpleSyncOperations.busy(gate.operationKey(1,202)),"held manual check releases lock");
        gate.allow=true;frame.clouds[1].holdCheck=false;PurpleSyncRunner.PostTicket ticket=PurpleSyncLifecycleCases.ticket(runner);gate.allow=false;
        PurpleSyncPublisher.Result[] published={null};check(runner.publish(ticket,result->published[0]=result)==PurpleSyncRunner.Start.Started,"manual publisher waits for fresh chat gate");
        check(published[0]!=null&&published[0].status==PurpleSyncCore.PublishStatus.NeedsReview&&frame.clouds[1].posts==0,"held before-post never publishes");
        check(!new File(frame.sync(1),"pending/config.json").exists(),"held before-post never stages a new record");
        gate.defer=true;checked[0]=null;check(runner.check(null,result->checked[0]=result)==PurpleSyncRunner.Start.Started,"deferred chat gate");check(runner.cancel(),"cancel during gate");gate.deferred.accept(true);
        check(checked[0]==null&&frame.clouds[1].checking==null&&!PurpleSyncOperations.busy(gate.operationKey(1,202)),"late gate callback after cancel cannot read or retain lock");
        check(gate.cancels>0,"runner ends cancel its owned discovery operation");frame.unchangedA();runner.close();return checks-before;
    }
    public static void main(String[] args)throws Exception {
        PurpleCore.ensureLoaded();File overlay=new File(args[0]).getCanonicalFile();
        for(String name:new String[]{"PurpleSyncRunner","PurpleSyncOperations","PurpleSyncAuto","PurpleAccountFiles","PurpleSyncTelegramClient","PurpleSyncChat"})if(!new File(Class.forName("org.telegram.messenger.purple."+name).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(overlay))throw new AssertionError("production source overlay "+name);
        File root=Files.createTempDirectory(new File(args[1]).toPath(),"integration-").toFile();
        System.out.println("MODERN_AFFECTED_CHECKS="+PurpleModernSyncCases.run(root));System.out.println("RUNNER_GATE_CHECKS="+run(root));
    }
}
