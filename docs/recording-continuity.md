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

F6 supplies the in-chat round bubble fallback. During active capture, tap outside
the camera to shrink, drag it to a corner, tap it to expand, and use its camera and pause controls.
The camera container is transformed in place; neither SurfaceTexture nor the
encoder is detached or restarted. The corner is device-local. Layout and the
visible window bounds keep the bubble above the keyboard. Leaving a chat pauses
its round, including background and lock. Both round implementations share the
base touch-dispatch seam. Native composer taps in the bottom inset pass through
before the bubble can claim a gesture. Paused full-size previews and captures
that the dock cannot shrink keep native routing. A native gesture stays native
through move, up and cancel, including the small bubble's camera/pause controls.
Small paused bubbles can still expand or drag to a saved corner. Expanded stock
camera buttons also retain their entire native gesture, even outside the camera
circle, so flipping does not shrink the recording. Closing a camera retires its
small presentation and controls before the native close animation completes.
A retained chat cannot expand a closed or hidden capture when it backgrounds,
so reopening after docked discard does not restore the feature backdrop or input
overlay. Fresh expanded opens restore the retained camera's native flip/flash
bar independently of the retired small state, without replaying a resize or dim
callback. Paused compact opens keep their compact controls. Other native dimming
retains its own ownership.

Shrinking releases the round's native recording panel for typing and shows a
video row in the recording strip with avatar, title, timer, waveform and native
Pause/Resume, Send and confirmed Discard icons. Long-press the timer for Preview. The round send context is frozen
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
`PurpleRecordingStrip` hosts the controls within the stock player row.

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
suite list before acceptance. The same suite compiles the complete production
`PurpleRoundBubble`, `InstantCameraViewBase`, settings and geometry with inert
Android ports. It
exercises both factory-selected camera adapters, native Send/Delete inset
routing while active and paused, ineligible dock fallback, full native gesture
ownership, intentional active shrinking, small controls, drag/corner persistence
and cancellation. Native adapters retain their source bottom-inset rejection;
legacy's remaining touch behavior, camera state and Android child dispatch/layout
are modeled. This does not qualify real encoder, animation or platform event
delivery. Application compilation and device qualification are separate.

Only the player context strip displays a docked recorder. The separate live-location
strip shares the upstream widget class but its Purple helper cannot publish a
recording row or enter player layout from a dock change. The eligibility supplier
reads `isLocation` after the upstream constructor assigns it. The upstream change
is confined to the existing `FragmentContextView` helper-construction hook.

The chat's recording host and the strip's change callback own their weak dock
registrations through strong fields. Both fields use `@Keep`: release R8 can
otherwise remove an unread field and leave a visible chat or strip registered
only weakly. Collection then prevents voice/round adoption or leaves discarded
rows visible. `recording-dock` also runs the exact host field, strip constructor
and dock adoption/clear seams through the offline AGP's release R8, then collects
and checks a second take, service request, pause/discard updates and unwatch.
The UI, recorder and service boundaries in that host case are inert; the APK
and device still need their separate checks.

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

## Recording integration review fixes

Native voice Preview remembers the frozen reply, topic, story, sender and
monoforum arguments by document identity when the dock hands the recorder back.
The direct Preview Send hook applies that snapshot after native trimming and
story preparation. Resumed sends continue through the recorder's original
arguments. Neither route copies the current compose reply into the recording.

Every accepted round send transfers its immutable target before encoder finish.
Both camera implementations reserve finalization through the dock and defer
cancel or destroy until the completion has durable recovery ownership. Chat
fragment destruction releases its UI ownership but retains that send target in
the camera delegate. Duplicate completions are consumed once. Genuine recording
errors still clear the reservation through native error notifications.

The native ChatActivity bridge closes the round backdrop for every consumed
send. Stock captures keep native after-send compose cleanup; a capture that
released compose while small preserves later text and reply changes. The camera
is released after durable bin retention even when its fragment has been destroyed.

Alternate-target requests from a paused notification foreground the activity
and defer the picker to its resumed lifecycle callback, checking generation
again. Stale requests are cleared with the recording.

The recording-dock host suite compiles the exact round helper against inert UI
and transport boundaries and extracts the actual native voice send parameter
initialization for direct and resumed sends. It covers stock backdrop cleanup,
post-destruction completion, duplicate delivery and frozen voice context after
trim. It also pins native cancel/destroy guards and foreground picker ordering.
These fixtures do not qualify encoder timing, UI animation or microphone
continuity on a device. Cross-chat round capture remains unshipped.

Paid confirmation closes the completed round backdrop immediately but defers
stock compose cleanup until the approved media has an outgoing storage acknowledgment. Canceling that
confirmation keeps the compose reply unchanged. Approval after fragment
destruction still queues the frozen target and releases its camera resources.
The deferred-approval host case covers both live and destroyed fragments.

## Completed rounds awaiting queue storage

Managed rounds persist `awaiting_queue` in their first manifest write. A clean
MP4 ends encoding, and remains recovery-owned until an outgoing message is
stored. The F10 sending timer and startup recovery no longer infer queueing
from a finished container for these entries. Old entries without this optional
field retain their existing interpretation.

Completed media is copied and synced into the Recording bin before any second
price confirmation or Stars-needed UI is opened. The bin owns the file and
frozen recovery destination, topic, reply and view-once metadata. The original
native camera is then released and the dock can accept a new recording.
Cancel, Back, dismissal and fragment destruction leave this completed item in
the bin. A later send can use the bin's existing actions. This trades native
resume of a completed encoder for a durable, explicit retry path.

Successful automatic sends keep the original immutable native context,
including sender, quote and suggestion fields. A thin SendMessagesHelper hook
runs after it submits the outgoing local message. Purple checks for that exact
message in the normal or scheduled outgoing table on the same serial storage
queue before removing recovery ownership. Preparation returning alone does
not acknowledge queueing. Account identity, one active delivery ticket and
one payment approval fence stale or duplicate callbacks. Native compose
cleanup waits for queue acknowledgment and preserves text/reply edits made
after completion. A weak UI owner avoids retaining a destroyed fragment.

Bin retry copies its retained payload into upstream's media directory and uses
the same acknowledgment. It cannot erase a file still being prepared by
upstream. Restart preserves an unqueued completed item, including the window
between encoder completion and the bin copy. A crash after the outgoing SQLite
commit but before the separate recovery-manifest removal can conservatively
leave a bin copy; startup does not automatically resend it. This favors
retention over deleting a possibly unqueued recording.

The recording-dock fixture includes payment Cancel, dismissal, price change,
Stars-sheet dismissal, failed/actual queue storage, duplicate completion and
approval, fragment destruction, subsequent compose edits and a late callback
after a new capture. A separate real manifest/store phase verifies restart
retention and independent outgoing bytes. The earlier four successful-path
review fixes and their assertions remain covered. These are host fixtures;
Android surface, payment-sheet and process timing still need device testing.

Queue acknowledgments bind UI cleanup to the original capture generation.
Starting another capture cannot let an older queued message clear its reply
state or close its backdrop. The adverse callback-order fixture fails on the
unguarded helper and checks both controls after the ownership guard.

Cleanup also checks the global dock generation after the completed round
releases its slot. A new voice capture, including one outside the old round
helper's local lifecycle, therefore fences the old acknowledgment as well.

## Encoder readiness and retry authorization

The legacy recorder can call `sendMedia` with `notReadyYet` before draining its
encoders or writing `moov`. It later clears the same info object's flag on the
UI thread after the muxer and final-path replacement finish, without promising
a second delegate callback. Purple keeps one pending completion and its frozen
target across that interval. It does not latch the completed path, touch the
bin or prepare a send while the native flag is pending. Duplicate callbacks
can update the pending info without starting another handoff. V2's completed
result enters the same path with its native output already finished.

Readiness and transient unfinished-file retention get at most 120 delayed
checks, 250 ms apart. Exhaustion takes an error path: copy a snapshot and its
journal into the bin and attempt recovery there, keeping an unrecoverable raw
item when repair fails. It never sends an unfinished file. This bounds checks,
not storage-call wall time. A successful completed handoff still copies every
final byte before payment. Snapshot retention does not erase the original;
only after it succeeds can the native camera be released. If persistence itself
fails, the original awaiting-queue manifest remains owned for restart recovery.
That disk-failure path cannot promise an actionable copy or immediate release.

An accepted V2 encoder error uses an internal stop reason to distinguish it
from user Discard. It follows F10's synchronous rename-before-erase protection,
then preserves a recovered or raw bin item even when keep-discarded is off.
Ordinary cancel retains its previous setting-controlled behavior. The native
edit is the single error-reason call in `InstantCameraView2.onError`; readiness
continues to use legacy's existing flag and finished V2 result. Fragment
destruction preserves the pending completion; an explicit native error
abandons its UI callback only after the registry has reserved recovery work.

A bin retry binds the expected account user at the start of the user action.
Original-chat retry uses the entry's recorded user; an explicit alternate
retry uses the identity selected when that action begins. This exact value
crosses worker dispatch, file copy, UI handoff, sender snapshot, payment,
preparation and delivery ticket. Each deferred authorization validates that the
slot is active and still holds that user. Native preparation and storage
acknowledgment validate the same value, never a newly sampled replacement.
A rejected attempt leaves the bin file and manifest intact. A failed storage
acknowledgment releases its ticket binding so an authorized retry is possible.

The focused cases cover both callback orders, incomplete-to-finished handoff,
duplicates, fragment destruction, bounded error fallback, accepted V2 error
with keep-discarded off, raw-journal restart retention and identity replacement
at each deferred boundary. The retry fixture compiles the exact public retry
entry and pending-route methods plus the real sender snapshot and delivery
classes against inert dispatch/storage boundaries. It also tests explicit
alternate selection and inactive-account rejection. This is host evidence;
real encoder timing, payment sheets and process/surface teardown need devices.

## Snapshot ownership across delayed error and discard

V2 files reserve their snapshot base, original source and journal in the normal
manifest before the worker publishes a lock-free capture token. If that save
fails, the token is not published and the protected rename cannot run. A stop
claims the token, then atomically renames into its reserved error, kept-discard
or keep-off-discard destination. The pathname records the captured policy.
Before the rename recovery finds the original source; afterward it finds the
reserved destination before applying missing-file cleanup. The original journal
stays owned throughout. Optional fields default empty for older manifests.

The shared `PurpleRecordingSnapshotHandoff` is used for both ordinary discard
and accepted encoder error. Its delayed callback captures the original session,
part, entry id, source, destination, policy and target, rather than resolving
account/chat guid again. Claim and retirement share an atomic state. A newer
round or voice creates another entry while the claimed older entry and journal
remain owned. Removal compares session identity, and the unified callback
settles at most once. There is no separate snapshotDone/stopped ordering gap.

Settlement copies the reserved bytes and journal into final bin files, repairs
only the copy and commits the replacement manifest before deleting original
paths. A persisted committed-copy flag permits retrying cleanup after restart
and prevents cleanup from deleting the source of an ordinary completed send.
Startup recovery uses the same copy-before-commit ordering, including when an
initial reservation or an earlier recovery was interrupted. Keep-discarded off
still deletes an ordinary discard; accepted errors remain recoverable or raw.
No per-stop manifest fsync is added to the UI thread.

The registry scheduling fixture compiles its actual recordStopped branch and
full production ownership classes with a controlled worker. A host-only source
overlay adds crash probes immediately before/after the real file rename and
atomic manifest replacement. It aborts the operation and constructs a new real
store on the same files, checking reserved intent, A/B payloads and journals,
entry identity, newer round/voice ownership, duplicate/reverse callback order,
keep policy and interruption during recovery itself. Native notifications,
encoder writes and OS power-loss behavior remain device qualification; the host
core stand-in deliberately keeps these incomplete MP4s raw and models voice
finalization, while the existing recording suite covers real codec recovery.

## Recording controls and bin playback

The recording row shares FragmentContextView's existing 48dp player height
(including the seek targets), player colors, PlayPauseDrawable, close icon and
send-plane icon. While it is shown, the stock title, play, speed and close views
are invisible; leaving recording restores their native visibility. Its
transparent surface keeps
ChatActivityTopPanelLayout's existing glass background, rounded clipping and
shadow. The avatar has a 7dp start inset and gap. The title can ellipsize while
the timer and actions retain their widths. RTL mirrors the stock player's
physical ordering: Discard and Send move to the left, followed by Pause, waveform,
timer, title and the right-inset avatar. The avatar's 7dp gap stays next to the
title. Child ordering and physical margins are explicit because the app disables
framework RTL layout. The existing view updates them on refresh and measurement
after a locale change; it preserves all child instances and handlers. Title
alignment uses TextCell's locale-based RIGHT/LEFT policy without changing bidi
content. The timer retains left-to-right digits and text direction.

Minimized rounds reuse the native camera's BlurredBackgroundDrawableViewFactory,
6dp padding and 21dp radius for their controls, with 48dp button targets and the
stock roundcamera_flip drawable. Both PIP icons copy the live native switch-camera
ImageView's color filter, including FlashViews.ImageViewInvertable's 0.6 light
inversion in the new design and its white dark-theme state. Each adapter's own
resource provider remains authoritative; no global-theme approximation or new
palette is introduced. PlayPauseDrawable keeps its white source glyph and gets
the same native ImageView filter as the camera glyph on each state update.
The production controls declare WRAP_CONTENT
FrameLayout bounds on both axes: two 48dp targets and 6dp padding produce a
108 by 60dp capsule rather than a camera-host-sized glass surface. Placement
clamps that capsule within the host width when a short preview is narrower than
the controls; the existing bottom reserve keeps it above the composer/keyboard.
The legacy camera's placeholder is a sibling
of its live preview, so both must share the bubble transform. Its progress arc
also retains the bubble scale during a camera flip. Native camera adapters expose
only the placeholder registration, background factory and scale predicate hooks.
Camera2's placeholder already lives inside its camera container.

A refused recording hold clears the pending long-press state before showing the
existing Discard/Continue dialog. Releasing that gesture cannot run the short-tap
voice/video mode toggle. The normal short tap and accepted hold keep stock behavior.

Each bin row uses UniversalAdapter's full-width custom row with wrapping height.
Its title, seek bar and elapsed time use explicit floating-point weights in
LayoutHelper, so they cannot select the integer gravity overload. The title stays
on one line; kind and duration, date, and status wrap below it. The title and
all metadata lines use TextCell's explicit locale-based RIGHT/LEFT gravity and
TextView gravity alignment, retaining native bidi text direction. The title and
overflow physically exchange sides when the locale changes on an existing row.
The manifest disables framework RTL layouts, so direction setters alone cannot
perform this placement. SeekBarView keeps AudioPlayerAlert's left-to-right
numerical progress, with LTR elapsed time on the left and total on the right.

The 48dp play target uses SharedAudioCell's RadialProgress2, MediaActionDrawable
and incoming loader/media-icon color keys, including pressed colors. SeekBarView
and elapsed/total time use AudioPlayerAlert's progress and time colors; dragging
reports position changes as in that player. VideoPlayer supplies pause and rewind;
rounds reveal a circular preview. Starting or resuming a row pauses the other
rows. Leaving the bin or detaching a row releases playback.

The overflow and long-press menu includes Send to Saved Messages for sendable
non-secret, non-view-once entries. It uses the selected account's own dialog,
without a picker or recorded reply/topic. Every bin send copies its outgoing
media and retains the original bin file and entry, including acknowledged round
retries. Explicit deletion and configured expiry still remove bin items.

The recording-dock suite executes the production playback and recording-button
methods over inert ports. It also constructs the complete production bin row
with the real LayoutHelper overloads, checks weighted bounds and radial icon
states, and exercises stock-strip visibility restoration. Alongside those are
minimized-placeholder transforms and the
existing delayed identity/queue-acknowledgment tests. Those ports do not establish
visual quality, codec playback or camera behavior on a device.

The focused PIP checks instantiate the production PurpleRoundControls with the
real LayoutHelper methods and verify its FrameLayout bounds, stock capsule
parameters and child targets. Separate production-placement ports cover all
four corners with narrow and keyboard-sized surfaces. These checks establish
layout contracts, not rendered glass/shadow pixels or camera drag behavior.

The bin constructor ports cover Latin, Arabic and mixed metadata in both locales
and locale changes on the same row, including physical text anchors, overflow
order and unchanged numerical seek/time direction. These are source contracts;
real Arabic glyph shaping and rendered row alignment need a device comparison.

The recording-strip constructor ports compile the complete production view over
inert Android boundaries, with no automatic framework RTL mirroring. They check
physical child order, insets, avatar gap, retained dimensions, long Latin/Arabic/
mixed titles, and repeated locale changes while active and inactive. They do not
render Android views; the final combined tree still needs canonical checks, an
APK and stock-versus-recording-strip screenshots in both locales.

Focused contrast ports execute both adapters' actual new-design/provider branches
and FlashViews.ImageViewInvertable.setInvert with the complete production PIP
controls. They cover local light/dark against the opposite global theme, V2's
null-provider fallback, old-design defaults, pause/resume, live inversion and the
production bubble's selected-native-button wiring. These establish tint policy
and filter identity, not final pixels; combined-source APK/device comparison
against native controls in light and dark is still required.

The bubble regression fixture runs both production native cancel methods and
extracts the chat animation/resize callbacks, feature-backdrop setters and
before-leave expansion. Resource effects and animation timing are inert ports;
close listeners are invoked explicitly. It covers pause during close, same-chat
background/resume, repeated takes and discarded controls without resetting any
global alpha or dialog dim. Hardware, platform animation and device rendering
remain separate qualification.

The retained-camera fixture also executes both native visibility initializers
and derives the button-alpha animation endpoint from each backend. Three takes
on the same chat/view cover direct discard and discard with background/resume,
visible native-bar flip targeting, expanded and compact paused opens, and old
close listeners after a new open. Native initializers reset alpha without fixing
visibility, so the fixture detects a bar stranded invisible across takes.

During a compact-camera drag, the gesture owns the camera transform. Pre-draw
and layout callbacks align the placeholder and controls with that live position
instead of restoring the saved corner. Release persists and snaps to the chosen
corner; cancellation restores the previous corner. The production dispatch
fixture interleaves real registered pre-draw/layout callbacks with movement,
release and cancellation for both camera backends. Android input timing and
rendered motion remain device checks.

The active-recorder refusal uses Telegram's localized video discard title and
description for a round take, and its voice copy for voice. Continue leaves the
take untouched; Discard retains the generation captured when the dialog opened.
The host modal control executes the production refusal method for both kinds,
including inactive state, missing activity and a replaced generation.
