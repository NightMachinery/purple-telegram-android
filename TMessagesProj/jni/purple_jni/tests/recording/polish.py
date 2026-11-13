"""Run production recording-button and bin-player methods over inert host ports."""
import os, pathlib, subprocess
import importlib.util
_spec = importlib.util.spec_from_file_location("bubble_routing", pathlib.Path(__file__).with_name("bubble_routing.py"))
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
method = _module.method


def run(root, output, dock=None):
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
    dock_source = pathlib.Path(dock).read_text() if dock else (java / 'messenger/purple/PurpleRecordingDock.java').read_text()
    put('DockModalPort.java', '''import org.telegram.messenger.purple.PurpleMediaTrace;public class DockModalPort {
 static class State {boolean active,round;int account,guid;long generation;boolean active(){return active;}}
 static final State state=new State();static long discarded=-1;
 static class LaunchActivity{static LaunchActivity instance=new LaunchActivity();}
 static class R{static class string{static final int DiscardVoiceMessageTitle=1,DiscardVoiceMessageDescription=2,DiscardVoiceMessageAction=3,Continue=4,DiscardVideoMessageTitle=5,DiscardVideoMessageDescription=6;}}
 static class LocaleController{static String getString(int id){return "localized:"+id;}}
 interface Click{void run(Object d,int w);}
 static class AlertDialog{static Builder shown;static class Builder{
  String title,message;Click positive,negative;java.util.function.Consumer<Object> cancel;Builder(Object activity){}
  Builder setTitle(String v){title=v;return this;}Builder setMessage(String v){message=v;return this;}
  Builder setPositiveButton(String s,Click c){positive=c;return this;}Builder setNegativeButton(String s,Click c){negative=c;return this;}
  Builder setOnCancelListener(java.util.function.Consumer<Object> c){cancel=c;return this;}void show(){shown=this;}
 }}
 static void discard(long generation){discarded=generation;}
''' + method(dock_source, '    public static boolean refuseStart()') + '''
 static int test(){int checks=0;
  state.active=false;AlertDialog.shown=null;if(refuseStart()||AlertDialog.shown!=null)throw new AssertionError("inactive dialog");checks++;
  for(boolean round:new boolean[]{false,true}){
   state.active=true;state.round=round;state.generation=42;discarded=-1;
   if(!refuseStart())throw new AssertionError("active refusal");checks++;
   AlertDialog.Builder d=AlertDialog.shown;
   if(!d.title.equals(LocaleController.getString(round?R.string.DiscardVideoMessageTitle:R.string.DiscardVoiceMessageTitle))||!d.message.equals(LocaleController.getString(round?R.string.DiscardVideoMessageDescription:R.string.DiscardVoiceMessageDescription)))throw new AssertionError("recorder kind copy");checks+=2;
   if(d.negative!=null)d.negative.run(null,0);if(discarded!=-1)throw new AssertionError("Continue dispatched discard");checks++;
   state.generation=99;state.round=!round;d.positive.run(null,0);
   if(discarded!=42)throw new AssertionError("dialog lost captured generation");checks++;
  }
  LaunchActivity.instance=null;AlertDialog.shown=null;if(!refuseStart()||AlertDialog.shown!=null)throw new AssertionError("missing activity must still refuse");checks++;
  return checks;
 }
}''')
    fit = (java / 'ui/PurpleMediaSettingsRow.java').read_text()
    put('MediaFitPort.java', '''class MediaFitBase {int width,height;void onMeasure(int w,int h){width=w;height=0;}}
public class MediaFitPort extends MediaFitBase {
 static class MeasureSpec{static final int EXACTLY=1,UNSPECIFIED=0;static int getSize(int n){return n;}static int makeMeasureSpec(int n,int mode){return n;}}
 static class Gravity{static final int TOP=48,RIGHT=5,LEFT=3;}
 static class FrameLayout{static class LayoutParams{int gravity,width,topMargin;}}
 static class AndroidUtilities{static int dp(int n){return n;}}
 static class LocaleController{static boolean isRTL;}
 static class Text{FrameLayout.LayoutParams params=new FrameLayout.LayoutParams();int measured,width,gravity;void measure(int w,int h){width=w;}int getMeasuredHeight(){return measured;}Object getLayoutParams(){return params;}void setGravity(int v){gravity=v;}}
 final Text title=new Text(),value=new Text();Text getTextView(){return title;}Text getValueTextView(){return value;}
''' + method(fit, '    protected void onMeasure(').replace('protected void', 'void') + '''
 static int test(){int checks=0;
 for(boolean rtl:new boolean[]{false,true})for(int width:new int[]{240,320,1080})for(int titleHeight:new int[]{20,48,96}){
  LocaleController.isRTL=rtl;MediaFitPort p=new MediaFitPort();p.title.measured=titleHeight;p.onMeasure(width,0);
  if(p.title.width!=width-42||p.title.params.width!=width-42||p.value.params.width!=width-42)throw new AssertionError("wrapped width");checks++;
  if(p.value.params.topMargin!=titleHeight+14)throw new AssertionError("detail overlaps measured long title");checks++;
  if(p.title.gravity!=(rtl?5:3)||p.value.gravity!=(rtl?5:3)||p.title.params.gravity!=(48|(rtl?5:3)))throw new AssertionError("native RTL anchor");checks++;
 }return checks;}
}''')
    put('SeekLabelPort.java', '''public class SeekLabelPort {
''' + method((java / 'messenger/purple/PurpleSeek.java').read_text(), '    public static String label(') + '''
 static int test(){int checks=0;
 for(int step:new int[]{5,10,15,30})for(int direction:new int[]{-1,1}){
  String text=label(direction,step);String sign=direction<0?"−":"+";
  if(!text.equals("\\u2066"+sign+step+"\\u2069"))throw new AssertionError("seek isolate");checks++;
  String rtl="سلام "+text+" پایان";java.text.Bidi b=new java.text.Bidi(rtl,java.text.Bidi.DIRECTION_RIGHT_TO_LEFT);
  int signAt=rtl.indexOf(sign);if((b.getLevelAt(signAt)&1)!=0||b.getLevelAt(signAt)!=b.getLevelAt(signAt+1))throw new AssertionError("seek sign reordered");checks++;
 }return checks;}
}''')
    for name in ['PurpleMediaTrace', 'PurpleDiagLogStore']:
        put('org/telegram/messenger/purple/' + name + '.java', (java / 'messenger/purple' / (name + '.java')).read_text())
    put('android/os/SystemClock.java', 'package android.os;public class SystemClock{public static long now=1000;public static long elapsedRealtime(){return now;}}')
    put('org/telegram/messenger/purple/PurpleLongRoundOriginals.java','package org.telegram.messenger.purple;public class PurpleLongRoundOriginals{public static class Owner{public PurpleMediaTrace.Take trace;}}')
    put('org/telegram/messenger/purple/PurpleDiagLog.java', '''package org.telegram.messenger.purple;public class PurpleDiagLog{
 public static final java.util.List<String> lines=new java.util.ArrayList<>();public static PurpleDiagLogStore.Field sourceBuild(){return PurpleDiagLogStore.sourceBuild("abcdef0");}
 public static PurpleDiagLogStore.Field count(String k,int v){return PurpleDiagLogStore.count(k,v);}public static PurpleDiagLogStore.Field code(String k,String v){return PurpleDiagLogStore.code(k,v);}
 public static void event(String a,String e,PurpleDiagLogStore.Field... f){lines.add(PurpleDiagLogStore.line(0,a,e,f));}
}''')
    put('MediaTraceCases.java','''package org.telegram.messenger.purple;
public class MediaTraceCases{
 public static int test(){int checks=0;PurpleDiagLog.lines.clear();
 PurpleMediaTrace.Take a=PurpleMediaTrace.begin(0,123456789,"legacy",180000);
 a.event(PurpleMediaTrace.Step.PAUSE_REQUEST);android.os.SystemClock.now+=37;a.event(PurpleMediaTrace.Step.PAUSE_ACK);
 String lines=String.join("\\n",PurpleDiagLog.lines);
 if(lines.indexOf("step=pause_request")>=lines.indexOf("step=pause_ack")||!lines.contains("elapsed_ms=37")||!lines.contains("cap_s=180"))throw new AssertionError("actual trace order/cap/monotonic delta");checks++;
 int before=PurpleDiagLog.lines.size();PurpleMediaTrace.begin(0,123456789,"v2",60000);int replaced=PurpleDiagLog.lines.size();a.event(PurpleMediaTrace.Step.MUXER_COMPLETE);
 if(PurpleDiagLog.lines.size()!=replaced||replaced!=before+2)throw new AssertionError("late old generation logged into replacement");checks++;
 PurpleMediaTrace.Take b=PurpleMediaTrace.current(0,123456789);b.event(PurpleMediaTrace.Step.RETAIN_FAILED,false);b.event(PurpleMediaTrace.Step.RETAIN_FAILED,false);
 if(PurpleDiagLog.lines.size()!=replaced+1)throw new AssertionError("retry failure flood");checks++;
 b.event(PurpleMediaTrace.Step.RETAIN_SAVED);b.retire();int retired=PurpleDiagLog.lines.size();b.event(PurpleMediaTrace.Step.FLIP_FIRST_FRAME);
 if(PurpleDiagLog.lines.size()!=retired)throw new AssertionError("late terminal callback logged");checks++;
 PurpleMediaTrace.Take op=PurpleMediaTrace.operation("split"),previous=PurpleMediaTrace.enter(op);
 java.util.ArrayList<PurpleDiagLogStore.Field> fields=new java.util.ArrayList<>();PurpleMediaTrace.decorate(fields);PurpleMediaTrace.leave(previous);
 if(fields.size()!=7)throw new AssertionError("operation correlation missing");checks++;
 fields.clear();PurpleMediaTrace.decorate(fields);if(!fields.isEmpty())throw new AssertionError("operation scope leaked");checks++;
 for(String line:PurpleDiagLog.lines)if(line.contains("123456789")||line.contains("invalid")||line.length()>512)throw new AssertionError("trace privacy/format");checks++;
 return checks;}
}''')
    put('ModePort.java', '''public class ModePort {
 Object parentActivity=new Object(),recordAudioVideoRunnable=new Object();boolean recordAudioVideoRunnableStarted=true,calledRecordRunnable,sendVoiceEnabled=true,sendRoundEnabled=true,video;
 static class Delegate{int switches,pre;void onPreAudioVideoRecord(){pre++;}void onSwitchRecordMode(boolean video){switches++;}void needShowMediaBanHint(){}}
 Delegate delegate=new Delegate();
 static class HapticFeedbackConstants{static final int KEYBOARD_TAP=0;}static class AccessibilityEvent{static final int TYPE_VIEW_CLICKED=0;}
 static class AndroidUtilities{static void cancelRunOnUIThread(Object r){}}
 boolean isInVideoMode(){return video;}void setRecordVideoButtonVisible(boolean v,boolean a){video=v;}void performHapticFeedback(int n){}void sendAccessibilityEvent(int n){}
 void hold(){''' + prefix + '''}
 void release(){''' + release + '''}
 public static void main(String[] args){int checks=DockModalPort.test()+MediaFitPort.test()+SeekLabelPort.test()+org.telegram.messenger.purple.MediaTraceCases.test();
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
    put('android/os/SystemClock.java', 'package android.os;public class SystemClock{public static long now=1000;public static long elapsedRealtime(){return now;}public static long uptimeMillis(){return now;}}')
    compiler = pathlib.Path(os.environ['JAVA_HOME']) / 'bin/javac'
    subprocess.run([str(compiler), '-d', str(output/'classes'), *map(str, output.rglob('*.java'))], check=True)
    for name in ['BinPort', 'ModePort', 'UiConstructorCases', 'BinRtlCases', 'RecordingStripRtlCases', 'StripVisibilityCases', 'RoundControlsCases', 'RoundContrastCases', 'SettingsAlignmentCases']:
        subprocess.run([str(compiler.with_name('java')), '-cp', str(output/'classes'), name], check=True)
    send = (java / 'messenger/purple/PurpleRecordingSend.java').read_text()
    assert 'PurpleRecordingFiles.move' not in send and 'PurpleRecordingRegistry.sent' not in send
    activity = (java / 'ui/PurpleRecordingBinActivity.java').read_text()
    assert 'R.string.PurpleRecordingBinSendSaved' in activity and 'sendItem(entry, currentAccount, self, 0, 0, false)' in activity
    assert 'PurpleRecordingDock' not in row
    print('BIN_SOURCE_PINS=5')
