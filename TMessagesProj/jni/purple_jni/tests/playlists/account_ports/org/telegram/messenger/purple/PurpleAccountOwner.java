package org.telegram.messenger.purple;
public final class PurpleAccountOwner {
 public static String owner="0-101",global="0-101"; public static long revision,role; public static boolean ready=true;
 public static boolean ready(){return ready;} public static String owner(){return owner;} public static String global(){return global;}
 public static long revision(){return revision;} public static long roleEpoch(){return role;}
 static long observedRevision(){return revision;} static long observedRoleEpoch(){return role;}
 public static void accountsChanged(){} public static boolean started(){return true;}
}
