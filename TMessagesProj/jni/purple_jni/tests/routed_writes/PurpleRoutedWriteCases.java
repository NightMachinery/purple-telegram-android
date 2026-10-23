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
}
