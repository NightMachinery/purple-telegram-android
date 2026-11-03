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
        case "core":
            core();
            System.out.println("MEDIA_QUEUE_CORE_CHECKS=" + checks);
            break;
        case "queue":
            queue();
            System.out.println("MEDIA_QUEUE_CHECKS=" + checks);
            break;
        case "pip":
            pip();
            System.out.println("MEDIA_PIP_CHECKS=" + checks);
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
        // A round without an open transcription reads 0 and 1 as download and
        // cancel; onButton passes voiceOrRound=false for it, so it never counts.
        check(!PurpleMediaRestart.buttonCounts(false, 0) && !PurpleMediaRestart.buttonCounts(false, 1), "a loading round's download and cancel do not count");
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

    static void pip() {
        doubleTap();
        // action(longPress, second, exists, logEvent, sameAccount, chatOnTop)
        check(PurpleRoundPip.action(false, false, true, false, true, true) == PurpleRoundPip.TOGGLE, "a single tap keeps upstream's toggle");
        check(PurpleRoundPip.action(false, true, true, false, true, true) == PurpleRoundPip.RESTART, "a double tap restarts, as in the chat");
        check(PurpleRoundPip.action(false, true, true, false, false, false) == PurpleRoundPip.RESTART, "whatever screen is on top");
        check(PurpleRoundPip.action(false, true, true, true, true, true) == PurpleRoundPip.RESTART, "an admin-log round restarts too");
        check(PurpleRoundPip.action(false, true, false, false, true, true) == PurpleRoundPip.TOGGLE, "a finished or deleted message does not restart");
        check(PurpleRoundPip.action(true, false, true, false, true, true) == PurpleRoundPip.SCROLL, "a long press scrolls when the chat is on top");
        check(PurpleRoundPip.action(true, false, true, false, true, false) == PurpleRoundPip.OPEN, "and opens the chat otherwise");
        check(PurpleRoundPip.action(true, true, true, false, true, false) == PurpleRoundPip.OPEN, "a long press never restarts");
        check(PurpleRoundPip.action(true, false, false, false, true, false) == PurpleRoundPip.TOGGLE, "a deleted or finished message opens nothing");
        check(PurpleRoundPip.action(true, false, false, false, true, true) == PurpleRoundPip.TOGGLE, "not even in its own chat");
        check(PurpleRoundPip.action(true, false, true, false, false, false) == PurpleRoundPip.TOGGLE, "another account on top opens nothing");
        check(PurpleRoundPip.action(true, false, true, false, false, true) == PurpleRoundPip.TOGGLE, "nor scrolls a chat of another account");
        check(PurpleRoundPip.action(true, false, true, true, true, false) == PurpleRoundPip.TOGGLE, "an admin-log round has no message to open");
        // At playback start, off the visible list: a round goes to the PiP,
        // except that an automatic advance follows the queue while it is on
        // screen. (Only messagePlayingDidStart asks; see P2-1 in the review.)
        check(PurpleRoundPip.leavesToPip(true, false, false), "a round starting far from the previous one floats");
        check(PurpleRoundPip.leavesToPip(true, false, true), "whatever is on screen");
        check(!PurpleRoundPip.leavesToPip(true, true, true), "an advance you are following scrolls, as upstream");
        check(PurpleRoundPip.leavesToPip(true, true, false), "an advance after you scrolled away floats, no scroll");
        for (int i = 0; i < 4; i++) {
            check(!PurpleRoundPip.leavesToPip(false, (i & 1) != 0, (i & 2) != 0), "an ordinary video keeps upstream's choice " + i);
        }
        // Taps on the floating round and on the chat are timed apart: a tap in
        // the chat and then one on the PiP are not a double tap.
        final PurpleDoubleTap chat = new PurpleDoubleTap();
        final PurpleDoubleTap floating = new PurpleDoubleTap();
        check(!chat.tap(0, 5, 9, 100, 300), "a tap in the chat");
        check(!floating.tap(0, 5, 9, 150, 300), "then one on the PiP is a first tap there");
        check(floating.tap(0, 5, 9, 250, 300), "and the next PiP tap completes the PiP pair");
    }

    /**
     * The real voiceQueueKeeps bridge over the real core: every chat kind,
     * direction and setting, read from a loaded settings.toml.
     */
    static void core() throws Exception {
        final String[] files = {
            "",
            "[playback]\nprivate_chat_queue_p = true\n",
            "[playback]\nprivate_chat_queue_p = false\n",
            "[playback]\nprivate_chat_queue_p = \"yes\"\n",
        };
        final boolean[] confines = {true, true, false, true};
        for (int f = 0; f < files.length; f++) {
            final PurpleCore.Loaded loaded = PurpleCore.load(files[f].getBytes("UTF-8"), null, "fixture", "android", "mobile");
            check(loaded.ok, "settings file " + f + " loads");
            for (int chat : new int[] {PurpleCore.PLAYBACK_ONE_TO_ONE, PurpleCore.PLAYBACK_SELF, PurpleCore.PLAYBACK_OTHER}) {
                for (int start = 0; start < 2; start++) {
                    for (int candidate = 0; candidate < 2; candidate++) {
                        final boolean confined = confines[f] && chat == PurpleCore.PLAYBACK_ONE_TO_ONE && start == 0;
                        final boolean expected = !confined || candidate == 0;
                        check(PurpleCore.voiceQueueKeeps(chat, start == 1, candidate == 1) == expected,
                                "file " + f + " chat " + chat + " start " + start + " candidate " + candidate);
                    }
                }
            }
            for (int chat : new int[] {-1, 3, 99}) {
                check(PurpleCore.voiceQueueKeeps(chat, false, true), "an unknown chat kind " + chat + " keeps everything");
            }
        }
    }

    /** A stand-in for the core's rule with the setting on or off; the core program runs the real one. */
    private static PurpleVoiceQueue.Rule rule(final boolean on, final int[] calls) {
        return (chat, startOutgoing, candidateOutgoing) -> {
            calls[0]++;
            final boolean confined = on && chat == PurpleCore.PLAYBACK_ONE_TO_ONE && !startOutgoing;
            return !confined || !candidateOutgoing;
        };
    }

    static void queue() {
        final int[] calls = {0};
        final PurpleVoiceQueue.Rule on = rule(true, calls);
        final PurpleVoiceQueue.Rule off = rule(false, calls);
        final int one = PurpleCore.PLAYBACK_ONE_TO_ONE;

        check(PurpleVoiceQueue.chatKind(false, false) == one, "a user or bot chat is one-to-one");
        check(PurpleVoiceQueue.chatKind(true, false) == PurpleCore.PLAYBACK_SELF, "Saved Messages is Self");
        check(PurpleVoiceQueue.chatKind(true, true) == PurpleCore.PLAYBACK_SELF, "Self wins over service");
        check(PurpleVoiceQueue.chatKind(false, true) == PurpleCore.PLAYBACK_OTHER, "a service chat is Other");

        // A manual play on their message in a one-to-one chat confines.
        PurpleVoiceQueue.forget();
        PurpleVoiceQueue.Anchor anchor = PurpleVoiceQueue.anchorFor(0, 42, 10, one, false, true, on);
        check(anchor.confined, "their message confines");
        check(anchor.skips(true), "your next message is skipped");
        check(!anchor.skips(false), "theirs plays");
        check(PurpleVoiceQueue.skipsAppended(0, 42, true), "your new message is not appended");
        check(!PurpleVoiceQueue.skipsAppended(0, 42, false), "their new message is");
        check(!PurpleVoiceQueue.skipsAppended(0, 43, true), "another dialog is not confined");
        check(!PurpleVoiceQueue.skipsAppended(1, 42, true), "nor the same dialog id in another account");

        // Later visits of the same build keep the anchor; the setting is read
        // when the anchor is made, so turning it off mid-queue changes nothing
        // until the next manual play.
        check(PurpleVoiceQueue.anchorFor(0, 42, 10, one, false, false, off) == anchor, "a later candidate keeps the anchor");
        check(PurpleVoiceQueue.anchorFor(0, 42, 10, one, false, false, off).confined, "still confined after a toggle");
        check(PurpleVoiceQueue.skipsAppended(0, 42, true), "and the live append still drops yours after a toggle");
        // The next manual play, even on the same message, re-reads it.
        anchor = PurpleVoiceQueue.anchorFor(0, 42, 10, one, false, true, off);
        check(!anchor.confined, "the next manual play reads the switch off");
        check(!anchor.skips(true), "and keeps your messages");
        check(!PurpleVoiceQueue.skipsAppended(0, 42, true), "and appends them");

        // A manual play on your own message never confines.
        anchor = PurpleVoiceQueue.anchorFor(0, 42, 11, one, true, true, on);
        check(!anchor.confined, "your own message does not confine");
        check(!anchor.skips(true) && !anchor.skips(false), "both directions play");
        // Saved Messages, groups and channels never confine.
        for (int chat : new int[] {PurpleCore.PLAYBACK_SELF, PurpleCore.PLAYBACK_OTHER}) {
            anchor = PurpleVoiceQueue.anchorFor(0, 44, 12, chat, false, true, on);
            check(!anchor.confined, "chat kind " + chat + " does not confine");
            check(!PurpleVoiceQueue.skipsAppended(0, 44, true), "chat kind " + chat + " appends everything");
        }
        // A play in another chat moves the anchor even without the start visit.
        PurpleVoiceQueue.anchorFor(0, 42, 10, one, false, true, on);
        anchor = PurpleVoiceQueue.anchorFor(0, 50, 3, one, false, false, on);
        check(anchor.dialogId == 50 && anchor.confined, "a new start in another chat re-anchors");
        check(!PurpleVoiceQueue.skipsAppended(0, 42, true), "and the old chat is free again");

        // A whole build, as createVoiceMessagesPlaylist runs it: oldest first,
        // the started message among them, upstream's filter after the hook.
        PurpleVoiceQueue.forget();
        final boolean[] out = {false, true, false, true, true, false, false};
        final java.util.List<Integer> queue = new java.util.ArrayList<>();
        final int start = 2;
        queue.add(start);
        for (int id = 0; id < out.length; id++) {
            final PurpleVoiceQueue.Anchor a = PurpleVoiceQueue.anchorFor(0, 77, start, one, out[start], id == start, on);
            if (a.skips(out[id])) continue;
            if (id > start) queue.add(id);
        }
        check(queue.equals(java.util.Arrays.asList(2, 5, 6)), "their later messages only: " + queue);
        PurpleVoiceQueue.forget();
        queue.clear();
        queue.add(1);
        for (int id = 0; id < out.length; id++) {
            final PurpleVoiceQueue.Anchor a = PurpleVoiceQueue.anchorFor(0, 77, 1, one, out[1], id == 1, on);
            if (a.skips(out[id])) continue;
            if (id > 1) queue.add(id);
        }
        check(queue.equals(java.util.Arrays.asList(1, 2, 3, 4, 5, 6)), "started on yours, everything: " + queue);
        PurpleVoiceQueue.forget();
        check(!PurpleVoiceQueue.skipsAppended(0, 77, true), "no anchor, no confinement");
        // The rule is asked twice per manual play, never per candidate.
        int before = calls[0];
        anchor = PurpleVoiceQueue.anchorFor(0, 78, 1, one, false, true, on);
        check(calls[0] - before == 2, "a manual play asks the rule twice: " + (calls[0] - before));
        before = calls[0];
        for (int i = 0; i < 100; i++) anchor.skips(i % 2 == 0);
        for (int i = 0; i < 100; i++) PurpleVoiceQueue.skipsAppended(0, 78, i % 2 == 0);
        check(calls[0] == before, "candidates and appends make no core calls");
    }
}
