package org.telegram.messenger;
public final class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 1;
    private static final UserConfig config = new UserConfig();
    public static UserConfig getInstance(int slot) { return config; }
    public boolean isClientActivated() { return false; }
}
