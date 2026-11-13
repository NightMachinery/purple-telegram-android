package org.telegram.messenger;
public final class LocaleController {
    public static String getString(int resource) { return "resource:"+resource; }
    public static String getString(String key,int resource) { return getString(resource); }
    public static String formatString(int resource,Object... args) { return getString(resource)+java.util.Arrays.toString(args); }
}
