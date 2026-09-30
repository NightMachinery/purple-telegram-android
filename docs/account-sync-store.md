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
mismatches, malformed state, and temporary artifacts. JNI syntax can be checked
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

After each save the newest 30 valid entries stay, plus the entry the caller
names as `keepId` (a restore passes its target, so a failed restore cannot
prune what it was restoring). When the store is full, id-shaped leftovers
older than the oldest kept entry are removed; other names are left alone.

The fingerprint is the core's `SettingsFingerprint`, read from the state text
the existing `PurpleCore.noteImported` bridge returns for the bytes, so no hash
is recomputed in Java. The core's version key check has no bridge yet; until
one is added, a save with a version key and an entry recording one are
refused. Without the native library every save is refused.
