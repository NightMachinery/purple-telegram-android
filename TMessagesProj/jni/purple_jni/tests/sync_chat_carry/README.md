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
- `lifecycle` and `frames`: Port identity, open refusal, idempotent close,
  `cancel(command)`, and the read-only `freshFrame` (pending journal included,
  null on loss or unknown stream, nothing after close).

The production `Env` (ConnectionsManager, UI thread, app update handling) is
covered by the AGP compile only; see [the move contract](../../../../../docs/sync-chat-move.md).
