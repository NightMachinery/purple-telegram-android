package org.telegram.messenger.purple;

public final class PurpleRoundCaptureLease {
    public final int account, guid, mode;
    public final long user, dialog, topic;
    private long nextBinding, binding;
    private boolean finishing, ended;

    public PurpleRoundCaptureLease(int account, long user, long dialog, long topic, int mode, int guid) {
        this.account = account; this.user = user; this.dialog = dialog;
        this.topic = topic; this.mode = mode; this.guid = guid;
    }

    public boolean matches(int account, long user, long dialog, long topic, int mode) {
        return !ended && this.account == account && this.user == user && this.dialog == dialog
                && this.topic == topic && this.mode == mode;
    }

    public long attach(int account, long user, long dialog, long topic, int mode) {
        if (finishing || !matches(account, user, dialog, topic, mode)) return 0;
        return binding = ++nextBinding;
    }

    public boolean detach(long token) {
        if (token == 0 || token != binding) return false;
        binding = 0;
        return true;
    }

    public boolean finish() {
        if (finishing || ended) return false;
        finishing = true;
        return true;
    }

    public boolean finishing() { return finishing; }
    public boolean ended() { return ended; }
    public void end() { ended = true; binding = 0; }
}
