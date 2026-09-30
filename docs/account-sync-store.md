# Android account sync local store

`PurpleAccountSyncStore` is an uncalled, device-local foundation for one
opted-in account sync owner. Its default root is
`ApplicationLoader.getFilesDirFixed()/purple/sync`. An off-state `open` does not
create `purple` or `sync`. The store does no Telegram I/O and is not an
account-sync runtime. A future owner must provide the selected account, current
device identity, a core-initialized bound state, and records built by the core.

The owner must keep one store handle open while using it, close it on logout or
shutdown, and treat every status other than `Ready` or an expected `NoPending`
as a pause. A static root registry excludes two handles in one process; a
`FileChannel` lock excludes cooperating processes. The lock file remains on
disk after close. No transport may send bytes before `stageConfig` returns
`Ready`. `stageConfig` rechecks the account binding after persistence, but the
runtime must check binding and active account again immediately before sending
because the user may switch accounts after the store returns. A later transport
must reconcile its own remote record before publishing; this store's device and
binding checks do not prove remote ownership. Binding preferences are managed by `PurpleAccountBinding`.

`hasState()` tells an unjoined check whether the device has any sync state
without opening the store: it creates nothing, answers false when the root
is missing or holds only the lock file and an empty `pending` directory, and
answers true for anything else (state, a stage, an artifact, or a root it
cannot list), so a check that finds something opens the store and reports
its verdict. `stateBytes()` returns a copy of the current state while the
store is `Ready`, for the flow calls that take state bytes.

`commitConfigState(next)` persists the state an adopting apply returns. It
needs a `Ready` store and at most 4 MiB, then checks the account binding,
that `next` is canonical and was created by this device, and the core's
config data commit check: only the config data may differ from the current
state, nothing may be pending before or after, and no seen sequence may go
backwards. A ready check writes `next` atomically and rechecks the binding.
An unchanged check reports `Unchanged` and writes nothing. A pending record
or a disallowed change reports `InvalidTransition`, a malformed state
`InvalidState`, another device `DeviceMismatch`, and none of them writes. A
lost binding pauses the store with `AccountUnbound`.

The root admits only `lock`, `state.json`, and `pending`; the pending directory
admits only `config.json`. AtomicFile temporary or backup artifacts and unknown
files produce `AmbiguousFiles`. The store preserves them for inspection. It
refuses symlink leaves and bounds state to 4 MiB and the staged record to
256 KiB. The JNI core checks canonical state and the exact canonical record,
including space, install, creating device, sequence, hash, and config version.
It distinguishes a pending stage from the stage for the current confirmed
sequence. The Java store never parses either JSON document.

Crash boundaries:

- Before a stage write, the old state remains usable.
- During an AtomicFile write, a leftover `.new` or `.bak` pauses recovery as
  `AmbiguousFiles`. The store does not choose among artifacts.
- After a stage write but before the reserved state write, reopening reports
  `OrphanStage` and preserves the stage. It cannot be sent until reconciled.
- After reserved state commits, a matching stage is readable. A missing or
  mismatched stage pauses rather than inventing bytes or advancing a sequence.
- During confirmation, a crash before confirmed state commits leaves the
  matching pending stage. A crash after confirmed state commits but before
  stage deletion leaves a stage that JNI validates against the confirmed
  sequence, hash, version, writer, and space. Opening removes only that exact
  validated stage. Any other stage pauses with `PendingMismatch`.

AtomicFile and stream syncing protect ordinary process-crash boundaries, but
this does not promise power-loss durability or parent-directory syncing. The
store has no automatic repair of an orphan, missing stage, or ambiguous file.
The future runtime needs a user-visible recovery route for those verdicts.

Run `TMessagesProj/jni/purple_jni/tests/run_account_sync_store.sh` for the
host Java state-machine test. It compiles the production store against small
Android stubs and tests off-state creation, successful reservation and
confirmation, two handles, replayed stages, missing stages, account and device
mismatches, malformed state, temporary artifacts, config data commits (ready,
unchanged without a write, pending, seen sequence regression, device mismatch
and lost binding), and `hasState` without creating anything. The flow bridge
harness below repeats the commit cases against the real core. JNI syntax can be checked
with the macOS Qt Core and JDK headers used by the existing account sync
acknowledgement test.

## Settings History

`PurpleSyncHistory` is a separate, equally uncalled store for copies of
`settings.toml` that sync will keep before it replaces the file. It lives in
`ApplicationLoader.getFilesDirFixed()/purple/sync-history`, a sibling of the
store root, because the store refuses any name in its root other than its own
three. The directory is created owner-only (0700) and every file is written
owner-only (0600) through a temporary sibling and a rename, then read back.

An entry is `<id>.toml`, the exact bytes (at most 256 KiB, not necessarily
UTF-8), plus `<id>.json`, the metadata: version 1, id, creation milliseconds,
reason (`before_update`, `before_choice`, `before_restore` or `before_undo`),
a label of at most 256 UTF-16 units, the config version key when known, the
fingerprint, the size, and whether the file existed. The id is sixteen decimal
digits of the creation time, a dash, and sixteen lowercase hex digits. Reading
refuses an entry whose files are not owner-only regular files, whose metadata
is not exactly one JSON object in strict UTF-8 of at most 16 KiB with those
fields and types, or whose bytes no longer match the recorded size and
fingerprint. A list skips such entries; a read returns nothing for them.

After each save at most 30 valid entries stay: the entry the caller names as
`keepId`, when it is valid, and the newest of the others (a restore passes its
target, so a failed restore cannot prune what it was restoring; the target
then takes one of the 30 places, as on the desktop). When the store is full,
id-shaped leftovers older than the oldest kept entry are removed; other names
are left alone.

The fingerprint is the core's `SettingsFingerprint` and the version key check
is the core's `IsConfigVersionKey`, both through `PurpleSyncCore`, so no hash
or key rule is reimplemented in Java. A save with an invalid version key, or
an entry recording one, is refused. Without the native library every save is
refused.

Run `TMessagesProj/jni/purple_jni/tests/run_sync_history.sh` on macOS for the
host test of History and of the settings file helper. It compiles both
production classes with the flow bridge wrappers against small Android stubs,
builds the flow bridge as a host dylib against Qt Core, and runs three passes:
the History and file rules with a fake core (save, list, read, prune with and
without `keepId`, owner-only modes, symlinked directories, every metadata
tamper case, settings reads and writes), the same store with the real core's
fingerprint and version key check, and a pass without the library, where
every save must be refused and nothing created.

## Settings sync flow bridge

`PurpleSyncCore` wraps `purple_jni/config_sync_bridge.cpp`, the JNI side of
the shared core's settings sync flow (`purple_sync_inventory`,
`purple_sync_config_flow`, `purple_sync_config_describe` and
`purple_config_diff`). The bridge is stateless: no native object survives a
call, so every call receives the full inventory, the local state bytes, the
staged record and the settings file again, and rebuilds what it needs. Each
reply is a `RawReply` of record, state and text bytes plus JSON metadata that
`PurpleSyncCore` parses into typed, immutable answers; Java never parses
config data or sync JSON itself. An answer whose `error` is not null carries
the bridge's reason (`NullInput`, `State` for unreadable state bytes,
`InvalidTransport`, `NativeUnavailable`, `MalformedNativeReply`, and for the
account-bound calls the binding errors plus `AccountChanged`).

An inventory is a `PurpleSyncInventory`: the account's positive user id,
whether the Saved Messages scan finished, and one row per candidate message
with its id, a transport outcome, the document id, the edit date, and the
downloaded bytes when fetched. The outcomes are `FETCHED` 0, `VANISHED` 1,
`CHANGED` 2, `OVERSIZED` 3, `INACCESSIBLE` 4, `REQUEST_FAILED` 5,
`CANCELLED` 6 and `INVALID` 7 (a document whose size is not positive). Only a
fetched row carries bytes; the core classifies them itself, and bytes over
4 MiB count as oversized without being copied. A record needs its real
document id: a fetched row with document id 0 is unreadable and makes the
check need review. A download that cannot finish is `INACCESSIBLE`, never a
fetched row with empty bytes. Vanished, changed, oversized and invalid rows
make the review NeedsReview; inaccessible, failed and cancelled rows, or an
unfinished scan, make it Incomplete. The inventory reports its row count and
total fetched bytes for logging; there is no total size cap.

Candidate selection is `classifyHistoryPage`: one page of Saved Messages
history (ids newest first, whether each is a message, forwarded, a document,
its caption and file name) gives More with the next offset, Complete on an
empty page, or Stalled when the page does not move strictly downward (an id
at or above a nonzero offset, a repeated or rising id, or an id that is not
positive). A candidate is an unforwarded document whose caption holds
`#purplesync` or whose file name is a desktop sync file name.

The calls, in the order a check, apply or publish uses them:

- `review(inventory, state, staged, local, device)` gives the review: status,
  whether the device is bound, space, install, the local file's status,
  fingerprint and publishability, the verdict, the heads (each with its
  record's text), offered and same keys, the own head, the describe message,
  action, device name parts, time, count, the choices and the stamp. It is
  not account-bound, so the caller compares `accountUserId` with the account
  it checked. `Review.clientFailure` builds the AccountUnavailable,
  AccountUnbound and StoreError reviews the platform reports without the core.
- `diff(before, after)` gives line hunks (with a truncation flag past 1000
  changed lines) and the per-table summary when both sides parse.
- `planApply(request)` and `completeApply(request, readBack)` apply a choice.
  An `ApplyRequest` is immutable: `unbound` before joining, `bound` for a
  joined device, and `afterJoin` right after a join, which also carries the
  settings file as it was before the join. Complete recomputes the plan from
  the same request, so the caller keeps one request for both calls. A plan
  asks to join (`join`, with `space` empty when a new space must be created);
  after the join, `afterJoin` checks that the pre-join review still has the
  shown stamp and then expects the joined review's stamp. When the plan
  writes a remote version, its `source` head carries the text, which the
  caller checks with `isSettingsTextWritable` and writes before completing.
  An adopting completion returns the next state bytes with commit Ready, or
  commit Unchanged and no bytes; the caller persists them with the store's
  `commitConfigState`. The completion also gives the next verdict, whether a
  publish is needed, the expected parents and whether the promise shown to
  the user was kept.
- `checkCommit(current, next)` checks that only the config data changed, that
  nothing is pending before or after, that no seen sequence went backwards,
  and that `next` is exactly the canonical result. Unchanged returns no bytes.
- `planPostEntry(request, staged)` is the entry rule a publish checks first:
  Finish sending (`PostRequest.finishSending()`) needs a staged record, new
  content (`PostRequest.newContent(fingerprint, parents)`) needs none.
- `planPost(account, inventory, state, staged, local, request, nowSeconds,
  sendQueued)` gives Finish with a publish status, ConfirmFound with the
  message id and record already on the server, Stage with a new record and
  its key, or Post with the staged record. The writer is Android, Purple
  Telegram Android. `sendQueued` says the account's Telegram send queue still
  holds an unsent or failed sync record post; Finish sending then stops with
  StillSending instead of posting again. For Stage, the caller reserves the
  record with `PurpleAccountSyncCore.reserveConfigRecord`, stages it with the
  store's `stageConfig`, and calls `checkStagedPost(account, inventory,
  stateBytes, staged)` before posting. Both account-bound calls read the
  binding token with `PurpleAccountBinding.read`, refuse an inventory taken
  for another user, and answer `AccountChanged` if the active user changed
  during the call.
- `describeApplyFailure` and `undoFinished` give the core's wording choices for
  a failed apply and whether an undo finished.

`settingsFingerprint`, `isConfigVersionKey` and `isSettingsTextWritable`
answer the core's plain questions about bytes and keys.

## Sync transport contract

`PurpleSyncTransport` is the interface between the flow and Telegram. The
Telegram implementation and the fake used by flow tests must behave the same
way:

- `check(progress, done)` scans Saved Messages, reads every candidate, and
  finishes with `Finished` and an inventory, `Cancelled`, or
  `AccountChanged`. Its result also says whether the account's send queue
  held an unsent or failed sync record post when the check ended.
- `post(staged, done)` sends the exact staged bytes and finishes with
  `Confirmed` (a positive message id and the server's read-back bytes),
  `OutcomeUnknown`, `NeedsReview`, `Cancelled` or `InvalidRecord`.
  `Cancelled` means nothing was handed to Telegram.
- `cancel()` stops the running call. A new `check` or `post` replaces the
  one still running, which then ends as if cancelled.

Every method may be called from any thread; the implementation moves work to
its own threads. `done` is called exactly once per call, on the main thread,
and never synchronously inside `check` or `post`. `progress` is called zero or
more times before `done`, also on the main thread, with the phase (Scanning
or Reading) and counts. After `cancel()`, `done` still arrives once, with
`Cancelled`, unless it had already been delivered; a post already handed to
Telegram reports `OutcomeUnknown` instead, since the message may still
arrive. The Telegram implementation, `PurpleSyncTelegramTransport`, is
described in `docs/account-sync-transport.md`.

Run `TMessagesProj/jni/purple_jni/tests/run_config_sync_bridge.sh` on macOS
to build both bridges and the flow sources against Qt Core and exercise
`PurpleSyncCore` from a host JVM. It needs an org.json jar (the Gradle cache
copy is found automatically, or set `ORG_JSON_JAR`). The harness ports the
stateless cases of the core's `tests/test_sync_flow.cpp`: every review
verdict and failure status, apply and complete for every choice kind, the
join variants and the joined-stamp rule, stale stamps, the publish truth
table with ConfirmFound, Finish sending and StillSending, the commit check
refusals, diff hunks and truncation, and history page classification.

## Settings sync executors

`PurpleSyncRunner` runs the manual settings sync for one account, and the
sync screen holds one. Construct it with `new PurpleSyncRunner(account,
transport)` and call every method on the main thread; every listener is
called on the main thread too. The runner captures the account's user id when
it is built, and every step checks again, right before each write, that the
same user is still active on that account. When the user is gone the step
stops with an account status and writes nothing more.

The runner does one thing at a time. `busy()` is true from the start of a
check, apply, publish, undo or restore until its listener runs; another start
meanwhile returns `Busy`, and a start after `close()` returns `Closed`. Every
start and every issued `Check` (the reviewed result of a check) moves a
generation counter forward. A `Check` can be applied or published only while
it is the runner's `current()`; an older one returns `Stale`. A callback the
generation has moved past, because the user cancelled, started something
newer or closed the screen, is dropped without calling the listener.
`cancel()` stops a running check only. `close()` cancels the transport,
abandons a publisher that has not posted yet (it ends `Cancelled` with no
post; a record it already staged stays, so the next check offers Finish
sending), and drops every later callback.

The core work runs on one shared background queue. `PurpleSyncApply` holds
the join, apply, adopt, restore and undo steps and follows the desktop's
`ApplySyncConfigChoice`, `RestoreSyncConfigHistory`,
`WriteSettingsWithHistory` and `InitializeSyncAccountLocally`. Writes happen
in one order: History, then `settings.toml`, then the account's sync state.
A failed History save writes nothing, and a crash after the file write leaves
a device that the next check silently adopts. A device with no sync state is
reviewed without opening the store, so looking creates nothing; its first
apply joins it, with a fresh install id and, for a new space, a time-ordered
space id from the server clock.

The file write itself runs on the main thread, where every other writer of
`settings.toml` runs (`PurpleSettings.save`, the `PurpleWriter` callers and
imports). They all share one `settings.toml.tmp` sibling, and the gate reload
after a write is not synchronized, so a write from the queue could race them.
The queue therefore posts `PurpleSyncSettingsFile.replace` to the main thread
and waits for it. A write that has not started within 30 seconds is abandoned
and reported as failed, and it never runs later. `replace` reads the file
first and refuses with `Changed` when its bytes differ from the ones the step
reviewed, which an apply reports as `NeedsRecheck`. An apply writes another
device's settings, so it stores them as an import that the auto-send never
posts back; a restore or undo puts back this device's own copy and counts as
a local save.

After an apply the runner reviews again, as the desktop's ReviewAgain does,
unless the apply needs a publish. A keep-local choice (Publish or
PublishChanges) then returns a `PostTicket` that posts on the inventory the
user reviewed. A share after Choose or Conflict returns a ticket marked
`freshCheckFirst`, so the screen runs a new check and publishes that `Check`.
`PurpleSyncPublisher` does the posting and starts only from a publish call.
It holds the store open for the whole post. It confirms a record already in
Saved Messages without posting (ConfirmFound); otherwise it reserves, stages
and re-checks the staged record before posting it, and it confirms the
server's read-back through `confirmConfigReadBack`. It passes the check's
`sendQueued` to `planPost`, so a record still in Telegram's send queue gives
`StillSending` instead of a second post. Finish sending posts the staged
bytes and never new content.

Undo follows the desktop. An apply that wrote the file replaces the Undo
offer: with a new one when the History copy of the replaced file exists and
is writable settings text, and with none otherwise. An apply that did not
write the file keeps the old offer. An undo that finishes clears the offer;
by the core's `SyncConfigUndoFinished` that is Restored, Unchanged, NotFound,
FileDidNotExist, NotText or InvalidReason. An undo that ends InvalidSettings,
HistoryError or WriteError keeps the offer for a retry. A restore from the
History page that ends Restored also clears it. Restore and undo keep their
target entry through the History prune and need no account. An apply labels
its History entry with the source device's platform and short id; a restore
or undo labels it with the restored entry's id. The screen words the label
together with the entry's History reason: `before_update` or `before_choice`
for an apply, `before_restore` and `before_undo` for the others. The settings
write itself carries `sync apply`, `sync restore` or `sync undo`, which only
the reload and auto-send log lines show.

Run `TMessagesProj/jni/purple_jni/tests/run_sync_executors.sh` on macOS to
drive the runner, apply and publisher through their production constructor
against the real core, with Android stubs and a fake transport over an
in-memory Saved Messages. It needs the same Qt and org.json as the bridge
harness; set `PURPLE_SYNC_DYLIB` to reuse a built library. It walks every
verdict, and covers a crash after the file write followed by adopt, History
failure, stale stamps, join failures, Finish sending and StillSending, lost
receipts, two devices interleaving, the Undo rules, account changes mid-flow,
stale callbacks and the main-thread write handoff.
