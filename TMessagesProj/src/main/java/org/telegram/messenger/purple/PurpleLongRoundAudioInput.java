package org.telegram.messenger.purple;

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
    private boolean ended;

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
        sampleRate = rate; channels = count; ended = false;
        decoder().start();
    }
    @Override public boolean hasRemaining() {
        while (!ended && (buffer == null || !buffer.hasRemaining())) {
            final AudioDecoder.DecodedBufferData data = decoder.decode();
            if (data.index < 0) { ended = true; break; }
            try {
                final long end = data.presentationTimeUs + AudioConversions.bytesToUs(
                        data.size, decoder.getSampleRate(), decoder.getChannelCount());
                if (end <= getStartTimeUs()) continue;
                if (data.presentationTimeUs >= getEndTimeUs()) { ended = true; break; }
                if (data.byteBuffer.hasRemaining()) {
                    buffer = converter.convert(data.byteBuffer.asShortBuffer(), decoder.getSampleRate(),
                            decoder.getChannelCount(), sampleRate, channels);
                }
            } finally { decoder.releaseOutputBuffer(data.index); }
        }
        return buffer != null && buffer.hasRemaining();
    }
    @Override public short getNext() { return hasRemaining() ? buffer.get() : 0; }
    @Override public void release() {
        buffer = null; ended = true;
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
