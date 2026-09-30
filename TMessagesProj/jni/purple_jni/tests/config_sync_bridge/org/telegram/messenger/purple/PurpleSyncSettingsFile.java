package org.telegram.messenger.purple;

public final class PurpleSyncSettingsFile {
    public enum Status { Present, Absent, Invalid }

    public static final class Contents {
        public final Status status;
        public final byte[] bytes;

        Contents(Status status, byte[] bytes) {
            this.status = status;
            this.bytes = bytes;
        }
    }

    private PurpleSyncSettingsFile() {
    }
}
