"""Exercise original descriptor ownership and persistence admission without a device."""
import pathlib, subprocess, os, runpy

def run(root, output):
    output.mkdir(parents=True,exist_ok=False)
    java=root/'TMessagesProj/src/main/java/org/telegram'
    def put(name,text):
        p=output/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    for name in ['PurpleLongRoundOriginal','PurpleLongRoundOriginals','PurpleLongRoundPlan','PurpleLongRoundSelection','PurpleRecordingFiles']:
        put('org/telegram/messenger/purple/'+name+'.java',(java/('messenger/purple/'+name+'.java')).read_text())
    put('android/system/StructStat.java','package android.system;public class StructStat {public long st_ino,st_dev,st_size;}')
    put('android/system/Os.java','''package android.system;import java.nio.file.*;import java.util.*;public class Os {public static String openedPath;
      public static StructStat fstat(java.io.FileDescriptor fd)throws Exception{return stat(openedPath);}
      public static StructStat stat(String path)throws Exception{Map<String,Object> a=Files.readAttributes(Path.of(path),"unix:ino,dev,size");StructStat s=new StructStat();s.st_ino=((Number)a.get("ino")).longValue();s.st_dev=((Number)a.get("dev")).longValue();s.st_size=((Number)a.get("size")).longValue();return s;}}''')
    put('android/widget/Toast.java','package android.widget;public class Toast {public static final int LENGTH_LONG=1;public static int notices;public static Toast makeText(Object c,String s,int n){return new Toast();}public void show(){notices++;}}')
    put('org/telegram/messenger/AndroidUtilities.java','package org.telegram.messenger;public class AndroidUtilities{public static void runOnUIThread(Runnable r){r.run();}}')
    put('org/telegram/messenger/purple/PurpleRecordingEntry.java','package org.telegram.messenger.purple;public class PurpleRecordingEntry{public String originalGeneration,snapshotSource;public long originalDevice,originalInode;}')
    put('org/telegram/messenger/ApplicationLoader.java','package org.telegram.messenger;public class ApplicationLoader {public static final Object applicationContext=new Object();}')
    put('org/telegram/messenger/LocaleController.java','package org.telegram.messenger;public class LocaleController {public static String getString(int n){return "notice";}}')
    put('org/telegram/messenger/R.java','package org.telegram.messenger;public class R {public static class string{public static final int PurpleLongRoundOriginalSaving=1,PurpleLongRoundOriginalFailed=2,PurpleLongRoundSelectionInvalid=3;}}')
    put('org/telegram/messenger/VideoEditedInfo.java','package org.telegram.messenger;public class VideoEditedInfo{public long startTime=-1,endTime=-1;}')
    put('org/telegram/messenger/purple/PurpleDiagLog.java','package org.telegram.messenger.purple;public class PurpleDiagLog {public static void event(String... x){}public static String reason(String x){return x;}}')
    put('org/telegram/messenger/purple/PurpleRecordingSendAs.java','package org.telegram.messenger.purple;public class PurpleRecordingSendAs {public static long user=9;public static boolean sameUser(int a,long u){return u==user;}}')
    put('org/telegram/messenger/purple/PurpleRecordingRegistry.java','''package org.telegram.messenger.purple;
      import java.io.*;import java.util.*;import java.util.function.Consumer;
      public class PurpleRecordingRegistry {public static List<Runnable> jobs=new ArrayList<>();public static int rows;public static boolean fail;public static File directory;
       public static boolean keepLongOriginalBlocking(PurpleLongRoundOriginal original){return false;}
       public static long[] roundOriginalTarget(int a,int g){return new long[]{a,100,0,0,9};}
       public static void keepLongOriginal(PurpleLongRoundOriginal original,Consumer<Boolean> done){jobs.add(()->{if(original.binId==null)original.binId="original-"+(++rows);boolean saved=false;try{if(fail)throw new IOException();File copy=new File(directory,original.binId+".mp4");PurpleRecordingFiles.copy(original.channel(),copy);original.verify(copy);saved=true;}catch(Exception e){}done.accept(saved);});}
       public static void step(){jobs.remove(0).run();}}
    ''')
    method=runpy.run_path(str(pathlib.Path(__file__).with_name('bubble_routing.py')))['method']
    erase=method((java/'utils/recorder/round/RoundVideoRemuxer.java').read_text(),'    static void erase(').replace('@NonNull ', '')
    put('org/telegram/messenger/purple/NativeErase.java','package org.telegram.messenger.purple; import java.io.*; public class NativeErase {'+erase.replace('static void erase(', 'public static void erase(')+'}')
    put('OriginalCases.java','''import java.io.*;import java.nio.file.*;import java.util.*;import android.system.Os;import org.telegram.messenger.purple.*;
      public class OriginalCases {static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
       static File source(File root,String name,byte[] bytes)throws Exception{File f=new File(root,name);Files.write(f.toPath(),bytes);Os.openedPath=f.toString();return f;}
       public static void main(String[] args)throws Exception{
        File root=new File(args[0]);root.mkdirs();PurpleRecordingRegistry.directory=root;byte[] full=new byte[8192];new Random(4).nextBytes(full);
        File source=source(root,"preview.mp4",full);
        PurpleLongRoundOriginals.capture(1,7,source,180000);
        PurpleLongRoundOriginals.capture(1,7,source,180000);
        check(PurpleRecordingRegistry.jobs.size()==1,"repeated preview callback owns one pending original");
        check(!PurpleLongRoundOriginals.ready(1,7,source,180000),"scheduling is not successful retention");
        PurpleRecordingRegistry.fail=true;PurpleRecordingRegistry.step();
        check(!PurpleLongRoundOriginals.ready(1,7,source,180000),"failed persistence blocks destructive finish and resume");
        PurpleLongRoundOriginals.release(1,7);NativeErase.erase(source);check(!source.exists()||source.length()==full.length,"native erase may defer cleanup only to preserve all original bytes");
        PurpleRecordingRegistry.fail=false;PurpleRecordingRegistry.step();
        check(Arrays.equals(full,Files.readAllBytes(new File(root,"original-1.mp4").toPath())),"held descriptor preserves full bytes after cleanup and persistence failure retry");
        check(PurpleRecordingRegistry.rows==1,"persistence retry reuses original entry");
        source=source(root,"second.mp4",full);PurpleLongRoundOriginals.capture(1,8,source,180000);
        PurpleRecordingRegistry.step();
        check(PurpleLongRoundOriginals.ready(1,8,source,180000),"synced indexed result admits native destructive work");
        PurpleRecordingSendAs.user=10;check(!PurpleLongRoundOriginals.ready(1,8,source,180000),"replacement account cannot use stale success");PurpleRecordingSendAs.user=9;
        File changed=source(root,"replacement.mp4",new byte[]{1,2,3});Files.move(changed.toPath(),source.toPath(),StandardCopyOption.REPLACE_EXISTING);Os.openedPath=source.toString();
        check(!PurpleLongRoundOriginals.ready(1,8,source,180000),"replacement inode cannot reuse retained original identity");
        check(PurpleRecordingRegistry.jobs.size()==1,"replacement captured independently");PurpleRecordingRegistry.step();
        check(PurpleLongRoundOriginals.ready(1,8,source,180000),"replacement admitted only after its own persistence");
        PurpleLongRoundOriginals.release(1,8);
        File first=source(root,"stale-first.mp4",full);PurpleLongRoundOriginals.capture(1,9,first,180000);
        File newer=source(root,"stale-new.mp4",full);PurpleLongRoundOriginals.capture(1,9,newer,180000);
        PurpleRecordingRegistry.step();check(!PurpleLongRoundOriginals.ready(1,9,newer,180000),"late old callback cannot bless new source");
        PurpleRecordingRegistry.step();check(PurpleLongRoundOriginals.ready(1,9,newer,180000),"new source needs its own acknowledgement");
        PurpleLongRoundOriginals.release(1,9);
        check(PurpleLongRoundOriginals.ready(1,10,null,180000)&&PurpleLongRoundOriginals.ready(1,10,newer,60000),"active or short source keeps existing behavior");
        System.out.println("LONG_ROUND_ORIGINAL_CHECKS="+checks);
       }}''')
    compiler=pathlib.Path(os.environ['JAVA_HOME'])/'bin/javac'
    subprocess.run([str(compiler),'-d',str(output/'classes'),*map(str,output.rglob('*.java'))],check=True)
    subprocess.run([str(compiler.with_name('java')),'-cp',str(output/'classes'),'OriginalCases',str(output/'files')],check=True)
    v2=(java/'ui/Components/InstantCameraView2.java').read_text()
    preview=v2.split('public void onPreviewReady(',1)[1].split('public void',1)[0]
    assert preview.index('PurpleLongRoundOriginals.capture(')<preview.index('postNotificationName(')
    send=v2.split('    public void send(',1)[1].split('    @Override',1)[0]
    assert send.index('PurpleLongRoundOriginals.beforeV2(')<send.index('PurpleRecordingDock.beginRoundFinish(')<send.index('session.finish(')
    resume=v2.split('public void togglePause()',1)[1].split('    @Override',1)[0]
    assert resume.index('PurpleLongRoundOriginals.beforeV2(')<resume.index('session.resumeRecording(')
    native=(java/'utils/recorder/round/RoundVideoSession.java').read_text()
    assert 'replaceWithFinalRange(source, trimStartMs, trimEndMs, includeAudio, reason)' in native
    assert native.index('purpleOriginalOwner.capture(snapshot,') < native.index('ensureNotCancelled();',native.index('currentWriter.createPreview(snapshot);'))
    print('LONG_ROUND_ORIGINAL_ORDER_PINS=5')
    durable(root,output/'durable',compiler,output/'classes')


def durable(root, output, compiler, ports):
    output.mkdir()
    java=root/'TMessagesProj/src/main/java/org/telegram'
    def put(name,text):
        p=output/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    for name in ['PurpleRecordingEntry','PurpleRecordingStore','PurpleRecordingManifest','PurpleRecordingFiles','PurpleRecordingSessions','PurpleRecordingSnapshotHandoff','PurpleRecordingCore']:
        text=(java/('messenger/purple/'+name+'.java')).read_text()
        if name=='PurpleRecordingManifest':
            text=text.replace('        if (!temp.renameTo(target)) {','        DurableProbe.before(bytes);\n        if (!temp.renameTo(target)) {')
            text=text.replace('            throw new IOException("rename failed");\n        }','            throw new IOException("rename failed");\n        }\n        DurableProbe.after(bytes);')
        put('org/telegram/messenger/purple/'+name+'.java',text)
    put('org/telegram/messenger/purple/PurpleCore.java','package org.telegram.messenger.purple;public class PurpleCore {public static void ensureLoaded(){}}')
    put('org/telegram/messenger/purple/PurpleRecordingSettings.java','package org.telegram.messenger.purple;public class PurpleRecordingSettings{public boolean keepAcrossChats,background;public static PurpleRecordingSettings read(){return new PurpleRecordingSettings();}}')
    put('org/telegram/messenger/FileLog.java','package org.telegram.messenger;public class FileLog{public static void e(Object error){}}')
    method=runpy.run_path(str(pathlib.Path(__file__).with_name('bubble_routing.py')))['method']
    registry=(java/'messenger/purple/PurpleRecordingRegistry.java').read_text()
    keep=method(registry,'    private static boolean keepLongOriginalOnWorker(')
    put('org/telegram/messenger/purple/PurpleRecordingRegistry.java',r'''package org.telegram.messenger.purple;
      import java.io.*;import java.util.*;import java.util.function.Consumer;
      public class PurpleRecordingRegistry {
       static PurpleRecordingStore store;static File root;static java.util.List<Runnable> jobs=new ArrayList<>();
       static File directory(){return root;}static void changed(){}
       public static long[] roundOriginalTarget(int account,int guid){return PurpleRecordingSendAs.sameUser(account,9)?new long[]{account,100,8,4,9,1}:null;}
       public static boolean keepLongOriginalBlocking(PurpleLongRoundOriginal original){return keepLongOriginalOnWorker(original);}
       public static void keepLongOriginal(PurpleLongRoundOriginal original,Consumer<Boolean> done){jobs.add(()->done.accept(keepLongOriginalOnWorker(original)));}
       static void step(){jobs.remove(0).run();}
    '''+keep+'}')
    put('org/telegram/messenger/purple/DurableProbe.java',r'''package org.telegram.messenger.purple;
      import java.io.*;import org.json.*;
      public class DurableProbe {static boolean blockCopy,failCommit,failReservation;static File blocked;static int hits;
       static void before(byte[] bytes)throws IOException{String manifest=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);if(failReservation&&manifest.contains("\"original_generation\"")&&!manifest.contains("\"snapshot_committed\":true")){hits++;throw new IOException("inert reservation failure");}if(failCommit&&new String(bytes,java.nio.charset.StandardCharsets.UTF_8).contains("\"snapshot_committed\":true")){hits++;throw new IOException("inert commit failure");}}
       static void after(byte[] bytes)throws IOException{try{if(!blockCopy)return;JSONObject root=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));JSONArray rows=root.getJSONArray("entries");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.has("original_generation")&&!row.optBoolean("snapshot_committed")){File bin=new File(PurpleRecordingRegistry.root,"bin");if(!bin.isDirectory()&&!bin.mkdirs())throw new IOException("inert bin setup");blocked=new File(bin,row.getString("id")+".mp4");if(!blocked.exists()&&!blocked.mkdir())throw new IOException();hits++;}}}catch(JSONException e){throw new IOException(e);}}
      }''')
    put('org/telegram/messenger/purple/DurableCases.java',r'''package org.telegram.messenger.purple;
      import java.io.*;import java.nio.file.*;import java.util.*;import android.system.Os;
      public class DurableCases {
       static int checks;static byte[] full;static File root;static final long[] target={1,100,8,4,9,1};
       static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
       static final PurpleRecordingStore.Core CORE=new PurpleRecordingStore.Core(){
        public PurpleRecordingCore.OggFinish finaliseOgg(File f){throw new AssertionError();}public PurpleRecordingCore.OggScan scanOgg(File f){throw new AssertionError();}
        public boolean binItemExpired(long b,long n,int k){return false;}public PurpleRecordingStore.RoundResult recoverRound(File m,File j,File o){throw new AssertionError();}
        public boolean matchesLongOriginal(PurpleRecordingEntry e,File f){return PurpleLongRoundOriginal.matchesReservation(e,f);}
       };
       static class Env implements PurpleRecordingSessions.Env {
        public long now(){return 400000;}public void schedule(Runnable r,long delay){}public File newestVoice(long started){return null;}
        public boolean keepDiscarded(){return false;}public boolean keepLongOriginal(File f){return PurpleLongRoundOriginals.protectWriter(f);}
        public void flushJournal(String p){}public void closeJournal(String p){}public void changed(){}public void discarded(String k,boolean a,boolean b){}
       }
       static File source(String name)throws Exception{File f=new File(root,name);Files.write(f.toPath(),full);Os.openedPath=f.toString();return f;}
       static PurpleRecordingStore restart(File directory)throws Exception{PurpleRecordingStore s=new PurpleRecordingStore(new PurpleRecordingManifest(directory),CORE,401);s.recoverAtStart(401);return s;}
       static PurpleRecordingEntry original(PurpleRecordingStore store){for(PurpleRecordingEntry e:store.entries())if(e.longOriginal)return e;throw new AssertionError("missing reserved original");}
       static void setup(String name)throws Exception{root=new File(root.getParentFile(),name);root.mkdirs();PurpleRecordingRegistry.root=new File(root,"registry");PurpleRecordingRegistry.store=new PurpleRecordingStore(new PurpleRecordingManifest(PurpleRecordingRegistry.root),CORE,400);}
       static void reservationCancel()throws Exception{
        setup("copy-failure");File writer=source("round_video_writer.mp4"),preview=source("round_video_preview.mp4");
        PurpleRecordingSessions sessions=new PurpleRecordingSessions(PurpleRecordingRegistry.store,new Env());sessions.roundRegistered(writer,null);sessions.started("1:7",1,target,false,400000);
        DurableProbe.blockCopy=true;PurpleLongRoundOriginals.Owner owner=PurpleLongRoundOriginals.owner(1,7);owner.capture(preview,writer,75000);
        PurpleRecordingEntry e=original(PurpleRecordingRegistry.store);String id=e.id;
        check(e.awaitingQueue&&e.originalGeneration!=null&&e.userId==9&&e.snapshotSource.equals(preview.toString()),"durable reservation owns frozen full preview before copying ACK");
        PurpleRecordingStore disk=new PurpleRecordingStore(new PurpleRecordingManifest(PurpleRecordingRegistry.root),CORE,401);
        check(original(disk).originalInode==e.originalInode&&original(disk).originalDevice==e.originalDevice,"manifest roundtrips exact source identity and generation");
        check(!PurpleLongRoundOriginals.ready(1,7,preview,75000),"copy failure is not successful retention");
        check(PurpleRecordingSnapshotHandoff.stop(sessions,"1:7",6,target,(task,delay)->task.run()),"actual writer handoff admits independently protected original with keep-discarded OFF");
        check(PurpleRecordingRegistry.store.bin().size()==1,"registered writer preserved while preview copy is pending");
        PurpleLongRoundOriginals.release(1,7);NativeErase.erase(preview);
        check(preview.isFile()&&Arrays.equals(full,Files.readAllBytes(preview.toPath())),"actual truncate-before-unlink deferred through cancel destroy and failed save");
        check(DurableProbe.hits>0&&DurableProbe.blocked.isDirectory(),"production copy failure boundary exercised");
        DurableProbe.blockCopy=false;check(DurableProbe.blocked.delete(),"remove only synthetic failure obstacle");
        PurpleRecordingStore recovered=restart(PurpleRecordingRegistry.root);e=recovered.find(id);
        check(e.inBin()&&e.longOriginal&&e.userId==9&&Arrays.equals(full,Files.readAllBytes(new File(e.media).toPath())),"reconstructed store recovers original without pending descriptor map");
        check(!preview.exists(),"reserved source retired only after media and manifest commit");
        PurpleRecordingStore again=restart(PurpleRecordingRegistry.root);check(again.find(id).inBin()&&again.entries().size()==recovered.entries().size(),"recovery idempotence cannot add duplicate originals");
       }
       static void failedCommit()throws Exception{
        setup("commit-failure");File preview=source("preview.mp4");DurableProbe.failCommit=true;int hits=DurableProbe.hits;
        PurpleLongRoundOriginals.Owner owner=PurpleLongRoundOriginals.owner(1,8);owner.capture(preview,null,75000);
        PurpleRecordingEntry e=original(PurpleRecordingRegistry.store);String id=e.id;
        check(DurableProbe.hits>hits&&e.awaitingQueue&&!e.snapshotCommitted,"failed final manifest commit rolls back visible ACK");
        NativeErase.erase(preview);check(preview.isFile()&&Arrays.equals(full,Files.readAllBytes(preview.toPath())),"completed copy without manifest ACK cannot release native source");
        DurableProbe.failCommit=false;PurpleRecordingStore recovered=restart(PurpleRecordingRegistry.root);e=recovered.find(id);
        check(e.inBin()&&!e.awaitingQueue&&Arrays.equals(full,Files.readAllBytes(new File(e.media).toPath())),"restart settles committed reservation after copy-before-manifest interruption");
       }
       static void failedReservation()throws Exception{
        setup("reservation-failure");File writer=source("round_video_writer.mp4"),preview=source("preview.mp4");PurpleRecordingSessions sessions=new PurpleRecordingSessions(PurpleRecordingRegistry.store,new Env());sessions.roundRegistered(writer,null);sessions.started("1:11",1,target,false,400000);
        DurableProbe.failReservation=true;int hits=DurableProbe.hits;PurpleLongRoundOriginals.Owner owner=PurpleLongRoundOriginals.owner(1,11);owner.capture(preview,writer,75000);
        check(DurableProbe.hits>hits,"actual reservation manifest failure reached before copy");
        check(!PurpleLongRoundOriginals.ready(1,11,preview,75000),"reservation write failure never admits native finish");
        check(PurpleRecordingSnapshotHandoff.stop(sessions,"1:11",6,target,(task,delay)->task.run()),"existing registered-writer reservation protects cancellation when new reservation fails");
        NativeErase.erase(preview);check(preview.isFile()&&Arrays.equals(full,Files.readAllBytes(preview.toPath())),"failed reservation keeps live source bytes without FD-only reliance");
        DurableProbe.failReservation=false;PurpleRecordingStore recovered=restart(PurpleRecordingRegistry.root);check(recovered.bin().size()==1&&Arrays.equals(full,Files.readAllBytes(new File(recovered.bin().get(0).media).toPath())),"existing durable writer recovery survives keep-off and failed new manifest");
        while(!PurpleRecordingRegistry.jobs.isEmpty())PurpleRecordingRegistry.step();
        check(original(PurpleRecordingRegistry.store).inBin(),"reservation retry commits the same owned full preview after storage recovers");
       }
       static void replacement()throws Exception{
        setup("replacement");File preview=source("preview.mp4");DurableProbe.blockCopy=true;PurpleLongRoundOriginals.Owner owner=PurpleLongRoundOriginals.owner(1,9);owner.capture(preview,null,75000);String id=original(PurpleRecordingRegistry.store).id;
        DurableProbe.blockCopy=false;DurableProbe.blocked.delete();File other=source("other.mp4");byte[] different=full.clone();different[different.length-1]^=1;Files.write(other.toPath(),different);Files.move(other.toPath(),preview.toPath(),StandardCopyOption.REPLACE_EXISTING);
        PurpleRecordingStore recovered=restart(PurpleRecordingRegistry.root);check(recovered.find(id).unfinished()&&recovered.bin().isEmpty(),"recovery cannot adopt replacement inode as old original");
        NativeErase.erase(preview);check(Arrays.equals(different,Files.readAllBytes(preview.toPath())),"stale path cannot erase a replacement capture");
       }
       static void acknowledged()throws Exception{
        setup("acknowledged");File preview=source("preview.mp4");PurpleLongRoundOriginals.Owner owner=PurpleLongRoundOriginals.owner(1,10);owner.capture(preview,null,75000);String id=original(PurpleRecordingRegistry.store).id;int count=PurpleRecordingRegistry.store.entries().size();
        owner.capture(preview,null,75000);check(PurpleRecordingRegistry.store.entries().size()==count,"repeated native callback reuses original reservation");
        check(PurpleLongRoundOriginals.ready(1,10,preview,75000),"only synced media and committed manifest admit trim/resume");
        PurpleRecordingSendAs.user=10;check(!PurpleLongRoundOriginals.ready(1,10,preview,75000),"account replacement cannot adopt old captured owner");PurpleRecordingSendAs.user=9;
        PurpleLongRoundOriginals.release(1,10);NativeErase.erase(preview);check(!preview.exists(),"ACK releases exactly original native erase");
        PurpleRecordingEntry e=restart(PurpleRecordingRegistry.root).find(id);check(e.userId==9&&Arrays.equals(full,Files.readAllBytes(new File(e.media).toPath())),"native cleanup after ACK preserves independent full original");
        File ordinary=source("unprotected.mp4");FileInputStream held=new FileInputStream(ordinary);NativeErase.erase(ordinary);check(!ordinary.exists()&&held.getChannel().size()==0,"unprotected erase retains real truncate semantics");held.close();
       }
       public static void main(String[] args)throws Exception{full=Files.readAllBytes(Path.of(args[0]));root=new File(args[1],"start");root.mkdirs();reservationCancel();failedCommit();failedReservation();replacement();acknowledged();System.out.println("LONG_ROUND_DURABLE_CHECKS="+checks);}
      }''')
    jars=sorted((pathlib.Path(os.environ['GRADLE_USER_HOME'])/'caches/modules-2/files-2.1/org.json/json').glob('*/*/json-*.jar'))
    assert jars,'org.json host jar missing'
    cp=os.pathsep.join([str(output/'classes'),str(ports),str(jars[-1])])
    subprocess.run([str(compiler),'-cp',cp,'-d',str(output/'classes'),*map(str,output.rglob('*.java'))],check=True)
    source=output/'full.mp4'
    subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','color=black:size=160x160:rate=1','-t','75','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p',str(source)],check=True)
    subprocess.run([str(compiler.with_name('java')),'-cp',cp,'org.telegram.messenger.purple.DurableCases',str(source),str(output/'cases')],check=True)
