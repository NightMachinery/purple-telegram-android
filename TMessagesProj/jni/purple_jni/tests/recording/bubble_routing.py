"""Run production bubble/base dispatch with inert Android layout/touch ports."""
import os, pathlib, shutil, subprocess


def method(text, signature):
    start = text.index(signature)
    pos = text.index('{', start)
    depth = 1
    end = pos + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def run(root, output, bubble=None):
    output.mkdir(parents=True, exist_ok=False)
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    def put(name, text):
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
    for name in ['ui/Components/PurpleRoundBubble.java', 'ui/Components/InstantCameraViewBase.java',
                 'messenger/purple/PurpleRoundBubbleGeometry.java',
                 'messenger/purple/PurpleRecordingDockState.java', 'messenger/purple/PurpleRecordingSettings.java']:
        text = pathlib.Path(bubble).read_text() if bubble and name.endswith('/PurpleRoundBubble.java') else (java / name).read_text()
        put('org/telegram/' + name, text)
    put('android/content/Context.java', """package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 public final Prefs prefs=new Prefs();
 public Prefs getSharedPreferences(String name,int mode){return prefs;}
 public static class Prefs {public int corner=3;public int getInt(String key,int fallback){return corner;}
 public Prefs edit(){return this;}public Prefs putInt(String key,int value){corner=value;return this;}public void apply(){}}
}""")
    put('android/view/MotionEvent.java', """package android.view;
public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
 private final int action;private final float x,y;
 public MotionEvent(int action,float x,float y){this.action=action;this.x=x;this.y=y;}
 public int getAction(){return action;}public int getActionMasked(){return action;}
 public float getX(){return x;}public float getY(){return y;}
 public MotionEvent offset(float dx,float dy){return new MotionEvent(action,x+dx,y+dy);}
}""")
    put('android/view/View.java', """package android.view;
import android.content.Context;import android.graphics.Rect;import java.util.*;
public class View {
 public static final int VISIBLE=0,INVISIBLE=4,GONE=8;
 protected final Context context;public int left,top,width,height,paddingBottom,visibility,events;
 private float tx,ty,sx=1,sy=1;private OnClickListener click;
 private final Map<Integer,Object> tags=new HashMap<>();
 public View(Context c){context=c;}public Context getContext(){return context;}
 public float getX(){return left+tx;}public float getY(){return top+ty;}
 public void setX(float v){tx=v-left;}public void setY(float v){ty=v-top;}
 public int getLeft(){return left;}public int getTop(){return top;}
 public int getWidth(){return width;}public int getHeight(){return height;}
 public int getMeasuredHeight(){return height;}public int getPaddingBottom(){return paddingBottom;}
 public float getPivotX(){return width/2f;}public float getPivotY(){return height/2f;}
 public float getScaleX(){return sx;}public float getScaleY(){return sy;}
 public void setScaleX(float v){sx=v;}public void setScaleY(float v){sy=v;}
 public float getTranslationX(){return tx;}public float getTranslationY(){return ty;}
 public void setTranslationX(float v){tx=v;}public void setTranslationY(float v){ty=v;}
 public int getVisibility(){return visibility;}public void setVisibility(int v){visibility=v;}
 public Object getTag(int key){return tags.get(key);}public void setTag(int key,Object value){tags.put(key,value);}
 public boolean dispatchTouchEvent(MotionEvent e){events++;return onTouchEvent(e);}
 public boolean onTouchEvent(MotionEvent e){if(click==null)return false;if(e.getAction()==MotionEvent.ACTION_UP&&e.getX()>=0&&e.getX()<=width&&e.getY()>=0&&e.getY()<=height)performClick();return true;}
 public boolean performClick(){if(click==null)return false;click.onClick(this);return true;}
 public interface OnClickListener{void onClick(View v);}public void setOnClickListener(OnClickListener l){click=l;}
 public void setContentDescription(String text){}public void setBackgroundColor(int color){}
 public void getWindowVisibleDisplayFrame(Rect r){r.bottom=2400;}public void getLocationOnScreen(int[] location){location[0]=left;location[1]=top;}
 public void bringToFront(){}
 public interface LayoutListener{void changed(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob);}
 public void addOnLayoutChangeListener(LayoutListener l){}
 public static class Observer{public interface PreDraw{boolean run();}public void addOnPreDrawListener(PreDraw l){}}
 public Observer getViewTreeObserver(){return new Observer();}
}""")
    put('android/view/ViewGroup.java', """package android.view;
import android.content.Context;import java.util.*;
public class ViewGroup extends View {
 protected final List<View> children=new ArrayList<>();private View target;
 public ViewGroup(Context c){super(c);}public void addView(View v){children.add(v);}
 public int getChildCount(){return children.size();}public View getChildAt(int n){return children.get(n);}
 public boolean dispatchTouchEvent(MotionEvent e){
  if(e.getAction()==MotionEvent.ACTION_DOWN){target=null;for(int i=children.size()-1;i>=0;i--){View v=children.get(i);
   if(v.getVisibility()==VISIBLE&&e.getX()>=v.getX()&&e.getX()<=v.getX()+v.getWidth()&&e.getY()>=v.getY()&&e.getY()<=v.getY()+v.getHeight()
    &&v.dispatchTouchEvent(e.offset(-v.getX(),-v.getY()))){target=v;return true;}}}
  else if(target!=null){View v=target;boolean result=v.dispatchTouchEvent(e.offset(-v.getX(),-v.getY()));if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL)target=null;return result;}
  return onTouchEvent(e);
 }
}""")
    put('android/widget/FrameLayout.java', """package android.widget;
import android.content.Context;import android.view.*;
public class FrameLayout extends ViewGroup {
 public int normalDispatches;public FrameLayout(Context c){super(c);}
 public boolean dispatchTouchEvent(MotionEvent e){normalDispatches++;return super.dispatchTouchEvent(e);}
}""")
    put('android/widget/LinearLayout.java', """package android.widget;
import android.content.Context;import android.view.*;
public class LinearLayout extends ViewGroup {
 public LinearLayout(Context c){super(c);}public static class LayoutParams{int w,h;public LayoutParams(int w,int h){this.w=w;this.h=h;}}
 public void addView(View v,LayoutParams p){v.left=width;v.width=p.w;v.height=p.h;width+=p.w;height=Math.max(height,p.h);super.addView(v);}
}""")
    put('android/widget/TextView.java', 'package android.widget;import android.content.Context;import android.view.View;public class TextView extends View {public TextView(Context c){super(c);}public void setText(String s){}public void setGravity(int g){}public void setTextColor(int c){}}')
    put('android/view/ViewConfiguration.java', 'package android.view;import android.content.Context;public class ViewConfiguration {public static ViewConfiguration get(Context c){return new ViewConfiguration();}public int getScaledTouchSlop(){return 8;}}')
    put('android/view/Gravity.java', 'package android.view;public class Gravity {public static final int CENTER=1;}')
    put('android/view/TextureView.java', 'package android.view;import android.content.Context;public class TextureView extends View {public TextureView(Context c){super(c);}}')
    for name, body in [('Rect','public int bottom;'),('RectF',''),('Paint','')]:
        put('android/graphics/'+name+'.java','package android.graphics;public class '+name+'{'+body+'}')
    put('org/telegram/messenger/AndroidUtilities.java', 'package org.telegram.messenger;public class AndroidUtilities {public static int dp(int n){return n;}}')
    put('org/telegram/messenger/LocaleController.java', 'package org.telegram.messenger;public class LocaleController {public static String getString(int n){return "control";}}')
    put('org/telegram/messenger/R.java', 'package org.telegram.messenger;public class R {public static class string {public static final int AccDescrSwitchCamera=1,PurpleRecordingPause=2;}}')
    put('org/telegram/messenger/ImageReceiver.java', 'package org.telegram.messenger;public class ImageReceiver {}')
    put('org/telegram/messenger/UserConfig.java', 'package org.telegram.messenger;public class UserConfig {public static int selectedAccount;}')
    put('org/telegram/messenger/AppGlobalConfig.java', 'package org.telegram.messenger;import org.telegram.utils.settings.SharedSettings;public class AppGlobalConfig {public SharedSettings.Flag roundVideoRecorder2Allowed=new SharedSettings.Flag();public static AppGlobalConfig getInstance(int account){return new AppGlobalConfig();}}')
    put('org/telegram/utils/settings/SharedSettings.java', 'package org.telegram.utils.settings;public class SharedSettings {public static Flag roundVideoCamera2Enabled=new Flag();public static class Flag {public boolean value;public boolean isSet(){return true;}public boolean get(){return value;}}}')
    put('org/telegram/ui/ActionBar/Theme.java', 'package org.telegram.ui.ActionBar;public class Theme {public static class ResourcesProvider {}public static final int key_inappPlayerTitle=1,key_inappPlayerBackground=2;public static int getColor(int n){return n;}}')
    for name in ['blur3/BlurredBackgroundDrawableViewFactory','blur3/drawable/color/BlurredBackgroundColorProvider']:
        package,klass=name.rsplit('/',1)
        put('org/telegram/ui/Components/'+name+'.java','package org.telegram.ui.Components.'+package.replace('/','.')+';public interface '+klass+' {}')
    put('org/telegram/messenger/purple/PurpleCore.java', 'package org.telegram.messenger.purple;public class PurpleCore {public static int[] settings;public static int[] recordingSettings(){return settings;}}')
    put('org/telegram/messenger/purple/PurpleRecordingDock.java', 'package org.telegram.messenger.purple;public class PurpleRecordingDock {public static final PurpleRecordingDockState state=new PurpleRecordingDockState();}')
    put('org/telegram/ui/Components/HostCamera.java', """package org.telegram.ui.Components;
import android.content.Context;import android.graphics.*;import android.view.*;import android.widget.*;import org.telegram.messenger.ImageReceiver;
import org.telegram.ui.Components.blur3.*;import org.telegram.ui.Components.blur3.drawable.color.*;
public class HostCamera extends InstantCameraViewBase {
 public boolean paused;public int flips,pauses;private final InstantViewCameraContainer camera;private final FrameLayout buttons;
 public HostCamera(Context c){super(c);width=1080;height=2400;paddingBottom=280;
  camera=new InstantViewCameraContainer(c){public void setImageReceiver(ImageReceiver r){}};camera.left=400;camera.top=700;camera.width=camera.height=280;addView(camera);
  buttons=new FrameLayout(c);buttons.left=200;buttons.top=100;buttons.width=120;buttons.height=40;
  View flip=new View(c);flip.width=flip.height=40;flip.setOnClickListener(v->flips++);buttons.addView(flip);addView(buttons);
 }
 public void setButtonsBackground(BlurredBackgroundDrawableViewFactory f,BlurredBackgroundColorProvider c){}
 public void setInternalPadding(int p){paddingBottom=p;}public void destroy(boolean a){}
 public void togglePause(){paused=!paused;pauses++;}public boolean isPaused(){return paused;}
 public void showCamera(boolean p){}public void startAnimation(boolean o,boolean p){}public RectF getCameraRect(){return new RectF();}
 public void changeVideoPreviewState(int s,float p){}public void send(int s,boolean n,int d,int r,int t,long e,long stars){}
 public void cancel(boolean g){}public View getButtonsLayout(){return buttons;}public View getMuteImageView(){return null;}
 public Paint getPaint(){return null;}public void hideCamera(boolean a){}public TextureView getTextureView(){return null;}
 public InstantViewCameraContainer getCameraContainer(){return camera;}public void setIsMessageTransition(boolean m){}
 public void resetCameraFile(){}public void onPanTranslationUpdate(float y){}
}""")
    v2 = method((java/'ui/Components/InstantCameraView2.java').read_text(), 'public boolean onTouchEvent(')
    legacy = method((java/'ui/Components/InstantCameraView.java').read_text(), 'public boolean onTouchEvent(')
    inset = legacy[legacy.index('if ('):legacy.index('if (ev.getAction() == MotionEvent.ACTION_DOWN && delegate')]
    for name, touch in [('InstantCameraView', 'public boolean onTouchEvent(MotionEvent ev) {'+inset+'return true;}'), ('InstantCameraView2', v2)]:
        put('org/telegram/ui/Components/'+name+'.java', 'package org.telegram.ui.Components;import android.content.Context;import android.view.MotionEvent;import org.telegram.ui.ActionBar.Theme;public class '+name+' extends HostCamera {public interface Delegate {}public '+name+'(Context c,InstantCameraView.Delegate d,Theme.ResourcesProvider r,boolean n){super(c);}'+touch+'}')
    shutil.copyfile(pathlib.Path(__file__).with_name('BubbleRoutingCases.java'), output/'BubbleRoutingCases.java')
    compiler = pathlib.Path(os.environ['JAVA_HOME'])/'bin/javac'
    subprocess.run([str(compiler),'-d',str(output/'classes'),*map(str,sorted(output.rglob('*.java')))],check=True)
    subprocess.run([str(compiler.with_name('java')),'-cp',str(output/'classes'),'BubbleRoutingCases'],check=True)
