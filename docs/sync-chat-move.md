# Sync-chat carry JNI and storage

`PurpleSyncChatMoveCore.request(operation, input)` is a pure adapter to the
shared carry planner and journal. It does not read files, acquire account locks,
post messages, delete messages or schedule callbacks. The later move executor
must supply verified transport and durability receipts. Its production entry
points remain held until those slices and the required qualification land.

The request is a UTF-8 JSON object carried in a byte array. The native strict
core JSON reader rejects duplicate keys, invalid UTF-8/Unicode, fractional and
unsafe JSON numbers, excessive depth and oversize input before decoding fields.
The complete request envelope is bounded to 4 MiB, including base64 expansion.
An oversized envelope refuses rather than truncating an inventory. Each binary
field is canonical padded base64: `state`, `journal`, `plan`, `bytes`,
`stagedRecord`, `retainedLocalText`, `preferenceToken` and prepared images.
A missing state is explicit JSON null, with a separate absence-confirmed flag.

The response has a named `status` and operation-specific fields. `canonical`
contains base64 of the exact core journal or prepared state bytes. `plan` and
`state` likewise contain the core codec's canonical bytes. These fields are
opaque persistence images. Never rebuild them from `JSONObject`, never patch
their known members in Java and never treat an empty canonical result as
permission to advance. A journal response also exposes named `phase`,
`nextAction` and decimal-string `revision` when its canonical bytes are nonempty.
Wait, Retry, NeedsReview and Superseded can have nonempty canonical journals;
the caller must durably retain them just as it retains Valid results.

Input protocol counters, message ids, dates and request identities are canonical
unsigned decimal strings, with no signs or leading zeroes. Dates and ids are
range checked before entering their signed core types. Selected-record projections and core journal/plan/state
images retain the core's existing safe-integer limits. Full-width 64-bit request
identities stay strings and are never converted through floating point.

Accounts use canonical environment/account keys, and peers use canonical typed
core tokens. A self-user alias refuses. Every operation consuming a journal or
plan requires independent `account`, `source` and `target` fields matching that
image. Events additionally retain the operation id and exact revision supplied
by `event`. An event for an earlier revision or another account/chat refuses.
Inventory messages must match their enclosing account and typed chat. Their
hashes are recomputed from exact bytes by MakeSyncCarryMessage, never trusted
from a Java-supplied hash. Ownership and completeness remain explicit facts.

## API map

- `hash`, `message`, `select`: SyncCarryRecordHash, MakeSyncCarryMessage and
  SelectSyncCarryHeads. Selection exposes records, heads, hashes, playlists and
  stamp through the core plan codec's selected-record representation.
- `plan`, `parsePlan`: PlanCarry, ParseSyncCarryPlan, SyncCarryPlanJson and
  SyncCarryPlanStamp. Inputs include scope, mode, access, eligibility, accepted
  reader consent, state/token, both inventories, optional exact ownVersion and
  winner, cleanup policy/authorization and playlist use. ownVersion carries
  text, key, parents, lineage, platform, app and at.
- `verify`, `prepareStage`, `usesState`, `prepareCommit`: the matching
  VerifySyncCarryReadback, PrepareSyncCarryStage, SyncCarryPlanUsesState and
  PrepareSyncCarryCommit functions. Verification includes missing hashes and
  destinationStamp; commit includes the exact canonical image and stateHash.
- `cleanupCandidates`, `sourceMatchesCleanup`: the matching shared cleanup
  calculators. These are planning answers, not a transport deletion capability.
- `finishPending`, `abandonPending`: FinishPendingStageForCarry and
  AbandonStageForLostChat. Results include status, beforeHash, stageHash,
  stageSeq and a prepared state image only when the core accepts it.
- `begin`, `parse`, `serialize`, `next`, `event`, `advance`, `resume`:
  Begin/Parse/SerializeSyncCarryJournal, NextSyncCarryJournalAction,
  SyncCarryEvent, AdvanceSyncCarryJournal and ResumeSyncCarryJournal.
  `begin` carries the captured scope, operation, mode, state/absence/token,
  resolution, eligibility/consent, explicit confirmation and immutable request
  ids/mark text. Resume's stateDurableConfirmed defaults to false.
- `commands`: SyncCarryJournalForwards and SyncCarryJournalPostsOwnRecord.
  Capture the returned commands before advancing a delivery intent.
- `checkDeletion`, `checkOldMarkDeletion`: CheckSyncCarryDeletion and
  CheckSyncCarryOldMarkDeletion, returning the exact `allowed` verdict. Both
  require a fresh complete destination and optional playlist receipt. The first
  takes a fresh message, the second a fresh old-mark receipt. Neither grants
  arbitrary stale-mark or whole-channel deletion.

Events use the core names PendingPrepared, StateObserved, StageRetired,
Resolution, PlanPrepared, OwnStagePersisted, DeliveryPrepared, DeliveryResult,
DestinationRead, MarkPostPrepared, MarkPostResult, MarkRead, CachePrepared,
CacheCompleted, CleanupCompleted and Cancel. Their optional payload names follow
the core fields: state/absence, stagedRecord, retainedLocalText, oldRead,
pendingReadback, preparedImageHash, resolution, plan, destination, playlist,
mark, cache, retiredStageHash, deleted, noRequestInFlight and noPendingFiles.
ObjectsDurable defaults to false. Omitted observations never become affirmative
ownership, completeness, durability or transport success.

Caches use chat/id/date. Marks add outcome, account, text, hasMedia and ownership.
Messages use account/chat/id/date/bytes/ownership/family. Inventories use
account/chat/complete/messages. Eligibility includes refusal and readers;
readers must state populationUnknown, with optional otherMembers/otherAdmins
counts. Playlist receipts carry status, scope, planStamp and destinationStamp.
Cache receipts carry operation, account, mark and complete. Enum names match
the shared headers exactly. Invalid transport encoding returns Invalid with no
canonical image. Newer journal formats return NewerVersion with no fallback.

## Retained bases and boundaries

The bridge preserves unknown core journal, plan and state members through their
core codecs. This includes the client-neutral `retained_config_bases` v1 object:
version 1, records containing sha256 and the complete canonical UTF-8 record as
a JSON string. The hash covers unescaped UTF-8 record bytes. The storage adapter below
enforces 256 KiB per record, sixteen distinct hashes, 2 MiB total raw records
and the 4 MiB encoded-state limit, preserve unknown container/entry members and
require an exact version key and lineage. The pure bridge preserves the container; the storage adapter validates and pins it.

The Android base pins the shared core through its gitlink. No shared algorithms,
new core lifecycle transitions or retention policy are copied into JNI. The
approved pending/protected-source, reader-baseline, logout/abandonment, repair
and cleanup changes remain requirements for their later executor/storage slices;
this facade grants no fallback when the pinned core lacks a later contract.

The real JNI host driver is described in
`TMessagesProj/jni/purple_jni/tests/sync_chat_move/README.md`. Host results do not
qualify Android directory durability, Telegram routes, shrinker behavior or UI.

## Durable operation storage

Android has a storage adapter for a future move executor. It sends nothing and
adds no move UI. Existing Follow/Fill and outward move execution remain held.
The adapter delegates journal parsing, transitions, prepared images and hashes
to the shared core through the existing carry JNI surface.

`PurpleSyncChatMoveStore` owns the same account FileChannel lock as ordinary
settings sync. Its carry-only opening path runs before ordinary recovery,
changed-chat checks and stage cleanup. Any carry artifact, including a malformed,
newer or foreign journal or an orphan object directory, blocks ordinary writers
across close and restart. Relink, Start again and interrupted ordinary recovery
also hold. A new login cannot resume an old login's operation. Account removal
continues to own removal of account-local files; this adapter deletes no journal.

The account sync directory contains a bounded `carry.json` v1 envelope. It binds
account, preference-token SHA256, persistent login authority, operation identity,
canonical core journal and its SHA256, object hash-to-kind mapping, and optional
stable send identities. Unknown envelope and journal members survive writes.
Every nonempty canonical core reply is persisted, including Wait, Retry,
NeedsReview and Superseded. An empty reply grants no new storage action.

Immutable `carry-objects/{kind}-{sha256}.json` images precede journal references.
Kinds are state, record and text. State and journal limits are 4 MiB; record/text
limits are 256 KiB; objects are capped at 1,024 and 64 MiB total. At most 4,096
request keys map to unique nonzero signed 64-bit decimal send IDs. Mapping bytes
are committed before an ID is returned for an outward effect. The executor must
reuse the exact operation/request key and may not invent a new ID after restart.

New file operations run on a worker. Descriptor walks reject symlinks, regular
files require current uid, mode 0600 and one link, and immediate directories
require current uid and mode 0700. Snapshots retain parent/file identity, revision
and exact bytes. Writes create an exclusive private temporary, fsync it, recheck
parent and expected target, rename, fsync the renamed file and parent directory,
and reread. Failed barriers remain failures even when new bytes are visible.
Reopen re-establishes barriers before reporting durable state or absence. The
account lock provides cooperative serialization; this is not protection against
a hostile process replacing files outside that lock. Physical Android filesystem
and power-loss behavior still require later native qualification.

Prepared pending, reserved-own and committed state images remain exact core
bytes. They are not decorated after hashing. Only the current core action admits
a state write or exact stage retirement. The cache witness reads a valid matching
account/login/operation/target-mark cache and barriers it; it never changes reader
consent or warning sets. Unknown paths and malformed referenced images hold.

### Exact retained settings bases

The client-owned `retained_config_bases` v1 state member matches Desktop's format:
a version and an array of objects containing SHA256 and exact canonical record
UTF-8. Unknown fields survive. Bounds are 16 records, 256 KiB per record, 2 MiB raw
total and the existing 4 MiB state limit. Matching requires the exact version key,
lineage and space. A matching text fingerprint alone is not a version identity.

Ordinary confirmation embeds the verified staged record before retiring its stage.
Ordinary startup also pins and barriers the exact current base before deleting
an already-confirmed legacy stage or an unreserved stage. Invalid or newer
retention, missing base proof and a main-thread refusal preserve the stage and
state for worker recovery. A startup cleanup cannot erase its only exact base.
Apply/publish preflight pins the exact prior base to immutable private
`retained-bases/{sha256}.json` files before settings replacement or a new send.
Adoption adds the exact verified chosen record to the same inherited state commit.
Automatic plans include retention in their complete expected-state checkpoint
before execution. A legacy caller can supply verified exact inventory records.
If the old or new base cannot be proven, `MissingLocalVersion` holds the ordinary
operation; dirty local text is never labelled with an old identity. Automatic
planning holds for review when retention cannot be prepared.

`checkpointRetainedBases()` runs on worker-side ordinary open and requires an
idle store:
no pending stage or active/unfinished automatic checkpoint. It materializes and barriers
all embedded records before compacting ordinary embedded history to the current
base. Entries with unknown metadata remain embedded. Sidecars are bounded at
1,024 files and 64 MiB; recovery snapshots include them. Nothing is garbage
collected. Existing inherited state writes may embed retention; new directory
barriers and sidecar reads are not moved onto the GUI thread. A GUI caller that
needs sidecar validation is refused rather than doing new worker-only IO there.

### Host evidence

The `sync-chat-move-store` host-suite entry depends on real AGP classes, freshly
built host JNI, shared carry fixtures and the existing modern-sync platform ports.
It exercises actual temporary private files, CAS, rename/barrier failures, absence,
links/permissions/bounds, stable send IDs, reopen/login and ordinary-writer fences,
and exact retained base admission. Android platform/routing/Looper are explicit
host ports. Run it through the canonical host runner described in
[AGENTS.md](../AGENTS.md); the author runs the full set again on the delivered base.
The lock file is validated by metadata without opening a second descriptor.
Closing another descriptor for that inode would release POSIX process locks.
The owner also compares its open lock descriptor with the named inode.

The store is confined to its opening worker thread; lock order is the store
instance followed by the account-store instance and its short `OWNED_ROOTS` set
monitor. No supplier or component callback runs under that global monitor. The
host test runs a two-thread contender with a deadline and deadlock detection,
and an independent JVM verifies the kernel FileChannel exclusion. Retained file
operations reject the Android main Looper. The inherited single-state AtomicFile
writes remain at `PurpleAccountSyncStore.initialize`, `stageConfig`,
`confirmConfigReadBack` and `commitConfigState`; retention adds canonical bytes
to those writes. No new main-thread sidecar read or durability barrier is allowed.

This establishes host behavior, not phone lifecycle, power-loss or Telegram safety.
