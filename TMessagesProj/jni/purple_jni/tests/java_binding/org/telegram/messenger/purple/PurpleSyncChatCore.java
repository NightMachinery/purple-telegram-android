package org.telegram.messenger.purple;

final class PurpleSyncChatCore {
    static final class Peer {
        static Peer self() { throw new AssertionError("PurpleSyncChatCore is not part of this test"); }
        String token() { throw new AssertionError("PurpleSyncChatCore is not part of this test"); }
    }
}
