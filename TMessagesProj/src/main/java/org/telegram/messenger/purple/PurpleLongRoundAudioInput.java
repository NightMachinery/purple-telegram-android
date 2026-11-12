package org.telegram.messenger.purple;

import android.media.MediaCodec;
import org.telegram.messenger.video.AudioBufferConverter;
import org.telegram.messenger.video.AudioConversions;
import org.telegram.messenger.video.AudioDecoder;
import org.telegram.messenger.video.AudioRecoder;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import java.util.ArrayList;
import org.telegram.messenger.video.audio_input.AudioInput;
import java.io.IOException;
import java.nio.ShortBuffer;

public final class PurpleLongRoundAudioInput extends AudioInput {
    public static AudioRecoder silentRecorder(
            MediaCodecVideoConvertor.ConvertVideoParams params) throws IOException {
        final ArrayList<AudioInput> inputs = new ArrayList<>();
        inputs.add(params.purpleAudioInput);
        return new AudioRecoder(inputs, params.purpleAudioDurationUs);
    }
    private AudioDecoder decoder;
    private final String path;
    private final int track;
    private long start, end;
    private final AudioBufferConverter converter = new AudioBufferConverter();
    private ShortBuffer buffer;
    private int sampleRate, channels;
    private boolean ended, sourceEos;
    private long samplesRemaining;

    public PurpleLongRoundAudioInput(String path, int track) throws IOException {
        this.path = path; this.track = track;
    }
    @Override public long getStartTimeUs() { return start; }
    @Override public long getEndTimeUs() { return end; }
    @Override public long getDurationUs() { return getEndTimeUs() - getStartTimeUs(); }
    @Override public int getSampleRate() { return decoder().getSampleRate(); }
    @Override public int getBitrate() { return decoder().getBitrateRate(); }
    @Override public int getChannelCount() { return decoder().getChannelCount(); }
    @Override public void setStartTimeUs(long value) { start = value; }
    @Override public void setEndTimeUs(long value) { end = value; }
    @Override public void start(int rate, int count) {
        sampleRate = rate; channels = count; ended = sourceEos = false; buffer = null;
        samplesRemaining = AudioConversions.usToShorts(getDurationUs(), rate, count);
        decoder().start();
    }
    @Override public boolean hasRemaining() {
        if (samplesRemaining <= 0) return false;
        while (!ended && (buffer == null || !buffer.hasRemaining())) {
            final AudioDecoder.DecodedBufferData data = decoder.decode();
            if (data.index < 0) {
                if (!sourceEos) throw new IllegalStateException("Audio decoder ended without EOS");
                ended = true; break;
            }
            try {
                sourceEos |= (data.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                final long end = data.presentationTimeUs + AudioConversions.bytesToUs(
                        data.size, decoder.getSampleRate(), decoder.getChannelCount());
                if (end <= getStartTimeUs()) continue;
                if (data.presentationTimeUs >= getEndTimeUs()) continue;
                if (data.size > 0 && data.byteBuffer.hasRemaining()) {
                    buffer = converter.convert(data.byteBuffer.asShortBuffer(), decoder.getSampleRate(),
                            decoder.getChannelCount(), sampleRate, channels);
                }
            } finally { decoder.releaseOutputBuffer(data.index); }
        }
        return samplesRemaining > 0;
    }
    @Override public short getNext() {
        if (!hasRemaining()) return 0;
        samplesRemaining--;
        return buffer != null && buffer.hasRemaining() ? buffer.get() : 0;
    }
    @Override public void release() {
        buffer = null; ended = true; samplesRemaining = 0; sourceEos = false;
        if (decoder != null) {
            try { decoder.release(); } finally { decoder = null; }
        }
    }
    private AudioDecoder decoder() {
        if (decoder == null) {
            try {
                decoder = new AudioDecoder(path, track);
                decoder.setStartTimeUs(start); decoder.setEndTimeUs(end);
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        return decoder;
    }
}
