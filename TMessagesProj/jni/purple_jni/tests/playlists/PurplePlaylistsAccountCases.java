package org.telegram.messenger.purple;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

public final class PurplePlaylistsAccountCases {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value,String name) { ++checks; if(!value)throw new AssertionError(name); }
    private static boolean refuses(Action action)throws Exception { try{action.run();return false;}catch(IOException e){return true;} }
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void directory(Path path)throws Exception { Files.createDirectory(path);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------")); }
    private static void write(Path path,byte[] value)throws Exception { Files.write(path,value);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")); }
    private static void write(Path path,String value)throws Exception {write(path,bytes(value));}
    private static final class Fixture {
        final Path files=Files.createTempDirectory("purple-playlist-account-");
        final Path root=files.resolve("purple");
        final Path local=root.resolve("local.toml"), owner=root.resolve("accounts/root.toml");
        Fixture()throws Exception {
            Files.setPosixFilePermissions(files,PosixFilePermissions.fromString("rwx------"));
            directory(root);directory(root.resolve("accounts"));
            write(local,"root_file_account=\"0-101\"\nglobal_account=\"0-101\"\n");
            write(owner,"root_file_account=\"0-101\"\n");
            ApplicationLoader.files=files.toFile();
            PurpleAccountOwner.owner="0-101";PurpleAccountOwner.global="0-101";PurpleAccountOwner.ready=true;
            ++PurpleAccountOwner.revision;++PurpleAccountOwner.role;
            for(int i=0;i<UserConfig.MAX_ACCOUNT_COUNT;++i){
                UserConfig config=new UserConfig();config.active=i<3;config.user=i==0?101:i==1?202:303;
                UserConfig.instances[i]=config;ConnectionsManager.instances[i]=new ConnectionsManager();
            }
            ConnectionsManager.instances[2].test=true;
            android.os.Looper.mainThread=null;
        }
        PurplePlaylistsAccount.Captured capture(int slot){return PurplePlaylistsAccount.capture(slot);}
        PurplePlaylistsStore open(int slot)throws Exception {return PurplePlaylistsAccount.open(capture(slot),()->true);}
        Path playlist(int slot){return slot==0?root.resolve("playlists"):root.resolve("accounts/"+(slot==1?"0-202":"1-303")+"/playlists");}
    }
    private static void bindingAndFirstUse()throws Exception {
        Fixture f=new Fixture();
        check(f.capture(-1)==null&&f.capture(4)==null&&f.capture(3)==null,"invalid or inactive captured slot refused");
        PurplePlaylistsAccount.Captured a=f.capture(0),b=f.capture(1),c=f.capture(2);
        check(a.identity.key.equals("0-101")&&b.identity.key.equals("0-202")&&c.identity.key.equals("1-303"),"real key JNI retains captured account and backend");
        check(PurpleAccountFiles.resolve(b.identity.key,a.owner).playlists.toPath().equals(f.playlist(1)),"real core path JSON exposes nonowner playlist route");
        try(PurplePlaylistsStore first=PurplePlaylistsAccount.open(a,()->true)){
            PurplePlaylistsStore.Read read=first.read();check(read.ready(),"owner store reads real defaults");
            PurplePlaylistsStore.Status[] wrongThread={null};
            Thread foreignThread=new Thread(()->wrongThread[0]=first.read().status);foreignThread.start();foreignThread.join();
            check(wrongThread[0]==PurplePlaylistsStore.Status.WrongThread,"store operations remain on the captured worker thread");
            check(!Files.exists(f.playlist(0).resolve("library.json"))&&!Files.exists(f.playlist(0).resolve("local.json")),"read never materializes defaults");
            UserConfig.selectedAccount=1;
            check(a.current()&&first.read().ready(),"global account selection cannot retarget captured storage");
            check(refuses(()->f.open(0)),"same captured account writer excluded");
            try(PurplePlaylistsStore second=f.open(1);PurplePlaylistsStore third=f.open(2)){
                check(second.read().ready()&&third.read().ready(),"distinct captured accounts retain independent stores");
                check(Files.isDirectory(f.playlist(1))&&Files.isDirectory(f.playlist(2)),"first-use nonowner parents created through real JNI");
                check(second.setSync(second.read(),true).ready(),"account-local preference uses real local codec and store");
                check(!Files.exists(f.playlist(0).resolve("local.json"))&&!Files.exists(f.playlist(2).resolve("local.json")),"local edit never crosses account route");
            }
        }
        android.os.Looper.mainThread=Thread.currentThread();
        check(refuses(()->f.open(0)),"main-thread store opening refused");android.os.Looper.mainThread=null;
        PurpleAccountOwner.ready=false;check(f.capture(0)==null,"unready owner cache cannot capture account");
    }
    private static void identityAndCacheRetirement()throws Exception {
        for(int kind=0;kind<5;++kind){
            Fixture f=new Fixture();PurplePlaylistsAccount.Captured captured=f.capture(0);
            try(PurplePlaylistsStore store=PurplePlaylistsAccount.open(captured,()->true)){
                if(kind==0){PurpleAccountFiles.userChanging(0,0);UserConfig.instances[0].active=false;UserConfig.instances[0].active=true;}
                if(kind==1){UserConfig replacement=new UserConfig();replacement.active=true;replacement.user=101;UserConfig.instances[0]=replacement;}
                if(kind==2)ConnectionsManager.instances[0]=new ConnectionsManager();
                if(kind==3)++PurpleAccountOwner.revision;
                if(kind==4){PurpleAccountOwner.global="1-303";++PurpleAccountOwner.role;++PurpleAccountOwner.revision;}
                check(!captured.current()&&!store.read().ready(),"captured lifetime instances and cached role retire old store");
            }
        }
        Fixture f=new Fixture();PurplePlaylistsAccount.Captured captured=f.capture(0);boolean[] alive={true};
        try(PurplePlaylistsStore store=PurplePlaylistsAccount.open(captured,()->alive[0])){
            alive[0]=false;check(!store.read().ready(),"command retirement independently revokes store");
        }
    }
    private static void diskAuthority()throws Exception {
        for(int kind=0;kind<7;++kind){
            Fixture f=new Fixture();PurplePlaylistsAccount.Captured captured=f.capture(1);
            try(PurplePlaylistsStore store=PurplePlaylistsAccount.open(captured,()->true)){
                if(kind==0)write(f.local,"root_file_account=\"0-101\"\nglobal_account=\"0-101\"\n# changed\n");
                if(kind==1)write(f.owner,"root_file_account=\"0-999\"\n");
                if(kind==2)Files.delete(f.local);
                if(kind==3)Files.delete(f.owner);
                if(kind==4)write(f.local,new byte[]{(byte)255});
                if(kind==5)Files.setPosixFilePermissions(f.local,PosixFilePermissions.fromString("rw-r--r--"));
                if(kind==6){Path parent=f.playlist(1).getParent();Files.move(parent,parent.resolveSibling("retired"));directory(parent);}
                check(!store.read().ready(),"changed on-disk authority or directory retires captured files");
                check(!Files.exists(f.playlist(1).resolve("library.json")),"retirement never invents replacement library");
            }
        }
        Fixture f=new Fixture();write(f.local,"root_file_account=\"0-101\"\nglobal_account=\"1-303\"\n");final Fixture wrongGlobal=f;
        check(refuses(()->wrongGlobal.open(1))&&!Files.exists(f.playlist(1)),"real route plan rejects stale cached global before account creation");
        f=new Fixture();write(f.owner,"root_file_account=\"0-999\"\n");final Fixture wrongOwner=f;
        check(refuses(()->wrongOwner.open(1))&&!Files.exists(f.playlist(1)),"real route plan rejects stale cached owner before account creation");
        f=new Fixture();write(f.local,new byte[4*1024*1024+1]);final Fixture oversized=f;
        check(refuses(()->oversized.open(1))&&!Files.exists(f.playlist(1)),"generic authority input cap remains unchanged");
    }
    private static void pathAndOperationRetirement()throws Exception {
        Fixture f=new Fixture();Path outside=f.files.resolve("outside");directory(outside);write(outside.resolve("sentinel"),"foreign");
        Files.createSymbolicLink(f.root.resolve("accounts/0-202"),outside);
        check(refuses(()->f.open(1))&&!Files.exists(outside.resolve("playlists")),"account-parent symlink never reaches foreign files");
        check(Arrays.equals(Files.readAllBytes(outside.resolve("sentinel")),bytes("foreign")),"foreign sentinel remains unchanged");
        for(boolean afterRename:new boolean[]{false,true}){
            Fixture current=new Fixture();boolean[] active={true};
            PurplePlaylistsStore.Current command=()->{
                Path folder=current.playlist(0);
                try{
                    if(afterRename&&Files.exists(folder.resolve("local.json")))active[0]=false;
                    if(!afterRename&&Files.isDirectory(folder)){
                        try(java.util.stream.Stream<Path> names=Files.list(folder)){
                            if(names.anyMatch(p->p.getFileName().toString().startsWith(".playlist-tmp-")))active[0]=false;
                        }
                    }
                }catch(IOException e){throw new IllegalStateException(e);}
                return active[0];
            };
            try(PurplePlaylistsStore store=PurplePlaylistsAccount.open(current.capture(0),command)){
                PurplePlaylistsStore.Read read=store.read();check(read.ready(),"guarded operation begins with a retained snapshot");
                byte[] expected=PurplePlaylistsLocalCore.setSync(read.state(),true,101).canonicalBytes();
                PurplePlaylistsStore.Write result=store.setSync(read,true);
                check(!result.ready()&&!result.stateCommitted,"retired command cannot report successful publication");
                check(Files.exists(current.playlist(0).resolve("local.json"))==afterRename,"retirement preserves the correct publication side");
                if(afterRename)check(result.stateMayHaveChanged&&Arrays.equals(expected,Files.readAllBytes(current.playlist(0).resolve("local.json"))),"post-rename retirement preserves full bytes and uncertainty");
                try(java.util.stream.Stream<Path> names=Files.list(current.playlist(0))){check(names.noneMatch(p->p.getFileName().toString().startsWith(".playlist-tmp-")),"retirement leaves no owned unpublished temporary");}
            }
        }
    }
    private static void rootRetirement()throws Exception {
        Fixture f=new Fixture();
        try(PurplePlaylistsStore store=f.open(0)){
            Path replacement=Files.createTempDirectory("purple-other-app-root-");
            Files.setPosixFilePermissions(replacement,PosixFilePermissions.fromString("rwx------"));
            ApplicationLoader.files=replacement.toFile();
            check(!store.read().ready(),"changed app root retires the captured private route");
        }
    }
    public static void main(String[] args)throws Exception {
        bindingAndFirstUse();identityAndCacheRetirement();diskAuthority();pathAndOperationRetirement();rootRetirement();
        System.out.println("PLAYLIST_ACCOUNT_BINDER_CHECKS="+checks);
    }
}
