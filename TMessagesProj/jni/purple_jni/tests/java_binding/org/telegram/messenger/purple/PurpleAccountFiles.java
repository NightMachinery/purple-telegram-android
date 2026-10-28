package org.telegram.messenger.purple;

final class PurpleAccountFiles {
    static final class Identity { String key; long lifetime; }
    static Identity capture(int account) { throw new AssertionError("PurpleAccountFiles is not part of this test"); }
}
