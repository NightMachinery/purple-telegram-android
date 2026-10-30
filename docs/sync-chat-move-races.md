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

Every scenario ends with a conservation check. The cloud remembers the chat
where each settings record's bytes were first published. Each such record must
still be in that chat or be present, byte for byte, in the install's final
bound chat (the winner chat for the race). A completed crash window must also
have deleted old settings, so the check covers real cleanup rather than an
untouched O.

## Priority 2

Seeded scheduling interleaves three executor queues. Duplicate callbacks are
sent both immediately and later in shuffled order, including callbacks from a
closed executor after its replacement has reopened the files. Every operation
gets its own mark and own-record request ids, so racing installs never share a
wire request.

Simultaneous switches stop all installs at mark intent and then at readback, so
the shared winner exists before any commit. Two installs race into one chat at
the same second and the third into the other chat. The seed picks the pair's
chat (N1 for even seeds, N2 for odd) and the odd install out. The pair's chat
always wins, either by chat order at the same second or because the odd
install's mark is one second earlier. So every seed has a loser whose mark lost
inside the winning chat, and the default seeds change the winning chat. Losing
operations retain their frozen source/target and attempted mark, never commit
and never delete O. A finished operation must hold the winner's exact mark.

The chain uses one install moving O to N1, another initially bound to N1 moving
N1 to N2, and an offline third install on O with a queued local edit. Its Follow
releases the edit into O after both marks: a late own post whose bytes exist in
no chat yet. Follow must carry those bytes into N2 and only then delete the O
copy. It keeps another install's O record. A control hides the carried record
from N2's readback: Follow holds before commit and the late O post survives.

ChatGone needs two agreeing signals: the port's source access label and the
scoped direct read. At Plan, a ChatGone label with a valid direct read holds
before any effect; with both signals the move completes from the own record
alone and deletes nothing from O. At Cleanup, Unknown/protected evidence and an
uncorroborated ChatGone label must not become affirmative ChatGone; the positive
control supplies scoped definite evidence.

Protected at Plan needs no second signal. With a direct read that agrees, and with
one that contradicts the label (O actually forwardable), the move carries only
this install's own record and leaves O untouched, while the Readable control
forwards and cleans O. A wrong Protected label therefore loses, duplicates,
misdirects and deletes nothing; the other installs' records stay in O.

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
- N9 offline late post: the new three-install chain, carrying a late edit's new
  bytes before exact own late cleanup, plus its lagging-readback hold.
- N10 protected/gone O: existing pending/protected cases, the Plan Protected
  controls, and the new Plan and Cleanup ChatGone controls. No live chat is protected or deleted by this suite.
- N11 cleanup/stale: existing cleanup/stale-mark cases and the new sentinel and
  durable cleanup restart checks. No whole-chat or whole-channel deletion.
- N12 convergence: the new simultaneous-switch and chained-move schedules.
  Synthetic ordering is not proof of Telegram completeness or server lag.
- N13 Lost/lifecycle: existing recovery, consent and executor lifecycle cases.
  No device session, account, login or background lifecycle is exercised here.

All production activation constants remain OFF. No network port, native UI,
Telegram request or device fixture is used by this harness.
