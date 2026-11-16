package org.telegram.messenger.purple;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import android.util.AtomicFile;
public final class LegacyCarryRefusalCases {
    private static int checks;
    private static final byte[] INITIAL=bytes("v1:0:0:device"), NEXT=bytes("v1:0:0:device:1");
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static PurpleAccountSyncStore.Core core()throws Exception{
        java.lang.reflect.Constructor<?> c=Class.forName("org.telegram.messenger.purple.PurpleAccountSyncStoreTest$FakeCore").getDeclaredConstructor();
        c.setAccessible(true);return (PurpleAccountSyncStore.Core)c.newInstance();
    }
    private static PurpleAccountSyncStore store(File root)throws Exception{return new PurpleAccountSyncStore(root,core());}
    private static File seed(File parent,String name)throws Exception{
        File root=new File(parent,name);check(root.mkdir(),"exclusive store fixture");
        try(PurpleAccountSyncStore s=store(root)){
            check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.Uninitialized,"empty admission");
            check(s.initialize(INITIAL).status==PurpleAccountSyncStore.Status.Ready,"seed actual store");
        }
        return root;
    }
    private static Map<String,String> snapshot(File root)throws Exception{
        Map<String,String> values=new TreeMap<>();
        try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){
            for(Path p:(Iterable<Path>)paths::iterator){
                String key=root.toPath().relativize(p).toString();
                values.put(key,Files.isSymbolicLink(p)?"link:"+Files.readSymbolicLink(p):Files.isDirectory(p)?"dir":Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
            }
        }
        return values;
    }
    private static void carry(File root,String name)throws Exception{
        File p=new File(root,name);
        if(name.equals("carry-objects")||name.equals("carry-history")){check(p.mkdir(),"carry directory");Files.write(new File(p,"opaque").toPath(),bytes("unfinished"));}
        else Files.write(p.toPath(),bytes("{}"));
    }
    public static void run(File parent)throws Exception{
        main(new String[]{new File(parent,"legacy-carry-refusal").getPath()});
    }
    public static void main(String[] args)throws Exception{
        File parent=new File(args[0]);check(parent.mkdir(),"exclusive fixture parent");
        for(String name:new String[]{"carry.json","carry-cache.json","carry-objects","carry-history",".carry-stage","carry.json.new","carry.json.bak"}){
            File root=seed(parent,"open-"+name);carry(root,name);
            Files.write(new File(root,"state.json.bak").toPath(),NEXT);
            Files.write(new File(root,"state.json.new").toPath(),bytes("unfinished state"));
            Map<String,String> before=snapshot(root);int writes=AtomicFile.finishWriteCalls;
            try(PurpleAccountSyncStore s=store(root)){
                check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.AmbiguousFiles,"legacy carry must refuse admission before recovery or writes");
            }
            check(snapshot(root).equals(before),"legacy files and all state recovery artifacts preserved");
            check(AtomicFile.finishWriteCalls==writes,"no committed write during refused open");
        }
        for(String name:new String[]{"carry.json","carry-cache.json","carry-objects","carry-history",".carry-stage","carry.json.new","carry.json.bak","carryx"})
            check(PurpleAccountSyncStore.legacyArtifactName(name),"the set-aside predicate names every refused carry entry: "+name);
        for(String name:new String[]{"lock","state.json","state.json.bak","pending","auto.json","chat.json","retained-bases","start-again.json","orphaned-20261116-000000",".carry","car.json","xcarry"})
            check(!PurpleAccountSyncStore.legacyArtifactName(name),"the set-aside predicate leaves ordinary entries: "+name);
        {
            File aside=seed(parent,"set-aside-control");carry(aside,"carry-objects");carry(aside,".carry-stage");
            File archive=new File(aside,"orphaned-20261116-000000");check(archive.mkdir(),"dated folder");
            for(String name:new String[]{"carry-objects",".carry-stage"})Files.move(new File(aside,name).toPath(),new File(archive,name).toPath(),LinkOption.NOFOLLOW_LINKS);
            try(PurpleAccountSyncStore s=store(aside)){
                check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.Ready,"carry set aside into a dated folder lets the store open");
                check(s.stateBytes()!=null,"state admission resumes after set-aside");
            }
        }
        File root=seed(parent,"late-stage");
        try(PurpleAccountSyncStore s=store(root)){
            check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.Ready,"open before late carry");
            carry(root,"carry.json");Map<String,String> before=snapshot(root);int writes=AtomicFile.finishWriteCalls;
            check(s.stageConfig(bytes("record:1"),bytes("v1:1:1:device")).status!=PurpleAccountSyncStore.Status.Ready,"late carry blocks staged config");
            check(snapshot(root).equals(before)&&AtomicFile.finishWriteCalls==writes,"stage and state unchanged");
        }
        root=seed(parent,"late-auto");
        try(PurpleAccountSyncStore s=store(root)){
            check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.Ready,"open for automatic checkpoint");
            carry(root,"carry-cache.json");Map<String,String> before=snapshot(root);int writes=AtomicFile.finishWriteCalls;
            check(!s.compareAuto(null,bytes("automatic checkpoint"),()->true),"late carry blocks automatic checkpoint write");
            check(snapshot(root).equals(before)&&AtomicFile.finishWriteCalls==writes,"automatic file not created");
            check(s.stateBytes()==null,"late carry revokes state snapshot admission");
        }
        root=seed(parent,"late-commit");
        try(PurpleAccountSyncStore s=store(root)){
            check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.Ready,"open for state commit");
            carry(root,"carry.json");Map<String,String> before=snapshot(root);
            check(s.commitConfigState(NEXT).status!=PurpleAccountSyncStore.Status.Ready,"late carry blocks state commit");
            check(snapshot(root).equals(before),"state commit preserves bytes");
        }
        root=new File(parent,"late-initialize");check(root.mkdir(),"new initialization root");
        try(PurpleAccountSyncStore s=store(root)){
            check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.Uninitialized,"initialize admission before artifact");
            carry(root,"carry.json");Map<String,String> before=snapshot(root);
            check(s.initialize(INITIAL).status!=PurpleAccountSyncStore.Status.Ready,"late carry blocks initialization");
            check(snapshot(root).equals(before),"no state initialization beside legacy evidence");
        }
        root=seed(parent,"late-cleanup");
        try(PurpleAccountSyncStore s=store(root)){
            check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.Ready,"open before cleanup");
            Files.write(new File(root,"pending/config.json").toPath(),bytes("record:1"));carry(root,"carry.json");
            Map<String,String> before=snapshot(root);
            java.lang.reflect.Method cleanup=PurpleAccountSyncStore.class.getDeclaredMethod("dropUnreservedStage");cleanup.setAccessible(true);cleanup.invoke(s);
            check(snapshot(root).equals(before),"cleanup preserves stage beside carry evidence");
        }
        root=seed(parent,"recovery-admission");carry(root,"carry.json");Map<String,String> before=snapshot(root);
        check(!PurpleAccountSyncStore.recoverInterrupted(parent,root,"",PurpleAccountEditorCases.PHYSICAL,()->true),"recovery refuses carry even without repair candidates");
        check(snapshot(root).equals(before),"recovery leaves every byte untouched");
        root=seed(parent,"dangling-link");Files.createSymbolicLink(new File(root,"carry.json").toPath(),Path.of("missing-owned-sentinel"));before=snapshot(root);
        try(PurpleAccountSyncStore s=store(root)){
            check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.AmbiguousFiles,"dangling carry link refused by name");
        }
        check(snapshot(root).equals(before),"dangling evidence preserved");
        root=seed(parent,"write-fence");
        try(PurpleAccountSyncStore s=store(root)){
            check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.Ready,"open before atomic admission hook");
            final File owned=root;s.require(()->{try{Files.write(new File(owned,"carry.json").toPath(),bytes("{}"));return true;}catch(Exception e){throw new RuntimeException(e);}});
            int writes=AtomicFile.finishWriteCalls;
            check(!s.compareAuto(null,bytes("checkpoint"),()->true),"atomic guard rechecks after admission callback");
            check(!new File(root,"auto.json").exists()&&!new File(root,"auto.json.new").exists()&&AtomicFile.finishWriteCalls==writes,"atomic refusal creates no checkpoint or temporary");
        }
        for(boolean legacy:new boolean[]{false,true})for(String name:new String[]{"state.json","auto.json"}){
            root=seed(parent,"late-finish-"+legacy+"-"+name);
            try(PurpleAccountSyncStore s=store(root)){
                check(s.open(true,0,"device").status==PurpleAccountSyncStore.Status.Ready,"open before final atomic fence");
                File destination=new File(root,name);
                if(!destination.exists())Files.write(destination.toPath(),bytes("existing automatic checkpoint"));
                Map<String,String> original=snapshot(root);
                final File owned=root;final int[] calls={0};
                s.require(()->{calls[0]++;if(calls[0]==2)try{carry(owned,"carry.json");}catch(Exception e){throw new RuntimeException(e);}return true;});
                java.lang.reflect.Method write=PurpleAccountSyncStore.class.getDeclaredMethod("write",File.class,byte[].class);
                write.setAccessible(true);
                int finishes=AtomicFile.finishWriteCalls,failures=AtomicFile.failWriteCalls;
                boolean refused=false;AtomicFile.legacy=legacy;
                try{
                    write.invoke(s,destination,bytes("replacement must not commit"));
                }catch(java.lang.reflect.InvocationTargetException error){
                    check(error.getCause() instanceof java.io.IOException,"final fence reports an I/O refusal");refused=true;
                }finally{AtomicFile.legacy=false;}
                check(refused,"late carry must refuse before atomic commit");
                check(calls[0]==2,"carry arrives after stream write at final admission fence");
                Map<String,String> after=snapshot(root);after.remove("carry.json");
                check(after.equals(original),"atomic rollback preserves previous state and every other file");
                check(AtomicFile.finishWriteCalls==finishes&&AtomicFile.failWriteCalls==failures+1,"exactly one rollback and no committed write");
                check(Arrays.equals(Files.readAllBytes(new File(root,"carry.json").toPath()),bytes("{}")),"late carry evidence remains untouched");
            }
        }
        System.out.println("LEGACY_CARRY_REFUSAL_CHECKS="+checks);
    }
}
