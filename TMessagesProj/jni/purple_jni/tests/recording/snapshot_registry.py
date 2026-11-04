"""Run the actual registry stop branch and snapshot/storage owners with controlled dispatch."""
import pathlib, subprocess, os, shutil, json, hashlib

def run(root, output, compiler, classes, jar, baseline=None):
    output.mkdir()
    tests=pathlib.Path(__file__).parent
    java=root/'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    def source(name):
        path=pathlib.Path(baseline)/('before-'+name) if baseline else java/name
        return path.read_text() if path.exists() else None
    inputs=[]
    for name in ['PurpleRecordingEntry.java','PurpleRecordingManifest.java','PurpleRecordingFiles.java','PurpleRecordingStore.java','PurpleRecordingSessions.java','PurpleRecordingSnapshotHandoff.java']:
        text=source(name)
        if text is None:continue
        inputs.append({'source':name,'sha256':hashlib.sha256(text.encode()).hexdigest()})
        if name=='PurpleRecordingManifest.java':
            marker='        if (!temp.renameTo(target)) {'
            assert text.count(marker)==1
            text=text.replace(marker,'        SnapshotProbe.beforeManifest(bytes);\n        if (!temp.renameTo(target)) {')
            marker='            throw new IOException("rename failed");\n        }'
            assert text.count(marker)==1
            text=text.replace(marker,marker+'\n        SnapshotProbe.afterManifest(bytes);')
        if name=='PurpleRecordingSnapshotHandoff.java':
            text=text.replace('final boolean renamed = capture.media.renameTo(stop.destination);','final boolean renamed = SnapshotProbe.rename(capture.media, stop.destination);')
        (output/name).write_text(text)
    registry=source('PurpleRecordingRegistry.java')
    inputs.append({'source':'PurpleRecordingRegistry.java','sha256':hashlib.sha256(registry.encode()).hexdigest()})
    start=registry.index('            final int reason =',registry.index('} else if (event == NotificationCenter.recordStopped)'))
    stop=registry.index('        } else if (event == NotificationCenter.audioDidSent)',start)
    branch=registry[start:stop]
    if baseline:
        assert branch.count('source.renameTo(moved)')==1
        branch=branch.replace('source.renameTo(moved)','SnapshotProbe.rename(source, moved)')
    (output/'RegistryStopRoute.java').write_text("""package org.telegram.messenger.purple;
import java.io.*;import java.util.*;import java.util.concurrent.TimeUnit;
public class RegistryStopRoute {
 static PurpleRecordingSessions sessions;static File root;static long[] context;static Worker worker=new Worker();
 static File directory(){return root;}static long[] target(int guid,boolean terminal){return context;}
 static class Worker {List<Runnable> tasks=new ArrayList<>();void schedule(Runnable r,long delay,TimeUnit unit){tasks.add(r);}void execute(Runnable r){tasks.add(r);}}
 static void stop(int account,int guid,int value){Object[] args={guid,value};String key=account+":"+guid;
"""+branch+'}}')
    (output/'SnapshotProbe.java').write_text("""package org.telegram.messenger.purple;
import java.io.*;import java.nio.charset.StandardCharsets;
class SnapshotProbe {
 static String armed;static int hits;static Runnable onBoundary;
 static class Crash extends Error {}
 static void at(String point){if(point.equals(armed)){armed=null;hits++;if(onBoundary!=null)onBoundary.run();throw new Crash();}}
 static boolean rename(File source,File dest){at("before-rename");boolean result=source.renameTo(dest);if(result)at("after-rename");return result;}
 static void beforeManifest(byte[] bytes){at("before-manifest");if(new String(bytes,StandardCharsets.UTF_8).contains("snapshot_base"))at("before-reservation");}
 static void afterManifest(byte[] bytes){at("after-manifest");if(new String(bytes,StandardCharsets.UTF_8).contains("snapshot_base"))at("after-reservation");}
}
""")
    for name in ['SnapshotRegistryCases.java','PaymentStoreCases.java','BoundaryStoreCases.java']:
        text=(tests/name).read_text()
        if name=='BoundaryStoreCases.java':
            # Only its deterministic MP4-prefix helper and core are needed here.
            text=text[:text.index(' public static void main(')]+'}\n'
        (output/name).write_text(text)
    cp=os.pathsep.join([str(output/'classes'),str(classes),str(jar)])
    (output/'inputs.json').write_text(json.dumps(inputs,indent=2)+'\n')
    subprocess.run([str(compiler),'-proc:none','-sourcepath',str(output/'empty'),'-cp',cp,'-d',str(output/'classes'),*map(str,sorted(output.glob('*.java')))],check=True)
    return [str(compiler.with_name('java')),'-cp',cp,'org.telegram.messenger.purple.SnapshotRegistryCases']
