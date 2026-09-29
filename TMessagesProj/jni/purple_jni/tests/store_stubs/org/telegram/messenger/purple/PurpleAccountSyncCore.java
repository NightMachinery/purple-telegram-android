package org.telegram.messenger.purple;

public final class PurpleAccountSyncCore {
    public static final int OBSERVATION_PRESENT = 2;
    public static final class Result {
        public String status;
        public String error;
        public String verdict;
        public String device;
        public long seq;
        public long pendingSeq;
        public boolean changed;
        public byte[] state;
    }
    public static Result inspectState(byte[] value) { throw new AssertionError(); }
    public static Result checkAccountBinding(int account, byte[] value) { throw new AssertionError(); }
    public static Result reserveConfigRecord(int account, byte[] state, byte[] record) { throw new AssertionError(); }
    public static Result checkLocalStage(byte[] state, byte[] record) { throw new AssertionError(); }
    public static Result confirmConfigReadBack(byte[] state, byte[] staged,
            String device, int kind, byte[] observed, int messageId) { throw new AssertionError(); }
}
