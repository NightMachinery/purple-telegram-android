package org.telegram.messenger.purple;

public final class PurpleLegacyCodecEos {
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
    public void audioInput(boolean end) { audioInputEnded |= end; }
    public void audioOutput(boolean end) { audioOutputEnded |= end; }
    public void videoInputEnd() { videoInputEnded = true; }
    public void videoOutput(boolean end) { videoOutputEnded |= end; }
    public void videoSample() { videoSample = true; }
    public void fail() { failed = true; }
    public void awaitMuxer(java.util.concurrent.CountDownLatch done) {
        boolean interrupted = false;
        while (true) {
            try { done.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public void muxerFinished() { muxerFinished = true; }
    public boolean hasVideoSample() { return videoSample; }
    public boolean canPreview() { return videoSample && !failed && muxerFinished; }
}
