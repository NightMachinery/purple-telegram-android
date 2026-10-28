package org.telegram.messenger;
import java.util.*;
public final class NotificationCenter {
 public static final int activeAccountChanged=1,screenStateChanged=2;
 private static final NotificationCenter global=new NotificationCenter();
 public static NotificationCenter getGlobalInstance(){return global;}
 public interface NotificationCenterDelegate{void didReceivedNotification(int id,int account,Object...args);}
 public final Map<Integer,List<NotificationCenterDelegate>> observers=new HashMap<>();
 public void addObserver(NotificationCenterDelegate d,int id){observers.computeIfAbsent(id,k->new ArrayList<>()).add(d);}
 public void postNotificationName(int id,Object...args){for(NotificationCenterDelegate d:new ArrayList<>(observers.getOrDefault(id,Collections.emptyList())))d.didReceivedNotification(id,0,args);}
}
