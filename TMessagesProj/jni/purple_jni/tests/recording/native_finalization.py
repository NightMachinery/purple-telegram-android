"""Native failure notifications through real sessions, synced manifest and reconstructed store."""
import hashlib,json,os,pathlib,runpy,subprocess
PARENT='7ecbc245ffa69d8e5ce0ec6d54a92b38ae14ca35'
eos=runpy.run_path(str(pathlib.Path(__file__).with_name('native_eos.py')))
method=eos['method']

def run(root,output,parent=False,case='all'):
    jars=sorted((pathlib.Path(os.environ['GRADLE_USER_HOME'])/'caches/modules-2/files-2.1/org.json/json').glob('*/*/json-*.jar'))
    assert jars,'org.json host jar missing'
    eos['run'].__globals__['PARENT']=PARENT
    java=root/'TMessagesProj/src/main/java/org/telegram'
    def source(name):
        path='TMessagesProj/src/main/java/org/telegram/messenger/purple/'+name+'.java'
        return subprocess.check_output(['git','show',PARENT+':'+path],cwd=root,text=True) if parent else (root/path).read_text()
    def prepare(root,out,native):
        def put(name,text):
            p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
        p=out/'InstantCameraView.java';t=p.read_text().replace(eos['CASES'],CASES).replace('static final boolean WRITE_TO_FILE_IN_BACKGROUND=false','static boolean WRITE_TO_FILE_IN_BACKGROUND=false');p.write_text(t)
        names=['PurpleLegacyCodecEos','PurpleRecordingEntry','PurpleRecordingManifest','PurpleRecordingStore','PurpleRecordingFiles','PurpleRecordingSessions','PurpleRecordingCore']
        hashes={}
        for name in names:
            text=source(name);hashes[name]=hashlib.sha256(text.encode()).hexdigest();put('org/telegram/messenger/purple/'+name+'.java',text)
        registry=source('PurpleRecordingRegistry')
        dispatch=method(registry,'    private static void onEvent(')
        retention=method(registry,'    public static void retainFailedLegacyRound(') if '    public static void retainFailedLegacyRound(' in registry else 'public static void retainFailedLegacyRound(int a,int g,String p,java.util.function.Consumer<Boolean> done){throw new AssertionError("unused new API in parent test");}'
        extra_signatures=['    private static void retainFailedLegacyRoundOnWorker(', '    public static boolean mayDeleteFailedLegacyRound(', '    public static File legacyRoundWriter(']
        extra='\n'.join(method(registry,sig) for sig in extra_signatures if sig in registry)
        if extra_signatures[1] not in registry: extra+='public static boolean mayDeleteFailedLegacyRound(File f){throw new AssertionError("unused new unlink API in parent control");}public static File legacyRoundWriter(File f){throw new AssertionError("unused new writer API in parent control");}'
        put('org/telegram/messenger/purple/PurpleRecordingRegistry.java',r'''package org.telegram.messenger.purple;
import java.io.*;import java.util.*;import java.util.concurrent.TimeUnit;import org.telegram.messenger.*;
public class PurpleRecordingRegistry {
 public interface ChatTarget{long[] target();}
 static Map<Integer,ChatTarget> chats=new HashMap<>();public static PurpleRecordingSessions sessions;public static PurpleRecordingStore store;public static File directory;
 public static class Worker{public List<Runnable> jobs=new ArrayList<>();public void execute(Runnable r){jobs.add(r);}void schedule(Runnable r,long delay,TimeUnit unit){}}
 public static Worker worker=new Worker();static void start(){}static long[] target(int guid){return target(guid,false);}static long[] target(int guid,boolean terminal){return chats.get(guid).target();}
 public static void dispatch(int event,int account,Object...args){onEvent(event,account,args);}
 public static void flush(){int limit=100;while(!worker.jobs.isEmpty()){if(--limit==0)throw new AssertionError("registry worker queue");worker.jobs.remove(0).run();}}
 public static void register(File media,File journal){worker.execute(()->sessions.roundRegistered(media,journal));}
 public static void setup(File root){directory=root;store=new PurpleRecordingStore(new PurpleRecordingManifest(root),FailureStore.CORE,400);sessions=new PurpleRecordingSessions(store,new FailureStore.Env());chats.clear();chats.put(7,()->new long[]{0,100,8,4,9,0});worker.jobs.clear();}
}'''.replace('\n}', '\n'+dispatch+'\n'+retention+'\n'+extra+'\n}',1))
        put('org/telegram/messenger/purple/PurpleRecordingSnapshotHandoff.java','package org.telegram.messenger.purple;public class PurpleRecordingSnapshotHandoff{public interface Queue{void run(Runnable r,long d);}public static boolean stop(PurpleRecordingSessions s,String k,int r,long[] t,Queue q){if(s.snapshotSource(k)!=null)throw new AssertionError("legacy fixture must not enter V2 handoff");return false;}}')
        put('org/telegram/messenger/purple/PurpleCore.java','package org.telegram.messenger.purple;public class PurpleCore{public static void ensureLoaded(){}}')
        put('org/telegram/messenger/purple/PurpleRecordingSettings.java','package org.telegram.messenger.purple;public class PurpleRecordingSettings{public boolean keepAcrossChats,background;public static PurpleRecordingSettings read(){return new PurpleRecordingSettings();}}')
        put('org/telegram/messenger/purple/PurpleDiagLog.java','package org.telegram.messenger.purple;public class PurpleDiagLog{public static void event(String...s){}public static String reason(String s){return s;}}')
        put('org/telegram/messenger/FileLog.java','package org.telegram.messenger;public class FileLog{public static void e(Object x){}}')
        put('org/telegram/messenger/AndroidUtilities.java','package org.telegram.messenger;import java.util.*;public class AndroidUtilities{public static List<Runnable> ui=new ArrayList<>();public static void runOnUIThread(Runnable r){ui.add(r);}public static void flush(){while(!ui.isEmpty())ui.remove(0).run();}}')
        put('org/telegram/messenger/NotificationCenter.java','package org.telegram.messenger;public class NotificationCenter{public static final int recordStarted=3,recordStopped=4,audioDidSent=5,recordResumed=6,audioRecordTooShort=7;}')
        put('org/telegram/messenger/purple/PurpleRoundJournal.java',r'''package org.telegram.messenger.purple;
import java.io.*;public class PurpleRoundJournal{public static int registrations;public static File last;
 public static void register(Object movie,boolean secret,boolean sync){try{registrations++;java.lang.reflect.Field f=movie.getClass().getDeclaredField("file");f.setAccessible(true);File media=(File)f.get(movie);last=new File(media+".pjr");java.nio.file.Files.write(last.toPath(),new byte[]{1,3,7,9,2,4});PurpleRecordingRegistry.register(media,last);}catch(Exception e){throw new AssertionError(e);}}}''')
        put('org/telegram/messenger/purple/FailureStore.java',STORE)
        p=out/'android/os/Handler.java';t=p.read_text().replace('public static List<Runnable> queue=', 'public boolean closed;public static ThreadLocal<Handler> active=new ThreadLocal<>();public static List<Runnable> queue=')
        t=t.replace('queue.add(()->handleMessage(m));return true;', 'if(closed)return false;queue.add(()->{active.set(this);try{handleMessage(m);}finally{active.remove();}});return true;');p.write_text(t)
        p=out/'android/os/Looper.java';p.write_text(p.read_text().replace('public void quit(){}','public void quit(){Handler h=Handler.active.get();if(h!=null)h.closed=true;}'))
        p=out/'Ports.java';t=p.read_text().replace('class ImageLoader{static boolean isSdCardPath(File f){return false;}}','class ImageLoader{static boolean sd;static boolean isSdCardPath(File f){return sd;}}').replace('class ApplicationLoader{static File getFilesDirFixed(){return new File(".");}}','class ApplicationLoader{static File directory;static File getFilesDirFixed(){return directory;}}')
        a=t.index('class MP4Builder{');z=t.index('class Mp4Movie{',a);t=t[:a]+MUXER+t[z:]
        t=t.replace('class Mp4Movie{void setCacheFile(File f){}','class Mp4Movie{File file;void setCacheFile(File f){file=f;}')
        a=t.index('class DispatchQueue{');z=t.index('class AndroidUtilities{',a);t=t[:a]+QUEUE+t[z:]
        t=t.replace('static NotificationCenter getInstance(int a){return new NotificationCenter();}','int account;NotificationCenter(){}NotificationCenter(int a){account=a;}static NotificationCenter getInstance(int a){return new NotificationCenter(a);}')
        t=t.replace('static int previews,stops,tooShort;', 'static int previews,stops,tooShort,lastReason;')
        t=t.replace('if(n==recordStopped)stops++;','if(n==recordStopped){stops++;lastReason=(Integer)x[1];}if(n>=3&&n<=7)org.telegram.messenger.purple.PurpleRecordingRegistry.dispatch(n,account,x);')
        p.write_text(t)
        (out/'failure-provenance.json').write_text(json.dumps({'parent':parent,'pin':PARENT if parent else None,'completeStoreSources':hashes,'registrySourceSha256':hashlib.sha256(registry.encode()).hexdigest(),'completeRegistryDispatchSha256':hashlib.sha256(dispatch.encode()).hexdigest(),'completeRegistryRetentionSha256':hashlib.sha256(retention.encode()).hexdigest(),'extraRegistryMethodsSha256':hashlib.sha256(extra.encode()).hexdigest(),'ports':['registered synthetic MP4 boxes and journal','throwing muxer','repair core result','clock/codec/audio/GL hardware from native_eos','queued worker/UI dispatch','actual background file-write queue and native latch']},indent=2)+'\n')
    eos['run'](root,output,parent=parent,case=case,after_prepare=prepare,classpath=jars[-1])

QUEUE=r'''
class DispatchQueue{
 java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{java.lang.Thread t=new java.lang.Thread(r);t.setDaemon(true);return t;});
 DispatchQueue(String s){}void setPriority(int p){}void cleanupQueue(){}void recycle(){worker.shutdown();}void postRunnable(Runnable r){worker.execute(r);}
}
'''
MUXER=r'''
class MP4Builder{
 static int finishes,tracks,samples,videoSamples,audioSamples;static boolean failFinish,blockFinish;static java.util.concurrent.CountDownLatch entered,allow;
 File file;MP4Builder createMovie(Object...x)throws IOException{file=((Mp4Movie)x[0]).file;new FileOutputStream(file).close();return this;}void setAllowSyncFiles(boolean b){}
 int addTrack(MediaFormat f,boolean a){tracks++;return a?1:0;}
 synchronized long writeSampleData(int track,ByteBuffer b,MediaCodec.BufferInfo info,boolean video)throws IOException{samples++;if(video)videoSamples++;else audioSamples++;write(false);return 0;}
 synchronized void write(boolean finished)throws IOException{try(DataOutputStream o=new DataOutputStream(new FileOutputStream(file))){o.writeInt(8);o.writeBytes("ftyp");o.writeInt(8+samples*8);o.writeBytes("mdat");o.write(new byte[samples*8]);if(finished){o.writeInt(8);o.writeBytes("moov");}o.flush();}}
 void finishMovie(Object...x)throws Exception{finishes++;if(blockFinish){entered.countDown();if(!allow.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("fixture finish release missing");}if(failFinish)throw new IOException("synthetic finishMovie failure after samples");write(true);}
}
'''
STORE=r'''
package org.telegram.messenger.purple;
import java.io.*;import java.nio.file.*;import java.util.*;
public class FailureStore{
 public static List<Runnable> retries=new ArrayList<>();public static void retry(){List<Runnable> next=new ArrayList<>(retries);retries.clear();for(Runnable r:next)r.run();}
 public static boolean repair;public static int repairs,closed;public static byte[] copiedJournal;public static File originalJournal;
 public static final PurpleRecordingStore.Core CORE=new PurpleRecordingStore.Core(){
  public PurpleRecordingCore.OggFinish finaliseOgg(File f){throw new AssertionError();}public PurpleRecordingCore.OggScan scanOgg(File f){throw new AssertionError();}public boolean binItemExpired(long b,long n,int k){return false;}
  public PurpleRecordingStore.RoundResult recoverRound(File m,File j,File out){try{if(j==null||!j.isFile())throw new AssertionError("unfinished writer lost journal before recovery");copiedJournal=Files.readAllBytes(j.toPath());repairs++;if(!repair)return null;Files.copy(m.toPath(),out.toPath(),StandardCopyOption.REPLACE_EXISTING);try(DataOutputStream o=new DataOutputStream(new FileOutputStream(out,true))){o.writeInt(8);o.writeBytes("moov");}return new PurpleRecordingStore.RoundResult(true,500);}catch(IOException e){throw new AssertionError(e);}}
 };
 public static class Env implements PurpleRecordingSessions.Env{
  public long now(){return System.currentTimeMillis();}public void schedule(Runnable r,long d){retries.add(r);}public File newestVoice(long s){return null;}public boolean keepDiscarded(){return false;}
  public void flushJournal(String p){if(p!=null&&!new File(p).isFile())throw new AssertionError("journal disappeared before flush");}
  public void closeJournal(String p){closed++;}
  public void changed(){}public void discarded(String k,boolean kept,boolean failed){}
 }
 public static PurpleRecordingStore restart()throws Exception{PurpleRecordingStore s=new PurpleRecordingStore(new PurpleRecordingManifest(PurpleRecordingRegistry.directory),CORE,401);s.recoverAtStart(401);return s;}
 public static File blockBin()throws Exception{PurpleRecordingEntry e=PurpleRecordingRegistry.store.entries().get(0);File f=new File(new PurpleRecordingManifest(PurpleRecordingRegistry.directory).binDir(),e.id+".mp4");if(!f.mkdir())throw new IOException("fixture cannot block bin destination");return f;}
 public static void queueReplacement(File media,File journal){PurpleRecordingRegistry.register(media,journal);PurpleRecordingRegistry.dispatch(3,0,7,false);}
}
'''
CASES=r'''
 static int checks;static File directory;static String selected;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void settle(){org.telegram.messenger.purple.PurpleRecordingRegistry.flush();org.telegram.messenger.AndroidUtilities.flush();AndroidUtilities.flush();org.telegram.messenger.purple.PurpleRecordingRegistry.flush();}
 static InstantCameraView take(boolean background)throws Exception{return take(background,false);}
 static InstantCameraView take(boolean background,boolean sd)throws Exception{
  ImageLoader.sd=sd;ApplicationLoader.directory=directory;
  AndroidUtilities.ui.clear();org.telegram.messenger.AndroidUtilities.ui.clear();Handler.all.clear();Handler.queue.clear();MediaCodec.all.clear();MediaCodec.pts.clear();MediaCodec.sizes.clear();EGLExt.pts.clear();MP4Builder.finishes=MP4Builder.samples=MP4Builder.videoSamples=MP4Builder.audioSamples=MP4Builder.tracks=0;MP4Builder.failFinish=MP4Builder.blockFinish=false;NotificationCenter.previews=NotificationCenter.stops=NotificationCenter.tooShort=0;SystemClock.now=1000;WRITE_TO_FILE_IN_BACKGROUND=background;
  org.telegram.messenger.purple.FailureStore.retries.clear();org.telegram.messenger.purple.FailureStore.repair=false;org.telegram.messenger.purple.FailureStore.repairs=org.telegram.messenger.purple.FailureStore.closed=0;
  org.telegram.messenger.purple.PurpleRecordingRegistry.setup(new File(directory,"store-"+System.nanoTime()));
  InstantCameraView v=new InstantCameraView();v.purpleRoundLimitMs=60000;v.cameraFile=new File(directory,"1800000000000_"+System.nanoTime()+".mp4");java.nio.file.Files.write(v.cameraFile.toPath(),new byte[0]);v.videoEncoder=v.new VideoRecorder();v.videoEncoder.ready=true;v.videoEncoder.handler=new EncoderHandler(v.videoEncoder);v.cameraThread=v.new CameraGLThread();v.videoEncoder.startRecording(v.cameraFile,new EGLContext());Handler.flush();AndroidUtilities.flush();settle();EGLExt.sample=t->MediaCodec.video.videoFrame(t);
  check(org.telegram.messenger.purple.PurpleRecordingRegistry.store.entries().size()==1,"real registration/start creates durable manifest owner");check(org.telegram.messenger.purple.PurpleRoundJournal.last.isFile(),"registered writer has journal");return v;
 }
 static void frame(InstantCameraView v,long ns){long t=1000000000L+ns;v.videoEncoder.handler.sendMessage(v.videoEncoder.handler.obtainMessage(MSG_VIDEOFRAME_AVAILABLE,(int)(t>>32),(int)t,0));}
 static void media(InstantCameraView v)throws Exception{frame(v,0);frame(v,200000000L);frame(v,240000000L);frame(v,280000000L);Handler.flush();check(!EGLExt.pts.isEmpty(),"nonempty native video admitted");}
 static void stop(InstantCameraView v,VideoRecorder r){SystemClock.now=2000;v.send(3,true,0,0,0,0,0);Handler.flush();settle();check(!r.running&&r.sendWhenDone==2,"native first STOP selects local player");r.recorderRunnable.run();check(Handler.queue.size()==2,"actual producer queues EOF then STOP");Handler.next();}
 static void durable(boolean playable)throws Exception{
  org.telegram.messenger.purple.PurpleRecordingStore disk=org.telegram.messenger.purple.FailureStore.restart();check(disk.bin().size()==1,"failed nonempty take survives real store reconstruction");org.telegram.messenger.purple.PurpleRecordingEntry e=disk.bin().get(0);check(e.account==0&&e.dialogId==100&&e.topicId==8&&e.replyId==4&&e.userId==9,"failure preserves captured target/account");check(new File(e.media).isFile()&&new File(e.media).length()>16,"durable owner names nonempty retained bytes");check(!e.discarded,"failed take retained with keep-discarded off");check(playable?org.telegram.messenger.purple.PurpleRecordingEntry.RECOVERED.equals(e.state):org.telegram.messenger.purple.PurpleRecordingEntry.UNRECOVERABLE.equals(e.state),"finished prefix or raw unfinished state truthful");if(!playable){check(e.journal!=null&&new File(e.journal).isFile(),"unfinished original keeps journal in manifest");check(org.telegram.messenger.purple.FailureStore.repairs>0,"real store exercised round recovery seam");}else check(org.telegram.messenger.purple.PurpleRecordingFiles.mp4Finished(new File(e.media))==1,"retained finished prefix is indexed");check(Delegate.sends==0&&NotificationCenter.previews==0,"failure never sends or advertises native playable preview");
 }
 static void failure(String mode,boolean background)throws Exception{
  InstantCameraView v=take(background);VideoRecorder r=v.videoEncoder;media(v);MediaCodec audio=r.audioEncoder,video=r.videoEncoder;boolean muxer=mode.contains("muxer")||mode.contains("unfinished");MP4Builder.failFinish=muxer;
  stop(v,r);if(mode.startsWith("drain"))video.failDrain=true;
  java.lang.Thread terminal=null;
  if(background){
   MP4Builder.blockFinish=true;MP4Builder.entered=new java.util.concurrent.CountDownLatch(1);MP4Builder.allow=new java.util.concurrent.CountDownLatch(1);
   terminal=new java.lang.Thread(Handler::flush);terminal.start();check(MP4Builder.entered.await(5,TimeUnit.SECONDS),"background finish reached real native completion barrier");terminal.interrupt();java.lang.Thread.sleep(30);check(terminal.isAlive(),"interruption cannot bypass unfinished muxer barrier");check(NotificationCenter.previews==0&&v.videoEncoder==r,"owner and preview wait for background finish");MP4Builder.allow.countDown();terminal.join(5000);check(!terminal.isAlive(),"background terminal drain completed");
  }else Handler.flush();
  check(audio.released&&video.released,"failed finalization retires both codecs");check(v.videoEncoder==r&&NotificationCenter.tooShort==0,"no destructive UI completion before durable retention ACK");
  settle();durable(!muxer);check(v.videoEncoder==null&&NotificationCenter.tooShort==1,"durably retained failure clears exact UI owner once");
 }
 static void success(boolean empty)throws Exception{
  InstantCameraView v=take(false);VideoRecorder r=v.videoEncoder;if(!empty)media(v);stop(v,r);Handler.flush();settle();check(v.videoEncoder==null,"normal/empty native owner retired");check(NotificationCenter.previews==(empty?0:1),"successful muxer alone permits nonempty preview");check(NotificationCenter.tooShort==(empty?1:0),"empty take alone follows destructive too-short route");check(Delegate.sends==0,"normal/empty remains local");if(empty)check(org.telegram.messenger.purple.FailureStore.restart().entries().isEmpty(),"true empty owner ends truthfully");
 }
 static void paused()throws Exception{
  InstantCameraView v=take(false);VideoRecorder r=v.videoEncoder;media(v);SystemClock.now=2000;v.togglePause();Handler.flush();settle();check(r.pauseRecorder&&NotificationCenter.previews==1,"native pause preserves existing local preview");check(org.telegram.messenger.purple.PurpleRecordingRegistry.store.entries().size()==1,"paused original remains registered");SystemClock.now=3000;v.togglePause();Handler.flush();settle();check(v.recording&&!r.pauseRecorder,"actual resume recreates codec generation");media(v);SystemClock.now=4000;v.send(3,true,0,0,0,0,0);Handler.flush();settle();r.recorderRunnable.run();Handler.flush();settle();check(v.videoEncoder==null&&NotificationCenter.previews==2,"successful resumed muxer finalization previews once more");check(org.telegram.messenger.purple.FailureStore.restart().bin().size()==1,"paused/resumed original survives store reconstruction");check(Delegate.sends==0,"paused finalization remains local");
 }
 static void failedSave()throws Exception{
  InstantCameraView v=take(false);VideoRecorder r=v.videoEncoder;media(v);stop(v,r);r.videoEncoder.failDrain=true;Handler.flush();File blocked=org.telegram.messenger.purple.FailureStore.blockBin();settle();check(v.videoEncoder==r&&NotificationCenter.tooShort==0,"failed retention cannot acknowledge cleanup");check(r.videoEncoder==null&&r.audioEncoder==null,"save failure does not retain camera codecs");check(org.telegram.messenger.purple.PurpleRoundJournal.last.isFile(),"failed save keeps original journal");check(!org.telegram.messenger.purple.FailureStore.restart().entries().isEmpty(),"failed save preserves durable original ownership");java.nio.file.Files.delete(blocked.toPath());org.telegram.messenger.purple.PurpleRecordingRegistry.retainFailedLegacyRound(0,7,v.cameraFile.toString(),ok->{if(!ok)throw new AssertionError("retry failed");});settle();durable(true);
 }
 static void stale(boolean accountChanged)throws Exception{
  InstantCameraView v=take(false);VideoRecorder r=v.videoEncoder;media(v);stop(v,r);r.videoEncoder.failDrain=true;
  File newer=new File(directory,"1800000000000_999.mp4"),journal=new File(newer+".pjr");java.nio.file.Files.write(newer.toPath(),new byte[]{1,2,3});java.nio.file.Files.write(journal.toPath(),new byte[]{1,3,7});
  org.telegram.messenger.purple.FailureStore.queueReplacement(newer,journal);Handler.flush();
  VideoRecorder replacement=v.new VideoRecorder();if(accountChanged)v.currentAccount=1;else v.videoEncoder=replacement;
  settle();check(NotificationCenter.tooShort==0,"stale failure callback cannot clear replacement/account UI");check(accountChanged?v.videoEncoder==null:v.videoEncoder==replacement,"only exact old recorder may retire");
  org.telegram.messenger.purple.PurpleRecordingStore disk=new org.telegram.messenger.purple.PurpleRecordingStore(new org.telegram.messenger.purple.PurpleRecordingManifest(org.telegram.messenger.purple.PurpleRecordingRegistry.directory),org.telegram.messenger.purple.FailureStore.CORE,401);check(disk.entries().size()==2,"old retained failure and replacement both remain owned");check(disk.bin().size()==1,"stale completion retains old generation exactly once");check(org.telegram.messenger.purple.PurpleRecordingRegistry.store.entries().stream().anyMatch(e->newer.toString().equals(e.media)&&e.unfinished()),"replacement session writer remains active");
 }

 static int cancelChecks;
 static void failedCancel(boolean indexed,boolean replace)throws Exception{failedCancel(indexed,replace,false);}
 static void failedCancel(boolean indexed,boolean replace,boolean sd)throws Exception{
  int before=checks;InstantCameraView v=take(false,sd);VideoRecorder recorder=v.videoEncoder;media(v);stop(v,recorder);recorder.videoEncoder.failDrain=true;MP4Builder.failFinish=!indexed;Handler.flush();
  File original=recorder.fileToWrite,journal=org.telegram.messenger.purple.PurpleRoundJournal.last;byte[] bytes=java.nio.file.Files.readAllBytes(original.toPath()),journalBytes=java.nio.file.Files.readAllBytes(journal.toPath());
  String id=org.telegram.messenger.purple.PurpleRecordingRegistry.store.entries().get(0).id;File blocked=org.telegram.messenger.purple.FailureStore.blockBin();settle();
  List<Boolean> acknowledgements=new ArrayList<>();org.telegram.messenger.purple.PurpleRecordingRegistry.retainFailedLegacyRound(0,7,original.toString(),acknowledgements::add);settle();
  check(acknowledgements.equals(java.util.Arrays.asList(false)),"real retention completion is false while bin unavailable");check(v.videoEncoder==recorder&&NotificationCenter.tooShort==0,"failed persistence does not acknowledge native owner cleanup");
  v.cancel(false);settle();check(NotificationCenter.lastReason==6&&v.cameraFile==null,"actual native Cancel emits reason6 and drops UI file reference");check(recorder.handler.closed,"real terminal encoder quit rejects Cancel's later message");
  org.telegram.messenger.purple.PurpleRecordingStore disk=org.telegram.messenger.purple.FailureStore.restart();
  check(disk.entries().stream().anyMatch(e->e.id.equals(id)&&e.media!=null&&new File(e.media).isFile()&&new File(e.media).length()>16),"failed save plus actual native Cancel retains named durable owner BEFORE retry");
  check(journal.isFile()&&java.util.Arrays.equals(journalBytes,java.nio.file.Files.readAllBytes(journal.toPath())),"unfinished or indexed original journal survives cancel/reconstruction");check(original.isFile()&&java.util.Arrays.equals(bytes,java.nio.file.Files.readAllBytes(original.toPath())),"native unlink cannot remove reserved named source");
  check(disk.entries().stream().anyMatch(e->e.id.equals(id)&&e.awaitingQueue),"reconstructed manifest keeps failed writer recovery intent");check(disk.bin().isEmpty(),"no successful bin retry occurred before reconstruction");check(!org.telegram.messenger.purple.FailureStore.retries.isEmpty(),"detached claim has independently reachable scheduled retry");
  org.telegram.messenger.purple.FailureStore.retry();settle();check(original.isFile()&&NotificationCenter.tooShort==0,"repeated save failure remains fenced without false ACK");check(!org.telegram.messenger.purple.FailureStore.retries.isEmpty(),"failed retry remains scheduled after Cancel detached session");
  File next=new File(directory,"1800000000000_next_"+System.nanoTime()+".mp4"),nextJournal=new File(next+".pjr");java.nio.file.Files.write(next.toPath(),new byte[]{9,8,7});java.nio.file.Files.write(nextJournal.toPath(),new byte[]{7,8,9});
  if(replace){org.telegram.messenger.purple.FailureStore.queueReplacement(next,nextJournal);settle();v.videoEncoder=v.new VideoRecorder();}
  List<Boolean> wrong=new ArrayList<>();org.telegram.messenger.purple.PurpleRecordingRegistry.retainFailedLegacyRound(1,7,original.toString(),wrong::add);settle();check(wrong.equals(java.util.Arrays.asList(false)),"another account cannot claim detached failed generation");
  if(sd){File fresh=org.telegram.messenger.purple.PurpleRecordingRegistry.legacyRoundWriter(original);check(!fresh.equals(original)&&fresh.isFile(),"next SD writer gets a different named source while failed claim remains reserved");check(original.isFile()&&java.util.Arrays.equals(bytes,java.nio.file.Files.readAllBytes(original.toPath())),"protected SD temporary writer is neither relocated nor reused");java.nio.file.Files.delete(fresh.toPath());}
  java.nio.file.Files.delete(blocked.toPath());org.telegram.messenger.purple.FailureStore.retry();settle();
  org.telegram.messenger.purple.PurpleRecordingStore committed=new org.telegram.messenger.purple.PurpleRecordingStore(new org.telegram.messenger.purple.PurpleRecordingManifest(org.telegram.messenger.purple.PurpleRecordingRegistry.directory),org.telegram.messenger.purple.FailureStore.CORE,401);
  check(committed.bin().size()==1&&committed.bin().get(0).id.equals(id),"independent retry commits captured original exactly once after Cancel/replacement");check(committed.bin().get(0).account==0&&!committed.bin().get(0).discarded,"failure retains captured account even with keep-discarded off");
  check(java.util.Arrays.equals(bytes,java.nio.file.Files.readAllBytes(new File(committed.bin().get(0).media).toPath())),"settled failed original bytes unchanged");
  if(!indexed)check(committed.bin().get(0).journal!=null&&new File(committed.bin().get(0).journal).isFile(),"unfinished failed original retains durable journal");
  if(replace){check(committed.entries().stream().anyMatch(e->next.toString().equals(e.media)&&e.unfinished()),"old completion cannot end replacement writer");check(v.videoEncoder!=recorder&&v.videoEncoder!=null,"old retry callback cannot clear replacement recorder");}
  check(org.telegram.messenger.purple.FailureStore.retries.isEmpty(),"successful claim has no recurring retry owner");check(org.telegram.messenger.purple.PurpleRecordingRegistry.mayDeleteFailedLegacyRound(original)&&original.delete(),"ordinary unlink works after durable settlement");check(Delegate.sends==0&&NotificationCenter.previews==0,"failed Cancel retention cannot send or preview");
  cancelChecks+=checks-before;
 }

 public static void main(String[]args)throws Exception{directory=new File(args[0]);directory.mkdirs();selected=args[1];
  if(selected.equals("cancel-indexed"))failedCancel(true,true);else if(selected.equals("cancel-unfinished"))failedCancel(false,true);else if(selected.equals("drain"))failure("drain",false);else if(selected.equals("unfinished"))failure("drain-unfinished",false);else if(selected.equals("muxer"))failure("muxer",false);else if(selected.equals("background"))failure("muxer",true);else{failure("drain",false);failure("drain-unfinished",false);failure("muxer",false);failure("muxer",true);success(false);success(true);paused();failedSave();stale(false);stale(true);failedCancel(true,false);failedCancel(false,true);failedCancel(false,true,true);}
  System.out.println("LEGACY_NATIVE_FINALIZATION_CHECKS="+checks);if(cancelChecks>0)System.out.println("LEGACY_NATIVE_FAILURE_CANCEL_CHECKS="+cancelChecks);
 }
'''
