package org.telegram.messenger.purple;

public interface PurpleSendReceipt {
    void onResult(int serverId, boolean preparationFailed);
    default boolean current() { return true; }
    static PurpleSendReceipt guarded(java.util.function.BooleanSupplier current, PurpleSendReceipt receipt) {
        return new PurpleSendReceipt() {
            public boolean current() { return current.getAsBoolean() && receipt.current(); }
            public void onResult(int serverId, boolean preparationFailed) { receipt.onResult(serverId, preparationFailed); }
        };
    }
}
