# Sync-chat carry JNI contract

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
a JSON string. The hash covers unescaped UTF-8 record bytes. Its storage slice
must enforce 256 KiB per record, sixteen distinct hashes, 2 MiB total raw records
and the 4 MiB encoded-state limit, preserve unknown container/entry members and
require an exact version key and lineage. This bridge neither pins bases nor
claims to validate that client-owned retention container.

The Android base pins the shared core through its gitlink. No shared algorithms,
new core lifecycle transitions or retention policy are copied into JNI. The
approved pending/protected-source, reader-baseline, logout/abandonment, repair
and cleanup changes remain requirements for their later executor/storage slices;
this facade grants no fallback when the pinned core lacks a later contract.

The real JNI host driver is described in
`TMessagesProj/jni/purple_jni/tests/sync_chat_move/README.md`. Host results do not
qualify Android directory durability, Telegram routes, shrinker behavior or UI.
