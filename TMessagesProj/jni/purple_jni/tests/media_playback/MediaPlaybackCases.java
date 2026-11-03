package org.telegram.messenger.purple;

/**
 * Host cases for the media playback features (docs/media-design.md), run on
 * the real AGP classes. Each program prints one KEY=N completion record.
 */
public final class MediaPlaybackCases {

    private static int checks;

    private MediaPlaybackCases() {
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        final String program = args[0];
        switch (program) {
        case "restart":
            restart();
            System.out.println("MEDIA_RESTART_CHECKS=" + checks);
            break;
        default:
            throw new IllegalArgumentException("unknown program " + program);
        }
    }

    /** The double-tap timing every tap surface shares. */
    static void doubleTap() {
        final PurpleDoubleTap taps = new PurpleDoubleTap();
        check(!taps.tap(0, 7, 1, 1000, 300), "a first tap is not a double tap");
        check(taps.tap(0, 7, 1, 1300, 300), "a second tap at the timeout is a double tap");
        check(!taps.tap(0, 7, 1, 1350, 300), "a third tap starts a new pair");
        check(taps.tap(0, 7, 1, 1400, 300), "and the fourth completes it");
        check(!taps.tap(0, 7, 1, 2000, 300), "first of a new pair");
        check(!taps.tap(0, 7, 1, 2301, 300), "one millisecond late is a first tap again");
        check(taps.tap(0, 7, 1, 2302, 300), "which the next tap completes");
        check(!taps.tap(0, 7, 1, 3000, 300), "first on message 1");
        check(!taps.tap(0, 7, 2, 3100, 300), "a tap on another message is a first tap");
        check(!taps.tap(0, 7, 1, 3200, 300), "and resets the pair for message 1");
        check(!taps.tap(0, 8, 1, 3250, 300), "the same id in another dialog is another message");
        check(!taps.tap(1, 8, 1, 3300, 300), "the same id and dialog in another account is another message");
        check(taps.tap(1, 8, 1, 3400, 300), "the same message completes");
        check(!taps.tap(0, 7, 1, 5000, 300), "first");
        check(!taps.tap(0, 7, 1, 4000, 300), "a clock that went back is not a double tap");
        check(!taps.tap(0, 7, 1, 6000, 300), "first");
        taps.reset();
        check(!taps.tap(0, 7, 1, 6100, 300), "a reset forgets the first tap");
        check(taps.tap(0, 7, 1, 6200, 300), "and the tap after it pairs again");
    }

    static void restart() {
        doubleTap();
        check(PurpleMediaRestart.imageTapCounts(true, -1), "a downloaded round's circle counts");
        for (int state = 0; state <= 4; state++) {
            check(!PurpleMediaRestart.imageTapCounts(true, state), "a round with button " + state + " goes on to didPressButton");
        }
        check(!PurpleMediaRestart.imageTapCounts(false, -1), "a photo or video tap does not count");
        check(PurpleMediaRestart.buttonCounts(true, 0), "play counts");
        check(PurpleMediaRestart.buttonCounts(true, 1), "pause counts");
        for (int state : new int[] {-1, 2, 3, 4}) {
            check(!PurpleMediaRestart.buttonCounts(true, state), "button state " + state + " has nothing to seek");
        }
        check(!PurpleMediaRestart.buttonCounts(false, 0), "music and files do not count");
        check(PurpleMediaRestart.restarts(true, false), "the playing message restarts");
        check(!PurpleMediaRestart.restarts(false, false), "another message does not");
        check(!PurpleMediaRestart.restarts(true, true), "a view-once message does not");
        // didClickedImage hands a round with a button on to didPressButton, so
        // one tap passes both entries. Replay that: each tap must be recorded
        // at most once, or the first tap alone would look like a double tap.
        check(recordedPerTap(3) == 0, "a round still downloading is never recorded");
        check(recordedPerTap(-1) == 1, "a downloaded round is recorded once, by the image entry");
        check(recordedPerTap(1) == 1, "a transcribed round is recorded once, by the button entry");
        final PurpleDoubleTap taps = new PurpleDoubleTap();
        boolean second = false;
        for (int tap = 0; tap < 2; tap++) {
            if (PurpleMediaRestart.imageTapCounts(true, 1)) second = taps.tap(0, 1, 1, 200 + tap, 300);
            if (PurpleMediaRestart.buttonCounts(true, 1)) second = taps.tap(0, 1, 1, 200 + tap, 300);
        }
        check(second, "two taps through both entries pair on the second tap");
    }

    private static int recordedPerTap(int buttonState) {
        int recorded = 0;
        if (PurpleMediaRestart.imageTapCounts(true, buttonState)) recorded++;
        if (buttonState != -1 && PurpleMediaRestart.buttonCounts(true, buttonState)) recorded++;
        return recorded;
    }
}
