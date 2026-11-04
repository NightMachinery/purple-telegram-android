package org.telegram.messenger.purple;

public final class PurpleRecordingDockState {
    public int account = -1;
    public int guid;
    public long generation = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
    public long user;
    public long dialog;
    public long topic;
    public boolean once;
    public boolean round;
    public final float[] levels = new float[64];
    public int levelIndex;
    public boolean scheduled;
    public boolean docked;
    public boolean paused;
    public boolean finishing;
    public boolean transitioning;
    public boolean sendPending;
    private long started;
    private long pausedAt;
    private long pausedTime;
    private long discardUntil;

    public String actionIdentity(String action) {
        return "purple-recording://action/" + generation + "/" + action;
    }

    public void amplitude(double value) {
        if (active() && !paused && Double.isFinite(value)) levels[levelIndex++ % levels.length] = (float) Math.max(0, Math.min(1, value / 100.0));
    }

    public boolean retarget(long dialog, long topic) {
        if (!active() || finishing || transitioning || dialog == 0) return false;
        this.dialog = dialog; this.topic = topic;
        generation++;
        discardUntil = 0;
        return true;
    }

    public boolean active() { return account >= 0; }
    public boolean canDock() { return active() && !round && !finishing && (!paused || docked); }

    public boolean begin(int account, int guid, long user, long dialog, long topic,
            boolean once, boolean scheduled, long now) {
        if (active()) return false;
        this.account = account;
        this.guid = guid;
        this.user = user;
        this.dialog = dialog;
        this.topic = topic;
        this.once = once;
        this.scheduled = scheduled;
        started = now;
        pausedTime = 0;
        pausedAt = 0;
        discardUntil = 0;
        docked = paused = finishing = transitioning = sendPending = round = false;
        java.util.Arrays.fill(levels, 0);
        levelIndex = 0;
        generation++;
        return true;
    }

    public boolean matches(int account, int guid) {
        return active() && this.account == account && this.guid == guid;
    }

    public boolean accepts(long generation) {
        return active() && !finishing && this.generation == generation;
    }

    public boolean requestPause() {
        if (!active() || finishing || transitioning) return false;
        transitioning = true;
        return true;
    }

    public boolean requestSend() {
        if (!active() || finishing || transitioning) return false;
        if (paused) {
            sendPending = true;
            transitioning = true;
            return false;
        }
        finishing = true;
        return true;
    }

    public void pause(boolean value, long now) {
        transitioning = false;
        if (!active() || value == paused) return;
        if (value) pausedAt = now;
        else pausedTime += Math.max(0, now - pausedAt);
        paused = value;
    }

    public long duration(long now) {
        return active() ? Math.max(0, (paused ? pausedAt : now) - started - pausedTime) : 0;
    }

    public boolean confirmDiscard(long now) {
        if (discardUntil != 0 && now <= discardUntil) {
            discardUntil = 0;
            return true;
        }
        discardUntil = now + 5000;
        return false;
    }

    public boolean discardArmed(long now) { return discardUntil > now; }

    public void clear() {
        account = -1;
        docked = paused = finishing = transitioning = sendPending = round = false;
        java.util.Arrays.fill(levels, 0);
        levelIndex = 0;
        discardUntil = 0;
        generation++;
    }
}
