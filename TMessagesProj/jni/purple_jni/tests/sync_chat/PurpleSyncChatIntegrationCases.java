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
    static void ledger(File root)throws Exception {
        Frame frame=new Frame(root,"typed-ledger",true);byte[] original=frame.initialize(1);
        PurpleSyncChatCore.Peer chat=PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel,44);
        org.json.JSONObject changed=new org.json.JSONObject(new String(original,java.nio.charset.StandardCharsets.UTF_8));
        changed.put("version",2).put("chat",chat.token());
        PurpleAccountSyncCore.Result inspected=PurpleAccountSyncCore.inspectState(changed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(inspected.isValid()&&inspected.state!=null,"real core v2 typed ledger canonical state");
        File state=new File(frame.sync(1),"state.json");Files.write(state.toPath(),inspected.state);
        try(PurpleAccountSyncStore ordinary=frame.env(1).store()) {
            check(ordinary.open(true,1,device(1)).status==PurpleAccountSyncStore.Status.AccountUnbound,"default Self store refuses nonself v2");
        }
        check(java.util.Arrays.equals(Files.readAllBytes(state.toPath()),inspected.state),"wrong scope leaves actual ledger bytes unchanged");
        try(PurpleAccountSyncStore typed=new PurpleAccountSyncStore(frame.sync(1),PurpleAccountSyncStore.NATIVE,chat)) {
            check(typed.open(true,1,device(1)).status==PurpleAccountSyncStore.Status.Ready,"explicit typed ledger opens matching v2 state");
            check(typed.commitConfigState(original).status==PurpleAccountSyncStore.Status.AccountUnbound,"typed commit cannot move chat back to Self");
            check(typed.confirmConfigReadBack(new byte[]{1},7).status==PurpleAccountSyncStore.Status.Unconfirmed,"unscoped readback cannot confirm typed ledger id");
            check(typed.confirmConfigReadBack(PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Chat,44),new byte[]{1},7).status==PurpleAccountSyncStore.Status.Unconfirmed,"same number other peer kind cannot confirm");
        }
        for(String artifact:new String[]{".bak",".new"})for(boolean primary:new boolean[]{false,true}) {
            File recovery=new File(frame.app,"scope-recovery-"+artifact+"-"+primary);check(recovery.mkdir(),"exclusive scope artifact fixture");
            File pending=new File(recovery,"pending");check(pending.mkdir(),"exclusive pending artifact fixture");
            if(primary)Files.write(new File(recovery,"state.json").toPath(),original);
            Files.write(new File(recovery,"state.json"+artifact).toPath(),inspected.state);
            Files.write(new File(pending,"config.json.bak").toPath(),bytes("unchanged stage backup"));
            Files.write(new File(recovery,"auto.json.bak").toPath(),bytes("unchanged checkpoint backup"));
            java.util.Map<File,byte[]> before=PurpleSyncRecoveryCases.files(recovery);
            try(PurpleAccountSyncStore ordinary=new PurpleAccountSyncStore(recovery,PurpleAccountSyncStore.NATIVE)) {
                check(ordinary.open(true,1,device(1)).status==PurpleAccountSyncStore.Status.AccountUnbound,"foreign typed atomic artifact refuses before recovery");
            }
            PurpleSyncRecoveryCases.retained(before);
            check(primary==new File(recovery,"state.json").exists(),"foreign backup cannot replace or create primary state");
        }
        File recovery=new File(frame.app,"matching-typed-backup");check(recovery.mkdir(),"matching typed recovery fixture");
        Files.write(new File(recovery,"state.json.bak").toPath(),inspected.state);
        try(PurpleAccountSyncStore typed=new PurpleAccountSyncStore(recovery,PurpleAccountSyncStore.NATIVE,chat)) {
            check(typed.open(true,1,device(1)).status==PurpleAccountSyncStore.Status.Ready,"matching typed backup recovery still works");
            check(java.util.Arrays.equals(typed.stateBytes(),inspected.state),"matching backup exact canonical bytes");
        }
        check(!PurpleSyncCore.checkCommit(inspected.state,inspected.state).isValid(),"raw Self commit refuses typed ledger");
        check(PurpleSyncCore.checkCommit(chat,inspected.state,inspected.state).isValid(),"scoped core commit control");
        PurpleSyncInventory inventory=new PurpleSyncInventory.Builder(202,chat,null).build(true);
        boolean refused=false;try { new PurpleSyncAutoCore.Inventory(inventory,"synthetic","1",1,new org.json.JSONArray()); }
        catch(IllegalArgumentException expected){refused=true;}
        check(refused,"typed inventory cannot enter Self-only automatic bridge");
        frame.unchangedA();
    }
    public static void main(String[] args)throws Exception {
        PurpleCore.ensureLoaded();File overlay=new File(args[0]).getCanonicalFile();
        for(String name:new String[]{"PurpleSyncRunner","PurpleSyncOperations","PurpleSyncAuto","PurpleAccountFiles","PurpleSyncTelegramClient","PurpleSyncChat","PurpleSyncChatIO","PurpleSyncChatUpload","PurpleSyncPost","PurpleSyncReader","PurpleSyncCheck","PurpleSyncInventory","PurpleSyncCore","PurpleAccountSyncStore","PurpleSyncPublisher","PurpleSyncAutoUpload","PurpleSyncAutoEngine","PurpleSyncAutoNetwork","PurpleSyncAutoClient","PurpleSyncTelegramTransport"})if(!new File(Class.forName("org.telegram.messenger.purple."+name).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(overlay))throw new AssertionError("production source overlay "+name);
        File root=Files.createTempDirectory(new File(args[1]).toPath(),"integration-").toFile();
        int ledgerBefore=checks;ledger(root);System.out.println("TYPED_LEDGER_CHECKS="+(checks-ledgerBefore));
        System.out.println("MODERN_AFFECTED_CHECKS="+PurpleModernSyncCases.run(root));System.out.println("RUNNER_GATE_CHECKS="+run(root));
        System.out.println("TYPED_UPLOAD_LIFECYCLE_CHECKS="+PurpleSyncChatUploadIntegrationCases.run(root));
        PurpleAccountSyncStoreTest.main(new String[0]);
        PurpleSyncCoreTest.main(new String[0]);
    }
}
