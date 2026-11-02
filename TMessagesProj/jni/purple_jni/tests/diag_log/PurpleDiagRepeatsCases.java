package org.telegram.messenger.purple;

import java.util.List;

/**
 * Host cases for the production PurpleDiagRepeats: the burst, the window,
 * the summary of dropped copies, independence of different lines, eviction,
 * a clock that goes backwards and the export flush. Run from
 * PurpleDiagLogCases against the AGP-compiled class.
 */
public final class PurpleDiagRepeatsCases {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }

    private static boolean summary(List<PurpleDiagRepeats.Summary> list, String area, String event, int dropped) {
        for (PurpleDiagRepeats.Summary item : list) {
            if (item.area.equals(area) && item.event.equals(event) && item.dropped == dropped) return true;
        }
        return false;
    }

    public static int run() {
        checks = 0;
        long at = 1792886400000L;
        String archive = "archive open_failed slot=0 step=store_open reason=archive_login_admission_failed";
        String other = "archive open_failed slot=1 step=store_open reason=archive_login_admission_failed";

        // The owner's case: one failure line thousands of times.
        PurpleDiagRepeats repeats = new PurpleDiagRepeats();
        int written = 0;
        for (int i = 0; i < 6000; ++i) {
            PurpleDiagRepeats.Decision decision = repeats.offer(at + i, archive, "archive", "open_failed");
            if (decision.write) ++written;
            check(decision.summaries.isEmpty(), "no summary inside the window");
        }
        check(written == PurpleDiagRepeats.BURST, "a burst of identical lines writes " + PurpleDiagRepeats.BURST + ": " + written);

        // A different line is limited on its own.
        check(repeats.offer(at + 6000, other, "archive", "open_failed").write, "another slot's line is written");

        // The window ends: the summary comes first, then the line is written again.
        PurpleDiagRepeats.Decision later = repeats.offer(at + PurpleDiagRepeats.WINDOW, archive, "archive", "open_failed");
        check(later.write, "the line is written again after the window");
        check(later.summaries.size() == 1 && summary(later.summaries, "archive", "open_failed", 6000 - PurpleDiagRepeats.BURST),
                "one summary with the dropped count");
        check(repeats.flush().isEmpty(), "nothing left to flush after the summary");

        // A window that ended is summarized when any other line arrives.
        PurpleDiagRepeats quiet = new PurpleDiagRepeats();
        for (int i = 0; i < 5; ++i) quiet.offer(at, archive, "archive", "open_failed");
        PurpleDiagRepeats.Decision unrelated = quiet.offer(at + PurpleDiagRepeats.WINDOW + 1, other, "archive", "open_failed");
        check(unrelated.write && summary(unrelated.summaries, "archive", "open_failed", 5 - PurpleDiagRepeats.BURST),
                "an ended window is summarized by an unrelated line");

        // No summary when nothing was dropped.
        PurpleDiagRepeats few = new PurpleDiagRepeats();
        for (int i = 0; i < PurpleDiagRepeats.BURST; ++i) check(few.offer(at, archive, "archive", "open_failed").write, "within the burst");
        check(few.flush().isEmpty(), "a window that dropped nothing has no summary");
        check(few.offer(at + PurpleDiagRepeats.WINDOW, archive, "archive", "open_failed").summaries.isEmpty(),
                "and its end adds none");

        // Export flush reports dropped copies of a window that is still open, once.
        PurpleDiagRepeats open = new PurpleDiagRepeats();
        for (int i = 0; i < 10; ++i) open.offer(at, archive, "archive", "open_failed");
        List<PurpleDiagRepeats.Summary> flushed = open.flush();
        check(flushed.size() == 1 && summary(flushed, "archive", "open_failed", 10 - PurpleDiagRepeats.BURST), "flush summarizes an open window");
        check(open.flush().isEmpty(), "a second flush adds nothing");
        check(!open.offer(at + 1, archive, "archive", "open_failed").write, "flushing does not reopen the burst");

        // A clock that goes backwards ends the window instead of silencing the line.
        PurpleDiagRepeats clock = new PurpleDiagRepeats();
        for (int i = 0; i < 4; ++i) clock.offer(at, archive, "archive", "open_failed");
        PurpleDiagRepeats.Decision back = clock.offer(at - 1000, archive, "archive", "open_failed");
        check(back.write && summary(back.summaries, "archive", "open_failed", 1), "a backwards clock ends the window");

        // Eviction summarizes the least recently seen line and bounds the memory.
        PurpleDiagRepeats evict = new PurpleDiagRepeats();
        for (int i = 0; i < 5; ++i) evict.offer(at, archive, "archive", "open_failed");
        boolean evicted = false;
        for (int i = 0; i < PurpleDiagRepeats.KEYS; ++i) {
            PurpleDiagRepeats.Decision decision = evict.offer(at + 1, "sync_chat request count=" + i, "sync_chat", "request");
            check(decision.write, "a new line is written");
            if (summary(decision.summaries, "archive", "open_failed", 5 - PurpleDiagRepeats.BURST)) evicted = true;
        }
        check(evicted, "the evicted line's dropped copies are summarized");
        check(evict.offer(at + 2, archive, "archive", "open_failed").write, "an evicted line starts a new window");
        return checks;
    }
}
