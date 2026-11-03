# Review: Android F9, seek back and forward

Branch `recording`, range `d111547..recording` (two commits: `0b7c0313` core bump and
`seekStepSeconds` bridge, `ac48a315` strip buttons and media session hooks). Read-only
review on 3 Nov 2026. Nothing was built or run on a device; the only things run were
the static checks named below.

Verdict: **CHANGES**. No P1. Two P2s: a paused round does not move on repeated presses,
and the "Seek step" chooser row that the design and this review's scope include is
missing. Both are small fixes.

## What was checked and holds

- **purple-core gitlink** is exactly `d7f378534f0f8829e3249dbc51f1e650d12f66d3`
  (`git ls-tree recording TMessagesProj/jni/purple`). The core's own diff
  (`6d0bd81b..d7f37853`) was not read: purple-core is outside this session's repository
  access. The bridge test (`seekcore`) exercises the core's parser through the real
  library, so the Android side of the contract is covered.
- **Static checks**: `purple/upstream-hooks.sh check` passes on the branch;
  `purple/run_host_suites.py --list` passes (37 configurations). The PINS list of
  `run_media_playback.py` was replayed against the tree: all 12 signature/hook pairs
  hold. Counted checks against the new `expects` floors: `seek` prints 49 (floor 45),
  `seekcore` 41 (floor 38), pins 17 (floor 17, deterministic).
- **Hook shape** (AGENTS.md): `FragmentContextView` gets one field and one call, both
  through the per-instance helper with fully qualified names and no import.
  `MusicPlayerService` calls `PurpleSeek` four times and imports it once. No hook carries
  more than the allowed comment. The baseline gains exactly the two new `file` lines. The
  README section lists the touched upstream files and why. The new strings are in
  `purple_strings.xml`.
- **Upstream behaviour unchanged**: `onSeekTo`, the expanded player's long-press
  rewinding, the speed button, close and play are untouched. The added session actions
  only enable `KEYCODE_MEDIA_REWIND` and `KEYCODE_MEDIA_FAST_FORWARD` in
  `MediaSessionCompat.Callback.onMediaButtonEvent`, which upstream left unhandled.
- **Clamping**: `PurpleSeek.targetMs` goes to 0:00 before the start, stops one second
  before the end, stays put inside the last second, and does nothing on an unknown
  duration. The tests cover each boundary, short messages and long positions.
- **Session sync**: `seekToProgress` posts `messagePlayingDidSeek`, which
  `MusicPlayerService` already turns into `updatePlaybackState`, so the notification and
  lock screen position follow a strip press. A press made from the session goes through
  the same path.
- **Strip layout**: forward sits at 72 to 108 dp from the right and back at 108 to 144,
  clear of speed (36 to 72) and close (4 to 40). The title's extra 72 dp right padding
  ends the ellipsised text at 108 dp, which is clear of the "−10" glyphs. The buttons
  show only in `STYLE_AUDIO_PLAYER`, so the live location strip and the group call
  styles are not affected. Running the hook before the style branches means the group
  call styles, which set the title padding outright, overwrite it after Purple has
  removed its share. A forced update in the same style removes and re-adds the padding,
  so it never grows. The manifest has `supportsRtl="false"`, so the "−10" label cannot
  flip in an RTL locale.
- **Privacy**: the diff, README, tests and commit messages contain no personal data,
  paths or hostnames.

## P2

### P2-1. A paused round video does not move on the second press, and its cell shows the old position

`PurpleSeek.step` takes the current position from `playing.audioProgress * duration`.
For a round, `MediaController` plays through `videoPlayer`, and the `videoPlayer` branch
of `seekToProgress` (`MediaController.java:2658-2662`) seeks the player but never writes
`audioProgress`. The progress timer is what normally refreshes it, and it does nothing
while paused (`if ((audioPlayer != null || videoPlayer != null) && !isPaused)`,
`MediaController.java:1634`).

Trace, round of 0:40 paused at 0:05:

1. Press "+10": position 5000, target 15000, `videoPlayer.seekTo(15000)`. `audioProgress`
   stays 0.125. `messagePlayingDidSeek` moves the lock screen to 0:15. The round cell and
   PiP still show 0:05.
2. Press "+10" again (or the headset fast-forward key): position is computed as 5000
   again, target 15000. The player does not move.

So on a paused round, any number of presses only gets one step from the paused point,
and the in-chat UI disagrees with the notification until playback resumes. Music and
voice are not affected while ready, because the `audioPlayer` branch sets `audioProgress`.
They are affected in the same way while the player has no duration yet
(`C.TIME_UNSET`, `seekToProgressPending`), for example right after starting a stream.

Fix, inside `PurpleSeek` with no new hook: read the position with
`MediaController.getProgressMs(playing)` (the live player position, both players), take
the duration from the player (`playing.audioPlayerDuration` or the same source), and seek
with `seekToProgressMs`. Optionally also set `playing.audioProgress` and post
`messagePlayingProgressDidChanged` so a paused cell redraws. A `step()` test with a stub
position source would pin it (see P3-5).

### P2-2. The "Seek step" chooser row is missing

The design puts a "Seek step" chooser under "Media playback" in `PurpleSettingsActivity`
(section "Settings", "Rows"), with the string `PurpleSeekStep`. Neither exists on the
branch: `PurpleSettingsActivity` is untouched and `PurpleSeekStep` is not in
`purple_strings.xml`. The commit message and README say the row is deferred. The owner
asked for a choosable 5, 10, 15 or 30 s step; today they can only reach it through the
raw settings editor (`PurpleSettingsEditorActivity`) by typing `seek_step = "15s"` under
`[playback]`. The step is applied and persisted correctly once written, so this is a
missing control, not a broken setting.

Fix: a choice row next to `ROW_VOICE_QUEUE`, with an id from the design's reserved block,
writing `[playback] seek_step` through the same TOML splice path the voice queue switch
uses, and calling the strip's relabel so the labels change at once.

## P3

### P3-1. The step and the end margin are measured against the attribute duration

`step()` uses `MessageObject.getDuration()`, which prefers the document attribute over
the player's own duration. `seekToProgress` then multiplies the fraction by the player's
duration. For a voice message the two differ by under a second, which is harmless. For
music whose metadata is wrong (common with VBR MP3 files), a file tagged 600 s that
really lasts 300 s steps by 5 s instead of 10, and the one-second end margin shrinks to
half a second. The P2-1 fix (milliseconds from the player) removes this as well.

### P3-2. The buttons keep their colour across a theme switch

The text colour and selector are set once when the buttons are created.
`FragmentContextView.updateColors()` recolours the speed, play and close buttons on a
theme change, but not Purple's. After switching day and night with the strip alive, the
"−10" and "+10" labels keep the old theme's colour until the strip is rebuilt. Without a
new hook, `relabel()` (already called on attach and on every press) can set the colour
from `Theme.key_inappPlayerClose` each time.

### P3-3. Headset keys on the legacy path and while buffering

- On MIUI, `supportLockScreenControls` is true on API 21 and later
  (`MusicPlayerService.java:70`), so upstream also registers `MusicPlayerReceiver` and a
  `RemoteControlClient`. The receiver's switch has no case for
  `KEYCODE_MEDIA_REWIND` or `KEYCODE_MEDIA_FAST_FORWARD`, and the client's transport
  flags do not include them. If the platform routes the key to the legacy receiver
  there, the keys do nothing. Unverified (no device); worth one check on a Xiaomi phone.
- While the current message is downloading, upstream sets the session's actions to 0
  (`STATE_BUFFERING`), so headset rewind and fast-forward are ignored while the strip
  buttons still seek. This matches upstream's own seek-to, so it is consistent, just
  worth knowing.

### P3-4. No seek buttons on the notification or lock screen, and no keyboard shortcuts on Android

The owner asked for the notification and lock screen to stay in sync. The position does
(see "Session sync" above), but Android's system media controls do not draw buttons for
`ACTION_REWIND` and `ACTION_FAST_FORWARD`, so there are no "−10" and "+10" buttons there;
only headsets, cars and watches use the new actions. Two `PlaybackStateCompat`
custom actions would add them if the owner wants them. Likewise the owner's "keyboard
shortcuts" are covered on Android only by hardware media keys; the design scopes
shortcut commands to Desktop. Both are design scope, not defects in this change; the
README could say so in one line.

### P3-5. Test gaps

- `PurpleSeek.step()` itself is untested: where the position and duration come from and
  what is passed to `MediaController`. That is exactly where P2-1 lives. A seam such as
  `step(direction, positionMs, durationMs, seeker)` would let the `seek` program cover it.
- `PurpleRecordingStrip.updateStyle` and its title padding bookkeeping are untested:
  audio to group call to audio, a forced update in the same style, and visibility in
  every style. A host test with a stub view would do.
- The design lists "seek clamping at 0 and at the end" under purple-core
  `tests/test_recording.cpp`. Here the rule lives in Java, so Desktop F9 will need its
  own copy of the same rule. Either move `targetMs` into the core and bridge it, or note
  in the design that the rule is per client, so the two clients do not drift.

## Summary of requested changes

1. P2-1: seek from the live player position with `getProgressMs` and `seekToProgressMs`,
   so paused rounds and preparing streams step correctly (also fixes P3-1).
2. P2-2: add the "Seek step" chooser row and the `PurpleSeekStep` string.
3. P3s at the author's discretion; P3-2 and the `step()` test are cheap.

REVIEW F9 android: CHANGES P1=0 P2=2 P3=5

## Re-check 1

Scope: `ac48a31..8e70f5c` on `recording` (one commit, "F9: seek from the live position,
add the Seek step row and notification buttons"). Read-only. The purple-core gitlink is
still `d7f378534f`. `purple/upstream-hooks.sh check` passes and
`purple/run_host_suites.py --list` passes (37 configurations). Nothing was built.

Verdict: **CHANGES**, one new P1 (the notification change drops shuffle and repeat for
music on Android 13 and later). Both earlier P2s are fixed.

### Earlier findings

- **P2-1, fixed.** `PurpleSeek.step` now reads `MediaController.getProgressMs` (the live
  player position, for both `audioPlayer` and `videoPlayer`) and seeks with
  `seekToProgressMs`. Replaying the original trace: a round paused at 0:05, press "+10"
  and the round seeks to 0:15. Press again and `getProgressMs` returns 15000, so it
  seeks to 0:25. `Live.seekMs` then writes `audioProgress` and `audioProgressSec` and
  posts `messagePlayingProgressDidChanged`, so the paused cell and the floating round
  redraw at once, and `messagePlayingDidSeek` still moves the session.
- **P3-1, fixed** in the same change: the duration is the audio player's, then the
  player-reported `audioPlayerDuration`, and only then the attribute.
- **P2-2, fixed.** `ROW_SEEK_STEP = 0x70750500` (the design's reserved block) opens a
  5, 10, 15 or 30 s chooser. It writes `[playback] seek_step` through
  `PurpleWriter.setTableString` and is in the same owner-refusal and session lists as
  the voice queue switch. `applyText` calls `PurpleGate.reload` on the UI thread before
  it returns, so the `PurpleRecordingStrip.relabelAll()` that follows reads the new step.
  `rebuild()` refreshes the row's value. The strings `PurpleSeekStep` and
  `PurpleSeekStepValue` were added.
- **P3-2, fixed.** `recolor()` runs from each button's `onDraw` and from `relabel()`, and
  only resets the colour when the theme colour changed, so there is no invalidate loop.
- **step() test, added.** The `Player` seam and `stepSeam()` cover repeated presses on a
  paused player (15000 then 25000 ms), the end margin, going back to 0:00, no position,
  an unknown duration, a refused seek, and a wrongly tagged file. Counted: `seek` now
  prints 75 checks (floor 70); pins are 18 (floor 18). `Live` itself (the
  `MediaController` side) is still untested, which is acceptable for a host suite.
- **P3-4, partly done.** The notification and lock-screen custom actions were added (see
  below). The README now says keyboard shortcut commands are Desktop only.

### New findings

#### P1-R1. Music loses shuffle and repeat on the Android 13+ notification and lock screen

`sessionActions(playbackState)` adds the two seek custom actions at the hook line, and
that line sits before upstream's own custom actions. That is true in both state
builders:

- `createNotification`: the Purple line is at `MusicPlayerService.java:500`, shuffle at
  `:506` and repeat at `:520`.
- `updatePlaybackState`: the Purple line is at `:743`, shuffle at `:748` and repeat at
  `:762`.

On Android 13 and later, the system media controls take their buttons from the session.
When the state has `ACTION_SKIP_TO_PREVIOUS` and `ACTION_SKIP_TO_NEXT`, which music
always has, the controls show previous, play/pause and next, plus only the **first two**
custom actions.

Trace, music on Android 13+: the custom actions are, in order, `SEEK_BACK`,
`SEEK_FORWARD`, `NOTIFY_SHUFFLE`, `NOTIFY_REPEAT`. The controls show "Back 10 s" and
"Forward 10 s" where upstream showed shuffle and repeat, and shuffle and repeat are gone
from the notification and lock screen. This changes upstream's music notification, which
this re-check was asked to guard against.

Voice and round are fine. They have no previous or next action and no upstream custom
actions, so the seek actions fill the empty previous and next slots, as the README says.
Android 12 and older build the notification from `Notification.Action`s and are
unaffected.

Fix, inside `PurpleSeek` with no new hook: add the custom actions only when the playing
message is not music. Music keeps upstream's previous, next, shuffle and repeat, and
headset rewind and fast-forward still work through `ACTION_REWIND` and
`ACTION_FAST_FORWARD`. Then add one line to the README saying music has no seek buttons
in the system controls. If the owner wants seek buttons for music too, the alternative is
to append them after upstream's actions, which needs the hook moved below the repeat
action in both builders. Even then they would not be visible when previous and next are
present, so skipping them for music is the honest choice.

#### P3-R1. The Seek step row sits in the focus card, not under "Media playback"

The row is added after the voice queue's `asShadow` (the section's closing info
text). So it renders as the first row of the next card, above "Follow Do Not Disturb",
while the README says "Media playback > Seek step". Fix: add it before that shadow.

#### P3-R2. A seek before the player knows its duration posts 0:00 to the session

When `audioPlayer.getDuration()` is still `C.TIME_UNSET` (a stream just starting),
`Live.durationMs` falls back to the message's duration, so a seek happens.
`seekToProgressMs` then posts `messagePlayingDidSeek` with
`clamp01(ms / TIME_UNSET) = 0`, and `MusicPlayerService` sets the session position to
0:00 until the next state update. This is narrow, and it is upstream's own method doing
it (cast sync uses the same path). It only matters if it shows up on a device.

#### P3-R3. Notification button labels follow a new step only on the next state rebuild

After the step changes in the row, "Back 10 s" stays on the notification until the next
play, pause or seek rebuilds the state. A press already uses the new step, because
`step()` reads it each time, and the first press relabels. This is cosmetic.

### Hook shape

`MusicPlayerService` gains one early return at the top of `onCustomAction`, which is an
allowed hook shape. It now calls `PurpleSeek` five times and imports it once, and the
baseline moves to `14 0 5`. `FragmentContextView` is unchanged. The new row lives in
Purple's own `PurpleSettingsActivity`. No hook carries a comment beyond the allowed one.
The new drawables and strings are Purple's.

RECHECK F9 android: CHANGES P1=1 P2=0

## Re-check 2

Scope: `8e70f5c..f1eef62` on `recording` (one commit, "F9: keep music's shuffle and
repeat in the system controls; move the Seek step row"). Read-only. The purple-core
gitlink is still `d7f378534f`. `purple/upstream-hooks.sh check` passes,
`purple/run_host_suites.py --list` passes (37 configurations), and no upstream file
changed in this commit. Nothing was built.

Verdict: **APPROVE**.

- **P1-R1, fixed.** `sessionActions(state)` now adds the two seek custom actions only
  when `seekButtons(playing.isMusic())` is true, which means only for non-music. For
  music, both state builders (`createNotification` and `updatePlaybackState`) add no
  custom actions of Purple's. Upstream's shuffle and repeat custom actions are therefore
  first again, in upstream's order, and the Android 13+ controls show previous, play/pause,
  next, shuffle and repeat exactly as before F9. The only remaining difference for music
  is the `ACTION_REWIND | ACTION_FAST_FORWARD` bits. System media controls do not draw
  those; they only enable headset keys, as accepted in the first review.
  `MediaController.getPlayingMessageObject()`, which the check reads, is the same message
  that every `createNotification` call site passes in, so the music test cannot disagree
  with the notification being built.
- **Voice and round** still get "Back N s" and "Forward N s" in the empty previous and
  next slots. The step is read on every state rebuild, and `onCustomAction` is unchanged.
- **P3-R1, fixed.** The Seek step row now comes before the voice queue's closing info
  text, so it sits inside the "Media playback" card.
- **Tests.** `seek` gains two checks for `seekButtons` (music no, voice and round yes) and
  now checks the constant `SESSION_ACTIONS` instead of calling `sessionActions(null)`,
  which would have reached `MediaController` on the host. The count is 76, above the
  floor of 70. The wiring from `isMusic()` to `seekButtons` is one line and is not
  host-tested, which is fine.
- **README** says music has no seek buttons in the system controls and keeps upstream's.
- **Nothing new found.** P3-R2 (a seek before a stream knows its duration briefly posts
  0:00 to the session) and P3-R3 (notification labels follow a new step on the next
  rebuild) stay as noted. Neither blocks.

RECHECK2 F9 android: APPROVE P1=0 P2=0
