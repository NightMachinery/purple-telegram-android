"""Exercise exact recorder ownership seams after release optimization."""
import os, pathlib, re, subprocess, zipfile


def block(text, signature):
    start = text.index(signature)
    pos = text.index('{', start)
    depth, end = 1, pos + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def run(root, output, owners=None, context_source=None):
    output.mkdir(parents=True, exist_ok=False)
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    dock = (java / 'messenger/purple/PurpleRecordingDock.java').read_text()
    chat = (owners or {}).get('PurpleChatHooks.java') or (java / 'ui/PurpleChatHooks.java').read_text()
    strip = (owners or {}).get('PurpleRecordingStrip.java') or (java / 'ui/PurpleRecordingStrip.java').read_text()
    context_source = context_source or (java / 'ui/Components/FragmentContextView.java').read_text()
    initializer = re.search(r'    private final org.telegram.ui.PurpleRecordingStrip purple = [^\n]+', context_source)[0]
    player_field = ' private final java.util.function.BooleanSupplier player;\n' if 'BooleanSupplier player' in strip else ''
    player_arg = '()->true,' if player_field else ''
    home = pathlib.Path(os.environ.get('GRADLE_USER_HOME', pathlib.Path.home() / '.gradle'))
    agp = re.search(r"com.android.tools.build:gradle:([^']+)", (root / 'build.gradle').read_text())[1]
    builders = list((home / 'caches/modules-2/files-2.1/com.android.tools.build/builder' / agp).glob('*/*.jar'))
    assert len(builders) == 1, 'the offline AGP builder supplies its exact R8'
    jdk = pathlib.Path(os.environ['JAVA_HOME'])
    def put(name, text):
        path = output / 'src' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    def field(text, name):
        # Retain the annotation with the field, rather than inventing its keep rule.
        return re.search(r'(?:    @[^\n]+\n)*    private final [^\n]*\b' + name + r'\b[^;]*;', text)[0]
    host_field_start = chat.index('    private final org.telegram.messenger.purple.PurpleRecordingDock.Host recordingHost')
    if chat[:host_field_start].endswith('    @androidx.annotation.Keep\n'):
        host_field_start -= len('    @androidx.annotation.Keep\n')
    host_field = chat[host_field_start:chat.index('\n    };', host_field_start) + 7]
    put('org/telegram/messenger/purple/PurpleMediaTrace.java', 'package org.telegram.messenger.purple;public class PurpleMediaTrace{public enum Step{DISCARD_REQUEST}public static void event(int a,int g,Step s){}public static void detach(int a,int g){}}')
    put('androidx/annotation/Keep.java', 'package androidx.annotation;import java.lang.annotation.*;@Retention(RetentionPolicy.CLASS)@Target({ElementType.FIELD,ElementType.TYPE})public @interface Keep {}')
    put('android/view/View.java', 'package android.view;public class View {public static final int VISIBLE=0,GONE=8;private int visibility;public int getVisibility(){return visibility;}public void setVisibility(int v){visibility=v;}public void bringToFront(){}}')
    put('org/telegram/ui/Components/FragmentContextView.java', 'package org.telegram.ui.Components;public class FragmentContextView {private boolean isLocation;public int relayouts;'+initializer+'public FragmentContextView(){this(false);}public FragmentContextView(boolean location){isLocation=location;}public org.telegram.ui.PurpleRecordingStrip recordingStrip(){return purple;}private void checkPlayer(boolean create){relayouts++;purple.showRecording();}public Object getContext(){return null;}public void addView(Object v,Object p){}}')
    put('org/telegram/ui/Components/LayoutHelper.java', 'package org.telegram.ui.Components;public class LayoutHelper {public static final int MATCH_PARENT=-1;public static Object createFrame(int w,int h,int g){return null;}}')
    put('android/view/Gravity.java', 'package android.view;public class Gravity {public static final int TOP=1;}')
    put('org/telegram/ui/PurpleRecordingStripView.java', 'package org.telegram.ui;public class PurpleRecordingStripView extends android.view.View {public int refreshes;public PurpleRecordingStripView(Object c,Object resources){}public void refresh(){refreshes++;}}')
    put('org/telegram/ui/PurpleRecordingStrip.java', '''package org.telegram.ui;
import android.view.*;import org.telegram.ui.Components.*;
public final class PurpleRecordingStrip {
 private final FragmentContextView strip;
 private PurpleRecordingStripView recording;
 private Object resourcesProvider;
 private static final int BUTTON_DP=48;
 private static final java.util.Set<PurpleRecordingStrip> STRIPS=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
''' + player_field + field(strip, 'changed') + '\n' + '\n'.join(block(strip, signature) for signature in ['public PurpleRecordingStrip(', 'public boolean docked()', 'public boolean showRecording()']) + '\n}')
    put('org/telegram/ui/PurpleChatHooks.java', '''package org.telegram.ui;
import org.telegram.messenger.*;
final class PurpleChatHooks {
 final ChatActivity chat;final Round roundRecording=new Round();
''' + host_field + '''
 PurpleChatHooks(ChatActivity chat){this.chat=chat;
''' + re.search(r'org.telegram.messenger.purple.PurpleRecordingDock.watch\(chat.getClassGuid\(\), recordingHost\);', chat)[0] + '''
 }
 long[] recordingTarget(){return new long[]{0,9,0,0,1,0};}
 static class Round {void started(){}void beginFinalization(){}void pause(){}void preview(){}void ready(Object[] a){}void send(boolean n,int d,long s){}void discard(){}void abandon(){}void retarget(long d,MessageObject t){}}
}''')
    put('org/telegram/ui/ChatActivity.java', 'package org.telegram.ui;public class ChatActivity {public static final int MODE_SCHEDULED=1;public Panel chatActivityEnterView=new Panel();public int getChatMode(){return 0;}public int getClassGuid(){return 7;}public boolean isPaused(){return false;}public static class Panel{public void purpleReleaseRecordUi(){}}}')
    put('org/telegram/messenger/MessageObject.java', 'package org.telegram.messenger;public class MessageObject {}')
    put('org/telegram/messenger/NotificationCenter.java', 'package org.telegram.messenger;public class NotificationCenter {public static final int recordStarted=1;}')
    put('android/os/SystemClock.java', 'package android.os;public class SystemClock {public static long elapsedRealtime(){return 1000;}}')
    for name in ['PurpleRecordingDockState', 'PurpleRecordingTarget', 'PurpleRecordingPreviewHandoff']:
        put('org/telegram/messenger/purple/' + name + '.java', (java / 'messenger/purple' / (name + '.java')).read_text())
    put('org/telegram/messenger/purple/PurpleRecordingSettings.java', 'package org.telegram.messenger.purple;public class PurpleRecordingSettings {public boolean background=true,keepAcrossChats=true;public static PurpleRecordingSettings read(){return new PurpleRecordingSettings();}}')
    put('org/telegram/messenger/purple/PurpleRecordingService.java', 'package org.telegram.messenger.purple;public class PurpleRecordingService {public static int starts,stops;public static void start(){starts++;}public static void stop(){stops++;}public static void refresh(){}}')
    put('org/telegram/messenger/purple/PurpleRecordingSessions.java', 'package org.telegram.messenger.purple;public class PurpleRecordingSessions {public static final int FLAG_QUICK_REPLY=2,FLAG_ONCE=1;}')
    put('org/telegram/messenger/UserConfig.java', 'package org.telegram.messenger;public class UserConfig {public static UserConfig getInstance(int a){return new UserConfig();}public long getClientUserId(){return 1;}}')
    put('org/telegram/messenger/MediaController.java', 'package org.telegram.messenger;public class MediaController {public static MediaController getInstance(){return new MediaController();}public void cleanRecording(boolean d){}public void stopRecording(int a,boolean b,int c,boolean d,long e){}}')
    put('org/telegram/messenger/MediaDataController.java', 'package org.telegram.messenger;public class MediaDataController {public static MediaDataController getInstance(int a){return new MediaDataController();}public void pushDraftVoiceMessage(long d,long t,Object v){}}')
    host = block(dock, 'public interface Host')
    event = block(dock, 'private static void event(')
    event = event[:event.index('        if (!state.active() && event')] + '    }'
    watch = re.search(r'hosts.put\(guid, new WeakReference<>\(host\)\);', dock)[0]
    put('org/telegram/messenger/purple/PurpleRecordingDock.java', '''package org.telegram.messenger.purple;
import java.lang.ref.WeakReference;import java.util.*;import android.os.SystemClock;import org.telegram.messenger.*;
public class PurpleRecordingDock {
''' + host + '''
 public static final PurpleRecordingDockState state=new PurpleRecordingDockState();
 private static final Map<Integer,WeakReference<Host>> hosts=new HashMap<>();
 private static final ArrayList<WeakReference<Runnable>> listeners=new ArrayList<>();
 private static final PurpleRecordingPreviewHandoff nativePreview=new PurpleRecordingPreviewHandoff();
 private static final PurpleRecordingTarget targetSnapshot=new PurpleRecordingTarget();
 private static boolean previewPending,targetPickerPending,roundFinalization,serviceReady;
 private static long foregroundSend=-1,foregroundTarget=-1;
 public static void watch(int guid,Host host){''' + watch + '''}
 public static void start(int guid,boolean voice){event(NotificationCenter.recordStarted,0,guid,voice);}
 public static boolean shown(){return state.active()&&state.docked;}
''' + event + '\n' + '\n'.join(block(dock, s) for s in ['public static void unwatch(', 'public static void listen(', 'private static Host host(', 'public static boolean valid(', 'public static void discard(', 'private static void clear()', 'public static void changed()']) + '\n}')
    bubble = (java / 'ui/Components/PurpleRoundBubble.java').read_text()
    put('org/telegram/ui/Components/InstantCameraViewBase.java', 'package org.telegram.ui.Components;public class InstantCameraViewBase {public boolean isPaused(){return false;}}')
    put('org/telegram/ui/Components/RoundEligibility.java', 'package org.telegram.ui.Components;public class RoundEligibility {public static boolean eligible(){return canShrink(new InstantCameraViewBase());}' + block(bubble, 'private static boolean canShrink(') + '}')
    put('org/telegram/ui/LifetimeCases.java', '''package org.telegram.ui;
import java.lang.ref.*;import org.telegram.messenger.purple.*;import org.telegram.ui.Components.*;
public class LifetimeCases {
 static Object owner,view;static int updates,checks,failures;
 static void check(boolean v,String name){checks++;if(!v){failures++;System.out.println("FAIL "+name);}}
 static void collect(){System.gc();System.runFinalization();}
 public static void main(String[] args){
  owner=new PurpleChatHooks(new ChatActivity());collect();
  PurpleRecordingDock.start(7,true);
  check(PurpleRecordingDock.state.active(),"voice adopted after collection");
  check(PurpleRecordingService.starts==1,"adopted voice requests service");
  PurpleRecordingDock.state.docked=true;
  PurpleRecordingStrip strip=new PurpleRecordingStrip(new FragmentContextView(),''' + player_arg + '''()->updates++);view=strip;
  check(strip.showRecording(),"initial row shown");collect();
  int before=updates;PurpleRecordingDock.state.pause(true,2000);PurpleRecordingDock.changed();
  check(updates==before+1,"pause reaches live strip listener after collection");
  long previous=PurpleRecordingDock.state.generation;
  PurpleRecordingDock.discard(previous);
  check(!PurpleRecordingDock.state.active(),"discard releases slot");
  check(updates==before+2,"discard reaches strip listener");
  check(!strip.showRecording(),"discard hides row");
  PurpleRecordingDock.start(7,false);
  check(PurpleRecordingDock.state.active()&&PurpleRecordingDock.state.round,"next round adopted after collection");
  check(RoundEligibility.eligible(),"adopted live round can shrink after collection");
  PurpleRecordingDock.discard(previous);
  check(PurpleRecordingDock.state.active(),"stale discard cannot clear next take");
  PurpleRecordingDock.state.transitioning=true;
  check(!RoundEligibility.eligible(),"transitioning round still cannot shrink");
  PurpleRecordingDock.state.transitioning=false;PurpleRecordingDock.state.paused=true;
  check(!RoundEligibility.eligible(),"paused round still cannot shrink");
  PurpleRecordingDock.unwatch(7);
  PurpleRecordingDock.state.clear();PurpleRecordingDock.start(7,true);
  check(!PurpleRecordingDock.state.active(),"destroyed chat cannot adopt");
  System.out.println("RECORDING_LIFETIME_CHECKS="+checks);System.out.println(checks+" checks, "+failures+" failures");
  if(failures!=0)throw new AssertionError("recording lifetime");
 }
}''')
    put('org/telegram/ui/StripScopeCases.java', '''package org.telegram.ui;
import org.telegram.messenger.purple.*;import org.telegram.ui.Components.*;
public class StripScopeCases {
 static Object playerRoot,locationRoot;static int checks,failures;
 static void check(boolean v,String name){checks++;if(!v){failures++;System.out.println("FAIL "+name);}}
 public static void main(String[] args){
  PurpleRecordingDock.state.begin(0,7,1,9,0,false,false,0);PurpleRecordingDock.state.docked=true;
  FragmentContextView player=new FragmentContextView(false),location=new FragmentContextView(true);
  playerRoot=player;locationRoot=location;
  PurpleRecordingDock.changed();
  check(player.relayouts==1,"player responds to dock change");
  check(location.relayouts==0,"location does not run player layout");
  check(player.recordingStrip().showRecording(),"player row shown");
  check(!location.recordingStrip().showRecording(),"location cannot duplicate recorder row");
  PurpleRecordingDock.state.clear();PurpleRecordingDock.changed();
  check(player.relayouts==2&&!player.recordingStrip().showRecording(),"discard clears player row");
  check(location.relayouts==0,"discard leaves location layout alone");
  System.out.println("RECORDING_STRIP_SCOPE_CHECKS="+checks);System.out.println(checks+" checks, "+failures+" failures");
  if(failures!=0)throw new AssertionError("strip scope");
 }
}''')
    classes = output / 'classes'
    subprocess.run([str(jdk/'bin/javac'), '--release', '8', '-d', str(classes), *map(str, sorted((output/'src').rglob('*.java')))], check=True)
    jar = output / 'input.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        for path in sorted(classes.rglob('*.class')):
            archive.write(path, path.relative_to(classes))
    rules = output / 'rules.pro'
    keep = (root/'TMessagesProj/proguard-rules.pro').read_text()
    rules.write_text('\n'.join(line for line in keep.splitlines() if 'androidx.annotation.Keep' in line) + '''
-keep class org.telegram.ui.LifetimeCases { *; }
-keep class org.telegram.ui.StripScopeCases { *; }
-keep,allowoptimization class org.telegram.ui.PurpleChatHooks { <init>(org.telegram.ui.ChatActivity); }
-keep,allowoptimization class org.telegram.ui.PurpleRecordingStrip { <init>(...); public boolean showRecording(); }
-dontobfuscate
''')
    optimized = output / 'optimized.jar'
    subprocess.run([str(jdk/'bin/java'), '-cp', str(builders[0]), 'com.android.tools.r8.R8', '--release', '--classfile', '--lib', str(jdk), '--pg-conf', str(rules), '--output', str(optimized), str(jar)], check=True)
    for main in ['org.telegram.ui.LifetimeCases', 'org.telegram.ui.StripScopeCases']:
        subprocess.run([str(jdk/'bin/java'), '-cp', str(optimized), main], check=True)
