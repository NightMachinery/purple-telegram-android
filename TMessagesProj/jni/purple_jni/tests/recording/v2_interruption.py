"""Exercise native V2 finish rejection and callbacks with the production owner."""
import pathlib, runpy


def method(source, marker):
    start = source.index(marker)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end].replace('@NonNull ', '')


def configure(root, ports):
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    native = (java / 'ui/Components/InstantCameraView2.java').read_text()
    session = (java / 'utils/recorder/round/RoundVideoSession.java').read_text()
    dock = (java / 'messenger/purple/PurpleRecordingDock.java').read_text()
    ports['org/telegram/utils/recorder/round/RoundVideoSession'] = '''public class RoundVideoSession {
      ''' + method(session, 'public enum State {') + '''
      public static class StateInfo {
        private final State state;public StateInfo(State s){state=s;}public State getState(){return state;}
        public long getRecordedDurationMs(){return 1200;}public long getMaximumDurationMs(){return 60000;}
        ''' + method(session, 'public boolean canFinish()') + '''
      }
      public State state=State.STARTING;public boolean cameraLive,micLive,player;
      public int accepted,stops;public boolean finishAfterCameraStops,finishWithAudio,cameraStopPending;
      public long recordedDurationMs,trimStartMs,trimEndMs=1200;public final Runnable durationLimitRunnable=()->{};
      public final org.telegram.ui.Components.InstantCameraView2 owner;
      public RoundVideoSession(org.telegram.ui.Components.InstantCameraView2 o){owner=o;}
      public void transition(State s){state=s;cameraLive=micLive=s==State.RECORDING;player=s==State.PREVIEWING;owner.onStateChanged(new StateInfo(s));}
      public void requireMainThread(){}public long getRecordedDurationMs(){return 1200;}
      public void disableCameraEffects(){}public void fail(Exception e){throw new AssertionError(e);}
      public void releasePlayer(){player=false;}public void setState(State s){state=s;owner.onStateChanged(new StateInfo(s));}
      public void finishFromPreview(boolean b){accepted++;org.telegram.messenger.AndroidUtilities.runOnUIThread(this::complete);}
      public void complete(){setState(State.COMPLETED);owner.delegate.sendMedia(new org.telegram.messenger.MediaController.PhotoEntry(),new org.telegram.messenger.VideoEditedInfo(),true,0,0,false,0);}
      public class Camera {public boolean stopSegment(){cameraLive=micLive=false;stops++;accepted++;org.telegram.messenger.AndroidUtilities.runOnUIThread(RoundVideoSession.this::complete);return true;}}
      public final Camera cameraController=new Camera();
      public static class Diagnostics {public void log(String s){}}public final Diagnostics diagnostics=new Diagnostics();
      public static class Handler {public void removeCallbacks(Runnable r){}}public final Handler mainHandler=new Handler();
      ''' + method(session, 'public boolean finish(boolean includeAudio)') + '}'
    ports['org/telegram/ui/Components/InstantCameraView2'] = '''import org.telegram.utils.recorder.round.RoundVideoSession;
      import org.telegram.messenger.*;
      public class InstantCameraView2 extends InstantCameraViewBase {
        private static final long MINIMUM_DURATION_MS=800;public RoundVideoSession session;
        public RoundVideoSession.StateInfo stateInfo;public int currentAccount,recordingGuid;
        public boolean muted,startedNotificationSent,resumeNotificationPending;public long lastVisibleDurationMs;
        public Object pendingSend;public static class SendOptions{public SendOptions(boolean n,int d,int r,int t,long e,long s){}}
        public static class Progress {public void setProgress(float f){}}public final Progress progressView=new Progress();
        public void showCamera(boolean b){starts++;currentAccount=delegate.getCurrentAccount();recordingGuid=delegate.getClassGuid();session=new RoundVideoSession(this);session.transition(RoundVideoSession.State.RECORDING);}
        public void emit(RoundVideoSession.State s){resumeNotificationPending=true;session.transition(s);}
        public long getCurrentDurationMs(){return 1200;}public void pauseForPreview(){}public void applyExternalTrim(){}
        public void postRecordStopped(int n){}public void saveLastCameraBitmap(boolean b){}public void stopHeavyOperations(){}
        public void hideInitialPlaceholder(){}public void setRecordingUiFrameClockActive(boolean b){}public void updateRecordingUiFrame(){}
        public void cancelCameraFlipAnimation(){}public void startHeavyOperations(){}public void updateButtons(){}
        ''' + method(native, 'public boolean isPaused()') + method(native, 'public void send(') + method(native, 'public void onStateChanged(').replace('InstantCameraView2.this', 'this') + '}'
    ports['org/telegram/messenger/NotificationCenter'] = '''public class NotificationCenter {
      public static final int audioRecordTooShort=1,recordStarted=2,recordResumed=3;
      public static final NotificationCenter instance=new NotificationCenter();public static int starts,resumes;
      public static NotificationCenter getInstance(int a){return instance;}
      public void postNotificationName(int id,Object...args){if(id==recordStarted){starts++;org.telegram.messenger.purple.PurpleRecordingDock.host.roundStarted();}else if(id==recordResumed)resumes++;}
    }'''
    body = ports['org/telegram/messenger/purple/PurpleRecordingDock']
    body = body.replace('public static Host host;', '''public static boolean roundFinalization;
      public static class State {public boolean round=true,finishing;public boolean matches(int a,int g){return host!=null&&host.target()[0]==a;}}
      public static final State state=new State();private static Host host(int g){return host;}private static void changed(){}
      ''' + method(dock, 'public static boolean beginRoundFinish(') + 'public static Host host;')
    body = body.replace('endings++;', 'endings++;roundFinalization=false;state.finishing=false;')
    ports['org/telegram/messenger/purple/PurpleRecordingDock'] = body


def run(root, output):
    runpy.run_path(str(pathlib.Path(__file__).with_name('cross_chat.py')))['run'](
        root, output, configure, 'V2InterruptionCases')
