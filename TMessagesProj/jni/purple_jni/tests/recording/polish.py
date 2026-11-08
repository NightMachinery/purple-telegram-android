"""Run production recording-button and bin-player methods over inert host ports."""
import os, pathlib, subprocess
import importlib.util
_spec = importlib.util.spec_from_file_location("bubble_routing", pathlib.Path(__file__).with_name("bubble_routing.py"))
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
method = _module.method


def run(root, output):
    output.mkdir()
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    def put(name, content):
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    row = (java / 'ui/PurpleRecordingBinRow.java').read_text()
    methods = '\n'.join(method(row, signature) for signature in [
        '    public void toggle()', '    private long duration()', '    private static String time(',
        '    private void label(', '    private void progress()', '    public void pause()', '    public void stop()'])
    drag = method(row, '            @Override public void onSeekBarDrag(').replace('@Override ', '')
    put('BinPort.java', '''import android.net.Uri;import java.io.File;import org.telegram.ui.Components.VideoPlayer;
import org.telegram.messenger.*;
public class BinPort {
 static final int VISIBLE=0,GONE=8;
 static class Entry {long durationMs=10000;String media="inert.ogg";boolean round;boolean round(){return round;}}
 static class Node {String value;float progress;int visibility;void setText(String s){value=s;}void setProgress(float f){progress=f;}void setVisibility(int v){visibility=v;}void setContentDescription(String s){}void setPause(boolean p,boolean a){}void setPlaying(boolean p){}}
 final Entry entry=new Entry();final Node toggle=new Node(),icon=new Node(),seek=new Node(),elapsed=new Node(),total=new Node(),preview=new Node();
 final Object texture=new Object();int activations,errors,posts;final Runnable activate=()->activations++,failure=()->errors++;
 VideoPlayer player;boolean dragging;final Runnable tick=this::progress;
 void removeCallbacks(Runnable r){posts=0;}void postDelayed(Runnable r,int ms){posts++;}
''' + methods + drag + '''
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  for(boolean round:new boolean[]{false,true}){
   BinPort p=new BinPort();p.entry.round=round;p.toggle();VideoPlayer active=p.player;
   check(active.play&&p.activations==1,"first toggle plays");check(active.texture==(round?p.texture:null),"round uses inline texture");
   active.position=5000;active.delegate.onStateChanged(true,3);check(p.seek.progress==.5f&&p.elapsed.value.equals("5"),"elapsed and seek track playback");
   p.toggle();check(!active.play&&p.activations==1,"second toggle pauses");
   p.onSeekBarDrag(true,.2f);check(active.position==2000&&p.elapsed.value.equals("2"),"seek while paused rewinds");
   p.toggle();check(active.play&&p.activations==2,"resume activates exclusivity");
   p.pause();check(!active.play,"another row can pause this one");
   active.position=10000;p.toggle();check(active.position==0&&active.play,"completed message restarts");
   active.delegate.onStateChanged(true,androidx.media3.common.Player.STATE_ENDED);check(!active.play,"completion stops");
   p.dragging=true;float old=p.seek.progress;active.position=7000;p.progress();check(p.seek.progress==old,"ticker does not fight drag");
   p.stop();check(p.player==null&&active.released&&p.posts==0&&p.preview.visibility==GONE,"stop releases player and ticker");
   check(p.seek.progress==0&&p.elapsed.value.equals("0"),"stop resets controls");
   active.delegate.onError(active,new Exception());check(p.errors==0,"late player callback ignored");
   p.toggle();p.player.delegate.onError(p.player,new Exception());check(p.errors==1&&p.player==null,"current failure releases player");
  }
  System.out.println("BIN_PLAYER_CHECKS="+checks);
 }
}''')
    put('android/net/Uri.java', 'package android.net;public class Uri {public static Uri fromFile(java.io.File f){return new Uri();}}')
    put('androidx/media3/common/Player.java','package androidx.media3.common;public class Player{public static final int STATE_ENDED=4;}')
    put('org/telegram/messenger/AndroidUtilities.java','package org.telegram.messenger;public class AndroidUtilities{public static String formatShortDuration(int n){return ""+n;}}')
    put('org/telegram/messenger/LocaleController.java','package org.telegram.messenger;public class LocaleController{public static String getString(int n){return ""+n;}}')
    put('org/telegram/messenger/R.java','package org.telegram.messenger;public class R{public static class string{public static final int AccActionPause=1,AccActionPlay=2;}}')
    put('org/telegram/ui/Components/VideoPlayer.java', '''package org.telegram.ui.Components;
public class VideoPlayer {
 public interface VideoPlayerDelegate{void onStateChanged(boolean p,int s);void onError(VideoPlayer p,Exception e);void onVideoSizeChanged(int w,int h,int r,float a);void onRenderedFirstFrame();}
 public boolean play,released;public long position;public Object texture;public VideoPlayerDelegate delegate;
 public void setDelegate(VideoPlayerDelegate d){delegate=d;}public void setTextureView(Object t){texture=t;}
 public void preparePlayer(android.net.Uri u,String type){}public long getDuration(){return 10000;}public long getCurrentPosition(){return position;}
 public boolean getPlayWhenReady(){return play;}public void setPlayWhenReady(boolean p){if(play==p)return;play=p;if(delegate!=null)delegate.onStateChanged(play,3);}public void seekTo(long p){position=p;}public void releasePlayer(boolean a){released=true;}
}''')
    enter = (java / 'ui/Components/ChatActivityEnterView.java').read_text()
    prefix = enter.split('private Runnable recordAudioVideoRunnable = new Runnable() {', 1)[1].split('public void run() {', 1)[1].split('            if (slideText != null)', 1)[0]
    release = enter.split('float alpha = 1.0f + dist / distCanMove;', 1)[1]
    release = method(release[release.index('if (recordAudioVideoRunnableStarted)'):], 'if (recordAudioVideoRunnableStarted)')
    put('ModePort.java', '''public class ModePort {
 Object parentActivity=new Object(),recordAudioVideoRunnable=new Object();boolean recordAudioVideoRunnableStarted=true,calledRecordRunnable,sendVoiceEnabled=true,sendRoundEnabled=true,video;
 static class Delegate{int switches,pre;void onPreAudioVideoRecord(){pre++;}void onSwitchRecordMode(boolean video){switches++;}void needShowMediaBanHint(){}}
 Delegate delegate=new Delegate();
 static class HapticFeedbackConstants{static final int KEYBOARD_TAP=0;}static class AccessibilityEvent{static final int TYPE_VIEW_CLICKED=0;}
 static class AndroidUtilities{static void cancelRunOnUIThread(Object r){}}
 boolean isInVideoMode(){return video;}void setRecordVideoButtonVisible(boolean v,boolean a){video=v;}void performHapticFeedback(int n){}void sendAccessibilityEvent(int n){}
 void hold(){''' + prefix + '''}
 void release(){''' + release + '''}
 public static void main(String[] args){int checks=0;
 for(boolean video:new boolean[]{false,true}){
  ModePort p=new ModePort();p.video=video;p.calledRecordRunnable=true;org.telegram.messenger.purple.PurpleRecordingDock.refuse=true;p.hold();p.release();
  if(p.video!=video||p.delegate.switches!=0||p.calledRecordRunnable||p.recordAudioVideoRunnableStarted)throw new AssertionError("refused hold toggled mode");checks+=4;
  p=new ModePort();p.video=video;p.release();if(p.video==video||p.delegate.switches!=1)throw new AssertionError("stock short tap lost");checks+=2;
  p=new ModePort();org.telegram.messenger.purple.PurpleRecordingDock.refuse=false;p.hold();p.release();if(!p.calledRecordRunnable||p.delegate.switches!=0||p.delegate.pre!=1)throw new AssertionError("normal hold changed");checks+=3;
 }
 System.out.println("RECORDING_MODE_CHECKS="+checks);}
}''')
    put('org/telegram/messenger/purple/PurpleRecordingDock.java','package org.telegram.messenger.purple;public class PurpleRecordingDock{public static boolean refuse;public static boolean refuseStart(){return refuse;}}')
    put('org/telegram/messenger/purple/PurpleScreenTime.java','package org.telegram.messenger.purple;public class PurpleScreenTime{public static final int ACTION_VOICE=1;public static void action(int n){}}')
    spec = importlib.util.spec_from_file_location("ui_layout", pathlib.Path(__file__).with_name("ui_layout.py"))
    layout = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(layout)
    layout.prepare(root, output, put, method)
    compiler = pathlib.Path(os.environ['JAVA_HOME']) / 'bin/javac'
    subprocess.run([str(compiler), '-d', str(output/'classes'), *map(str, output.rglob('*.java'))], check=True)
    for name in ['BinPort', 'ModePort', 'UiConstructorCases', 'BinRtlCases', 'StripVisibilityCases', 'RoundControlsCases', 'SettingsAlignmentCases']:
        subprocess.run([str(compiler.with_name('java')), '-cp', str(output/'classes'), name], check=True)
    send = (java / 'messenger/purple/PurpleRecordingSend.java').read_text()
    assert 'PurpleRecordingFiles.move' not in send and 'PurpleRecordingRegistry.sent' not in send
    activity = (java / 'ui/PurpleRecordingBinActivity.java').read_text()
    assert 'R.string.PurpleRecordingBinSendSaved' in activity and 'self, 0, 0, false, this::sentResult' in activity
    assert 'PurpleRecordingDock' not in row
    print('BIN_SOURCE_PINS=5')
