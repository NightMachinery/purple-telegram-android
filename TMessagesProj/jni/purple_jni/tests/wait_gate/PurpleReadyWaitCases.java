package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;

public final class PurpleReadyWaitCases {
    private static int checks;
    private static void check(boolean value, String text) {
        ++checks;
        if (!value) throw new AssertionError(text);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void phases() {
        PurpleWaitGate gate = new PurpleWaitGate(100, 2, 10);
        check(gate.key(101, true, false) == PurpleWaitGate.Key.ABORT, "countdown Enter aborts");
        check(gate.poll(2099, true, true, false) == PurpleWaitGate.Outcome.WAITING, "last countdown millisecond");
        check(gate.poll(2100, true, true, false) == PurpleWaitGate.Outcome.READY, "deadline presents Ready");
        check(gate.ready(), "poll does not consume the action");
        check(gate.complete(2599, true, true, false, true) == PurpleWaitGate.Outcome.WAITING, "late tap at499ms cannot confirm");
        check(gate.key(2599, false, false) == PurpleWaitGate.Key.IGNORE, "keys during guard ignored");
        check(gate.key(2600, true, true) == PurpleWaitGate.Key.IGNORE, "repeat Enter cannot confirm");
        check(gate.key(2600, true, false) == PurpleWaitGate.Key.CONFIRM, "fresh Enter at500ms confirms");
        check(gate.key(2600, false, false) == PurpleWaitGate.Key.ABORT, "other Ready keys abort");
        check(gate.complete(2600, true, false, true, true) == PurpleWaitGate.Outcome.WAITING, "locked confirmation refuses");
        gate.guardInput(3000);
        check(gate.guarded(3499) && !gate.guarded(3500), "revealing cover rearms guard");
        check(gate.complete(3500, true, true, false, true) == PurpleWaitGate.Outcome.READY, "button consumes exactly once");
        check(gate.complete(3501, true, true, false, true) == PurpleWaitGate.Outcome.FINISHED, "stale duplicate is fenced");
        gate = new PurpleWaitGate(100, 2, 10);
        check(gate.poll(12099, true, false, true) == PurpleWaitGate.Outcome.READY, "late wake before expiry reaches Ready while locked");
        check(gate.poll(12100, true, false, true) == PurpleWaitGate.Outcome.EXPIRED, "timeout anchored to original deadline");
        check(gate.complete(12600, true, true, false, true) == PurpleWaitGate.Outcome.FINISHED, "expired wait cannot apply");
        gate = new PurpleWaitGate(0, 1, 10);
        check(gate.poll(8 * 3600000L, true, false, true) == PurpleWaitGate.Outcome.EXPIRED, "sleep spans timeout without timer delivery");
        gate = new PurpleWaitGate(0, 1, 10);
        gate.poll(1000, true, true, false);
        check(gate.abort() == PurpleWaitGate.Outcome.ABORTED, "Abort usable during guard");
        check(new PurpleWaitGate(100, 0).complete(100, true, true, false, true) == PurpleWaitGate.Outcome.READY, "zero delay unchanged");
        gate = new PurpleWaitGate(Long.MAX_VALUE - 1000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        check(gate.remaining(Long.MAX_VALUE - 1000) == 1000, "deadline saturates without overflow");
    }
    private static void presence() {
        PurpleWaitPresence.Lock open = PurpleWaitPresence.Lock.UNLOCKED, locked = PurpleWaitPresence.Lock.LOCKED;
        for (int offset : new int[]{0, 1, 299, 300, 301, 1000, 10000}) {
            PurpleWaitPresence p = new PurpleWaitPresence();
            check(p.update(100, false, open) && p.suspended(), "pause suspends input immediately");
            check(p.update(100 + offset, false, locked) == (offset < 300), "strict300ms lock boundary " + offset);
            if (offset < 300) {
                check(p.update(8 * 3600000L, false, locked), "established lock survives hours");
                check(p.update(8 * 3600000L + 1, false, open) && p.suspended(), "unlock allows owner return");
                check(p.update(8 * 3600000L + 2000, true, open) && !p.suspended(), "return at1999ms allowed");
            } else check(!p.update(101 + offset, true, open), "late lock never rescues switch");
        }
        PurpleWaitPresence p = new PurpleWaitPresence();
        p.update(0, false, open);
        check(!p.update(1, true, open), "quick app switch and return cancels");
        p = new PurpleWaitPresence(); p.update(0, false, open); p.update(200, false, open);
        check(!p.update(300, false, open), "onStop does not restart away boundary");
        p = new PurpleWaitPresence(); p.update(0, true, locked); p.update(10, false, locked);
        check(p.update(10000, false, locked), "lock before pause survives");
        p.update(10001, false, open);
        check(!p.update(12001, true, open), "owner return at2000ms cancels");
        p = new PurpleWaitPresence();
        p.update(0, false, locked); p.update(100, false, open); p.update(150, false, open, true, false);
        check(p.update(200, false, locked) && p.suspended(), "relock during return restores Locked");
        check(p.update(10000, false, locked), "original unlocked-return deadline cannot cancel new lock");
        p.update(10100, false, open);
        check(p.update(12099, true, open) && !p.suspended(), "second unlock gets its own bounded return");
        p = new PurpleWaitPresence(); p.update(0, false, locked); p.update(100, false, open); p.update(150, false, open, true, false);
        p.update(200, false, locked); p.update(10000, false, locked); p.update(10100, false, open);
        check(!p.update(12100, true, open), "second unlock at2000ms still cancels");
        for (boolean resumeFirst : new boolean[]{false, true}) {
            p = new PurpleWaitPresence(); p.update(0, false, locked);
            p.update(100, false, open, resumeFirst, false);
            if (!resumeFirst) p.update(150, false, open, true, false);
            check(p.update(200, false, locked) && p.update(10000, false, locked), "positive owner resume preserves relock in either signal order");
        }
        p = new PurpleWaitPresence(); p.update(0, false, locked); p.update(100, false, open);
        check(!p.update(200, false, locked), "no owner resume makes relock ambiguous and cancels");
        for (int elapsed : new int[]{1999, 2000, 2001, 10000}) {
            p = new PurpleWaitPresence(); p.update(0, false, locked); p.update(100, false, open);
            p.update(101, false, open, true, false);
            check(p.update(100 + elapsed, false, locked) == (elapsed < 2000), "return expiry precedes first relock sample " + elapsed);
        }
        for (int elapsed : new int[]{299, 300, 400}) {
            p = new PurpleWaitPresence(); p.update(0, false, locked); p.update(100, false, open);
            p.update(150, false, open, true, false); p.update(200, false, open, false, true);
            p.update(250, false, open); p.update(499, false, open);
            check(p.update(200 + elapsed, false, locked) == (elapsed < 300), "fresh departure keeps original strict lock-proof boundary " + elapsed);
        }
        p = new PurpleWaitPresence();
        check(!p.update(0, true, PurpleWaitPresence.Lock.UNKNOWN), "unknown facts cancel safely");
    }
    private static void core() {
        String prefix = "version=2\n";
        for (String scalar : new String[]{"", "0", "9", "10", "600", "900", "-1", "true", "\"oops\"", "1.5"}) {
            String input = prefix + (scalar.isEmpty() ? "" : "[peek]\nready_timeout_seconds=" + scalar + "\n");
            PurpleCore.Loaded loaded = PurpleCore.load(bytes(input), bytes(""), "fixture", "android", "mobile");
            int expected = scalar.equals("0") || scalar.equals("9") ? 10 : scalar.equals("10") ? 10 : scalar.equals("900") ? 900 : 600;
            check(loaded.ok && loaded.clock.peekReadyTimeout == expected, "real core timeout parse " + scalar);
            if (!scalar.isEmpty() && !scalar.equals("10") && !scalar.equals("600") && !scalar.equals("900"))
                check(!loaded.warnings.isEmpty(), "invalid or clamped value warns " + scalar);
        }
        String input = prefix + "# keep\n[peek]\nready_timeout_seconds = 600 # note\nstart_delay_seconds=2\nunknown=7\n";
        PurpleCore.SpliceResult edited = PurpleCore.setPeekReadyTimeout(bytes(input), 10);
        check(edited.ok() && edited.text.contains("# keep") && edited.text.contains("# note") && edited.text.contains("unknown=7"), "timeout splice preserves comments and unknown keys");
        check(PurpleCore.load(bytes(edited.text), bytes(""), "fixture", "android", "mobile").clock.peekReadyTimeout == 10, "splice publishes effective value");
        check(!PurpleCore.setPeekReadyTimeout(bytes(input), 9).ok(), "setter refuses belowfloor");
        check(PurpleCore.setPeekReadyTimeout(bytes(prefix), 600).ok(), "shared splice can materialize absent default; editor filters the no-op");
        check(PurpleCore.setPeekStartDelay(bytes(input), 3).ok(), "shared scalar refactor keeps start-delay writer");
        check(!PurpleWaitGate.readyTimeoutWait(600, 10) && !PurpleWaitGate.readyTimeoutWait(600, 600)
                && PurpleWaitGate.readyTimeoutWait(600, 601), "editor lowering immediate, equalnoop, raising waits");
    }
    private static void layout() {
        for (int abortTop : new int[]{-60, 0, 40, 100, 800, 2000}) {
            for (int height = 64; height < 2000; height += 23) {
                for (int gap : new int[]{0, 8, 24}) {
                    int top = PurpleWaitLayout.readyTop(abortTop, gap, height);
                    check(top + height <= abortTop, "ready action bottom never crosses countdown Abort");
                    check(top + height == abortTop - gap, "Abort stays anchored even with clipped heading");
                }
            }
        }
    }
    public static int run() { phases(); presence(); core(); layout(); return checks; }
}
