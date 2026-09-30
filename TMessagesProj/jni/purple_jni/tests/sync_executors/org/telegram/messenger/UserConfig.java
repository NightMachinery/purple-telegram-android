package org.telegram.messenger;

public final class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 4;
    public static volatile boolean activated = true;
    public static volatile long userId = 777;
    private static final UserConfig INSTANCE = new UserConfig();

    private UserConfig() {
    }

    public static UserConfig getInstance(int account) {
        return INSTANCE;
    }

    public boolean isClientActivated() {
        return activated;
    }

    public long getClientUserId() {
        return userId;
    }
}
