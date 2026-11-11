package org.telegram.messenger.purple;
public class PurpleGate {static int version=1;public static int generation(){return version;}public static void reload(String reason){++version;PurpleAccountSettings.invalidate();}public static void reloadAccountFiles(String reason){reload(reason);}}
