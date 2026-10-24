# Encrypted archive storage

The storage preview prepares one account-specific SQLite store on Android 6
(API 23) or newer. It retains encrypted owned bytes without expiry and shows
physical storage as an estimate. Message capture and Edit History are not yet
active. It does not start downloads or change live Telegram history.

## Ownership and keys

A stable production/test account key selects an app-private catalogue directory.
Each store has a random 128-bit store id, a separate data-key id and an external
work generation. Account slots and the app-wide Work Mode account never select
a store. Owned files live below `files/purple/content-archive`; binding, creation,
generation, removal and bounded gap-outbox records live in the separate
`content-archive-state` root. Both roots are excluded from Android cloud backup
and device transfer.

A dedicated Android Keystore AES-256-GCM alias, scoped by account, store and key
id, wraps a random 32-byte data key.
One generated-IV Cipher handles each wrap. Shared core envelopes authenticate
account, store, row, key and length. Core supplies the index HKDF context; its
separate derived key produces HMAC equality tokens. Payloads, original media
names and raw peer identities are not SQL index values. Schema/index kinds,
opaque tokens, dates, ids, flags, generations, counters and file sizes remain
visible metadata. Key buffers owned by this adapter are cleared when released;
JVM/provider copies are not a forensic-erasure promise.

Existing data without its matching key is preserved and refused. An unsupported
platform never creates a plaintext fallback. Pending logout cleanup takes
priority over unlocking. Settings offers an explicit local removal confirmation
when a key or format is unavailable; confirmation retains its opening account.

## Checked execution and compatibility

The Purple JNI shim uses Telegram's existing SQLite 3.39.3 on a dedicated queue.
It checks typed binds, prepare, step, reset, finalize, row/change expectations,
transaction barriers and close. BUSY, FULL and uncertain commits never count as
retention success. Connections and iterators have one owning thread. Path walks
use search-only handles for intermediate platform directories, then require an
owned readable final directory. They reject symlinks, hardlinks and changed
directory identities. Sidecar checks and
no-checkpoint close prevent a replaced directory from becoming a cleanup target.
An active transaction must be explicitly rolled back before close.

SQL store format 2 is independent of logical archive/row/envelope format 1.
Every existing connection first reads the full-width SQL version. Format 2 then
requires the recorded shape fingerprint and the exact core-generated DDL shape
in the same read snapshot. Format-1 drafts return SchemaMismatch, future formats
remain read-only, and unreadable metadata refuses. There is no migration or
fallback initialization. Recreating an incompatible draft requires explicit
removal followed by a new store/key. All schema and statement glue is centralized
in `archive_core_bridge.cpp`.

Only an owned creation intent permits new-store initialization. The intent and
work fence precede key creation; the wrapper is durably published and verified
before SQL initialization. Recovery can finish those known cuts without
replacing an existing archive key. Core statement programs keep their metadata
sidecars, revision checks, bind limits and durable-commit acknowledgement.

Read snapshots use separate checked read-only connections, bounded pages and a
maximum of eight leases per account. Delivery is revoked on account/work changes,
maintenance or header revision/generation changes. Logout drains their handles
before the writer connection and crypto state can close. Ordinary identity
replacement also drains snapshots before releasing the old account worker.

## Owned media, gaps and logout

Owned-media primitives encrypt at most 1 MiB of plaintext per chunk, including
one empty chunk for an empty file. They verify every envelope, exact length and
whole-file SHA-256 before successful delivery. They stage encrypted files only,
never hardlink or alias Telegram cache files, and never overwrite an existing
owned blob. Physical publication alone does not mark a media job Complete.
Future acquisition/completion callers must use core's exact historical
version/revision/hash and purge/work-epoch job contracts; old unbound jobs refuse.
Lifecycle capture carries its original epoch separately from the fresh executor
epoch. No TTL/view-once/secret-chat capture or media-job effects are active in
this storage preview.

The gap API retains a bounded account/store/work-bound outbox. It
retains generic retry metadata when SQL fails and clears only after a checked
durable gap commit. Settings exposes saved gaps on refresh; future capture callers
must show failures immediately. Physical usage streams 128 directory entries per page and
uses checked uint64 accumulation, counting owned DB, WAL, SHM, wrapper, blob and
staging bytes once. Concurrent changes make this a display estimate.

Logout captures identity before `clearConfig`, publishes a core removal
tombstone and external generation fence, then drains readers/writers. Bounded
removal deletes only that store's DB/sidecars, blobs, staging, wrapper and
dedicated Keystore alias. The binding and intent clear only after durable
absence. The external fence remains. Startup resumes pending removals even for
signed-out accounts, without creating replacement keys. An unsafe retained
account is refused independently so another account's cleanup still runs.
A missing or invalid binding, unexplained fence, retained store or account key
blocks creation and leaves a durable recovery obligation. Completed cleanup
records checked absence before a new store can be selected. Logout uses an
account-wide removal intent when catalogue authority is ambiguous: it deletes
all owned account stores and all aliases in that account's namespace, preserving
other accounts. Valid generation fences survive with an absence receipt.
Unsafe files keep cleanup pending; no completion is reported before all owned
files and account aliases are absent. Account identity changes close handles without removing data. Ordinary account
selection never invokes removal; process termination leaves committed WAL data
for the next checked open.

## Headless validation

`jni/purple_jni/tests/run_archive.py` (below TMessagesProj) runs actual AGP output
with host platform ports, the core JNI library, and the real bundled-SQLite shim.
The test-only fault interposer injects prepare/bind/step/reset/finalize/commit/close
failures while retaining real SQLite state. It is not linked into the app.
Cases cover key loss, creation interruption, removal cuts, account isolation,
reader revocation, malformed paths, whole-file media integrity, bounded usage,
gap retries, genuine format-1 refusal, explicit recreation and fingerprint/DDL
drift. The shared core suite additionally runs with ARCHIVE_SQLITE_TESTS=1.
Android Keystore hardware/provider behavior and device backup execution still
require device qualification; host JCA checks do not claim those results.
