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
    static final java.nio.file.LinkOption NOFOLLOW=java.nio.file.LinkOption.NOFOLLOW_LINKS;
    interface Change { void run(Frame frame,File sync)throws Exception; }
    static String identity(File file)throws Exception{
        return Files.getAttribute(file.toPath(),"unix:dev",NOFOLLOW)+":"+Files.getAttribute(file.toPath(),"unix:ino",NOFOLLOW);
    }
    static java.util.TreeSet<String> archives(File sync)throws Exception{
        java.util.TreeSet<String> out=new java.util.TreeSet<>();
        for(String name:PurpleSyncRecoveryFiles.names(sync))if(PurpleSyncRecoveryFiles.archiveName(name))out.add(name);
        return out;
    }
    static java.util.TreeSet<String> carryNames(File directory)throws Exception{
        java.util.TreeSet<String> out=new java.util.TreeSet<>();
        for(String name:PurpleSyncRecoveryFiles.names(directory))if(PurpleAccountSyncStore.legacyArtifactName(name))out.add(name);
        return out;
    }
    static void mode(File file,String mode)throws Exception{Files.setPosixFilePermissions(file.toPath(),java.nio.file.attribute.PosixFilePermissions.fromString(mode));}
    /** Opaque carry leftovers: a malformed file, a folder with content, a temporary and an unfinished file. */
    static Map<String,String> seedCarry(File sync)throws Exception{
        write(new File(sync,"carry.json"),new byte[]{'{',(byte)0xff});
        write(new File(sync,"carry-objects/opaque"),"unfinished object");
        write(new File(sync,".carry-stage"),new byte[]{0,1,2});
        write(new File(sync,"carry.json.new"),"partial");
        Map<String,String> ids=new java.util.TreeMap<>();
        for(String name:carryNames(sync))ids.put(name,identity(new File(sync,name)));
        return ids;
    }
    static Map<String,String> carryBytes(File directory)throws Exception{
        Map<String,String> out=new java.util.TreeMap<>();
        for(String name:carryNames(directory))try(java.util.stream.Stream<java.nio.file.Path> stream=Files.walk(new File(directory,name).toPath())){
            for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)stream::iterator)out.put(directory.toPath().relativize(path).toString(),
                    Files.isDirectory(path,NOFOLLOW)?"dir":java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        }
        return out;
    }
    /** Makes every carry entry unreadable, so a set-aside that read or listed one would fail. A folder keeps
     * write and search only, which a cross-folder directory rename needs to update its parent link. */
    static void seal(File directory,boolean sealed)throws Exception{
        for(String name:carryNames(directory)) {
            File entry=new File(directory,name);boolean folder=Files.isDirectory(entry.toPath(),NOFOLLOW);
            if(folder&&!sealed)mode(entry,"rwx------");
            if(folder)for(String inner:PurpleSyncRecoveryFiles.names(entry))mode(new File(entry,inner),sealed?"---------":"rw-------");
            mode(entry,sealed?(folder?"-wx------":"---------"):folder?"rwx------":"rw-------");
        }
    }
    static PurpleSyncRecoveryModel.Offer carryOffer(Frame frame,PurpleSyncRecoveryFiles.Moves moves)throws Exception{
        PurpleSyncRecoveryModel.Offer shown=offer(frame,1,null,moves);
        check(shown!=null&&shown.carry!=null&&shown.carry.safe,"fixture offers a safe carry set");
        return shown;
    }
    static PurpleAccountSyncStore.Status open(Frame frame,int slot)throws Exception{
        try(PurpleAccountSyncStore store=frame.env(slot).store()){return store.open(true,slot,device(slot)).status;}
    }
    static void carrySetAsideCases(File parent)throws Exception{
        // WHY: a Frame resets the shared account globals, so the control runs before the frame under test exists.
        Frame control=new Frame(parent,"carry-control",true);control.initialize(1);
        PurpleSyncRunner healthy=control.env(1).runner();PurpleSyncRunner.Check expected=fresh(healthy);healthy.close();
        Frame frame=new Frame(parent,"carry-set-aside",true);frame.initialize(1);File sync=frame.sync(1);
        Map<String,String> ids=seedCarry(sync),content=carryBytes(sync);
        Map<File,byte[]> kept=files(frame.root);
        kept.keySet().removeIf(file->file.getPath().startsWith(sync.getPath()+File.separator)
                &&PurpleAccountSyncStore.legacyArtifactName(sync.toPath().relativize(file.toPath()).getName(0).toString()));
        seal(sync,true);
        check(!Files.isReadable(new File(sync,"carry.json").toPath()),"sealed carry is unreadable to this fixture");
        PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.Check shown=fresh(runner);
        check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.SetAsideOld&&shown.carryNotice==PurpleSyncRunner.CarryNotice.None,"unreadable carry is offered for explicit set-aside from lstat alone");
        check(shown.review==null||shown.review.status!=PurpleSyncCore.ReviewStatus.Ready,"the Check itself stays held");
        check(archives(sync).isEmpty()&&carryNames(sync).equals(ids.keySet()),"Check moves nothing");
        check(open(frame,1)==PurpleAccountSyncStore.Status.AmbiguousFiles,"the store still refuses before confirmation");
        PurpleSyncRunner.RecoveryOutcome[] wrong={null};
        check(runner.recover(shown,true,value->wrong[0]=value)==PurpleSyncRunner.Start.Stale&&wrong[0]==null,"Start again cannot take the set-aside Check");
        PurpleSyncRunner.RecoveryOutcome[] result={null};
        check(runner.setAsideOld(shown,value->result[0]=value)==PurpleSyncRunner.Start.Started,"confirmed set-aside starts");
        check(result[0]!=null&&result[0].ok,"confirmed set-aside completes");
        java.util.TreeSet<String> folders=archives(sync);check(folders.size()==1,"one new dated folder");
        File archive=new File(sync,folders.first());
        check((((Number)Files.getAttribute(archive.toPath(),"unix:mode",NOFOLLOW)).intValue()&0777)==0700,"the dated folder is private");
        check(carryNames(sync).isEmpty()&&carryNames(archive).equals(ids.keySet())&&PurpleSyncRecoveryFiles.names(archive).length==ids.size(),"exactly the carry entries moved");
        for(Map.Entry<String,String> item:ids.entrySet())check(item.getValue().equals(identity(new File(archive,item.getKey()))),"moved entry keeps its identity: "+item.getKey());
        seal(archive,false);check(carryBytes(archive).equals(content),"moved carry bytes and nested entries are exact");
        retained(kept);frame.unchangedA();
        check(runner.setAsideOld(shown,value->{})==PurpleSyncRunner.Start.Stale,"a used Check cannot run again");
        check(open(frame,1)==PurpleAccountSyncStore.Status.Ready,"the ordinary store opens after set-aside");
        PurpleSyncRunner.Check after=fresh(runner);
        check(after.recoveryAction==PurpleSyncRunner.RecoveryAction.None&&after.carryNotice==PurpleSyncRunner.CarryNotice.None
                &&expected.recoveryAction==PurpleSyncRunner.RecoveryAction.None&&after.review!=null&&expected.review!=null
                &&after.review.status==expected.review.status,"ordinary review resumes like a frame that never had carry");
        runner.close();publish(frame,1);
        check(carryNames(archive).equals(ids.keySet()),"publication leaves the set-aside folder alone");
    }
    static void carryPartialCases(File parent)throws Exception{
        Frame frame=new Frame(parent,"carry-partial",true);frame.initialize(1);File sync=frame.sync(1);
        Map<String,String> ids=seedCarry(sync);int[] moves={0};
        PurpleSyncRecoveryModel.Offer first=carryOffer(frame,(a,b,id,from,to)->++moves[0]==2?false:PurpleSyncRecoveryFiles.NATIVE_MOVES.move(a,b,id,from,to));
        check(first.carry.entries.keySet().equals(ids.keySet()),"the offer names every carry entry");
        check(!first.model.setAsideCarry(first),"an injected second-move failure reports a partial set-aside");
        java.util.TreeSet<String> folders=archives(sync);check(folders.size()==1,"a partial set-aside keeps its dated folder");
        String moved=ids.keySet().iterator().next();
        check(carryNames(new File(sync,folders.first())).equals(new java.util.TreeSet<>(java.util.Collections.singleton(moved))),"only the first entry moved");
        java.util.TreeSet<String> rest=new java.util.TreeSet<>(ids.keySet());rest.remove(moved);
        check(carryNames(sync).equals(rest),"the rest stay in place, never restored or deleted");
        check(open(frame,1)==PurpleAccountSyncStore.Status.AmbiguousFiles,"the store still refuses the remaining entries");
        check(!first.model.setAsideCarry(first)&&moves[0]==2,"a used offer cannot retry");
        PurpleSyncRecoveryModel.Offer next=carryOffer(frame,PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(next.carry.entries.keySet().equals(rest)&&!next.carry.sameAs(first.carry),"a fresh offer names only the remaining entries");
        PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.Check shown=fresh(runner);
        check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.SetAsideOld,"the next Check asks again for the rest");
        write(new File(sync,"carry-late.json"),"late");
        PurpleSyncRunner.RecoveryOutcome[] mismatch={null};
        check(runner.setAsideOld(shown,value->mismatch[0]=value)==PurpleSyncRunner.Start.Started&&mismatch[0]!=null&&!mismatch[0].ok,"a changed set needs a new confirmation");
        check(carryNames(sync).size()==rest.size()+1&&archives(sync).size()==1,"a mismatched confirmation moves nothing");
        shown=fresh(runner);PurpleSyncRunner.RecoveryOutcome[] done={null};
        check(runner.setAsideOld(shown,value->done[0]=value)==PurpleSyncRunner.Start.Started&&done[0]!=null&&done[0].ok,"the fresh confirmation sets aside the rest");
        folders=archives(sync);check(folders.size()==2&&carryNames(sync).isEmpty(),"the set spans two dated folders");
        Map<String,String> found=new java.util.TreeMap<>();int count=0;
        for(String folder:folders)for(String name:carryNames(new File(sync,folder))){++count;found.put(name,identity(new File(new File(sync,folder),name)));}
        check(count==ids.size()+1&&found.size()==ids.size()+1,"no entry is duplicated or lost");
        for(Map.Entry<String,String> item:ids.entrySet())check(item.getValue().equals(found.get(item.getKey())),"each original entry is set aside once with its identity: "+item.getKey());
        check(open(frame,1)==PurpleAccountSyncStore.Status.Ready,"the ordinary store opens after the retry");
        runner.close();frame.unchangedA();
    }
    static void carryUnsafeCases(File parent)throws Exception{
        for(String kind:new String[]{"symlink","hardlink","fifo","many","journal"}) {
            Frame frame=new Frame(parent,"carry-unsafe-"+kind,true);frame.initialize(1);File sync=frame.sync(1);
            File outside=new File(frame.app,"outside");write(outside,"outside exact");
            if(kind.equals("symlink"))Files.createSymbolicLink(new File(sync,"carry-link").toPath(),outside.toPath());
            else if(kind.equals("hardlink"))Files.createLink(new File(sync,"carry-hard.json").toPath(),outside.toPath());
            else if(kind.equals("fifo"))check(new ProcessBuilder("mkfifo",new File(sync,"carry-pipe").getPath()).start().waitFor()==0,"fixture creates a special carry entry");
            else if(kind.equals("many")) { for(int i=0;i<=PurpleSyncRecoveryModel.Carry.LIMIT;++i)write(new File(sync,"carry-"+i),"opaque"); }
            else { write(new File(sync,"carry.json"),"{}");write(new File(sync,"start-again.json"),"{}"); }
            java.util.TreeSet<String> before=new java.util.TreeSet<>(Arrays.asList(PurpleSyncRecoveryFiles.names(sync)));before.remove("lock");
            PurpleSyncRunner runner=frame.env(1).runner();PurpleSyncRunner.Check shown=fresh(runner);
            check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.Held&&shown.carryNotice==PurpleSyncRunner.CarryNotice.Unsafe,"unsafe carry holds with a notice: "+kind);
            check(runner.setAsideOld(shown,value->{})==PurpleSyncRunner.Start.Stale,"no set-aside is offered for unsafe carry: "+kind);
            PurpleSyncRecoveryModel.Offer model=offer(frame,1,null,PurpleSyncRecoveryFiles.NATIVE_MOVES);
            check(model!=null&&model.carry!=null&&!model.carry.safe&&!model.model.setAsideCarry(model),"the model refuses unsafe carry: "+kind);
            java.util.TreeSet<String> after=new java.util.TreeSet<>(Arrays.asList(PurpleSyncRecoveryFiles.names(sync)));after.remove("lock");
            check(after.equals(before)&&archives(sync).isEmpty(),"unsafe carry moves nothing: "+kind);
            check(Files.readString(outside.toPath()).equals("outside exact"),"outside data is unchanged: "+kind);
            runner.close();frame.unchangedA();
        }
    }
    static void carryReplacementCases(File parent)throws Exception{
        Map<String,Change> before=new LinkedHashMap<>();
        before.put("entry",(frame,sync)->{File entry=new File(sync,"carry.json");Files.delete(entry.toPath());write(entry,new byte[]{'{',(byte)0xff});});
        before.put("new-carry",(frame,sync)->write(new File(sync,"carry-new"),"new"));
        before.put("new-name",(frame,sync)->write(new File(sync,"extra.json"),"extra"));
        before.put("state",(frame,sync)->{File state=new File(sync,"state.json");byte[] bytes=Files.readAllBytes(state.toPath());Files.delete(state.toPath());write(state,bytes);});
        before.put("chat",(frame,sync)->{File chat=new File(sync,"chat.json");Files.delete(chat.toPath());write(chat,"{}");});
        before.put("root",(frame,sync)->{Files.move(sync.toPath(),new File(sync.getParentFile(),"sync-moved").toPath());directory(sync);seedCarry(sync);});
        before.put("route",(frame,sync)->frame.local("replaced"));
        before.put("login",(frame,sync)->retire(1));
        before.put("preference",(frame,sync)->MessagesController.getMainSettings(1).edit().putString("purple_sync_binding_202","33333333333333333333333333333333").commit());
        before.put("journal",(frame,sync)->write(new File(sync,"start-again.json"),"{}"));
        for(Map.Entry<String,Change> item:before.entrySet()) {
            Frame frame=new Frame(parent,"carry-before-"+item.getKey(),true);frame.initialize(1);File sync=frame.sync(1);
            write(new File(sync,"chat.json"),"{}");seedCarry(sync);
            PurpleSyncRecoveryModel.Offer shown=carryOffer(frame,PurpleSyncRecoveryFiles.NATIVE_MOVES);
            item.getValue().run(frame,sync);java.util.TreeSet<String> carry=carryNames(sync);
            check(!shown.model.setAsideCarry(shown),"a change before the action refuses: "+item.getKey());
            check(archives(sync).isEmpty()&&carryNames(sync).equals(carry),"nothing moves after a change: "+item.getKey());
        }
        Map<String,Change> during=new LinkedHashMap<>();
        during.put("entry",before.get("entry"));during.put("new-name",before.get("new-name"));during.put("chat",before.get("chat"));
        during.put("route",before.get("route"));during.put("login",before.get("login"));during.put("preference",before.get("preference"));
        for(Map.Entry<String,Change> item:during.entrySet()) {
            Frame frame=new Frame(parent,"carry-during-"+item.getKey(),true);frame.initialize(1);File sync=frame.sync(1);
            write(new File(sync,"chat.json"),"{}");Map<String,String> ids=seedCarry(sync);int[] calls={0};
            Change change=item.getValue();
            PurpleSyncRecoveryModel.Offer shown=carryOffer(frame,(a,b,id,from,to)->{
                boolean moved=PurpleSyncRecoveryFiles.NATIVE_MOVES.move(a,b,id,from,to);
                if(++calls[0]==1)try{change.run(frame,sync);}catch(Exception e){throw new IllegalStateException(e);}
                return moved;
            });
            check(!shown.model.setAsideCarry(shown)&&calls[0]==1,"a change during the moves stops after the first: "+item.getKey());
            java.util.TreeSet<String> folders=archives(sync);
            check(folders.size()==1&&carryNames(new File(sync,folders.first())).size()==1&&carryNames(sync).size()==ids.size()-1,"one entry stays set aside and the rest stay in place: "+item.getKey());
        }
        Frame busy=new Frame(parent,"carry-lock-busy",true);busy.initialize(1);seedCarry(busy.sync(1));
        PurpleSyncRecoveryModel.Offer shown=carryOffer(busy,PurpleSyncRecoveryFiles.NATIVE_MOVES);
        java.util.TreeSet<String> waiting=carryNames(busy.sync(1));
        try(PurpleSyncRecoveryFiles.Lock held=new PurpleSyncRecoveryFiles.Lock(busy.sync(1),shown.carry.rootIdentity)){
            check(!shown.model.setAsideCarry(shown)&&archives(busy.sync(1)).isEmpty()&&carryNames(busy.sync(1)).equals(waiting),"a held account recovery lock refuses before any folder or move");
        }
        check(!shown.model.setAsideCarry(shown),"the refused offer stays consumed after the lock frees");
        PurpleSyncRecoveryModel.Offer again=carryOffer(busy,PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(again.model.setAsideCarry(again)&&carryNames(busy.sync(1)).isEmpty()&&archives(busy.sync(1)).size()==1,"a fresh offer succeeds once the lock frees");
        busy.unchangedA();
    }
    static void carryOtherAccountCases(File parent)throws Exception{
        Frame frame=new Frame(parent,"carry-other-account",true);frame.initialize(1);
        seedCarry(frame.sync(0));java.util.TreeSet<String> before=carryNames(frame.sync(0));
        PurpleSyncRunner b=frame.env(1).runner();PurpleSyncRunner.Check shown=fresh(b);
        check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.Held&&shown.carryNotice==PurpleSyncRunner.CarryNotice.OtherAccount,"another account's carry holds B with a notice only");
        check(shown.recovery!=null&&shown.recovery.carry==null,"B's offer names nothing outside its own folder");
        check(b.setAsideOld(shown,value->{})==PurpleSyncRunner.Start.Stale,"B cannot set aside another account's files");
        check(carryNames(frame.sync(0)).equals(before)&&archives(frame.sync(0)).isEmpty()&&archives(frame.sync(1)).isEmpty(),"no folder moves for another account");
        b.close();
        PurpleSyncRunner a=frame.env(0).runner();PurpleSyncRunner.Check own=fresh(a);
        check(own.recoveryAction==PurpleSyncRunner.RecoveryAction.SetAsideOld,"the owning account is offered its own files");
        PurpleSyncRunner.RecoveryOutcome[] result={null};
        check(a.setAsideOld(own,value->result[0]=value)==PurpleSyncRunner.Start.Started&&result[0]!=null&&result[0].ok,"the owning account sets them aside");
        check(carryNames(frame.sync(0)).isEmpty()&&archives(frame.sync(0)).size()==1&&archives(frame.sync(1)).isEmpty(),"only the owning account's folder changes");
        a.close();
        b=frame.env(1).runner();shown=fresh(b);
        check(shown.recoveryAction==PurpleSyncRunner.RecoveryAction.None&&shown.carryNotice==PurpleSyncRunner.CarryNotice.None,"B's ordinary Check resumes");
        b.close();
    }
    static void carryCases(File parent)throws Exception{
        int before=checks;
        carrySetAsideCases(parent);carryPartialCases(parent);carryUnsafeCases(parent);carryReplacementCases(parent);carryOtherAccountCases(parent);
        System.out.println("CARRY_SET_ASIDE_CHECKS="+(checks-before));
    }
    static void run(File parent)throws Exception{keyedCases(parent);rootCases(parent);archiveCases(parent);pendingRootCases(parent);archiveRunnerCases(parent);carryCases(parent);}
}
