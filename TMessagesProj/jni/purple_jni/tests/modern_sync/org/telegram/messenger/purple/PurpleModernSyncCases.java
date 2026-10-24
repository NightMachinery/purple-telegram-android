package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PurpleModernSyncCases {
    static int checks;
    static final String A="0-101",B="0-202";
    static final String TOKEN_A="11111111111111111111111111111111",TOKEN_B="22222222222222222222222222222222";
    static final byte[] GLOBAL=bytes("# A exact 日本語\nversion=1\n[sync]\nsend_after_save_p=true\n[lists.work]\ntitle=\"Work\"\nmembers=[11,22]\n[presets.work]\nchats=[\"work\"]\n");
    static final byte[] OWN=bytes("# B exact 🌻\nversion=2\n[last_seen]\nreasons_p=true\n[sync]\nsend_after_save_p=false\n");
    static final byte[] REMOTE=bytes(new String(OWN,StandardCharsets.UTF_8).replace("reasons_p=true","reasons_p=false"));
    static final PurpleGlobalChoiceFiles.Physical PHYSICAL=PurpleAccountEditorCases.PHYSICAL;
    static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);++checks;}
    static void directory(File file)throws Exception{Files.createDirectories(file.toPath());Files.setPosixFilePermissions(file.toPath(),PosixFilePermissions.fromString("rwx------"));}
    static void write(File file,byte[] bytes)throws Exception{directory(file.getParentFile());Files.write(file.toPath(),bytes);Files.setPosixFilePermissions(file.toPath(),PosixFilePermissions.fromString("rw-------"));}
    static void write(File file,String text)throws Exception{write(file,bytes(text));}
    static String device(int slot){return "fixture-device-"+slot;}
    static String install(int slot){byte[] entropy=new byte[16];Arrays.fill(entropy,(byte)(slot+1));PurpleAccountSyncCore.Result result=PurpleAccountSyncCore.formatInstallId(entropy);check(result.isValid(),"native install id");return result.id;}
    static void retire(int slot)throws Exception{
        java.lang.reflect.Field field=PurpleAccountFiles.class.getDeclaredField("lifetimes");field.setAccessible(true);
        synchronized(PurpleAccountFiles.class){++((long[])field.get(null))[slot];}
    }
    static final class Cloud implements PurpleSyncTransport {
        final long user;
        final ArrayList<byte[]> records=new ArrayList<>();
        int posts,nextId=100;
        boolean queued,holdCheck,holdPost;
        CheckDone checking;
        PostDone posting;
        byte[] posted;
        Cloud(long user){this.user=user;}
        PurpleSyncInventory inventory(){PurpleSyncInventory.Builder builder=new PurpleSyncInventory.Builder(user);int id=100;for(byte[] record:records)builder.fetched(++id,id+1000L,1700000000L,record);return builder.build(true);}
        public void check(Progress progress,CheckDone done){if(holdCheck)checking=done;else done.onCheckDone(new CheckResult(CheckStatus.Finished,inventory(),queued));}
        void finishCheck(){CheckDone done=checking;checking=null;done.onCheckDone(new CheckResult(CheckStatus.Finished,inventory(),queued));}
        public void post(byte[] staged,PostDone done){++posts;posted=staged.clone();if(holdPost){posting=done;return;}records.add(staged.clone());done.onPostDone(new PostResult(PostStatus.Confirmed,100+records.size(),staged.clone()));}
        void unknownPost(){PostDone done=posting;posting=null;done.onPostDone(new PostResult(PostStatus.OutcomeUnknown,0,null));}
        void finishPost(){PostDone done=posting;posting=null;records.add(posted.clone());done.onPostDone(new PostResult(PostStatus.Confirmed,100+records.size(),posted.clone()));}
        public void cancel(){checking=null;posting=null;}
        void remote(byte[] text,String space,int sequence,byte[]... parents){
            PurpleAccountSyncCore.Result built=PurpleAccountSyncCore.buildConfigRecord(text,parents,space,
                    install(2),device(2),"Desktop","fixture",sequence,1700000000L+sequence);
            PurpleModernSyncCases.check(built.isValid()&&built.record!=null,"genuine remote record build");records.add(built.record);
        }
    }
    static final class Frame {
        final File app,root;
        String global=A;
        long role;
        final int[] refresh=new int[2];
        final LinkedHashMap<File,byte[]> aBytes=new LinkedHashMap<>();
        final LinkedHashMap<File,String> aNames=new LinkedHashMap<>();
        final Cloud[] clouds={new Cloud(101),new Cloud(202)};
        Frame(File parent,String name,boolean own)throws Exception{
            app=new File(parent,name);check(app.mkdir(),"exclusive modern fixture");directory(app);root=new File(app,"purple");directory(root);directory(new File(root,"accounts"));
            ApplicationLoader.files=app;MessagesController.reset();
            for(int i=0;i<2;++i){UserConfig.getInstance(i).active=true;UserConfig.getInstance(i).user=i==0?101:202;ConnectionsManager.getInstance(i).test=false;retire(i);}
            for(int i=2;i<UserConfig.MAX_ACCOUNT_COUNT;++i){UserConfig.getInstance(i).active=false;UserConfig.getInstance(i).user=0;}
            UserConfig.selectedAccount=0;
            MessagesController.getMainSettings(0).edit().putString("purple_sync_binding_101",TOKEN_A).commit();
            MessagesController.getMainSettings(1).edit().putString("purple_sync_binding_202",TOKEN_B).commit();
            write(new File(root,"accounts/root.toml"),"root_file_account=\""+A+"\"\n");local("own");
            write(settings(0),GLOBAL);write(good(0),GLOBAL);
            for(String suffix:new String[]{".bak",".import.bak"})write(new File(settings(0).getPath()+suffix),"A untouched "+suffix+"\n");
            write(new File(root,"state.toml"),"# root markers exact 日本語\nunknown=17\nselected_preset=\"work\"\n");
            if(own)write(settings(1),OWN);
            initialize(0);
            new PurpleSyncHistory(history(0),PurpleSyncHistory.NATIVE,System::currentTimeMillis).save(GLOBAL,PurpleSyncHistory.Reason.BeforeUpdate,"A existing","",null);
            snapshotA();
        }
        File settings(int slot){return new File(root,slot==0?"settings.toml":"accounts/"+B+"/settings.toml");}
        File good(int slot){return new File(settings(slot).getPath()+".good");}
        File sync(int slot){return new File(root,slot==0?"sync":"accounts/"+B+"/sync");}
        File history(int slot){return new File(root,slot==0?"sync-history":"accounts/"+B+"/sync-history");}
        String token(int slot){return MessagesController.getMainSettings(slot).getString("purple_sync_binding_"+(slot==0?101:202),null);}
        String newSpace(){PurpleAccountSyncCore.Result result=PurpleAccountSyncCore.formatTimeOrderedSpaceId(1800000000000L);check(result.isValid()&&!result.id.isEmpty(),"native space id: "+result.status+" "+result.error+" id="+result.id);return result.id;}
        byte[] initialize(int slot)throws Exception{
            PurpleAccountSyncCore.Result result=PurpleAccountSyncCore.initializeBoundLocalState(slot,install(slot),device(slot),newSpace());
            check(result.isValid()&&result.state!=null,"actual binding-backed state initialization: "+result.status+" "+result.error);
            PurpleAccountSyncStore store=new PurpleAccountSyncStore(sync(slot),PurpleAccountSyncStore.NATIVE,PHYSICAL,PurpleEditorDirectories.NATIVE);
            try{check(store.open(true,slot,device(slot)).status==PurpleAccountSyncStore.Status.Uninitialized,"new store opens uninitialized");check(store.initialize(result.state).status==PurpleAccountSyncStore.Status.Ready,"real native-bound store initialization");}finally{store.close();}
            return result.state;
        }
        byte[] state(int slot)throws Exception{return Files.readAllBytes(new File(sync(slot),"state.json").toPath());}
        String space(int slot)throws Exception{return PurpleAccountSyncCore.inspectState(state(slot)).space;}
        void local(String mode)throws Exception{write(new File(root,"local.toml"),"root_file_account=\""+A+"\"\nglobal_account=\""+global+"\"\n[account_work_mode]\n\""+B+"\"=\""+mode+"\"\n");}
        void snapshotA()throws Exception{
            aBytes.clear();aNames.clear();
            for(File file:new File[]{settings(0),good(0),new File(settings(0).getPath()+".bak"),new File(settings(0).getPath()+".import.bak"),new File(root,"state.toml")})aBytes.put(file,Files.readAllBytes(file.toPath()));
            for(File dir:new File[]{sync(0),history(0)})try(java.util.stream.Stream<Path> paths=Files.walk(dir.toPath())){for(Path path:(Iterable<Path>)paths::iterator)if(Files.isRegularFile(path))aBytes.put(path.toFile(),Files.readAllBytes(path));}
            for(File dir:new File[]{sync(0),history(0)})aNames.put(dir,Arrays.toString(PurpleSyncRecoveryFiles.names(dir)));
        }
        void unchangedA()throws Exception{for(Map.Entry<File,byte[]> entry:aBytes.entrySet())check(Arrays.equals(entry.getValue(),Files.readAllBytes(entry.getKey().toPath())),"B operation retains A exact bytes: "+entry.getKey().getName());for(Map.Entry<File,String> entry:aNames.entrySet())check(entry.getValue().equals(Arrays.toString(PurpleSyncRecoveryFiles.names(entry.getKey()))),"B operation retains A directory entries");check(TOKEN_A.equals(token(0)),"B operation retains A preference token");}
        Env env(int slot){return new Env(this,slot);}
    }
    static final class Env implements PurpleSyncRunner.Env {
        final Frame frame;
        final int slot;
        final PurpleAccountFiles.Identity identity;
        Env(Frame frame,int slot){this.frame=frame;this.slot=slot;identity=PurpleAccountFiles.capture(slot);}
        public boolean current(int account,long user){return account==slot&&identity!=null&&identity.userId==user&&identity.current();}
        public String operationKey(int account,long user){return identity.key+"\n"+frame.sync(slot).getPath();}
        public String device(){return PurpleModernSyncCases.device(slot);}
        public long serverTimeMillis(int account){return 1800000000000L;}
        public boolean hasState(){return PurpleAccountSyncStore.hasState(frame.sync(slot));}
        public PurpleAccountSyncStore store(){return new PurpleAccountSyncStore(frame.sync(slot),PurpleAccountSyncStore.NATIVE,PHYSICAL,PurpleEditorDirectories.NATIVE);}
        public PurpleSyncHistory history(){return new PurpleSyncHistory(frame.history(slot),PurpleSyncHistory.NATIVE,System::currentTimeMillis);}
        public PurpleSyncSettingsFile.Contents readSettings(){
            PurpleSyncSettingsFile.Contents read=PurpleSyncSettingsFile.read(frame.settings(slot),frame.good(slot));
            try{
                String key=identity.key;String global=frame.global;File globalFile=global.equals(A)?frame.settings(0):frame.settings(1);
                ArrayList<File> authorities=new ArrayList<>(Arrays.asList(frame.good(slot),frame.settings(0),frame.good(0),frame.settings(1),frame.good(1)));
                int opening=frame.refresh[slot];
                PurpleAccountEditorModel model=new PurpleAccountEditorModel(frame.root,frame.settings(slot),new File(frame.settings(slot).getPath()+".bak"),A,global,key,authorities,PHYSICAL,new PurpleAccountEditorModel.Context(){
                    public boolean current(boolean refreshed){return Env.this.current(slot,identity.userId)&&frame.refresh[slot]==opening+(refreshed?1:0);}
                    public void refresh(){++frame.refresh[slot];}
                    public void notifyChanged(){}
                },PurpleEditorDirectories.NATIVE,true);
                byte[] local=Files.readAllBytes(new File(frame.root,"local.toml").toPath());
                String[] accounts={global.equals(A)?B:A};byte[][] files={readMaybe(global.equals(A)?frame.settings(1):frame.settings(0))};
                int[] modes={PurpleRoutedWriteModel.modeNative(local,accounts[0],global)};
                PurpleConfigImpact.Input input=new PurpleConfigImpact.Input(read.status==PurpleSyncSettingsFile.Status.Absent?null:read.bytes,key.equals(global),PurpleRoutedWriteModel.modeNative(local,key,global),readMaybe(globalFile),accounts,files,modes);
                ArrayList<File> effectFiles=new ArrayList<>(Arrays.asList(new File(frame.root,"local.toml"),new File(frame.root,"accounts/root.toml")));
                for(File file:authorities)if(!file.equals(frame.settings(slot))&&!file.equals(frame.good(slot))&&!effectFiles.contains(file))effectFiles.add(file);
                long role=frame.role;
                PurpleSyncImpactGuard effects=new PurpleSyncImpactGuard(frame.root,effectFiles,frame.settings(slot).getParentFile(),PHYSICAL,()->Env.this.current(slot,identity.userId)&&role==frame.role);
                return new PurpleSyncSettingsFile.Contents(read.status,read.bytes,read.usingLastGood,new PurpleSyncMutation(model,input,()->Env.this.current(slot,identity.userId),effects));
            }catch(Exception e){return read;}
        }
        public PurpleSyncSettingsFile.WriteResult replaceSettings(PurpleSyncSettingsFile.Contents expected,byte[] text,String reason,boolean imported){
            if(expected==null||expected.mutation==null||!expected.current()||!readSettings().sameAs(expected))return new PurpleSyncSettingsFile.WriteResult(PurpleSyncSettingsFile.WriteStatus.Changed,null);
            boolean written=expected.mutation.replace(text,imported);
            if(written&&imported)try{
                File marker=new File(frame.root,slot==0?"state.toml":"accounts/"+B+"/send-state.toml");
                PurpleGlobalChoiceFiles.Snapshot old=new PurpleGlobalChoiceFiles.Snapshot(marker,false,PurpleGlobalChoiceFiles.LIMIT,PHYSICAL);
                String noted=PurpleLegacySendState.noteNative(old.bytes,text,true);
                PurpleGlobalChoiceFiles.Publication publication=PurpleGlobalChoiceFiles.replace(marker,bytes(noted),PHYSICAL,()->current(slot,identity.userId),PurpleAppWideRemovalModel::ownedTemporary);
                check(publication.changed&&publication.local!=null,"actual native import marker publication");
            }catch(Exception e){throw new AssertionError(e);}
            PurpleSyncSettingsFile.Contents back=readSettings();
            return new PurpleSyncSettingsFile.WriteResult(written&&Arrays.equals(back.bytes,text)?PurpleSyncSettingsFile.WriteStatus.Written:PurpleSyncSettingsFile.WriteStatus.WriteFailed,back);
        }
        public boolean requiresRecovery(){return true;}
        public boolean sendQueued(){return frame.clouds[slot].queued;}
        public PurpleSyncRecoveryModel.Offer recovery(PurpleSyncInventory inventory,PurpleSyncSettingsFile.Contents local){
            try{
                PurpleAccountBinding.Binding binding=PurpleAccountBinding.readForCheck(slot);if(!binding.isValid())return null;
                PurpleSyncRecoveryModel.Preferences preferences=PurpleSyncRecovery.capturePreferences(slot,binding,()->current(slot,identity.userId));
                return new PurpleSyncRecoveryModel(frame.root,identity.key,A,frame.global,preferences,()->current(slot,identity.userId),PHYSICAL,PurpleEditorDirectories.NATIVE,PurpleSyncRecoveryFiles.NATIVE_MOVES).check(inventory,local);
            }catch(Exception e){throw new AssertionError(e);}
        }
        PurpleSyncRunner runner(){return new PurpleSyncRunner(slot,identity.userId,frame.clouds[slot],this,Runnable::run,Runnable::run);}
    }
    static byte[] readMaybe(File file)throws Exception{return file.isFile()?Files.readAllBytes(file.toPath()):null;}
    static PurpleSyncRunner.Check fresh(PurpleSyncRunner runner){PurpleSyncRunner.CheckOutcome[] outcome={null};check(runner.check(null,value->outcome[0]=value)==PurpleSyncRunner.Start.Started,"runner starts independent Check");check(outcome[0]!=null&&outcome[0].status==PurpleSyncRunner.CheckStatus.Finished,"runner completes Check");return outcome[0].check;}
    static PurpleSyncRunner.ApplyOutcome apply(PurpleSyncRunner runner,PurpleSyncRunner.Check shown,String key){PurpleSyncRunner.ApplyOutcome[] result={null};check(runner.apply(shown,key,value->result[0]=value)==PurpleSyncRunner.Start.Started,"captured review starts apply");check(result[0]!=null,"apply callback");return result[0];}
    static PurpleSyncCore.Choice remote(PurpleSyncRunner.Check check){for(PurpleSyncCore.Choice choice:check.review.choices)if(choice.key!=null)return choice;throw new AssertionError("no remote choice: "+check.review.status+" "+check.review.verdict);}
    static PurpleSyncRunner.Preview preview(PurpleSyncRunner runner,String id){PurpleSyncRunner.Preview[] out={null};check(runner.preview(id,value->out[0]=value),"History preview starts");check(out[0]!=null,"History preview exists");return out[0];}
    static void flowCases(File parent)throws Exception{
        Frame frame=new Frame(parent,"apply-restore-undo",true);frame.initialize(1);Env env=frame.env(1);PurpleSyncRunner runner=env.runner();
        frame.clouds[1].remote(REMOTE,frame.space(1),1);PurpleSyncRunner.Check shown=fresh(runner);PurpleSyncCore.Choice choice=remote(shown);
        check(choice.impactReady,"schema2 remote choice carries genuine impact");
        PurpleSyncRunner.ApplyOutcome applied=apply(runner,shown,choice.key);
        check(applied.result.status==PurpleSyncCore.ApplyStatus.Applied&&applied.result.wroteFile,"B genuine modern apply writes");
        check(Arrays.equals(REMOTE,Files.readAllBytes(frame.settings(1).toPath())),"B applied exact remote bytes");
        check(Files.readString(new File(frame.root,"accounts/"+B+"/send-state.toml").toPath()).contains("last_imported_fingerprint"),"B records its import marker");
        check(frame.clouds[1].posts==0,"remote apply queues no echo");frame.unchangedA();
        PurpleSyncRunner.UndoOffer undo=runner.undoOffer();check(undo!=null,"B Undo offered");PurpleSyncRunner.Preview undoPreview=preview(runner,undo.historyId);
        check(undoPreview.impactReady,"Undo has genuine scope impact");PurpleSyncRunner.RestoreOutcome[] undone={null};
        check(runner.undo(undo,undoPreview,value->undone[0]=value)==PurpleSyncRunner.Start.Started,"B undo starts");
        check(undone[0].result.status==PurpleSyncCore.RestoreStatus.Restored&&Arrays.equals(OWN,Files.readAllBytes(frame.settings(1).toPath())),"B undo restores exact original");
        check(frame.clouds[1].posts==0&&runner.undoOffer()==null,"Undo neither sends nor leaves consumed offer");frame.unchangedA();
        PurpleSyncRunner.Preview restorePreview=preview(runner,applied.result.historyId);PurpleSyncRunner.RestoreOutcome[] restored={null};
        check(runner.restore(restorePreview,value->restored[0]=value)==PurpleSyncRunner.Start.Started,"B history restore starts");
        check(restored[0].result.status==PurpleSyncCore.RestoreStatus.Unchanged,"same History restore is unchanged");
        frame.unchangedA();runner.close();
    }
    static void missingCases(File parent)throws Exception{
        Frame frame=new Frame(parent,"first-file",false);Env env=frame.env(1);String space=frame.newSpace();frame.clouds[1].remote(REMOTE,space,1);
        PurpleSyncRunner runner=env.runner();PurpleSyncRunner.Check shown=fresh(runner);check(shown.local.status==PurpleSyncSettingsFile.Status.Absent&&!shown.local.usingLastGood,"missing B is explicit absent, not A fallback");
        PurpleSyncRunner.ApplyOutcome outcome=apply(runner,shown,remote(shown).key);
        check(outcome.result.status==PurpleSyncCore.ApplyStatus.Applied&&outcome.result.joined&&outcome.result.wroteFile,"Join safely creates first B file with owned directory receipts");
        check(Arrays.equals(REMOTE,Files.readAllBytes(frame.settings(1).toPath())),"first B file exact");
        check(runner.undoOffer()==null,"absent prior primary is retained in History but cannot be undone to deletion");
        List<PurpleSyncHistory.Entry> entries=env.history().list();check(entries.size()==1&&!entries.get(0).existed,"History records original absence");frame.unchangedA();runner.close();
        for(boolean missing:new boolean[]{true,false})for(boolean validGood:new boolean[]{true,false}){
            Frame fallback=new Frame(parent,"fallback-"+missing+"-"+validGood,!missing);Env fallbackEnv=fallback.env(1);
            if(!missing)write(fallback.settings(1),"version=[\n");write(fallback.good(1),validGood?OWN:bytes("version=[\n"));
            PurpleSyncSettingsFile.Contents contents=fallbackEnv.readSettings();
            check(contents.usingLastGood==validGood,"per-file good validity controls fallback");
            PurpleSyncRunner held=fallbackEnv.runner();fallback.clouds[1].remote(REMOTE,fallback.newSpace(),1);PurpleSyncRunner.Check review=fresh(held);
            check(review.review.status!=PurpleSyncCore.ReviewStatus.Ready||review.review.choices.stream().noneMatch(c->c.impactReady),"fallback or bad current never authorizes modern replacement");
            check(!fallback.settings(1).exists()==missing,"primary absence retained");fallback.unchangedA();held.close();
        }
    }
    public static int run(File parent)throws Exception{
        checks=0;PurpleSyncAutoCases.run(parent);flowCases(parent);missingCases(parent);PurpleSyncRecoveryCases.run(parent);PurpleSyncLifecycleCases.run(parent);PurpleSyncReviewFixCases.run(parent);return checks;}
}
