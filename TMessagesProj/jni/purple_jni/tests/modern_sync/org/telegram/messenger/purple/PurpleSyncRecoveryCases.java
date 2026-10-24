package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

final class PurpleSyncRecoveryCases {
    static PurpleSyncRecoveryModel.Offer offer(Frame frame,int slot,PurpleSyncInventory inventory,PurpleSyncRecoveryFiles.Moves moves)throws Exception{
        Env env=frame.env(slot);PurpleAccountBinding.Binding binding=PurpleAccountBinding.readForCheck(slot);
        PurpleSyncRecoveryModel.Preferences preferences=PurpleSyncRecovery.capturePreferences(slot,binding,()->env.current(slot,env.identity.userId));
        return new PurpleSyncRecoveryModel(frame.root,slot==0?A:B,A,frame.global,preferences,()->env.current(slot,env.identity.userId),PHYSICAL,PurpleEditorDirectories.NATIVE,moves).check(inventory,env.readSettings());
    }
    static void removeToken(int slot){MessagesController.getMainSettings(slot).edit().remove("purple_sync_binding_"+(slot==0?101:202)).commit();}
    static void publish(Frame frame,int slot)throws Exception{
        PurpleSyncRunner runner=frame.env(slot).runner();PurpleSyncRunner.Check shown=fresh(runner);
        PurpleSyncRunner.ApplyOutcome applied=apply(runner,shown,null);check(applied.result.status==PurpleSyncCore.ApplyStatus.Applied&&applied.post!=null,"local choice prepares a real post");
        PurpleSyncPublisher.Result[] sent={null};check(runner.publish(applied.post,result->sent[0]=result)==PurpleSyncRunner.Start.Started,"account publication starts");
        check(sent[0]!=null&&sent[0].status==PurpleSyncCore.PublishStatus.Confirmed,"real reserve, post, readback and confirmation");runner.close();
    }
    static void keyedCases(File parent)throws Exception{
        Frame frame=new Frame(parent,"auto-relink",true);frame.initialize(1);removeToken(1);
        PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.Check shown=fresh(runner);
        check(TOKEN_B.equals(frame.token(1)),"manual B Check automatically relinks only its uniquely keyed store");
        check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.None&&shown.review.status==PurpleSyncCore.ReviewStatus.Ready,"normal core review runs after auto-relink");
        frame.unchangedA();runner.close();
        Frame duplicate=new Frame(parent,"duplicate-store-token",true);duplicate.initialize(1);removeToken(1);
        File clone=new File(duplicate.root,"accounts/0-303/sync");directory(clone);write(new File(clone,"state.json"),duplicate.state(1));directory(new File(clone,"pending"));
        PurpleSyncRecoveryModel.Offer ambiguous=offer(duplicate,1,duplicate.clouds[1].inventory(),PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(ambiguous!=null&&ambiguous.plan.status==PurpleSyncRecoveryCore.Status.Ambiguous,"duplicate retained store token refuses ownership");
        check(!ambiguous.model.relink(ambiguous)&&duplicate.token(1)==null,"ambiguous recovery never restores a token");duplicate.unchangedA();
        Frame signed=new Frame(parent,"retired-relink",true);signed.initialize(1);removeToken(1);
        PurpleSyncRecoveryModel.Offer retained=offer(signed,1,signed.clouds[1].inventory(),PurpleSyncRecoveryFiles.NATIVE_MOVES);retire(1);
        check(!retained.model.relink(retained)&&signed.token(1)==null,"logout/re-login lifetime retires retained relink");signed.unchangedA();
    }
    static Map<File,byte[]> files(File root)throws Exception{
        Map<File,byte[]> out=new LinkedHashMap<>();if(!root.exists())return out;
        try(java.util.stream.Stream<java.nio.file.Path> stream=Files.walk(root.toPath())){for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)stream::iterator)if(Files.isRegularFile(path))out.put(path.toFile(),Files.readAllBytes(path));}return out;
    }
    static void retained(Map<File,byte[]> expected)throws Exception{for(Map.Entry<File,byte[]> entry:expected.entrySet())check(Arrays.equals(entry.getValue(),Files.readAllBytes(entry.getKey().toPath())),"recovery retains unrelated exact bytes: "+entry.getKey().getName());}
    static void rootCases(File parent)throws Exception{
        Frame frame=new Frame(parent,"root-relink",true);frame.initialize(1);publish(frame,0);removeToken(0);
        Map<File,byte[]> b=files(new File(frame.root,"accounts/"+B));byte[] original=frame.state(0);byte[] settings=Files.readAllBytes(frame.settings(0).toPath());
        PurpleSyncRunner runner=frame.env(0).runner();PurpleSyncRunner.Check shown=fresh(runner);
        check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.Relink&&frame.token(0)==null,"root Check shows explicit Relink without restoring anything");
        PurpleSyncRunner.RecoveryOutcome[] result={null};check(runner.recover(shown,false,value->result[0]=value)==PurpleSyncRunner.Start.Started,"explicit root Relink refreshes cloud evidence");
        check(result[0]!=null&&result[0].ok&&TOKEN_A.equals(frame.token(0)),"complete exact-account cloud proof restores root token");
        check(Arrays.equals(original,frame.state(0))&&Arrays.equals(settings,Files.readAllBytes(frame.settings(0).toPath())),"root Relink changes no settings or sync state bytes");retained(b);check(TOKEN_B.equals(frame.token(1)),"root Relink retains B preference");runner.close();
        Frame incomplete=new Frame(parent,"root-incomplete",true);publish(incomplete,0);removeToken(0);
        PurpleSyncInventory.Builder partial=new PurpleSyncInventory.Builder(101);
        PurpleSyncRecoveryModel.Offer held=offer(incomplete,0,partial.build(false),PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(held!=null&&held.plan.status==PurpleSyncRecoveryCore.Status.Incomplete&&!held.plan.offerStartAgain,"incomplete cloud read authorizes neither root relink nor reset");
        check(!held.model.relink(held)&&incomplete.token(0)==null,"incomplete root proof does not write preferences");
        PurpleSyncInventory wrong=new PurpleSyncInventory.Builder(202).build(true);
        held=offer(incomplete,0,wrong,PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(held.plan.status==PurpleSyncRecoveryCore.Status.Ambiguous&&!held.plan.offerStartAgain,"wrong cloud account refuses root proof");
        Frame mismatch=new Frame(parent,"root-mismatch",true);publish(mismatch,0);removeToken(0);mismatch.clouds[0].records.clear();
        held=offer(mismatch,0,mismatch.clouds[0].inventory(),PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(held.plan.status==PurpleSyncRecoveryCore.Status.StartAgain&&held.plan.offerStartAgain&&mismatch.token(0)==null,"complete negative root proof offers only explicit archive");
    }
    static Frame invalid(File parent,String name)throws Exception{
        Frame frame=new Frame(parent,name,true);frame.initialize(1);
        write(new File(frame.sync(1),"state.json"),new byte[]{(byte)0xff,'b','a','d'});
        write(new File(frame.sync(1),"pending/other-stream.json"),new byte[]{0,1,2,(byte)0xff});
        write(new File(frame.settings(1).getPath()+".bak"),"B backup exact\n");write(new File(frame.settings(1).getPath()+".import.bak"),"B import backup exact\n");write(frame.good(1),OWN);
        return frame;
    }
    static void archiveCases(File parent)throws Exception{
        Frame frame=invalid(parent,"partial-archive");Map<File,byte[]> preserved=new LinkedHashMap<>();
        for(File file:new File[]{frame.settings(1),frame.good(1),new File(frame.settings(1).getPath()+".bak"),new File(frame.settings(1).getPath()+".import.bak"),new File(frame.root,"local.toml")})preserved.put(file,Files.readAllBytes(file.toPath()));
        new PurpleSyncHistory(frame.history(1),PurpleSyncHistory.NATIVE,System::currentTimeMillis).save(OWN,PurpleSyncHistory.Reason.BeforeUpdate,"B saved","",null);preserved.putAll(files(frame.history(1)));
        byte[] original=frame.state(1),unknown=Files.readAllBytes(new File(frame.sync(1),"pending/other-stream.json").toPath());int[] moves={0};
        PurpleSyncRecoveryModel.Offer shown=offer(frame,1,frame.clouds[1].inventory(),(a,b,id,from,to)->++moves[0]==2?false:PurpleSyncRecoveryFiles.NATIVE_MOVES.move(a,b,id,from,to));
        check(shown!=null&&shown.plan.offerStartAgain,"invalid state offers explicit Start again");
        PurpleSyncOperations.undo(B+"\n"+frame.sync(1).getPath(),new PurpleSyncRunner.UndoOffer("B undo",null));
        check(!shown.model.startAgain(shown,true),"queued sync post blocks archive before any move");
        check(!new File(frame.sync(1),"start-again.json").exists()&&new File(frame.sync(1),"pending").exists(),"busy Start again publishes no journal or move");
        shown=offer(frame,1,frame.clouds[1].inventory(),(a,b,id,from,to)->++moves[0]==2?false:PurpleSyncRecoveryFiles.NATIVE_MOVES.move(a,b,id,from,to));
        check(!shown.model.startAgain(shown,false),"injected state move failure reports partial archive");
        File journalFile=new File(frame.sync(1),"start-again.json");check(journalFile.isFile(),"durable journal retained after partial move");
        check(!new File(frame.sync(1),"pending").exists()&&Arrays.equals(original,frame.state(1)),"pending moves before state");
        check(PurpleAccountSyncStore.hasState(frame.sync(1)),"partial journal cannot be mistaken for setup");
        PurpleSyncRecoveryModel.Offer resume=offer(frame,1,frame.clouds[1].inventory(),PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(resume!=null&&resume.resuming()&&resume.plan.start==PurpleSyncRecoveryCore.Start.Ready,"next Check resumes exactly the retained plan");
        String archive=resume.plan.archive;check(resume.model.startAgain(resume,false),"resumed archive completes exact readback");
        File archived=new File(frame.root,archive);
        check(Arrays.equals(original,Files.readAllBytes(new File(archived,"state.json").toPath())),"invalid binary state archived byte-exactly");
        check(Arrays.equals(unknown,Files.readAllBytes(new File(archived,"pending/other-stream.json").toPath())),"unknown pending data preserved byte-exactly");
        check(!journalFile.exists()&&!new File(frame.sync(1),"state.json").exists()&&!new File(frame.sync(1),"pending").exists(),"completed journal leaves no live old state or stages");
        check(!PurpleAccountSyncStore.hasState(frame.sync(1)),"retained archives do not block fresh setup");
        check(TOKEN_B.equals(frame.token(1)),"Start again preserves valid existing preference token");retained(preserved);frame.unchangedA();
        PurpleSyncRecoveryModel.Offer setup=offer(frame,1,frame.clouds[1].inventory(),PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(setup!=null&&setup.plan.status==PurpleSyncRecoveryCore.Status.Setup,"only completed archive re-enters setup");
        PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.ApplyOutcome joined=apply(runner,fresh(runner),null);
        check(joined.result.status==PurpleSyncCore.ApplyStatus.Applied&&joined.result.joined,"fresh store with retained archive can initialize");
        check(TOKEN_B.equals(frame.token(1)),"fresh setup reuses the preference token rather than rotating it");frame.unchangedA();runner.close();
        Frame changed=invalid(parent,"changed-archive");moves[0]=0;
        shown=offer(changed,1,changed.clouds[1].inventory(),(a,b,id,from,to)->++moves[0]==2?false:PurpleSyncRecoveryFiles.NATIVE_MOVES.move(a,b,id,from,to));
        check(!shown.model.startAgain(shown,false),"second partial archive fixture");
        JSONObject journal=new JSONObject(Files.readString(new File(changed.sync(1),"start-again.json").toPath()));
        File directory=new File(changed.sync(1),"orphaned-"+journal.getJSONObject("plan").getString("date"));write(new File(directory,"pending/other-stream.json"),"changed");
        resume=offer(changed,1,changed.clouds[1].inventory(),PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(!resume.model.startAgain(resume,false),"altered archived pending bytes refuse completion");
        check(new File(changed.sync(1),"state.json").exists()&&new File(changed.sync(1),"start-again.json").exists(),"failed readback preserves remaining live state and journal");changed.unchangedA();
        Frame unsafe=invalid(parent,"unsafe-pending");File outside=new File(unsafe.app,"outside");write(outside,"outside exact");
        Files.createSymbolicLink(new File(unsafe.sync(1),"pending/link").toPath(),outside.toPath());
        shown=offer(unsafe,1,unsafe.clouds[1].inventory(),PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(shown!=null&&shown.plan.status==PurpleSyncRecoveryCore.Status.Incomplete&&!shown.plan.offerStartAgain,"symlink pending entry makes discovery incomplete");
        check(Files.readString(outside.toPath()).equals("outside exact"),"unsafe pending traversal never changes outside data");unsafe.unchangedA();
    }
    static void pendingRootCases(File parent)throws Exception {
        for(boolean found:new boolean[]{false,true}) {
            Frame frame=new Frame(parent,"root-pending-"+found,true);frame.initialize(1);
            PurpleSyncRunner publisher=frame.env(0).runner();PurpleSyncRunner.PostTicket ticket=PurpleSyncLifecycleCases.ticket(publisher);
            frame.clouds[0].holdPost=true;
            check(publisher.publish(ticket,value->{})==PurpleSyncRunner.Start.Started,"root creates durable pending config stage");
            if(found)frame.clouds[0].records.add(frame.clouds[0].posted.clone());
            frame.clouds[0].unknownPost();publisher.close();removeToken(0);
            byte[] state=frame.state(0),stage=Files.readAllBytes(new File(frame.sync(0),"pending/config.json").toPath());
            PurpleSyncRunner runner=frame.env(0).runner();PurpleSyncRunner.Check shown=fresh(runner);
            check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.Relink,"root pending proof uses config.json adapter for "+found);
            PurpleSyncRunner.RecoveryOutcome[] relinked={null};check(runner.recover(shown,false,value->relinked[0]=value)==PurpleSyncRunner.Start.Started,"pending root relink starts");
            check(relinked[0]!=null&&relinked[0].ok&&TOKEN_A.equals(frame.token(0)),"complete root pending proof relinks");
            check(Arrays.equals(state,frame.state(0))&&Arrays.equals(stage,Files.readAllBytes(new File(frame.sync(0),"pending/config.json").toPath())),"relink preserves pending stage and reservation");
            runner.close();
        }
    }
    static void archiveRunnerCases(File parent)throws Exception {
        Frame frame=invalid(parent,"runner-archive-clears-only-B");Env env=frame.env(1);PurpleSyncRunner runner=env.runner();
        String aKey=frame.env(0).operationKey(0,101),bKey=env.operationKey(1,202);
        PurpleSyncRunner.UndoOffer a=new PurpleSyncRunner.UndoOffer("A retained",null),b=new PurpleSyncRunner.UndoOffer("B old",null);
        PurpleSyncOperations.undo(aKey,a);PurpleSyncOperations.undo(bKey,b);
        PurpleSyncRunner.Check shown=fresh(runner);check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.StartAgain,"runner offers account B archive");
        PurpleSyncRunner.RecoveryOutcome[] result={null};check(runner.recover(shown,true,value->result[0]=value)==PurpleSyncRunner.Start.Started,"runner refreshes and archives B");
        check(result[0]!=null&&result[0].ok&&PurpleSyncOperations.undo(bKey)==null,"only SetupReady clears B Undo");
        check(PurpleSyncOperations.undo(aKey)==a,"B Start again retains A Undo");frame.unchangedA();runner.close();
        Frame queued=invalid(parent,"queue-arrives-during-archive");boolean[] idle={true};int[] moves={0};
        PurpleSyncRecoveryModel.Offer offer=offer(queued,1,queued.clouds[1].inventory(),(from,to,id,source,destination)->{
            boolean moved=PurpleSyncRecoveryFiles.NATIVE_MOVES.move(from,to,id,source,destination);if(++moves[0]==1)idle[0]=false;return moved;
        });
        check(!offer.model.startAgain(offer,false,()->idle[0]),"new queued send stops archive between moves");
        check(moves[0]==1&&new File(queued.sync(1),"start-again.json").isFile()&&new File(queued.sync(1),"state.json").isFile(),"interrupted queue leaves journal and live state for same-plan resume");
        queued.unchangedA();
    }
    static void run(File parent)throws Exception{keyedCases(parent);rootCases(parent);archiveCases(parent);pendingRootCases(parent);archiveRunnerCases(parent);}
}
