# Review: media F1-F3 on Android (branch `media`)

Scope: `0bd0336..3be897da` (5 commits: F2 restart, F1 round PiP, core bump with the
`voiceQueueKeeps` bridge, F3 private-chat voice queue, the F1 needScroll follow-up).
Read against AGENTS.md, `docs/media-design.md` with its coordinator answers, and the
owner decisions since the design (F1: the floating round keeps playing when another
chat opens, no jump to an auto-played next message when scrolled away; Desktop float
double-click restarts; F5 "Actual size" is Desktop only).

Read-only for the code. Nothing was built. Static checks run here:

- `purple/upstream-hooks.sh check`: OK for the tree.
- `python3 purple/run_host_suites.py --list`: the list and the drivers agree
  (37 configurations, the new `media-playback` suite included).
- The purple-core submodule is not populated in this VM and its repository is outside
  this session's scope, so `purple_playback.{h,cpp}` was not read. The bridge and the
  suite's `core` program are reviewed as written; the rule itself is unverified here.

Verdict: CHANGES. No P1 survived a trace. Two P2s: one F1 claim the code cannot deliver,
and one owner question that the Desktop decision opens on Android.

## P1

None. Each candidate below was traced and either fell to P2/P3 or was cleared (see
"Checked and cleared").

## P2

### P2-1. "A jump away floats the round" is not wired; the hook only runs when playback starts

Files: `ui/ChatActivity.java` (`updateTextureViewPosition`), `ui/PurpleChatHooks.java`
(`roundLeavesToPip`), README "Media playback", commit messages of e1da660f and 3be897da.

The README says: "Now a jump away floats the round" (reply quote, search result,
scroll-to-bottom), and e1da660f says a round "that leaves the list for any reason
(reply quote, search result, scroll-to-bottom) continues in the PiP".

Trace:

1. The hook term sits inside `if (needVisibleUpdate && videoPlayerContainer != null)` in
   `updateTextureViewPosition`.
2. The only call with `needVisibleUpdate == true` is the `messagePlayingDidStart` branch
   of `didReceivedNotification` (`updateTextureViewPosition(true, needScroll)`). Every
   other call site (five of them, including the scroll and layout paths) passes
   `(false, false)` and skips the block.
3. A reply-quote tap, a search result or the scroll-to-bottom button calls
   `scrollToMessageId` while the round keeps playing. No `messagePlayingDidStart` is
   posted, so `roundLeavesToPip` is never asked.
4. What happens after a jump is still upstream's: `ChatScrollCallback.onEndAnimation`
   sets `checkTextureViewPosition = true`, and the PiP appears only if a later
   `updateMessagesVisiblePart` runs with no fast-scroll animation running.

So F1 delivers its real change only at playback start: an automatic advance to a round
within five messages, made while you are scrolled away, now plays in the PiP instead of
scrolling the chat. That matches the owner's "no jump to an auto-played next message".
The jump-away half is either already upstream's behaviour (then the README credits
Purple for it) or still broken (then the owner hits a round playing with its picture
off screen after a reply jump, and this branch does not fix it).

Fix: correct the README and the `PurpleChatHooks.roundLeavesToPip` Javadoc to say the
hook decides at playback start only. Then add "reply-quote jump while a round plays"
to the device checklist. If that check shows no PiP after the jump, the hook belongs in
`updateMessagesVisiblePart`'s `checkTextureViewPosition && !isFastScrollAnimationRunning()`
term, which is a separate change. The `pip` program's "a jump away from a playing round
floats it" check tests the pure function, not this wiring, so it passes either way.

### P2-2. Android PiP double-tap opens the message; Desktop's float double-click now restarts

Files: `messenger/purple/PurpleRoundPip.java`, README "The floating round".

The design justified the Android PiP double-tap as "This mirrors Desktop's float". The
owner has since changed Desktop: float double-click restarts (F2 wins), "go to message"
becomes a hover button, and drag stays. Android still opens the message, so the same
gesture on the same floating round now does different things on the two clients. On
Android it also differs from the in-chat round, where a double-tap restarts.

This is not a code defect. The owner needs to decide whether Android follows Desktop
(double-tap restarts; going to the message needs another affordance, such as a
long-press, since the PiP has no hover) or keeps "open". Either answer is small: the
`action()` switch already separates SCROLL/OPEN from the toggle, and
`PurpleMediaRestart` has the seek-and-resume step.

## P3

### P3-1. F2 counts a non-transcribed round's download and cancel states as play and pause (PLAUSIBLE)

`PurpleMediaRestart.buttonCounts` accepts `buttonState` 0 and 1 for any voice or round.
For a round without an open transcription, `didPressButton` reads 0 as "download" and 1
as "cancel loading" (`buttonState == 1 && ...` → `cancelLoadImage`, state back to 0).
`didClickedImage` sends any round with `buttonState != -1` there. While a round streams
(it is the playing message, `isPlayingMessage` true, cell still loading), a double-tap
does upstream's cancel on the first tap and then a seek to 0 on the second, which
Purple swallows. The result is a restarted round whose download was cancelled. The
design said a not-downloaded round declines. The suite's `recordedPerTap(1)` case is
labelled "a transcribed round" but the same state covers the loading round. Fix: in
`onButton`, count a round only when `message.isVoiceTranscriptionOpen()`.

### P3-2. "On screen" includes rows behind the top panel and the input field, and upstream still recentres a laid-out next round

`queueOnScreen` walks `getChildAt` of the chat list. Rows under the pinned/top panel or
behind the input field (the list draws under its padding) are laid-out children, so a
small scroll away still counts as "following". Separately, upstream's
`messagePlayingDidStart` loop centres a playing round that is a laid-out child
(`chatLayoutManager.scrollToPositionWithOffset`). A next round peeking under the input
field therefore still pulls the chat. The owner may read that as the jump they asked
to remove. Device check first. If it bites, compare against the visible bounds
(`chatListViewPaddingTop`, `blurredViewBottomOffset`) rather than child presence.

### P3-3. Turning the switch off mid-queue changes the live append, against the Javadoc and the test comment

`Anchor.skips` asks the core again on every call. After you turn
`private_chat_queue_p` off during a confined queue, `skipsAppended` returns false for
your new messages, so they join the queue. The class Javadoc says "a toggle takes
effect from the next manual play, never in the middle of a queue". The `queue` program's
comment says the same, but it checks only `anchor.confined`, never `skips` under the
new rule. The behaviour is harmless and arguably better, but code, doc and test should
agree. Simplest fix: cache `keepsOutgoing` in the anchor when it is made.

### P3-4. A confined build calls the core once per loaded message

The hook runs before upstream's filter, so while a queue is confined every message in
`messages` (text, photos, older messages, incoming ones) costs one JNI call that takes
`gate.mutex` on the UI thread. That can be several hundred calls per tap, where the
design estimated "tens". If a background thread holds the gate mutex, the tap stalls.
Return early in `skipsInPlaylist` for a candidate that is not outgoing, or not a voice
or round message. The rule keeps all incoming ones anyway.

### P3-5. A PiP round from the admin log opens the channel at an event message id

`ChannelAdminLogActivity` plays rounds with `eventId != 0`. `PurpleRoundPip.onTap` does
not check `eventId`, so a double-tap on that PiP opens the channel at the logged
message's id. That message can be a deleted one, since the log shows deletions. Treat
`message.eventId != 0` as TOGGLE.

### P3-6. The design doc's Android F1 section is stale

`docs/media-design.md` still describes the "chat pulls you back" gap and the
`roundLeavesToPip()` signature with no arguments. 3be897da corrected the README, not
the design. Add a one-line note under "Android: current behaviour" pointing to the
README, so the next reader does not re-derive the old claim.

### P3-7. purple-core is pinned to a branch head

ef7c1fa9 moves the submodule to "its media branch head" (`6d0bd81b`). Before `media`
merges, make sure that commit is reachable from a purple-core branch that will not be
rebased, or the gitlink dangles for every later clone. This could not be checked here.

## Owner decision: the floating round closing when another chat opens

The implementer could not reproduce it. I agree that no upstream path closes a round's
PiP just because a chat opens:

- `ChatActivity`'s `onAttachedToWindow` hands over the texture only when the playing
  message is in that chat (`getDialogId() == dialog_id`).
- `setTextureView(..., true)` keeps an existing PiP (`videoPlayer.setTextureView(pip)`).
- `onRemoveFromParent` stops only non-round videos (`isVideo()` is false for a round,
  since `isVideoDocument` returns false on `round_message`).
- The F1 term cannot close a PiP. It only ever calls `setCurrentVideoVisible(false)`,
  and the condition short-circuits on `PipRoundVideoView.getInstance() != null`.

These upstream paths do close it, and any of them can look like "it closed when I
opened a chat". They are worth a device pass:

1. The queue advances from a round to a voice message. `cleanupPlayer` closes the PiP
   when the next item is not a round. With F3 confining the queue, which message is
   "next" changes.
2. Any other playback starts, such as a voice in the new chat or music. `playMessage`'s
   audio branch closes the PiP.
3. A fling to the edge, deleting the message, or the queue ending (`messagePlayingDidReset`).
4. The activity is recreated (theme or configuration change), which drops the PiP window.

So the keep-playing decision holds in code today. If the owner can still reproduce the
close, ask which of these four preceded it.

## Checked and cleared

- Hook shape. ChatMessageCell (2 hooks), PipRoundVideoView (1) and MediaController (1)
  use fully qualified names. ChatActivity routes both hooks through `purple`. Each hook
  is one guarded call; the one widened line is documented in the README. Baseline
  numbers match the diff. Row id `0x70750027` is unique and in the reserved range.
- Setting off, or core unavailable. `skipsInPlaylist` and `skipsAppended` return false,
  so upstream's queue runs unchanged. The settings row reads the same gate the bridge
  reads, writes through `setTableBool("playback", ...)` with an app-wide session like
  the suggestions row, and rebuilds after the write.
- F3 build order. `messages` is iterated oldest first, so the start is visited before
  every later candidate in normal and secret chats. Older candidates that hit a stale
  anchor are dropped by upstream's id filter anyway. Merged (migrated) messages are
  skipped before the hook.
- Double-tap counting. One tap reaches at most one entry: `didClickedImage` records
  only `buttonState == -1`, and anything else is handed to `didPressButton`. The PiP has
  its own `PurpleDoubleTap`, and taps are keyed by account, dialog and id.
- PiP undo and open route. The undo mirrors upstream's toggle (`isMessagePaused` →
  `playMessage`, else `pauseMessage`), and the drag reset and edge snap still run. The
  open route matches `FragmentContextView` exactly and adds an account check, so no
  new Work Mode bypass appears.
- Null and leak paths. All entries null-check their message. No static field holds a
  `MessageObject` or a view; the statics are ids and timestamps. All callers run on the
  UI thread, and the shared state is `synchronized`.
- JNI. The symbol name matches `PurpleCore.voiceQueueKeepsNative(int, boolean, boolean)`.
  Out-of-range chat kinds keep everything. Java catches `UnsatisfiedLinkError` and
  `RuntimeException` and keeps everything.
- Privacy. The diff has no personal paths, hosts, emails or identifiers.
- Tests. The new suite is listed with floors, its driver makes its own `javac` call and
  pins all six hook lines plus both helpers. Missing, and acceptable as GUI checks:
  `queueOnScreen`, the `onTap` wiring, and the jump in P2-1.

## Device checklist this review adds

- Reply-quote jump while a round plays in-chat: does the PiP appear (P2-1)?
- Voice, then a round within five messages, after scrolling far up: PiP, no jump. After
  scrolling a little, so the round sits under the input field: does the chat recentre (P3-2)?
- Double-tap a round that is still streaming (P3-1).
- PiP showing, then open another chat from the list and wait for the queue to advance
  round to round, and round to voice (owner F1 report).

## Re-check 1

Scope: `3be897da..39ad300e` (one commit, "Restart the floating round on double-tap and
fix the media review points"). I verified each fix by trace and looked only for
regressions it introduces, with most attention on the PiP touch path. Static checks on
`39ad300e`: `purple/upstream-hooks.sh check` is OK (the ratchet is unchanged, and the
PiP hook is still one fully qualified line); `run_host_suites.py --list` is consistent.
The suite floors rose with the new checks (pip 40, queue 28, restart 37). Nothing was
built or run.

Verdict: APPROVE. P1=0, P2=0. P3-2 is left for the device check, as agreed.

### Fixes

- **P2-1, fixed by dropping the claim.** The README, the `roundLeavesToPip` Javadoc and
  the test labels now say the hook decides only at playback start. They also say a
  reply/search/scroll-to-bottom jump stays upstream's behaviour and is on the device
  checklist. That matches the trace (the one `updateTextureViewPosition(true, …)`
  caller is `messagePlayingDidStart`). The code is unchanged.
- **P2-2, fixed (owner: F2 wins).** `PurpleRoundPip.action` now gives:
  - a short second tap on a message that is still playing: RESTART, through the
    shared `PurpleMediaRestart.restart`;
  - a long press: SCROLL or OPEN by the player bar's route;
  - anything else: TOGGLE.

  The old "undo the first tap" step is gone. Its job now falls to the restart: tap 1
  pauses (upstream), and on tap 2 `seekToProgress(…, 0)` succeeds, `isMessagePaused()`
  is true, and `playMessage` resumes the same message from 0:00. If the seek fails,
  `onTap` returns false and upstream's toggle runs, so the round ends where two plain
  taps leave it. Nothing gets stuck.
- **P3-1, fixed.** `onButton` counts a round only while `isVoiceTranscriptionOpen()`.
  A loading round's 0/1 (download/cancel) goes to upstream untouched. Voice is
  unchanged.
- **P3-3 and P3-4, fixed.** The `Anchor` makes two core calls when it is created and
  stores `keepsOutgoing` and `keepsIncoming`. `skips` reads those fields, so a toggle
  mid-queue no longer reaches the live append, and a build costs at most four JNI
  calls (an anchor may be made once on an older candidate and again on the start
  visit) instead of one per loaded message. `confined` is `!keepsOutgoing ||
  !keepsIncoming`, which is the same result as before. With the setting off, the
  core unavailable, or an unknown chat kind, both fields are true and nothing is
  skipped.
- **P3-5, fixed.** `eventId != 0` makes a long press TOGGLE. An admin-log round still
  restarts on double-tap, which is harmless (seek and resume of the playing message).
- **P3-6, fixed.** `docs/media-design.md` has a note under "Android: current
  behaviour" pointing to the README.
- **P3-2, open** for the device check. **P3-7, open**: the submodule pin is unchanged
  and still unverifiable here.

### PiP touch path, traced against `PipRoundVideoView`

`onInterceptTouchEvent` takes ACTION_DOWN and sets `startDragging`. `onTouchEvent`
turns a move of 0.3 cm into `dragging`. The hook runs only on ACTION_UP with
`startDragging && !dragging` and passes `event.getEventTime() - event.getDownTime()`.
Because the parent intercepts the DOWN, that UP's down time is this gesture's.

- **Tap** (shorter than `getLongPressTimeout()`, first of a pair): TOGGLE, so upstream
  pauses or resumes. Same as before.
- **Double tap** (second short tap on the same message within `getDoubleTapTimeout()`,
  measured from release to release): RESTART, and upstream's toggle is skipped. A third
  quick tap starts a new pair and toggles.
- **Long press** (held for the long-press timeout without moving 0.3 cm, acting on
  release): it resets the tap pair, so the next short tap is a first tap, then SCROLL
  or OPEN. With another account on top or an admin-log round it does upstream's
  toggle, as a long press did before this branch.
- **Drag, edge snap and fling-to-close**: unchanged. A drag never reaches the hook,
  and `dragging`/`startDragging` reset and `animateToBoundsMaybe()` still run after it
  on every UP.
- **Mixed**: tap then long press leaves the round paused by the tap and opens the
  message. A long press on a paused round opens it and keeps it paused. Neither is
  wrong.

### New P3 notes from the fix (not blocking)

- **R1.** The long press gives no feedback until release, and a still finger has no way
  to cancel except dragging. A slow tap held past the timeout now opens the chat
  instead of pausing. The timeout follows the system setting (400 ms by default, longer
  under accessibility settings), so this mostly affects slow tappers. Consider a haptic
  tick when the timeout passes (would need a delayed runnable from DOWN, so a second
  hook line) or leave it for device feedback.
- **R2.** The pure `action()` matrix is fully covered. The duration wiring
  (`getEventTime() - getDownTime()`) is pinned by text only, and the long press joins
  the device checklist: hold, then release, on the PiP in the round's own chat, in
  another chat, and in a chat of another account.

RECHECK MEDIA android: APPROVE P1=0 P2=0
