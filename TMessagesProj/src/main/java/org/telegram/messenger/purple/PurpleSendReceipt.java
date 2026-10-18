package org.telegram.messenger.purple;

public interface PurpleSendReceipt {
    void onResult(int serverId, boolean preparationFailed);
}
