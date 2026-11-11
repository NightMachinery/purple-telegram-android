"""Complete native drain and AudioRecord producer over a queued, consumptive codec port."""
import hashlib,json,pathlib,runpy,subprocess

PARENT='44ed2af1cec16e5eaeeec9d0f2e046f31b8133e9'
base=runpy.run_path(str(pathlib.Path(__file__).with_name('native_cap.py')))
method=base['method']

def run(root,output,parent=False,case='all'):
    # Keep parent production bodies exact; only the new helper is unused there.
    base['run'].__globals__['PARENT']=PARENT
    def prepare(root,out,native):
        p=out/'InstantCameraView.java';t=p.read_text()
        t=t.replace(base['CASES'],CASES)
        t=t.replace('private Runnable recorderRunnable=()->{};',method(native,'        private Runnable recorderRunnable =')+';')
        old='void drainEncoder(boolean end){'+('if(!EGLExt.pts.isEmpty())purpleEos.videoSample();' if 'purpleEos' in native else '')+'}'
        assert old in t
        t=t.replace(old,method(native,'        public void drainEncoder('))
        p.write_text(t)
        p=out/'Ports.java';t=p.read_text()
        start=t.index('class MediaCodec{');end=t.index('class Surface{',start)
        t=t[:start]+CODEC+t[end:]
        start=t.index('class AudioRecord{');end=t.index('class AudioFormat{',start)
        t=t[:start]+AUDIO+t[end:]
        t=t.replace('class MP4Builder{', 'class MP4Builder{static int tracks,samples,videoSamples,audioSamples;int addTrack(MediaFormat f,boolean audio){tracks++;return audio?1:0;}long writeSampleData(int track,ByteBuffer b,MediaCodec.BufferInfo info,boolean video){samples++;if(video)videoSamples++;else audioSamples++;return 0;}')
        t=t.replace('static final int stopAllHeavyOperations=', 'static final int recordProgressChanged=8,stopAllHeavyOperations=')
        t=t.replace('static int previews,stops;', 'static int previews,stops,tooShort;')
        t=t.replace('if(n==audioDidSent)previews++;', 'if(n==audioRecordTooShort)tooShort++;if(n==audioDidSent)previews++;')
        t=t.replace('static List<Runnable> ui=new ArrayList<>();','static ByteBuffer cloneByteBuffer(ByteBuffer b){return b.duplicate();}static List<Runnable> ui=new ArrayList<>();')
        t=t.replace('KEY_MIME="mime",','KEY_PREPEND_HEADER_TO_SYNC_FRAMES="prepend",KEY_MIME="mime",')
        t=t.replace('void setString(String k,String v){}','boolean containsKey(String k){return false;}int getInteger(String k){return 0;}ByteBuffer getByteBuffer(String k){return ByteBuffer.allocate(0);}void setByteBuffer(String k,ByteBuffer v){}void setString(String k,String v){}')
        t=t.replace('if(x instanceof Throwable)throw new AssertionError((Throwable)x);','if(x instanceof Throwable)errors++;').replace('class FileLog{','class FileLog{static int errors;')
        t+='\n@interface RequiresApi{int api();}class Build{static class VERSION_CODES{static final int N=24;}}\n'
        p.write_text(t)
        p=out/'android/os/Handler.java';t=p.read_text().replace('public boolean sendMessage(Message m){handleMessage(m);return true;}', 'public static List<Runnable> queue=new ArrayList<>();public boolean sendMessage(Message m){queue.add(()->handleMessage(m));return true;}public static void next(){if(queue.isEmpty())throw new AssertionError("missing native handler message");queue.remove(0).run();}public static void flush(){int limit=100;while(!queue.isEmpty()){if(--limit==0)throw new AssertionError("native handler progress exhausted");next();}}')
        p.write_text(t)
        p=out/'android/opengl/EGLExt.java';t=p.read_text().replace('public static List<Long> pts=', 'public static java.util.function.LongConsumer sample;public static List<Long> pts=').replace('pts.add(t);','pts.add(t);if(sample!=null)sample.accept(t);');p.write_text(t)
        provenance=json.loads((out/'provenance.json').read_text())
        provenance.update(parentPin=PARENT if parent else None,completeDrainSha256=hashlib.sha256(method(native,'        public void drainEncoder(').encode()).hexdigest(),completeProducerSha256=hashlib.sha256(method(native,'        private Runnable recorderRunnable =').encode()).hexdigest(),schedule='queued actual EncoderHandler, producer EOF before terminal STOP; explicit UI/encoder turns',ports=['monotonic clock','stateful consumptive codec with EOS flags and bounded retry sentinel','AudioRecord hardware','GL','muxer/file ownership','window','thread launch'])
        provenance['generatedJavaSha256']={str(p.relative_to(out)):hashlib.sha256(p.read_bytes()).hexdigest() for p in out.rglob('*.java')}
        (out/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    base['run'](root,output,parent=parent,case=case,prepare=prepare)

CODEC=r'''
class MediaCodec{
 static final int BUFFER_FLAG_END_OF_STREAM=4,BUFFER_FLAG_CODEC_CONFIG=2,BUFFER_FLAG_KEY_FRAME=1,CONFIGURE_FLAG_ENCODE=1,INFO_TRY_AGAIN_LATER=-1,INFO_OUTPUT_FORMAT_CHANGED=-2,INFO_OUTPUT_BUFFERS_CHANGED=-3;
 static List<Long> pts=new ArrayList<>();static List<Integer> sizes=new ArrayList<>();static List<MediaCodec> all=new ArrayList<>();static int releases;static MediaCodec video,audio;
 static class BufferInfo{int size,offset,flags;long presentationTimeUs;}
 static class Event{int size,flags;long time;Event(int s,int f,long t){size=s;flags=f;time=t;}}
 ArrayDeque<Event> outputs=new ArrayDeque<>();Event leased;boolean isAudio,format=true,inputEnd,outputEnd,released;int submitted,consumed,afterEnd,delay,progress=100,inputDelay,nonempty;boolean failDrain;
 static MediaCodec createEncoderByType(String s){MediaCodec c=new MediaCodec();c.isAudio=s.startsWith("audio");all.add(c);if(c.isAudio)audio=c;else video=c;return c;}
 void configure(Object...x){}void start(){}void stop(){}void release(){if(released)throw new AssertionError("double codec release");released=true;releases++;}Surface createInputSurface(){return new Surface();}
 int dequeueInputBuffer(long n){if(inputEnd)throw new AssertionError("input requested after EOS");if(inputDelay-->0)return -1;return 0;}ByteBuffer getInputBuffer(int n){return ByteBuffer.allocate(2048);}
 void queueInputBuffer(int i,int o,int size,long time,int flags){if(inputEnd)throw new AssertionError("duplicate input after EOS");pts.add(time);sizes.add(size);if(size>0){nonempty++;outputs.add(new Event(8,0,time));}if((flags&4)!=0){inputEnd=true;submitted++;outputs.add(new Event(0,4,time));}}
 void videoFrame(long time){if(inputEnd)throw new AssertionError("video frame after input EOS");nonempty++;outputs.add(new Event(8,0,time/1000));}
 void signalEndOfInputStream(){if(inputEnd)throw new AssertionError("video EOS submitted twice");inputEnd=true;submitted++;outputs.add(new Event(0,4,0));}
 int dequeueOutputBuffer(BufferInfo b,long timeout){
  if(failDrain)throw new IllegalStateException("synthetic codec failure");
  if(--progress==0)throw new AssertionError("bounded codec protocol: waiting without terminal input/progress");
  if(outputEnd){if(++afterEnd>1)throw new AssertionError("bounded codec protocol: output EOS already consumed");return -1;}
  if(leased!=null)throw new AssertionError("output not released");
  if(format){format=false;return INFO_OUTPUT_FORMAT_CHANGED;}
  if(delay-->0)return INFO_TRY_AGAIN_LATER;
  if(outputs.isEmpty())return INFO_TRY_AGAIN_LATER;
  leased=outputs.remove();b.size=leased.size;b.offset=0;b.flags=leased.flags;b.presentationTimeUs=leased.time;return 0;
 }
 ByteBuffer getOutputBuffer(int n){if(leased==null)throw new AssertionError("no output ownership");return ByteBuffer.allocate(256);}
 MediaFormat getOutputFormat(){return new MediaFormat();}
 void releaseOutputBuffer(int n,boolean render){if(leased==null)throw new AssertionError("output released twice");if((leased.flags&4)!=0){consumed++;outputEnd=true;}leased=null;}
}
'''
AUDIO=r'''
class AudioTimestamp{static final int TIMEBASE_MONOTONIC=1;long nanoTime;}
class AudioRecord{
 static final int RECORDSTATE_STOPPED=1;boolean stopped,released;int reads,scriptedReads;Runnable beforeRead;
 static int getMinBufferSize(Object...x){return 2048;}AudioRecord(Object...x){}void startRecording(){}int getChannelCount(){return 1;}int getSampleRate(){return 48000;}
 int getRecordingState(){return stopped?RECORDSTATE_STOPPED:3;}void stop(){stopped=true;}void release(){released=true;}
 int read(ByteBuffer b,int size){reads++;if(beforeRead!=null){Runnable r=beforeRead;beforeRead=null;r.run();}if(scriptedReads-->0){for(int i=0;i<size;i++)b.put((byte)0);return size;}return -1;}
 void getTimestamp(AudioTimestamp t,int base){t.nanoTime=1000000000L;}
}
'''
CASES=r'''
 static int checks;static File directory;static String selected;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static InstantCameraView take()throws Exception{
  AndroidUtilities.ui.clear();Handler.all.clear();Handler.queue.clear();MediaCodec.all.clear();MediaCodec.pts.clear();MediaCodec.sizes.clear();EGLExt.pts.clear();MP4Builder.finishes=MP4Builder.samples=MP4Builder.videoSamples=MP4Builder.audioSamples=MP4Builder.tracks=0;NotificationCenter.previews=NotificationCenter.stops=NotificationCenter.tooShort=0;SystemClock.now=1000;
  InstantCameraView v=new InstantCameraView();v.purpleRoundLimitMs=60000;v.cameraFile=new File(directory,"original-"+System.nanoTime()+".mp4");java.nio.file.Files.write(v.cameraFile.toPath(),new byte[]{4,1,7,9});v.videoEncoder=v.new VideoRecorder();v.videoEncoder.ready=true;v.videoEncoder.handler=new EncoderHandler(v.videoEncoder);v.cameraThread=v.new CameraGLThread();v.videoEncoder.startRecording(v.cameraFile,new EGLContext());Handler.flush();AndroidUtilities.flush();EGLExt.sample=t->MediaCodec.video.videoFrame(t);return v;
 }
 static void frame(InstantCameraView v,long ns){long t=1000000000L+ns;v.videoEncoder.handler.sendMessage(v.videoEncoder.handler.obtainMessage(MSG_VIDEOFRAME_AVAILABLE,(int)(t>>32),(int)t,0));}
 static void media(InstantCameraView v){frame(v,0);frame(v,200000000L);frame(v,240000000L);Handler.flush();check(!EGLExt.pts.isEmpty(),"native video admission before EOS");}
 static void pcm(InstantCameraView v,long us,int bytes){VideoRecorder r=v.videoEncoder;AudioBufferInfo b=new AudioBufferInfo();b.results=1;b.read[0]=bytes;b.buffer[0].limit(bytes);b.offset[0]=1000000L+us;r.audioStartTime=1000000L;r.audioFirst=1000000L;r.handler.sendMessage(r.handler.obtainMessage(MSG_AUDIOFRAME_AVAILABLE,b));}
 static void producerStop(InstantCameraView v,VideoRecorder r){
  Handler.flush();check(!r.running,"first native STOP closes producer running flag");check(r.audioRecorder!=null,"actual producer owns AudioRecord");
  r.recorderRunnable.run();check(r.audioRecorder.released,"actual AudioRecord producer released");check(Handler.queue.size()==2,"actual producer queued EOF then terminal STOP");Handler.next();check(MP4Builder.finishes==0,"producer EOF precedes finalization");Handler.flush();AndroidUtilities.flush();
 }
 static void result(InstantCameraView v,MediaCodec a,MediaCodec video,boolean playable)throws Exception{
  check(a.submitted==1&&a.consumed==1,"audio EOS exactly once");check(video.submitted==1&&video.consumed==1,"video EOS exactly once");check(a.released&&video.released,"both codec resources released");check(a.leased==null&&video.leased==null,"all output ownership released");check(MP4Builder.finishes==1,"one muxer finish");check(NotificationCenter.previews==(playable?1:0),"truthful local preview count");check(NotificationCenter.tooShort==(playable?0:1),"empty take reports existing too-short event");check(Delegate.sends==0,"terminal completion never sends");check(v.videoEncoder==null,"native terminal recorder retired");check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(v.cameraFile.toPath()),new byte[]{4,1,7,9}),"original retained bytes unchanged");
 }
 static void consumed(boolean delayed)throws Exception{
  InstantCameraView v=take();VideoRecorder r=v.videoEncoder;media(v);MediaCodec a=r.audioEncoder,video=r.videoEncoder;
  if(delayed)a.delay=4;
  pcm(v,59490000,2048);Handler.flush();check(a.submitted==1&&r.running,"cap PCM queues active EOS before UI stop");check(v.recording,"cap UI callback pending");
  frame(v,300000000L);Handler.flush();if(!delayed)check(a.consumed==1,"queued video consumed active audio EOS via real drain(false)");
  AndroidUtilities.flush();producerStop(v,r);result(v,a,video,true);check(MP4Builder.audioSamples>0&&MP4Builder.videoSamples>0,"delayed valid audio/video samples written");
 }
 static void empty(boolean videoPresent)throws Exception{
  InstantCameraView v=take();VideoRecorder r=v.videoEncoder;if(videoPresent)media(v);MediaCodec a=r.audioEncoder,video=r.videoEncoder;
  if(!videoPresent){AudioBufferInfo b=new AudioBufferInfo();b.results=1;b.read[0]=2048;b.buffer[0].limit(2048);r.handler.sendMessage(r.handler.obtainMessage(MSG_AUDIOFRAME_AVAILABLE,b));Handler.flush();check(a.submitted==0,"no-first-video buffered PCM has no EOS yet");}
  SystemClock.now=60500;Handler.due();AndroidUtilities.flush();a.inputDelay=2;a.delay=2;producerStop(v,r);result(v,a,video,videoPresent);check(a.nonempty==0,"terminal zeroPCM does not invent audio");
 }
 static void ordinary()throws Exception{
  InstantCameraView v=take();VideoRecorder r=v.videoEncoder;media(v);pcm(v,0,2048);Handler.flush();MediaCodec a=r.audioEncoder,video=r.videoEncoder;
  for(int i=0;i<3;i++){frame(v,280000000L+i*40000000L);Handler.flush();}
  check(a.submitted==0&&a.consumed==0,"repeated nonterminal drains do not end audio");SystemClock.now=2000;v.recordedTime=1000;v.send(3,true,0,0,0,0,0);producerStop(v,r);result(v,a,video,true);
 }
 static void pauseResume()throws Exception{
  InstantCameraView v=take();VideoRecorder r=v.videoEncoder;media(v);pcm(v,59490000,2048);Handler.flush();frame(v,300000000L);Handler.flush();check(r.audioEncoder.consumed==1,"pause race starts after consumed EOS");r.pause();Handler.flush();check(r.pauseRecorder,"native pause wins pending deadline UI");AndroidUtilities.flush();check(Delegate.sends==0,"pause race never sends");
  v=take();r=v.videoEncoder;media(v);MediaCodec old=r.audioEncoder;
  r.drainEncoder(true);check(old.submitted==1&&old.consumed==1,"prior codec generation settled");
  r.eglDisplay=EGL14.EGL_NO_DISPLAY;r.eglContext=EGL14.EGL_NO_CONTEXT;r.eglSurface=EGL14.EGL_NO_SURFACE;r.audioStopedByTime=true;r.prepareEncoder(true);AndroidUtilities.flush();check(r.audioEncoder!=old,"native prepare recreated codec generation");
  MediaCodec a=r.audioEncoder,video=r.videoEncoder;pcm(v,0,1024);Handler.flush();check(a.nonempty==1,"new codec generation admits PCM after EOS reset");SystemClock.now=2000;v.recordedTime=1000;v.send(3,true,0,0,0,0,0);producerStop(v,r);result(v,a,video,true);
 }
 static void failed()throws Exception{
  InstantCameraView v=take();VideoRecorder r=v.videoEncoder;media(v);MediaCodec a=r.audioEncoder,video=r.videoEncoder;SystemClock.now=2000;v.send(3,true,0,0,0,0,0);Handler.flush();video.failDrain=true;r.recorderRunnable.run();Handler.flush();AndroidUtilities.flush();check(a.released&&video.released,"failed terminal drain still releases both codecs");check(NotificationCenter.previews==0&&NotificationCenter.tooShort==1,"failed drain cannot publish playable preview");check(Delegate.sends==0&&v.videoEncoder==null,"failed drain retires without sending");check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(v.cameraFile.toPath()),new byte[]{4,1,7,9}),"failed drain leaves original bytes unchanged");
 }
 static void cancel()throws Exception{
  InstantCameraView v=take();VideoRecorder r=v.videoEncoder;media(v);pcm(v,59490000,2048);Handler.flush();frame(v,300000000L);Handler.flush();v.cancel(false);Handler.flush();r.recorderRunnable.run();Handler.flush();AndroidUtilities.flush();check(Delegate.sends==0&&NotificationCenter.previews==0,"cancel fences pending cap UI and preview");check(r.audioEncoder==null&&r.videoEncoder==null,"cancel retires codecs");
 }
 public static void main(String[]args)throws Exception{directory=new File(args[0]);directory.mkdirs();selected=args[1];
  if(selected.equals("consumed")){consumed(false);}else if(selected.equals("no-first")){empty(false);}else if(selected.equals("no-pcm")){empty(true);}else{consumed(false);consumed(true);empty(false);empty(true);ordinary();pauseResume();failed();cancel();}
  System.out.println("LEGACY_NATIVE_EOS_CHECKS="+checks);
 }
'''
