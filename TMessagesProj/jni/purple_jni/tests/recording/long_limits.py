"""Execute production local policy and native timer/progress/audio/resume boundaries."""
import os,pathlib,subprocess,runpy,re
method=runpy.run_path(str(pathlib.Path(__file__).with_name('bubble_routing.py')))['method']


def run(root, output, baseline=False):
    output.mkdir(parents=True,exist_ok=False)
    java=root/'TMessagesProj/src/main/java/org/telegram'
    def source(path):
        if baseline:return subprocess.check_output(['git','show','a38cb4249f6d8152e9ecf729ccd8812c2bbeb6cc:TMessagesProj/src/main/java/org/telegram/'+path],cwd=root,text=True)
        return (java/path).read_text()
    legacy=source('ui/Components/InstantCameraView.java')
    composer=source('ui/Components/ChatActivityEnterView.java')
    native=(java/'utils/recorder/round/RoundVideoSession.java').read_text()
    timer=re.search(r'if \(t >= (.*?) && !stoppedInternal\)',composer).group(1)
    progress=re.search(r'progress = Math.min\(1f, recordedTime / (.*?)\);',legacy).group(1)
    stop=re.search(r'if \(!running && (\(input.offset\[a\].*?)\) \{',legacy).group(1)
    def put(path,text):
        p=output/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    put('org/telegram/messenger/purple/PurpleRoundLimits.java',(java/'messenger/purple/PurpleRoundLimits.java').read_text())
    put('org/telegram/messenger/MessagesController.java',"""package org.telegram.messenger;
      public class MessagesController {public static final Pref[] prefs={new Pref(),new Pref()};
       public static Pref getMainSettings(int a){return prefs[a];}
       public static class Pref{int value=1;public int getInt(String k,int d){return value;}public Pref edit(){return this;}
        public Pref putInt(String k,int v){value=v;return this;}public void apply(){}}
      }""")
    put('LimitsCases.java',"""import org.telegram.messenger.purple.PurpleRoundLimits;
      public class LimitsCases {
      enum State {RECORDING,PREVIEWING} State state=State.RECORDING;
      long recordedDurationMs,maximumDurationMs,recordingStartedAtRealtimeMs,trimStartMs,trimEndMs;
      static class SystemClock{static long now;static long elapsedRealtime(){return now;}}
      void requireMainThread(){}
      """+method(native,'    public long getRecordedDurationMs() {\n        requireMainThread();')+method(native,'        public boolean canResumeRecording()')+"""
      static boolean timer(long t,long purpleRoundLimitMs,boolean stoppedInternal){return t >= """+timer+""" && !stoppedInternal;}
      static float ring(long recordedTime,long purpleRoundLimitMs){return Math.min(1f,recordedTime / """+progress+""");}
      static class Input{long[] offset={0};}static Input input=new Input();static int a;static boolean running;
      static long videoLast=Long.MAX_VALUE,desyncTime;
      static boolean audio(long totalTime,long purpleAudioLimitUs){return !running && """+stop+""";}
      static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
      public static void main(String[] args){
       check(PurpleRoundLimits.minutes(0)==1&&PurpleRoundLimits.maximumDurationMs(0)==60000,"unchanged one-minute default");
       for(int m:new int[]{1,3,5}){
        PurpleRoundLimits.setMinutes(0,m);long cap=PurpleRoundLimits.maximumDurationMs(0);
        check(cap==m*60000L,"chosen local duration");check(PurpleRoundLimits.minutes(1)==1,"account isolation");
        check(!timer(cap-501,cap,false)&&timer(cap-500,cap,false)&&!timer(cap,cap,true),"actual composer cutoff and once-only guard");
        check(ring(cap/2,cap)==.5f&&ring(cap+100,cap)==1f,"actual legacy ring uses selected limit");
        check(!audio(cap*1000-1,cap*1000)&&audio(cap*1000,cap*1000),"actual legacy audio drain selected bound");
        if(m>1){check(!timer(60000,cap,false),"prior sixty-second composer cutoff removed");check(!audio(61000000,cap*1000),"audio beyond sixty seconds preserved");}
        LimitsCases nativeSession=new LimitsCases();nativeSession.maximumDurationMs=cap;nativeSession.recordedDurationMs=cap-1000;
        SystemClock.now=5000;nativeSession.recordingStartedAtRealtimeMs=1000;
        check(nativeSession.getRecordedDurationMs()==cap,"native duration clamps at configured bound");
        nativeSession.state=State.PREVIEWING;nativeSession.trimStartMs=0;nativeSession.trimEndMs=cap;
        check(!nativeSession.canResumeRecording(),"native retained preview at cap cannot resume");
        nativeSession.trimStartMs=500;check(nativeSession.canResumeRecording(),"explicit trim makes room for native resume");
        nativeSession.state=State.RECORDING;check(!nativeSession.canResumeRecording(),"cannot resume active session");
       }
       for(int invalid:new int[]{-1,0,2,4,6,999}){PurpleRoundLimits.setMinutes(0,invalid);check(PurpleRoundLimits.minutes(0)==1,"invalid preference defaults safely");}
       System.out.println("LONG_ROUND_LIMIT_CHECKS="+checks);
      }}""")
    compiler=pathlib.Path(os.environ['JAVA_HOME'])/'bin/javac'
    subprocess.run([str(compiler),'-d',str(output/'classes'),*map(str,output.rglob('*.java'))],check=True)
    subprocess.run([str(compiler.with_name('java')),'-cp',str(output/'classes'),'LimitsCases'],check=True)
