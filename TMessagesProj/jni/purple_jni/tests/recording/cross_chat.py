"""Run the production capture owner over inert camera, Android and file ports."""
import os, pathlib, shutil, subprocess


def run(root, output, configure=None, case="CrossChatCases"):
    output.mkdir(parents=True, exist_ok=False)
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    def put(name, text):
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    for name in ['ui/PurpleRoundCapture.java', 'messenger/purple/PurpleRoundCaptureLease.java']:
        put('org/telegram/' + name, (java / name).read_text())
    ports = {
        'android/content/Context': 'public class Context {}',
        'android/app/Activity': 'public class Activity extends android.content.Context {}',
        'android/graphics/SurfaceTexture': 'public class SurfaceTexture {}',
        'android/graphics/Rect': 'public class Rect {public int bottom=1800;}',
        'android/view/MotionEvent': 'public class MotionEvent {}',
        'android/view/Gravity': 'public class Gravity {public static final int CENTER=1,RIGHT=2,LEFT=4,BOTTOM=8;}',
        'android/view/View': '''public class View {
          public static final int INVISIBLE=4; public Object parent;public int visibility;public float alpha=1;
          public boolean dispatchTouchEvent(android.view.MotionEvent e){return true;}public Object getParent(){return parent;}public void setVisibility(int v){visibility=v;}
          public void setAlpha(float v){alpha=v;}public void setClipToPadding(boolean b){}
          public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){}
          public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
          public interface LayoutListener{void run(View v,int a,int b,int c,int d,int e,int f,int g,int h);}
          public void addOnLayoutChangeListener(LayoutListener l){}
          public void getWindowVisibleDisplayFrame(android.graphics.Rect r){}public void getLocationOnScreen(int[] p){}
          public int getHeight(){return 1800;}public interface ClickListener{void run(View v);}
          public void setOnClickListener(ClickListener l){}
        }''',
        'android/view/ViewGroup': '''public class ViewGroup extends View {
          public final java.util.List<View> children=new java.util.ArrayList<>();public int adds,removes;
          public void addView(View v,Object p){v.parent=this;children.add(v);adds++;}
          public void removeView(View v){v.parent=null;children.remove(v);removes++;}
        }''',
        'android/view/TextureView': 'public class TextureView extends View {public final android.graphics.SurfaceTexture surface=new android.graphics.SurfaceTexture();public android.graphics.SurfaceTexture getSurfaceTexture(){return surface;}}',
        'android/widget/FrameLayout': 'public class FrameLayout extends android.view.ViewGroup {public FrameLayout(android.content.Context c){}public void setClipChildren(boolean b){}}',
        'android/widget/TextView': 'public class TextView extends android.view.View {public TextView(android.content.Context c){}public void setText(String s){}public void setTextColor(int c){}public void setTextSize(int n){}public void setPadding(int a,int b,int c,int d){}public void setGravity(int g){}public void setBackgroundColor(int c){}}',
        'android/widget/Toast': 'public class Toast {public static final int LENGTH_LONG=1;public static Toast makeText(android.content.Context c,String s,int t){return new Toast();}public void show(){}}',
        'org/telegram/messenger/AndroidUtilities': '''public class AndroidUtilities {
          public static final java.util.ArrayList<Runnable> tasks=new java.util.ArrayList<>();
          public static int dp(int n){return n;}public static void runOnUIThread(Runnable r){tasks.add(r);}
          public static void runOnUIThread(Runnable r,long ms){tasks.add(r);}
          public static void drain(){int limit=500;while(!tasks.isEmpty()&&limit-->0)tasks.remove(0).run();if(limit<=0)throw new AssertionError("loop");}
        }''',
        'org/telegram/messenger/ApplicationLoader': 'public class ApplicationLoader {public static boolean mainInterfacePaused;public static final android.content.Context applicationContext=new android.content.Context();}',
        'org/telegram/messenger/UserConfig': 'public class UserConfig {public long user=100;public static final UserConfig[] all={new UserConfig(),new UserConfig()};public static UserConfig getInstance(int n){return all[n];}public long getClientUserId(){return user;}}',
        'org/telegram/messenger/LocaleController': 'public class LocaleController {public static String getString(int id){return "text";}}',
        'org/telegram/messenger/purple/PurpleDiagLog': 'public class PurpleDiagLog {public static Object count(String k,int n){return null;}public static Object flag(String k,boolean b){return null;}public static void event(String a,String e,Object...f){}}',
        'org/telegram/messenger/FileLog': 'public class FileLog {public static void d(String s){}}',
        'org/telegram/messenger/R': 'public class R {public static class string {public static final int PurpleRecordingSendUnavailable=1,PurpleRoundCaptureSave=2,Cancel=3,DiscardVideoMessageTitle=4,DiscardVideoMessageDescription=5,DiscardVoiceMessageAction=6,Continue=7,PurpleRoundCaptureSaved=8;}}',
        'org/telegram/messenger/MediaController': 'public class MediaController {public static final MediaController instance=new MediaController();public static MediaController getInstance(){return instance;}public void requestRecordAudioFocus(boolean b){}public static class PhotoEntry {public String path="inert.mp4";}}',
        'org/telegram/messenger/MessageObject': 'public class MessageObject {public int getId(){return 8;}}',
        'org/telegram/messenger/VideoEditedInfo': 'public class VideoEditedInfo {public boolean notReadyYet;}',
        'org/telegram/messenger/camera/CameraView': 'public class CameraView {public static boolean isCameraAllowed(){return true;}}',
        'org/telegram/tgnet/ConnectionsManager': 'public class ConnectionsManager {static int next=1000;public static int generateClassGuid(){return ++next;}}',
        'org/telegram/ui/LaunchActivity': 'public class LaunchActivity extends android.app.Activity {public final android.widget.FrameLayout root=new android.widget.FrameLayout(this);public android.widget.FrameLayout getMainContainerFrameLayout(){return root;}}',
        'org/telegram/ui/ChatActivity': '''public class ChatActivity {
          public static final int MODE_DEFAULT=0;public int account,mode,guid;public long dialog,topic;public boolean isTopic,secret,thread;
          public final LaunchActivity activity;public final org.telegram.ui.Components.ChatActivityEnterView chatActivityEnterView=new org.telegram.ui.Components.ChatActivityEnterView();
          public ChatActivity(LaunchActivity a,int g,long d){activity=a;guid=g;dialog=d;}
          public int getCurrentAccount(){return account;}public long getDialogId(){return dialog;}public long getTopicId(){return topic;}
          public int getChatMode(){return mode;}public int getClassGuid(){return guid;}public boolean isSecretChat(){return secret;}
          public boolean isThreadChat(){return thread;}public boolean isPaused(){return false;}
          public org.telegram.messenger.MessageObject getReplyMessage(){return new org.telegram.messenger.MessageObject();}
          public android.app.Activity getParentActivity(){return activity;}public android.content.Context getContext(){return activity;}
        }''',
        'org/telegram/ui/Components/ChatActivityEnterView': '''public class ChatActivityEnterView {
          public boolean voiceOnce;public int guid,releases,restores;public void purpleRoundRecordingGuid(int n){guid=n;}
          public void purpleReleaseRecordUi(){releases++;}public void purpleRestoreRoundUi(Object[] p){restores++;}
          public void setRoundVideoUiFrameClockActive(boolean b){}public void onRoundVideoUiFrame(long n){}
          public void setVideoTimelineTrim(float a,float b){}
        }''',
        'org/telegram/ui/Components/InstantCameraView': '''public class InstantCameraView extends InstantCameraViewBase {
          public interface Delegate {
            android.view.View getFragmentView();android.app.Activity getParentActivity();int getClassGuid();int getCurrentAccount();long getDialogId();
            void sendMedia(org.telegram.messenger.MediaController.PhotoEntry e,org.telegram.messenger.VideoEditedInfo i,boolean n,int d,int r,boolean b,long s);
          }
        }''',
        'org/telegram/ui/Components/InstantCameraView2': 'public class InstantCameraView2 extends InstantCameraViewBase {}',
        'org/telegram/ui/Components/InstantCameraViewBase': '''public class InstantCameraViewBase extends android.view.View {
          public static boolean v2;public static int creations;public static InstantCameraViewBase last;
          public InstantCameraView.Delegate delegate;public boolean paused,small;public int stops,pauses,starts;public Animation animation;
          public final android.view.TextureView texture=new android.view.TextureView();
          public static InstantCameraViewBase create(android.content.Context c,InstantCameraView.Delegate d,Object r,boolean b){
            creations++;last=v2?new InstantCameraView2():new InstantCameraView();last.delegate=d;return last;}
          public interface Animation{void run(boolean a,boolean b);}public interface Trim{void run(float a,float b);}
          public interface RecordingUiFrameCallback{void onActiveChanged(boolean b);void onFrame(long n);}
          public void setAnimationCallback(Animation a){animation=a;}public void setTrimCallback(Trim t){}
          public void setRecordingUiFrameCallback(RecordingUiFrameCallback c){}public void setButtonsBackground(Object a,Object b){}
          public void showCamera(boolean b){starts++;org.telegram.messenger.purple.PurpleRecordingDock.host.roundStarted();}
          public void setInternalPadding(int n){}public void togglePause(){paused=!paused;pauses++;}
          public boolean isPaused(){return paused;}public void changeVideoPreviewState(int s,float p){}
          public void send(int s,boolean n,int d,int r,int ttl,long e,long stars){stops++;org.telegram.messenger.purple.PurpleRecordingDock.host.roundFinishing();
            delegate.sendMedia(new org.telegram.messenger.MediaController.PhotoEntry(),new org.telegram.messenger.VideoEditedInfo(),n,d,r,false,stars);}
          public void cancel(boolean b){stops++;org.telegram.messenger.purple.PurpleRecordingDock.host.roundAbandon();}
          public void hideCamera(boolean b){stops++;}public android.view.TextureView getTextureView(){return texture;}
        }''',
        'org/telegram/ui/Components/PurpleRoundBubble': '''public class PurpleRoundBubble {
          public interface Visibility{void small(boolean b);}static Visibility changed;
          public static void attach(InstantCameraViewBase v,Visibility cb){changed=cb;}
          public static boolean animation(InstantCameraViewBase v,boolean a,boolean b){return !v.small;}
          public static void shrink(InstantCameraViewBase v){v.small=true;changed.small(true);}
          public static void expand(InstantCameraViewBase v){v.small=false;changed.small(false);}
          public static boolean small(InstantCameraViewBase v){return v.small;}
        }''',
        'org/telegram/ui/Components/LayoutHelper': 'public class LayoutHelper {public static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;public static Object createFrame(int a,int b,Object...rest){return null;}}',
        'org/telegram/ui/ActionBar/Theme': 'public class Theme {public interface ResourcesProvider {int getColor(int key);}public static int getColor(int key){return key;}}',
        'org/telegram/ui/ActionBar/AlertDialog': '''public class AlertDialog {public static class Builder {
          public Builder(android.app.Activity a){}public Builder setTitle(String s){return this;}public Builder setMessage(String s){return this;}
          public interface Click{void run(Object d,int w);}public Builder setPositiveButton(String s,Click c){return this;}public Builder setNegativeButton(String s,Click c){return this;}public void show(){}
        }}''',
        'org/telegram/ui/Components/blur3/BlurredBackgroundDrawableViewFactory': 'public class BlurredBackgroundDrawableViewFactory {public BlurredBackgroundDrawableViewFactory(Object o){}}',
        'org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceColor': 'public class BlurredBackgroundSourceColor {public void setColor(int c){}}',
        'org/telegram/ui/Components/blur3/drawable/color/impl/BlurredBackgroundProviderImpl': 'public class BlurredBackgroundProviderImpl {public static Object photoViewer(Object o){return null;}}',
        'org/telegram/messenger/purple/PurpleRecordingSettings': 'public class PurpleRecordingSettings {public boolean keepAcrossChats=true;public static PurpleRecordingSettings read(){return new PurpleRecordingSettings();}}',
        'org/telegram/messenger/purple/PurpleRecordingSessions': 'public class PurpleRecordingSessions {public static final int FLAG_ONCE=1;}',
        'org/telegram/messenger/purple/PurpleRecordingRegistry': '''public class PurpleRecordingRegistry {
          public interface ChatTarget{long[] target();}public static ChatTarget target;public static int retains;public static long[] retained;
          public static void watchSession(int g,ChatTarget t){target=t;}public static void unwatchSession(int g){target=null;}
          public static void retainRound(int a,int g,String p,long[] t,java.util.function.Consumer<String> c){retains++;retained=t.clone();org.telegram.messenger.AndroidUtilities.runOnUIThread(()->c.accept("local-only"));}
          public static void retainFailedRound(int a,int g,String p,long[] t,java.util.function.Consumer<String> c){retainRound(a,g,p,t,c);}
        }''',
    }
    dock = (java / 'messenger/purple/PurpleRecordingDock.java').read_text()
    host = dock[dock.index('    public interface Host {'):dock.index('    public static final PurpleRecordingDockState')]
    ports['org/telegram/messenger/purple/PurpleRecordingDock'] = '''public class PurpleRecordingDock {
    ''' + host + '''public static Host host;public static int endings;
      public static void watch(int g,Host h){host=h;}public static void unwatch(int g){host=null;}
      public static boolean refuseStart(){return org.telegram.ui.PurpleRoundCapture.refuseStart();}
      public static void floatRound(int a,int g,boolean small){if(small)host.release();}
      public static void roundEnded(int a,int g){endings++;}
    }'''
    if configure is not None:
        configure(root, ports)
    for name, body in ports.items():
        package, unused, klass = name.rpartition('/')
        put(name + '.java', 'package ' + package.replace('/', '.') + ';import org.telegram.messenger.MessageObject;' + body)
    shutil.copyfile(pathlib.Path(__file__).with_name(case + '.java'), output / ('org/telegram/ui/' + case + '.java'))
    compiler = pathlib.Path(os.environ['JAVA_HOME']) / 'bin/javac'
    subprocess.run([str(compiler), '-d', str(output / 'classes'), *map(str, sorted(output.rglob('*.java')))], check=True)
    subprocess.run([str(compiler.with_name('java')), '-cp', str(output / 'classes'), 'org.telegram.ui.' + case], check=True)
