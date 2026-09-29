package org.telegram.messenger;

public final class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 4;

    private UserConfig() {
    }

    public static UserConfig getInstance(int account) {
        throw new UnsupportedOperationException();
    }

    public boolean isClientActivated() {
        throw new UnsupportedOperationException();
    }

    public long getClientUserId() {
        throw new UnsupportedOperationException();
    }
}
