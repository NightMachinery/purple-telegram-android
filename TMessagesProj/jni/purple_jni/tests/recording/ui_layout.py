"""Construct the real bin row with the real LayoutHelper overloads over inert UI ports."""
import pathlib, re


def prepare(root, output, put, method):
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    for name in ['PurpleRecordingBinRow', 'PurpleRecordingPlayButton']:
        put('org/telegram/ui/' + name + '.java', (java / 'ui' / (name + '.java')).read_text())
    helper = (java / 'ui/Components/LayoutHelper.java').read_text()
    signatures = re.findall(r'    public static LinearLayout.LayoutParams createLinear\([^\n]+', helper)
    frames = [
        '    public static FrameLayout.LayoutParams createFrame(int width, float height)',
        '    public static FrameLayout.LayoutParams createFrame(int width, int height, int gravity)']
    methods = [method(helper, signature) for signature in signatures + frames]
    methods.append(method(helper, '    private static int getSize('))
    put('org/telegram/ui/Components/LayoutHelper.java', 'package org.telegram.ui.Components;import android.widget.*;import org.telegram.messenger.AndroidUtilities;public class LayoutHelper {public static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;' + ''.join(methods) + '}')
    put('android/content/Context.java', 'package android.content;public class Context{public static final int MODE_PRIVATE=0;public SharedPreferences getSharedPreferences(String n,int m){return new SharedPreferences();}}')
    put('android/graphics/Canvas.java', 'package android.graphics;public class Canvas{public void drawLine(float a,float b,float c,float d,Paint p){}}')
    put('android/graphics/Outline.java', 'package android.graphics;public class Outline{public void setOval(int l,int t,int r,int b){}}')
    put('android/text/TextUtils.java', 'package android.text;public class TextUtils{public enum TruncateAt{END}}')
    put('android/view/Gravity.java', 'package android.view;public class Gravity{public static final int CENTER_VERTICAL=16,CENTER_HORIZONTAL=1,CENTER=17,START=3,END=5,TOP=48,LEFT=3,RIGHT=5,HORIZONTAL_GRAVITY_MASK=7;}')
    put('android/view/ViewOutlineProvider.java','package android.view;public abstract class ViewOutlineProvider{public abstract void getOutline(View v,android.graphics.Outline o);}')
    put('android/view/View.java', '''package android.view;
import android.content.Context;import android.graphics.Canvas;
public class View{
 public static final int VISIBLE=0,INVISIBLE=4,GONE=8,LAYOUT_DIRECTION_LTR=0,LAYOUT_DIRECTION_RTL=1,TEXT_DIRECTION_LTR=3,TEXT_ALIGNMENT_GRAVITY=1;
 public int padLeft,padTop,padRight,padBottom,textDirection,textAlignment;public Object background;private final java.util.Map<Integer,Object> tags=new java.util.HashMap<>();
 public Object getTag(int key){return tags.get(key);}public void setTag(int key,Object value){tags.put(key,value);}public void setTextAlignment(int v){textAlignment=v;}public int getTextAlignment(){return textAlignment;}public int getTextDirection(){return textDirection;}
 private ViewGroup.LayoutParams params;public int visibility,width=48,height=48,direction;public boolean enabled=true,pressed;private OnClickListener click;
 private final Context context;public View(Context c){context=c;}public Context getContext(){return context;}public void setClickable(boolean b){}public boolean isAttachedToWindow(){return false;}public void post(Runnable r){}protected void onVisibilityChanged(View v,int n){}public void setLayoutParams(ViewGroup.LayoutParams p){params=p;}public ViewGroup.LayoutParams getLayoutParams(){return params;}
 public void setVisibility(int v){visibility=v;}public int getVisibility(){return visibility;}public int getWidth(){return width;}public int getHeight(){return height;}
 public void setPadding(int l,int t,int r,int b){padLeft=l;padTop=t;padRight=r;padBottom=b;}public void setLayoutDirection(int n){direction=n;}public void setTextDirection(int n){textDirection=n;}public void setBackgroundColor(int n){}public void setBackground(Object d){background=d;}
 public void setOutlineProvider(ViewOutlineProvider p){}public void setClipToOutline(boolean b){}public void setEnabled(boolean b){enabled=b;}public void setFocusable(boolean b){}
 public void setContentDescription(String s){}public void invalidate(){}public void removeCallbacks(Runnable r){}public void postDelayed(Runnable r,int ms){}
 public interface OnClickListener{void run(View v);}public interface OnLongClickListener{boolean run(View v);}
 public void setOnClickListener(OnClickListener l){click=l;}public boolean performClick(){if(!enabled||click==null)return false;click.run(this);return true;}
 public void setOnLongClickListener(OnLongClickListener l){}public void setPressed(boolean p){pressed=p;}public boolean isPressed(){return pressed;}
 protected void onDraw(Canvas c){}public void draw(Canvas c){onDraw(c);}protected void onAttachedToWindow(){}protected void onDetachedFromWindow(){}protected void onMeasure(int w,int h){}public void measure(int w,int h){onMeasure(w,h);}
}''')
    put('android/view/ViewGroup.java','''package android.view;import android.content.Context;import java.util.*;
public class ViewGroup extends View{
 public static class LayoutParams{public int width,height;public LayoutParams(int w,int h){width=w;height=h;}}
 public static class MarginLayoutParams extends LayoutParams{public int leftMargin,topMargin,rightMargin,bottomMargin;public MarginLayoutParams(int w,int h){super(w,h);}public void setMargins(int l,int t,int r,int b){leftMargin=l;topMargin=t;rightMargin=r;bottomMargin=b;}}
 public final List<View> children=new ArrayList<>();public ViewGroup(Context c){super(c);}public void addView(View v,LayoutParams p){v.setLayoutParams(p);children.add(v);}public void removeAllViews(){children.clear();}public void removeView(View v){children.remove(v);}public void addView(View v,int index,LayoutParams p){v.setLayoutParams(p);children.add(index,v);}public View getChildAt(int n){return children.get(n);}public int getChildCount(){return children.size();}
}''')
    put('android/widget/LinearLayout.java','''package android.widget;import android.content.Context;import android.view.*;
public class LinearLayout extends ViewGroup{public static final int HORIZONTAL=0,VERTICAL=1;public int orientation;
 public LinearLayout(Context c){super(c);}public void setOrientation(int o){orientation=o;}public void setGravity(int g){}
 public static class LayoutParams extends ViewGroup.MarginLayoutParams{public float weight;public int gravity;public LayoutParams(int w,int h){super(w,h);}public LayoutParams(int w,int h,float t){super(w,h);weight=t;}}
}''')
    put('android/widget/FrameLayout.java','''package android.widget;import android.content.Context;import android.view.*;
public class FrameLayout extends ViewGroup{public FrameLayout(Context c){super(c);}public static class LayoutParams extends ViewGroup.MarginLayoutParams{public int gravity;public LayoutParams(int w,int h){super(w,h);}public LayoutParams(int w,int h,int g){super(w,h);gravity=g;}}}''')
    put('android/widget/TextView.java','''package android.widget;import android.view.View;import android.content.Context;
public class TextView extends View{public String text;public boolean single;public int gravity;public TextView(Context c){super(c);}public void setText(String s){text=s;}public String getText(){return text;}public void setTextSize(int n){}public void setTextColor(int n){}public void setGravity(int n){gravity=n;}public int getGravity(){return gravity;}public void setTypeface(Object o){}public void setSingleLine(boolean b){single=b;}public void setEllipsize(android.text.TextUtils.TruncateAt e){}}''')
    put('android/widget/ImageView.java','package android.widget;import android.content.Context;import android.view.View;public class ImageView extends View{public enum ScaleType{CENTER}public Object drawable;private android.graphics.ColorFilter filter;public ImageView(Context c){super(c);}public void setImageResource(int n){}public void setScaleType(ScaleType t){}public void setColorFilter(int n){}public void setColorFilter(android.graphics.ColorFilter n){filter=n;}public android.graphics.ColorFilter getColorFilter(){return filter;}public void setImageDrawable(Object d){drawable=d;}}')
    put('android/view/TextureView.java','package android.view;import android.content.Context;public class TextureView extends View{public TextureView(Context c){super(c);}}')
    put('org/telegram/messenger/AndroidUtilities.java','package org.telegram.messenger;public class AndroidUtilities{public static int dp(float n){return (int)n;}public static Object bold(){return null;}public static String formatShortDuration(int n){return ""+n;}}')
    put('org/telegram/messenger/LocaleController.java','package org.telegram.messenger;public class LocaleController{public static boolean isRTL;public static String getString(int n){return ""+n;}public static String formatString(int n,Object... args){return args[0]+" of "+args[1];}}')
    put('org/telegram/messenger/R.java','package org.telegram.messenger;public class R{public static class string{public static final int AccActionPause=1,AccActionPlay=2,AccDescrMoreOptions=3,PurpleRecordingBinPlay=4,AccDescrSwitchCamera=5,PurpleRecordingResume=6,PurpleRecordingPause=7,DiscardVoiceMessageTitle=8,DiscardVoiceMessageDescription=9,DiscardVoiceMessageAction=10,Cancel=11,PurpleRecordingBinRound=12,PurpleRecordingVoice=13,PurpleRecordingSendElsewhere=14,Send=15,PurpleRecordingNotificationHint=16,PurpleLongRoundLocal=17,AccDescrPlayerDuration=18;}public static class drawable{public static final int msg_actions=1,purple_settings=2,send_plane_24=3,miniplayer_close=4;}public static class raw{public static final int roundcamera_flip=1;}}')
    put('org/telegram/messenger/purple/PurpleRecordingEntry.java','package org.telegram.messenger.purple;public class PurpleRecordingEntry{public static final String UNRECOVERABLE="bad";public String state="recovered",media="inert.ogg";public long durationMs=10000;public boolean round;public boolean round(){return round;}}')
    put('org/telegram/ui/ActionBar/Theme.java','''package org.telegram.ui.ActionBar;public class Theme{
 public static class ResourcesProvider{public boolean dark;public boolean isDark(){return dark;}}public static boolean globalDark;public static final int key_windowBackgroundWhite=1,key_windowBackgroundWhiteGrayIcon=2,key_listSelector=3,key_windowBackgroundWhiteGrayText=4,key_windowBackgroundWhiteBlackText=5,key_player_progressBackground=6,key_player_progress=7,key_player_time=8,key_chat_inLoader=9,key_chat_inLoaderSelected=10,key_chat_inMediaIcon=11,key_chat_inMediaIconSelected=12,key_chat_inAudioSelectedProgress=13,key_chat_inAudioProgress=14,key_inappPlayerTitle=15,key_inappPlayerPerformer=16,key_inappPlayerClose=17,key_inappPlayerPlayPause=18;
 public static int getColor(int key,ResourcesProvider r){return key;}public static Object createSelectorDrawable(int c,int type){return null;}public static Object createSelectorDrawable(int c,int type,int radius){return null;}
}''')
    put('org/telegram/ui/Components/SeekBarView.java','''package org.telegram.ui.Components;import android.view.View;import android.content.Context;import org.telegram.ui.ActionBar.Theme;
public class SeekBarView extends View{public float progress;public boolean report;public SeekBarViewDelegate delegate;public int inner,outer;
 public interface SeekBarViewDelegate{void onSeekBarDrag(boolean stop,float progress);default void onSeekBarPressed(boolean p){}default CharSequence getContentDescription(){return null;}}
 public SeekBarView(Context c,Theme.ResourcesProvider r){super(c);}public void setColors(int i,int o){inner=i;outer=o;}public void setReportChanges(boolean b){report=b;}public void setDelegate(SeekBarViewDelegate d){delegate=d;}public void setProgress(float f){progress=f;}
}''')
    put('org/telegram/ui/Components/MediaActionDrawable.java','package org.telegram.ui.Components;public class MediaActionDrawable{public static final int ICON_PLAY=0,ICON_PAUSE=1;}')
    put('org/telegram/ui/Components/RadialProgress2.java','''package org.telegram.ui.Components;import android.view.View;import android.graphics.Canvas;import org.telegram.ui.ActionBar.Theme;
public class RadialProgress2{public static RadialProgress2 last;public int icon;public int[] colors;public boolean attached;public RadialProgress2(View v,Theme.ResourcesProvider r){last=this;}public void setColorKeys(int a,int b,int c,int d){colors=new int[]{a,b,c,d};}public void setIcon(int n,boolean same,boolean animate){icon=n;}public void setProgressRect(int a,int b,int c,int d){}public void setProgressColor(int c){}public void draw(Canvas c){}public void setPressed(boolean p,boolean m){}public void onAttachedToWindow(){attached=true;}public void onDetachedFromWindow(){attached=false;}}
''')
    put('UiConstructorCases.java','''import android.content.Context;import android.view.*;import android.widget.*;import org.telegram.ui.*;import org.telegram.ui.Components.*;import org.telegram.ui.ActionBar.Theme;import org.telegram.messenger.*;import org.telegram.messenger.purple.*;
public class UiConstructorCases{
 static int checks;static void check(boolean b,String n){checks++;if(!b)throw new AssertionError(n);}static LinearLayout.LayoutParams params(View v){return (LinearLayout.LayoutParams)v.getLayoutParams();}
 public static void main(String[] args){
 for(boolean rtl:new boolean[]{false,true})for(boolean round:new boolean[]{false,true}){
 LocaleController.isRTL=rtl;PurpleRecordingEntry entry=new PurpleRecordingEntry();entry.round=round;
 PurpleRecordingBinRow row=new PurpleRecordingBinRow(new Context(),null,entry,"Long recording title","kind\\nduration\\ndate\\nstatus",()->{},()->{},()->{});
 LinearLayout heading=(LinearLayout)row.getChildAt(0);TextView name=(TextView)heading.getChildAt(rtl?1:0);check(params(name).weight==1f&&params(name).width==0,"title uses weighted overload");check(name.single,"title cannot inflate row height");
 check(params(heading).width==-1&&params(heading).height==-2,"heading fills width and wraps height");
 View metadata=row.getChildAt(1);check(params(metadata).width==-1&&params(metadata).height==-2,"metadata fills width and wraps");
 View preview=row.getChildAt(2);check(preview.getVisibility()==View.GONE,"preview reserves no space initially");
 LinearLayout controls=(LinearLayout)row.getChildAt(3);PurpleRecordingPlayButton toggle=(PurpleRecordingPlayButton)controls.getChildAt(0);SeekBarView seek=(SeekBarView)controls.getChildAt(1);
 check(params(controls).width==-1&&params(controls).height==48,"playbar fills width with48dp height");check(params(toggle).width==48&&params(toggle).height==48,"play48dp touch target");check(params(seek).weight==1f&&params(seek).width==0,"seek uses weighted overload");check(seek.report,"seek reports drags like stock player");check(seek.delegate.getContentDescription().toString().equals("0 of 10"),"initial accessible elapsed/total");
 check(seek.inner==Theme.key_player_progressBackground&&seek.outer==Theme.key_player_progress,"stock player seek palette");
 LinearLayout times=(LinearLayout)row.getChildAt(4);check(params(times.getChildAt(0)).weight==1f,"elapsed uses weighted overload");
 RadialProgress2 radial=RadialProgress2.last;check(java.util.Arrays.equals(radial.colors,new int[]{Theme.key_chat_inLoader,Theme.key_chat_inLoaderSelected,Theme.key_chat_inMediaIcon,Theme.key_chat_inMediaIconSelected}),"SharedAudioCell radial palette");check(radial.icon==MediaActionDrawable.ICON_PLAY,"initial visible play icon");
 toggle.performClick();check(radial.icon==MediaActionDrawable.ICON_PAUSE,"production click changes native icon");seek.delegate.onSeekBarDrag(true,.25f);check(((TextView)times.getChildAt(0)).text.equals("2"),"production seek updates elapsed");check(seek.delegate.getContentDescription().toString().equals("2 of 10"),"accessible elapsed follows selected position");toggle.performClick();check(radial.icon==MediaActionDrawable.ICON_PLAY,"second click pauses native icon");row.stop();
 }
 check(LayoutHelper.createLinear(0,-2,1).weight==0,"integer overload reproduced original regression");check(LayoutHelper.createLinear(0,-2,1f).weight==1f,"float overload preserves weight");
 System.out.println("RECORDING_UI_CONSTRUCTOR_CHECKS="+checks);
 }
}''')
    put('BinRtlCases.java','''import android.content.Context;import android.view.*;import android.widget.*;import org.telegram.ui.*;import org.telegram.messenger.*;import org.telegram.messenger.purple.*;
public class BinRtlCases{
 static int checks;static void check(boolean b,String n){checks++;if(!b)throw new AssertionError(n);}
 public static void main(String[] args){
 for(boolean initial:new boolean[]{false,true})for(String label:new String[]{"Long Latin title","عنوان طويل","Mixed عنوان"}){
  LocaleController.isRTL=initial;String details=label+" · 1:25\\nالساعة 5:22\\nDiscarded";
  PurpleRecordingBinRow row=new PurpleRecordingBinRow(new Context(),null,new PurpleRecordingEntry(),label,details,()->{},()->{},()->{});
  LinearLayout heading=(LinearLayout)row.getChildAt(0);TextView name=(TextView)heading.getChildAt(initial?1:0),metadata=(TextView)row.getChildAt(1);
  for(boolean rtl:new boolean[]{!initial,initial,!initial}){
   LocaleController.isRTL=rtl;row.measure(320,600);
   check(heading.getChildAt(rtl?1:0)==name,"title and overflow use explicit physical order on locale reuse");
   check(name.getGravity()==(rtl?Gravity.RIGHT:Gravity.LEFT)&&metadata.getGravity()==name.getGravity(),"all metadata lines share stock physical anchor");
   check(name.getTextAlignment()==View.TEXT_ALIGNMENT_GRAVITY&&metadata.getTextAlignment()==View.TEXT_ALIGNMENT_GRAVITY,"bidi content cannot choose paragraph anchor");
   check(name.text.equals(label)&&metadata.text.equals(details),"Latin Arabic mixed content preserved");
   check(name.getTextDirection()==0&&metadata.getTextDirection()==0,"native bidi text direction preserved");
   LinearLayout controls=(LinearLayout)row.getChildAt(3),times=(LinearLayout)row.getChildAt(4);
   check(controls.direction==View.LAYOUT_DIRECTION_LTR&&times.direction==View.LAYOUT_DIRECTION_LTR,"native seek and numerical time row stay LTR");
   check(times.getChildAt(0).getTextDirection()==View.TEXT_DIRECTION_LTR&&times.getChildAt(1).getTextDirection()==View.TEXT_DIRECTION_LTR,"numeric times isolated LTR");
   check(((TextView)times.getChildAt(0)).getGravity()==Gravity.LEFT&&((TextView)times.getChildAt(1)).getGravity()==Gravity.RIGHT,"elapsed left and total right like AudioPlayerAlert");
  }
 }
 System.out.println("RECORDING_BIN_RTL_CHECKS="+checks);}
}''')
    assert 'android:supportsRtl="false"' in (root/'TMessagesProj/src/main/AndroidManifest.xml').read_text()
    strip = (java / 'ui/PurpleRecordingStrip.java').read_text()
    visibility = method(strip, '    private void recordingVisibility(')
    height = method(strip, '    public int height(')
    put('StripVisibilityCases.java','''import android.view.View;import android.content.Context;import java.util.*;
public class StripVisibilityCases{
 static final int BUTTON_DP=48;static class FragmentContextView{static final int STYLE_AUDIO_PLAYER=0;}final Map<View,Integer> stockVisibility=new HashMap<>();
''' + visibility + height + '''
 static int checks;static void check(boolean b,String n){checks++;if(!b)throw new AssertionError(n);}
 public static void main(String[] args){StripVisibilityCases p=new StripVisibilityCases();View title=new View(new Context()),play=new View(new Context()),close=new View(new Context()),speed=new View(new Context());speed.setVisibility(View.GONE);
 for(int i=0;i<2;i++)p.recordingVisibility(true,title,play,close,speed);
 for(View v:new View[]{title,play,close,speed})check(v.getVisibility()==View.INVISIBLE,"stock glyphs suppressed under transparent strip");
 close.setVisibility(View.GONE);p.recordingVisibility(false);check(title.getVisibility()==View.VISIBLE&&play.getVisibility()==View.VISIBLE,"player controls restore");check(speed.getVisibility()==View.GONE,"hidden speed restores hidden");check(close.getVisibility()==View.GONE,"new native visibility preserved");check(p.stockVisibility.isEmpty(),"restoration is one-shot");
 check(p.height(0,36)==48,"recording shares existing player height");check(p.height(2,36)==36,"other strip styles unchanged");
 System.out.println("RECORDING_STRIP_VISIBILITY_CHECKS="+checks);}
}''')
    activity = (java / 'ui/PurpleRecordingBinActivity.java').read_text()
    assert 'UItem.asCustom(row, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT)' in activity
    assert 'recordingVisibility(recording, title, play, close, speed);' in strip
    assert 'LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, BUTTON_DP, Gravity.TOP)' in strip
    put('org/telegram/ui/Components/PurpleRoundControls.java', (java/'ui/Components/PurpleRoundControls.java').read_text())
    put('android/graphics/Color.java','package android.graphics;public class Color{public static final int WHITE=-1,BLACK=0xff000000;}')
    put('android/graphics/PorterDuff.java','package android.graphics;public class PorterDuff{public enum Mode{MULTIPLY}}')
    put('android/graphics/ColorFilter.java','package android.graphics;public class ColorFilter{}')
    put('android/graphics/PorterDuffColorFilter.java','package android.graphics;public class PorterDuffColorFilter extends ColorFilter{public final int color;public PorterDuffColorFilter(int c,PorterDuff.Mode m){color=c;}}')
    put('org/telegram/ui/Components/PlayPauseDrawable.java','package org.telegram.ui.Components;public class PlayPauseDrawable{public boolean pause;public PlayPauseDrawable(int size){}public void setColor(int c){}public void setPause(boolean b,boolean animated){pause=b;}}')
    put('org/telegram/ui/Components/RLottieDrawable.java','package org.telegram.ui.Components;public class RLottieDrawable{public RLottieDrawable(int id,int w,int h){}public int getFramesCount(){return 10;}public void setCurrentFrame(int n){}}')
    put('org/telegram/ui/Components/blur3/drawable/color/BlurredBackgroundColorProvider.java','package org.telegram.ui.Components.blur3.drawable.color;public interface BlurredBackgroundColorProvider{}')
    put('org/telegram/ui/Components/blur3/drawable/BlurredBackgroundDrawable.java','package org.telegram.ui.Components.blur3.drawable;public class BlurredBackgroundDrawable{public int padding,radius;public void setPadding(int p){padding=p;}public void setRadius(int r){radius=r;}}')
    put('org/telegram/ui/Components/blur3/BlurredBackgroundDrawableViewFactory.java','''package org.telegram.ui.Components.blur3;
import android.view.View;import org.telegram.ui.Components.blur3.drawable.*;import org.telegram.ui.Components.blur3.drawable.color.*;
public class BlurredBackgroundDrawableViewFactory{public View parent;public BlurredBackgroundDrawable create(View p,BlurredBackgroundColorProvider c){parent=p;return new BlurredBackgroundDrawable();}}''')
    put('RoundControlsCases.java','''import android.content.Context;import android.view.*;import android.widget.*;import org.telegram.ui.Components.*;import org.telegram.ui.Components.blur3.*;import org.telegram.ui.Components.blur3.drawable.*;
public class RoundControlsCases{
 static int checks;static void check(boolean b,String n){checks++;if(!b)throw new AssertionError(n);}
 public static void main(String[] args){int[] clicks={0,0};BlurredBackgroundDrawableViewFactory factory=new BlurredBackgroundDrawableViewFactory();
 PurpleRoundControls controls=new PurpleRoundControls(new Context(),()->clicks[0]++,()->clicks[1]++,new Object[]{factory,null},()->null);
 check(controls.getLayoutParams() instanceof FrameLayout.LayoutParams,"explicit camera-host layout params");FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)controls.getLayoutParams();
 check(p.width==-2&&p.height==-2,"production glass wrapper wraps both axes");check(p.gravity==(Gravity.TOP|Gravity.LEFT),"absolute origin matches setX/setY placement");
 check(controls.orientation==LinearLayout.HORIZONTAL&&controls.getChildCount()==2,"stock horizontal camera controls");
 int width=controls.padLeft+controls.padRight,height=0;for(int i=0;i<2;i++){View v=controls.getChildAt(i);check(v.getLayoutParams().width==48&&v.getLayoutParams().height==48,"48dp child target");width+=v.getLayoutParams().width;height=Math.max(height,v.getLayoutParams().height);}
 check(width==108&&height+controls.padTop+controls.padBottom==60,"stock capsule intrinsic108x60dp");
 BlurredBackgroundDrawable bg=(BlurredBackgroundDrawable)controls.background;check(factory.parent==controls&&bg.padding==6&&bg.radius==21,"native glass factory,padding,radius retained");
 controls.getChildAt(0).performClick();controls.getChildAt(1).performClick();check(clicks[0]==1&&clicks[1]==1,"native actions retained");
 controls.update(true);check(!((PlayPauseDrawable)((ImageView)controls.getChildAt(1)).drawable).pause,"paused state shows play");
 controls.update(false);check(((PlayPauseDrawable)((ImageView)controls.getChildAt(1)).drawable).pause,"recording state shows pause");
 System.out.println("ROUND_CONTROLS_LAYOUT_CHECKS="+checks);}
}''')
    settings = (java/'ui/PurpleSettingsRow.java').read_text()
    alignment = method(settings, '    public static void alignText(')
    tag = re.search(r'    private static final int ALIGNMENT_TAG = [^;]+;', settings)[0]
    put('SettingsAlignmentCases.java','''import android.content.Context;import android.view.*;import android.widget.TextView;import org.telegram.messenger.*;
public class SettingsAlignmentCases{
'''+tag+alignment+'''
 static int checks;static void check(boolean b,String n){checks++;if(!b)throw new AssertionError(n);}
 public static void main(String[] args){
 for(boolean rtl:new boolean[]{false,true})for(String label:new String[]{"Purple Settings","عنوان","Mixed عنوان"}){
 LocaleController.isRTL=rtl;TextView title=new TextView(new Context()),subtitle=new TextView(new Context());title.setText(label);subtitle.setText("Work عنوان");
 title.setGravity(17);subtitle.setGravity(48);title.setTextAlignment(0);subtitle.setTextAlignment(4);title.setTextDirection(3);subtitle.setTextDirection(4);int titleGravity=title.getGravity(),subtitleGravity=subtitle.getGravity();
 alignText(R.drawable.purple_settings,title,subtitle);
 check((title.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==(rtl?Gravity.RIGHT:Gravity.LEFT)&&(subtitle.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==(title.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK),"locale gravity for Latin,Arabic,mixed labels");
 check(title.getTextAlignment()==View.TEXT_ALIGNMENT_GRAVITY&&subtitle.getTextAlignment()==View.TEXT_ALIGNMENT_GRAVITY,"gravity owns physical label alignment");
 check(title.getTextDirection()==3&&subtitle.getTextDirection()==4,"native text direction preserved");check(title.text.equals(label)&&subtitle.text.equals("Work عنوان"),"strings unchanged");
 subtitle.setText("New preset");alignText(R.drawable.purple_settings,title,subtitle);check((subtitle.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==(title.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK),"dynamic subtitle uses same policy");
 alignText(R.drawable.msg_actions,title,subtitle);check(title.getGravity()==titleGravity&&subtitle.getGravity()==subtitleGravity,"recycled stock row gravity restored");check(title.getTextAlignment()==0&&subtitle.getTextAlignment()==4,"native alignment restored");
 check(title.getTag(ALIGNMENT_TAG)==null&&subtitle.getTag(ALIGNMENT_TAG)==null,"override state released");
 }
 System.out.println("SETTINGS_RTL_ALIGNMENT_CHECKS="+checks);}
}''')
    settings_cell = (java/'ui/SettingsActivity.java').read_text()
    assert 'PurpleSettingsRow.alignText(icon, titleView, subtitleView);' in settings_cell
    stock_text = (java/'ui/Cells/TextCell.java').read_text()
    assert 'setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)' in stock_text

    import importlib.util
    spec = importlib.util.spec_from_file_location("strip_layout", pathlib.Path(__file__).with_name("strip_layout.py"))
    strip_layout = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(strip_layout)
    strip_layout.prepare(root, put)

    spec = importlib.util.spec_from_file_location("controls_contrast", pathlib.Path(__file__).with_name("controls_contrast.py"))
    controls_contrast = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(controls_contrast)
    controls_contrast.prepare(root, put, method)
