"""Exercise native audio initialization, clean EOS and part validation over stateful codec ports.

The complete decoder/recorder and PCM conversion bodies execute here. Video/GL,
AAC bytes and Android formats remain ports; no Android output is decoded.
"""
from pathlib import Path
import hashlib, json, os, subprocess, sys

def body(text, signature):
    start=text.index(signature);opening=text.index('{',start);depth=1;pos=opening+1
    while depth:
        depth += (text[pos]=='{')-(text[pos]=='}');pos+=1
    return text[start:pos]


def run(root, output, compile_wrapper=None, case=None):
    s=root;o=output;o.mkdir(parents=True,exist_ok=False)
    j=s/'TMessagesProj/src/main/java'
    packets=[{'pts_time':str((i*1024/48000)+(i//200)*.005),'stream_index':0} for i in range(3237)]
    facts={'streams':[{'index':0,'codec_name':'aac','duration':'69.135000'},{'index':1,'codec_name':'h264','duration':'69.420000'}],
           'packets':packets+[{'pts_time':str(i/30),'stream_index':1} for i in range(2083)]}
    audio=facts['streams'][0]
    (o/'synthetic-source-facts.json').write_text(json.dumps(facts,indent=2)+'\n')
    def put(path, text):
        p=o/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    copied={}
    names=['video/AudioDecoder.java','video/AudioRecoder.java','video/AudioConversions.java','video/AudioBufferConverter.java','video/audio_input/AudioInput.java','video/audio_input/BlankAudioInput.java','video/audio_input/GeneralAudioInput.java','purple/PurpleLongRoundAudioInput.java']
    names += ['purple/PurpleLongRoundMedia.java','purple/PurpleLongRoundPlan.java','purple/PurpleLongRoundSelection.java','purple/PurpleRecordingFiles.java','video/Sample.java']
    names += [str(p.relative_to(j/'org/telegram/messenger')) for folder in ['remix','resample'] for p in (j/'org/telegram/messenger/video'/folder).glob('*.java')]
    for name in names:
        p=j/'org/telegram/messenger'/name
        put('org/telegram/messenger/'+name,p.read_text());copied[str(p)]=hashlib.sha256(p.read_bytes()).hexdigest()
    put('android/annotation/TargetApi.java','package android.annotation; public @interface TargetApi {int value();}')
    put('androidx/annotation/NonNull.java','package androidx.annotation; public @interface NonNull {}')
    for name in ['android/content/Context','android/content/res/AssetFileDescriptor','android/media/MediaDataSource','android/net/Uri']:
        package,typ=name.rsplit('/',1);put(name+'.java','package '+package.replace('/','.')+'; public class '+typ+' {}')
    put('android/os/Build.java','package android.os; public class Build {public static class VERSION {public static int SDK_INT=36;}}')
    put('android/os/SystemClock.java','package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}')
    put('android/util/Log.java','package android.util; public class Log {public static int d(String a,String b){return 0;} public static int e(String a,String b){return 0;}public static int w(String a,String b){return 0;}}')
    put('org/telegram/messenger/FileLog.java','''package org.telegram.messenger; public class FileLog {
     public static int caughtDuration;public static void e(Throwable t){if(t instanceof NullPointerException)caughtDuration++;else throw new AssertionError(t);}public static void e(String s){throw new AssertionError(s);}}''')
    put('org/telegram/messenger/MediaController.java','package org.telegram.messenger; public class MediaController {public static final String AUDIO_MIME_TYPE="audio/mp4a-latm"; public interface VideoConvertorListener {boolean checkConversionCanceled();void didWriteData(long size,float progress);}}')
    put('org/telegram/messenger/VideoEditedInfo.java','''package org.telegram.messenger; public class VideoEditedInfo {public long startTime,endTime,estimatedSize,estimatedDuration;public int resultWidth,originalWidth,resultHeight,originalHeight,rotationValue,framerate;public boolean roundVideo,muted;public String originalPath;public boolean needConvert(){return true;}}''')
    for diagnostic in ['PurpleLongRoundDiagnostics','PurpleDiagLogStore']:
        put('org/telegram/messenger/purple/'+diagnostic+'.java', (root/'TMessagesProj/src/main/java/org/telegram/messenger/purple'/(diagnostic+'.java')).read_text())
    put('org/telegram/messenger/purple/PurpleDiagLog.java','package org.telegram.messenger.purple;public class PurpleDiagLog{public static PurpleDiagLogStore.Field reason(String s){return PurpleDiagLogStore.reason(s);}public static PurpleDiagLogStore.Field code(String k,String v){return PurpleDiagLogStore.code(k,v);}public static PurpleDiagLogStore.Field count(String k,int v){return PurpleDiagLogStore.count(k,v);}public static PurpleDiagLogStore.Field flag(String k,boolean v){return PurpleDiagLogStore.flag(k,v);}public static void event(String a,String b,PurpleDiagLogStore.Field...c){}}')
    put('android/media/MediaFormat.java','''package android.media;import java.util.*;
     public class MediaFormat {public static final String KEY_MIME="mime",KEY_DURATION="durationUs",KEY_SAMPLE_RATE="sample-rate",KEY_CHANNEL_COUNT="channel-count",KEY_BIT_RATE="bitrate",KEY_MAX_INPUT_SIZE="max-input-size";
     final Map<String,Object> data=new HashMap<>();public void setInteger(String k,int v){data.put(k,v);}public void setLong(String k,long v){data.put(k,v);}public void setString(String k,String v){data.put(k,v);}
     public long getLong(String k){return (Long)data.get(k);}public int getInteger(String k){return (Integer)data.get(k);}public String getString(String k){return (String)data.get(k);}public boolean containsKey(String k){return data.containsKey(k);}
     public static MediaFormat createAudioFormat(String mime,int rate,int channels){MediaFormat f=new MediaFormat();f.setString(KEY_MIME,mime);f.setInteger(KEY_SAMPLE_RATE,rate);f.setInteger(KEY_CHANNEL_COUNT,channels);return f;}}''')
    times=','.join(str(round(float(p['pts_time'])*1_000_000))+'L' for p in packets)
    video=next(x for x in facts['streams'] if x['codec_name']=='h264')
    videoTimes=','.join(str(round(float(p['pts_time'])*1_000_000))+'L' for p in facts['packets'] if p['stream_index']==video['index'])
    put('android/media/MediaExtractor.java','''package android.media;import java.nio.*;import java.io.*;
     public class MediaExtractor {public static final int SEEK_TO_PREVIOUS_SYNC=0,SAMPLE_FLAG_SYNC=1;int pos,track;boolean derived;static final long[] PTS={'''+times+'''};public static final long[] VIDEO={'''+videoTimes+'''};
     public static long[][] derivedPts;public static long[] derivedDuration;
     public void setDataSource(String p)throws IOException{derived=p.endsWith("derived.mp4");}public int getTrackCount(){return derived?2:1;}public void selectTrack(int t){track=t;}public void unselectTrack(int t){}public void release(){}
     public MediaFormat getTrackFormat(int t){MediaFormat f=MediaFormat.createAudioFormat(t==1?"video/avc":"audio/mp4a-latm",48000,1);f.setLong(MediaFormat.KEY_DURATION,derived?derivedDuration[t]:'''+str(round(float(audio['duration'])*1e6))+'''L);return f;}
     public void seekTo(long t,int mode){pos=0;while(pos+1<PTS.length&&PTS[pos+1]<=t)pos++;}
     public int readSampleData(ByteBuffer b,int offset){if(pos==PTS.length)return -1;b.clear();b.putInt(1024);b.flip();return 4;}
     public long getSampleTime(){long[] pts=derived?derivedPts[track]:PTS;return pos<pts.length?pts[pos]:-1;}public int getSampleFlags(){return 1;}public boolean advance(){return ++pos<(derived?derivedPts[track]:PTS).length;}}''')
    put('android/media/MediaMetadataRetriever.java','''package android.media;public class MediaMetadataRetriever {public static final int METADATA_KEY_DURATION=1,METADATA_KEY_VIDEO_WIDTH=2,METADATA_KEY_VIDEO_HEIGHT=3,METADATA_KEY_VIDEO_ROTATION=4;public void setDataSource(String p){}public String extractMetadata(int key){return key==1?Long.toString((Math.max(MediaExtractor.derivedDuration[0],MediaExtractor.derivedDuration[1])+999)/1000):key==4?"0":"384";}public void release(){}}''')
    put('android/media/MediaCodec.java','''package android.media;import java.nio.*;import java.io.*;import java.util.*;
     public class MediaCodec {
     public static final int CONFIGURE_FLAG_ENCODE=1,BUFFER_FLAG_KEY_FRAME=1,BUFFER_FLAG_SYNC_FRAME=1,BUFFER_FLAG_CODEC_CONFIG=2,BUFFER_FLAG_END_OF_STREAM=4,INFO_TRY_AGAIN_LATER=-1,INFO_OUTPUT_FORMAT_CHANGED=-2,INFO_OUTPUT_BUFFERS_CHANGED=-3;
     public static long pcmShorts,nonzeroShorts,zeroShorts;public static int inputEos,outputEos,decoders;public static long lastEncodedPts;public static boolean failDecode,failCreate,starve,cancel;int starved;
     boolean encode,started;int accumulated;long encodedFrames;MediaFormat configured;ByteBuffer input=ByteBuffer.allocate(32768);ByteBuffer output=ByteBuffer.allocate(32768);final ArrayDeque<Frame> q=new ArrayDeque<>();Frame pending;
     record Frame(ByteBuffer data,long pts,int flags){}public static class BufferInfo{public int offset,size,flags;public long presentationTimeUs;}
     public static MediaCodec createEncoderByType(String s)throws IOException{MediaCodec c=new MediaCodec();c.encode=true;return c;}public static MediaCodec createDecoderByType(String s)throws IOException{decoders++;if(failCreate)throw new IOException("decoder creation sentinel");return new MediaCodec();}
     public void configure(MediaFormat f,Object a,Object b,int flags){configured=f;if(encode!=(flags==1))throw new AssertionError();}public void start(){started=true;}public void stop(){started=false;}public void release(){}public void flush(){q.clear();}
     public MediaFormat getOutputFormat(){return MediaFormat.createAudioFormat(encode?"audio/mp4a-latm":"audio/raw",configured.getInteger(MediaFormat.KEY_SAMPLE_RATE),configured.getInteger(MediaFormat.KEY_CHANNEL_COUNT));}
     public ByteBuffer[] getInputBuffers(){return new ByteBuffer[]{input};}public ByteBuffer[] getOutputBuffers(){return new ByteBuffer[]{output};}public ByteBuffer getInputBuffer(int i){input.clear();return input;}public ByteBuffer getOutputBuffer(int i){return output;}
     public int dequeueInputBuffer(long t){if(!started)throw new AssertionError("not started");return 0;}
     public void queueInputBuffer(int i,int off,int size,long pts,int flags){
      ByteBuffer bytes;
      if(encode){pcmShorts+=size/2;for(int k=0;k<size;k+=2){short value=input.getShort(k);if(value==0)zeroShorts++;else{if(value!=1234)throw new AssertionError("PCM changed");nonzeroShorts++;}}accumulated+=size/4;while(accumulated>=1024){q.add(new Frame(ByteBuffer.allocate(16),encodedFrames*1000000L/48000,1));encodedFrames+=1024;accumulated-=1024;}if((flags&4)!=0){inputEos++;if(accumulated>0){q.add(new Frame(ByteBuffer.allocate(16),encodedFrames*1000000L/48000,1));encodedFrames+=1024;accumulated=0;}q.add(new Frame(ByteBuffer.allocate(0),encodedFrames*1000000L/48000,4));}return;}
      else {int n=size==0?0:input.getInt(0);bytes=ByteBuffer.allocate(n*2);while(bytes.hasRemaining())bytes.putShort((short)1234);bytes.flip();}
      q.add(new Frame(bytes,pts,flags));}
     public int dequeueOutputBuffer(BufferInfo info,long t){if(!encode&&cancel)throw new java.util.concurrent.CancellationException("cancel sentinel");if(!encode&&starve){if(++starved>3)throw new IllegalStateException("codec timeout sentinel");return -1;}if(!encode&&failDecode)throw new IllegalStateException("decoder failure sentinel");if(pending!=null)throw new AssertionError("unreleased output");pending=q.poll();if(pending==null)return -1;output.clear();output.put(pending.data().duplicate()).flip();
      info.offset=0;info.size=output.remaining();info.presentationTimeUs=pending.pts();info.flags=pending.flags();if(encode){lastEncodedPts=Math.max(lastEncodedPts,info.presentationTimeUs);if((info.flags&4)!=0)outputEos++;}return 0;}
     public void releaseOutputBuffer(int i,boolean render){if(pending==null)throw new AssertionError();pending=null;}
     }''')
    native=(j/'org/telegram/messenger/video/MediaCodecVideoConvertor.java').read_text()
    anchor=native.index('MediaFormat audioFormat = extractor.getTrackFormat(audioIndex);')
    init_start=native.rfind('if (audioIndex >= 0) {',0,anchor)
    init_end=native.index('boolean audioEncoderDone',anchor)
    initialization=native[init_start:init_end]
    apply=body(native,'private static void applyAudioInputs(')
    put('org/telegram/messenger/video/MediaCodecVideoConvertor.java','''package org.telegram.messenger.video;
    import java.util.*;import java.io.*;import java.nio.*;import org.telegram.messenger.*;import org.telegram.messenger.video.audio_input.*;import org.telegram.messenger.purple.*;import android.media.*;
    public class MediaCodecVideoConvertor {
     public boolean convertVideo(ConvertVideoParams p){throw new AssertionError("GL/video conversion is outside this port");}
     public static class ConvertVideoParams {public AudioInput purpleAudioInput;public long purpleAudioDurationUs;public ArrayList<MixedSoundInfo> soundInfos=new ArrayList<>();public static ConvertVideoParams of(String p,File f,long off,int rot,boolean secret,int w,int h,int rw,int rh,int fps,int bit,int original,long start,long end,long avatar,boolean compress,long duration,MediaController.VideoConvertorListener cb,VideoEditedInfo info){return new ConvertVideoParams();}}
     public static class Muxer {public int addTrack(MediaFormat f,boolean a){return 0;}public ArrayList<Long> pts=new ArrayList<>();public long samples,lastPts;public long writeSampleData(int t,ByteBuffer b,MediaCodec.BufferInfo i,boolean length){if(i.size>0){samples++;lastPts=i.presentationTimeUs;pts.add(i.presentationTimeUs);}return 1;}}
     public static AudioRecoder initialize(AudioInput input,long startTime,long endTime,int audioIndex)throws Exception {
      ConvertVideoParams convertVideoParams=new ConvertVideoParams();convertVideoParams.purpleAudioInput=input;convertVideoParams.purpleAudioDurationUs=endTime-startTime;
      MediaExtractor extractor=new MediaExtractor();String videoPath="synthetic";extractor.setDataSource(videoPath);float volume=1f;long duration=(endTime-startTime)/1000;boolean copyAudioBuffer=true;int audioTrackIndex=-5,maxBufferSize=0;ByteBuffer audioBuffer=null;AudioRecoder audioRecoder=null;Muxer muxer=new Muxer();
     '''+initialization+'''
     return audioRecoder;
     }
     '''+apply+body(native,'public static class MixedSoundInfo')+'}')
    (o/'converter-audio-initialization.java.txt').write_text(initialization)
    track=(j/'org/telegram/messenger/video/Track.java').read_text()
    members=body(track,'private static class SamplePresentationTime')
    methods=body(track,'public void addSample(')+'\n'+body(track,'public void prepare()')
    put('org/telegram/messenger/video/NativeTrack.java','''package org.telegram.messenger.video;import java.util.*;import android.media.*;public class NativeTrack {
     private boolean isAudio;private int timeScale;private long duration;private ArrayList<Sample> samples=new ArrayList<>();private LinkedList<Integer> syncSamples=null;private ArrayList<SamplePresentationTime> samplePresentationTimes=new ArrayList<>();private long[] sampleDurations;private int[] sampleCompositions;
     '''+members+methods+'''
     public static long[] normalize(java.util.List<Long> pts,int rate){NativeTrack t=new NativeTrack();t.isAudio=true;t.timeScale=rate;for(long p:pts){MediaCodec.BufferInfo i=new MediaCodec.BufferInfo();i.size=16;i.presentationTimeUs=p;t.addSample(0,i);}t.prepare();long[] out=new long[t.sampleDurations.length+1];long time=0;for(int i=0;i<t.sampleDurations.length;i++){out[i]=time*1000000/rate;time+=t.sampleDurations[i];}out[out.length-1]=t.duration*1000000/rate;return out;}}
    ''')
    put('NativeLongAudioCases.java', '''import org.telegram.messenger.purple.*;
import org.telegram.messenger.video.*;
import org.telegram.messenger.video.audio_input.*;
import org.telegram.messenger.*;
import android.media.*;
import java.util.*;

public class NativeLongAudioCases {
    static int checks;
    static java.io.File directory;
    static void check(boolean okay, String what) {
        checks++;
        if (!okay) throw new AssertionError(what);
    }
    static PurpleLongRoundAudioInput range(long start, long end, PurpleLongRoundAudioInput reuse) throws Exception {
        int logged = FileLog.caughtDuration, eos = MediaCodec.outputEos, inputEos = MediaCodec.inputEos;
        long before = MediaCodec.pcmShorts, nonzero = MediaCodec.nonzeroShorts, zero = MediaCodec.zeroShorts;
        PurpleLongRoundAudioInput input = reuse == null ? new PurpleLongRoundAudioInput("synthetic", 0) : reuse;
        AudioRecoder recorder = MediaCodecVideoConvertor.initialize(input, start, end, 0);
        check(FileLog.caughtDuration - logged == 2, "two caught output-duration diagnostics use the real input-format fallback");
        check(recorder.format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48000, "native initializer retains source sample rate");
        check(recorder.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2, "native initializer retains encoder stereo policy");
        MediaCodecVideoConvertor.Muxer muxer = new MediaCodecVideoConvertor.Muxer();
        int steps = 0;
        while (!recorder.step(muxer, 0)) {
            if (++steps > 10000) throw new AssertionError("native step did not terminate");
        }
        check(muxer.samples > 0, "native audio samples are emitted after the caught diagnostics");
        check(MediaCodec.inputEos - inputEos == 1 && MediaCodec.outputEos - eos == 1, "one consumptive input/output EOS");
        ArrayList<Long> videoPts = new ArrayList<>();
        for (long p : MediaExtractor.VIDEO) if (p >= start && p < end) videoPts.add(p);
        long[] a = NativeTrack.normalize(muxer.pts, 48000), v = NativeTrack.normalize(videoPts, 90000);
        MediaExtractor.derivedPts = new long[][]{Arrays.copyOf(a, a.length - 1), Arrays.copyOf(v, v.length - 1)};
        MediaExtractor.derivedDuration = new long[]{a[a.length - 1], v[v.length - 1]};
        java.io.File derived = new java.io.File(directory, "derived.mp4");
        java.nio.file.Files.write(derived.toPath(), new byte[]{1});
        boolean accepted = false;
        try {
            PurpleLongRoundMedia.Facts facts = PurpleLongRoundMedia.inspect(derived, true);
            accepted = true;
            System.out.println("NATIVE_VALIDATION start=" + start + " PASS durationMs=" + facts.durationMs);
        } catch (java.io.IOException failure) {
            System.out.println("NATIVE_VALIDATION start=" + start + " REJECT reason=" + failure.getMessage() + " audioUs=" + a[a.length - 1] + " videoUs=" + v[v.length - 1]);
        }
        check(accepted, "unchanged native part validator requires complete audio coverage");
        check(MediaCodec.pcmShorts - before == AudioConversions.usToShorts(end - start, 48000, 2), "selected range has an exact bounded PCM sample budget");
        check(MediaCodec.nonzeroShorts - nonzero > AudioConversions.usToShorts(end - start - 500000, 48000, 2), "actual selected PCM survives instead of wholesale silence");
        check(start == 10000000 ? MediaCodec.zeroShorts == zero : MediaCodec.zeroShorts > zero, "fully covered trim stays PCM; only the uncovered EOS tail is filled");
        check(input.getStartTimeUs() == start && input.getEndTimeUs() == end, "selection is not widened to the source");
        check(!input.hasRemaining(), "no samples remain beyond the selected budget");
        recorder.release();
        input.release();
        input.release();
        check(!input.hasRemaining(), "release is idempotent and retires the sample budget");
        return input;
    }
    static AudioDecoder decoder(PurpleLongRoundAudioInput input) throws Exception {
        var field = PurpleLongRoundAudioInput.class.getDeclaredField("decoder");
        field.setAccessible(true);
        return (AudioDecoder) field.get(input);
    }
    static void uncleanEnd() throws Exception {
        PurpleLongRoundAudioInput input = new PurpleLongRoundAudioInput("synthetic", 0);
        AudioRecoder recorder = MediaCodecVideoConvertor.initialize(input, 0, 1000000, 0);
        decoder(input).stop();
        long zeros = MediaCodec.zeroShorts;
        boolean refused = false;
        try { input.hasRemaining(); }
        catch (IllegalStateException expected) { refused = expected.getMessage().contains("EOS"); }
        check(refused, "native stop without an observed EOS cannot synthesize silence");
        check(MediaCodec.zeroShorts == zeros, "unclean stop emits no padding");
        recorder.release(); input.release();
    }
    static void failure(boolean create) throws Exception {
        PurpleLongRoundAudioInput input = new PurpleLongRoundAudioInput("synthetic", 0);
        AudioRecoder recorder = null;
        long zero = MediaCodec.zeroShorts;
        boolean failed = false;
        try {
            MediaCodec.failCreate = create;
            recorder = MediaCodecVideoConvertor.initialize(input, 0, 1000000, 0);
            MediaCodec.failDecode = true;
            input.hasRemaining();
        } catch (IllegalStateException expected) {
            failed = expected.getMessage().contains("sentinel") || expected.getCause() != null && expected.getCause().getMessage().contains("sentinel");
        } finally {
            MediaCodec.failCreate = MediaCodec.failDecode = false;
            if (recorder != null) recorder.release();
            input.release();
        }
        check(failed, create ? "decoder creation failure propagates" : "decoder failure propagates");
        check(MediaCodec.zeroShorts == zero, "decoder errors cannot become padding");
    }
    static void blank() throws Exception {
        int decoders = MediaCodec.decoders;
        long zero = MediaCodec.zeroShorts, nonzero = MediaCodec.nonzeroShorts;
        AudioRecoder recorder = MediaCodecVideoConvertor.initialize(new BlankAudioInput(1000000), 0, 1000000, -1);
        MediaCodecVideoConvertor.Muxer muxer = new MediaCodecVideoConvertor.Muxer();
        int steps = 0; while (!recorder.step(muxer, 0)) if (++steps > 10000) throw new AssertionError("blank did not end");
        check(MediaCodec.decoders == decoders, "video-only circles never open the original audio decoder");
        check(MediaCodec.nonzeroShorts == nonzero, "video-only circles retain silence");
        check(MediaCodec.zeroShorts - zero == AudioConversions.usToShorts(1000000, 44100, 2), "native blank input uses its own bounded encoder rate");
        recorder.release();
    }
    static void interrupted(boolean cancel) throws Exception {
        PurpleLongRoundAudioInput input = new PurpleLongRoundAudioInput("synthetic", 0);
        AudioRecoder recorder = MediaCodecVideoConvertor.initialize(input, 0, 1000000, 0);
        long zero = MediaCodec.zeroShorts;
        boolean failed = false;
        try {
            MediaCodec.starve = !cancel; MediaCodec.cancel = cancel;
            input.hasRemaining();
        } catch (RuntimeException expected) {
            failed = cancel ? expected instanceof java.util.concurrent.CancellationException : expected.getMessage().contains("timeout");
        } finally {
            MediaCodec.starve = MediaCodec.cancel = false;
            recorder.release(); input.release();
        }
        check(failed, cancel ? "cancellation propagates without EOS" : "codec starvation timeout propagates without EOS");
        check(MediaCodec.zeroShorts == zero, "interrupted decoding cannot become padding");
    }
    public static void main(String[] args) throws Exception {
        directory = new java.io.File(args[0]);
        String selected = args.length > 1 ? args[1] : "all";
        if (selected.equals("tail")) range(34710000, 69420000, null);
        else if (selected.equals("unclean")) uncleanEnd();
        else {
            PurpleLongRoundAudioInput reused = range(0, 34710000, null);
            range(34710000, 69420000, null);
            range(10000000, 11000000, null);
            range(0, 34710000, reused);
            uncleanEnd(); failure(true); failure(false); interrupted(false); interrupted(true); blank();
        }
        System.out.println("LONG_ROUND_NATIVE_AUDIO_CHECKS=" + checks);
    }
}
''')
    copied[str(j/'org/telegram/messenger/video/MediaCodecVideoConvertor.java')]=hashlib.sha256(native.encode()).hexdigest()
    copied[str(j/'org/telegram/messenger/video/Track.java')]=hashlib.sha256(track.encode()).hexdigest()
    (o/'copied-production-sha256.json').write_text(json.dumps(copied,indent=2)+'\n')
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    command=[str(jdk/'javac'),'-d',str(o/'classes'),*map(str,o.rglob('*.java'))]
    subprocess.run([*(compile_wrapper or []),*command],check=True)
    result=subprocess.run([str(jdk/'java'),'-cp',str(o/'classes'),'NativeLongAudioCases',str(o),case or 'all'],check=False,text=True,capture_output=True,timeout=60)
    (o/'native-long-audio.log').write_text(result.stdout+result.stderr)
    print(result.stdout,end='');print(result.stderr,end='',file=sys.stderr)
    result.check_returncode()
