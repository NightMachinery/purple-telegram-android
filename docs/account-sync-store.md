# Android account sync local store

`PurpleAccountSyncStore` is the device-local store for an explicitly captured
originating account. The immutable owner uses `purple/sync`; other accounts use
`purple/accounts/<key>/sync`. There is no default-root constructor. Its runner
provides that account, device identity, core-initialized bound state and records
built by core. See [account-modern-sync.md](account-modern-sync.md) for current
routing, replacement guards and binding recovery.

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

`hasState(context)` tells an unjoined check whether the device has any sync state
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

The live root admits `lock`, `state.json`, and `pending`, plus recognized
private orphan archives. An active recovery journal blocks normal setup. The pending directory
admits only `config.json`. Before it checks names, `open` finishes or undoes
any write a process death interrupted, for `state.json` and
`pending/config.json`, the way `AtomicFile.openRead` does: a `.bak` (left by
AtomicFile on Android 10 and lower, which writes in place beside a backup)
replaces the file, a `.new` (left by Android 11 and later, which writes a new
file and renames it) is removed, and an empty file with no backup, the first
write Android 10 and lower started but never filled, is removed. What remains
is the version from before the interrupted write, which the caller never got
`Ready` for; only a first write on Android 10 and lower that wrote every byte
stays, because it left no backup and the file is complete. A failed repair is
`IoError`. Any other name, or a `.new` or `.bak` that is not a plain regular
file, gives `AmbiguousFiles`, and the store preserves it for inspection. It
refuses symlink leaves and bounds state to 4 MiB and the staged record to
256 KiB. The JNI core checks canonical state and the exact canonical record,
including space, install, creating device, sequence, hash, and config version.
It distinguishes a pending stage from the stage for the current confirmed
sequence. The Java store never parses either JSON document.

Crash boundaries:

- Before a stage write, the old state remains usable.
- During any write, the next `open` puts back the version before it, as above.
- After a stage write but before the reserved state write commits, the stage
  was never sent: the publisher posts only after `stageConfig` returns
  `Ready`, which needs the reserved state on disk. So when nothing is pending
  and the stage is exactly the record the core would reserve next for the
  state on disk, `open` removes it and reports `Ready`. Any other stage with
  nothing pending pauses with `OrphanStage` and is preserved, as is a stage
  with no state at all. When `stageConfig` itself sees the reserved state
  write fail and `state.json` still holds the old state, it removes its stage
  before it reports `IoError`; when the state file changed or cannot be read,
  it leaves both for `open` to judge.
- After reserved state commits, a matching stage is readable. A missing or
  mismatched stage pauses rather than inventing bytes or advancing a sequence.
- During confirmation, a crash before confirmed state commits leaves the
  matching pending stage. A crash after confirmed state commits but before
  stage deletion leaves a stage that JNI validates against the confirmed
  sequence, hash, version, writer, and space. Opening removes only that exact
  validated stage. Any other stage pauses with `PendingMismatch`.

AtomicFile and stream syncing protect ordinary process-crash boundaries, but
this does not promise power-loss durability or parent-directory syncing. The
store repairs only the interrupted writes and never-sent stages above. It has
no automatic repair of any other orphan, of a missing or mismatched stage, or
of an unknown file. The sync screen reports those verdicts as a store error
that changes nothing until an explicit, core-authorized Start again archives
them. See the modern sync recovery contract above. One interrupted write is still not repaired: on Android 10 and lower
the stage's first write goes straight to `pending/config.json` with no backup,
so a death partway through a large record leaves a truncated stage, which
pauses with `OrphanStage`. The first state write is a few hundred bytes, so a
death there leaves an empty file, which is removed.

The [production-class host harness](../TMessagesProj/jni/purple_jni/tests/modern_sync/README.md)
runs the existing store state-machine cases with AtomicFile crash injection and
also repeats actual initialization, staging, confirmation and recovery against
JNI. The state-machine suite covers off-state creation, two handles, replayed
stages, interrupted writes, commit transitions, unknown files and binding loss.

## Settings History

`PurpleSyncHistory` is a separate store for copies of
`settings.toml` that sync will keep before it replaces the file. It lives in
the captured account's `sync-history`, a sibling of the
store root, because the store refuses any name in its root other than its own
three. The directory is created owner-only (0700) and every file is written
owner-only (0600) through a temporary sibling and a rename, then read back.

An entry is `<id>.toml`, the exact bytes (at most 256 KiB, not necessarily
UTF-8), plus `<id>.json`, the metadata: version 1, id, creation milliseconds,
save sequence number, reason (`before_update`, `before_choice`,
`before_restore` or `before_undo`), a label of at most 256 UTF-16 units, the
config version key when known, the fingerprint, the size, and whether the file
existed. The id is sixteen decimal digits of the creation time, a dash, and
sixteen lowercase hex digits. Reading refuses an entry whose files are not
owner-only regular files, whose metadata is not exactly one JSON object in
strict UTF-8 of at most 16 KiB with those fields and types, or whose bytes no
longer match the recorded size and fingerprint. A list skips such entries; a
read returns nothing for them.

After each save at most 30 valid entries stay: the entry the caller names as
`keepId`, when it is valid, and the most recently saved of the others (a
restore passes its target, so a failed restore cannot prune what it was
restoring; the target then takes one of the 30 places, as on the desktop).
When the store is full, id-shaped leftovers older than the oldest kept entry
are removed; other names are left alone.

"Most recently saved" means save order, not clock time. The id and
`created_ms` hold the clock at save time, which is what History shows, and the
metadata's `sequence` is one more than the highest of the valid entries. The
list (newest first) and the prune order by `sequence`, then by id. An entry
written before the sequence existed has none, counts as 0 and so sorts below
every sequenced entry. When `sequence` is present it must be a whole number
from 1 to 2^53 - 1, or the entry is invalid.

The clock time alone cannot give save order. If entries are saved while the
phone's clock runs months ahead and the clock is then corrected, every later
save has an earlier time than they do; ordering by time would make each new
copy the oldest, and once those entries fill the store the next save would
remove the copy saved just before it. Treating times later than the current
clock as old does not help: in a full store, a single copy saved during the
jump would then be removed by the first save after the correction, although it
was the copy saved just before that one. Numbering ids after the newest
entry's time keeps save order, but then every entry saved before the real time
passes the jump shows, for good, a time just after it. With the sequence, the
prune follows save order in both cases and each entry still shows the time the
clock had when it was saved, so after a clock change the list can show times
out of order.

The fingerprint is the core's `SettingsFingerprint` and the version key check
is the core's `IsConfigVersionKey`, both through `PurpleSyncCore`, so no hash
or key rule is reimplemented in Java. A save with an invalid version key, or
an entry recording one, is refused. Without the native library every save is
refused.

The production-class harness runs the existing History rules and native
fingerprint cases. History fault injection supplies its explicit Core/Clock
ports; settings parsing always uses production JNI. Settings write-failure
cases supply the writer port rather than shadowing PurpleSettings.

## Settings sync flow bridge

`PurpleSyncCore` wraps `purple_jni/config_sync_bridge.cpp`, the JNI side of
the shared core's settings sync flow (`purple_sync_inventory`,
`purple_sync_config_flow`, `purple_sync_config_describe` and
`purple_config_diff`). The bridge is stateless: no native object survives a
call, so every call receives the full inventory, the local state bytes, the
staged record and the settings file again, and rebuilds what it needs. Each
reply is a `RawReply` of record, state and text bytes plus JSON metadata that
`PurpleSyncCore` parses into typed, immutable answers; Java never parses
config data or sync JSON itself. Native code builds that `RawReply`, and
`PurpleAccountSyncCore`'s, through JNI by class name and constructor
signature. No Java code calls either constructor, so
`TMessagesProj/proguard-rules.pro` keeps both; without that rule the release
build strips them and the first bridge call throws `NoSuchMethodError`, which
the host tests cannot show because they run without R8. Every wrapper in both
classes catches `LinkageError` (which covers `UnsatisfiedLinkError`,
`NoSuchMethodError` and `NoClassDefFoundError`) as well as
`RuntimeException`, so a future shrinker regression ends that call with
`NativeUnavailable` instead of crashing the thread. A check then ends on a
failure status, normally StoreError ("This device's sync state could not be
opened. Nothing was changed."), the log names `NativeUnavailable`, and
nothing is written or posted. An answer whose
`error` is not null carries the bridge's reason (`NullInput`, `State` for
unreadable state bytes, `InvalidTransport`, `NativeUnavailable`,
`MalformedNativeReply`, and for the account-bound calls the binding errors
plus `AccountChanged`).

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
  A completion that adopts heads, or records as seen the heads a choice
  replaced, returns the next state bytes with commit Ready, or commit
  Unchanged and no bytes; the caller persists them with the store's
  `commitConfigState`. An update or a pick can therefore commit state while
  adopting nothing. The completion also gives the next verdict, whether a
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

Every settings file carries its own `usingLastGood` fact. Reads derive this
from that primary and its own good copy, never PurpleGate's global flag. Missing
or invalid TOML beside a valid good copy holds new-content replacement and
publication. A previously staged Finish may reconcile the retained bytes under
normal inventory, state and queue checks. Read-only History works with a missing
or corrupt primary, but a replacement requires a Ready impact of its actual
opening text. The good copy is not an inferred comparison source.

`settingsFingerprint`, `isConfigVersionKey` and `isSettingsTextWritable`
answer the core's plain questions about bytes and keys.

## Sync transport contract

`PurpleSyncTransport` is the interface between the flow and Telegram. The
Telegram implementation and the fake used by flow tests must behave the same
way:

- `check(progress, done)` scans Saved Messages, reads every candidate, and
  finishes with `Finished` and an inventory, `Cancelled`, or
  `AccountChanged`. Its result also says whether the account's send queue
  held an unsent or failed sync record post when the check began or when it
  ended.
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

The production-class host harness runs the core bridge cases against the full
host library. It covers every review verdict and failure status, choice kinds,
join and stale-stamp rules, publish/Finish truth tables, commit checks, diff
hunks and history page classification. R8 remains a separate qualification gate.

## Settings sync executors

`PurpleSyncRunner` runs the manual settings sync for one account, and the
sync screen holds one. Construct it with `new PurpleSyncRunner(account,
transport)` and call every method on the main thread; every listener is
called on the main thread too. The runner captures the full environment, user id and login lifetime when
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
`cancel()` stops a running check only. A check counts as running, and
`checking()` stays true, until its listener runs, including while its review
runs on the queue, so a cancel there drops the review and a Share that would
have followed it never posts. `close()` cancels the transport,
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
first and refuses with `Changed` when its bytes, or whether the app runs from
`settings.toml.good`, differ from what the step reviewed, which an apply
reports as `NeedsRecheck`. An apply writes another
device's settings, so it stores them as an import that the auto-send never
posts back. A restore or undo skips the auto-send bookkeeping entirely, as
the desktop's restore does: it is not recorded as an import and does not arm
the legacy send after save, so it stays on this device until the user
publishes it, as the Restore and Undo dialogs say.

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
the reload log line shows.

The production-class host harness drives these executors through their shared
runtime models and real files/JNI, with synthetic transport and Android platform
ports. The previous accountless executor harness is superseded by that route.
See its README for the exact qualified boundaries and retained failure cases.

## Sync across devices screen

`PurpleSyncActivity` is Settings → Purple → Sync across devices. It is built
with the settings screen's `currentAccount` and names that account's user in
its header. There is no account list: to sync another account, switch to it
with Telegram's account switcher first. The screen builds one
`PurpleSyncTelegramTransport` for that account and user, and one
`PurpleSyncRunner` over it, when it is created, and closes the runner when it
is destroyed. Closing cancels a running check and abandons a publisher that
has not posted yet; a post already handed to Telegram keeps going in
Telegram's queue, and a later check offers Finish sending for it.

The screen shows, in order: the status line; the action row, when the current
review offers an action and nothing is running; **Undo last update**, while
the runner holds an Undo offer; **Check Saved Messages**, disabled while
something runs or when the account no longer has the screen's user;
**Cancel check**, while a check runs; the intro with the cloud disclosure; and
**History**. `PurpleSyncText` words every status: the review's message comes
from the core, the sentence from `purple_strings.xml` (`PurpleSync*` resources), in
the desktop's English except where Android has no account list.

What each action does:

- **Publish settings** (Empty), **Publish changes** (LocalChanges) and
  **Finish sending** (Pending) first run a new check, reported as "Checking
  Saved Messages again before sending". If the fresh review offers the same
  action, a confirmation with the cloud disclosure follows, but only while
  the sync screen is in front: when History or another screen covers it, or
  the app is in the background, the review shows its action row instead, to
  be tapped again. Otherwise the status says that Saved Messages or this
  device changed and nothing was sent. Confirming Publish or Publish changes
  applies with no remote choice, which joins a device that has no sync state
  yet, and publishes the returned post ticket on that fresh check. Confirming
  Finish sending publishes `PostRequest.finishSending()` on it.
- **Join sync** (Adopt on a device with no sync state) asks with the
  disclosure, then applies with no remote choice. Nothing is posted.
- Adopt on a joined device happens inside the runner's review, without a
  button; if recording it fails, the failure is worded above the status.
- **Review update** (UpdateReady) and **Choose settings** (Choose and
  Conflict) open `PurpleSyncReviewDialog`: the source device and time, a
  summary of the change against this device's text (at most six entries, then
  "and N more", or line counts when either side is not valid TOML), a
  **Show lines** toggle over a monospace diff of at most 400 lines with any
  truncation noted, the newer-schema warning when the chosen record carries
  it, and, when the choice posts or the device has not joined yet, the
  disclosure. Choose and Conflict offer each version as a radio row: the
  device name with "changed <time>" on a second line, so a long name cannot
  push the time out of view, and "This device's settings" when the local
  file can be kept. An update names its one version on a single line,
  "<device> · changed <time>". The positive button says Apply, Use this
  version, Use and share, Join with this version or Join and share. The diff
  is computed on the runner's queue.
- A choice that also posts (the runner's `freshCheckFirst` ticket) writes the
  file, runs a new check and publishes the ticket's request on that check.
  Cancelling that check says the chosen settings are on this device but were
  not sent.
- A publish ends in the publisher's result, and the action row disappears
  until the next check.

A confirmation or a review choice that arrives after the check the screen
showed stopped being the runner's `current()` (another check, an apply, a
restore) does nothing and says that the check result changed. The runner
also refuses such a Check with `Stale`.

After an apply that wrote the file, a bulletin says "Settings updated from
<device>." and, when the History copy can be put back and no share or
publish follows the apply, carries an Undo button; the Undo last update row
appears too. Both ask for confirmation, then call `runner.undo`, and the
runner decides when the offer ends. The button is Android's own: the
desktop's notice is a toast without one. When a share or publish follows,
the bulletin has no button and the row stays hidden until the post ends,
which matches the desktop, whose Undo row is hidden while something runs.
Undo while something runs, such as tapping an earlier bulletin's button
during a check, says to wait instead of doing nothing.

`PurpleSyncHistoryActivity` lists History newest first and opens at the
newest entry. Each row shows the entry's date and time, with its label on a
second line that wraps instead of being cut off; the preview dialog names
the entry as "<date time> · <label>". The label is worded from the entry's
reason and its stored label: the source device for an apply, and the
restored entry's time, read from its id, for a restore or undo. Entries
recorded when settings.toml did not exist end in "no file" and cannot be
restored. Tapping an entry previews it against the current file with the
same summary and diff, and offers Restore when the copy is valid UTF-8 text
and the current file is readable. Restore asks for confirmation in a second
dialog shown with `show()` over the preview, because
`BaseFragment.showDialog` dismisses the dialog already on screen. A restore
closes History, and the sync screen says the change stays on this device
until it is published.

Every action first checks that the account still has the user the screen was
built for, and so does the screen when it resumes. When the user is gone the
status says the account is unavailable and a running check is cancelled.

The screen is Android-only code and has no host test; the APK build compiles
it and the emulator smoke run drives it.
