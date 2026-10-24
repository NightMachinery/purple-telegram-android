# Account-specific modern settings sync

The Sync across devices screen belongs to the account that opened it. Its
captured Telegram environment, user id and login lifetime accompany Check,
Join, Apply, Publish, Finish, History, Undo and recovery. The selected account
and app-wide role do not choose a destination later. The immutable root owner
uses `purple/sync` and `purple/sync-history`; other accounts use the same names
under `purple/accounts/<environment>-<user>/`. Accountless sync accessors are
removed. Legacy file sending keeps its separate admission rules.

## Replacements and History

Modern apply accepts supported schema 2 through the existing account editor
publication. The exact opening primary, good copy, role, Work Mode, loaded
account files, parent identities and login lifetime stay bound to the review.
Shared core replacement impacts include every remote version a local choice
supersedes. Non-Ready impacts hold the action. Dangerous choices require an
explicit selection, account titles identify the destination, and Cancel has
focus. A later role, file, parent or lifetime change requires another review.
History restore and Undo retain the same impact and exact-byte guards.

Reads use only the destination's primary and good copy. A missing primary is
Absent, never inherited global bytes. A missing or invalid TOML primary beside
its own valid good copy blocks new-content sync. Unsafe or unreadable paths are
Invalid. Read-only History remains available. A previously staged Finish can
reconcile or post those original staged bytes under the usual core and queue
checks without replacing settings. Invalid current text is not silently swapped
for good text in a replacement impact.

A remote replacement keeps History before publishing settings, then commits
adopted sync state. History retains thirty entries per account, including the
original absence when creating a first file. An empty stream plus a truly absent
primary has a disclosed setup-only Join action without a choice: it creates only
the binding/store, no settings, good copy, History entry or post. A valid own good
copy still holds that route. The first settings file remains an explicit Save. An owned Join directory receipt
can rebase only a missing opening parent that Join itself created. An unrelated
parent replacement refuses. Apply records that account's import fingerprint;
Apply, History restore and Undo never start a legacy echo send. Account-file
refresh preserves the accepted global state while it is still current.

Operations, retained Undo and queued-send facts are account-specific. Two
accounts may wait on network work independently. A second operation on the same
account is held; a retired login cannot leave its replacement login blocked.
Closing a runner releases only its own operation lease.

## Binding recovery

The pinned core's PlanSyncAccountRecovery decides from complete root/keyed
store discovery, recognized binding preferences and exact-account cloud
inventory. Symlinks, unknown layouts, malformed preference values, unreadable
entries and duplicate token claims refuse recovery. Existing preference keys
are retained; no environment is guessed for an unmapped signed-out preference.
Signed-out accounts' retained preferences and files are inert for another
account. Their regular state/sidecar tokens remain read-only collision evidence;
inactive token reads have a separate 16 MiB budget and a 4 MiB file limit. They
retain only parsed tokens, never actionable snapshots or pending data. Exhausted,
oversized or unreadable inactive claims stay unknown and do not hold an unrelated
account. Only a claim to the destination account/store/token holds that account. Slot reuse
does not map an old numeric preference onto the new user. Writes retain the full
opening identity and expected preference value.

Before classification, Check repairs only its own store's recognized AtomicFile
state/stage leftovers under the account and store locks. It preflights the exact
regular files and names; unsafe or unknown entries refuse before repair. It does
not repair a signed-out account's store.

Check may restore only a uniquely proved keyed non-root binding automatically.
The root requires explicit Relink and fresh complete cloud/store-space proof.
Relink changes only that account's preference token. Normal pending, inventory,
file and clone checks still run afterward.

Explicit Start again uses PlanSyncStartAgain while holding the account operation
and store locks with no queued send. It creates a private unused `orphaned-date`
directory and durably publishes the exact plan and identity/byte journal before
moving anything. Pending moves first, including unknown retained pending bytes,
then state. Native descriptor checks, exclusive rename and directory fsync guard
these moves. Settings, good, backups, History, local choices and other accounts
are never archive targets.

A partial failure retains `start-again.json` and blocks setup. Finish starting
again resumes that same journal, checks exact archive bytes and identities, and
calls CompleteSyncStartAgain. Only SetupReady removes the journal and clears
that account's Undo. A valid existing preference token is reused for fresh setup;
only a missing token consumes sixteen secure random bytes. Install identity is
generated separately. No archive rollback or universal filesystem transaction
is claimed.

## Headless qualification

Use [the production-class host harness](../TMessagesProj/jni/purple_jni/tests/modern_sync/README.md).
It compiles platform adapters and fixtures only, checks every business class
origin against actual AGP output, and uses the real JNI/parser/models. It runs
A/B isolation, missing/fallback inputs, publication/Finish, retained impacts,
History/Undo, simultaneous operations, binding proof and journal failures.
The same command also runs routed/editor/transfer, existing store state-machine,
core bridge and History suites. Fixtures stay outside the repository.

Real AGP JavaCompile and affected host/arm64 JNI compilation are separate gates.
These checks do not qualify Android UI, the actual Os implementation, devices,
emulators, an APK or shrinker behavior. No upstream hook changes are needed.
