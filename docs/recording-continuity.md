# Recording continuity

F7 keeps a voice recording running with the app in the background or the screen
locked. A microphone foreground service starts while the recording chat is in
front. Its low-importance notification has Pause/Resume, Send and Discard. Discard
requires a second tap within five seconds. A partial wake lock is held while the
recording runs and released on pause and every service teardown. Failure to
promote the service retains upstream's pause-on-background behavior.

F8 lets a voice leave its chat. Back, a background transition or a tap on the
locked recording panel releases that panel without stopping MediaController.
The context strip then owns the recording controls. Text composed in another
chat uses that chat's normal Send button. The recorder retains its original
account, topic, reply and send arguments; unrelated voice drafts cannot restore
over a docked recording. A paused voice already shown in the native preview stays a native draft; it is
not adopted by leaving that screen. A paused dock keeps its recorder resources
so both legacy and V2 can resume. A second recording first offers Discard and Continue.
The user starts it again after the old recording ends.

The strip opens the target through LaunchActivity's normal chat route. Preview
pauses and hands the voice back to the target chat's native draft and trim UI.
Paid and scheduled sends open the app and use the native confirmation and date
picker while retaining the dock target. A restricted or slow-mode send keeps the
recording and explains the refusal. A paused legacy voice resumes before sending.
Preview consumption is observed through the same draft-clear signal as F10.
Each notification action has a generation-specific Intent data URI as well as
a checked generation extra, so Android cannot update an old action to target a
new recording. Intent extras alone do not distinguish PendingIntents, as the
[Android API reference](https://developer.android.com/reference/android/app/PendingIntent)
explains. The account identity is checked before an action can send. Hidden targets use the neutral Recording
label under Work Mode. Screens without a context strip use the notification.

F6 supplies the in-chat round bubble fallback. Tap outside the camera to shrink,
drag it to a corner, tap it to expand, and use its camera and pause controls.
The camera container is transformed in place; neither SurfaceTexture nor the
encoder is detached or restarted. The corner is device-local. Layout and the
visible window bounds keep the bubble above the keyboard. Leaving a chat pauses
its round, including background and lock. Both round implementations share the
base touch-dispatch seam.

Shrinking releases the round's native recording panel for typing and shows a
video row in the recording strip with avatar, title, timer, waveform and native
Pause/Resume, Preview, Send and confirmed Discard. The round send context is frozen
at start, including account, reply, topic, quote and send-as arguments. Text sent
while it floats cannot change that context. Preview expands back into the native
round preview; its existing duration cap and trimming remain in charge.

Rounds are kept within their owning chat. Before navigating away or backgrounding,
the native panel is restored and the camera pauses. Round strip controls cannot
resume a camera outside the owning visible chat. Cross-chat round capture remains
unshipped until the separately authorized device spike passes.

A deleted user, left chat or unavailable topic changes Send to Send to another
chat. Selection uses a same-account topic-aware sheet, leaving the old target
and recording intact on cancel. Selecting a replacement explicitly drops the old
reply and send-as context, invalidates old notification/payment actions and uses
the new target's payment/schedule checks.
Secret-chat recordings never offer that route. The F10 recovery target remains
the original capture target.

## Settings

Purple Settings, Media recording exposes `keep_across_chats_p`, `background_p`
and `round_float_p`. All default on and are parsed by purple-core. Changing the
background setting applies to the next service start. The existing F9/F10
settings and bin remain in the same screen.

## Ownership and hooks

`PurpleRecordingDockState` is the host-testable ownership state. It serializes
pause and send transitions, retains the target, counts recorded time without
pauses and expires the notification discard confirmation. `PurpleRecordingDock`
connects that state to native events and `PurpleRecordingService`. The existing
`PurpleRecordingStrip` hosts the controls above the stock player row.

Upstream edits are confined to MediaController (pause decision, second-start
refusal and draft-restore refusal), ChatActivity (back/overlay and camera binding),
ChatActivityEnterView (event filtering, panel touch and the minimal UI-release
extension), FragmentContextView (show/hide using its existing player layout),
InstantCameraViewBase (touch dispatch), and one manifest service declaration.
The camera-binding callback can only set the native dim and input overlay.
Recorder state, target checks and service logic stay in Purple classes.

## Validation

The `recording-dock` host suite covers target retention, ownership, stale action
rejection, repeated pause/send taps, paused legacy send transitions, recorded
time, discard confirmation and bubble bounds. Run the complete canonical host
suite list before acceptance. Application compilation and device qualification
are separate from those pure host cases.

Device checks, in risk order:

1. Start in chat A, topic and reply; switch account and chat, type and send text,
   then send the recording. Check its account, recipient, topic and reply.
2. Preview/send/discard/resume with both voice recorders, including a restored
   draft and two drafts in different chats. Check F10's bin after every path.
3. Lock for ten minutes, background, change screens and reconnect Bluetooth.
   Check captured audio continuity and the foreground-service/wake-lock lifetime.
4. Deny notification permission, deny service startup, stop the service and
   switch off background recording. Check stock pause fallback and no silent audio.
5. Exercise paid and scheduled sends, slow mode, deleted topics and logout. A
   failed check must never redirect a recording to another chat or identity.
6. Both round recorders: shrink, scroll, drag/snap, keyboard, flip, pause/resume,
   expand, 60-second cap and background. Check the video timestamps and F10 files.
7. Repeat stale notification taps after send/discard/new recording and after a
   process restart. Check that the new recording is unaffected.

Unreadable F10 manifests use exclusively created set-aside names. Rapid failures
cannot overwrite an earlier damaged manifest, and a failed set-aside prevents
later saves from replacing that evidence.

The native extension in MediaController changes only its private send target
fields, before the dock requests Send. ChatActivityEnterView exposes native round
panel restoration as well as release; it retains view-once while restoring the
existing UI. PurpleRoundRecording snapshots the original round's native send
arguments and handles its completed media through one ChatActivity sendMedia
hook. Those seams require private native members; the ownership decisions and
picker remain in Purple helpers. Native quick-reply capture contexts retain their
stock controls and are not adopted into the dock.

Send-as selection is frozen in a recording's immutable native argument object.
A weak-key Purple map carries its account, user, dialog and chosen sender through
video preparation and voice finalization. Two SendMessagesHelper hooks validate
that identity and apply the frozen sender. Normal sends have no such entry and
use the native choice. Recipient replacement creates a fresh snapshot for the
new dialog. This also prevents a recycled account slot from sending an old
recording after asynchronous video preparation.

Voice Preview hands the still-paused recorder to the native draft panel with a
single-use, account-checked handoff. It changes the UI guid without replacing the
private native reply, thread or send arguments with whatever is in the newly
opened compose field. The capture's F10 target and the currently selected send
target remain distinct when the user replaced a recipient. Resuming in front
promotes the microphone service again before a later background transition.

If an original secret chat is gone, Send shows the unavailable notice and keeps
the recording. It never opens a recipient picker or finalizes the capture into
a missing encrypted chat. Preview and confirmed Discard remain available.
