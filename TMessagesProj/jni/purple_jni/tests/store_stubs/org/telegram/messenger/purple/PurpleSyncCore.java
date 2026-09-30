package org.telegram.messenger.purple;

public final class PurpleSyncCore {
    public enum CommitStatus { Ready, Unchanged, InvalidTransition, InvalidState }
    public static final class CommitCheck {
        public String error;
        public CommitStatus status;
        public byte[] state;
        public boolean isValid() { return error == null; }
    }
    public static CommitCheck checkCommit(byte[] current, byte[] next) { throw new AssertionError(); }
}
