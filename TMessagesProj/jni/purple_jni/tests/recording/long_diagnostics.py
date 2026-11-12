"""Run actual long-output methods and diagnostic sanitizer over bounded media ports."""
from pathlib import Path
import hashlib, json, os, subprocess

def run(root, output, compile_wrapper=None, case=None, media_source=None):
    output.mkdir(parents=True, exist_ok=False)
    java=root/'TMessagesProj/src/main/java/org/telegram'
    def put(name,text):
        p=output/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    copied={}
    for name in ['PurpleLongRoundMedia','PurpleLongRoundDiagnostics','PurpleLongRoundPlan','PurpleLongRoundSelection','PurpleDiagLogStore']:
        source=java/'messenger/purple'/(name+'.java')
        if name == 'PurpleLongRoundMedia' and media_source is not None: source=media_source
        put('org/telegram/messenger/purple/'+name+'.java',source.read_text());copied[str(source)]=hashlib.sha256(source.read_bytes()).hexdigest()
    ui=(java/'ui/PurpleLongRoundSend.java').read_text()
    start=ui.index('                } catch (Exception failure) {',ui.index('void prepare(boolean circles)'))
    end=ui.index('\n                }',start)+len('\n                }')
    caught=ui[start+17:end]
    assert caught.startswith(' catch (Exception failure) {')
    put('org/telegram/messenger/purple/ActualRequestCatch.java','package org.telegram.messenger.purple;public class ActualRequestCatch {public static boolean run(Exception input){boolean invalid=false;try{throw input;}'+caught+'return invalid;}}')
    copied[str(java/'ui/PurpleLongRoundSend.java')]=hashlib.sha256(ui.encode()).hexdigest()
    times=json.loads((Path(__file__).with_name('long_diagnostic_video_pts.json')).read_text())
    def body(text, signature):
        start=text.index(signature);opening=text.index('{',start);depth=1;pos=opening+1
        while depth:
            depth += (text[pos]=='{')-(text[pos]=='}');pos+=1
        return text[start:pos]
    track_source=java/'messenger/video/Track.java';track=track_source.read_text()
    copied[str(track_source)]=hashlib.sha256(track_source.read_bytes()).hexdigest()
    sample=java/'messenger/video/Sample.java'
    put('org/telegram/messenger/video/Sample.java',sample.read_text());copied[str(sample)]=hashlib.sha256(sample.read_bytes()).hexdigest()
    put('android/media/MediaCodec.java','package android.media;public class MediaCodec{public static final int BUFFER_FLAG_SYNC_FRAME=1;public static class BufferInfo{public int flags,size;public long presentationTimeUs;}}')
    put('org/telegram/messenger/video/NativeTrack.java','''package org.telegram.messenger.video;import java.util.*;import android.media.*;public class NativeTrack {
     private boolean isAudio;private int timeScale;private long duration;private ArrayList<Sample> samples=new ArrayList<>();private LinkedList<Integer> syncSamples=null;private ArrayList<SamplePresentationTime> samplePresentationTimes=new ArrayList<>();private long[] sampleDurations;private int[] sampleCompositions;
     '''+body(track,'private static class SamplePresentationTime')+body(track,'public void addSample(')+body(track,'public void prepare()')+'''
     public static long[] normalize(long[] source,long start,long end){NativeTrack t=new NativeTrack();t.timeScale=90000;for(long p:source)if(p>=start&&p<end){MediaCodec.BufferInfo i=new MediaCodec.BufferInfo();i.size=16;i.presentationTimeUs=p;t.addSample(0,i);}t.prepare();long[] out=new long[t.sampleDurations.length+1];long time=0;for(int i=0;i<t.sampleDurations.length;i++){out[i]=time*1000000/90000;time+=t.sampleDurations[i];}out[out.length-1]=t.duration*1000000/90000;return out;}}
    ''')
    put('android/os/SystemClock.java','package android.os;public class SystemClock {public static int calls,failAt=-1;public static long elapsedRealtime(){return ++calls==failAt?2000000:0;}}')
    put('org/telegram/messenger/VideoEditedInfo.java','package org.telegram.messenger;public class VideoEditedInfo{public long startTime,endTime,estimatedSize,estimatedDuration;public boolean roundVideo,muted;public int resultWidth,originalWidth,resultHeight,originalHeight,rotationValue,framerate;public String originalPath;public boolean needConvert(){return true;}}')
    put('org/telegram/messenger/MediaController.java','package org.telegram.messenger;public class MediaController{public interface VideoConvertorListener{boolean checkConversionCanceled();void didWriteData(long size,float progress);}}')
    put('org/telegram/messenger/purple/PurpleDiagLog.java','''package org.telegram.messenger.purple;import java.util.*;public class PurpleDiagLog {
      public static boolean fail;public static final List<String> lines=new ArrayList<>();static void check(){if(fail)throw new AssertionError("diagnostic port failed");}
      public static PurpleDiagLogStore.Field count(String k,int v){check();return PurpleDiagLogStore.count(k,v);}public static PurpleDiagLogStore.Field flag(String k,boolean v){check();return PurpleDiagLogStore.flag(k,v);}public static PurpleDiagLogStore.Field code(String k,String v){check();return PurpleDiagLogStore.code(k,v);}public static PurpleDiagLogStore.Field reason(String v){check();return PurpleDiagLogStore.reason(v);}
      public static void event(String a,String e,PurpleDiagLogStore.Field...f){check();lines.add(PurpleDiagLogStore.line(0,a,e,f));}}
    ''')
    put('org/telegram/messenger/purple/PurpleRecordingFiles.java','package org.telegram.messenger.purple;import java.io.*;import java.nio.file.*;public class PurpleRecordingFiles{public static void copy(File a,File b)throws IOException{if(android.media.Facts.mode.equals("copy"))throw android.media.Facts.io;Files.copy(a.toPath(),b.toPath(),StandardCopyOption.REPLACE_EXISTING);if(android.media.Facts.mode.equals("copy_digest"))Files.write(b.toPath(),new byte[]{7});}}')
    put('org/telegram/messenger/video/audio_input/AudioInput.java','package org.telegram.messenger.video.audio_input;public class AudioInput {public void release(){}}')
    put('org/telegram/messenger/video/audio_input/BlankAudioInput.java','package org.telegram.messenger.video.audio_input;public class BlankAudioInput extends AudioInput{public BlankAudioInput(long t){}}')
    put('org/telegram/messenger/purple/PurpleLongRoundAudioInput.java','''package org.telegram.messenger.purple;import java.io.*;public class PurpleLongRoundAudioInput extends org.telegram.messenger.video.audio_input.AudioInput {
      public static int released;public PurpleLongRoundAudioInput(String p,int t)throws IOException{if(android.media.Facts.mode.equals("conversion_setup"))throw android.media.Facts.io;}
      public void release(){released++;if(android.media.Facts.mode.equals("audio_release"))throw android.media.Facts.runtime;}}
    ''')
    put('android/media/MediaFormat.java','''package android.media;public class MediaFormat{public static final String KEY_MIME="mime",KEY_DURATION="duration";public final String mime;public final long duration;public MediaFormat(String m,long d){mime=m;duration=d;}public String getString(String k){return mime;}public long getLong(String k){return duration;}public boolean containsKey(String k){return true;}}''')
    put('android/media/Facts.java','''package android.media;import java.io.*;import java.util.*;public class Facts {
      public static String mode="ok";public static File source;public static int calls;public static org.telegram.messenger.purple.PurpleLongRoundMedia.Work work;
      public static final IOException io=new IOException("/private/SENSITIVE_PATH/secret@person.test account_123456789");public static final IllegalStateException runtime=new IllegalStateException(io.getMessage());
      public static final Map<String,Integer> parts=new HashMap<>();public static final long[] VIDEO={'''+','.join(str(v)+'L'for v in times)+'''};
      public static boolean part(String path){return parts.containsKey(path);}public static long duration(String p){if(mode.equals("coverage"))return coverageDuration;if(!part(p))return 69419000;if(mode.equals("vfr_bad"))return 34900000;if(mode.equals("vfr_ok"))return 34720000;if(mode.equals("part_duration"))return 35000000;if(mode.equals("duration"))return 60100000;return 34710000;}
      public static long[] vfr(int part){return org.telegram.messenger.video.NativeTrack.normalize(VIDEO,part==0?0:34709500,part==0?34709500:69419000);}
      public static long coverageDuration=34773000,videoEnd=34654333,audioEnd=34772833;public static long[] videoPts,audioPts;public static boolean sync=true;
      public static long trackDuration(String path,int track){if(mode.equals("coverage"))return track==0?videoEnd:audioEnd;if(mode.startsWith("vfr")&&part(path)&&track==0){long[] v=vfr(1);return v[v.length-1];}return duration(path);}
      public static long[] pts(String path,int track){if(mode.equals("coverage"))return track==0?videoPts:audioPts;if(mode.startsWith("vfr")&&part(path)&&track==0){long[] v=vfr(1);return Arrays.copyOf(v,v.length-1);}
       if(mode.equals("start")&&part(path))return new long[]{100001,34700000};if(mode.equals("timestamps")&&part(path))return new long[]{0,200000,100000};if(mode.equals("missing_samples")&&part(path))return new long[]{0,34000000};if(mode.equals("duration")&&part(path))return new long[]{0,59950000};return new long[]{0,duration(path)-10000};}
    }''')
    put('android/media/MediaMetadataRetriever.java','''package android.media;public class MediaMetadataRetriever{public static final int METADATA_KEY_DURATION=1,METADATA_KEY_VIDEO_WIDTH=2,METADATA_KEY_VIDEO_HEIGHT=3,METADATA_KEY_VIDEO_ROTATION=4;String path;
      public void setDataSource(String p)throws java.io.IOException{path=p;if(Facts.mode.equals("source_inspect")&&!Facts.part(p)||Facts.mode.equals("part_inspect")&&Facts.part(p))throw Facts.io;}
      public String extractMetadata(int k){return k==1?Long.toString(Facts.duration(path)/1000):k==4?"0":k==2&&Facts.mode.equals("format")&&Facts.part(path)?"383":"384";}
      public void release(){if(Facts.mode.equals("metadata_release")&&Facts.part(path))throw Facts.runtime;}}
    ''')
    put('android/media/MediaExtractor.java','''package android.media;public class MediaExtractor {public static final int SEEK_TO_PREVIOUS_SYNC=0,SAMPLE_FLAG_SYNC=1;String path;int track,pos;
      public void setDataSource(String p)throws java.io.IOException{path=p;if(Facts.mode.equals("extractor_open")&&Facts.part(p))throw Facts.io;}
      public int getTrackCount(){return Facts.mode.equals("output_audio")&&Facts.part(path)?1:2;}public MediaFormat getTrackFormat(int t){if(Facts.mode.equals("track_format")&&Facts.part(path))throw Facts.runtime;return new MediaFormat(t==0?(Facts.mode.equals("tracks")&&Facts.part(path)?"video/other":"video/avc"):(Facts.mode.equals("audio_format")&&Facts.part(path)?"audio/other":"audio/mp4a-latm"),Facts.trackDuration(path,t));}
      public void selectTrack(int t){track=t;}public void unselectTrack(int t){if(Facts.mode.equals("track_unselect")&&Facts.part(path))throw Facts.runtime;}public void seekTo(long t,int m){pos=0;}public long getSampleTime(){long[] p=Facts.pts(path,track);return pos<p.length?p[pos]:-1;}public int getSampleFlags(){return !Facts.sync||Facts.mode.equals("sync")&&Facts.part(path)?0:1;}public boolean advance(){return ++pos<Facts.pts(path,track).length;}
      public void release(){if(Facts.mode.equals("extractor_release")&&Facts.part(path))throw Facts.runtime;if(Facts.mode.equals("source_digest")&&!Facts.part(path))Facts.source.delete();}}
    ''')
    put('org/telegram/messenger/video/MediaCodecVideoConvertor.java','''package org.telegram.messenger.video;import java.io.*;import java.nio.file.*;import org.telegram.messenger.*;import org.telegram.messenger.video.audio_input.*;import android.media.Facts;
      public class MediaCodecVideoConvertor{public boolean convertVideo(ConvertVideoParams p)throws Exception{Facts.parts.put(p.file.toString(),Facts.calls++);Files.write(p.file.toPath(),new byte[]{1,2,3});if(Facts.mode.equals("work_check"))Facts.work.cancelled.set(true);if(Facts.mode.equals("final_digest")&&Facts.calls==2)Files.write(Facts.source.toPath(),new byte[]{9});return Facts.mode.equals("conversion");}
      public static class ConvertVideoParams{public AudioInput purpleAudioInput;public long purpleAudioDurationUs;File file;public static ConvertVideoParams of(String p,File f,long o,int rot,boolean secret,int w,int h,int rw,int rh,int fps,int b,int ob,long start,long end,long av,boolean compress,long dur,MediaController.VideoConvertorListener cb,VideoEditedInfo info){ConvertVideoParams out=new ConvertVideoParams();out.file=f;return out;}}}
    ''')
    put('org/telegram/messenger/purple/LongDiagnosticCases.java','''package org.telegram.messenger.purple;
import java.io.*;import java.nio.file.*;import java.util.*;import android.media.Facts;
public class LongDiagnosticCases {
 static int checks;static File directory;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s+" logs="+PurpleDiagLog.lines);}
 static void reset(String mode)throws Exception{Facts.sync=true;Facts.mode=mode;Facts.calls=0;Facts.parts.clear();Facts.source=new File(directory,"SENSITIVE_PATH-account_123456789.mp4");Files.write(Facts.source.toPath(),new byte[]{4,5,6});PurpleDiagLog.lines.clear();PurpleDiagLog.fail=false;android.os.SystemClock.calls=0;android.os.SystemClock.failAt=-1;Facts.work=new PurpleLongRoundMedia.Work();}
 static String lines(){return String.join("\\n",PurpleDiagLog.lines);}static void privacy(){for(String l:PurpleDiagLog.lines){check(!l.contains("SENSITIVE_PATH")&&!l.contains("123456789")&&!l.contains("person.test")&&!l.contains("/private/")&&!l.contains("invalid"),"typed values survive without private text or unknown tokens");check(l.length()<=512,"bounded line");}}
 static Exception prepare(boolean circles){try{PurpleLongRoundMedia.prepare(Facts.source,new File(directory,"outputs"){public long getUsableSpace(){return Facts.mode.equals("space")?0:Long.MAX_VALUE;}},circles,Facts.work);return null;}catch(Exception e){return e;}}
 static void fail(String mode,String stage,String message)throws Exception{reset(mode);Exception failure=prepare(true);check(failure!=null,"failure preserved "+mode);check(lines().contains("step="+stage),"failure stage "+stage);if(message!=null)check(message.equals(failure.getMessage()),"original reason preserved "+mode);check(Facts.source.exists()||mode.equals("source_digest"),"no original cleanup "+mode);int count=Facts.parts.size();Facts.work.discard();check(Arrays.stream(new File(directory,"outputs").listFiles()).noneMatch(f->f.getName().startsWith("purple-long-")),"same explicit copy cleanup "+count);privacy();}
 static void coverageFacts(int part)throws Exception{reset("coverage");long[] v=Facts.vfr(part);Facts.videoPts=Arrays.copyOf(v,v.length-1);Facts.videoEnd=part==0?34763333:34654333;Facts.audioEnd=34772833;Facts.coverageDuration=34773000;Facts.audioPts=new long[1630];for(int i=0;i<1630;i++)Facts.audioPts[i]=i*1024L*1000000/48000;}
 static void inspectCoverage(boolean accepted,String label)throws Exception{try{PurpleLongRoundMedia.inspect(Facts.source,true);check(accepted,label+" unexpectedly accepted");}catch(IOException failure){check(!accepted,label+" unexpectedly rejected "+failure.getMessage());}privacy();}
 static void coverage()throws Exception{
  long[] nativeEnd=Facts.vfr(1);check(nativeEnd[nativeEnd.length-1]==34654377,"complete native Track models final interval, format port uses observed end 44us earlier");
  coverageFacts(1);check(Facts.videoPts.length==293&&Facts.videoPts[292]==34537200&&Facts.videoEnd==34654333,"observed second-part count, PTS and declared end");inspectCoverage(true,"observed second part final-frame end");
  coverageFacts(0);check(Facts.videoPts.length==296&&Facts.videoPts[295]==34645888&&Facts.videoEnd==34763333,"observed first-part count, PTS and declared end");inspectCoverage(true,"observed first part unchanged");
  coverageFacts(1);Facts.videoPts=new long[11];for(int i=0;i<11;i++)Facts.videoPts[i]=i*100000L;Facts.videoEnd=1100000;Facts.coverageDuration=1300000;Facts.audioEnd=1300000;Facts.audioPts=new long[]{0,100000,1200000};inspectCoverage(true,"exact 200ms end tolerance");
  Facts.coverageDuration=1301000;inspectCoverage(false,"one millisecond beyond unchanged end tolerance");
  coverageFacts(1);Facts.coverageDuration=34900000;inspectCoverage(false,"true missing tail beyond 200ms");
  coverageFacts(1);Facts.videoEnd=34537200;inspectCoverage(false,"declared track ends at sample start");
  coverageFacts(1);Facts.videoEnd=1;inspectCoverage(false,"track duration precedes last sample");
  coverageFacts(1);Facts.videoEnd=-1;inspectCoverage(false,"absent track duration cannot justify extension");
  coverageFacts(1);Facts.videoEnd=Long.MAX_VALUE;inspectCoverage(false,"unbounded metadata cannot justify extension");
  coverageFacts(1);Facts.videoPts=new long[]{0,34000000};inspectCoverage(false,"large hole cannot manufacture a final interval");
  coverageFacts(1);Facts.videoPts=new long[]{0};inspectCoverage(false,"single sample cannot manufacture cadence");
  coverageFacts(1);Facts.videoPts=new long[]{0,0,0};inspectCoverage(false,"duplicate-only samples cannot manufacture cadence");
  coverageFacts(1);Facts.videoPts=new long[]{};inspectCoverage(false,"empty video");
  coverageFacts(1);Facts.videoPts[0]=100001;inspectCoverage(false,"start gap unchanged");
  coverageFacts(1);Facts.sync=false;inspectCoverage(false,"non-sync first video unchanged");
  coverageFacts(1);Facts.videoPts[2]=1;inspectCoverage(false,"nonmonotonic video unchanged");
  coverageFacts(1);Facts.audioPts=new long[]{0,34000000};inspectCoverage(false,"missing audio tail unchanged");
  coverageFacts(1);Facts.audioEnd=1;inspectCoverage(false,"inconsistent audio duration");
  coverageFacts(1);Facts.coverageDuration=60001000;inspectCoverage(false,"60 second ceiling unchanged");
  coverageFacts(1);Facts.videoPts=new long[100001];inspectCoverage(false,"sample-count ceiling unchanged");
  coverageFacts(1);Facts.videoPts=new long[]{0,60000001};inspectCoverage(false,"sample timestamp ceiling unchanged");
  coverageFacts(1);Facts.audioPts=new long[]{0,200000,1};inspectCoverage(false,"nonmonotonic audio unchanged");
 }
 public static void main(String[] a)throws Exception{directory=new File(a[0]);check(directory.mkdir(),"exclusive fixture");check(new File(directory,"outputs").mkdir(),"outputs");
  if(a.length>1){coverage();System.out.println("LONG_OUTPUT_DIAGNOSTIC_CHECKS="+checks);return;}
  reset("ok");List<PurpleLongRoundMedia.Output> outputs=PurpleLongRoundMedia.prepare(Facts.source,new File(directory,"outputs"){public long getUsableSpace(){return Long.MAX_VALUE;}},true,Facts.work);check(outputs.size()==2&&outputs.get(0).circle,"success return preserved");check(lines().contains("long_convert step=conversion part=1 error=0"),"false native result explicit");Facts.work.discard();privacy();
  for(String[] c:new String[][]{{"source_inspect","metadata",null},{"source_digest","source_digest",null},{"space","space","space"},{"conversion_setup","conversion_setup",null},{"conversion","conversion","conversion"},{"audio_release","audio_release",null},{"work_check","work_check","cancelled"},{"part_inspect","metadata",null},{"metadata_release","metadata_release",null},{"extractor_open","extractor_open",null},{"extractor_release","extractor_release",null},{"track_format","track_format",null},{"track_unselect","track_unselect",null},{"format","metadata","format"},{"audio_format","track_format","audio format"},{"tracks","track_format","tracks"},{"start","track_scan","start"},{"sync","track_scan","start"},{"timestamps","track_scan","timestamps"},{"missing_samples","track_scan","missing samples"},{"duration","inspect_duration","duration"},{"output_audio","output_audio","output audio"},{"part_duration","part_duration","part duration"},{"final_digest","final_digest","source changed"}})fail(c[0],c[1],c[2]);
  reset("work_check");check(prepare(true)!=null&&lines().contains("cancelled=1 expired=0"),"work cancellation flags are exact");Facts.work.discard();
  reset("ok");File empty=new File(directory,"empty.mp4");check(empty.createNewFile(),"empty media fixture");try{PurpleLongRoundMedia.inspect(empty,true);throw new AssertionError("empty admitted");}catch(IOException expected){check(expected.getMessage().equals("media")&&lines().contains("reason=media"),"media rejection is exact");}privacy();
  reset("conversion");Exception failure=prepare(true);check(lines().contains("long_convert step=conversion part=0 error=1"),"true native result explicit");check(PurpleLongRoundAudioInput.released>0,"finally still executes");Facts.work.discard();
  reset("audio_release");check(prepare(true)==Facts.runtime,"release throwable identity preserved");Facts.work.discard();reset("source_inspect");check(prepare(true)==Facts.io,"inspector throwable identity preserved");
  reset("vfr_bad");failure=prepare(true);check(failure!=null&&failure.getMessage().equals("missing samples"),"real VFR missing tail remains rejected");String row=PurpleDiagLog.lines.stream().filter(l->l.contains("reason=missing_samples")).findFirst().orElseThrow();check(row.contains("duration_s=34 duration_ms=900 duration_us=0"),"actual global duration");check(row.contains("last_s=34 last_ms=537 last_us=200")&&row.contains("count=293")&&row.contains("sync=1 monotonic=1"),"actual variable-rate sample facts");Facts.work.discard();privacy();
  reset("vfr_ok");check(prepare(true)==null,"same VFR timestamps pass original bound with covered duration");Facts.work.discard();
  reset("ok");check(prepare(true)==null,"calibrate final check");int finalCall=android.os.SystemClock.calls;Facts.work.discard();reset("ok");android.os.SystemClock.failAt=finalCall;failure=prepare(true);check(failure!=null&&lines().contains("step=final_check"),"final work check distinguished");Facts.work.discard();
  reset("ok");PurpleDiagLog.fail=true;check(prepare(true)==null,"diagnostic failure cannot change success");Facts.work.discard();reset("conversion");PurpleDiagLog.fail=true;check(prepare(true).getMessage().equals("conversion"),"diagnostic failure cannot replace original failure");Facts.work.discard();
  for(String mode:new String[]{"copy","copy_digest"}){reset(mode);Exception copyFailure=prepare(false);check(copyFailure!=null&&lines().contains("step="+mode),"ordinary failure stage "+mode);Facts.work.discard();privacy();}
  reset("ok");check(prepare(false)==null&&Facts.calls==0,"whole ordinary copy still bypasses converter");Facts.work.discard();
  reset("ok");try{PurpleLongRoundMedia.prepare(Facts.source,directory,true,1000000,1000001,Facts.work);throw new AssertionError("invalid trim admitted");}catch(PurpleLongRoundSelection.Invalid expected){check(lines().contains("step=selection"),"invalid trim stays an invalid selection");}
  reset("ok");check(ActualRequestCatch.run(new PurpleLongRoundSelection.Invalid()),"actual UI catch retains invalid-selection flag");check(!ActualRequestCatch.run(Facts.io),"actual UI catch retains ordinary fallback flag");check(lines().contains("step=request")&&lines().contains("exception=io"),"actual UI catch emits typed stage/class");privacy();
  PurpleDiagLog.lines.clear();PurpleLongRoundDiagnostics.range(0,Long.MAX_VALUE,Long.MIN_VALUE);String bounded=lines();check(bounded.contains("start_s=306 start_ms=0 start_us=0")&&bounded.contains("end_s=neg"),"time values saturate before formatting");check(!bounded.contains(Long.toString(Long.MAX_VALUE)),"no arbitrary long logger");
  coverage();
  System.out.println("LONG_OUTPUT_DIAGNOSTIC_CHECKS="+checks);
 }
}''')
    (output/'production-inputs.json').write_text(json.dumps(copied,indent=2)+'\n')
    compiler=Path(os.environ['JAVA_HOME'])/'bin/javac'
    command=[str(compiler),'-d',str(output/'classes')]+[str(p)for p in output.rglob('*.java')]
    if compile_wrapper:
        import shlex
        command=shlex.split(compile_wrapper)+command
    subprocess.run(command,check=True)
    subprocess.run([str(compiler.with_name('java')),'-cp',str(output/'classes'),'org.telegram.messenger.purple.LongDiagnosticCases',str(output/'fixtures')]+([case] if case else []),check=True)
