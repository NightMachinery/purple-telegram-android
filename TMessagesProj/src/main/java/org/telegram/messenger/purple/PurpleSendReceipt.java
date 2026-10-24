package org.telegram.messenger.purple;

public interface PurpleSendReceipt {
    void onResult(int serverId, boolean preparationFailed);
    default boolean current() { return true; }
    default PurpleAccountFiles.Identity modernOrigin() { return null; }
    static PurpleSendReceipt modern(PurpleAccountFiles.Identity origin, PurpleSendReceipt receipt) {
        return new PurpleSendReceipt() {
            public boolean current() { return origin != null && origin.current() && receipt.current(); }
            public PurpleAccountFiles.Identity modernOrigin() { return origin; }
            public void onResult(int serverId, boolean failed) { receipt.onResult(serverId, failed); }
        };
    }
    static PurpleSendReceipt guarded(java.util.function.BooleanSupplier current, PurpleSendReceipt receipt) {
        return new PurpleSendReceipt() {
            public boolean current() { return current.getAsBoolean() && receipt.current(); }
            public void onResult(int serverId, boolean preparationFailed) { receipt.onResult(serverId, preparationFailed); }
        };
    }
}
