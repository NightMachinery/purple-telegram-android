package org.telegram.messenger.purple;

final class PurpleSyncFileContext {
    final PurpleAccountFiles.Identity origin;
    final PurpleAccountFiles files;
    private final String owner;
    private final String global;
    private final long epoch;

    PurpleSyncFileContext(int account) {
        PurpleAccountOwner.ensureStarted();
        origin = PurpleAccountFiles.capture(account);
        owner = PurpleAccountOwner.owner();
        global = PurpleAccountOwner.global();
        epoch = PurpleAccountOwner.roleEpoch();
        files = origin == null ? null : PurpleAccountFiles.resolve(origin.key, owner);
    }

    boolean editable() {
        return current() && PurpleAccountOwner.legacyEditorAllowed(origin.slot);
    }

    boolean current() {
        return origin != null && files != null
                && origin.slot == org.telegram.messenger.UserConfig.selectedAccount && origin.current()
                && epoch == PurpleAccountOwner.roleEpoch()
                && java.util.Objects.equals(owner, PurpleAccountOwner.owner())
                && java.util.Objects.equals(global, PurpleAccountOwner.global())
                && origin.key.equals(owner) && origin.key.equals(global)
                && PurpleAccountOwner.legacyOperation(origin.slot);
    }
}
