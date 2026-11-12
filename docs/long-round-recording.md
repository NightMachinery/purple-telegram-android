# Longer local round recording

The Media recording settings screen offers a per-account local recording length
of 1, 3 or 5 minutes. The default is one minute. This changes recording length,
not Telegram's circle format: circles remain at most one minute. Local does not
mean offline: the delivered native recorder already uploads temporary file parts
while recording, before message-send approval. This feature does not redesign
that transport behavior.

The local preference uses the account's existing main SharedPreferences. It does
not change the shared core settings schema. UniversalFragment UItem buttons,
AlertDialog item choosers and the stock Minutes plural supply settings UI. New
explanatory text lives in purple_strings.xml.

Legacy captures account, recording guid and the selected limit in each native
recorder. A recorder-handler deadline and codec admission use a cumulative active
budget, excluding paused time. Neither depends on composer, ring or dock draws.
The existing half-second stop margin applies in expanded, docked and pending
discard-confirmation states. Video PTS at or beyond that boundary are rejected;
PCM is restricted to complete mono16 samples before it, including cumulative
resume offsets, and empty EOS timestamps cannot exceed it. Encoder padding and
video-frame duration remain codec-dependent. The500ms reserve accommodates the
nominal30fps frame and1024-sample AAC packet; host tests prove input admission,
not measured Android output duration or instant camera/microphone release.

The native deadline posts exactly one generation-fenced call to the existing
local-preview stop. While that UI callback is delayed, admission remains closed
for over-budget input. Cancellation or another recorder cannot be replaced by
the old callback. Pause removes its deadline, resume uses only remaining active
time, and stop/start failure retires it. No cap callback sends a message or
changes the captured destination. Existing native stop/drain, file ownership and
original retention paths remain in charge of finalization. The legacy progress
ring and composer timer use the captured limit as before; the composer keeps its
half-second margin as a compatible early stop. V2 retains its existing immutable
RoundVideoSession limit and independent session deadline, trim/resume admission
and finalization. This correction does not change that backend.

Native encoders stream to disk rather than retaining a take in Java memory.
V2 remuxing uses its existing bounded2MiB sample buffer. Increasing local length
increases disk usage and processing time; it changes no capture codec, source
resolution or global camera policy. Hardware startup, real audio/video duration
and release still require a device pass.

The original38 preference/timer/ring/drain/V2 checks remain. A separate host
regression compiles complete legacy start, prepare, audio/video admission,
pause/resume, stop, send and cancel methods, plus actual dock float/discard,
composer-release and strip confirmation callbacks. Exact-parent controls fail
independently for deadline, video and PCM admission with a correct one-minute
preference, compact mode, a pending discard dialog and zero composer draws.
Corrected checks cover1/3/5 minutes, delayed UI completion, confirmation choices,
pause/resume, changed preferences and stale recorder callbacks. Clock, handler,
window, audio, codec, GL, muxer and file-ownership behavior remain inert ports;
player setup models its native EGL release. These checks preserve original bytes
and journal registration, but the real store/retention suite remains the source
of durability evidence. No device duration, hardware-release latency or sample
continuity is inferred from these host checks.

The EOS regression additionally executes the complete native drain and actual
AudioRecord producer body through queued EncoderHandler dispatch. Its stateful
codec port consumes each output once, retains EOS flags and delays output or
input availability. Exact44ed parent controls detect waiting after consumed EOS
and missing terminal audio with no first video or PCM. PurpleLegacyCodecEos
retains input/output completion across every drain, resets only codec-generation
state on prepare, and remembers successful video writes across resumed segments.
Final drain submits a single zero-byte audio EOS if the producer could not align
any PCM. It continues through delayed format/sample output until EOS, including
when an earlier nonterminal drain already consumed audio EOS. A playable local
preview additionally requires successful muxer finalization. Both native finish
branches acknowledge success; the background branch keeps its completion latch
even if the encoder waiter is interrupted. Successful sample writes returning0
remain successful. A genuinely empty take follows the existing too-short path.
The codec, hardware, muxer and file ports remain synthetic; these checks do not
measure Android codec progress, encoded duration or physical hardware release.
The original171 admission assertions and all previous suite floors remain.

A nonempty failed local preview first reserves its registered legacy session
and writer. The registry worker syncs recovery ownership, flushes the journal,
and uses the existing failed-recording store policy to retain the writer in the
bin, irrespective of keep-discarded. Only a durable acknowledgement permits the
captured recorder to emit its terminal UI event and retire. The acknowledgement
is fenced by recorder identity, captured account/guid and writer path; it cannot
end a replacement session. A superseding start or late cancel cannot drop the
reserved entry before settlement. If persistence fails, codecs are already
released but the native UI owner stays latched; the original manifest/journal
and held ownership remain available for recovery instead of reporting success.
Failed reservations also live in a separate writer-identity claim map until
settlement, independently of the active session. A failed copy or manifest save
schedules a worker retry against that same claim; Cancel, end and replacement
cannot remove it. Native Cancel still releases its UI file reference and emits
its ordinary stop event, but unlink of a reserved writer is fenced until the
owned bin copy and settlement succeed. The camera File deletion override and
encoder cleanup share that fence. A failed SD writer stays at its registered
temporary pathname instead of being relocated, and a later SD take gets a fresh
temporary name rather than reusing the reserved source. Successful settlement
retires the claim and restores ordinary source deletion. Retries retain media
locally and never send a message.

A process loss before the worker syncs recovery intent retains only the prior
manifest guarantee; this does not introduce a new crash transaction/schema.
After synced intent, a failed copy followed by native Cancel keeps the named
writer and journal discoverable at restart even while the bin destination is
unavailable. The host regression executes actual Cancel, reason6 registry
session discard and Store reconstruction before any successful retry for both
indexed and unfinished media, then restores storage and verifies one settlement
after replacement without touching its writer. Its SD case checks native failed
finalization and protected temporary-name selection. Camera, codec, repair and
Android filesystem behavior remain ports or device qualification requirements.

The finalization regression composes complete native stop/drain/producer and
registry dispatch with real Sessions, Store, Files and synced Manifest classes.
It reconstructs the store for a finished prefix and unfinished failed outputs,
with keep-discarded off, and verifies journal ownership and captured target.
Its throwing muxer covers synchronous and background finish, including the latch
under interruption; further cases cover failed bin persistence/retry, stale
account/generation, successful pause/resume and truly empty output. Exact7290
parent controls lose the reconstructed owner or advertise a failed preview.
MP4 boxes, journal bytes, codec/hardware and the repair result remain ports;
these are persistence/protocol checks, not Android output or repair-codec proof.
All121 EOS and171 admission assertions and previous suite floors remain.

The upstream InstantCameraView seams capture one PurpleLegacyRoundBudget per
VideoRecorder, arm it when audio capture starts, pause/retire it with native
transitions, and guard video and PCM codec input. A small private callback must
access recorder identity and the existing private send(3) path; PCM admission
must set the native EOS/drain flags. Those two seams stay beside the native
state they control. Duration policy and scheduling live in Purple's helper.
Codec EOS submission/release acknowledgements and the preview guard also stay
beside native codec and muxer operations; their persistent state lives in
PurpleLegacyCodecEos rather than changing the native queue or muxer design.
The terminal failure handoff uses PurpleRecordingRegistry/Sessions and the
existing failed-store policy; no native file deletion or send route is added.



For recordings longer than one minute, normal Send prepares circle parts by
default and opens a single review with the part count and retained-original
notice. The primary button sends those parts. A secondary button prepares one
ordinary full-length video instead and returns to review. Neither path sends
until the final review and any stock paid-message confirmation are approved.
The Recording bin marks these entries as long local recordings and offers the
same default. The retained file's measured duration decides whether the long-output review is
needed. An estimate alone never defines a selection. A genuine legacy preview
selection is validated in microseconds against measured source bounds and limits
both outgoing formats to that selected range. Invalid ranges are refused without
widening them. The full unedited original stays separate from selected outputs.
V2 physically materializes its selection before delivery and resets offsets, so
that resulting file is not trimmed a second time.

Circle preparation balances consecutive source intervals with a 59.5-second
planning ceiling, avoiding a tiny final tail. All parts are prepared and checked
before enqueue. Each has independently encoded H.264 video and AAC audio,
measured duration at most 60 seconds, square dimensions, an opening video sync
sample, and bounded timestamps. A scoped MediaCodecVideoConvertor extension
accepts an owned AudioInput and its microsecond duration. PurpleLongRoundAudioInput
excludes fully preceding decoded blocks and uses the native decoder's partial
PCM trim, so prior-sync AAC packets are not blindly copied across a boundary.
The owned audio input emits exactly the selected-range sample budget. If the
selected source audio ends early, it preserves every decoded sample and fills
only the uncovered tail after an observed clean codec EOS. A decoder stop
without EOS, codec failure, timeout or cancellation cannot become silence.
Release and codec retry reset both the budget and EOS observation. Validation
and trim bounds remain unchanged. The caught missing-output-duration diagnostic
in native AudioDecoder falls back to extractor duration; it alone is not a
conversion failure.

Ordinary video preserves all original bytes and carries roundVideo=false.
Prepared files bypass another conversion; native send code reads their actual
media attributes. No longer-than-one-minute circle metadata is emitted.

Preparation runs on one worker, checks available disk space with a reserve,
limits duration and source size, honors cancellation and a deadline, and hashes
the source before and after. Only individually owned derived files are removed
on cancellation or failure. The bin original remains under the existing retention
policy, including after successful send. User deletion and normal retention
expiry still apply. A failed preparation can be retried from that original. If circle encoding
fails, its error dialog also offers the full ordinary-video alternative without
requiring a successful circle encode first.

Each request captures account, user, target, topic, reply and send-as arguments
before asynchronous work. Each output gets a separate copy of the same frozen
send identity. Delivery queues one part at a time and advances only after local
storage acknowledgement. This proves local queueing, not server delivery. A
timeout or identity change stops the sequence; a partial or uncertain queue
result tells the user to check the chat before retrying, rather than silently
resending potentially queued parts. No durable outbox or automatic retry is
introduced. Existing short recordings retain their prior send path.

Host tests execute the production interval plan, preparation, output attributes,
queue state and owned audio adapter over inert Android ports. A synthetic long
H.264/AAC source is split through a FFmpeg converter port, then every part is
probed and decoded. This checks the preparation contract and independently
playable synthetic outputs; it does not execute Android MediaCodec, UI rendering,
physical capture or real message sending, and does not establish sample-perfect
continuity on a device. Production delivery and preview tests cover captured
identity, delayed lookup, queue acknowledgement and original retention. Device
qualification must check both recorder adapters, boundary audio and video,
cancellation, storage pressure, partial sends and the stock AlertDialog review
beside a stock confirmation in light, dark, long text and RTL layouts.

The owned stock AlertDialog review, preparation-error dialog and spinner attach
cleanup to the builder's internal pre-dismiss hook. BaseFragment and its shown
StoryViewer or PremiumPreviewBottomSheet may replace Android's dismissal
listener; that native listener remains available for playback, visual and fragment
cleanup. The internal hook runs independently and closes the active request on
cancellation, back, outside dismissal or unexpected spinner dismissal, allowing
the same bin entry to be reviewed again. A refused show closes the request too.
A deliberately replaced review marks its format choice before opening the next
spinner, and completed preparation clears that spinner's ownership before
intentional dismissal. Late worker results cannot reopen a closed request.

Host regressions execute the actual review, preparation and cleanup methods,
all three BaseFragment.showDialog overloads, both native sheet showDialog
implementations, AlertDialog.dismiss and its internal pre-dismiss setter.
Exact-parent controls reproduce lost cleanup for review, preparation error and
spinner in each sheet. Corrected code preserves both owned request cleanup and
native sheet cleanup, permits same-entry retry and keeps neutral/fallback work
alive. Circles remain the default, cancellation enqueues nothing, paid approval
covers the prepared count, and duplicate or late approval cannot restart or abort
an accepted sequence. Android window and button dispatch, handlers, workers,
media preparation, playback and texture surfaces remain ports. This source
ordering evidence does not attribute the observed device retry to an exact
runtime callback, or replace a device cancellation/retry and decoded-media pass.

Before V2 exposes a completed long preview or finishes a long active take, its
serialized file worker reserves that exact full source in the existing Recording
bin manifest. The reservation captures account, user, generation, pathname,
device and inode before copying. The worker syncs an independent bin copy,
checks original and copy hashes, then atomically commits the bin entry. Native
trim and resume require that acknowledgment. A copy or final-manifest failure
leaves the source reservation discoverable at startup, without depending on an
open descriptor or a pending callback. Repeated callbacks reuse the reservation.

The native erase hook defers its usual truncate-before-unlink operation only for
an independently protected long original or writer. Cancel, destroy and native
error cleanup can release hardware while preserving those source bytes. The
registered writer's existing snapshot reservation also keeps its full take when
this feature owns it, even with keep-discarded disabled. If a new reservation
cannot be committed, that existing writer reservation remains the recovery
fallback. Initial reservation failure aborts active finalization before remux,
completion or ordinary-entry rebinding, including cap trimming and audio removal.
A completed durable reservation with a failed copy is different: its recorded
source may coexist with the derived result until startup or a later retry saves
it. Repeated native capture callbacks must obtain the same durable admission,
including when a previous attempt failed. A failed new reservation never counts
as a saved original.

Native error handling arms the active writer before invalidating output, changing
state or publishing the stop callback, while elapsed recording time is still
available. The existing error snapshot handoff then retains the full writer even
with keep-discarded disabled; camera, writer and executor cleanup still proceeds.
Ordinary unprotected erase and the short-take discard policy remain unchanged.

Startup reconstructs ownership from the real manifest, validates source identity,
and retries the full copy before retiring its reserved pathname. A replacement
inode is refused without adopting or deleting another capture. The runtime bin
retry never sends a message. Once a synced copy and manifest are acknowledged,
native cleanup may erase the old source; the separately marked full-original bin
entry remains under normal retention. This uses the existing synced-file and
atomic-manifest contract, without a stronger claim about directory durability,
power loss, or recovery when the underlying storage itself is unavailable. Only
newly recorded media is retained. Host regressions execute the actual native
erase, native error and active-finalization methods, store and manifest code with
controlled I/O failures and reconstructed stores. Camera, handler and executor
ports check admission and cleanup ordering; Android filesystem behavior, real
executor timing and hardware release still require device checks.

Muted Camera2 results may contain video only. Sending one ordinary video
preserves that track set and the selected range. Circle parts encode explicit
AAC silence when the selected source has no audio, without opening a decoder
for the retained unmuted original. Every derived track set and duration is
checked before review. Synthetic host decode covers silent outputs; Android
codec behavior still needs device qualification.

After cancelling native paid approval or its Stars sheet, an explicit Send can
replace only that same account owner’s payment request with zero enqueue attempts.
The old generation closes before the new review opens; late payment callbacks
cannot send or revoke the replacement. An approved or attempted send remains
protected against replacement and blind retry.

A native-audio host regression executes the converter audio-initialization block,
complete AudioDecoder/AudioRecoder and PCM conversion methods, and native Track
sample timing with the unchanged production part validator. Short AAC tails
reproduce a parent coverage rejection. Corrected checks retain selected PCM,
fill the clean-EOS residual budget, reset on retry and propagate failed or
interrupted decoding. Codec, extractor/metadata, AAC packet bytes and video/GL
remain ports; this does not identify the exact device terminal failure or prove
Android encoded output, decoded continuity or physical codec progress.
