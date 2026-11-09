"""Production owner/factory and native constructor theme contract over inert UI ports."""
import pathlib, runpy

method = runpy.run_path(str(pathlib.Path(__file__).with_name('v2_interruption.py')))['method']


def configure(root, ports):
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    theme = (java / 'ui/ActionBar/Theme.java').read_text()
    base = (java / 'ui/Components/InstantCameraViewBase.java').read_text()
    legacy = (java / 'ui/Components/InstantCameraView.java').read_text()
    v2 = (java / 'ui/Components/InstantCameraView2.java').read_text()
    ports['android/graphics/ColorFilter'] = 'public class ColorFilter {}'
    ports['android/graphics/Paint'] = 'public class Paint {}'
    ports['android/graphics/drawable/Drawable'] = 'public class Drawable {}'
    ports['org/telegram/ui/ActionBar/Theme'] = """import android.graphics.*;import android.graphics.drawable.Drawable;
      public class Theme {
      public static boolean dark;public static int shift,matrixCalls;
      public static Paint paint=new Paint();public static ColorFilter chat_animatedEmojiTextColorFilter=new ColorFilter();
      public static int getColor(int key){return key+shift;}
      public static boolean isCurrentThemeDark(){return dark;}
      public static Paint getThemePaint(String key){return paint;}
      public static void applyServiceShaderMatrix(int w,int h,float x,float y){matrixCalls++;}
      """ + method(theme, 'public interface ResourcesProvider {') + '}'
    ports['org/telegram/messenger/AppGlobalConfig'] = """public class AppGlobalConfig {
      public static final AppGlobalConfig[] all={new AppGlobalConfig(),new AppGlobalConfig()};public static int lastAccount=-1;
      public static AppGlobalConfig getInstance(int a){lastAccount=a;return all[a];}
      public static class Flag {public boolean value;public boolean get(){return value;}}
      public final Flag roundVideoRecorder2Allowed=new Flag();
    }"""
    ports['org/telegram/utils/settings/SharedSettings'] = """public class SharedSettings {
      public static class Flag {public boolean set,value;public boolean isSet(){return set;}public boolean get(){return value;}}
      public static final Flag roundVideoCamera2Enabled=new Flag();
    }"""
    ports['org/telegram/messenger/SharedConfig'] = 'public class SharedConfig {public static boolean isUsingCamera2(int a){return false;}}'
    body = ports['org/telegram/ui/Components/InstantCameraViewBase']
    old_factory = method(body, 'public static InstantCameraViewBase create(')
    body = body.replace(old_factory, method(base, 'public static InstantCameraViewBase create('))
    body = body.replace('public class InstantCameraViewBase extends android.view.View {', """import org.telegram.ui.ActionBar.Theme;
      import org.telegram.utils.settings.SharedSettings;import org.telegram.messenger.AppGlobalConfig;
      import android.content.Context;
      public class InstantCameraViewBase extends android.view.View {
      protected InstantCameraViewBase(Context c){}public void setWillNotDraw(boolean b){}
      public static int dp(int n){return org.telegram.messenger.AndroidUtilities.dp(n);}
      """)
    ports['org/telegram/ui/Components/InstantCameraViewBase'] = body
    delegate = method(ports['org/telegram/ui/Components/InstantCameraView'], 'public interface Delegate {')
    delegate = delegate[:-1] + method(legacy, 'default boolean isSecretChat()') + '}'
    for name, source, marker, cutoff in [
        ('InstantCameraView', legacy, 'public InstantCameraView(', '        paint = new Paint'),
        ('InstantCameraView2', v2, 'public InstantCameraView2(', '        flashViews = new FlashViews')]:
        ctor = method(source, marker)
        prefix = ctor[:ctor.index(cutoff)]
        branch = method(ctor, 'if (!isNewDesign) {')
        branch += ' else ' + method(ctor, 'if (' + ('!resourcesProvider.isDark()' if name=='InstantCameraView' else 'resourcesProvider != null && !resourcesProvider.isDark()') + ') {')
        ports['org/telegram/ui/Components/' + name] = """import android.content.Context;
          import org.telegram.ui.ActionBar.Theme;import org.telegram.messenger.SharedConfig;
          public class """ + name + """ extends InstantCameraViewBase {
          public int currentAccount,recordingGuid,buttonsSizePx;public boolean useCamera2,WRITE_TO_FILE_IN_BACKGROUND,isSecretChat,secretChat;
          public android.view.View parentView;public Theme.ResourcesProvider resourcesProvider;
          public static class Button {public float invert;public void setInvert(float f){invert=f;}}
          public static class Flash {public int adds;public void add(Button b){adds++;}}
          public final Button switchCameraButton=new Button(),flashButton=new Button();public final Flash flashViews=new Flash();
          """ + (delegate if name=='InstantCameraView' else '') + prefix + branch + 'creations++;last=this;}}'
    ports['org/telegram/ui/ChatActivity'] = ports['org/telegram/ui/ChatActivity'].replace('public class ChatActivity {',
        'public class ChatActivity {public org.telegram.ui.ActionBar.Theme.ResourcesProvider getResourceProvider(){throw new AssertionError("must not read or retain chat theme");}')


def run(root, output):
    runpy.run_path(str(pathlib.Path(__file__).with_name('cross_chat.py')))['run'](
        root, output, configure, 'ConstructionCases')
