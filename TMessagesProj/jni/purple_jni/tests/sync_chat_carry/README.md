# Carry dispatcher host suite

Run the registered sync-chat-carry configuration through the canonical host-suite
runner. It consumes actual AGP classes, fresh JNI and core fixtures, and reuses the
executor suite's move fixture. Business-class origins are asserted against AGP.

`PurpleSyncChatCarryCases` drives the production `PurpleSyncChatCarry` as the Port
of a real `PurpleSyncChatMove`. Everything below the dispatcher's `Env` seam is
injected and synthetic: discovery reads, inventory scans, exact reads, uploads and
writes answer from the fixture's records, so no request reaches a network.

Coverage, by case:

- `happy`: forward and own-upload moves complete; one record write and one mark
  write each; the own upload carries the permit's bytes; updates reach the app.
- `pending`: a readable pending record is posted into the old chat through the
  upload path; a protected one never is.
- `cleanup`: Cleanup, CleanupRead and CleanupDelete delete the copied record (by
  its scanned receipt) and the old mark (by an exact read).
- `unknownReplies`: missing fresh info, an incomplete inventory and a failed
  read-back each hold without a further write.
- `partialWrites`: a refused forward or own send is sent once and holds; close
  during a write cancels it and suppresses its reply.
- `floodRetry`: after FLOOD_WAIT a forward renews through the controller and
  resends once; an own record resends once with the same upload.
- `losingOwnMark` (decision D-M2B5-13): after this move posts its own mark 90, a
  newer rival mark in the same chat or another chat, visible at the uninterrupted
  ReadMark or right after the mark write, holds the move with ResolutionChanged.
  Nothing commits and nothing in the old chat is deleted; an older rival is the
  control that completes.
- `unprovenOwnMark` (decision D-M2B5-13): at the uninterrupted ReadMark, a fresh
  search that still names the opening winner, or an exact read of the own mark at
  another date than the winner's, holds with ResolutionChanged. Nothing commits and
  nothing in the old chat is deleted; a retry completes once the own mark is the
  proven winner.
- `fences`: close or cancel called from inside a run's own release suppresses
  its reply, and close inside a pass's release suppresses its frame; nothing is
  handed over after either returns. Close and cancel called inside a reply,
  status or frame callback on the delivering thread return promptly (checked
  with a timeout) and nothing is delivered after them.
- `failedReads`: a failed scan in Source, Destination or CleanupRead, or a failed
  exact read in Mark, replies null; the move holds OutcomeUnknown and deletes
  nothing in the old chat. An absent own mark stays a messageEmpty response.
- `partialReads`: partial reads that still return data are Unknown too. An
  inexact or unscoped exact page at Mark, and a Source scan whose history is
  complete but whose candidate downloads failed, reply null; the move holds
  OutcomeUnknown without commitment, deletion or forward.
- `committedWindow`: the env reads state.json and the admitted cache from disk,
  as production does. An ordinary move and Follow pass the window between
  CommitState and CompleteCache, where state is bound to the target while the
  cache still names the source: Fresh returns the real resolution, CompleteCache
  runs and the move reaches Done. Fill and same-chat RepairMark pass the same
  window without a mismatch. A mismatch no journal owns still pauses as
  BoundChatMismatch: a freshFrame (in the window or idle), a target binding
  whose state hash is not the journal's next state, and a state bound to a
  third chat.
- `windowWinner`: one change on the first Fresh in that window (the own mark left
  out of discovery, removed, or with another date, or a newer rival mark in the
  target). Switch and Follow both hold ResolutionChanged before CompleteCache,
  keep the admitted cache at the source, send no second mark and delete nothing.
- `lifecycle` and `frames`: Port identity, open refusal, idempotent close,
  `cancel(command)`, and the read-only `freshFrame` (pending journal included,
  null on loss or unknown stream, nothing after close).

The production `Env` (ConnectionsManager, UI thread, app update handling) is
covered by the AGP compile only; see [the move contract](../../../../../docs/sync-chat-move.md).
