# Cross-chat round capture spike

This is an experimental foreground-capture path awaiting the device gate. It
does not claim camera continuity from host tests. All round finish actions in
this spike save a local Recording bin item instead of enqueueing a message.
The root button says **Save to Recording bin**. Durable send correlation and
full target/send-option handling belong to the next implementation phase.

## Ownership

`PurpleRoundCapture` owns one native round view from before camera startup,
including recordings where the user stays in the original chat. The native
delegate is the capture owner, never a ChatActivity. Its view starts in
LaunchActivity's root container and is not removed or reparented on navigation.
The existing native implementations keep their own camera, encoder, audio and
upload machinery; no encoder code is copied into Purple.

`PurpleRoundCaptureLease` freezes account, client-user identity, original dialog,
topic, chat mode and an independent capture guid. Disposable chat bindings
project UI state. Old fragments cannot detach a newly returned screen. Registry
session targets have their own lifetime rather than disappearing when a chat is
unwatched. Both native constructors and the factory take the delegate's account.

The spike accepts ordinary non-secret chat recordings, including forum topics.
Other modes are refused before opening the camera. Its local bin metadata keeps
the original account/user/dialog/topic/reply and view-once flag. This is not yet
the complete deep-frozen quote/send-as/suggestion/send-options protocol required
for production sending. Voice recording and existing bin-copy sends retain their
existing paths.

## Navigation and preservation

Leaving a chat shrinks the root-owned camera and releases only that chat's
composer visuals. New chats keep normal typing and Send behavior. Returning to
the original target binds the same capture; explicit expansion restores its
capture controls. Small paused captures may also shrink/expand across screens.
Native pause/resume and flip remain native operations.

Background, passcode, account switch and audio-focus/telephone interruption
finish into a local draft, without automatic capture resume. The root becomes
invisible and stops receiving touches while preservation completes. Activity
destruction requests the same preservation and bounds old-host retention; it
does not promise a live surface in a replacement window. The actual destruction,
permission/error and recovery paths still require device qualification and the
full lifecycle model in the next phase.

Root controls use activity-owned resources. They do not keep an old chat's blur
factory alive. The spike uses the native background factory with its color
source fallback. Full rendering, layering, sizing, IME and tablet polish remain
subject to the device gate.

## Evidence

The `recording-dock` host suite compiles the complete production capture owner
over inert Android/camera/storage ports. It checks the two adapter selections,
two switches and return, source-fragment detachment, no additional capture
start/stop, independent guid, frozen retention destination, repeated finish,
background/recreation cleanup, no automatic resume, account/user/topic/mode
binding checks and stale binding tokens. These are ownership tests; the ports do
not open cameras or encode media.

The always-on Purple diagnostics include `round_capture` events with a local
take counter, adapter choice, preview-window and surface create/destroy counts,
texture/surface change counts, frame ticks and recorded milliseconds. These
records contain no chat/account identifiers, paths or file contents. FileLog
also includes object identities when normal logging is enabled. Ignore surface
changes explicitly caused by native pause/resume when evaluating navigation.

For each adapter, start a take in the allowed test account's own Saved Messages,
shrink, browse two other chats without sending, then return and expand. Also
destroy the original fragment rather than merely covering it. Capture must keep
one preview window attachment, the same texture and surface, no surface destroy,
and increasing frame/duration counters through all chat transitions. Watch for
black, frozen or duplicated previews and an expanding central placeholder.
After return, exercise flip and pause/resume separately, then Save to Recording
bin. Play the saved file and inspect both decoded audio and video continuously
across the navigation boundaries, using harmless visible/audible time markers.
An MP4 with two tracks is insufficient evidence of continuity.

Also check an ordinary take that never navigates; composer text in the browsed
chat; drag/corners; the IME and modal/lock layering; background and account-switch
preservation with no auto-resume; and release of camera/microphone indicators
after Save, Discard and interruption. Legacy's internal camera backend selection
is separate from the newer round-adapter selection. Record the combinations the
device actually supports. The coordinator runs these device checks and decides
whether the next phase may start.
