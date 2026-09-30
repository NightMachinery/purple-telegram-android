package org.telegram.messenger.purple;

public final class PurpleSyncSettingsFile {
    public enum Status { Present, Absent, Invalid }

    public static final class Contents {
        public final Status status;
        public final byte[] bytes;
        public final boolean usingLastGood;

        Contents(Status status, byte[] bytes) {
            this(status, bytes, false);
        }

        Contents(Status status, byte[] bytes, boolean usingLastGood) {
            this.status = status;
            this.bytes = bytes;
            this.usingLastGood = usingLastGood;
        }
    }

    private PurpleSyncSettingsFile() {
    }
}
