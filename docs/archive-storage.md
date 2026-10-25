# Encrypted archive storage

The archive retains observed message versions and acquired media in one
account-specific encrypted SQLite store on Android 6 (API 23) or newer. View
Edit History offers owned Open and Save for authenticated Complete attachments.
It retains bytes without expiry, shows estimated physical usage and deletes the
account archive at logout. Capture never starts downloads or changes live
Telegram history.

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
Acquisition/completion uses core's exact historical
version/revision/hash and purge/work-epoch job contracts; old unbound jobs refuse.
Lifecycle capture carries its original epoch separately from the fresh executor
epoch. Lifecycle capture distinguishes TTL expiry and view-once consumption from
ordinary read or player close events. Missing preimages remain explicit gaps.

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

## Login admission and explicit recovery

Archive admission uses a random nonce for each login. `UserConfig.setCurrentUser` calls Purple's transition hook under its user lock when no user becomes a user, or the user changes. The hook prepares the nonce without I/O. `saveConfig(true)` adds it to the same editor immediately after serializing `user`; the existing single `editor.apply()` publishes both. Ordinary saves never rotate it. A restart reads the persisted nonce offline. DC migration and auth-key replacement do not participate: Android retains transport keys at normal logout, unlike Desktop's login boundary.

Android's [Editor contract](https://developer.android.com/reference/android/content/SharedPreferences.Editor#apply()) is atomic for the editor's changes. [AOSP SharedPreferencesImpl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/SharedPreferencesImpl.java#L450-L582) writes one map snapshot for apply/commit. Its [disk protocol](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/SharedPreferencesImpl.java#L703-L821) renames the previous XML to `.bak`, writes and syncs the complete replacement, then deletes the backup. [Loading](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/SharedPreferencesImpl.java#L140-L148) restores that backup after an interrupted write. `apply()` is asynchronous and reports no disk result; failed persistence leaves the previous user/nonce pair, rather than a newly persisted user with an old nonce.

The admission record binds account, store, key, generation and nonce with HMAC-SHA256 using the authenticated archive data key and a separate login domain. Admission, orphan removal and subsequent store selection share the account file lock. An authenticated record for a different nonce identifies an old login's orphan: erase that account's retained store and alias before creating its replacement. Missing, unreadable, malformed or unauthenticated metadata refuses without deletion. Existing draft archives without a valid record require explicit discard, with no migration. A missing persisted login nonce refuses until a fresh login establishes it.

Settings offers “Discard this account’s archive and start fresh” for refused records. Confirmation captures the opening account/lifetime, worker, delivery epoch and a seal of safely identified metadata file stamps. It does not require a valid login nonce or readable admission contents, so oversized, unreadable or malformed records remain explicitly discardable. No archive bytes or keys change before confirmation. The account lock checks the seal again before cleanup. Logout revokes immediately and performs filesystem work on the account queue. Startup waits for the complete loaded account inventory, discovers retained metadata, stores and key aliases, and schedules account-fenced cleanup when no signed-in slot owns that account. The persisted signed-out state supplies the recovery obligation even if the process died before the queued remover wrote its intent. The owner queue checks that inventory again before removal, protecting a newer login and every other account. Active accounts still require valid pending removal records for automatic resumption.

## Passive media retention and lifecycle

The account archive observes media that Telegram has already acquired. It never starts or accelerates a download. Streaming plaintext is copied in at most 64 KiB pieces with a 4 MiB account backlog; at most eight source jobs per account and 128 across accounts are admitted. Unsupported, ambiguous or overflowing sources record an explicit retention gap. Documents use their actual document identity and declared size. Photo qualities and document thumbnails have distinct identities with their actual quality and size, so a thumbnail cannot masquerade as a complete full-resolution photo. Alternative document representations use their own document identities. Inline cached image bytes use the same staging protocol; stripped/vector previews remain preserved in the original encrypted TL payload and are not labeled as complete image files.

The core staging protocol seals fixed 256 KiB window images and bounded coverage records. Each job retains its original account, store/work epoch, full message key, exact version/revision/content hash, media identity and purge generation. Encrypted bindings survive restart; metadata caches are untrusted and rebuild from authenticated checkpoint chains. The header is published last after durable images and checkpoints. Two-pass finalization publishes authenticated canonical chunks and completes only the original version. Cached encrypted files are decrypted on the account worker with an ephemeral copy of Telegram's local key. The existing-file fast path reads only the existing 48-byte key file, with inode and size checks; it never creates a key. Two bounded passes detect a changing source. An already-open regular file may survive producer unlink; unavailable paths never count as acquired bytes.

TTL expiry, destructive view-once consumption and expired-media replacement are distinct causes. Ordinary read state and cache eviction do not mark a message consumed. The pending-task cause captures and encrypts the original version, revision, content hash, media identity and epoch before destructive replay. Opening alone leaves the message live. A delayed close or replay cannot retarget a newer edit; unknown or stale causes refuse with a gap. Body-only whole-message TTL expiry uses a targeted epoch-less MediaExpiry with exact version, revision, content hash and generation, plus the store/removal fence at commit. Media-bearing events use the core lifecycle planner. Main-thread archive IO/wait is zero. The storage owner shares a 250 ms acknowledgement deadline across at most 64 messages, then Telegram proceeds with a gap; it never waits for media bytes.

Selection changes, including Passport, immediately revoke new source bindings and queued deliveries. Logout revokes before queueing removal, never waits for copying, and removes retained staging with the account archive. Every byte publication checks the original authority again.

B3a recovery admits covered staging only with an AES-GCM sealed consistency record. The record contains the complete original binding, authenticated checkpoint hash/count/coverage, and agreed plaintext SHA-256. A cached-file source writes it after both descriptor-bound passes agree. Streaming and inline sources write it after the core finalizer has verified their immutable authenticated pieces, before completion commits. Recovery never creates this admission: an unadmitted covered job becomes Failed, its owned staging directory is erased, and a retention gap remains. Partial streams remain incomplete and may resume from a fresh observed source under their original binding. Canonical orphan chunks remain owned until archive removal; they are never credited as Complete.

Acquired sources retain the opening worker and store objects plus a complete normalized semantic fingerprint (text/caption, entities, rich/opaque content, all media descriptors, completeness/missing fields and topic). SQL lookup and the current archive head must agree with that opening preimage. Discard revokes pending sources before confirmation and again before removal; a stale source cannot create a worker or store. Semantic normalization runs on FileLoader's queue, and an unexpected main-thread bind records a gap immediately. Binding-scoped logout removal includes lifecycle causes, staging (including stream/admission records), blobs and causal journals. Signed-out restart cleanup erases the same whole account store without touching another slot.


Source refusals are terminal: the account worker publishes an AES-GCM sealed `refused` record bound to the job/binding, and commits the core Failed result. Neither append, admission, completion nor reopen accepts that candidate afterward. If a write fails, the surviving obligation and absence of admission keep it incomplete; no fresh partial stream can validate it. Refused encrypted candidate artifacts remain account-owned until removal.

Stream provenance is local to one FileLoadOperation binding, stable normalized media identity and account/store incarnation. That binding must itself observe every byte from zero to the declared length, after the existing plaintext/CDN verification seam. A bounded interval ledger (at most 1024 merged ranges per binding) tracks only those observations; it never imports coverage from a different binding, cached copy or process. The core finalizer authenticates and hashes those exact immutable pieces, and the source validator issues consistency only after this single-session proof holds. Complementary sessions remain gaps even if their combined disk coverage is full. A fresh session can resume an interrupted candidate only by observing the entire coherent source itself and matching every inherited overlap. Cached copies use two agreeing full descriptor-bound passes; inline bytes use their immutable full-content hash. Generic staging completion cannot mint consistency from coverage.

Owned attachment access is available from each retained version in View Edit History. Missing and partial media are labeled explicitly and never trigger downloads. Open supports images on API 23+, and audio/video through checked random-access proxy descriptors on API 26+; other formats retain Save with an explanation. Access preparation verifies all canonical ciphertext chunks and the whole plaintext hash on the account worker before returning an opaque capability. Each capability retains the exact opening account/login/store, semantic version/origin, generations and committed reader lease. The bounded registry permits eight capabilities globally, four per account, and two streams per capability with one 1 MiB plaintext page per stream. Selection/logout/discard revoke memory immediately; every committed archive transaction and every chunk/cache read revalidate against the lease and generation. Orphan leases are released after restart without restoring URI authority.

The private provider exposes only opaque process-local tokens, never archive paths or roots. Query/open/size perform memory admission on Binder; API 26 reads run on an owned HandlerThread. API 23-25 use a bounded, nonblocking pipe pump off Binder with a five-second stall limit. Archive reads refuse main/Binder callers. The owned image decoder samples to at most 2048 by 2048; media codec/OS behavior still needs device qualification. No external Open or foreign URI grant is offered in this slice.

Save explicitly discloses direct, non-atomic SAF writing before opening the picker. The separate private save activity owns picker and export across history navigation and configuration recreation. It streams verified bytes, checks final size/hash/authority and close before reporting success, attempts to delete partial output on failure/cancellation, and reports failed cleanup. Process death or a blocked/broken external provider may leave a partial user-selected file, as the disclosure states. There is no hidden plaintext spool. Completed user exports are outside archive logout/purge cleanup. The only added upstream registration is three private AndroidManifest entries (two owned activities and the opaque provider); all feature logic stays in Purple files.

Media admission also refuses locked, waiting-for-passcode and overdue automatic-lock states. Cached reads and URI lookup obey the same gate; a new explicit access is required after revocation.

Capability close coalesces repeated revocation into one pending release task. A failed durable release remains owned and is retried by the bounded capability monitor, without filling the account queue.


### Owned Open and retained history freshness

The history reader retains its original SQLite snapshot. Core transaction revision and generation checks remain unchanged. For a lease transaction initiated by that history view, the native adapter compares the actual expected and next states after clearing only leases and revision. Only an acknowledged committed transition with otherwise equal states, expected revision equal to the view revision and next revision exactly one greater may advance that view's freshness revision. Another view's lease, an interleaved payload write, a failed or uncertain commit, purge, discard, logout and login replacement do not advance it. Core stays at 81e627b; no schema or header change is required.

A Save that fully writes, verifies and closes its destination remains a completed user copy after a late account selection change. Completion and current UI authority are separate facts. The UI describes incomplete output as removed only after successful destination deletion; a failed cleanup is reported explicitly.
