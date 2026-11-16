package org.telegram.messenger;public class SharedConfig{static int id;public static int getLastLocalId(){return ++id;}public static void saveConfig(){}}
