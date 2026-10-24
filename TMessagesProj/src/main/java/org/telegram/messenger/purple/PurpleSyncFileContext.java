package org.telegram.messenger.purple;

final class PurpleSyncFileContext {
    final PurpleAccountFiles.Identity origin;
    final PurpleAccountFiles files;
    private final String owner;

    PurpleSyncFileContext(int account) {
        PurpleAccountOwner.ensureStarted();
        origin = PurpleAccountFiles.capture(account);
        owner = PurpleAccountOwner.owner();
        files = origin == null ? null : PurpleAccountFiles.resolve(origin.key, owner);
    }

    boolean editable() { return current(); }

    boolean current() {
        return origin != null && files != null && origin.current()
                && java.util.Objects.equals(owner, PurpleAccountOwner.owner())
                && PurpleAccountOwner.modernOperation(origin.slot);
    }
}
