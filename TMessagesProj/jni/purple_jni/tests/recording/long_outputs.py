"""Run production long-output preparation with FFmpeg codec and inert Android/queue ports."""
import pathlib, os, subprocess, json

def run(root, output):
    output.mkdir(parents=True, exist_ok=False)
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    def put(name, text):
        p = output / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    for name in ['PurpleLongRoundPlan', 'PurpleLongRoundMedia', 'PurpleLongRoundQueue', 'PurpleLongRoundAudioInput', 'PurpleRecordingFiles']:
        put('org/telegram/messenger/purple/' + name + '.java', (java / ('messenger/purple/' + name + '.java')).read_text())
    put('org/telegram/messenger/purple/PurpleDiagLog.java', 'package org.telegram.messenger.purple; public class PurpleDiagLog {public static void event(String... x){} public static String reason(String x){return x;}}')
    put('android/os/SystemClock.java', 'package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}')
    put('org/telegram/messenger/VideoEditedInfo.java', '''package org.telegram.messenger;
      public class VideoEditedInfo {public long startTime,endTime,estimatedSize,estimatedDuration;public boolean roundVideo;
       public int resultWidth,originalWidth,resultHeight,originalHeight,rotationValue,framerate;public String originalPath;
       public boolean needConvert(){return true;}}''')
    put('org/telegram/messenger/MediaController.java', '''package org.telegram.messenger;
      public class MediaController {public interface VideoConvertorListener{boolean checkConversionCanceled();void didWriteData(long size,float progress);}}''')
    put('android/media/MediaFormat.java', '''package android.media; import java.util.*;
      public class MediaFormat {public static final String KEY_MIME="mime",KEY_DURATION="duration";Map<String,Object> m=new HashMap<>();
       public String getString(String k){return (String)m.get(k);}public long getLong(String k){return (Long)m.get(k);}public boolean containsKey(String k){return m.containsKey(k);}}''')
    put('android/media/Probe.java', '''package android.media; import java.util.*;
      public class Probe {public static String exec(String... args)throws Exception{Process p=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.INHERIT).start();String s=new String(p.getInputStream().readAllBytes());if(p.waitFor()!=0)throw new Exception("media tool failed");return s.trim();}
       static Map<String,String> facts(String path,String stream)throws Exception{Map<String,String> m=new HashMap<>();String s=exec("ffprobe","-v","error","-select_streams",stream,"-show_entries","stream=width,height,duration,codec_name","-of","default=noprint_wrappers=1",path);for(String l:s.split("\\n")){String[] pair=l.split("=",2);if(pair.length==2)m.put(pair[0],pair[1]);}return m;}
       static long micros(String seconds){return (long)Math.ceil(Double.parseDouble(seconds)*1000000);}}
    ''')
    put('android/media/MediaMetadataRetriever.java', '''package android.media; import java.util.*;
      public class MediaMetadataRetriever {public static final int METADATA_KEY_DURATION=1,METADATA_KEY_VIDEO_WIDTH=2,METADATA_KEY_VIDEO_HEIGHT=3,METADATA_KEY_VIDEO_ROTATION=4;Map<String,String> facts;String path;
       public void setDataSource(String path)throws Exception{this.path=path;facts=Probe.facts(path,"v:0");}
       public String extractMetadata(int key){try{if(key==1)return Long.toString((Probe.micros(Probe.exec("ffprobe","-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",path))+999)/1000);if(key==2)return facts.get("width");if(key==3)return facts.get("height");return "0";}catch(Exception e){throw new RuntimeException(e);}}
       public void release(){}}
    ''')
    put('android/media/MediaExtractor.java', '''package android.media; import java.util.*;
      public class MediaExtractor {public static final int SEEK_TO_PREVIOUS_SYNC=0,SAMPLE_FLAG_SYNC=1;String path;int track,pos;List<String> packets;
       public void setDataSource(String path){this.path=path;}public int getTrackCount(){return 2;}
       public MediaFormat getTrackFormat(int i){try{Map<String,String> f=Probe.facts(path,i==0?"v:0":"a:0");MediaFormat m=new MediaFormat();m.m.put("mime",i==0?"video/avc":"audio/mp4a-latm");m.m.put("duration",Probe.micros(f.get("duration")));return m;}catch(Exception e){throw new RuntimeException(e);}}
       public void selectTrack(int track){this.track=track;}public void unselectTrack(int i){packets=null;}
       public void seekTo(long time,int mode){try{String all=Probe.exec("ffprobe","-v","error","-select_streams",track==0?"v:0":"a:0","-show_packets","-show_entries","packet=pts_time,flags","-of","csv=p=0",path);packets=new ArrayList<>();for(String l:all.split("\\n")){if(l.contains(",")&&!l.startsWith("-")&&!l.startsWith("Skip"))packets.add(l);}pos=0;}catch(Exception e){throw new RuntimeException(e);}}
       public long getSampleTime(){return pos>=packets.size()?-1:Probe.micros(packets.get(pos).split(",")[0]);}
       public int getSampleFlags(){return packets.get(pos).contains("K")?1:0;}public boolean advance(){return ++pos<packets.size();}public void release(){}}
    ''')
    for name in ['AudioInput']:
        put('org/telegram/messenger/video/audio_input/' + name + '.java', (java/('messenger/video/audio_input/'+name+'.java')).read_text())
    put('org/telegram/messenger/video/AudioConversions.java', '''package org.telegram.messenger.video;
      public class AudioConversions {public static long bytesToUs(int size,int rate,int channels){return size*1000000L/(rate*channels*2);}}''')
    put('org/telegram/messenger/video/AudioBufferConverter.java', '''package org.telegram.messenger.video; import java.nio.*;
      public class AudioBufferConverter {public ShortBuffer convert(ShortBuffer b,int a,int c,int d,int e){ShortBuffer out=ShortBuffer.allocate(b.remaining());out.put(b).flip();return out;}}''')
    put('org/telegram/messenger/video/AudioDecoder.java', '''package org.telegram.messenger.video; import java.nio.*; import java.io.*;
      public class AudioDecoder {public static int released;int index;long start,end;
       public AudioDecoder(String p,int t)throws IOException{}public void setStartTimeUs(long v){start=v;}public void setEndTimeUs(long v){end=v;}
       public int getSampleRate(){return 1000;}public int getChannelCount(){return 1;}public int getBitrateRate(){return 16000;}public void start(){}public void release(){released++;}
       public DecodedBufferData decode(){DecodedBufferData d=new DecodedBufferData();if(index==3)return d;d.index=index;d.presentationTimeUs=index*10000;d.size=20;d.byteBuffer=ByteBuffer.allocate(20);for(int i=0;i<10;i++)d.byteBuffer.putShort((short)(index*10+i));d.byteBuffer.flip();index++;return d;}
       public void releaseOutputBuffer(int i){}public static class DecodedBufferData{public int index=-1,size;public long presentationTimeUs;public ByteBuffer byteBuffer;}}
    ''')
    put('org/telegram/messenger/video/MediaCodecVideoConvertor.java', '''package org.telegram.messenger.video;
      import java.io.*;import java.util.*;import org.telegram.messenger.*;import org.telegram.messenger.video.audio_input.*;import android.media.Probe;
      public class MediaCodecVideoConvertor {public static int calls,failAt=-1;public static Runnable hook;
       public boolean convertVideo(ConvertVideoParams p){calls++;try{if(hook!=null)hook.run();if(p.cb.checkConversionCanceled()||calls==failAt)return true;
        if(!p.compress||p.purpleAudioInput==null||p.purpleAudioDurationUs!=p.end-p.start||p.duration!=(p.end-p.start+999)/1000)throw new AssertionError("production conversion contract");
        Probe.exec("ffmpeg","-v","error","-y","-i",p.path,"-ss",Double.toString(p.start/1000000.0),"-t",Double.toString((p.end-p.start)/1000000.0),"-c:v","libx264","-preset","ultrafast","-profile:v","baseline","-bf","0","-r","30","-pix_fmt","yuv420p","-c:a","aac","-b:a","128k","-movflags","+faststart",p.file.toString());return p.cb.checkConversionCanceled();
       }catch(Exception e){throw new RuntimeException(e);}}
       public static class ConvertVideoParams {public AudioInput purpleAudioInput;public long purpleAudioDurationUs,start,end,duration;String path;File file;boolean compress;MediaController.VideoConvertorListener cb;
        public static ConvertVideoParams of(String path,File file,long offset,int rotation,boolean secret,int w,int h,int rw,int rh,int fps,int bitrate,int original,long start,long end,long avatar,boolean compress,long duration,MediaController.VideoConvertorListener cb,VideoEditedInfo info){ConvertVideoParams p=new ConvertVideoParams();p.path=path;p.file=file;p.start=start;p.end=end;p.duration=duration;p.compress=compress;p.cb=cb;return p;}}}
    ''')
    put('LongOutputCases.java', '''import java.io.*;import java.nio.file.*;import java.util.*;import org.telegram.messenger.purple.*;import org.telegram.messenger.video.*;
      public class LongOutputCases {static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
       static byte[] hash(File f)throws Exception{return java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));}
       public static void main(String[] args)throws Exception{
        File source=new File(args[0]),directory=new File(args[1]);byte[] original=hash(source);
        for(long duration:new long[]{60001,60100,75000,119001,180000,300000,305000}){PurpleLongRoundPlan p=new PurpleLongRoundPlan(duration);long end=0;for(int i=0;i<p.count;i++){check(p.startUs(i)==end,"exact consecutive boundary");end=p.endUs(i);check(end-p.startUs(i)<=59500000,"conservative part bound");p.validatePart(i,(end-p.startUs(i)+999)/1000);}check(end==duration*1000,"full source coverage");}
        for(long bad:new long[]{-1,0,60000,305001,Long.MAX_VALUE}){try{new PurpleLongRoundPlan(bad);throw new AssertionError("bad plan admitted");}catch(IllegalArgumentException expected){checks++;}}
        PurpleLongRoundQueue q=new PurpleLongRoundQueue(3);check(q.begin()==-1,"no unreviewed send");check(q.confirm()&&!q.confirm(),"one approval");check(q.begin()==0&&q.begin()==-1,"only one pending part");check(q.acknowledge(0)&&!q.acknowledge(0),"exactly once acknowledgement");check(q.begin()==1,"ordered next part");q.stop();check(!q.acknowledge(1)&&q.begin()==-1&&q.queued==1,"uncertain failure stops without blind retry");
        PurpleLongRoundAudioInput audio=new PurpleLongRoundAudioInput("inert",0);audio.setStartTimeUs(10000);audio.setEndTimeUs(30000);audio.start(1000,1);int count=0;while(audio.hasRemaining()){check(audio.getNext()==10+count,"prior sync PCM excluded");count++;}check(count==20,"audio boundary coverage");audio.release();audio.release();audio.start(1000,1);check(audio.hasRemaining()&&audio.getNext()==10,"codec retry reconstructs audio decoder");audio.release();
        PurpleLongRoundMedia.Work ordinary=new PurpleLongRoundMedia.Work();List<PurpleLongRoundMedia.Output> copy=PurpleLongRoundMedia.prepare(source,directory,false,ordinary);check(copy.size()==1&&!copy.get(0).info().roundVideo&&!copy.get(0).info().needConvert(),"ordinary full video with honest flag and no reconversion");check(Arrays.equals(hash(copy.get(0).file),original),"ordinary preserves all original bytes");ordinary.discard();
        PurpleLongRoundMedia.Work circles=new PurpleLongRoundMedia.Work();List<PurpleLongRoundMedia.Output> parts=PurpleLongRoundMedia.prepare(source,directory,true,circles);check(parts.size()==new PurpleLongRoundPlan(PurpleLongRoundMedia.inspect(source,false).durationMs).count,"all planned parts prepared");for(PurpleLongRoundMedia.Output part:parts){check(part.circle&&part.info().roundVideo&&!part.info().needConvert()&&part.facts.durationMs<=60000,"honest independently prepared circle");System.out.println("DERIVED_PART="+part.file);}
        PurpleLongRoundMedia.Work cancelled=new PurpleLongRoundMedia.Work();cancelled.cancelled.set(true);try{PurpleLongRoundMedia.prepare(source,directory,true,cancelled);throw new AssertionError("cancel ignored");}catch(IOException expected){checks++;}cancelled.discard();
        MediaCodecVideoConvertor.failAt=MediaCodecVideoConvertor.calls+2;PurpleLongRoundMedia.Work failed=new PurpleLongRoundMedia.Work();try{PurpleLongRoundMedia.prepare(source,directory,true,failed);throw new AssertionError("failure ignored");}catch(IOException expected){checks++;}failed.discard();MediaCodecVideoConvertor.failAt=-1;
        PurpleLongRoundMedia.Work interrupted=new PurpleLongRoundMedia.Work();MediaCodecVideoConvertor.hook=()->interrupted.cancelled.set(true);try{PurpleLongRoundMedia.prepare(source,directory,true,interrupted);throw new AssertionError("mid encode cancel ignored");}catch(IOException expected){checks++;}interrupted.discard();MediaCodecVideoConvertor.hook=null;
        check(source.isFile()&&Arrays.equals(original,hash(source)),"full original survives success cancel failure and retry");System.out.println("LONG_ROUND_OUTPUT_CHECKS="+checks);
       }}''')
    compiler=pathlib.Path(os.environ['JAVA_HOME'])/'bin/javac'
    subprocess.run([str(compiler),'-d',str(output/'classes'),*map(str,output.rglob('*.java'))],check=True)
    source=output/'source-179967.mp4'
    subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','testsrc2=size=160x160:rate=30','-f','lavfi','-i','sine=frequency=997:sample_rate=48000','-t','179.967','-c:v','libx264','-preset','ultrafast','-profile:v','baseline','-bf','0','-pix_fmt','yuv420p','-c:a','aac','-b:a','128k',str(source)],check=True)
    derived=output/'derived';derived.mkdir()
    result=subprocess.run([str(compiler.with_name('java')),'-cp',str(output/'classes'),'LongOutputCases',str(source),str(derived)],check=True,text=True,stdout=subprocess.PIPE)
    print(result.stdout,end='')
    evidence=[]
    for line in result.stdout.splitlines():
        if not line.startswith('DERIVED_PART='):continue
        part=pathlib.Path(line.split('=',1)[1])
        data=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(part)],text=True))
        assert float(data['format']['duration'])<=60
        assert {s['codec_name'] for s in data['streams']}=={'h264','aac'}
        subprocess.run(['ffmpeg','-v','error','-xerror','-i',str(part),'-f','null','-'],check=True)
        evidence.append({'file':part.name,'duration':data['format']['duration'],'streams':[{k:s.get(k) for k in ['codec_name','duration','start_time','nb_frames']} for s in data['streams']]})
    (output/'decoded-parts.json').write_text(json.dumps(evidence,indent=2)+'\n')
    print('LONG_ROUND_DECODE_CHECKS='+str(len(evidence)))
