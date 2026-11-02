/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The repeat limit of the Purple diagnostic log, kept free of Android so host
 * tests run this production class unchanged.
 *
 * A line body (everything after the timestamp) may be written {@link #BURST}
 * times per {@link #WINDOW}; later copies in that window are only counted. A
 * window ends {@link #WINDOW} after its first line, when the body is evicted
 * from the {@link #KEYS} most recently seen ones, or when an export flushes.
 * A window that dropped copies then yields one summary,
 * {@code diag repeated area=<area> event=<event> count=<dropped>}, so a
 * failure that repeats thousands of times costs a few lines instead of
 * evicting every other line from the bounded log.
 */
final class PurpleDiagRepeats {
    static final long WINDOW = 10 * 60 * 1000L;
    static final int BURST = 3;
    static final int KEYS = 64;

    /** A window that dropped lines: what the summary names and how many it dropped. */
    static final class Summary {
        final String area;
        final String event;
        final int dropped;

        Summary(String area, String event, int dropped) {
            this.area = area;
            this.event = event;
            this.dropped = dropped;
        }
    }

    /** Whether to write the offered line, and the summaries due before it. */
    static final class Decision {
        final boolean write;
        final List<Summary> summaries;

        Decision(boolean write, List<Summary> summaries) {
            this.write = write;
            this.summaries = summaries;
        }
    }

    private static final class Window {
        final String area;
        final String event;
        long start;
        int written;
        int dropped;

        Window(String area, String event, long start) {
            this.area = area;
            this.event = event;
            this.start = start;
        }
    }

    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(16, 0.75f, true);

    /** A clock that went backwards ends the window too, so a wrong clock cannot silence a line. */
    private static boolean ended(Window window, long now) {
        return now < window.start || now - window.start >= WINDOW;
    }

    private static void summarize(Window window, List<Summary> out) {
        if (window.dropped > 0) out.add(new Summary(window.area, window.event, window.dropped));
        window.dropped = 0;
    }

    /**
     * Decides one line. {@code area} and {@code event} are the vocabulary
     * tokens the line already holds; {@code body} is the line without its
     * timestamp.
     */
    synchronized Decision offer(long now, String body, String area, String event) {
        List<Summary> due = new ArrayList<>();
        for (Window window : windows.values()) {
            if (window.dropped > 0 && ended(window, now)) summarize(window, due);
        }
        Window window = windows.get(body);
        boolean write;
        if (window == null || ended(window, now)) {
            if (window == null) {
                window = new Window(area, event, now);
                windows.put(body, window);
            }
            window.start = now;
            window.written = 1;
            write = true;
        } else if (window.written < BURST) {
            ++window.written;
            write = true;
        } else {
            ++window.dropped;
            write = false;
        }
        Iterator<Map.Entry<String, Window>> eldest = windows.entrySet().iterator();
        while (windows.size() > KEYS && eldest.hasNext()) {
            summarize(eldest.next().getValue(), due);
            eldest.remove();
        }
        return new Decision(write, due);
    }

    /** Summaries of every window that dropped lines so far, for an export to include. */
    synchronized List<Summary> flush() {
        List<Summary> due = new ArrayList<>();
        for (Window window : windows.values()) summarize(window, due);
        return due;
    }
}
