package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Map;

public final class PurpleAppWideRemovalCases {
    private static int checks;
    private static final String OWN = "# retain 🌻\nversion = 1 # file\nunknown = \"日本語\"\n[last_seen]\nreasons_p = false # retain own\n[notifications]\npreview_p = false\n[sync]\nsend_after_save_p = true\n[premium]\nenabled = true\n[presets.work]\nlists = []\n";
    private static final String GLOBAL = "version=1\n[premium]\nenabled=false\n";
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); ++checks; }
    private static void write(File f, String s) throws Exception { Files.createDirectories(f.toPath().getParent()); Files.write(f.toPath(), bytes(s)); }
    private static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private static final PurpleGlobalChoiceFiles.Physical PHYSICAL = new PurpleGlobalChoiceFiles.Physical() {
        public PurpleGlobalChoiceFiles.Mark mark(File file, boolean directory) throws Exception {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return new PurpleGlobalChoiceFiles.Mark("missing", "", 0);
            BasicFileAttributes a = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String,Object> unix = Files.readAttributes(file.toPath(), "unix:dev,ino,mode,nlink,ctime", LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || (directory ? !a.isDirectory() : !a.isRegularFile())
                    || (!directory && ((Number) unix.get("nlink")).intValue() != 1)) throw new IllegalStateException("unsafe real fixture path");
            return new PurpleGlobalChoiceFiles.Mark(unix.get("dev") + ":" + unix.get("ino"), directory ? ""
                    : a.size() + ":" + a.lastModifiedTime() + ":" + unix.get("ctime"), ((Number)unix.get("mode")).intValue());
        }
        public boolean write(File file, PurpleGlobalChoiceFiles.Mark owned, byte[] value) {
            return PurpleGlobalChoiceFiles.writeNative(file.getPath(), owned.identity, value);
        }
    };
    private static final class Fixture implements PurpleAppWideRemovalModel.Context {
        final File root, target, backup, global, local, owner, good, foreign;
        final String globalAccount;
        PurpleAppWideRemovalModel model;
        boolean alive = true, role = true, ui = true, failAfterBackup, failAfterMain, failDuringNotify;
        int refresh, notify;
        boolean replaceTemporaryBeforeMark;
        File foreignTemporary;
        final PurpleGlobalChoiceFiles.Physical physical;
        private PurpleGlobalChoiceFiles.Physical makePhysical() { return new PurpleGlobalChoiceFiles.Physical() {
            public PurpleGlobalChoiceFiles.Mark mark(File file, boolean directory) throws Exception {
                if (replaceTemporaryBeforeMark && file.getName().startsWith("appwide-")) {
                    replaceTemporaryBeforeMark = false;
                    File replacement = new File(root,"foreign-created-before-mark");
                    PurpleAppWideRemovalCases.write(replacement,"foreign creation sentinel\n");
                    Files.move(replacement.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    foreignTemporary = file;
                }
                return PHYSICAL.mark(file,directory);
            }
            public boolean write(File file, PurpleGlobalChoiceFiles.Mark owned, byte[] value) throws Exception {
                return PHYSICAL.write(file,owned,value);
            }
        }; }
        Fixture(File parent, String name) throws Exception {
            this(parent, name, false);
        }
        Fixture(File parent, String name, boolean missingGlobal) throws Exception {
            root = new File(parent, name); check(root.mkdir(), "exclusive genuine fixture directory");
            target = new File(root,"accounts/0-202/settings.toml"); backup = new File(target.getPath()+".bak");
            globalAccount = missingGlobal ? "0-303" : "0-101";
            global = new File(root,missingGlobal ? "accounts/0-303/settings.toml" : "settings.toml");
            local = new File(root,"local.toml"); owner = new File(root,"accounts/root.toml");
            good = new File(target.getPath()+".good"); foreign = new File(root,"unrelated/settings.toml");
            write(target,OWN); if (!missingGlobal) write(global,GLOBAL);
            write(local,"root_file_account=\"0-101\"\nglobal_account=\""+globalAccount+"\"\n");
            write(owner,"root_file_account=\"0-101\"\n"); write(foreign,"unrelated sentinel\n");
            physical = makePhysical();
        }
        void preview() throws Exception { model = new PurpleAppWideRemovalModel(root,target,backup,"0-101",globalAccount,"0-202",
                Arrays.asList(global,new File(global.getPath()+".good"),local,owner,good),physical,this); }
        public boolean current(boolean refreshed) {
            try {
                if (failAfterBackup && backup.isFile() && read(backup).equals(OWN)) alive = false;
                if (failAfterMain && target.isFile() && !read(target).equals(OWN)) alive = false;
                return alive && role && ui && refresh == (refreshed ? 1 : 0);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        public void refresh() { ++refresh; }
        public void notifyChanged() { ++notify; if (failDuringNotify) alive = false; }
        void unchanged() throws Exception {
            check(read(target).equals(OWN),"original main bytes retained");
            check(read(global).equals(GLOBAL) && read(foreign).equals("unrelated sentinel\n"),"global and unrelated file exact");
        }
    }
    private static void refusedPreview(Fixture f) throws Exception {
        boolean existed = Files.exists(f.backup.toPath(), LinkOption.NOFOLLOW_LINKS);
        boolean link = Files.isSymbolicLink(f.backup.toPath());
        java.nio.file.Path linkTarget = link ? Files.readSymbolicLink(f.backup.toPath()) : null;
        byte[] before = existed && !link ? Files.readAllBytes(f.backup.toPath()) : null;
        boolean refused = false;
        try { f.preview(); } catch (Exception e) { refused = true; }
        check(refused,"unsafe or ineligible source refuses production preview");
        check(existed == Files.exists(f.backup.toPath(), LinkOption.NOFOLLOW_LINKS),"preview refusal never creates or removes backup");
        if (link) check(linkTarget.equals(Files.readSymbolicLink(f.backup.toPath())),"foreign symlink retained");
        else if (existed) check(Arrays.equals(before,Files.readAllBytes(f.backup.toPath())),"foreign backup bytes retained");
    }
    public static int run(File parent) throws Exception {
        checks = 0;
        check(PurpleAppWideRemovalModel.eligible("0-202","0-101"),"non-global source eligible");
        check(!PurpleAppWideRemovalModel.eligible("0-101","0-101")
                && !PurpleAppWideRemovalModel.eligible(null,"0-101")
                && !PurpleAppWideRemovalModel.eligible("0-202",null),"global or unknown account not eligible");
        Fixture success = new Fixture(parent,"success"); success.preview();
        check(success.model.count > 0 && success.model.groups.length() > 0,"actual registry removal counts and groups");
        JSONObject nativePlan = new JSONObject(PurpleAppWideRemovalModel.planNative(bytes(OWN),success.target.getPath()));
        String successor = nativePlan.getString("text");
        PurpleAppWideRemovalModel.Result saved = success.model.apply();
        check(saved.ok && saved.changed && saved.backupChanged,"production backup then main succeeds");
        check(read(success.backup).equals(OWN),"backup is exact original UTF8 bytes");
        check(read(success.target).equals(successor),"main is exact real shared removal output");
        check(successor.contains("version = 2 # file") && successor.contains("unknown = \"日本語\"")
                && successor.contains("reasons_p = false # retain own") && successor.contains("[sync]"),"layered version and unknown/per-account/file-local comments preserved");
        check(!successor.contains("[premium]") && !successor.contains("[presets.work]"),"real app-wide units removed");
        check(success.refresh==1 && success.notify==1,"refresh and notification only after confirmed main publication");
        check(read(success.global).equals(GLOBAL) && read(success.foreign).equals("unrelated sentinel\n"),"unrelated source files exact");
        check(!success.model.apply().changed,"confirmation single use");
        Fixture missingGlobal = new Fixture(parent,"missing-global-directory",true);
        JSONObject missingRoute = new JSONObject(PurpleGlobalChoiceModel.planNative(
                Files.readAllBytes(missingGlobal.local.toPath()),Files.readAllBytes(missingGlobal.owner.toPath()),
                "0-101",missingGlobal.globalAccount,"0-202"));
        check(missingRoute.getBoolean("ok") && new File(missingGlobal.root,missingRoute.getString("currentPath")).equals(missingGlobal.global),
                "real local-choice planner selects absent non-root global account file");
        check(!missingGlobal.global.getParentFile().exists(),"selected global directory starts absent");
        missingGlobal.preview();
        String missingSuccessor = new JSONObject(PurpleAppWideRemovalModel.planNative(bytes(OWN),missingGlobal.target.getPath())).getString("text");
        PurpleAppWideRemovalModel.Result missingSaved = missingGlobal.model.apply();
        check(missingSaved.ok && missingSaved.changed && missingSaved.backupChanged,"existing own file removes app-wide settings with absent global authority directory");
        check(read(missingGlobal.backup).equals(OWN) && read(missingGlobal.target).equals(missingSuccessor),"absent-global removal retains exact backup and real shared successor");
        check(!missingGlobal.global.getParentFile().exists() && !new File(missingGlobal.global.getPath()+".good").exists(),"removal never creates read-only global directory or recovery source");
        Fixture globalCreator = new Fixture(parent,"global-directory-created-after-preview",true); globalCreator.preview();
        Files.createDirectory(globalCreator.global.getParentFile().toPath());
        PurpleAppWideRemovalModel.Result creatorRefused = globalCreator.model.apply();
        check(!creatorRefused.ok && !creatorRefused.changed && !creatorRefused.backupChanged
                && !globalCreator.backup.exists() && read(globalCreator.target).equals(OWN),"post-preview global directory creator refuses before any publication");
        check(globalCreator.global.getParentFile().isDirectory() && !globalCreator.global.exists()
                && !new File(globalCreator.global.getPath()+".good").exists(),"foreign global directory retained without source creation");
        Fixture missingDestination = new Fixture(parent,"missing-writable-destination",true);
        File retainedDestination = new File(missingDestination.root,"retained-own-directory");
        Files.move(missingDestination.target.getParentFile().toPath(),retainedDestination.toPath());
        refusedPreview(missingDestination);
        check(!missingDestination.target.getParentFile().exists() && !missingDestination.backup.exists()
                && read(new File(retainedDestination,"settings.toml")).equals(OWN),"missing writable destination is refused without recreating its directory");
        Fixture missingBackupParent = new Fixture(parent,"missing-backup-parent",true);
        File absentBackup = new File(missingBackupParent.root,"absent-backup-parent/settings.toml.bak");
        boolean backupParentRefused = false;
        try {
            new PurpleAppWideRemovalModel(missingBackupParent.root,missingBackupParent.target,absentBackup,"0-101","0-303","0-202",
                    Arrays.asList(missingBackupParent.global,missingBackupParent.local,missingBackupParent.owner,missingBackupParent.good),
                    missingBackupParent.physical,missingBackupParent);
        } catch (Exception e) { backupParentRefused = true; }
        check(backupParentRefused && !absentBackup.getParentFile().exists() && !missingBackupParent.backup.exists()
                && read(missingBackupParent.target).equals(OWN),"read-only authority absence does not permit a missing writable backup parent");
        Fixture existing = new Fixture(parent,"existing-backup"); write(existing.backup,OWN); existing.preview();
        Object backupKey = Files.readAttributes(existing.backup.toPath(),BasicFileAttributes.class).fileKey();
        PurpleAppWideRemovalModel.Result exact = existing.model.apply();
        check(exact.ok && !exact.backupChanged,"exact prior backup reused");
        check(backupKey.equals(Files.readAttributes(existing.backup.toPath(),BasicFileAttributes.class).fileKey()),"equal backup inode preserved");
        PurpleGlobalChoiceDialog dialog = new PurpleGlobalChoiceDialog();
        PurpleGlobalChoiceDialog.Token token = dialog.picker(); Object identity = new Object();
        dialog.bind(token,identity,() -> true); check(dialog.shown(token,identity) && dialog.current(token),"real production dialog token is live");
        Fixture canceled = new Fixture(parent,"cancel"); canceled.preview(); dialog.dismissed(token); canceled.ui = dialog.current(token);
        check(!canceled.model.apply().changed && !canceled.backup.exists(),"retired confirmation refuses before backup"); canceled.unchanged();
        Fixture role = new Fixture(parent,"global-role-change"); role.preview(); role.role = false;
        check(!role.model.apply().changed && !role.backup.exists(),"changed global/account role refuses"); role.unchanged();
        Fixture lifetime = new Fixture(parent,"same-key-new-lifetime"); lifetime.preview(); lifetime.alive = false;
        check(!lifetime.model.apply().changed && !lifetime.backup.exists(),"origin retirement not recaptured"); lifetime.unchanged();
        Fixture local = new Fixture(parent,"local-choice-replaced"); local.preview(); write(local.local,"global_account=\"0-303\"\n");
        check(!local.model.apply().changed && !local.backup.exists(),"actual changed local source refuses"); local.unchanged();
        Fixture source = new Fixture(parent,"global-source-replaced"); source.preview(); write(source.global,"version=2\n# changed source\n");
        check(!source.model.apply().changed && !source.backup.exists(),"global source bytes change refuses");
        Fixture main = new Fixture(parent,"main-same-bytes-replaced"); main.preview(); File replacement = new File(main.root,"replacement"); write(replacement,OWN);
        Files.move(replacement.toPath(),main.target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        check(!main.model.apply().changed && !main.backup.exists(),"same bytes replacement inode refuses"); main.unchanged();
        Fixture backup = new Fixture(parent,"foreign-backup"); backup.preview(); write(backup.backup,"foreign sentinel\n");
        check(!backup.model.apply().changed && read(backup.backup).equals("foreign sentinel\n"),"foreign backup not overwritten"); backup.unchanged();
        Fixture creation = new Fixture(parent,"foreign-before-first-temp-mark"); creation.preview(); creation.replaceTemporaryBeforeMark = true;
        PurpleAppWideRemovalModel.Result refusedCreation = creation.model.apply();
        check(!refusedCreation.changed && !refusedCreation.backupChanged,"native creation identity refuses replacement before first Java mark");
        check(creation.foreignTemporary != null && read(creation.foreignTemporary).equals("foreign creation sentinel\n"),"foreign unpublished inode is neither written nor cleaned"); creation.unchanged();
        Fixture backupOnly = new Fixture(parent,"backup-only"); backupOnly.preview(); backupOnly.failAfterBackup = true;
        PurpleAppWideRemovalModel.Result partial = backupOnly.model.apply();
        check(partial.backupChanged && !partial.changed && !partial.ok,"retirement after backup reports backup-only");
        check(read(backupOnly.backup).equals(OWN),"backup-only exact original remains"); backupOnly.unchanged();
        Fixture mainSaved = new Fixture(parent,"main-saved-context-retired"); mainSaved.preview(); mainSaved.failAfterMain = true;
        PurpleAppWideRemovalModel.Result after = mainSaved.model.apply();
        check(after.changed && after.backupChanged && !after.ok,"post-main context retirement reports saved outcome");
        check(read(mainSaved.target).equals(successor) && read(mainSaved.backup).equals(OWN),"no rollback after post-main retirement");
        Fixture notify = new Fixture(parent,"synchronous-notification-retirement"); notify.preview(); notify.failDuringNotify = true;
        PurpleAppWideRemovalModel.Result notified = notify.model.apply();
        check(notified.changed && !notified.ok && notify.notify==1,"synchronous notification rechecks retained context");
        Fixture symlink = new Fixture(parent,"symlink-backup"); Files.createSymbolicLink(symlink.backup.toPath(),symlink.foreign.toPath()); refusedPreview(symlink);
        check(read(symlink.foreign).equals("unrelated sentinel\n"),"symlink sentinel exact");
        String structuredOwn = "version=1\n[last_seen]\nreasons_p={x=true}\n[premium]\nenabled=true\n";
        Fixture warningOnly = new Fixture(parent,"warning-only-per-account-preserved"); write(warningOnly.target,structuredOwn); warningOnly.preview();
        JSONObject warningPlan = new JSONObject(PurpleAppWideRemovalModel.planNative(bytes(structuredOwn),warningOnly.target.getPath()));
        String warningSuccessor = warningPlan.getString("text");
        check(warningPlan.getBoolean("ok") && warningPlan.getInt("count") > 0 && warningSuccessor.contains("reasons_p={x=true}")
                && warningSuccessor.contains("version=2") && !warningSuccessor.contains("[premium]"),"real shared removal accepts warnings and preserves structured per-account data in version2");
        PurpleAppWideRemovalModel.Result warningSaved = warningOnly.model.apply();
        check(warningSaved.ok && warningSaved.changed && warningSaved.backupChanged
                && read(warningOnly.backup).equals(structuredOwn) && read(warningOnly.target).equals(warningSuccessor),"warning-only source publishes exact original backup and planner successor");
        for (String text : new String[]{"version=999\n[premium]\nenabled=true\n", "version=\"2\"\n[premium]\nenabled=true\n", "[premium\n", "[last_seen]\ntrade_remember={x=true}\n[premium]\nenabled=true\n", "version=2\n[last_seen]\nreasons_p=true\n"}) {
            Fixture invalid = new Fixture(parent,"invalid-"+checks); write(invalid.target,text); refusedPreview(invalid);
        }
        Fixture queuedChoice = new Fixture(parent,"queued-local-before-preview");
        write(queuedChoice.local,"root_file_account=\"0-101\"\nglobal_account=\"0-202\"\n");
        refusedPreview(queuedChoice); queuedChoice.unchanged();
        Fixture readonly = new Fixture(parent,"read-only-main");
        Files.setPosixFilePermissions(readonly.target.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
        refusedPreview(readonly); readonly.unchanged();
        Fixture good = new Fixture(parent,"good-source-replaced"); good.preview(); write(good.good,"version=2\n");
        check(!good.model.apply().changed && !good.backup.exists(),"changed recovery source refuses before backup"); good.unchanged();
        Fixture directory = new Fixture(parent,"directory-replacement"); directory.preview();
        File originalDirectory = directory.target.getParentFile(); File movedDirectory = new File(directory.root,"retained-original-directory");
        Files.move(originalDirectory.toPath(),movedDirectory.toPath());
        write(directory.target,OWN);
        check(!directory.model.apply().changed && !directory.backup.exists(),"same-path account directory replacement refuses");
        Fixture malformed = new Fixture(parent,"invalid-utf8"); Files.write(malformed.target.toPath(),new byte[]{(byte)0xc3,(byte)0x28}); refusedPreview(malformed);
        Fixture queued = new Fixture(parent,"queued-refresh"); queued.preview(); ++queued.refresh;
        check(!queued.model.apply().changed && !queued.backup.exists(),"queued watcher generation change refuses"); queued.unchanged();
        return checks;
    }
    private PurpleAppWideRemovalCases() { }
}
