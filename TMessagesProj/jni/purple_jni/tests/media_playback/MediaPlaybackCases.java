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
        case "seek":
            seek();
            System.out.println("MEDIA_SEEK_CHECKS=" + checks);
            break;
        case "seekcore":
            seekCore();
            System.out.println("MEDIA_SEEK_CORE_CHECKS=" + checks);
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

    /** F9's seek targets, step validation and labels, without a player. */
    static void seek() {
        final long min = 60_000;
        // Back and forward by every step from the middle of a 10-minute message.
        for (int step : PurpleSeek.STEPS) {
            check(PurpleSeek.validStep(step) == step, step + " s is a valid step");
            check(PurpleSeek.targetMs(5 * min, 10 * min, -1, step) == 5 * min - step * 1000L, "back " + step + " s");
            check(PurpleSeek.targetMs(5 * min, 10 * min, 1, step) == 5 * min + step * 1000L, "forward " + step + " s");
            check(PurpleSeek.label(-1, step).equals("\u2212" + step), "the back label for " + step);
            check(PurpleSeek.label(1, step).equals("+" + step), "the forward label for " + step);
        }
        for (int bad : new int[] {-10, 0, 1, 20, 60, Integer.MAX_VALUE}) {
            check(PurpleSeek.validStep(bad) == PurpleCore.SEEK_STEP_DEFAULT, bad + " falls back to the default step");
        }
        check(PurpleCore.SEEK_STEP_DEFAULT == 10, "the default step is 10 s");
        // Before the start goes to 0:00.
        check(PurpleSeek.targetMs(4_000, 60_000, -1, 10) == 0, "back from 0:04 goes to 0:00");
        check(PurpleSeek.targetMs(10_000, 60_000, -1, 10) == 0, "back from exactly one step goes to 0:00");
        check(PurpleSeek.targetMs(0, 60_000, -1, 10) == -1, "back at 0:00 seeks nothing");
        // Past the end stops one second before it.
        check(PurpleSeek.targetMs(55_000, 60_000, 1, 10) == 59_000, "forward near the end stops a second before it");
        check(PurpleSeek.targetMs(49_000, 60_000, 1, 10) == 59_000, "forward to exactly a second before the end");
        check(PurpleSeek.targetMs(48_999, 60_000, 1, 10) == 58_999, "forward short of the margin is a full step");
        check(PurpleSeek.targetMs(59_000, 60_000, 1, 10) == -1, "forward at the margin seeks nothing");
        check(PurpleSeek.targetMs(59_500, 60_000, 1, 10) == -1, "forward inside the last second stays put");
        check(PurpleSeek.targetMs(59_500, 60_000, -1, 10) == 49_500, "back from the last second is a full step");
        check(PurpleSeek.targetMs(60_000, 60_000, -1, 30) == 30_000, "back from the end");
        // Short messages.
        check(PurpleSeek.targetMs(0, 3_000, 1, 10) == 2_000, "forward on a 3 s message stops at 0:02");
        check(PurpleSeek.targetMs(0, 800, 1, 10) == -1, "forward on a message under a second seeks nothing");
        check(PurpleSeek.targetMs(500, 800, -1, 5) == 0, "back on a message under a second goes to 0:00");
        // Positions outside the message are clamped first.
        check(PurpleSeek.targetMs(-5_000, 60_000, 1, 10) == 10_000, "a negative position counts as 0:00");
        check(PurpleSeek.targetMs(90_000, 60_000, -1, 10) == 50_000, "a position past the end counts as the end");
        // Nothing to seek.
        check(PurpleSeek.targetMs(5_000, 0, 1, 10) == -1, "an unknown duration seeks nothing");
        check(PurpleSeek.targetMs(5_000, -1, -1, 10) == -1, "a negative duration seeks nothing");
        check(PurpleSeek.targetMs(5_000, 60_000, 0, 10) == -1, "no direction seeks nothing");
        check(PurpleSeek.targetMs(5_000, 60_000, -7, 10) == 0, "any negative direction is back");
        check(PurpleSeek.targetMs(5_000, 60_000, 7, 10) == 15_000, "any positive direction is forward");
        // A long message does not overflow.
        final long day = 24L * 3600 * 1000;
        check(PurpleSeek.targetMs(day, 2 * day, 1, 30) == day + 30_000, "a day-long position");
        // The media session's added actions are rewind and fast-forward only.
        check(PurpleSeek.SESSION_ACTIONS == (64L | 8L), "the session adds ACTION_FAST_FORWARD and ACTION_REWIND");
        check(!PurpleSeek.seekButtons(true), "music keeps shuffle and repeat: no seek custom actions");
        check(PurpleSeek.seekButtons(false), "voice and round get the seek custom actions");
        // The session's custom actions map to a direction; anything else is upstream's.
        check(PurpleSeek.direction(PurpleSeek.ACTION_BACK) == -1, "the back custom action seeks back");
        check(PurpleSeek.direction(PurpleSeek.ACTION_FORWARD) == 1, "the forward custom action seeks forward");
        check(PurpleSeek.direction("org.telegram.messenger.NOTIFY_SHUFFLE") == 0, "shuffle is not Purple's");
        check(PurpleSeek.direction(null) == 0, "no action is not Purple's");
        check(!PurpleSeek.ACTION_BACK.equals(PurpleSeek.ACTION_FORWARD), "the two actions differ");
        // The Seek step row's choices and how it spells them.
        check(java.util.Arrays.equals(PurpleSeek.choices(), new int[] {5, 10, 15, 30}), "the row offers 5, 10, 15 and 30 s");
        PurpleSeek.choices()[0] = 99;
        check(PurpleSeek.choices()[0] == 5, "the row's choices are a copy");
        check(PurpleSeek.settingValue(15).equals("15s"), "15 s is written as \"15s\"");
        check(PurpleSeek.settingValue(7).equals("10s"), "an invalid step is written as the default");
        stepSeam();
    }

    /** A player for step(): a live position, a duration, and the seeks it was asked for. */
    private static final class StubPlayer implements PurpleSeek.Player {
        long position;
        final long duration;
        final boolean takes;
        final java.util.List<Long> seeks = new java.util.ArrayList<>();

        StubPlayer(long position, long duration, boolean takes) {
            this.position = position;
            this.duration = duration;
            this.takes = takes;
        }

        @Override
        public long positionMs() {
            return position;
        }

        @Override
        public long durationMs() {
            return duration;
        }

        @Override
        public boolean seekMs(long ms) {
            seeks.add(ms);
            if (takes) position = ms;
            return takes;
        }
    }

    /**
     * step() reads the live position each press and seeks in milliseconds: a
     * paused round that does not update its cached progress still moves on
     * every press (review P2-1).
     */
    static void stepSeam() {
        final StubPlayer paused = new StubPlayer(5_000, 40_000, true);
        check(PurpleSeek.step(1, paused, 10), "a paused round seeks forward");
        check(PurpleSeek.step(1, paused, 10), "and again");
        check(paused.seeks.equals(java.util.Arrays.asList(15_000L, 25_000L)), "two presses are two steps: " + paused.seeks);
        check(PurpleSeek.step(1, paused, 10), "a third press");
        check(paused.position == 35_000, "lands at 0:35");
        check(PurpleSeek.step(1, paused, 10) && paused.position == 39_000, "the fourth stops a second before the end");
        check(!PurpleSeek.step(1, paused, 10), "the fifth seeks nothing");
        check(paused.seeks.size() == 4, "and asks the player nothing");
        check(PurpleSeek.step(-1, paused, 30) && paused.position == 9_000, "back 30 s from 0:39");
        check(PurpleSeek.step(-1, paused, 30) && paused.position == 0, "back again goes to 0:00");
        check(!PurpleSeek.step(-1, paused, 30), "back at 0:00 seeks nothing");

        final StubPlayer idle = new StubPlayer(-1, 40_000, true);
        check(!PurpleSeek.step(1, idle, 10) && idle.seeks.isEmpty(), "no player position seeks nothing");
        final StubPlayer unknown = new StubPlayer(5_000, 0, true);
        check(!PurpleSeek.step(1, unknown, 10) && unknown.seeks.isEmpty(), "an unknown duration seeks nothing");
        final StubPlayer refused = new StubPlayer(5_000, 40_000, false);
        check(!PurpleSeek.step(-1, refused, 5), "a player that refuses the seek reports no seek");
        check(refused.seeks.equals(java.util.Collections.singletonList(0L)), "after being asked for 0:00");
        // The player's duration, not the attribute's: a 300 s file tagged 600 s
        // steps by the full step and keeps a full second's margin.
        final StubPlayer music = new StubPlayer(295_000, 300_000, true);
        check(PurpleSeek.step(1, music, 10) && music.position == 299_000, "the end margin is a second of the real duration");
        final StubPlayer start = new StubPlayer(100_000, 300_000, true);
        check(PurpleSeek.step(1, start, 10) && start.position == 110_000, "a full 10 s step");
    }

    /**
     * The real seekStepSeconds bridge over the real core (F9): each allowed
     * step, the default, and values the core refuses, read from a loaded
     * settings.toml.
     */
    static void seekCore() throws Exception {
        final String[] files = {
            "",
            "[playback]\nseek_step = \"5s\"\n",
            "[playback]\nseek_step = \"10s\"\n",
            "[playback]\nseek_step = \"15s\"\n",
            "[playback]\nseek_step = \"30s\"\n",
            "[playback]\nseek_step = \"20s\"\n",
            "[playback]\nseek_step = \"1m\"\n",
            "[playback]\nseek_step = \"abc\"\n",
            "[playback]\nseek_step = 15\n",
            "[playback]\nprivate_chat_queue_p = false\nseek_step = \"30s\"\n",
        };
        final int[] steps = {10, 5, 10, 15, 30, 10, 10, 10, 10, 30};
        final boolean[] warns = {false, false, false, false, false, true, true, true, true, false};
        for (int f = 0; f < files.length; f++) {
            final PurpleCore.Loaded loaded = PurpleCore.load(files[f].getBytes("UTF-8"), null, "fixture", "android", "mobile");
            check(loaded.ok, "settings file " + f + " loads");
            check(PurpleCore.seekStepSeconds() == steps[f], "file " + f + " gives a step of " + steps[f]);
            check(PurpleSeek.stepSeconds() == steps[f], "PurpleSeek reads the same step from file " + f);
            boolean warned = false;
            for (String warning : loaded.warnings) {
                warned |= warning.contains("seek_step");
            }
            check(warned == warns[f], "file " + f + (warns[f] ? " warns" : " does not warn") + " about seek_step");
            check(PurpleCore.voiceQueueKeeps(PurpleCore.PLAYBACK_ONE_TO_ONE, false, true) == (f == files.length - 1),
                    "file " + f + " reads the queue filter alongside the step");
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
