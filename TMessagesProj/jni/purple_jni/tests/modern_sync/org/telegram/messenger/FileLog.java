package org.telegram.messenger;

public final class FileLog {
    public static int errors;
    /** Every error line, so cases can read the diagnostic lines PurpleDiagLog writes through here. */
    public static final java.util.List<String> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static void e(String message) {
        ++errors;
        lines.add(message);
    }

    public static void e(Throwable error) {
        ++errors;
    }

    public static void e(Throwable error,boolean report) {
        ++errors;
    }

    public static void d(String message) {
    }
}
