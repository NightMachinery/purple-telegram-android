package org.telegram.messenger.purple;

public final class PurpleRecordingPreviewHandoff {
    private long[] send, capture;
    public void arm(long[] send, long[] capture) {
        this.send = send.clone(); this.capture = capture.clone();
    }
    public boolean matches(int account, long user, long dialog, long topic) {
        return send != null && send[0] == account && send[4] == user && send[1] == dialog && send[2] == topic;
    }
    public long[][] take(int account, long user, long dialog, long topic) {
        if (!matches(account, user, dialog, topic)) return null;
        long[][] result = {send.clone(), capture.clone()}; clear(); return result;
    }
    public void clear() { send = capture = null; }
}
