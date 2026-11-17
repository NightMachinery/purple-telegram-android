# Playlist downloads and cache

Download now fetches the selected playlist with at most two owned requests at a
time. Each request copies at most 256 KiB before the network buffer is released.
File-reference refresh requalifies the same source once. Server waits require an
explicit Retry. Cancel closes owned requests and descriptors before reporting its
terminal state. Closing a screen removes its observer and leaves the account job.

## Keep policy

Per-playlist On, Off and Inherit are local download preferences. Inherit resolves
the actual shared app-wide playlists.keep_downloaded_p setting. Changes use the
captured account worker, current read capability, compare-and-write and readback.
Unavailable inherited settings do not become an assumed On value. The stock
radio and default controls update only after confirmed writes.

Keep downloaded retains files fetched by an explicit Download now action, matching
the accepted Download all design. Adding or playing a song does not start a new
background download. Playlist sync scheduling remains a separate feature.

Malformed or newer Android download metadata refuses typed download changes while
ordinary playlist reads, Rename, Add, recent use, Undo and sync choices remain
usable. Ordinary edits preserve opaque Android and Desktop download members and
do not create an absent inventory. Stock status text distinguishes newer data
from unavailable data.

## Retained On route

A durable intent precedes exclusive staging creation. Native inode identity is
checkpointed before bytes arrive. Appends are sequential; recovery verifies the
saved inode and overlapping bytes. Sealing reads the declared size, hashes and
fsyncs the file. The proof is checkpointed before no-replace publication and final
directory/file verification. Uncertain publication is reconciled from proof.

Only current source membership and full native file proof can register a kept
file. Metadata checks on the caller thread revoke changed roots, routing files,
library/local revisions or file identity. Explicit document paths and forced-cache
resolution retain stock precedence. Kept files stay outside ordinary cache cleanup.

## Managed Off route

Off uses a private account subdirectory inside FileLoader's ordinary cache root.
The same guarded native operations handle bounded append, sealing and no-replace
publication. An evictable cache index stores inode, size and digest before final
publication. A failed publication can be reconciled from that proof. This route
writes no kept-file record and reports no kept native registration.

Active writer, index and partial paths use the stock AutoDeleteMediaTask lock.
Reference counts prevent a failed overlapping reader from releasing another
operation's locks. Completed cache files remain evictable. Exact cache-root
replacement revokes both outstanding work and mappings. Source or policy changes
refuse later publication. The index is bounded to 1,024 entries; capacity and
large-list behavior still need application qualification.

FileLoader has one additional guarded cache lookup before its stock database
lookup. It takes the captured account rather than the globally selected slot and
preserves explicit document paths. The actual native-player prepare hook can
rehydrate a proven cache mapping on the serialized account worker. Eviction revokes
the mapping, and a later explicit download refetches the bytes. Pathname lookup
still has the stock final-open race; it is not an open-by-descriptor contract.

## Removal and recovery

Remove downloads now uses a stock AlertDialog and a current immutable screen
capability. Shared core protects every other unpurged playlist, including Recently
deleted. Pruning follows confirmed Remove songs, Delete forever, Undo and sync
merges; its failure preserves edit success and Undo. Active batches defer cleanup.
Inactive selected intents, or unreferenced intents during pruning, are eligible.
Only exact owned staging identities may be discarded. An identityless existing
part is never adopted or deleted. Absent parts require a native directory barrier
and authority/absence recheck before the reservation can be forgotten.

Interrupted publication is reconciled using exact final identity, size and hash.
A proven published file becomes inventory before shared reference policy decides
whether it may be removed. Unlink failures preserve mutation uncertainty and old
proof; healthy explicit retry can confirm durable absence without recreating files.
Ordinary cache and user Downloads are outside retained cleanup.

A typed cleanup subrecord in the existing client-local inventory records global
pruning or exact selected playlist targets. It is committed and read back before
file changes, and cleared only after confirmed completion. Refresh, screen reopen
and a new process recover the matching Retry kind without automatic deletion or
network retry. Newer/malformed subrecords refuse download changes while ordinary
library operations preserve opaque client state. Unknown fields survive updates.

A selected cleanup target becomes obsolete only when the shared library has a
purge tombstone. Its notice then offers global prune Retry, including a stale
selected screen. After all eligible intent/file work completes, one local write
clears global prune and only purged selected targets. Live and Recently deleted
targets, unknown fields and reference-protected files survive. A failed prune
keeps the marker. Undo and older remote merges cannot retarget the operation.

## Qualification and remaining work

Host evidence measures769 download/control/removal checks,131 worker checks,
179 screen checks, API37 and four cold-process pairs. Five single-hook variants
fail named unchanged assertions. Native physical47/mediaJNI67/files179/fault56/
media113 pass, including actual partial-unlink barrier failure and healthy absence
reconciliation. This revision rebuilds the shared state and JNI projection in the66-unit image.
The exact31 baseline fails the purge-target assertion, and removing the shared
purged-only filter fails the existing live-selection scope assertion. Earlier
physical and screen counts are inherited source-bound evidence, not new runs.
Canonical46 parsing and counter floors are static checks only.

Tests use real worker/JNI/files with synthetic account, transport and scheduling
ports. No original application integration, full46/freshAGP/NDK/R8/package or native
provider/cache-manager/audio/visual claim is made. Default-writer UI, large-list
fairness, playlist sync/notices and
automatic sync scheduling remain before final application qualification.

## Default writer host boundary

The default-writer gate compiles the complete production writer, routed editor,
file snapshot and effective-settings cache against the shared JNI image. Synthetic
account, Android scheduling and notification/sync endpoints exercise global routing,
publication admission, external file changes, cached defaults and role replacement.
Gate reload is an inert cache-invalidation port; this does not qualify its native UI,
real sync transport or automatic scheduling. The production screen still calls the
standard app-wide writer and refreshes after the operation. Full application and
visual qualification remain required.

## Managed cache capacity after eviction

At the1,024-record cap, a new cache publication can reclaim one canonical record
whose media is confirmed absent by the native barrier and recheck. It preserves
present files, symlinks, unrecognized record names and unknown index fields, then
uses the existing index compare-and-swap/readback before publishing new media. A
full index with no eligible absent record still refuses capacity. This reconciles
evicted metadata; it does not delete cache files or introduce an eviction policy.
The scan is bounded by the existing1,024-entry index limit and stops after one
confirmed absence. Native application qualification remains separate work.

## Final native registration

After durable downloads finish, the account worker registers one retained file per
turn. Ordinary queued work can run between registrations. Publication checks the
batch cancellation receipt, including during native inspection; Cancel leaves
completed durable files available for a later explicit retry. Successful native
mappings keep the account worker lifetime, so finishing the batch does not revoke
them. A single file inspection still occupies one worker turn. Progress reuses
its immutable completed list while that list is unchanged.

Host regressions reopen actual account files and native JNI proofs, queue work
between registrations and cancel inside a real publication authority check. This
is private host evidence; application and real provider qualification remain.
