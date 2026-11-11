"""Full legacy recorder methods over inert clock, codec, GL, window and file ports."""
import hashlib,json,os,pathlib,runpy,shlex,subprocess
method=runpy.run_path(str(pathlib.Path(__file__).with_name('bubble_routing.py')))['method']
PARENT='a7c1a9c36b0bbf92a775cb0e64cb0463eaecf229'

def run(root, output, parent=False, case='all', prepare=None, classpath=None):
    output.mkdir(parents=True,exist_ok=False)
    java=root/'TMessagesProj/src/main/java/org/telegram'
    path='TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java'
    native=subprocess.check_output(['git','show',PARENT+':'+path],cwd=root,text=True) if parent else (root/path).read_text()
    provenance={path:hashlib.sha256(native.encode()).hexdigest()}
    def put(name,text):
        p=output/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    for name in ['PurpleLegacyRoundBudget','PurpleRoundLimits','PurpleLegacyCodecEos']:
        text=(java/('messenger/purple/'+name+'.java')).read_text()
        provenance[name]=hashlib.sha256(text.encode()).hexdigest()
        put('org/telegram/messenger/purple/'+name+'.java',text)
    put('org/telegram/messenger/MessagesController.java','''package org.telegram.messenger;public class MessagesController{
      public static Pref[] prefs={new Pref(),new Pref()};public static Pref getMainSettings(int a){return prefs[a];}
      public static class Pref{int value=1;public int getInt(String k,int d){return value;}public Pref edit(){return this;}public Pref putInt(String k,int n){value=n;return this;}public void apply(){}}}''')
    put('org/telegram/messenger/purple/PurpleRecordingDock.java','''package org.telegram.messenger.purple;public class PurpleRecordingDock{
      public static boolean roundFinalizing(int a,int g){return false;}public static boolean beginRoundFinish(int a,int g){throw new AssertionError("cap must not reserve send");}}''')
    put('org/telegram/messenger/purple/PurpleRoundJournal.java','package org.telegram.messenger.purple;public class PurpleRoundJournal{public static int registrations;public static void register(Object m,boolean secret,boolean sync){registrations++;}}')
    put('org/telegram/messenger/purple/PurpleRecordingRegistry.java','package org.telegram.messenger.purple;public class PurpleRecordingRegistry{public static boolean mayDeleteFailedLegacyRound(java.io.File f){return true;}public static java.io.File legacyRoundWriter(java.io.File f){return f;}public static void retainFailedLegacyRound(int a,int g,String p,java.util.function.Consumer<Boolean> done){done.accept(true);}}')
    put('android/os/SystemClock.java','package android.os;public class SystemClock{public static long now;public static long elapsedRealtime(){return now;}}')
    put('android/os/Message.java','package android.os;public class Message{public int what,arg1,arg2;public Object obj;}')
    put('android/os/Handler.java','''package android.os;import java.util.*;public class Handler{
      public static List<Handler> all=new ArrayList<>();public List<Runnable> jobs=new ArrayList<>();public Map<Runnable,Long> at=new HashMap<>();
      public Handler(){all.add(this);}public void handleMessage(Message m){}public boolean sendMessage(Message m){handleMessage(m);return true;}
      public Message obtainMessage(int w){return obtainMessage(w,0,0,null);}public Message obtainMessage(int w,Object o){return obtainMessage(w,0,0,o);}
      public Message obtainMessage(int w,int a,int b){return obtainMessage(w,a,b,null);}public Message obtainMessage(int w,int a,int b,Object o){Message m=new Message();m.what=w;m.arg1=a;m.arg2=b;m.obj=o;return m;}
      public boolean postDelayed(Runnable r,long delay){jobs.add(r);at.put(r,SystemClock.now+delay);return true;}public void removeCallbacks(Runnable r){jobs.removeIf(x->x==r);at.remove(r);}
      public Looper getLooper(){return new Looper();}public static void due(){for(Handler h:new ArrayList<>(all))for(Runnable r:new ArrayList<>(h.jobs))if(h.at.get(r)<=SystemClock.now){h.jobs.remove(r);h.at.remove(r);r.run();}}
    }''')
    put('android/os/Looper.java','''package android.os;public class Looper{public static void prepare(){}public static void loop(){}public static Looper myLooper(){return new Looper();}public void quit(){}public java.lang.Thread getThread(){return java.lang.Thread.currentThread();}}''')
    for n in ['EGLContext','EGLDisplay','EGLConfig','EGLSurface']:
        put('android/opengl/'+n+'.java','package android.opengl;public class '+n+'{}')
    gl='''package android.opengl;public class EGL14{
      public static final EGLDisplay EGL_NO_DISPLAY=new EGLDisplay();public static final EGLContext EGL_NO_CONTEXT=new EGLContext();public static final EGLSurface EGL_NO_SURFACE=new EGLSurface();
      public static final int EGL_DEFAULT_DISPLAY=0,EGL_OPENGL_ES2_BIT=1,EGL_RED_SIZE=2,EGL_GREEN_SIZE=3,EGL_BLUE_SIZE=4,EGL_ALPHA_SIZE=5,EGL_RENDERABLE_TYPE=6,EGL_NONE=7,EGL_CONTEXT_CLIENT_VERSION=8;
      public static EGLDisplay eglGetDisplay(Object...x){return new EGLDisplay();}public static EGLContext eglCreateContext(Object...x){return new EGLContext();}public static EGLContext eglGetCurrentContext(){return new EGLContext();}
      public static EGLSurface eglCreateWindowSurface(Object...x){return new EGLSurface();}public static boolean eglInitialize(Object...x){return true;}public static boolean eglChooseConfig(Object...x){((EGLConfig[])x[3])[0]=new EGLConfig();return true;}
      public static boolean eglMakeCurrent(Object...x){return true;}public static boolean eglQueryContext(Object...x){return true;}public static void eglSwapBuffers(Object...x){}public static void eglDestroySurface(Object...x){}public static void eglDestroyContext(Object...x){}public static void eglReleaseThread(){}public static void eglTerminate(Object...x){}public static int eglGetError(){return 0;}}'''
    put('android/opengl/EGL14.java',gl)
    put('android/opengl/EGLExt.java','package android.opengl;import java.util.*;public class EGLExt{public static List<Long> pts=new ArrayList<>();public static void eglPresentationTimeANDROID(EGLDisplay d,EGLSurface s,long t){pts.add(t);}}')
    constants=['GL_BLEND','GL_TEXTURE0','GL_FLOAT','GL_TRIANGLE_STRIP','GL_SRC_ALPHA','GL_ONE_MINUS_SRC_ALPHA','GL_VERTEX_SHADER','GL_FRAGMENT_SHADER','GL_LINK_STATUS']
    funcs=['glUseProgram','glActiveTexture','glVertexAttribPointer','glEnableVertexAttribArray','glUniformMatrix4fv','glUniform2f','glEnable','glUniform1f','glBindTexture','glDrawArrays','glDisableVertexAttribArray','glDisable','glDeleteTextures','glBlendFunc','glAttachShader','glLinkProgram','glDeleteProgram']
    put('GLES20.java','class GLES20{'+''.join('static final int '+n+'='+str(i)+';' for i,n in enumerate(constants))+''.join('static void '+n+'(Object...x){}' for n in funcs)+'''static int glCreateProgram(){return 1;}static int glGetAttribLocation(Object...x){return 1;}static int glGetUniformLocation(Object...x){return 1;}static void glGetProgramiv(Object...x){((int[])x[2])[0]=1;}}''')
    put('Ports.java',r'''
import java.io.*;import java.nio.*;import java.util.*;import android.os.*;
class GLES11Ext{static final int GL_TEXTURE_EXTERNAL_OES=0;}
class Thread{static final int MAX_PRIORITY=10;Thread(Runnable r){}Thread(Runnable r,String n){}void start(){}void setPriority(int p){}}
class BuildVars{static final boolean LOGS_ENABLED=false,DEBUG_VERSION=false;}
class FileLog{static void e(Object x){if(x instanceof Throwable)throw new AssertionError((Throwable)x);}static void e(String s){}static void d(String s){}}
class MediaCodec{static final int BUFFER_FLAG_END_OF_STREAM=4,CONFIGURE_FLAG_ENCODE=1;static List<Long> pts=new ArrayList<>();static List<Integer> sizes=new ArrayList<>();static int releases;static class BufferInfo{}
 static MediaCodec createEncoderByType(String s){return new MediaCodec();}void configure(Object...x){}void start(){}void stop(){}void release(){releases++;}Surface createInputSurface(){return new Surface();}
 int dequeueInputBuffer(int n){return 0;}ByteBuffer getInputBuffer(int n){return ByteBuffer.allocate(2048);}
 void queueInputBuffer(int i,int o,int size,long time,int flags){pts.add(time);sizes.add(size);}}
class Surface{void release(){}}
class MediaFormat{static final String KEY_MIME="mime",KEY_SAMPLE_RATE="sample",KEY_CHANNEL_COUNT="channels",KEY_BIT_RATE="bitrate",KEY_MAX_INPUT_SIZE="max",KEY_COLOR_FORMAT="color",KEY_FRAME_RATE="fps",KEY_I_FRAME_INTERVAL="iframe";void setString(String k,String v){}void setInteger(String k,int v){}static MediaFormat createVideoFormat(Object...x){return new MediaFormat();}}
class MediaCodecInfo{static class CodecCapabilities{static final int COLOR_FormatSurface=1;}}
class AudioRecord{static int getMinBufferSize(Object...x){return 2048;}AudioRecord(Object...x){}void startRecording(){}int getChannelCount(){return 1;}int getSampleRate(){return 48000;}}
class AudioFormat{static final int CHANNEL_IN_MONO=1,ENCODING_PCM_16BIT=2;}
class MediaRecorder{static class AudioSource{static final int DEFAULT=0;}}
class MP4Builder{static int finishes;MP4Builder createMovie(Object...x){return this;}void setAllowSyncFiles(boolean b){}void finishMovie(Object...x){finishes++;}}
class Mp4Movie{void setCacheFile(File f){}void setRotation(int n){}void setSize(int a,int b){}void setSecret(boolean b){} }
class DispatchQueue{DispatchQueue(String s){}void setPriority(int p){}void cleanupQueue(){}void recycle(){}void postRunnable(Runnable r){r.run();}}
class AndroidUtilities{static List<Runnable> ui=new ArrayList<>();static void runOnUIThread(Runnable r){ui.add(r);}static void flush(){while(!ui.isEmpty())ui.remove(0).run();}static void lockOrientation(Object a){}static String readRes(int n){return "";}static void copyFile(File a,File b)throws IOException{java.nio.file.Files.copy(a.toPath(),b.toPath());}}
class NotificationCenter{static final int stopAllHeavyOperations=1,startAllHeavyOperations=2,recordStarted=3,recordStopped=4,audioDidSent=5,recordResumed=6,audioRecordTooShort=7;static int previews,stops;static NotificationCenter getInstance(int a){return new NotificationCenter();}static NotificationCenter getGlobalInstance(){return new NotificationCenter();}void postNotificationName(int n,Object...x){if(n==audioDidSent)previews++;if(n==recordStopped)stops++;}}
class MessagesController{int roundVideoSize=360,roundVideoBitrate=1000,roundAudioBitrate=32;static MessagesController getInstance(int a){return new MessagesController();}}
class SharedConfig{static boolean deviceIsHigh(){return false;}}
class ImageLoader{static boolean isSdCardPath(File f){return false;}}
class ApplicationLoader{static File getFilesDirFixed(){return new File(".");}}
class InstantCameraVideoEncoderOverlayHelper{InstantCameraVideoEncoderOverlayHelper(int a,int b){}void bind(){}void render(){}void destroy(){}}
class GLUtils{static String getEGLErrorString(int n){return "";}}
class Size{int getWidth(){return 360;}int getHeight(){return 360;}}
class Texture{Bitmap getBitmap(int...x){return new Bitmap();}Animation animate(){return new Animation();}}
class Animation{Animation setDuration(int n){return this;}Animation alpha(float a){return this;}Animation setInterpolator(Object i){return this;}void start(){}}
class Bitmap{int getPixel(int a,int b){return 1;}}
class DecelerateInterpolator{}
class R{static class raw{static final int instant_lanczos_vert=1,instant_lanczos_frag_oes=2;}}
class HapticFeedbackConstants{static final int KEYBOARD_TAP=1,FLAG_IGNORE_GLOBAL_SETTING=1;}
class VideoEditedInfo{long startTime=-1,endTime=-1,estimatedDuration,estimatedSize;boolean roundVideo,notReadyYet;Object file,encryptedFile,thumb;byte[] key,iv;int framerate,resultWidth,originalWidth,resultHeight,originalHeight,bitrate;String originalPath;boolean needConvert(){return false;}}
class VideoPlayer{void releasePlayer(boolean b){}}
class MediaController{static class PhotoEntry{int ttl;long effectId;PhotoEntry(Object...x){}}static MediaController getInstance(){return new MediaController();}void requestRecordAudioFocus(boolean b){}}
class FileLoader{static FileLoader getInstance(int a){return new FileLoader();}void cancelFileUpload(String s,boolean b){}}
class AutoDeleteMediaTask{static void unlockFile(File f){}}
class StoryEntry{static File makeCacheFile(int a,boolean b){try{return File.createTempFile("preview",".mp4");}catch(Exception e){throw new RuntimeException(e);}}}
interface Schedule{void accept(boolean n,int d,int r);}
class AlertsCreator{static void createScheduleDatePickerDialog(Object a,long d,Schedule s,Runnable r,Object p){throw new AssertionError("cap must not open send picker");}}
class Delegate{static int sends;Object getParentActivity(){return null;}long getDialogId(){return 1;}boolean isInScheduleMode(){return false;}void sendMedia(Object...x){sends++;throw new AssertionError("auto send forbidden");}}
''')
    fields=native.split('    private class VideoRecorder implements Runnable {',1)[1].split('        private Runnable recorderRunnable',1)[0]
    signatures=['        public void startRecording(', '        public void stopRecording(', '        public void pause()', '        public void resume()', '        private void handleAudioFrameAvailable(', '        private void handleVideoFrameAvailable(', '        private void handlePauseRecording()', '        private void handleResumeRecording()', '        private void handleStopRecording(', '        private void prepareEncoder(']
    methods='\n'.join(method(native,s) for s in signatures)
    outer='\n'.join(method(native,s) for s in ['    public void send(', '    public void cancel(', '    public void togglePause()'])
    composer=(java/'ui/Components/ChatActivityEnterView.java').read_text()
    dock=(java/'messenger/purple/PurpleRecordingDock.java').read_text()
    strip=(java/'ui/PurpleRecordingStripView.java').read_text()
    for name,text in [('composer',composer),('dock',dock),('strip',strip)]:provenance[name]=hashlib.sha256(text.encode()).hexdigest()
    put('org/telegram/messenger/purple/PurpleRecordingDockState.java',(java/'messenger/purple/PurpleRecordingDockState.java').read_text())
    dock_methods='\n'.join(method(dock,n) for n in ['    public static void floatRound(', '    public static void discard(', '    public static boolean valid('])
    put('org/telegram/messenger/purple/PurpleRecordingDock.java','''package org.telegram.messenger.purple;public class PurpleRecordingDock{
      public static PurpleRecordingDockState state=new PurpleRecordingDockState();public interface Host{void release();void roundDiscard();}public static Host owner;
      static Host host(int g){return owner;}static void syncOnce(){}static void changed(){}static void clear(){state.account=-1;state.generation++;}
      static class Snapshot{void dock(){}}static Snapshot targetSnapshot=new Snapshot();
      public static boolean roundFinalizing(int a,int g){return false;}public static boolean beginRoundFinish(int a,int g){throw new AssertionError("cap must not reserve send");}
      static class UserConfig{static UserConfig getInstance(int a){return new UserConfig();}long getClientUserId(){return 9;}}
      static class MediaController{static MediaController getInstance(){return new MediaController();}void cleanRecording(boolean b){}void stopRecording(Object...x){throw new AssertionError("voice branch");}}
      static class MediaDataController{static MediaDataController getInstance(int a){return new MediaDataController();}void pushDraftVoiceMessage(Object...x){}}
      '''+dock_methods+'}')
    release=method(composer,'    public void purpleReleaseRecordUi()')
    timer_stop=method(composer,'        public void stop() {\n            if (isRunning)')
    put('Composer.java','''class Composer{boolean recordingAudioVideo=true;Object audioToSend,videoToSendMessageObject;static final int RECORD_STATE_CANCEL=0;Timer timer=new Timer();
      void updateRecordInterface(int s,boolean a){timer.stop();}class Timer{boolean isRunning=true;long startTime=1,stopTime,lastSendTypingTime;void invalidate(){}'''+timer_stop+'}'+release+'}')
    modal=strip.split('discard = icon(context, () -> {',1)[1].split('        });',1)[0]
    put('DiscardControl.java','''import org.telegram.messenger.purple.PurpleRecordingDock;
      class DiscardControl{long generation;Object resourcesProvider;Object getContext(){return null;}void open(){'''+modal+'''} }
      class LocaleController{static String getString(int id){return "label";}}
      interface Click{void click(Object d,int w);}
      class AlertDialog{static Builder shown;static class Builder{Click yes,no;Builder(Object a,Object b){}Builder setTitle(String s){return this;}Builder setMessage(String s){return this;}Builder setPositiveButton(String s,Click c){yes=c;return this;}Builder setNegativeButton(String s,Click c){no=c;return this;}void show(){shown=this;}void positive(){yes.click(null,1);}void negative(){if(no!=null)no.click(null,0);}void dismiss(){}}}
    ''')
    s_r="static class string{static final int DiscardVoiceMessageTitle=1,DiscardVoiceMessageDescription=2,DiscardVoiceMessageAction=3,Cancel=4;}"
    ports=output/'Ports.java';ports.write_text(ports.read_text().replace('class R{static class raw','class R{'+s_r+'static class raw'))
    wrapper=r'''
import java.io.*;import java.nio.*;import java.util.*;import java.util.concurrent.*;import java.lang.ref.*;import android.os.*;import android.opengl.*;
public class InstantCameraView {
 int currentAccount=0,recordingGuid=7;long purpleRoundLimitMs,recordedTime,recordPlusTime,recordStartTime,size;boolean recording,cancelled,flashing,isSecretChat,allowSendingWhileRecording,useCamera2,bothCameras,cameraReady;
 int audioSampleRate=48000,surfaceIndex;int[] cameraTexture={0,0},oldCameraTexture={0};float cameraTextureAlpha=1;FloatBuffer textureBuffer=FloatBuffer.allocate(8),vertexBuffer=FloatBuffer.allocate(12),oldTextureTextureBuffer;Size oldTexturePreviewSize;Size[] previewSize={new Size(),new Size()};float[] mMVPMatrix=new float[16],moldSTMatrix=new float[16],mSTMatrix=new float[16];
 Object file,encryptedFile,firstFrameThumb,resourcesProvider;byte[] key,iv;File cameraFile,previewFile;Texture textureView=new Texture(),textureOverlayView=new Texture();VideoEditedInfo videoEditedInfo;VideoPlayer videoPlayer;Delegate delegate=new Delegate();boolean cameraTextureAvailable=true;VideoRecorder videoEncoder;CameraGLThread cameraThread;
 static final boolean WRITE_TO_FILE_IN_BACKGROUND=false;static final String VERTEX_SHADER="";
 void updateFlash(){}void invalidate(){}void saveLastCameraBitmap(){}void stopProgressTimer(){}void startAnimation(boolean a,boolean b){}void hideCamera(boolean b){}void showCamera(boolean b){cameraThread=new CameraGLThread();videoEncoder.startRecording(cameraFile,new EGLContext());}void performHapticFeedback(int a,int b){}
 int dp(int n){return n;}int loadShader(int a,String b){return 1;}String createFragmentShader(Size s){return "";}String createFragmentShaderV2(Size s){return "";}
 class CameraGLThread{void shutdown(int send,boolean n,int d,int r,int ttl,long effect){if(ttl!=-2)videoEncoder.stopRecording(send,new SendOptions(n,d,r,ttl,effect,0));}}
 '''
    wrapper+=outer+'\n'+method(native,'    public static class SendOptions')+'\n'+method(native,'    public static class AudioBufferInfo')
    wrapper+='\n'+native[native.index('    private static final int MSG_START_RECORDING'):native.index('    public static class SendOptions')]
    wrapper+='\n private class VideoRecorder implements Runnable{'+fields+'''
        private boolean started,sentMedia;private Runnable recorderRunnable=()->{};
        public static final int ENCODER_SEND_CANCEL=0,ENCODER_SEND_SEND=1,ENCODER_SEND_PLAYER=2;
        public void run(){}void drainEncoder(boolean end){if(!EGLExt.pts.isEmpty())purpleEos.videoSample();}void setBluetoothScoOn(boolean b){}void createKeyframeThumb(){}void setupVideoPlayer(File f){eglDisplay=EGL14.EGL_NO_DISPLAY;eglContext=EGL14.EGL_NO_CONTEXT;eglSurface=EGL14.EGL_NO_SURFACE;}void didWriteData(File f,long a,boolean b){}
    '''+methods+'\n}\n'+CASES+'\n}'
    if 'purpleEos' not in fields:wrapper=wrapper.replace('if(!EGLExt.pts.isEmpty())purpleEos.videoSample();','')
    put('InstantCameraView.java',wrapper)
    (output/'provenance.json').write_text(json.dumps({'parent':parent,'sources':provenance,'completeNativeMethods':signatures,'completeUiMethods':['send','cancel','togglePause'],'ports':['clock','handler','codec','GL','audio hardware','window','muxer','CameraGL shutdown','file ownership']},indent=2)+'\n')
    if prepare is not None:prepare(root, output, native)
    compiler=pathlib.Path(os.environ['JAVA_HOME'])/'bin/javac'
    subprocess.run([*shlex.split(os.environ.get('PURPLE_NATIVE_COMPILE_WRAPPER','')),str(compiler),*(['-cp',str(classpath)] if classpath else []),'-d',str(output/'classes'),*map(str,output.rglob('*.java'))],check=True)
    subprocess.run([str(compiler.with_name('java')),'-cp',str(output/'classes')+(os.pathsep+str(classpath) if classpath else ''),'InstantCameraView',str(output/'files'),case],check=True)

CASES=r'''
 static int checks;static File directory;static String selected;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void clock(long n){SystemClock.now=n;}
 static InstantCameraView take(int minutes)throws Exception{
  AndroidUtilities.ui.clear();Handler.all.clear();MediaCodec.pts.clear();MediaCodec.sizes.clear();EGLExt.pts.clear();NotificationCenter.previews=NotificationCenter.stops=0;MP4Builder.finishes=0;
  org.telegram.messenger.purple.PurpleRoundLimits.setMinutes(0,minutes);InstantCameraView v=new InstantCameraView();v.purpleRoundLimitMs=org.telegram.messenger.purple.PurpleRoundLimits.maximumDurationMs(v.currentAccount);
  v.cameraFile=new File(directory,"original-"+System.nanoTime()+".mp4");java.nio.file.Files.write(v.cameraFile.toPath(),new byte[]{4,1,7,9});
  v.videoEncoder=v.new VideoRecorder();v.videoEncoder.ready=true;v.videoEncoder.handler=new EncoderHandler(v.videoEncoder);v.cameraThread=v.new CameraGLThread();
  v.videoEncoder.startRecording(v.cameraFile,new EGLContext());AndroidUtilities.flush();return v;
 }
 static void video(InstantCameraView v,long ns){v.videoEncoder.handleVideoFrameAvailable(1000000000L+ns,0);}
 static void audio(InstantCameraView v,long us,int bytes){VideoRecorder r=v.videoEncoder;AudioBufferInfo b=new AudioBufferInfo();b.results=1;b.read[0]=bytes;b.buffer[0].limit(bytes);b.offset[0]=1000000L+us;r.audioStartTime=1000000L;r.audioFirst=1000000L;r.handleAudioFrameAvailable(b);}
 static void ended(InstantCameraView v){VideoRecorder r=v.videoEncoder;r.handleStopRecording(r.sendWhenDone,r.sendWhenDoneOptions);AndroidUtilities.flush();}
 static void boundary(int minutes,int mode)throws Exception{
  clock(1000);InstantCameraView v=take(minutes);VideoRecorder r=v.videoEncoder;long stop=minutes*60000L-500;
  Composer composer=new Composer();org.telegram.messenger.purple.PurpleRecordingDock.state=new org.telegram.messenger.purple.PurpleRecordingDockState();
  org.telegram.messenger.purple.PurpleRecordingDock.state.begin(0,7,9,10,0,false,false,SystemClock.now);org.telegram.messenger.purple.PurpleRecordingDock.state.round=true;
  org.telegram.messenger.purple.PurpleRecordingDock.owner=new org.telegram.messenger.purple.PurpleRecordingDock.Host(){public void release(){composer.purpleReleaseRecordUi();}public void roundDiscard(){v.cancel(false);}};
  if(mode>0)org.telegram.messenger.purple.PurpleRecordingDock.floatRound(0,7,true);
  check(mode==0||!composer.timer.isRunning&&!composer.recordingAudioVideo,"real dock release stops composer timer");
  DiscardControl modal=new DiscardControl();modal.generation=org.telegram.messenger.purple.PurpleRecordingDock.state.generation;if(mode>=2){modal.open();check(v.recording,"unconfirmed native discard action does not stop capture");if(mode==3)AlertDialog.shown.negative();if(mode==4)AlertDialog.shown.dismiss();}
  video(v,0);video(v,200000000L);clock(1000+stop-1);r.currentTimestamp=(stop-40)*1000000L;r.lastTimestamp=1000000000L+(stop-40)*1000000L;r.skippedFirst=true;
  video(v,(stop-10)*1000000L);check(!EGLExt.pts.isEmpty()&&EGLExt.pts.get(EGLExt.pts.size()-1)<stop*1000000L,"native video admitted below cap");
  if(!selected.equals("deadline")&&!selected.equals("video")){audio(v,stop*1000-20000,2048);check(MediaCodec.pts.get(MediaCodec.pts.size()-1)+MediaCodec.sizes.get(MediaCodec.sizes.size()-1)*1000000L/96000<=stop*1000,"native PCM admission ends at cap");}
  Handler.due();clock(1000+stop+2000);Handler.due();int count=EGLExt.pts.size();video(v,(stop+5000)*1000000L);if(!selected.equals("deadline"))check(EGLExt.pts.size()==count,"no video admission with UI callback delayed");
  if(!selected.equals("deadline")&&!selected.equals("video")){audio(v,stop*1000+5000000,2048);
  for(int i=0;i<MediaCodec.pts.size();i++)check(MediaCodec.pts.get(i)<=stop*1000,"no late PCM or EOS timestamp beyond cap");}
  check(v.recording,"UI completion deliberately pending");AndroidUtilities.flush();check(!v.recording&&!r.running&&r.sendWhenDone==2,"capture deadline reaches existing local preview stop with zero composer draws");
  ended(v);check(NotificationCenter.previews==1&&NotificationCenter.stops==1,"one local preview completion");check(Delegate.sends==0,"no message send");
  check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(v.cameraFile.toPath()),new byte[]{4,1,7,9}),"full original bytes unchanged");
 }
 public static void main(String[] args)throws Exception{
  directory=new File(args[0]);directory.mkdirs();selected=args[1];
  for(int m:new int[]{1,3,5})for(int mode:new int[]{2,0,1,3,4})boundary(m,mode);
  clock(1000);InstantCameraView v=take(1);VideoRecorder old=v.videoEncoder;clock(21000);v.togglePause();AndroidUtilities.flush();check(!v.recording&&old.pauseRecorder,"actual pause creates local preview");
  List<Runnable> pausedDeadlines=new ArrayList<>(old.handler.jobs);clock(300000);Handler.due();check(!old.running||old.pauseRecorder,"long pause has no active capture deadline");v.togglePause();AndroidUtilities.flush();for(Runnable timer:pausedDeadlines)timer.run();check(v.recording,"actual resume admitted with remaining budget");
  clock(339499);Handler.due();check(v.recording,"pause interval excluded");clock(339500);Handler.due();AndroidUtilities.flush();check(!v.recording,"remaining cumulative active budget expires after resume");ended(v);
  check(Delegate.sends==0,"pause/resume cap never sends");

  clock(1000);v=take(3);VideoRecorder immutable=v.videoEncoder;org.telegram.messenger.purple.PurpleRoundLimits.setMinutes(0,1);org.telegram.messenger.purple.PurpleRoundLimits.setMinutes(1,5);
  clock(61000);Handler.due();AndroidUtilities.flush();check(v.recording,"active native capture retains three-minute budget after preference changes");clock(180500);Handler.due();AndroidUtilities.flush();check(!v.recording&&immutable.sendWhenDone==2,"captured account retains its own budget");ended(v);
  clock(1000);v=take(1);VideoRecorder stale=v.videoEncoder;List<Runnable> pending=new ArrayList<>(stale.handler.jobs);v.recordedTime=1000;v.cancel(false);AndroidUtilities.flush();clock(90000);for(Runnable r:pending)r.run();AndroidUtilities.flush();check(NotificationCenter.previews==0,"cancelled native timer cannot create preview");
  clock(1000);v=take(1);stale=v.videoEncoder;clock(60500);Handler.due();v.recordingGuid=8;v.videoEncoder=v.new VideoRecorder();v.videoEncoder.ready=true;v.videoEncoder.handler=new EncoderHandler(v.videoEncoder);v.cameraThread=v.new CameraGLThread();v.videoEncoder.startRecording(v.cameraFile,new EGLContext());AndroidUtilities.flush();check(v.recording&&v.videoEncoder.running,"queued old cap cannot stop new recorder generation");
  clock(1000);v=take(1);clock(60500);Handler.due();v.cancel(false);AndroidUtilities.flush();check(NotificationCenter.previews==0&&Delegate.sends==0,"positive cancel wins before cap UI completion without sending");
  check(org.telegram.messenger.purple.PurpleRoundJournal.registrations>0,"native startup keeps original writer journal registration");
  System.out.println("LEGACY_NATIVE_CAP_CHECKS="+checks);
 }
'''
