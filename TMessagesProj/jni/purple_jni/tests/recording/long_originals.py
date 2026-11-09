"""Exercise original descriptor ownership and persistence admission without a device."""
import pathlib, subprocess, os

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
    put('org/telegram/messenger/ApplicationLoader.java','package org.telegram.messenger;public class ApplicationLoader {public static final Object applicationContext=new Object();}')
    put('org/telegram/messenger/LocaleController.java','package org.telegram.messenger;public class LocaleController {public static String getString(int n){return "notice";}}')
    put('org/telegram/messenger/R.java','package org.telegram.messenger;public class R {public static class string{public static final int PurpleLongRoundOriginalSaving=1,PurpleLongRoundOriginalFailed=2,PurpleLongRoundSelectionInvalid=3;}}')
    put('org/telegram/messenger/VideoEditedInfo.java','package org.telegram.messenger;public class VideoEditedInfo{public long startTime=-1,endTime=-1;}')
    put('org/telegram/messenger/purple/PurpleDiagLog.java','package org.telegram.messenger.purple;public class PurpleDiagLog {public static void event(String... x){}public static String reason(String x){return x;}}')
    put('org/telegram/messenger/purple/PurpleRecordingSendAs.java','package org.telegram.messenger.purple;public class PurpleRecordingSendAs {public static long user=9;public static boolean sameUser(int a,long u){return u==user;}}')
    put('org/telegram/messenger/purple/PurpleRecordingRegistry.java','''package org.telegram.messenger.purple;
      import java.io.*;import java.util.*;import java.util.function.Consumer;
      public class PurpleRecordingRegistry {public static List<Runnable> jobs=new ArrayList<>();public static int rows;public static boolean fail;public static File directory;
       public static long[] roundOriginalTarget(int a,int g){return new long[]{a,100,0,0,9};}
       public static void keepLongOriginal(PurpleLongRoundOriginal original,Consumer<Boolean> done){jobs.add(()->{if(original.binId==null)original.binId="original-"+(++rows);boolean saved=false;try{if(fail)throw new IOException();File copy=new File(directory,original.binId+".mp4");PurpleRecordingFiles.copy(original.channel(),copy);original.verify(copy);saved=true;}catch(Exception e){}done.accept(saved);});}
       public static void step(){jobs.remove(0).run();}}
    ''')
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
        PurpleLongRoundOriginals.release(1,7);check(source.delete(),"native cleanup can unlink paused source");
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
    print('LONG_ROUND_ORIGINAL_ORDER_PINS=4')
