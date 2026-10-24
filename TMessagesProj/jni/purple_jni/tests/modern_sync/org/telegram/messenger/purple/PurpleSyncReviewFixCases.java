package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

final class PurpleSyncReviewFixCases {
    static void logout(int slot) {
        UserConfig.getInstance(slot).active=false;UserConfig.getInstance(slot).user=0;
        PurpleAccountFiles.identityChanged(slot);
    }
    static void login(int slot,long user) {
        UserConfig.getInstance(slot).active=true;UserConfig.getInstance(slot).user=user;
        PurpleAccountFiles.identityChanged(slot);
    }
    static void preferences(File parent)throws Exception {
        Frame frame=new Frame(parent,"production-preference-scanner",true);frame.initialize(1);
        PurpleSyncRunner runner=frame.env(1).runner();check(fresh(runner).recoveryAction==PurpleSyncRunner.RecoveryAction.None,"baseline bound scanner");runner.close();
        Map<File,byte[]> a=PurpleSyncRecoveryCases.files(frame.sync(0));logout(0);
        check(PurpleAccountFiles.capture(0)==null,"production identity capture sees real signed-out platform slot");
        runner=frame.env(1).runner();check(fresh(runner).review.status==PurpleSyncCore.ReviewStatus.Ready,"A retained numeric preference does not block B");runner.close();
        write(new File(frame.sync(0),"unknown-retained.bin"),new byte[]{0,1,(byte)255});
        login(0,303);MessagesController.getMainSettings(0).edit().putString("purple_sync_binding_303","33333333333333333333333333333333").commit();
        check("0-303".equals(PurpleAccountFiles.capture(0).key),"production scanner observes slot reuse C");
        runner=frame.env(1).runner();check(fresh(runner).review.status==PurpleSyncCore.ReviewStatus.Ready,"retained A bytes remain inert after slot is reused for C");runner.close();
        check(TOKEN_A.equals(frame.token(0)),"old A preference retained in reused slot");PurpleSyncRecoveryCases.retained(a);
        MessagesController.getMainSettings(0).edit().putString("purple_sync_binding_101",TOKEN_B).commit();
        runner=frame.env(1).runner();PurpleSyncRunner.Check held=fresh(runner);
        check(held.recovery!=null&&held.recovery.plan.status==PurpleSyncRecoveryCore.Status.Ambiguous,"retained preference claiming B token still refuses");runner.close();
        MessagesController.getMainSettings(1).edit().remove("purple_sync_binding_202").commit();
        runner=frame.env(1).runner();held=fresh(runner);
        check(held.recovery.plan.status==PurpleSyncRecoveryCore.Status.Ambiguous&&frame.token(1)==null,"retained claim of B store token prevents automatic relink too");runner.close();
        MessagesController.getMainSettings(1).edit().putString("purple_sync_binding_202",TOKEN_B).commit();
        MessagesController.getMainSettings(0).edit().putString("purple_sync_binding_101",TOKEN_A).putString("purple_sync_binding_202",TOKEN_A).commit();
        runner=frame.env(1).runner();held=fresh(runner);check(held.recovery.plan.status==PurpleSyncRecoveryCore.Status.Ambiguous,"retained preference claiming B identity still refuses");runner.close();
        Frame storeClaim=new Frame(parent,"retained-store-claims-B",true);storeClaim.initialize(1);logout(0);write(new File(storeClaim.sync(0),"state.json"),storeClaim.state(1));
        runner=storeClaim.env(1).runner();held=fresh(runner);check(held.recovery.plan.status==PurpleSyncRecoveryCore.Status.Ambiguous,"inert A store with B token is genuine ambiguity");runner.close();
        write(new File(storeClaim.sync(0),"state.json.bak"),storeClaim.state(1));write(new File(storeClaim.sync(0),"state.json"),"unreadable retained A state");
        runner=storeClaim.env(1).runner();held=fresh(runner);check(held.recovery.plan.status==PurpleSyncRecoveryCore.Status.Ambiguous,"retained backup claiming B token is read-only ambiguity evidence");
        check(new File(storeClaim.sync(0),"state.json.bak").exists()&&Files.readString(new File(storeClaim.sync(0),"state.json").toPath()).equals("unreadable retained A state"),"B never repairs signed-out A sidecars");runner.close();
    }
    static byte[] paddedState(byte[] state) {
        byte[] padded=new byte[PurpleSyncRecoveryFiles.FILE_LIMIT];Arrays.fill(padded,(byte)' ');
        System.arraycopy(state,0,padded,0,state.length);
        check(PurpleAccountSyncCore.inspectState(padded).isValid(),"large retained state is native-valid");
        return padded;
    }
    static void inactiveBudgets(File parent)throws Exception {
        for(boolean exhausted:new boolean[]{false,true}) {
            Frame frame=new Frame(parent,"inactive-budget-"+exhausted,true);frame.initialize(1);byte[] b=frame.state(1);
            byte[] a=paddedState(frame.state(0));
            for(String name:new String[]{"state.json","state.json.new","state.json.bak"})write(new File(frame.sync(0),name),a);
            login(2,150);MessagesController.getMainSettings(2).edit().putString("purple_sync_binding_150","33333333333333333333333333333333").commit();
            PurpleAccountSyncCore.Result c=PurpleAccountSyncCore.initializeBoundLocalState(2,install(2),device(2),frame.newSpace());
            check(c.isValid(),"retained C initialization");File directory=new File(frame.root,"accounts/0-150/sync");byte[] cb=paddedState(c.state);
            write(new File(directory,"state.json"),cb);
            if(exhausted)for(String name:new String[]{"state.json.bak","state.json.new"})write(new File(directory,name),cb);
            logout(0);logout(2);frame.snapshotA();Map<File,byte[]> retained=PurpleSyncRecoveryCases.files(directory);
            PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.Check shown=fresh(runner);
            check(shown.review.status==PurpleSyncCore.ReviewStatus.Ready,"inactive bytes do not consume B actionable budget, exhausted="+exhausted);
            check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.None,"inactive budget exhaustion never requests recovery");runner.close();
            check(Arrays.equals(b,frame.state(1))&&frame.clouds[1].posts==0,"inactive budget check leaves B exact and never posts");
            check(TOKEN_A.equals(frame.token(0))&&TOKEN_B.equals(frame.token(1)),"inactive budget check preserves binding preferences");
            PurpleSyncRecoveryCases.retained(retained);frame.unchangedA();
            if(exhausted) {
                MessagesController.getMainSettings(2).edit().putString("purple_sync_binding_150",TOKEN_B).commit();
                runner=frame.env(1).runner();shown=fresh(runner);
                check(shown.recovery!=null&&shown.recovery.plan.status==PurpleSyncRecoveryCore.Status.Ambiguous,"known B token claim still refuses after inactive budget exhaustion");runner.close();
                MessagesController.getMainSettings(2).edit().putString("purple_sync_binding_150","33333333333333333333333333333333").commit();
                PurpleSyncRecoveryFiles.InactiveTokens tokens=new PurpleSyncRecoveryFiles.InactiveTokens();
                int[] reads={0};PurpleGlobalChoiceFiles.Physical physical=new PurpleGlobalChoiceFiles.Physical() {
                    public PurpleGlobalChoiceFiles.Mark mark(File file,boolean dir)throws Exception {++reads[0];return PHYSICAL.mark(file,dir);}
                    public boolean write(File file,PurpleGlobalChoiceFiles.Mark mark,byte[] bytes){throw new AssertionError("inactive discovery never writes");}
                };
                for(int i=0;i<4;++i)check(tokens.inspect(new File(directory,"state.json"),physical)!=null,"independent inactive allowance");
                int before=reads[0];check(tokens.inspect(new File(directory,"state.json.bak"),physical)==null&&reads[0]==before,"exhausted inactive budget makes no further reads");
            }
        }
        Frame oversized=new Frame(parent,"inactive-unknown-oversized",true);oversized.initialize(1);logout(0);
        write(new File(oversized.sync(0),"state.json"),new byte[PurpleSyncRecoveryFiles.FILE_LIMIT+1]);oversized.snapshotA();
        PurpleSyncRunner runner=oversized.env(1).runner();check(fresh(runner).review.status==PurpleSyncCore.ReviewStatus.Ready,"oversized unrelated inactive claim stays unknown");runner.close();oversized.unchangedA();
    }
    static void crashes(File parent)throws Exception {
        for(String suffix:new String[]{"new","bak"})for(boolean pending:new boolean[]{false,true}) {
            Frame frame=new Frame(parent,"runner-crash-"+suffix+"-"+pending,true);frame.initialize(1);
            if(pending) {
                PurpleSyncRunner sender=frame.env(1).runner();frame.clouds[1].holdPost=true;
                PurpleSyncRunner.PostTicket ticket=PurpleSyncLifecycleCases.ticket(sender);
                check(sender.publish(ticket,value->{})==PurpleSyncRunner.Start.Started,"durable stage before injected interrupted write");
                frame.clouds[1].unknownPost();sender.close();
            }
            File target=new File(frame.sync(1),pending?"pending/config.json":"state.json");byte[] original=Files.readAllBytes(target.toPath());byte[] state=frame.state(1);
            File artifact=new File(target.getPath()+"."+suffix);write(artifact,suffix.equals("new")?bytes("incomplete write"):original);if(suffix.equals("bak"))write(target,"incomplete write");
            PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.Check shown=fresh(runner);
            check(shown.review.status==PurpleSyncCore.ReviewStatus.Ready,"runner recovers "+target.getName()+"."+suffix+" before classification");
            check(!artifact.exists()&&Arrays.equals(original,Files.readAllBytes(target.toPath())),"AtomicFile layout recovers previous exact bytes");
            check(Arrays.equals(state,frame.state(1)),"recovery does not reserve or confirm a new generation");frame.unchangedA();runner.close();
        }
        for(String unsafe:new String[]{"unknown","symlink","directory"}) {
            Frame frame=new Frame(parent,"unsafe-crash-"+unsafe,true);frame.initialize(1);File state=new File(frame.sync(1),"state.json");byte[] original=frame.state(1);File artifact=new File(state.getPath()+".new");
            if(unsafe.equals("symlink"))Files.createSymbolicLink(artifact.toPath(),state.toPath());
            else if(unsafe.equals("directory"))directory(artifact);
            else {write(artifact,"incomplete");write(new File(frame.sync(1),"unknown.bin"),"untouched");}
            PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.Check shown=fresh(runner);
            check(shown.review.status!=PurpleSyncCore.ReviewStatus.Ready,"unsafe or unknown crash artifacts stay held");
            check(Files.exists(artifact.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)&&Arrays.equals(original,frame.state(1)),"refused repair preserves artifact and state");frame.unchangedA();runner.close();
        }
    }
    static void emptyJoin(File parent)throws Exception {
        Frame frame=new Frame(parent,"explicit-empty-join",false);MessagesController.getMainSettings(1).edit().remove("purple_sync_binding_202").commit();Env env=frame.env(1);PurpleSyncRunner runner=env.runner();PurpleSyncRunner.Check shown=fresh(runner);
        check(shown.setupOnlyJoin&&shown.action()==PurpleSyncCore.Action.Join,"UI action model explicitly offers setup-only Join");
        check(!shown.requiresChoice(PurpleSyncCore.Action.Join)&&shown.review.choices.isEmpty(),"UI confirmation does not require a nonexistent choice");
        check(shown.requiresChoice(PurpleSyncCore.Action.Publish),"setup permission cannot authorize publication");
        PurpleSyncRunner.ApplyOutcome joined=apply(runner,shown,null);
        check(joined.result.status==PurpleSyncCore.ApplyStatus.Applied&&joined.result.joined&&!joined.result.wroteFile&&!joined.result.publishNeeded,"empty Join only binds");
        check(PurpleAccountBinding.isToken(frame.token(1)),"setup-only Join persists its new account binding token");
        check(env.hasState()&&joined.post==null&&frame.clouds[1].posts==0&&!frame.settings(1).exists(),"empty Join creates no primary or post");
        check(env.history().list().isEmpty()&&!frame.good(1).exists(),"setup-only Join creates no false History or good copy");
        check(!fresh(runner).setupOnlyJoin,"already-bound absent file is not offered setup again");frame.unchangedA();runner.close();
        Frame changed=new Frame(parent,"empty-join-stale-file",false);runner=changed.env(1).runner();shown=fresh(runner);write(changed.settings(1),OWN);
        check(runner.apply(shown,null,value->{})==PurpleSyncRunner.Start.Stale&&!new File(changed.sync(1),"state.json").exists(),"setup-only confirmation refuses a file created after review");changed.unchangedA();runner.close();
        Frame fallback=new Frame(parent,"empty-join-good-held",false);write(fallback.good(1),OWN);runner=fallback.env(1).runner();shown=fresh(runner);
        check(!shown.setupOnlyJoin&&shown.action()!=PurpleSyncCore.Action.Join,"own good copy still blocks setup-only shortcut");fallback.unchangedA();runner.close();
    }
    static void run(File parent)throws Exception {preferences(parent);inactiveBudgets(parent);crashes(parent);emptyJoin(parent);}
}
