# Review: Android F10, crash-safe recordings and the Recording bin

Branch `recording`, head `1456bd45f7`, scope `f1eef62b5..1456bd45f7` (three commits: the purple-core bump with
the settings bridge and the AAC measure shim; voice registry, discovery, Ogg recovery and the bin; the round
journal hooks, journal, recovery and repair binding). Read against `docs/media-recording-design.md` (F10, its
Settings, Upstream hooks and Tests parts, and the owner's answers at the top) and purple-core
`docs/recording-formats.md` at the pinned gitlink. Encryption at rest is out of scope by design.

Verdict: **CHANGES**. P1 = 4, P2 = 7, P3 = 5.

The byte-level work is solid: the journal encoding, the Off mode, the Mp4Movie hooks, the core's repair and the
MP4Builder rebuild all hold up under tracing. The defects are in the registry's event handling. It keys sessions by
`account:guid` and treats any repeated `recordStarted` as a resume. Several stock upstream flows never send the
event the registry waits for, and those flows are pause into preview, send from preview, discard from preview and
resume. Three of the four P1s come from that. The fourth is V2's `erase`, which truncates the file before unlinking
it.

## What was checked and holds

- Gitlink `TMessagesProj/jni/purple` is exactly `a6631f8de3e813096b70dc63d01268fce009e4fd` (populated and
  checked out at that commit).
- `purple/upstream-hooks.sh check`: OK. `python3 purple/run_host_suites.py --list`: the list and drivers agree
  (38 configurations). The host suites themselves were not run on this VM.
- Hook shape. `Mp4Movie` (+2), `InstantCameraView` (+1) and `RoundVideoMp4Writer` (+1) are one fully qualified call
  each, with no comment and the baseline updated. The hooks do not change `Mp4Movie` output: with nothing
  registered, `onTrack`/`onSample` return at the volatile `any` test. With a registered movie, `track()` and
  `flush()` catch `Throwable`, and `sample()` only fills an array.
- Journal Off still writes the config header: `track()` opens the file and appends the start and track records
  whatever the interval is, and `sample()` returns at `intervalSeconds <= 0`.
- The journal's offsets match MP4Builder. `addSample` gets `dataOffset` after a new `mdat` header is written. The
  split header is written in place as size 16, `mdat` and eight zero bytes, and the core skips that form. The
  "to verify on Android" note in `recording-formats.md` is answered correctly in the README.
- Disk full or a write error stops the journal quietly (`stop()`, one `journal_stopped` line). MP4Builder is not
  touched.
- Rebuild: `Track` reads only width/height/MIME/csd-0/csd-1 (and optional profile/level) for video and sample
  rate/channels for audio. All of these come from the journal, so `addTrack` cannot throw on a rebuilt format.
- The diag log gets tokens and counts only, and every new token is in the allowlist. The manifest keeps chat ids
  inside `files/purple-recordings/`, as the design says.
- `bin_keep` choices, including `7d` through the core's new `d` suffix, and `off` meaning never, go through
  `BinItemExpired`. A core that cannot be asked expires nothing.

## P1

### P1-1. A recording that ends from preview never closes its session, so the next recording in that chat is not protected and preview discards never reach the bin

`PurpleRecordingRegistry.started` (`existing != null` means "a resume after a pause into preview") and `didSent`
(draft means `paused = true`) assume a paused session always gets a later stop or send event. Upstream sends none
when the recording ends from preview:

- Voice, send from preview: `ChatActivityEnterView.sendMessageInternal` (the `audioToSend != null` branch) calls
  `MediaController.cleanRecording(false)` and `SendMessagesHelper.sendMessage` directly. That path has no
  `recordStopped` and no `audioDidSent`.
- Voice, discard from preview: `ChatActivityEnterView` deletes `audioToSendPath`, calls `cleanRecording(true)`, then
  `stopRecording(0)`. Legacy returns early at `audioRecorder == null`, before it posts `recordStopped`. V2's
  `cleanRecording` has already run `finishAudioRecorderV2Lifecycle`, which clears `audioRecorderV2Active`, so
  `stopRecording` takes the legacy branch and posts nothing either.
- Rounds, send from preview: legacy `InstantCameraView.send(4)` stops the encoder or calls `sendMedia` without a
  `recordStopped`. V2 `InstantCameraView2.send` posts `recordStopped(5)` only when the state is `RECORDING`.
- Rounds, discard after pause: `cancel()` does post `recordStopped(0/6)`, but `checkDiscarded` returns at
  `session.paused`. `resumed()` only clears `paused` for voice sessions, and the `recordResumed` that a round resume
  posts has no guid.

Trace (V2 voice, the default recorder): record in chat A, lock, stop into preview, then send. The session `n:guidA`
stays in `sessions`, paused, and its entry stays `recording` with `media` set to the sent preview file. Now record
again in the same chat. `recordStarted(guidA)` finds the existing session, resets its flags and returns. No
`discover` runs, because only `recordResumed` re-discovers. Then the app crashes mid-recording. At the next start,
`recoverAtStart` finds the entry's file is the old, clean preview file, counts it as `finishedCleanly` and drops the
entry. The new recording's unfinished `audio_recording_voice_*.ogg` is in no entry, and the orphan scan has already
run once. The recording is lost, which is exactly what F10 exists to prevent.

The same lingering session explains why preview discards never become "Discarded" bin items, even though the
owner's answer 1 (discarded recordings go to the bin, `keep_discarded_p` default on) covers them. The README's
"Known limits" mentions the voice case. It does not mention rounds discarded after a pause, and it does not mention
that the next recording in the chat goes unprotected.

Fix: end a paused session explicitly. Treat a `recordStarted` for a key whose session is paused and whose preview
was consumed as a new recording, or observe the upstream signals that do fire (`recordPaused` and the draft being
cleared, or the preview file being deleted or uploaded). Then run the discard check for paused sessions too.

### P1-2. A stale round session captures the next round's registration in any chat, so the recovered round is filed under, and sent to, the wrong chat

`roundRegistered` attaches a registration to the round session with the latest `startedAt`. It ignores account,
guid and whether that session is paused or stopped. Combined with P1-1:

1. Chat A: record a round, stop into preview, send from preview. Session A lingers and its entry has `dialogId` A.
2. Chat B: record a round. Both recorders register before `recordStarted`: legacy in `prepareEncoder`, and V2 in
   `RoundVideoSession.start()` through `createOutputGeneration`, before the state reaches `RECORDING`. The
   `RoundVideoMp4Writer` registration also lands here. `roundRegistered` runs on the worker, finds session A as the
   latest round session, and attaches B's file and journal to A's entry. Then `started(B)` creates session B, but
   `pendingRoundMedia` is null, so B's entry never gets a file.
3. The app crashes during B's recording. At the next start, entry A (dialog A) points at B's unfinished file and
   journal and is recovered as chat A's item. Entry B has no media and is dropped as `missing`.
4. In the bin the round shows under chat A, and "Send to the original chat" sends B's round to chat A.

A wrong-recipient send is worse than a lost file. Also, `end(B)` closes `B.entry.journal`, which is null, so B's
journal stays open after a normal send. Fix: never attach to a stopped or paused session, and pair a registration
with the session that `recordStarted` names. For example, carry the pending registration until the matching
`recordStarted` arrives, and drop it when another one arrives.

### P1-3. Pause into preview re-points a round's entry to the finished preview snapshot and drops its journal; a crash after resume recovers nothing

On pause both round recorders post `audioDidSent(guid, videoEditedInfo, previewPath, ...)`. Legacy does it from
`handlePauseRecording` after `mediaMuxer.finishMovie(previewFile)`, and V2 from `onPreviewReady` with
`session.getPreviewFile()`. `args[1]` is never null for rounds, so `didSent` takes the draft branch:
`session.hold(previewFile)` and `store.attach(entry, previewFile, null)`, which sets `entry.journal = null`.

On resume, legacy keeps writing the same `videoFile` through the same movie and does not register again. V2 also
keeps the same writer when the trim did not change (`rebuildForResume` makes a new generation only when
`trimChanged`). So the entry keeps pointing at the preview copy. Crash after resume: `recoverAtStart` sees the
preview file has `moov` (`finishMovie(into)` and `createPreview` write one), counts it as `finishedCleanly` and
drops the entry. The real recording and its `.pjr`, which the journal is still writing, are never recovered, and
the `.pjr` is never deleted.

Fix: for rounds, `audioDidSent` with a preview path should not replace `media` or `journal`. The registration is the
only source of the round's file.

### P1-4. A discarded V2 round is lost: `RoundVideoRemuxer.erase` truncates the file before deleting it, so the held descriptor reads zero bytes

`InstantCameraView2.cancel` posts `recordStopped(0/6)`, and then `RoundVideoSession.finishCancellation` runs
`deleteMediaFiles`, which calls `RoundVideoRemuxer.erase(file)`. That is `setLength(0)` and then `delete()`. The
registry's discard path relies on the descriptor it holds still seeing the bytes after the unlink. That works for
voice and for legacy rounds, which only `delete()`. Here the inode itself is emptied.

`checkDiscarded` (2 s later) finds the file gone, and `PurpleRecordingFiles.copy(held, target)` copies
`from.size() == 0` bytes. `settle` then gets `mp4Finished` as -1, and `RecoverRound` drops every journaled sample
as past the end, so the item becomes `unrecoverable` with an empty file. With `keep_discarded_p` on (the default),
every discarded V2 round shows in the bin as "Could not be recovered" and holds nothing. Fix: copy at the stop, or
on the first discard check, before upstream's file executor can run `erase` (for example, copy synchronously in
`stopped()` for reasons 0 and 6 while the file is still whole, then delete the copy if a send follows). Or link the
file (`Os.link`) at the stop.

## P2

### P2-1. Legacy voice pause and resume: a crash after resume recovers only the second segment, and a slow join can misreport a send as a discard

Legacy resume starts a new file (`recordingPrevAudioFile = recordingAudioFile`, a new `<time>_*.ogg`), and
`resumed()` re-discovers and holds only that newest segment. The pre-pause segment is not in the entry, so a crash
after resume recovers only what was recorded after the resume. At the final send, `joinRecord` deletes both
segments and writes a joined file. If the join of a long recording takes more than the 2 s before the first
`checkDiscarded`, the held segment is gone and `audioDidSent` has not arrived yet, so the sent recording is copied
into the bin as "Discarded" (a duplicate). This applies only where the server config or the user turns V2 off.

### P2-2. Rounds that enter the bin already finished carry duration 0, and are sent with duration 0

The registry never calls `PurpleRecordingStore.duration`. `settle` sets `durationMs` only from the Ogg finaliser or
from a rebuild, and when `mp4Finished == 1` it leaves it at 0. Legacy cancel runs `finishMovie()` before
`videoFile.delete()`, so every discarded legacy round is finished. The bin row shows 0:00. "Send" builds
`VideoEditedInfo.estimatedDuration = 0`, and `SendMessagesHelper` turns that into `attributeVideo.duration`, so the
recipient sees a 0 s round. Read the duration from the file (MP4 `mvhd`, or `MediaMetadataRetriever`) when it is
finished.

### P2-3. The view-once flag is never recorded

`PurpleRecordingEntry.once` exists, and the bin hides "Send to another chat" for it, but nothing ever sets it.
`ChatTarget` returns only account, dialog, topic and reply. A view-once voice or round that is discarded or
recovered is offered "Save to Downloads" and "Send to another chat". "Send to the original chat" sends it as a
permanent message (`ttl` 0 in `sendVoice`, no `ttl` in `sendRound`). The design lists the view-once flag in the
entry, and the user chose view-once.

### P2-4. Entries are keyed by account slot, with nothing done at logout

Nothing handles logout. After an account is logged out, its items list as "Unknown chat". "Send to the original
chat" still calls `SendMessagesHelper.getInstance(slot)` for an inactive slot. If a different account later signs
into that slot, the recording goes out from that other identity: user and channel ids are global, so the dialog
resolves. Check `UserConfig.isClientActivated` and the slot's client user id (store it in the entry) before sending.
Decide what logout does to the account's items: keep them as "Unknown chat" without "Send to original", or delete
them.

### P2-5. Rounds drop their entry before the message is queued

The design keeps an entry in `sending` until upstream has queued the message. For rounds, `stopped(reason 5)` calls
`end()` at once: the journal is closed and deleted, and the entry is dropped. That happens before legacy finishes
the movie and calls `sendMedia`, and before V2's `finish` (which can include a final-range remux taking seconds).
A crash in that window leaves nothing recoverable. Move the round entry to `sending` at reason 5 and finish it when
`sendMedia` has run. A `messageReceivedByServer` style signal is not available here, but a clean `moov` at the next
start already settles it as `finishedCleanly`.

### P2-6. "Send to the original chat" can miss forum topics and replies

`PurpleRecordingSend.send` resolves `top` and `reply` with `MessagesStorage.getMessage(dialogId, id)`, which reads
`messages_v2` only. Forum topic roots and topic messages are often only in `messages_topics`, or not cached at all.
When `top` is null, the voice or round goes to the forum's General topic, and the reply is silently dropped. Also,
`ChatActivity.getTopicId()` returns `threadMessageId` for saved-messages sublists, quick replies and suggestions, so
for those modes the stored "topic" is not a topic. A recording made in the quick-reply editor would be sent from the
bin as a real message. Store the chat mode, use the topic id directly (as upstream's send params allow), and refuse
"Send to original" for quick replies. Needs a device check.

### P2-7. The registry's event handling, where every P1 lives, has no host coverage

The `recording` suite tests the store, manifest, journal and recovery well. But `PurpleRecordingRegistry` (event
sequences, pause and resume, preview send and discard, registration pairing, held-descriptor discard) is not
exercised at all. The design's Tests section asks for "crash states becoming recovered" and the discard path. Give
the registry an event-driven core that runs without `NotificationCenter` (the store already shows the pattern).
Then add the sequences from P1-1 to P1-4, including a truncate-then-unlink discard.

## P3

- **Journal leaks.** Journals no session claims are never closed or deleted, and their `.pjr` files stay on disk.
  That covers V2's final-range remux generation registered after `end()`, the trim-on-resume generation's
  predecessor once `entry.journal` was nulled (P1-3), and registrations made before the registry started. The
  `.pjr` files sit next to `round_video_*.mp4` in `files/cache` and next to legacy files in `MEDIA_DIR_DOCUMENT`,
  about 110 KB per minute. Their streams stay open until GC, and `any` stays true. Also, `pendingRoundMedia` from a
  finished round's remux can be adopted by a new round started within 10 s.
- **Orphan-scan race on first start.** The one-time orphan scan runs asynchronously right after `onAccountsLoaded`.
  If a recording is already running by then (launched from a shortcut, say), its unfinished file is not yet in any
  entry (voice attaches 1 s after start), so the scan *moves* the live file into the bin. The recorder keeps writing
  the moved inode, and upstream's send then finds no file. This needs a narrow window but can only happen once.
  Skip files modified in the last few seconds.
- **Ids outside the allocated block.** `PurpleRecordingBinActivity.MENU_CLEAR = 0x70750510` and the item ids
  `0x70750600-0x707506ff` lie outside the media-recording block `0x70750500-0x7075050f` that the design allocates.
  No collision exists today, but record the extra ranges in the design. The list also silently stops at 256 items.
- **Bin edges.** "Send to the original chat" for a hidden chat of another account does nothing, with no message. A
  discard copy that fails on a full disk leaves a partial file in `bin/` that no entry owns, so it is never swept.
  The design's "deleted when the secret chat is" is not implemented. "Recording bin" appears both in Purple settings
  and at the top of the Media recording screen.
- **Docs.** The `PurpleRecordingRegistry.roundRegistered` Javadoc and the README say V2 registers "after
  `recordStarted`". It registers before (`RoundVideoSession.start` creates the first generation before the state
  becomes `RECORDING`), which is why the pending path, rather than the session path, handles it. The README's known
  limit "a send queued just before a crash can come back as a recovered item" does not apply to voice: the file is
  a clean Ogg by then, so recovery drops it as `finishedCleanly`.

REVIEW F10 android: CHANGES P1=4 P2=7 P3=5
