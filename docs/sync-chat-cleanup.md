# Old settings cleanup

Moving to a new sync chat and deleting old messages are separate choices. The
production move, Follow and cleanup constants remain OFF; the controller is
qualified only with injected host ports. No Telegram request is made by this
slice's qualification.

With the cleanup switch ON, the Switch confirmation can authorize settings cleanup and old-mark removal
independently. Eligible non-Saved sources offer settings cleanup checked; Saved
Messages stays unchecked and discloses that older Purple devices may pause or
offer to start over after their settings messages are deleted. Old-mark removal
starts unchecked and requires a captured positive mark id.

## Exact authority

Cleanup starts after the shared core has verified the complete destination N and
the actual settings state and admitted cache are durable. The existing account
Carry hold remains. `SyncCarryCleanupCandidates` chooses the frozen references;
`CheckSyncCarryDeletion` or `CheckSyncCarryOldMarkDeletion` is the sole authority
immediately before each single-id deletion. Every actual transient retry obtains
fresh worker admission, a new complete N scan and an exact O reread. Capabilities
bind the opening epoch, journal revision, item and nonrepeating slot generation.

AllSettings covers this account's frozen ownership-passing settings candidates
across installs. It cannot expand to later arrivals, ordinary messages, legacy
`settings.toml` documents or playlist records. O id, date, full bytes, hash,
classification and C0 ownership must still match. A changed source, wrong chat,
missing N coverage, unknown readers or lost rights holds. Existing C6 warning
and Pause rules remain in force.

Follow uses only the core's OwnLatePosts policy, outside Saved Messages: this
install/device's issued or confirmed posts, strictly after the winning mark and
covered in N. Other installs' late posts and equal-date posts survive. Fill does
not inherit this authorization. A Switch's old mark needs its separate recorded
choice and exact positive Own supported-mark readback. No whole-channel request
is constructed.

## Partial results and recovery

The bounded account/login/operation wrapper stores a durable per-item intent
before dispatch and a result after exact absence. It retains full record bytes
and their hash; checksums bind the cleanup ledger to its scope. Unknown fields,
CAS and durability barriers follow the move store's existing rules. A failed
barrier produces no capability. A close or restart never treats an acknowledgement
as proof and never blindly resends an uncertain deletion.

Absence requires an exact same-id MessageEmpty with an explicit matching typed
peer. Zero rows, search omission, peerless MessageEmpty and contradictory shapes
remain Unknown for cleanup records and the Switch old mark. The standalone
selected-stale-mark exception in D-M2B5-12 does not broaden this rule. A definite
scoped ChatGone for O uses the core's CleanupChatGone transition.

Progress says **Moved; cleanup pending**, retaining the account write hold until
the core permits Done. Retry explicitly resumes the saved operation and rechecks
its original scope. Skip requires confirmation, records remaining references as
abandoned through SkipCleanup and allows syncing in N; it reports cleanup as
skipped, never complete. Per-item confirmed results survive partial outcomes.
Stale progress controls and callbacks cannot act on a successor operation.

The install-moved note is based on an uncopied settings record in N from this
install/device. Required carried hashes and the operation's own carried record
are excluded. Copying an old record into N does not establish that this install
has started using N.

## Qualification boundary

The canonical `sync-chat-move-cleanup` suite uses current AGP business classes,
real JNI, real local files and deterministic synthetic transport ports. It covers
scope, exact changes, permission loss, crash/reopen, conservative absence,
per-attempt admission, Skip/ChatGone and progress. Host tests do not establish
real Android lifecycle, Telegram server semantics or physical power-loss behavior.

Cleanup progress is independent of channel decoration. A held Pin, Mute or Archive
keeps its reason and step progress while receiving current Retry/Skip controls;
cleanup status never retries a decoration. A durable cleanup hold also survives a
ledger I/O failure: remembered phase and committed status are presentation only,
and Retry/Skip reopens and validates the actual journal before acting.

A fresh renewal intentionally revokes its previous attempt permit without
revoking the owning operation. If the renewal spans the shared request timeout
and first backoff, it retires once into executor recovery. A late grant sends
nothing. The executor rereads or holds and restores Retry/Skip; only a fresh
explicit retry can attempt the still-present item again.

## Resume after commit

A fresh operation still compares the exact state and cache the user reviewed.
Retry and Skip for an admitted journal reopen that operation instead of comparing
committed N with the original O screen snapshot. Store validation binds the
account, login, operation, retained objects and durable state. Core Resume checks
its recorded opening, pending, reserved or committed state for the current phase.
Cleanup also witnesses the actual completed cache and state before Retry or Skip.
Changed or invalid files hold without a deletion; remembered progress is never
admission authority. A successful Skip reaches core CleanupSkipped and releases
the write hold even while a Pin, Mute or Archive decoration is Waiting or Held.
The decoration keeps its step and reason, and only an explicit retry restarts it.
