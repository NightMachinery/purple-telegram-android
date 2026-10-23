package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Map;

public final class PurpleAccountEditorCases {
    private static int checks;
    private static final String ORIGINAL = "# exact 🌻\nversion=2\nunknown=\"日本語\"\n[last_seen]\nreasons_p=true\n";
    private static final String DRAFT = ORIGINAL.replace("reasons_p=true", "reasons_p=false");
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); ++checks; }
    private static void write(File file, String text) throws Exception { Files.createDirectories(file.toPath().getParent()); Files.write(file.toPath(), bytes(text)); }
    private static final PurpleGlobalChoiceFiles.Physical PHYSICAL = new PurpleGlobalChoiceFiles.Physical() {
        public PurpleGlobalChoiceFiles.Mark mark(File file, boolean directory) throws Exception {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return new PurpleGlobalChoiceFiles.Mark("missing", "", 0);
            BasicFileAttributes a = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String,Object> unix = Files.readAttributes(file.toPath(), "unix:dev,ino,mode,nlink,ctime", LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || (directory ? !a.isDirectory() : !a.isRegularFile())
                    || !directory && ((Number)unix.get("nlink")).intValue() != 1) throw new IllegalStateException("unsafe real fixture");
            return new PurpleGlobalChoiceFiles.Mark(unix.get("dev")+":"+unix.get("ino"), directory ? "" : a.size()+":"+a.lastModifiedTime()+":"+unix.get("ctime"), ((Number)unix.get("mode")).intValue());
        }
        public boolean write(File file, PurpleGlobalChoiceFiles.Mark owned, byte[] value) {
            return PurpleGlobalChoiceFiles.writeNative(file.getPath(), owned.identity, value);
        }
    };
    private static final class Fixture implements PurpleAccountEditorModel.Context {
        final File root, target, backup, global;
        boolean alive = true, stopAfterWrite, stopOnNotify;
        int refresh, notify;
        PurpleAccountEditorModel model;
        Fixture(File parent, String name) throws Exception {
            root = new File(parent,name); check(root.mkdir(),"exclusive fixture root");
            target = new File(root,"accounts/0-202/settings.toml"); backup = new File(target.getPath()+".bak");
            global = new File(root,"settings.toml"); write(target,ORIGINAL); write(global,"version=1\n");
            write(new File(root,"local.toml"),"root_file_account=\"0-101\"\nglobal_account=\"0-101\"\n");
            write(new File(root,"accounts/root.toml"),"root_file_account=\"0-101\"\n");
        }
        void open() throws Exception { model = new PurpleAccountEditorModel(root,target,backup,"0-101","0-101","0-202",
                Arrays.asList(global,new File(global.getPath()+".good"),new File(target.getPath()+".good")),PHYSICAL,this); }
        void open(PurpleEditorDirectories.Creator creator) throws Exception {
            model = new PurpleAccountEditorModel(root,target,backup,"0-101","0-101","0-202",
                    Arrays.asList(global,new File(global.getPath()+".good"),new File(target.getPath()+".good")),PHYSICAL,this,creator);
        }
        public boolean current(boolean refreshed) {
            try { if (stopAfterWrite && target.isFile() && Arrays.equals(Files.readAllBytes(target.toPath()),bytes(DRAFT))) alive=false; }
            catch (Exception e) { throw new IllegalStateException(e); }
            return alive && refresh==(refreshed?1:0);
        }
        public void refresh() { ++refresh; }
        public void notifyChanged() { ++notify; if(stopOnNotify)alive=false; }
    }
    public static int run(File parent) throws Exception {
        checks=0;
        String mixed="version=2\n[last_seen]\nreasons_p=false\ntrade_remember=\"7d\"\n[playlists]\nmenu_item_p=false\n";
        JSONObject facts=new JSONObject(PurpleAccountEditorModel.previewNative(bytes(ORIGINAL),bytes(mixed),"fixture.toml",false,0));
        check(facts.optBoolean("ok"),"actual shared parser accepts mixed-scope input");
        String notes=facts.getJSONArray("inactive").toString();
        check(notes.contains("last_seen.trade_remember")&&notes.contains("playlists.menu_item_p")&&!notes.contains("last_seen.reasons_p"),"real per-key registry distinguishes mixed-table keys");
        check(new JSONObject(PurpleAccountEditorModel.previewNative(bytes(ORIGINAL),bytes(mixed),"fixture.toml",true,0)).getJSONArray("inactive").length()==0,"global target has no inactive notes");
        check(!new JSONObject(PurpleAccountEditorModel.previewNative(bytes(ORIGINAL),bytes("["),"fixture.toml",false,0)).optBoolean("ok"),"broken draft refuses");
        check(!new JSONObject(PurpleAccountEditorModel.previewNative(bytes(ORIGINAL),bytes("version=999\n"),"fixture.toml",false,0)).optBoolean("ok"),"newer draft refuses");
        check(!new JSONObject(PurpleAccountEditorModel.previewNative(null,bytes("version=1\n"),"fixture.toml",false,0)).optBoolean("ok"),"first creation cannot downgrade layered template");
        check(!new JSONObject(PurpleAccountEditorModel.previewNative(bytes(ORIGINAL),new byte[]{(byte)0xff},"fixture.toml",false,0)).optBoolean("ok"),"real JNI rejects malformed UTF8");
        check(!new JSONObject(PurpleAccountEditorModel.previewNative(new byte[]{(byte)0xff},bytes(DRAFT),"fixture.toml",false,0)).optBoolean("ok"),"real JNI rejects malformed opened UTF8");
        check(PurpleAccountEditorModel.encode("\uD800")==null,"Java strict encoding rejects an unpaired surrogate");
        Fixture saved=new Fixture(parent,"save");saved.open();
        PurpleAccountEditorModel.Result success=saved.model.save(saved.model.preview(DRAFT));
        check(success.ok&&success.changed&&success.backupChanged,"production backup and guarded publication succeed");
        check(Arrays.equals(Files.readAllBytes(saved.backup.toPath()),bytes(ORIGINAL)),"backup exact comments unknowns and UTF8");
        check(Arrays.equals(Files.readAllBytes(saved.target.toPath()),bytes(DRAFT)),"main exact editor bytes");
        Fixture stale=new Fixture(parent,"stale");stale.open();PurpleAccountEditorModel.Preview preview=stale.model.preview(DRAFT);
        java.nio.file.attribute.FileTime time=Files.getLastModifiedTime(stale.target.toPath());
        write(stale.target,ORIGINAL.replace("true","TRUE"));Files.setLastModifiedTime(stale.target.toPath(),time);
        check(!stale.model.save(preview).changed&&!stale.backup.exists(),"same length and mtime changed bytes refuse before backup");
        Fixture identity=new Fixture(parent,"identity");identity.open();preview=identity.model.preview(DRAFT);identity.alive=false;
        check(!identity.model.save(preview).changed&&identity.refresh==0&&identity.notify==0,"retired origin refuses without refresh");
        Fixture after=new Fixture(parent,"after-io");after.open();preview=after.model.preview(DRAFT);after.stopAfterWrite=true;
        PurpleAccountEditorModel.Result late=after.model.save(preview);
        check(late.changed&&!late.ok&&after.refresh==0&&after.notify==0,"post-I/O lifetime change preserves publication but blocks refresh");
        Fixture notify=new Fixture(parent,"notify");notify.open();preview=notify.model.preview(DRAFT);notify.stopOnNotify=true;
        check(!notify.model.save(preview).ok&&notify.notify==1,"synchronous notification lifetime change is retained");
        Fixture missing=new Fixture(parent,"missing");Files.delete(missing.target.toPath());missing.open();
        check(!missing.target.exists()&&missing.model.openedText.equals(PurpleAccountEditorModel.templateNative()),"opening absent file creates nothing and uses actual shared template");
        preview=missing.model.preview(missing.model.openedText);
        check(missing.model.save(preview).ok&&Files.readString(missing.target.toPath()).contains("version = 2"),"explicit first save creates layered file in existing account directory");
        Fixture creator=new Fixture(parent,"creator");Files.delete(creator.target.toPath());creator.open();preview=creator.model.preview(creator.model.openedText);
        write(creator.target,"foreign creator sentinel\n");
        check(!creator.model.save(preview).changed&&Files.readString(creator.target.toPath()).equals("foreign creator sentinel\n"),"post-open foreign creator is preserved");
        Fixture recovery=new Fixture(parent,"recovery");Files.delete(recovery.target.toPath());write(new File(recovery.target.getPath()+".good"),ORIGINAL);
        boolean recoveryHeld=false;
        try { recovery.open(); } catch (Exception e) { recoveryHeld=true; }
        check(recoveryHeld&&!recovery.target.exists(),"missing primary with own good is recovery, never a blank first save");
        Fixture noDirectory=new Fixture(parent,"no-directory");Files.delete(noDirectory.target.toPath());Files.delete(noDirectory.target.toPath().getParent());noDirectory.open();
        check(!noDirectory.model.save(noDirectory.model.preview(noDirectory.model.openedText)).changed&&!noDirectory.target.getParentFile().exists(),"missing destination directory remains honestly held");
        Fixture symlink=new Fixture(parent,"symlink");symlink.open();preview=symlink.model.preview(DRAFT);Files.delete(symlink.target.toPath());Files.createSymbolicLink(symlink.target.toPath(),symlink.global.toPath());
        check(!symlink.model.save(preview).changed&&Files.readString(symlink.global.toPath()).equals("version=1\n"),"foreign symlink and global sentinel retained");
        runDirectoryCases(parent);
        return checks;
    }

    private static Fixture absentDirectory(File parent, String name) throws Exception {
        Fixture fixture = new Fixture(parent,name);
        Files.delete(fixture.target.toPath()); Files.delete(fixture.target.toPath().getParent());
        return fixture;
    }
    private static void runDirectoryCases(File parent) throws Exception {
        Fixture first=absentDirectory(parent,"directory-first-save");first.open(PurpleEditorDirectories.NATIVE);
        String template=first.model.openedText;
        check(!first.target.getParentFile().exists(),"production creation capability performs no I/O on open");
        PurpleAccountEditorModel.Result saved=first.model.save(first.model.preview(template));
        check(saved.ok&&saved.changed&&saved.directoryAttempted&&saved.directoriesCreated==1,"genuine native own-directory receipt enables first explicit Save");
        check((PHYSICAL.mark(first.target.getParentFile(),true).mode&0777)==0700,"created directory has requested owner-only permission bits, not a host-access claim");
        check(Files.readString(first.target.toPath()).equals(template)&&!template.contains("[last_seen]")&&!template.contains("reasons_p")&&!template.contains("send_after_save"),"first Save is exact shared template with no unrelated row pinning");
        check(Files.readString(first.global.toPath()).equals("version=1\n")&&!first.backup.exists()&&!new File(first.target.getPath()+".good").exists(),"first Save adopts no global good backup or store");

        Fixture foreign=absentDirectory(parent,"directory-foreign-creator");
        foreign.open((directory,parentMark)->{
            Files.createDirectory(directory.toPath());write(new File(directory,"foreign.txt"),"foreign directory sentinel\n");
            return PurpleEditorDirectories.NATIVE.create(directory,parentMark);
        });
        PurpleAccountEditorModel.Result refused=foreign.model.save(foreign.model.preview(foreign.model.openedText));
        check(!refused.ok&&!refused.changed&&refused.directoriesCreated==0&&!foreign.target.exists(),"foreign creator is refused, never adopted");
        check(Files.readString(new File(foreign.target.getParentFile(),"foreign.txt").toPath()).equals("foreign directory sentinel\n"),"foreign directory contents preserved");

        Fixture replacement=absentDirectory(parent,"directory-replaced-after-receipt");
        File retained=new File(replacement.root,"owned-created-directory-retained");
        replacement.open((directory,parentMark)->{
            PurpleEditorDirectories.Receipt receipt=PurpleEditorDirectories.NATIVE.create(directory,parentMark);
            Files.move(directory.toPath(),retained.toPath());Files.createDirectory(directory.toPath());
            write(new File(directory,"foreign.txt"),"replacement sentinel\n");return receipt;
        });
        refused=replacement.model.save(replacement.model.preview(replacement.model.openedText));
        check(!refused.ok&&!refused.changed&&refused.directoriesCreated==1&&retained.isDirectory(),"original directory receipt cannot authorize replacement inode");
        check(!replacement.target.exists()&&Files.readString(new File(replacement.target.getParentFile(),"foreign.txt").toPath()).equals("replacement sentinel\n"),"no wrong-file write or foreign-directory rollback");

        Fixture symbolic=absentDirectory(parent,"directory-symlink-before-create");
        symbolic.open((directory,parentMark)->{
            Files.createSymbolicLink(directory.toPath(),symbolic.root.toPath());
            return PurpleEditorDirectories.NATIVE.create(directory,parentMark);
        });
        refused=symbolic.model.save(symbolic.model.preview(symbolic.model.openedText));
        check(!refused.changed&&refused.directoriesCreated==0&&Files.isSymbolicLink(symbolic.target.getParentFile().toPath()),"symlink creator is refused and retained");
        check(Files.readString(symbolic.global.toPath()).equals("version=1\n"),"symlink target global file unchanged");

        Fixture retired=absentDirectory(parent,"directory-origin-retired-after-create");
        retired.open((directory,parentMark)->{
            PurpleEditorDirectories.Receipt receipt=PurpleEditorDirectories.NATIVE.create(directory,parentMark);
            retired.alive=false;return receipt;
        });
        refused=retired.model.save(retired.model.preview(retired.model.openedText));
        check(!refused.ok&&!refused.changed&&refused.directoriesCreated==1&&retired.refresh==0&&retired.notify==0,"retained lifetime retirement after directory I/O blocks publication and notifications");
        check(retired.target.getParentFile().isDirectory()&&retired.target.getParentFile().list().length==0,"owned empty directory is retained as an honest partial outcome");

        Fixture changed=absentDirectory(parent,"directory-authority-disk-change");
        changed.open((directory,parentMark)->{
            PurpleEditorDirectories.Receipt receipt=PurpleEditorDirectories.NATIVE.create(directory,parentMark);
            write(changed.global,"changed global sentinel\n");return receipt;
        });
        refused=changed.model.save(changed.model.preview(changed.model.openedText));
        check(!refused.changed&&refused.directoriesCreated==1&&changed.refresh==0&&!changed.target.exists(),"authority disk change during directory I/O blocks further publication");
        check(Files.readString(changed.global.toPath()).equals("changed global sentinel\n"),"external authority edit is not rolled back");

        Fixture parentSwap=absentDirectory(parent,"directory-parent-swap");
        File retainedParent=new File(parentSwap.root,"original-account-parent-retained");
        parentSwap.open((directory,parentMark)->{
            File accounts=directory.getParentFile();Files.move(accounts.toPath(),retainedParent.toPath());Files.createDirectory(accounts.toPath());
            write(new File(accounts,"root.toml"),"root_file_account=\"0-101\"\n");
            return PurpleEditorDirectories.NATIVE.create(directory,parentMark);
        });
        refused=parentSwap.model.save(parentSwap.model.preview(parentSwap.model.openedText));
        check(!refused.changed&&refused.directoriesCreated==0&&!parentSwap.target.getParentFile().exists(),"native creation checks retained parent descriptor identity");
        check(retainedParent.isDirectory()&&new File(parentSwap.root,"accounts/root.toml").isFile(),"both original and replacement parent contents retained");

        Fixture authorityMissing=absentDirectory(parent,"directory-missing-owner-parent");
        Files.delete(new File(authorityMissing.root,"accounts/root.toml").toPath());Files.delete(new File(authorityMissing.root,"accounts").toPath());
        boolean held=false;try{authorityMissing.open(PurpleEditorDirectories.NATIVE);}catch(Exception e){held=true;}
        check(held&&!new File(authorityMissing.root,"accounts").exists(),"missing owner authority is recovery, not permission to create intermediate root metadata");

        Fixture root=new Fixture(parent,"directory-existing-root");final int[] calls={0};
        PurpleAccountEditorModel rootModel=new PurpleAccountEditorModel(root.root,root.global,new File(root.root,"settings.toml.bak"),"0-101","0-101","0-101",
                Arrays.asList(new File(root.global.getPath()+".good")),PHYSICAL,root,(directory,parentMark)->{++calls[0];return PurpleEditorDirectories.NATIVE.create(directory,parentMark);});
        PurpleAccountEditorModel.Result rootSaved=rootModel.save(rootModel.preview("# root retained\nversion=1\n"));
        check(rootSaved.ok&&calls[0]==0&&!rootSaved.directoryAttempted,"existing single-account root Save never invokes directory creation");
    }
    private PurpleAccountEditorCases() { }
}
