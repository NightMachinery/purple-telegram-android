package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FileLog {
    public static volatile RuntimeException injectedFailure;
    public static final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    public static void e(String message) {
        RuntimeException failure = injectedFailure;
        if (failure != null) throw failure;
        lines.add(message);
    }

    public static void e(Throwable error) {
        RuntimeException failure = injectedFailure;
        if (failure != null) throw failure;
    }

    public static void e(Throwable error, boolean report) {
        e(error);
    }

    public static void d(String message) {
    }
}
