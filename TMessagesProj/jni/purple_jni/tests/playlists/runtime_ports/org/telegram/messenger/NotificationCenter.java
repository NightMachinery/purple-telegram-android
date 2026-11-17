package org.telegram.messenger;
public final class NotificationCenter {
    public interface NotificationCenterDelegate { void didReceivedNotification(int id, int account, Object... args); }
    public static final int didReceiveNewMessages = 1, replaceMessagesObjects = 2;
    private static final NotificationCenter instance = new NotificationCenter();
    public static int observers;
    public static NotificationCenter getInstance(int slot) { return instance; }
    public void addObserver(NotificationCenterDelegate observer, int id) { ++observers; }
    public void removeObserver(NotificationCenterDelegate observer, int id) { --observers; }
}
