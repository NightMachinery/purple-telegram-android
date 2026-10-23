package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Map;

public final class PurpleRoutedWriteCases {
    private static int checks;
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); ++checks; }
    private static void write(File file, String value) throws Exception { Files.createDirectories(file.toPath().getParent()); Files.write(file.toPath(), bytes(value)); }
    private static final String GLOBAL = "# keep global\nversion=1\n[premium]\nenabled_p=true\n[lists.work]\ntitle=\"Work\"\nmembers=[\n 11, # A-only name\n 22, # Shared name\n]\n[lists.other]\nmembers=[77]\n";
    private static final PurpleGlobalChoiceFiles.Physical PHYSICAL = new PurpleGlobalChoiceFiles.Physical() {
        public PurpleGlobalChoiceFiles.Mark mark(File file, boolean directory) throws Exception {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return new PurpleGlobalChoiceFiles.Mark("missing", "", 0);
            BasicFileAttributes a = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String,Object> unix = Files.readAttributes(file.toPath(), "unix:dev,ino,mode,nlink,ctime", LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || (directory ? !a.isDirectory() : !a.isRegularFile())
                    || !directory && ((Number)unix.get("nlink")).intValue() != 1) throw new IllegalStateException("unsafe fixture");
            return new PurpleGlobalChoiceFiles.Mark(unix.get("dev")+":"+unix.get("ino"), directory ? "" : a.size()+":"+a.lastModifiedTime()+":"+unix.get("ctime"), ((Number)unix.get("mode")).intValue());
        }
        public boolean write(File file, PurpleGlobalChoiceFiles.Mark owned, byte[] value) {
            return PurpleGlobalChoiceFiles.writeNative(file.getPath(), owned.identity, value);
        }
    };
    private static final class Fixture implements PurpleAccountEditorModel.Context {
        final File root, own, global, local;
        boolean alive=true, stopAfterWrite, stopOnNotify;
        int refreshed, notified;
        PurpleRoutedWriteModel model;
        Fixture(File parent, String name, String globalKey, String mode, String ownText) throws Exception {
            root=new File(parent,name);check(root.mkdir(),"exclusive fixture");
            own=new File(root,"accounts/0-202/settings.toml");
            global=new File(root,globalKey.equals("0-101")?"settings.toml":"accounts/0-303/settings.toml");
            local=new File(root,"local.toml");
            write(global,GLOBAL);
            write(new File(root,"accounts/root.toml"),"root_file_account=\"0-101\"\n");
            write(local,"root_file_account=\"0-101\"\nglobal_account=\""+globalKey+"\"\n[account_work_mode]\n\"0-202\"=\""+mode+"\"\n");
            if(ownText!=null)write(own,ownText);
        }
        void open(String globalKey, boolean appWide) throws Exception {
            model=new PurpleRoutedWriteModel(root,own,global,"0-101",globalKey,"0-202",appWide,PHYSICAL,this,PurpleEditorDirectories.NATIVE);
        }
        public boolean current(boolean after) {
            try { if(stopAfterWrite && model!=null && Files.exists(model.editor.backup.toPath()))alive=false; }
            catch(Exception e){throw new IllegalStateException(e);}
            return alive && refreshed==(after?1:0);
        }
        public void refresh(){++refreshed;}
        public void notifyChanged(){++notified;if(stopOnNotify)alive=false;}
    }
    private static void toggle(Fixture f,String list,long peer,boolean add,String title) throws Exception {
        JSONObject result=f.model.toggle(list,peer,add,title);
        check(result!=null&&result.optBoolean("ok"),"actual routed list plan succeeds");
        PurpleAccountEditorModel.Result saved=f.model.save(result.getString("text"));
        check(saved.ok&&saved.changed,"actual guarded list publication succeeds");
    }
    public static int run(File parent) throws Exception {
        checks=0;
        for(String globalKey:new String[]{"0-101","0-303"}) {
            Fixture wide=new Fixture(parent,"wide-"+globalKey,globalKey,"own","version=2\n[last_seen]\nreasons_p=false\n");wide.open(globalKey,true);
            byte[] untouched=Files.readAllBytes(wide.own.toPath());
            check(wide.model.appWide&&wide.model.editor.target.equals(wide.global),"app-wide file never follows selected own account");
            PurpleCore.SpliceResult splice=PurpleCore.setTableBool(wide.model.bytes(),"premium","enabled_p",false);
            check(splice.ok()&&wide.model.save(splice.text).ok,"real Premium splice and production file publication");
            check(Arrays.equals(untouched,Files.readAllBytes(wide.own.toPath())),"other account unchanged byte for byte");
            check(Files.readString(wide.global.toPath()).contains("enabled_p=false"),"captured root or non-root global updated");
            check(Files.readString(new File(wide.global.getPath()+".bak").toPath()).equals(GLOBAL),"app-wide exact backup");
        }
        Fixture schedule=new Fixture(parent,"wide-schedule","0-303","own","version=2\n");schedule.open("0-303",true);
        PurpleCore.SpliceResult ruleset=PurpleCore.addRuleset(schedule.model.bytes(),"Week","any","enabled");
        check(ruleset.ok()&&schedule.model.save(ruleset.text).ok,"real schedule writer targets non-root app-wide file");
        check(Files.readString(schedule.global.toPath()).contains("[[schedule.rulesets]]")&&Files.readString(schedule.own.toPath()).equals("version=2\n"),"schedule never lands in selected account file");
        Fixture budget=new Fixture(parent,"wide-budget","0-303","own","version=2\n");budget.open("0-303",true);
        PurpleCore.SpliceResult appended=PurpleCore.appendBudget(budget.model.bytes(),"all",3600,"soft",300,1);
        check(appended.ok()&&budget.model.save(appended.text).ok,"real screen-time budget writer targets non-root app-wide file");
        check(Files.readString(budget.global.toPath()).contains("[[screen_time.budgets]]")&&Files.readString(budget.own.toPath()).equals("version=2\n"),"budget leaves selected account bytes unchanged");
        Fixture absentGlobal=new Fixture(parent,"first-offline-global","0-303","own","version=2\n");
        Files.delete(absentGlobal.global.toPath());Files.delete(absentGlobal.global.getParentFile().toPath());absentGlobal.open("0-303",true);
        check(!absentGlobal.global.getParentFile().exists(),"opening absent offline app-wide file creates nothing");
        PurpleCore.SpliceResult enabled=PurpleCore.setTableBool(absentGlobal.model.bytes(),"premium","enabled_p",true);
        PurpleAccountEditorModel.Result first=absentGlobal.model.save(enabled.text);
        check(enabled.ok()&&first.ok&&first.directoriesCreated==1,"first explicit offline app-wide edit uses native creation receipt");
        check(Files.readString(absentGlobal.global.toPath()).contains("version = 2")&&!new File(absentGlobal.global.getPath()+".bak").exists(),"new app-wide file remains layered and has no inherited backup");
        Fixture missingRoot=new Fixture(parent,"missing-root","0-101","own",null);Files.delete(missingRoot.global.toPath());
        boolean held=false;try{missingRoot.open("0-101",true);}catch(Exception e){held=true;}
        check(held&&!missingRoot.global.exists(),"missing root file stays recovery");
        Fixture good=new Fixture(parent,"missing-with-good","0-101","own",null);write(new File(good.own.getPath()+".good"),"version=2\n");
        held=false;try{good.open("0-101",false);}catch(Exception e){held=true;}
        check(held&&!good.own.exists(),"missing own primary with good stays recovery");
        Fixture fork=new Fixture(parent,"inherited-missing","0-101","own",null);fork.open("0-101",false);
        check(!fork.own.getParentFile().exists(),"menu open does not create account directory");
        check(fork.model.lists(11).getJSONArray("lists").getJSONObject(0).getBoolean("member"),"checkmark reads inherited members");
        toggle(fork,"work",33,true,"B chat");
        String text=Files.readString(fork.own.toPath());
        check(text.contains("version = 2")&&text.contains("[lists.work]")&&!text.contains("[premium]")&&!text.contains("[lists.other]")&&!text.contains("title="),"first fork creates only layered member override");
        check(text.contains("A-only name")&&text.contains("Shared name")&&text.contains("B chat"),"copied comments preserved without other-session lookup");
        check(Files.readString(fork.global.toPath()).equals(GLOBAL),"fork leaves global bytes unchanged");
        check(!new File(fork.own.getPath()+".bak").exists(),"first write makes no backup from inherited bytes");
        Fixture own=new Fixture(parent,"existing-own","0-101","own","# local\nversion=2\n[lists.work]\nmembers=[\n 55, # own name\n]\n");own.open("0-101",false);
        JSONObject list=own.model.lists(55).getJSONArray("lists").getJSONObject(0);
        check(list.getBoolean("member")&&list.getJSONArray("members").length()==1,"own members replace inherited array");
        toggle(own,"work",66,true,"Own new");
        text=Files.readString(own.own.toPath());
        check(text.contains("own name")&&text.contains("Own new")&&!text.contains("A-only"),"own comments never copied from inherited array");
        own.refreshed=0;own.open("0-101",false);toggle(own,"work",66,false,"");
        check(!Files.readString(own.own.toPath()).contains("Own new")&&Files.readString(own.own.toPath()).contains("own name"),"second tick recaptures the committed own file without stale inherited members");
        Fixture follow=new Fixture(parent,"follow","0-101","follow","version=2\n[lists.work]\nmembers=[55]\n");follow.open("0-101",false);
        byte[] before=Files.readAllBytes(follow.own.toPath());
        check(follow.model.appWide&&follow.model.lists(11).getJSONArray("lists").getJSONObject(0).getBoolean("member"),"follow checkmarks and target are global");
        toggle(follow,"work",11,false,"");
        check(Arrays.equals(before,Files.readAllBytes(follow.own.toPath())),"follow leaves own fork untouched");
        check(!Files.readString(follow.global.toPath()).contains("A-only name"),"follow removes global member and its own comment");
        Fixture stock=new Fixture(parent,"stock","0-101","stock","version=2\n[lists.work]\nmembers=[]\n");stock.open("0-101",false);
        check(!stock.model.appWide&&!stock.model.lists(11).getJSONArray("lists").getJSONObject(0).getBoolean("member"),"stock menu reads and edits retained own membership");
        toggle(stock,"work",11,true,"Stock own");
        check(Files.readString(stock.global.toPath()).equals(GLOBAL),"stock does not mutate global");
        Fixture empty=new Fixture(parent,"explicit-empty","0-101","own","version=1\n[lists.work]\ntitle=\"Whole file definition\"\nmembers=[55]\n");empty.open("0-101",false);toggle(empty,"work",55,false,"");
        text=Files.readString(empty.own.toPath());
        check(text.contains("members")&&text.contains("Whole file definition"),"untick keeps explicit empty members and never uses Reset on a defined list");
        Fixture stale=new Fixture(parent,"stale-choice","0-101","own",null);stale.open("0-101",false);write(stale.local,"root_file_account=\"0-101\"\nglobal_account=\"0-202\"\n");
        check(stale.model.toggle("work",33,true,"x")==null&&!stale.own.exists(),"role input change blocks retained list intent without creation");
        Fixture retired=new Fixture(parent,"retired-origin","0-101","own",null);retired.open("0-101",false);retired.alive=false;
        check(retired.model.toggle("work",33,true,"x")==null&&!retired.own.exists(),"retired account lifetime blocks mutation");
        Fixture late=new Fixture(parent,"retire-after-backup","0-101","own","version=2\n");late.open("0-101",true);late.stopAfterWrite=true;
        PurpleCore.SpliceResult splice=PurpleCore.setTableBool(late.model.bytes(),"premium","enabled_p",false);
        PurpleAccountEditorModel.Result result=late.model.save(splice.text);
        check(result.backupChanged&&!result.changed&&late.refreshed==0&&Files.readString(late.global.toPath()).equals(GLOBAL),"retirement after backup refuses final publication");
        Fixture unsafe=new Fixture(parent,"unsafe-target","0-101","own",null);unsafe.open("0-101",false);
        Files.createDirectories(unsafe.own.getParentFile().toPath());Files.createSymbolicLink(unsafe.own.toPath(),unsafe.global.toPath());
        check(unsafe.model.toggle("work",33,true,"x")==null&&Files.readString(unsafe.global.toPath()).equals(GLOBAL),"foreign symlink is never adopted");
        check(PurpleRoutedWriteModel.modeNative(bytes("[account_work_mode]\n\"0-202\"=\"follow\"\n"),"0-202","0-101")==1,"actual shared local choice mode");
        check(PurpleRoutedWriteModel.modeNative(bytes("[account_work_mode]\n\"0-101\"=\"follow\"\n"),"0-101","0-101")==0,"global follow resolves to own");
        reviewRegressions(parent);
        sliceTwo(parent);
        publicationReview(parent);
        return checks;
    }
    private static void reviewRegressions(File parent) throws Exception {
        PurpleCore.SpliceResult rule = PurpleCore.appendScheduleRule(bytes("version=1\n"),
                "", true, new int[]{1}, 540, 1020, "normal");
        check(rule.ok(), "review probe matching rule fixture parses through genuine JNI");
        Fixture staleDelete = new Fixture(parent, "review-stale-delete", "0-101", "own", "version=2\n");
        File b = new File(staleDelete.root, "accounts/0-303/settings.toml");
        write(staleDelete.global, rule.text); write(b, rule.text); staleDelete.open("0-101", true);
        PurpleRoutedWriteModel opening = staleDelete.model;
        check(opening.current(), "opening rule editor has a current A route");
        write(staleDelete.local, "root_file_account=\"0-101\"\nglobal_account=\"0-303\"\n");
        check(!opening.current(), "Delete must refuse the opening writer before showing confirmation after A-to-B switch");
        PurpleCore.SpliceResult wouldDeleteB = PurpleCore.removeScheduleRule(Files.readAllBytes(b.toPath()), "", 0, 540, 1020, "normal");
        check(wouldDeleteB.ok() && wouldDeleteB.changed, "matching native rule fingerprint alone would permit deleting B");
        PurpleCore.SpliceResult removeA = PurpleCore.removeScheduleRule(opening.bytes(), "", 0, 540, 1020, "normal");
        PurpleAccountEditorModel.Result refused = opening.save(removeA.text);
        check(!refused.ok && !refused.changed && !refused.backupChanged, "retained confirmation cannot replace its original route with a fresh B capture");
        check(Files.readString(staleDelete.global.toPath()).equals(rule.text) && Files.readString(b.toPath()).equals(rule.text),
                "review stale-delete repro leaves both A and B byte-for-byte unchanged");
        check(!new File(staleDelete.global.getPath() + ".bak").exists() && !new File(b.getPath() + ".bak").exists(),
                "stale Delete creates no backup in either account");
        Fixture delete = new Fixture(parent, "review-current-delete", "0-101", "own", "version=2\n");
        b = new File(delete.root, "accounts/0-303/settings.toml"); write(delete.global, rule.text); write(b, rule.text); delete.open("0-101", true);
        PurpleCore.SpliceResult removed = PurpleCore.removeScheduleRule(delete.model.bytes(), "", 0, 540, 1020, "normal");
        check(removed.ok() && delete.model.save(removed.text).ok, "unchanged opening route still permits the confirmed Delete");
        check(!Files.readString(delete.global.toPath()).contains("[[schedule.rules]]") && Files.readString(b.toPath()).equals(rule.text),
                "positive Delete edits only opening A and preserves matching B");
        for (String stage : new String[]{"input-save", "countdown-complete"}) {
            Fixture delay = new Fixture(parent, "review-delay-" + stage, "0-101", "own", "version=2\n");
            String aText = "version=1\n[peek]\nstart_delay_seconds=3\n";
            String bText = "version=1\n[peek]\nstart_delay_seconds=30\n";
            b = new File(delay.root, "accounts/0-303/settings.toml"); write(delay.global, aText); write(b, bText); delay.open("0-101", true);
            opening = delay.model;
            byte[] capturedSettings = opening.bytes();
            check(PurplePeekSnapshot.waitSeconds(3, 9) == 9, "delay countdown uses max of the opening delay and requested delay");
            write(delay.local, "root_file_account=\"0-101\"\nglobal_account=\"0-303\"\n");
            check(!opening.current(), "retained Peek writer refuses app-wide switch at " + stage);
            PurpleCore.SpliceResult changed = PurpleCore.setPeekStartDelay(capturedSettings, 9);
            check(changed.ok() && !opening.save(changed.text).changed, "opening Peek settings cannot be recaptured at " + stage);
            check(Files.readString(delay.global.toPath()).equals(aText) && Files.readString(b.toPath()).equals(bText),
                    "stale Peek " + stage + " leaves both account files unchanged");
            check(!new File(delay.global.getPath() + ".bak").exists() && !new File(b.getPath() + ".bak").exists(),
                    "stale Peek " + stage + " creates no backup");
        }
        Fixture current = new Fixture(parent, "review-current-delay", "0-101", "own", "version=2\n");
        write(current.global, "version=1\n[peek]\nstart_delay_seconds=3\n"); current.open("0-101", true);
        PurpleCore.SpliceResult changed = PurpleCore.setPeekStartDelay(current.model.bytes(), 9);
        check(changed.ok() && current.model.save(changed.text).ok, "unchanged opening Peek context still writes after the countdown");
        check(Files.readString(current.global.toPath()).contains("start_delay_seconds=9"), "positive Peek change publishes the requested delay");
    }
    private PurpleRoutedWriteCases() { }
    private static JSONObject facts(String before, String own, String global) throws Exception {
        return new JSONObject(PurpleListDiagnosticsModel.factsNative(before == null ? null : bytes(before), bytes(own), bytes(global)));
    }
    private static long runtime(String mode, String own, String global, String globalKey) throws Exception {
        JSONObject result = new JSONObject(PurpleWorkModeModel.createNative(bytes("[account_work_mode]\n\"0-202\"=\""+mode+"\"\n"),
                "0-202",globalKey,bytes(global),"global",null,own==null?null:bytes(own),"own",null));
        check(result.getInt("mode")== (mode.equals("stock")?2:mode.equals("follow")&&!globalKey.equals("0-202")?1:0),"native runtime captures exact mode");
        return Long.parseLong(result.getString("handle"));
    }
    private static void sliceTwo(File parent) throws Exception {
        Fixture choices=new Fixture(parent,"mode-transitions","0-101","own","version=2\n[lists.work]\nmembers=[55]\n");
        byte[] a=Files.readAllBytes(choices.global.toPath()),b=Files.readAllBytes(choices.own.toPath());
        for(int mode:new int[]{1,2,0}) {
            choices.open("0-101",false);
            check(choices.model.setMode(mode),"guarded native local mode publication");
            check(PurpleRoutedWriteModel.modeNative(Files.readAllBytes(choices.local.toPath()),"0-202","0-101")==mode,"mode transition readback");
            check(Arrays.equals(a,Files.readAllBytes(choices.global.toPath()))&&Arrays.equals(b,Files.readAllBytes(choices.own.toPath())),"device choice never rewrites either settings file");
        }
        choices.open("0-101",false);check(!choices.model.setMode(3),"unknown choice refused");
        Fixture staleMode=new Fixture(parent,"stale-mode-picker","0-101","own","version=2\n");staleMode.open("0-101",false);
        write(staleMode.local,"root_file_account=\"0-101\"\nglobal_account=\"0-202\"\n");
        a=Files.readAllBytes(staleMode.global.toPath());b=Files.readAllBytes(staleMode.own.toPath());byte[] local=Files.readAllBytes(staleMode.local.toPath());
        check(!staleMode.model.setMode(2)&&Arrays.equals(local,Files.readAllBytes(staleMode.local.toPath())),"stale picker cannot change a new global route");
        check(Arrays.equals(a,Files.readAllBytes(staleMode.global.toPath()))&&Arrays.equals(b,Files.readAllBytes(staleMode.own.toPath())),"stale choice leaves A and B untouched");
        Fixture reset=new Fixture(parent,"delete-own","0-101","own","# header\nversion=2\n[lists.work]\n# retained table note\nmembers=[55]\n[last_seen]\nreasons_p=false\n");reset.open("0-101",false);
        JSONObject row=reset.model.lists(55).getJSONArray("lists").getJSONObject(0);
        check(row.getBoolean("ownMembers")&&row.getBoolean("deleteOwn"),"members-only v2 table is visibly own and reversible");
        JSONObject deletion=reset.model.deleteOwn("work");check(deletion!=null&&reset.model.save(deletion.getString("text")).ok,"guarded own deletion succeeds");
        String text=Files.readString(reset.own.toPath());check(text.contains("retained table note")&&text.contains("reasons_p=false")&&!text.contains("members"),"deletion preserves notes and other keys");
        check(Files.readString(reset.global.toPath()).equals(GLOBAL),"deletion never changes global bytes");
        reset.refreshed=0;reset.open("0-101",false);
        check(reset.model.lists(11).getJSONArray("lists").getJSONObject(0).getBoolean("member"),"deleting own members restores inherited checkmark");
        for(String definition:new String[]{"version=1\n[lists.work]\nmembers=[55]\n","[lists.work]\nmembers=[55]\n",
                "version=2\n[lists.work]\ntitle=\"Defined\"\nmembers=[55]\n","version=2\n[lists.work]\nkinds=[\"private\"]\nmembers=[55]\n",
                "version=2\n[lists.work]\nunknown=1\nmembers=[55]\n"}) {
            Fixture whole=new Fixture(parent,"delete-refused-"+checks,"0-101","own",definition);whole.open("0-101",false);
            check(!whole.model.lists(55).getJSONArray("lists").getJSONObject(0).getBoolean("deleteOwn")&&whole.model.deleteOwn("work")==null,"defined or unknown-key list never offers Reset");
            check(Files.readString(whole.own.toPath()).equals(definition)&&Files.readString(whole.global.toPath()).equals(GLOBAL),"refused Reset leaves both files unchanged");
        }
        Fixture dotted=new Fixture(parent,"delete-literal-dot","0-101","own","version=2\n[lists.\"work.dot\"]\nmembers=[55]\n[lists.work]\nmembers=[66]\n");
        write(dotted.global,GLOBAL+"\n[lists.\"work.dot\"]\nmembers=[99]\n");dotted.open("0-101",false);
        deletion=dotted.model.deleteOwn("work.dot");check(deletion!=null&&dotted.model.save(deletion.getString("text")).ok,"literal dotted table name uses quoted TOML key");
        check(Files.readString(dotted.own.toPath()).contains("members=[66]"),"literal table deletion leaves sibling members unchanged");
        Fixture staleDelete=new Fixture(parent,"stale-own-delete","0-101","own","version=2\n[lists.work]\nmembers=[55]\n");staleDelete.open("0-101",false);
        write(staleDelete.local,"root_file_account=\"0-101\"\nglobal_account=\"0-202\"\n");
        check(staleDelete.model.deleteOwn("work")==null&&Files.readString(staleDelete.own.toPath()).contains("55")&&Files.readString(staleDelete.global.toPath()).equals(GLOBAL),"delete confirmation across A-to-B switch refuses with both files intact");
        JSONObject diag=facts("version=2\n[premium]\nenabled_p=true\n[lists.work]\ntitle=\"Old\"\nmembers=[1]\n", "version=2\n[premium]\nenabled_p=false\n[lists.work]\ntitle=\"New\"\nmembers=[2,3]\n",GLOBAL);
        check(diag.optBoolean("ok")&&diag.getInt("inactiveCount")==2,"inactive app-wide key count includes title but excludes members");
        diag=facts(null,"version=2\n[lists.\"work.dot\"]\nmembers=[1,2]\n",GLOBAL);
        check(diag.getJSONArray("orphaned").getJSONObject(0).getString("name").equals("work.dot")&&diag.getJSONArray("orphaned").getJSONObject(0).getInt("count")==2,"undefined literal list warns with own member count");
        diag=facts("version=1\n[lists.work]\nmembers=[1]\n","version=1\n[lists.work]\n",GLOBAL);
        check(diag.getJSONArray("emptied").length()==1,"whole-file removed members warn about devices using its definitions");
        diag=facts("version=2\n[lists.work]\nmembers=[1]\n","version=2\n",GLOBAL);
        check(diag.getJSONArray("emptied").length()==0,"members-only Reset does not claim a global definition became empty");
        String config="version=1\n[lists.work]\ntitle=\"Work\"\nmembers=[11]\n[presets.test]\nlist_order=[{list=\"work\",show_mode=\"always\",notify_p=false,stories=\"never\"}]\n[[presets.test.views]]\nname=\"Work view\"\nlist_order=[{list=\"work\",show_mode=\"always\"}]\n";
        PurpleCore.Loaded loaded=PurpleCore.load(bytes(config),bytes("active_preset=\"test\"\n"),"fixture","android","phone");
        check(loaded.ok&&!loaded.normal,"genuine production gate resolves filtering fixture");
        String own="version=2\n[lists.work]\nmembers=[55]\n[last_seen]\nreasons_p=false\n";
        long ownHandle=runtime("own",own,config,"0-101"),followHandle=runtime("follow",own,config,"0-101"),stockHandle=runtime("stock",own,config,"0-101");
        int mine=PurpleWorkModeModel.visibleNative(ownHandle,55,PurpleCore.KIND_PRIVATE),other=PurpleWorkModeModel.visibleNative(ownHandle,11,PurpleCore.KIND_PRIVATE);
        check((mine&PurpleCore.SHOW_MASK)==PurpleCore.SHOW_ALWAYS&&(other&PurpleCore.SHOW_MASK)==PurpleCore.SHOW_NEVER,"own runtime replaces global members");
        check((mine&PurpleCore.NOTIFY_BIT)==0,"own member preserves app-wide entry mute");
        check((PurpleWorkModeModel.visibleNative(followHandle,11,0)&PurpleCore.SHOW_MASK)==PurpleCore.SHOW_ALWAYS&&(PurpleWorkModeModel.visibleNative(followHandle,55,0)&PurpleCore.SHOW_MASK)==PurpleCore.SHOW_NEVER,"follow runtime uses global members without discarding fork");
        check(PurpleWorkModeModel.visibleNative(stockHandle,55,0)==(PurpleCore.SHOW_ALWAYS|PurpleCore.NOTIFY_BIT)&&PurpleWorkModeModel.visibleUnpeekedNative(stockHandle,11,0)==16,"stock runtime is visible and audible regardless of preset");
        check((mine&(1<<8))!=0&&(other&(1<<8))==0,"extra view membership follows per-account members");
        check("Work".equals(PurpleWorkModeModel.deciderNative(ownHandle,55,0))&&PurpleWorkModeModel.deciderNative(ownHandle,11,0)==null,"verdict decider follows own snapshot");
        check(!PurpleWorkModeModel.storyShownNative(ownHandle,55,0,true,-1,false)&&PurpleWorkModeModel.storyShownNative(stockHandle,55,0,true,-1,false),"own story rule applies while stock stays stock");
        check((PurpleWorkModeModel.visibleNative(ownHandle,55,0)&PurpleCore.SHOW_MASK)==PurpleCore.SHOW_ALWAYS,"another account snapshot never overwrites own runtime");
        long fresh=runtime("own","version=2\n[lists.work]\nmembers=[77]\n",config,"0-101");
        check((PurpleWorkModeModel.visibleNative(fresh,55,0)&15)==4&&(PurpleWorkModeModel.visibleNative(ownHandle,55,0)&15)==0,"runtime settings are captured snapshots rather than live disk choice");
        PurpleWorkModeModel.retireNative(new long[]{ownHandle,followHandle,fresh});check(PurpleWorkModeModel.visibleNative(ownHandle,55,0)==4,"retired native snapshots fail closed");
        check(PurpleWorkModeModel.createNative(bytes("global_account=\"0-303\"\n"),"0-202","0-101",bytes(config),"global",null,bytes(own),"own",null)==null,"runtime rejects a local choice for another global account");
        JSONObject globalMode=new JSONObject(PurpleRoutedWriteModel.modePlanNative(bytes("root_file_account=\"0-202\"\n"),"0-202","0-202",2));
        check(globalMode.optBoolean("ok")&&PurpleRoutedWriteModel.modeNative(bytes(globalMode.getString("text")),"0-202","0-202")==2,"global account may choose stock without changing its presets");
        check(!new JSONObject(PurpleRoutedWriteModel.modePlanNative(bytes("root_file_account=\"0-202\"\n"),"0-202","0-202",1)).optBoolean("ok"),"global follow is not an offered choice");
        byte[] previousChoice=bytes("[account_work_mode]\n\"0-202\"=\"stock\"\n");
        check(Arrays.equals(previousChoice,PurpleWorkModeModel.localForReload(bytes("[broken"),false,previousChoice,"0-101")),"malformed local reload retains previous accepted device choice");
        check(Arrays.equals(previousChoice,PurpleWorkModeModel.localForReload(null,false,previousChoice,"0-101")),"unreadable local reload retains previous accepted device choice");
        check(PurpleRoutedWriteModel.modeNative(PurpleWorkModeModel.localForReload(null,true,previousChoice,"0-101"),"0-202","0-101")==0,"removing local file resets device choice to own");
    }
    private static void publicationReview(File parent) throws Exception {
        String config="version=1\n[lists.work]\nmembers=[11]\n[presets.test]\nlist_order=[{list=\"work\",show_mode=\"always\",notify_p=false,stories=\"never\"}]\n";
        String own="version=2\n[lists.work]\nmembers=[55]\n";
        PurpleCore.load(bytes(config),bytes("active_preset=\"test\"\n"),"fixture","android","phone");
        long initial=runtime("own",own,config,"0-101");
        PurpleWorkModeModel.Publication<Long> publication=new PurpleWorkModeModel.Publication<>(initial);
        long candidate=runtime("own",own,config,"0-101");
        check(publication.read(h -> PurpleWorkModeModel.visibleNative(h,11,0))==4,"replacement build leaves published exclusion intact");
        java.util.concurrent.atomic.AtomicInteger retired=new java.util.concurrent.atomic.AtomicInteger();
        check(!publication.publish(candidate,()->false,h->{retired.incrementAndGet();PurpleWorkModeModel.retireNative(new long[]{h});})
                &&retired.get()==0&&publication.read(h->h)==initial,"rejected replacement never retires published generation");
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),attempted=new java.util.concurrent.CountDownLatch(1),retirement=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger answer=new java.util.concurrent.atomic.AtomicInteger();
        Thread reader=new Thread(()->{
            try { publication.read(h->{entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("reader release timeout");}catch(InterruptedException e){throw new AssertionError(e);}answer.set(PurpleWorkModeModel.visibleNative(h,55,0));return 0;}); }
            catch(Throwable failure){error.set(failure);}
        });
        Thread publisher=new Thread(()->{
            try { attempted.countDown();publication.publish(candidate,()->true,h->{
                if(publication.read(current->current)!=candidate)throw new AssertionError("retired before publication");
                retired.incrementAndGet();PurpleWorkModeModel.retireNative(new long[]{h});retirement.countDown();
            }); } catch(Throwable failure){error.set(failure);}
        });
        reader.start();
        check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"in-flight reader acquired published generation");
        publisher.start();
        check(attempted.await(5,java.util.concurrent.TimeUnit.SECONDS)&&!retirement.await(100,java.util.concurrent.TimeUnit.MILLISECONDS),"retirement waits for an in-flight reader");
        check(PurpleWorkModeModel.visibleNative(initial,11,0)==4&&PurpleWorkModeModel.visibleNative(initial,55,0)==0,"old native snapshot remains usable during pending publication");
        release.countDown();reader.join(5000);publisher.join(5000);
        check(!reader.isAlive()&&!publisher.isAlive()&&error.get()==null&&answer.get()==0,"in-flight reader never observes visible audible fallback");
        check(retired.get()==1&&publication.read(h->PurpleWorkModeModel.visibleNative(h,11,0))==4,"replacement publishes before old snapshot retirement");
        check(PurpleWorkModeModel.visibleNative(initial,55,0)==4&&PurpleWorkModeModel.visibleUnpeekedNative(initial,55,0)==4
                &&!PurpleWorkModeModel.storyShownNative(initial,55,0,true,-1,false),"retired positive handles fail hidden muted and story hidden");
        check(PurpleWorkModeModel.visibleNative(Long.MAX_VALUE,55,-1)==4,"missing handle remains closed even with invalid chat kind");
        PurpleCore.load(bytes(config),bytes("active_preset=\"normal\"\n"),"fixture","android","phone");
        long normal=runtime("own",own,config,"0-101");
        check(publication.read(h->PurpleWorkModeModel.visibleNative(h,11,0))==4,"replacement resolution build does not mutate published resolution");
        check(publication.publish(normal,()->true,h->PurpleWorkModeModel.retireNative(new long[]{h}))
                &&publication.read(h->PurpleWorkModeModel.visibleNative(h,11,0))==16,"new resolution begins at generation publication");
        PurpleWorkModeModel.retireNative(new long[]{normal});
        Fixture follow=new Fixture(parent,"follow-editor-preview","0-101","follow",own);
        PurpleAccountEditorModel editor=new PurpleAccountEditorModel(follow.root,follow.own,new File(follow.own.getPath()+".bak"),"0-101","0-101","0-202",
                Arrays.asList(follow.global),PHYSICAL,follow,PurpleEditorDirectories.NATIVE);
        String changed="version=2\n[lists.work]\nmembers=[77]\n";
        PurpleAccountEditorModel.Preview preview=editor.preview(changed);
        check(editor.workMode==1&&preview!=null&&preview.facts.getBoolean("followMembers")
                &&preview.facts.getJSONArray("inactive").toString().contains("lists.work.members"),"opening Follow choice marks own member edits inactive in genuine editor model");
        check(!new JSONObject(PurpleAccountEditorModel.previewNative(bytes(own),bytes(changed),"own",false,0)).getBoolean("followMembers")
                &&!new JSONObject(PurpleAccountEditorModel.previewNative(bytes(own),bytes(changed),"own",false,2)).getBoolean("followMembers"),"Own and Stock previews keep own member edits active for their storage scope");
        PurpleAccountEditorModel.Preview removed=editor.preview("version=2\n");
        check(removed!=null&&removed.facts.getBoolean("followMembers")&&removed.facts.getJSONArray("inactive").toString().contains("lists.work.members"),"Follow preview also classifies removed own member keys as inactive here");
        write(follow.local,"root_file_account=\"0-101\"\nglobal_account=\"0-101\"\n");
        check(editor.preview(changed)==null&&!editor.save(preview).ok&&Files.readString(follow.own.toPath()).equals(own),"choice changed after opening refuses preview and saved Follow intent");
        for(String escaped:new String[]{"work\\nline","work\\tline","work\\rline","work\\u0001line","work\\u007fline","work\\\"quote","work\\\\slash"}) {
            Fixture literal=new Fixture(parent,"delete-control-"+checks,"0-101","own","version=2\n[lists.\""+escaped+"\"]\nmembers=[55]\n");
            write(literal.global,GLOBAL+"\n[lists.\""+escaped+"\"]\nmembers=[11]\n");literal.open("0-101",false);
            String name=new JSONObject("{\"name\":\""+escaped+"\"}").getString("name");
            JSONObject deletion=literal.model.deleteOwn(name);
            check(deletion!=null&&literal.model.save(deletion.getString("text")).ok,"Delete own accepts fully escaped literal list name "+escaped);
            check(Files.readString(literal.global.toPath()).equals(GLOBAL+"\n[lists.\""+escaped+"\"]\nmembers=[11]\n")
                    &&!Files.readString(literal.own.toPath()).contains("members"),"literal control-name deletion preserves global bytes");
        }
    }
}
