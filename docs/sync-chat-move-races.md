# Seeded move crash and race host harness

`sync-chat-move-races` drives the production `PurpleSyncChatMove`, actual AGP
business classes, the real JNI/core bridge, and private durable files through
synthetic ports. Three distinct device/install identities share one synthetic
cloud. Each restart creates a new executor over the original directory and
reopens its actual journal, state, cache and retained files.

Run the registered suite with `--only=sync-chat-move-races` through
`purple/run_host_suites.py`. The normal runner compiles its prerequisites.
The focused driver can instead consume matching existing AGP receipts, JNI and
fixture outputs. It checks the Purple production source cohort against the
AGP source receipt and asserts business class origins at runtime. Each compiler
call still goes through the runner's compile wrapper. Default seeds are
271828, 314159 and 424242. Repeat `--seed` to select seeds, or use
`--scenario matrix` / `--scenario races` to reproduce a subset. Failures include
the seed and named case. The driver prints the full nested output, retaining
completion records and failure diagnostics.

## Priority 1

The matrix covers Deliver, VerifyDestination, PostMark, ReadMark, CommitState
and Cleanup before dispatch, after the effect/read before its acknowledgement,
and after a durable transition. CommitState's middle window uses the existing
rename-before-directory-barrier fault point. Cleanup's durable window requires
an actual persisted `absent` deletion receipt. The port intercepts the real
executor command; it does not substitute a core event for the operation.

A mark authorization is already durable before dispatch. Losing its callback
therefore holds without a blind resend, even if the synthetic cloud never
received the mark. An unacknowledged visible mark also holds with its frozen
intent. Successfully acknowledged delivery reads N after reopening rather than
creating another copy. State ledgers are checked against the bound chat's
actual bytes and this install's writer identity. Unrelated and legacy messages
remain in O.

## Priority 2

Seeded scheduling interleaves three executor queues. Duplicate callbacks are
sent both immediately and later in shuffled order, including callbacks from a
closed executor after its replacement has reopened the files. Simultaneous
switches stop all installs at mark intent and then at readback, so the shared
same-second winner exists before any commit. Losing operations retain their
frozen source/target and attempted mark, never commit and never delete O.

The chain uses one install moving O to N1, another initially bound to N1 moving
N1 to N2, and an offline third install following from O after publishing a late
own record. Follow deletes only its verified eligible late O record. It keeps
another install's O record. Unknown/protected evidence and an uncorroborated
ChatGone label must not become affirmative ChatGone; the positive control
supplies scoped definite evidence.

## Existing coverage and priority 3

The existing [executor fixture](../TMessagesProj/jni/purple_jni/tests/sync_chat_move_executor/README.md)
covers actionRestarts, durabilityRestarts, pendingRestarts, pendingProofs,
lifecycleCallbacks, observerAndForeground, quiescence and lockOrder. The [store suite](../TMessagesProj/jni/purple_jni/tests/sync_chat_move_store/PurpleSyncChatMoveStoreCases.java) covers write/rename/directory-barrier/CAS faults and newer/invalid journal
refusals. [I/O](../TMessagesProj/jni/purple_jni/tests/sync_chat_move_io/PurpleSyncChatMoveIOCases.java) covers scoped record receipts, wrong peers, changed metadata,
unknown/incomplete inventories and renewal races. [Cleanup](../TMessagesProj/jni/purple_jni/tests/sync_chat_move_cleanup/PurpleSyncChatMoveCleanupCases.java) covers changed source
bytes/ownership, partial deletion and explicit skip/retry. A5j cites those
cases instead of duplicating the remaining phase and storage matrix.

## D1 native qualification boundary

The new cases are in [PurpleSyncChatMoveRaceCases](../TMessagesProj/jni/purple_jni/tests/sync_chat_move_races/PurpleSyncChatMoveRaceCases.java).
These are host fixture mappings for the later D1 N6-N13 manifest, not native
or Telegram observations:

- N6 routes: existing I/O wire/readback and C0 ownership cases. Actual forwarding
  flags, server ownership and archived routing still need native qualification.
- N7 restart: the new crash matrix plus existing executor/store fault cases.
  Real host fsync/rename and process-local reopen do not prove Android barriers
  or physical power-loss durability.
- N8 Follow/Fill: existing executor modes and consent cases; the new chain
  exercises Follow. Native foreground, reader and Pause behavior remains open.
- N9 offline late post: the new three-install chain, with exact own late cleanup.
- N10 protected/gone O: existing pending/protected cases and the new affirmative
  ChatGone control. No live chat is protected or deleted by this suite.
- N11 cleanup/stale: existing cleanup/stale-mark cases and the new sentinel and
  durable cleanup restart checks. No whole-chat or whole-channel deletion.
- N12 convergence: the new simultaneous-switch and chained-move schedules.
  Synthetic ordering is not proof of Telegram completeness or server lag.
- N13 Lost/lifecycle: existing recovery, consent and executor lifecycle cases.
  No device session, account, login or background lifecycle is exercised here.

All production activation constants remain OFF. No network port, native UI,
Telegram request or device fixture is used by this harness.
