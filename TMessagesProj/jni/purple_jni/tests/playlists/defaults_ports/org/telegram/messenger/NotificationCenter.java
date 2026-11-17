package org.telegram.messenger;
public class NotificationCenter {public static final int dialogFiltersUpdated=1;public static NotificationCenter getInstance(int slot){return new NotificationCenter();}public void postNotificationName(int id,Object...args){org.telegram.messenger.purple.NativeWriterFixture.notified++;}}
