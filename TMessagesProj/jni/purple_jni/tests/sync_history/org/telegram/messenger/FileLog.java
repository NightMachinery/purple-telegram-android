package org.telegram.messenger;

public final class FileLog {
    public static int errors;

    public static void e(String message) {
        ++errors;
    }

    public static void e(Throwable error) {
        ++errors;
    }

    public static void d(String message) {
    }
}
