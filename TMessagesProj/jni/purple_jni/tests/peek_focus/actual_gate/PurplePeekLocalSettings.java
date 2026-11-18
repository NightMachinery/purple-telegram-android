package org.telegram.messenger.purple;

final class PurplePeekLocalSettings {
    static Snapshot policy = new Snapshot(false);

    static final class Snapshot {
        final boolean enabled;

        Snapshot(boolean enabled) { this.enabled = enabled; }
        boolean current() { return this == policy; }
    }

    static Snapshot snapshot() { return policy; }
}
