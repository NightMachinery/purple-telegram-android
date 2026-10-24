package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class PurpleSyncRecoveryFiles {
    static final int FILE_LIMIT = 4 * 1024 * 1024, JOURNAL_LIMIT = 64 * 1024 * 1024;
    interface Current { boolean current(); }
    interface Moves {
        boolean move(File source, File destination, String identity, String sourceParent, String destinationParent);
    }
    static final Moves NATIVE_MOVES = (a,b,id,from,to) -> moveNative(a.getPath(), b.getPath(), id, from, to);
    static final class Snapshot {
        final File file;
        final boolean directory;
        final int limit;
        final PurpleGlobalChoiceFiles.Mark mark;
        final byte[] bytes;
        final String[] entries;
        Snapshot(File file, boolean directory, int limit, PurpleGlobalChoiceFiles.Physical physical) throws Exception {
            this.file=file; this.directory=directory; this.limit=limit;
            mark=physical.mark(file,directory);
            bytes=directory || mark.missing() ? null : read(file,limit);
            entries=directory && !mark.missing() ? names(file) : null;
            if (!mark.same(physical.mark(file,directory))) throw new IllegalStateException("recovery input changed");
        }
        boolean current(PurpleGlobalChoiceFiles.Physical physical) {
            try {
                Snapshot next=new Snapshot(file,directory,limit,physical);
                return mark.same(next.mark) && Arrays.equals(bytes,next.bytes) && Arrays.equals(entries,next.entries);
            } catch (Exception e) { return false; }
        }
    }
    static final class Tree {
        final File root;
        final PurpleGlobalChoiceFiles.Physical physical;
        final LinkedHashMap<File,Snapshot> files=new LinkedHashMap<>();
        private int total;
        Tree(File root,PurpleGlobalChoiceFiles.Physical physical) { this.root=root; this.physical=physical; }
        Snapshot capture(File file,boolean directory) throws Exception { return capture(file,directory,FILE_LIMIT); }
        Snapshot capture(File file,boolean directory,int limit) throws Exception {
            if (!file.equals(root) && !file.getAbsolutePath().startsWith(root.getAbsolutePath()+File.separator)) throw new IllegalStateException("recovery path outside root");
            ArrayList<File> parents=new ArrayList<>();
            for(File parent=file.getParentFile();parent!=null&&!parent.equals(root.getParentFile());parent=parent.getParentFile())parents.add(0,parent);
            for(File parent:parents) if(!files.containsKey(parent))files.put(parent,new Snapshot(parent,true,0,physical));
            Snapshot previous=files.get(file);
            if(previous!=null) { if(previous.directory!=directory)throw new IllegalStateException("recovery type changed"); return previous; }
            Snapshot snapshot=new Snapshot(file,directory,limit,physical);
            int size=snapshot.bytes!=null?snapshot.bytes.length:0;
            if(limit!=JOURNAL_LIMIT) {
                if(size>16*1024*1024-total)throw new IllegalStateException("recovery discovery exceeds bound");
                total+=size;
            }
            files.put(file,snapshot);return snapshot;
        }
        boolean current() { for(Snapshot snapshot:files.values())if(!snapshot.current(physical))return false;return true; }
        boolean currentExcept(File scope) {
            for(Snapshot snapshot:files.values()) if(!snapshot.file.equals(scope)
                    && !snapshot.file.getPath().startsWith(scope.getPath()+File.separator) && !snapshot.current(physical))return false;
            return true;
        }
    }
    static final class InactiveTokens {
        private int remaining=16*1024*1024;
        String inspect(File file,PurpleGlobalChoiceFiles.Physical physical) {
            if(remaining==0)return null;
            int limit=Math.min(FILE_LIMIT,remaining);
            Snapshot snapshot;
            try { snapshot=new Snapshot(file,false,limit,physical); }
            catch(Exception e) { remaining-=limit;return null; }
            remaining-=snapshot.bytes!=null?snapshot.bytes.length:0;
            if(snapshot.bytes==null)return null;
            PurpleAccountSyncCore.Result parsed=PurpleAccountSyncCore.inspectState(snapshot.bytes);
            return parsed.isValid()&&PurpleAccountBinding.isToken(parsed.bindingToken)?parsed.bindingToken:null;
        }
    }
    static final class Lock implements AutoCloseable {
        private final String key;
        private final int fd;
        private boolean closed;
        Lock(File root,String identity) throws Exception {
            key=PurpleAccountSyncStore.claimRecovery(root);
            if(key==null)throw new IllegalStateException("sync store busy");
            int descriptor;
            try { descriptor=lockNative(root.getPath(),identity); }
            catch(RuntimeException|LinkageError e) { PurpleAccountSyncStore.releaseRecovery(key);throw e; }
            fd=descriptor;
            if(fd<0) { PurpleAccountSyncStore.releaseRecovery(key);throw new IllegalStateException("sync store locked"); }
        }
        public void close() { if(!closed) { closed=true;closeNative(fd);PurpleAccountSyncStore.releaseRecovery(key); } }
    }
    static String[] names(File directory) throws Exception {
        String[] names=directory.list();
        if(names==null || names.length>10000)throw new IllegalStateException("incomplete recovery directory");
        Arrays.sort(names);return names;
    }
    static byte[] read(File file,int limit) throws Exception {
        try(FileInputStream input=new FileInputStream(file)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1) { if(out.size()>limit-count)throw new IllegalStateException("recovery input exceeds bound");out.write(buffer,0,count); }
            return out.toByteArray();
        }
    }
    static Snapshot publish(File target,Snapshot expected,byte[] bytes,PurpleGlobalChoiceFiles.Physical physical,Current guard) {
        File temp=null;PurpleGlobalChoiceFiles.Mark owned=null;boolean moved=false;
        try {
            if(bytes==null || bytes.length>JOURNAL_LIMIT || !guard.current() || !expected.current(physical))return null;
            PurpleGlobalChoiceFiles.Created created=PurpleAppWideRemovalModel.ownedTemporary(target.getParentFile());
            if(created==null)return null;temp=created.file;owned=created.mark;
            if(!guard.current() || !expected.current(physical) || !writeNative(temp.getPath(),owned.identity,bytes))return null;
            Snapshot written=new Snapshot(temp,false,JOURNAL_LIMIT,physical);
            if(!owned.identity.equals(written.mark.identity) || (written.mark.mode&0777)!=0600 || !Arrays.equals(bytes,written.bytes)
                    || !guard.current() || !expected.current(physical) || !written.current(physical) || !moveNative(temp.getPath(),target.getPath(),owned.identity,
                            physical.mark(target.getParentFile(),true).identity,physical.mark(target.getParentFile(),true).identity))return null;
            moved=true;Snapshot actual=new Snapshot(target,false,JOURNAL_LIMIT,physical);
            if(!owned.identity.equals(actual.mark.identity) || !Arrays.equals(bytes,actual.bytes))return null;
            return syncNative(target.getParent(),physical.mark(target.getParentFile(),true).identity)?actual:null;
        } catch(Exception|LinkageError e) { return null; }
        finally { if(temp!=null&&!moved&&owned!=null)try { if(owned.identity.equals(physical.mark(temp,false).identity))temp.delete(); }catch(Exception e) { } }
    }
    static boolean archiveName(String name) { return name.matches("orphaned-[0-9]{8}-[0-9]{6}"); }
    static boolean key(String value) {
        if(value==null||!value.matches("[01]-[1-9][0-9]*"))return false;
        try{return Long.parseLong(value.substring(2))>0;}catch(NumberFormatException e){return false;}
    }
    static native boolean moveNative(String source,String destination,String expected,String sourceParent,String destinationParent);
    static native boolean syncNative(String path,String expected);
    static native int lockNative(String path,String expected);
    static native void closeNative(int fd);
    static native boolean writeNative(String path,String expected,byte[] bytes);
    private PurpleSyncRecoveryFiles() { }
}
