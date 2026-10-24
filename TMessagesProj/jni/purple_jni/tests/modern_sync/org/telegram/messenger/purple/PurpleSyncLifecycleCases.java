package org.telegram.messenger.purple;

import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

final class PurpleSyncLifecycleCases {
    static PurpleSyncRunner.PostTicket ticket(PurpleSyncRunner runner) {
        PurpleSyncRunner.ApplyOutcome applied=apply(runner,fresh(runner),null);
        check(applied.result.status==PurpleSyncCore.ApplyStatus.Applied&&applied.post!=null,"local choice prepares account publication");
        return applied.post;
    }
    static void publication(File parent)throws Exception {
        Frame frame=new Frame(parent,"B-publish",true);frame.initialize(1);
        PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.PostTicket ticket=ticket(runner);
        PurpleSyncPublisher.Result[] result={null};check(runner.publish(ticket,value->result[0]=value)==PurpleSyncRunner.Start.Started,"B Publish starts");
        check(result[0]!=null&&result[0].status==PurpleSyncCore.PublishStatus.Confirmed&&result[0].posts==1,"B reserve/send/readback/confirm finishes once");
        check(!new File(frame.sync(1),"pending/config.json").exists(),"confirmed B stage removed");
        check(PurpleAccountSyncCore.inspectState(frame.state(1)).isValid(),"confirmed B state stays native-valid");
        frame.unchangedA();runner.close();
        for(boolean confirmed:new boolean[]{false,true})for(boolean missing:new boolean[]{false,true}) {
            Frame pending=new Frame(parent,"finish-"+confirmed+"-"+missing,true);pending.initialize(1);
            PurpleSyncRunner first=pending.env(1).runner();PurpleSyncRunner.PostTicket staged=ticket(first);pending.clouds[1].holdPost=true;
            result[0]=null;check(first.publish(staged,value->result[0]=value)==PurpleSyncRunner.Start.Started,"publication retains uncertain stage");
            check(result[0]==null&&new File(pending.sync(1),"pending/config.json").isFile(),"stage is durable before receipt");
            if(confirmed)pending.clouds[1].records.add(pending.clouds[1].posted.clone());pending.clouds[1].unknownPost();
            check(result[0].status==PurpleSyncCore.PublishStatus.OutcomeUnknown,"lost receipt preserves uncertain outcome");first.close();
            write(pending.good(1),OWN);if(missing)Files.delete(pending.settings(1).toPath());else write(pending.settings(1),"version=[\n");
            PurpleSyncRunner resumed=pending.env(1).runner();PurpleSyncRunner.Check shown=fresh(resumed);
            check(shown.local.usingLastGood,"Finish remains available with this file's fallback");pending.clouds[1].holdPost=false;
            result[0]=null;check(resumed.publish(shown,PurpleSyncCore.PostRequest.finishSending(),value->result[0]=value)==PurpleSyncRunner.Start.Started,"Finish bypasses only replacement context");
            check(result[0]!=null&&result[0].status==PurpleSyncCore.PublishStatus.Confirmed,"Finish confirms staged exact bytes with bad/missing primary");
            check(result[0].posts==(confirmed?0:1),"found staged post is confirmed without a duplicate");
            check(!new File(pending.sync(1),"pending/config.json").exists(),"Finish removes only confirmed stage");
            check(missing?!pending.settings(1).exists():Files.readString(pending.settings(1).toPath()).equals("version=[\n"),"Finish does not repair or replace primary");
            pending.unchangedA();resumed.close();
        }
    }
    static void conflict(File parent)throws Exception {
        Frame frame=new Frame(parent,"conflict-share",true);frame.initialize(1);
        PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.PostTicket first=ticket(runner);PurpleSyncPublisher.Result[] result={null};
        check(runner.publish(first,value->result[0]=value)==PurpleSyncRunner.Start.Started&&result[0].status==PurpleSyncCore.PublishStatus.Confirmed,"initial B version published");
        write(frame.settings(1),REMOTE);
        byte[] whole=bytes("version=1\n[lists.remote]\ntitle=\"Remote\"\nmembers=[77]\n[presets.remote]\nchats=[\"remote\"]\n");
        frame.clouds[1].remote(whole,frame.space(1),1,frame.clouds[1].records.get(0));
        PurpleAccountSyncCore.Result branch=PurpleAccountSyncCore.buildConfigRecord(bytes("version=2\n# other remote branch\n"),new byte[][]{frame.clouds[1].records.get(0)},frame.space(1),install(3),device(3),"Desktop","fixture",1,1700000001L);
        check(branch.isValid()&&branch.record!=null,"second genuine branch");frame.clouds[1].records.add(branch.record);
        PurpleSyncRunner.Check shown=fresh(runner);
        check(shown.review.verdict==PurpleSyncCore.Verdict.Conflict,"concurrent local and remote edit needs explicit choice");
        PurpleSyncCore.Choice local=null;for(PurpleSyncCore.Choice choice:shown.review.choices)if(choice.key==null)local=choice;
        check(local!=null&&local.impactReady&&local.dangerous,"keeping layered B over remote whole file carries loss warning");
        boolean replaced=false;for(PurpleSyncCore.ChoiceImpact change:local.impacts)if(change.replacedKey!=null)replaced=true;
        check(replaced,"local-over-remote impact names replaced remote version");
        PurpleSyncRunner.ApplyOutcome applied=apply(runner,shown,remote(shown).key);
        check(applied.result.status==PurpleSyncCore.ApplyStatus.Applied&&applied.post!=null&&applied.post.freshCheckFirst,"chosen remote conflict retains original post ticket: "+applied.result.status+" store="+applied.result.storeStatus+" wrote="+applied.result.wroteFile+" post="+(applied.post!=null)+" fresh="+(applied.post!=null&&applied.post.freshCheckFirst));
        check(runner.publish(applied.post,value->{})==PurpleSyncRunner.Start.Stale,"conflict share requires fresh inventory");
        PurpleSyncRunner.Check refreshed=fresh(runner);result[0]=null;
        check(runner.publish(refreshed,applied.post,value->result[0]=value)==PurpleSyncRunner.Start.Started,"fresh inventory keeps original impact authority");
        check(result[0]!=null&&result[0].status==PurpleSyncCore.PublishStatus.Confirmed,"reviewed conflict version is published and confirmed");
        frame.unchangedA();runner.close();
    }
    static void stale(File parent)throws Exception {
        for(String changed:new String[]{"role","local","other","parent","lifetime","backend"}) {
            Frame frame=new Frame(parent,"stale-"+changed,true);frame.initialize(1);PurpleSyncRunner runner=frame.env(1).runner();
            frame.clouds[1].remote(REMOTE,frame.space(1),1);PurpleSyncRunner.Check shown=fresh(runner);String key=remote(shown).key;
            byte[] original=Files.readAllBytes(frame.settings(1).toPath());
            if(changed.equals("role"))++frame.role;
            if(changed.equals("local"))frame.local("follow");
            if(changed.equals("other"))write(frame.settings(0),new String(GLOBAL,java.nio.charset.StandardCharsets.UTF_8)+"\n# changed\n");
            if(changed.equals("parent")){File directory=frame.settings(1).getParentFile();File moved=new File(directory.getPath()+"-old");Files.move(directory.toPath(),moved.toPath());directory(directory);for(File child:moved.listFiles())Files.move(child.toPath(),new File(directory,child.getName()).toPath());}
            if(changed.equals("lifetime"))retire(1);
            if(changed.equals("backend"))ConnectionsManager.getInstance(1).test=true;
            check(runner.apply(shown,key,value->{throw new AssertionError("stale apply callback");})!=PurpleSyncRunner.Start.Started,"retained review refuses changed "+changed);
            check(Arrays.equals(original,Files.readAllBytes(frame.settings(1).toPath())),"stale review retains B exact primary");runner.close();
        }
        Frame frame=new Frame(parent,"stale-post-ticket",true);frame.initialize(1);PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.PostTicket ticket=ticket(runner);
        PurpleSyncRunner foreign=frame.env(1).runner();check(foreign.publish(ticket,value->{})==PurpleSyncRunner.Start.Stale,"foreign runner cannot consume a post ticket");
        frame.local("stock");check(runner.publish(ticket,value->{})==PurpleSyncRunner.Start.Stale&&frame.clouds[1].posts==0,"retained local-over-remote impact survives through post");
        frame.unchangedA();runner.close();foreign.close();
    }
    static void simultaneous(File parent)throws Exception {
        Frame frame=new Frame(parent,"simultaneous",true);frame.initialize(1);
        PurpleSyncRunner a=frame.env(0).runner(),b=frame.env(1).runner(),duplicate=frame.env(1).runner();frame.clouds[0].holdCheck=true;frame.clouds[1].holdCheck=true;
        check(a.check(null,value->{})==PurpleSyncRunner.Start.Started,"A independent held network Check");
        check(b.check(null,value->{})==PurpleSyncRunner.Start.Started,"B Check starts while A waits on network");
        check(duplicate.check(null,value->{})==PurpleSyncRunner.Start.Busy,"same B operation is serialized");
        a.close();check(b.busy()&&!duplicate.closed(),"closing A does not end B operation");
        frame.clouds[1].finishCheck();check(!b.busy(),"only B result ends B operation");b.close();duplicate.close();
        Cloud oldCloud=new Cloud(202);oldCloud.holdCheck=true;Env oldEnv=frame.env(1);PurpleSyncRunner old=new PurpleSyncRunner(1,202,oldCloud,oldEnv,Runnable::run,Runnable::run);check(old.check(null,value->{})==PurpleSyncRunner.Start.Started,"old lifetime holds lease");
        PurpleSyncTransport.CheckDone late=oldCloud.checking;retire(1);PurpleSyncRunner next=frame.env(1).runner();
        check(next.check(null,value->{})==PurpleSyncRunner.Start.Started,"retired lease cannot strand same key new login");
        old.close();late.onCheckDone(new PurpleSyncTransport.CheckResult(PurpleSyncTransport.CheckStatus.Finished,frame.clouds[1].inventory(),false));
        check(next.busy(),"late end of old lease cannot release new owner");frame.clouds[1].finishCheck();next.close();frame.unchangedA();
    }
    static void history(File parent)throws Exception {
        Frame frame=new Frame(parent,"history-guards",true);frame.initialize(1);Env env=frame.env(1);PurpleSyncRunner runner=env.runner();
        PurpleSyncHistory.Entry saved=env.history().save(REMOTE,PurpleSyncHistory.Reason.BeforeUpdate,"fixture","",null);
        check(saved!=null,"real History entry saved");PurpleSyncRunner.Preview preview=preview(runner,saved.id);
        write(frame.settings(1),bytes(new String(OWN,java.nio.charset.StandardCharsets.UTF_8)+"\n# edited\n"));
        check(runner.restore(preview,value->{})==PurpleSyncRunner.Start.Stale,"History refuses changed primary after preview");
        preview=preview(runner,saved.id);frame.local("follow");check(runner.restore(preview,value->{})==PurpleSyncRunner.Start.Stale,"History refuses changed Work Mode after preview");
        write(frame.settings(1),"version=[\n");java.util.List<PurpleSyncHistory.Entry>[] entries=new java.util.List[]{null};
        check(runner.history(value->entries[0]=value)&&entries[0].size()==1,"read-only History lists despite corrupt primary");
        check(!preview(runner,saved.id).impactReady,"corrupt primary is not silently replaced by good for impact");Files.delete(frame.settings(1).toPath());
        check(runner.history(value->entries[0]=value)&&entries[0].size()==1,"read-only History lists despite absent primary");
        for(int i=0;i<35;++i)check(env.history().save(bytes("version=2\n# "+i+"\n"),PurpleSyncHistory.Reason.BeforeUpdate,"fixture","",null)!=null,"History bounded save "+i);
        check(env.history().list().size()==30,"History retains thirty entries per account");frame.unchangedA();runner.close();
    }
    static void ownedMoves(File parent)throws Exception {
        Frame frame=new Frame(parent,"exclusive-native-moves",true);File directory=new File(frame.app,"native-moves");directory(directory);
        File from=new File(directory,"source"),to=new File(directory,"destination");write(from,"original bytes");write(to,"existing destination");
        String parentIdentity=PHYSICAL.mark(directory,true).identity,sourceIdentity=PHYSICAL.mark(from,false).identity;
        check(!PurpleSyncRecoveryFiles.moveNative(from.getPath(),to.getPath(),sourceIdentity,parentIdentity,parentIdentity),"native archive move refuses an existing destination atomically");
        check(Files.readString(to.toPath()).equals("existing destination")&&Files.readString(from.toPath()).equals("original bytes"),"refused rename overwrites neither file");
        Files.delete(to.toPath());check(!PurpleSyncRecoveryFiles.moveNative(from.getPath(),to.getPath(),"wrong",parentIdentity,parentIdentity),"native move requires the exact retained source inode");
        check(!PurpleSyncRecoveryFiles.moveNative(from.getPath(),to.getPath(),sourceIdentity,"wrong",parentIdentity),"native move requires the opening parent inode");
        check(PurpleSyncRecoveryFiles.moveNative(from.getPath(),to.getPath(),sourceIdentity,parentIdentity,parentIdentity),"exclusive native move succeeds for owned source and absent destination");
        check(!from.exists()&&Files.readString(to.toPath()).equals("original bytes"),"durable move retains exact bytes");frame.unchangedA();
    }
    static void signedOutEdit(File parent)throws Exception {
        Frame frame=new Frame(parent,"signed-out-edit",true);frame.initialize(1);PurpleSyncRunner old=frame.env(1).runner();
        PurpleSyncRunner.PostTicket ticket=ticket(old);PurpleSyncPublisher.Result[] posted={null};
        check(old.publish(ticket,value->posted[0]=value)==PurpleSyncRunner.Start.Started&&posted[0].status==PurpleSyncCore.PublishStatus.Confirmed,"publish before sign-out");
        PurpleSyncRunner.Check retained=fresh(old);org.telegram.messenger.UserConfig.getInstance(1).active=false;retire(1);write(frame.settings(1),REMOTE);
        check(old.apply(retained,null,value->{})==PurpleSyncRunner.Start.AccountUnavailable,"signed-out old session cannot consume review");
        org.telegram.messenger.UserConfig.getInstance(1).active=true;retire(1);PurpleSyncRunner reentered=frame.env(1).runner();PurpleSyncRunner.Check shown=fresh(reentered);
        check(shown.review.verdict==PurpleSyncCore.Verdict.LocalChanges&&Arrays.equals(shown.local.bytes,REMOTE),"new login retains signed-out edit as local change");
        check(frame.clouds[1].posts==1,"re-login Check sends no signed-out edit automatically");frame.unchangedA();old.close();reentered.close();
    }
    static void binding(File parent)throws Exception {
        Frame frame=new Frame(parent,"binding-capture",true);int[] consumed={0};SecureRandom random=new SecureRandom(){public void nextBytes(byte[] bytes){consumed[0]+=bytes.length;Arrays.fill(bytes,(byte)7);}};
        PurpleAccountBinding.Initialization existing=PurpleAccountBinding.prepareInitialization(1,random);
        check(existing.isValid()&&consumed[0]==0,"valid token consumes no entropy");
        MessagesController.getMainSettings(1).edit().remove("purple_sync_binding_202").commit();
        check(!PurpleAccountBinding.persist(1,existing,TOKEN_B).isValid(),"expected preference value prevents stale initialization");
        PurpleAccountBinding.Initialization absent=PurpleAccountBinding.prepareInitialization(1,random);check(absent.isValid()&&consumed[0]==16,"absent token consumes exactly sixteen bytes");
        ConnectionsManager.getInstance(1).test=true;check(!PurpleAccountBinding.persist(1,absent,"07070707070707070707070707070707").isValid(),"backend switch refuses retained token publication");
        ConnectionsManager.getInstance(1).test=false;PurpleAccountBinding.Initialization retired=PurpleAccountBinding.prepareInitialization(1,random);retire(1);
        check(!PurpleAccountBinding.persist(1,retired,"07070707070707070707070707070707").isValid(),"logout/login refuses retained token publication");frame.unchangedA();
    }
    static void run(File parent)throws Exception {publication(parent);conflict(parent);stale(parent);simultaneous(parent);history(parent);ownedMoves(parent);signedOutEdit(parent);binding(parent);}
}
