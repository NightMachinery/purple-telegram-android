package org.telegram.messenger.purple;

public final class PurpleLegacyCodecEos {
    private final PurpleMediaTrace.Take trace;
    public PurpleLegacyCodecEos() { this(null); }
    public PurpleLegacyCodecEos(PurpleMediaTrace.Take trace) { this.trace = trace; }
    private void observed(PurpleMediaTrace.Step step) { if (trace != null) trace.event(step, step != PurpleMediaTrace.Step.FAILURE); }
    private boolean audioInputEnded;
    private boolean audioOutputEnded;
    private boolean videoInputEnded;
    private boolean videoOutputEnded;
    private boolean videoSample;
    private boolean failed;
    private boolean muxerFinished;

    public void reset() {
        audioInputEnded = audioOutputEnded = videoInputEnded = videoOutputEnded = false;
        muxerFinished = false;
    }

    public boolean audioInputEnded() { return audioInputEnded; }
    public boolean audioOutputEnded() { return audioOutputEnded; }
    public boolean videoInputEnded() { return videoInputEnded; }
    public boolean videoOutputEnded() { return videoOutputEnded; }
    public void audioInput(boolean end) { if (end && !audioInputEnded) observed(PurpleMediaTrace.Step.AUDIO_INPUT_EOS); audioInputEnded |= end; }
    public void audioOutput(boolean end) { if (end && !audioOutputEnded) observed(PurpleMediaTrace.Step.AUDIO_OUTPUT_EOS); audioOutputEnded |= end; }
    public void videoInputEnd() { if (!videoInputEnded) observed(PurpleMediaTrace.Step.VIDEO_INPUT_EOS); videoInputEnded = true; }
    public void videoOutput(boolean end) { if (end && !videoOutputEnded) observed(PurpleMediaTrace.Step.VIDEO_OUTPUT_EOS); videoOutputEnded |= end; }
    public void videoSample() { videoSample = true; }
    public void fail() { if (!failed) observed(PurpleMediaTrace.Step.FAILURE); failed = true; }
    public void awaitMuxer(java.util.concurrent.CountDownLatch done) {
        boolean interrupted = false;
        while (true) {
            try { done.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public void muxerFinished() { observed(PurpleMediaTrace.Step.MUXER_COMPLETE); muxerFinished = true; }
    public boolean hasVideoSample() { return videoSample; }
    public boolean canPreview() { return videoSample && !failed && muxerFinished; }
}
