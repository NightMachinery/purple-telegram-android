package org.telegram.messenger.purple;

public final class PurpleRecordingTarget {
    private int guid;
    private long[] target;
    private boolean leftChat;

    public void capture(int guid, long[] target) {
        this.guid = guid;
        this.target = target == null ? null : target.clone();
        leftChat = false;
    }
    public void dock() { leftChat = true; }
    public void once(boolean once) {
        if (target == null || target.length < 6) return;
        target[5] = (target[5] & ~1L) | (once ? 1 : 0);
    }
    public void clear(int guid) { if (this.guid == guid) target = null; }
    public long[] forEvent(int guid, boolean docked, boolean terminal) {
        return this.guid == guid && target != null && (docked || terminal && leftChat) ? target.clone() : null;
    }
}
