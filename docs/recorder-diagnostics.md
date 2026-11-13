# Diagnosing recordings from normal use

After a problem, open **Purple Settings > Diagnostics > Export diagnostic log**
and choose where to share the text file. Export soon after the incident, before
rotation removes older events. Include the approximate time, steps, expected
result and what happened. The export header contains app, source and core build
versions. Export is manual; these events never upload themselves.

The existing app-private sink keeps a 256 KiB file and one rotated predecessor.
Its bounded writer can drop events under load. Repeated retention failures are
limited to one per 30 seconds per take. Clear removes the diagnostic files and
exports. No new log folder or export UI is introduced.

## Reading a trace

`process_hi/process_lo` identify one random process scope. `take_hi/take_lo`
identify a random take or split operation in that process. They contain no
account, chat, message, phone or device identifier. Scope changes after process
restart; events from separate process scopes cannot establish an ordering across
boots. `elapsed_s` plus `elapsed_ms` is Android elapsed-realtime since that
scope began, independent of wall-clock changes. `trace_build source_build` captures the compile-time source commit for each
take/operation. This stays meaningful if a later app update exports older events;
the export header describes the currently installed build. No device or user
identifier is accepted by the no-argument production source-build field.

`capture_budget` records the captured backend and local cap in seconds. `stop_s` plus `stop_ms` records the captured native stop
budget, including the legacy composer margin. A zero
cap means unspecified (voice has no round cap; a V2 owner is created before its
session config is attached). The V2 session subsequently records its exact
configured cap. `started` is the native recording notification. Legacy budget
resume/pause events are requests at the budget boundary, not hardware completion.
Legacy `audio_record_start_returned` observes AudioRecord.startRecording's
return, `resume_applied` observes the encoder thread clearing its pause flag,
and `pause_preview_ready` observes the native preview callback after setup.
These are distinct API/state boundaries, not physical microphone proof. The
dock's guid-less pause/resume notifications are deliberately not logged as
acknowledgements: resume can be posted before the encoder thread handles it.

`pause_request`, `resume_request`, `stop_request`, `discard_request`, and
`discard_modal` describe requested actions. Continue or native dialog cancellation
emits `discard_cancel` only while the captured generation still owns that modal.
`docked/expanded` describe the selected presentation. `chat_hidden/chat_visible`
are chat lifecycle events; `background/foreground` come from the existing app
foreground callback and are distinct from moving between chats.

Legacy `deadline` means its capture-owned monotonic budget expired. Audio/video
input and output EOS events mean the native codec path observed those flags.
`drain_returned outcome=ok` means both output EOS flags were observed when the
final drain returned; `error` means one was absent. `muxer_complete` is emitted
only after `finishMovie` returned successfully; `failure outcome=error` records
an unsuccessful native codec/muxer path. Audio/video encoder release events mean
those native API calls returned after stop, not that the physical driver is
proven released.

V2 `state_*` events report the session's actual state setter. STARTING, PAUSING,
RESUMING and FINISHING are intermediate states, not completion. RECORDING,
PREVIEWING and COMPLETED report the matching state acknowledgement. ERROR is a
terminal error, never a playable-preview claim. `release_returned` means the
camera controller release API returned, after which that trace is retired.
A RELEASED state alone does not retire it before the pending release return. Camera flips distinguish request,
configured-session callback and first-frame callback. Legacy
`flip_frame_submitted` records its existing GL target-frame submission boundary,
not a physical display observation. Failed camera creation emits failure.

Retention `retain_request`, `retain_failed outcome=error`, and
`retain_saved outcome=ok` distinguish attempts from a successful production
persistence result. Captured tokens stay with their original callbacks, and
replacement takes retire older tokens. A missing success remains unknown.

Split `long_*` events retain the existing selected ranges, part index, converter
outcome, actual per-track format duration, sample count, first/last/current PTS,
sync count and monotonicity, plus typed rejection. A work-scoped correlation and
elapsed delta are appended during preparation and removed on return or failure.
A standalone inspection outside preparation has no work correlation.
`split_cleanup outcome=ok` means every owned file was absent or its delete
returned true. A refused deletion emits `outcome=error` and the existing typed
cleanup failure; this still does not prove durable filesystem removal. No validation,
audio timing, threshold, retention or send decision is changed by diagnostics.

## What logs cannot establish

Missing events, a crash, queue overflow, rotation or retired callbacks mean
unknown. No event proves audible quality, intelligibility, gapless parts,
perceptual A/V synchronization, physical camera/microphone release, or content
coverage without inspecting the relevant media under a separate permission.
Logs contain no media bytes, file paths, user identifiers, raw exception text or
credentials. Native callbacks establish their documented API boundary only.

Host controls execute the production trace helper and EOS observer, exercise
replacement/late callbacks and thread-local cleanup, and use the actual release
log exporter to check ordered typed events, privacy, rotation and clearing.
Android rendering, TalkBack, hardware and OS-crash survival remain device checks.
