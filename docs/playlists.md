# Playlists

The older settings-sync host fixtures use an explicit inert playlist lifecycle
port. They test settings sync without starting real playlist workers against
Android framework stubs. Dedicated playlist runtime, actor and worker suites
exercise the actual playlist classes separately. Each settings-sync Java phase
has a five-minute timeout, so an uncaught main-thread exception cannot leave an
idle non-daemon worker hiding the failure indefinitely.

A canceled request reports possible file changes when it reached a write.
An unchanged canceled request suppresses its completion. Both paths keep the
account and view checks, so a result can only reach the captured screen.

Use `--host-java` with Java 21 for checked JNI runs on macOS. The same
physical fixture passes 47 checks on Java 17, which also emits array-critical
warnings. Java 21 completes those checks without warnings. Gradle and javac
still use the JDK selected by `--java-home`.

The Android library bridge uses the shared playlist model for strict format 1
parsing, canonical serialization, deterministic merge and containment checks.
It does not create a second Java implementation of those rules.

`PurplePlaylistsCore` takes UTF-8 library byte arrays and the captured account's
positive user ID. Successful canonicalization or merge returns fresh canonical
bytes. A containment result carries a separate boolean and no replacement
library. Invalid and newer-format inputs return their status and which input
was refused, with neither replacement bytes nor a containment decision.
Failures therefore cannot become an empty library or permission to prune.

Document IDs stay decimal strings inside the shared format, including values
above Java's signed long range. Canonical bytes use ordinary UTF-8, preserving
supplementary Unicode. Accessors return copies of the canonical bytes.
The bridge does not wrap library data in the settings JSON envelope, apply its
4 MiB cap, or apply the local edit budget to a read or merge. Java array and
allocation limits still apply. Allocation failures remain exceptions, distinct
from a structurally invalid library.

These operations are synchronous and contain no account-file, clock, entropy,
Telegram request, publication or deletion access. A caller must run them on
the account worker using its captured routing authority. Their output alone
does not authorize a write, sync publication or record pruning.

Storage, captured-account serialization and shared typed edits/Undo have focused
host qualification. The Playlists tab has a private stock-component implementation and API compile;
Application integration, native playback validation, retained downloads and sync
transport remain pending.
Sharing remains deferred. A sync chat change does not authorize cleanup of the
old chat.

`playlists-library` exercises the actual Java facade and shared-core JNI library.
Its cases cover typed input refusals, newer format, Unicode and unsigned IDs,
account normalization, canonicalization, merge and purge dominance, containment,
caller byte preservation and a local input larger than both the settings
envelope and the local edit budget. The bridge requires actual native and AGP
qualification; host cases do not establish Android UI or playback behavior.


## Device-local state boundary

`PurplePlaylistsLocalCore` uses the shared local-state codec for explicit
defaults, canonicalization and changes to the local sync switch. It preserves
unknown root and nested bookkeeping fields. A missing filesystem entry must
be proved by the durable store before requesting defaults; null, empty,
unreadable, invalid and newer input never become a replacement state.

The sync switch changes no memo, unsynced age, last result, hidden snapshot or
recent-use field. This pure boundary performs no storage, transport, account
routing or playback. Its returned bytes are candidates, never durable-write or
pruning authorization. The account worker reloads and validates route, file identity and revision
before publication and serializes writes in order.

The local-state JNI suite exercises the real codec through Java, including
unknown-field roundtrips, malformed and newer state, account normalization,
UTF-8 and a 25 MiB input budget boundary. Its measured host floor is 115. Four deliberately broken native hooks fail
the unchanged cases; Android application qualification remains required.


## Account store protocol

`PurplePlaylistsStore` serializes library and local-state publication on one
worker thread. Each successful read is a capability for that store's latest
exact physical snapshots. A later read, any attempted write, closure or an
external identity change revokes it. Defaults are returned only for snapshots
the physical adapter identifies as absent. Invalid, newer or unreadable files
block writes and remain untouched.

Changing merges first back up exact prior library bytes. Every full write
materializes and confirms the library, including an empty one, before local
state. The local-state publication guard binds that confirmed library snapshot.
A failure never restores an old snapshot over a partial commit. Results expose
confirmed commits separately from attempted files that may have changed, and
callers must reload after an attempted write. The typed local sync operation
preserves library and backup bytes and absence without acknowledging a sync.

The physical adapter must own an exclusive account writer lock and prove the
captured account route, owner record, local choice and all directory identities.
It must compare target identity and bytes, refuse links and unsafe permissions,
perform file and directory durability barriers, verify exact readback and call
the supplied guard throughout publication. The guard binds route and other
files; the adapter itself binds the target's old snapshot before publication
and its newly owned identity afterwards. A target cannot be checked against its
old identity after the adapter has replaced it.

The current source group provides the ordering protocol and permanent cases
over synthetic physical endpoints with actual shared JNI codecs. The physical adapter, captured-account binder and serialized actor now have
real JNI/filesystem host qualification; Android event/UI wiring remains open. Its measured host counter is 62, with five broken
store hooks rejected. Synthetic endpoints establish no
filesystem, lock, power-loss, native application or device guarantees.


Before a local checkpoint, the store explicitly confirms the library file and
parent durability barriers even when the requested library bytes already match.
A readable file left by an interrupted publication is not sufficient authority
for publishing local memo or status. Barrier failure preserves the library and
revokes the earlier read capability; a later attempt starts with a fresh read.


## Playlist physical backend

The separate native playlist file engine preserves existing sync/settings size
limits. It binds private physical directories and exact snapshots, holds a
cooperative process/filesystem writer lock, checks the supplied account route
during bounded I/O and confirms file and parent barriers before returning a
publication. An interrupted post-rename operation remains uncertain and is never
rolled back over a newer file. Explicit confirmation covers unchanged libraries
before local checkpoint publication.

Its native fixture and Java/JNI Physical cases are registered together in the
playlist physical host suite. The measured floors are 47 Java/JNI and 179 native-file checks. Syscall fault
coverage, a captured account route binder and account worker are still required
before integration or publication.


The Physical adapter binds opaque native session/snapshot identifiers to one
worker, store instance, filename and exact bytes. Callback wrappers retain the
method name native code invokes after optimization. Native exceptions preserve
pending Java failures; close releases the native lock and guard reference.
Reentrant writes and close are refused before invoking external callbacks.
Host cases use actual temporary files and JNI, not physical stubs. The cases passed warning-free checked JNI under the explicitly selected host
runtime. They do not establish Android application behavior.


### Focused Android storage qualification

Private JNI checks measure 189 library, 115 local-state and 62 store-protocol
assertions, rejecting twelve deliberately broken production hooks. The file
engine adds 179 native filesystem checks and 47 Java/JNI checks, including
UTF-8 paths, 25 MiB files, stale snapshots, private modes and process locks.
These are focused host results. Account binding, worker serialization and application qualification are still required.

The physical driver accepts `--java-runtime` or `PURPLE_HOST_JAVA` independently
of the AGP javac receipt, and pins that executable for both phases. On the
qualification Mac, JDK 17 emits a critical-region diagnostic even for an inert
JDK-only filesystem baseline. The unchanged adapter and cases are warning-free
under installed JDK 21 with `-Xcheck:jni`. The driver rejects runtime warnings
case-insensitively; the JDK 17 result is not a clean JNI pass. This does not
change the Android application toolchain or establish Android runtime behavior.


The source-bound file fault fixture adds 56 measured checks for interrupted and
short writes, write failure, file and parent barriers, rename failure, readback
I/O and byte mismatch, cleanup failure and ownership, and authority revocation
between write chunks. Seven single-hook mutations fail named unchanged checks.
The fixture substitutes only syscall call names in the exact source; production
code carries no fault state. A post-rename failure remains uncertain and never
rolls the published file back. This is host fault coverage, not power-loss or
Android application qualification.


### Captured Android account storage

The Java account path facade now includes the shared core's playlist directory.
The private binder captures the explicit slot, account key, user, lifetime,
configuration/connection instances and owner/global revision and role. It proves
the owner/local-choice inputs with the existing small-file durable snapshots
and the shared route model before opening that directory. Its persistent guard
checks those captured inputs independently, without calling Physical.current.
It follows the captured account, not the app's later selected account. UI reply
admission is a separate worker requirement. The complete binder compiles against
the actual Android API and business classes; its runtime authority has 49 source-bound host checks over the real route model
and native files. The serialized actor and typed edits/Undo have focused host qualification;
UI wiring and application qualification remain pending.

Parent creation is explicit. The default open requires intermediate account
directories to exist; the binder requests private creation with a no-follow
walk, captured route checks and child/parent barriers. Permissive or substituted
parents are refused rather than repaired. Reopening flushes the relevant child
and parent again, so a failed first-use barrier cannot be bypassed merely because
the directory now exists. A failed creation can leave private empty directories;
retry revalidates them, never deletes them or invents library defaults. The real
Java/JNI and source-bound native cases cover this policy and reject three broken
hooks. The unchanged engine fails the same parent-barrier repair check.


The account gate compiles nine complete production classes. Its inert Android
ports supply only cached owner/account instances and scheduling; owner/local
parsing, route validation, path resolution, durable snapshots, codecs and file
publication execute the real shared model and JNI. Cases cover three accounts
across both backends, first use, captured-versus-selected identity, instance and
lifetime replacement, disk authority edits/removal/permissions, symlink and
directory replacement, root and command retirement, worker-thread refusal and
both sides of publication. Four removed guards fail named unchanged assertions.
Removing the additional app-root predicate alone still passes because the
independent current-anchor checks refuse the operation; that surviving variant
is not counted as a negative control. This does not simulate Android account
event delivery, its main loop or the application UI.


### Serialized Android storage work

A registry retains one actor for each captured account lifetime. The actor owns
native storage on its creator thread, has sixteen pending slots and never waits
for I/O on the UI thread. Replacement retires the old actor immediately and
admits a new one only after its creator-thread close. Failed close remains held.
It does not cancel shared media work or interrupt other actors.

Reads return opaque snapshots bound to the actor, account lifetime and latest
read. A started failed read also invalidates the old snapshot. Local sync changes
and merges serialize on the same actor. The persistent route guard uses the
currently executing request, so canceling one request does not permanently bind
all future work to that canceled request. Invalid/newer files remain held.

Commit results distinguish confirmed library/local writes from possible changes.
In particular a library commit followed by a failed local checkpoint is reported
as partial, even after cancellation. Merge keeps the exact prior raw backup and
confirms the library before local state. No arbitrary remote-library size cap is
introduced by the actor.

UI completion uses a weak target plus a separate screen-scope predicate. Delivery
rechecks actor revision and captured account lifetime. The eventual screen must
include selected-account and screen generation in that predicate and avoid
capturing its view in the callback. UI freshness never retargets filesystem work.

The permanent worker fixture runs 131 checks over real account routing, model,
JNI and synthetic files with controlled queued UI delivery. Eight single-hook
mutants fail named assertions for cancellation, snapshot admission, UI revision,
UI account lifetime, remote copying, partial-result truth, registry lifetime and
queue capacity. Only Android cached account/scheduling ports are inert. The existing account identity event also retires registered playlist actors.
Application and UI qualification remain open.


### Typed edits and Undo

`PurplePlaylistsEdits` calls the shared create, copy, rename, delete, restore,
purge, add/remove/reorder and Undo operations. It transports strict UTF-8 text
and decimal unsigned document IDs without modified UTF-8 or signed-long loss.
Copy parses the actual source library. The core owns validation, clocks, edit
limits, conflict rules, ordering and edit counts; Java adds no second algorithm.
Commands copy caller arrays before admission. The shared local-state codec
preserves unknown fields, memo and status. Changed edits retain the earliest
unsynced age; create/copy/add update recent use, including a no-op add.

A native editor belongs to its creator actor thread. It retains one structured
Undo record in memory, with no persisted Undo format. A prepared candidate
replaces that record only after confirmed library publication. UI receives an
opaque actor-owned ticket, never a native handle. Successful replacement,
consumption, uncertain library publication and actor close revoke the ticket.
Ordinary reads preserve it; merges that may change the library revoke it.
Core Undo conflicts are refusals, not permission to restore old file bytes.

A confirmed library with a failed local checkpoint keeps explicit partial-write
flags and its Undo record. During that actor lifetime the next valid edit can
repair bookkeeping using the original unsynced age, rather than its retry time.
This is not crash-recovery proof: the in-memory age and Undo disappear at process
exit. Callers must show partial results and must not blindly retry mutations.
UI delivery independently checks the current request even when a no-op keeps an
older snapshot, so cancellation cannot deliver optimistic success through it.

The permanent edit driver passes 70 checks; the worker driver passes 131.
Ten single-hook mutants fail unchanged named assertions for unsigned IDs,
earliest age, no-op recent use, confirmed/uncertain Undo, native close, owned
Undo tickets, refresh preservation, partial commits and canceled no-op delivery.
They use the real core/JNI/actor and synthetic files, with inert cached Android
account/scheduling endpoints. The host image links two new units with sixty
independently pinned unchanged units. Fresh full-40/AGP qualification, application
wiring, crash-recovery behavior, native UI and playback remain unqualified.


### Typed read model and screen lifecycle

`PurplePlaylistsView` projects the actual shared `ListVisiblePlaylists` and
`PlaylistVisibleSongIds` results on the account actor. Java receives immutable
rows and one selected detail, not a second JSON parser or ordering engine.
Metadata, primary and alternate sources, Unicode and decimal unsigned IDs
remain typed. Deleted rows are separate; incomplete records and hidden local
snapshots have explicit counts. Missing selection grants no fallback detail.
Invalid or newer library/local files grant no rows. The read has no settings
or local-edit byte cap and never writes defaults over unreadable input.

`PurplePlaylistsScreen` is the main-thread facade. It captures an explicit
selected account, queues storage and projection on the existing actor and
holds only opaque read and Undo capabilities. Completion uses weak screen and
listener references and separate request, screen-generation, selected-account,
actor-revision and captured-lifetime fences. Refresh cancels the older request;
screen closure cancels admission without stopping unrelated account work.
Copy obtains its source bytes on the actor, avoiding a large UI-thread clone.
Account identity changes stop the registered actor before a new acquisition.

A confirmed Undo exposes the shared inverse as Redo. The action is labeled
in the stock bulletin and the playlist overflow menu. Undoing an untouched
creation or copy moves it to Recently deleted so Redo can restore the same
playlist. Permanent deletion has no history action. After an uncertain write the
UI must refresh or deliberately Undo, never retry a mutation blindly.

The permanent drivers measure 85 view/screen, 70 edit, 131 worker and 49 account
checks. Seven additional named mutants fail the read-order, unsigned-ID,
selected-account, consumed-Undo, partial-result and identity-retirement checks.
The unchanged 63-unit host image adds the typed projection unit to the earlier
62-unit proof. These are real core/JNI/account-file tests with synthetic files
and inert cached Android/main-loop ports, not Android application behavior.

### Stock Android playlist interface draft

The private implementation uses `UniversalFragment` and its stock recycler and
`TextCell` rows, ActionBar menus, `EditTextBoldCursor`, AlertDialog and stock
Undo bulletins. MainTabs uses the existing music tab animation and opens the
playlist library instead of Contacts. Contacts and Playlists are available in
the chat list's three-dot menu, and Contacts remains available in the playlist
menu. Embedded tabs hide the standalone Back button. List/detail and Recently
deleted views expose shared create, rename, copy, delete, restore, permanent
delete, remove-song and saved-order actions. Confirmation callbacks bind the
exact screen state, and pausing/destroying the fragment closes its screen scope.

All 26 complete current production source classes compile together against the
actual Android APIs and compiled business classes. Resources are XML-parsed but
not linked. The private screen invokes the captured native playlist player and
has an Add chooser. Retained downloads and sync transport remain unimplemented.
The coherent full-43 campaign, fresh Debug and Standalone AGP, native packaging,
drag/large-list behavior and light/dark/long-title/RTL stock comparisons remain
required. No current APK installation or device qualification is implied.


### Captured song-source resolver

`PurplePlaylistsResolver` walks the immutable core-projected songs in saved
order, with primary then alternate references. It checks the exact peer,
message and decimal unsigned document identity, a regular main audio document,
voice/video exclusion, content expiry, platform restrictions and the explicit
save-restriction policy before granting a playable result. Alternate copies
retain their source and are tried only after permanent absence, changed content,
restriction or access refusal. Temporary failure is distinct from absence.

`PurplePlaylistsTelegram` uses the captured account connection and a separate
caller scope. A missing cache entry never proves deletion: an exact server
request establishes absence. Missing channel access hashes may be resolved
through the saved username, but only the original channel ID, actual channel
type and usable hash can publish peer metadata or permit the exact read.
Unrecognized, duplicate or mismatched message pages fail closed. Each reply
is consumed once. Closing cancels the exact outstanding request, and stale
account or caller-scope callbacks cannot publish or retarget another account.

Only one request is active. Continuations yield to the main loop rather than
recursing across a large library. Server Wait stops all remaining reads, keeps
its delay and grants no automatic retry. The player must revalidate both the
captured authority and returned message immediately before playback; a resolver
result is not a permanent media capability. Large-library playback should
resolve the selected song and a bounded look-ahead without waiting on every
source in the library.

The permanent resolver gate measures 132 checks through the complete production
resolver and Telegram adapter, actual compiled Telegram wire classes, and four
inert platform endpoints. Seven deliberately broken document, peer, Wait,
account, username, duplicate-response and channel-type guards fail named
assertions with direct JVM exit 1. Both complete production classes compile
against the actual Android APIs. These checks make no server request and do
not prove Android application playback, provider behavior or real network errors.
The original failed supervisor result remains when its expected assertion label
was superseded by an earlier, stronger cache-publication assertion; the actual
positive and negative runtime receipts were independently accepted unchanged.

The resolver now connects to the private saved-order/native adapter described
below. Coherent full-43 and fresh AGP validation, linked resources, retained
downloads, sync and native application qualification remain required.


### Saved-order playback coordinator

`PurplePlaylistsPlayback` owns an immutable typed song snapshot and a traversal
of its original row indices. Normal, Reverse and Shuffle never sort Telegram
message IDs. A selected row plays first, Shuffle keeps that row first, and a live
order change anchors the owned current song without restarting the native player.
Repeat None and One keep manual boundaries; All wraps, including one-song queues.
Automatic Repeat One requests a restart. Exhaustion remains handled instead of
falling through into an unrelated chat queue.

The coordinator resolves only the selected source before the first start. It
yields between permanent-failure skips and visits each row at most once in a
wrapped scan. Transient failure, invalid input, native admission refusal and
server Wait require a fresh explicit command. Automatic end and mode changes do
not retry them. Resolution never edits or relinks the saved library.

Every selection cancels its old request and revokes its command generation.
Pause, close, account replacement, duplicate replies and already-completed old
resolver deliveries cannot start audio. An endpoint supplies a one-shot explicit
Play receipt or automatic continuation ticket, and a live order change keeps the
original receipt. It cannot mint a new Play command after expiry. The native
endpoint must revalidate captured authority and the actual message immediately
before starting. Endpoint callbacks must run on the main thread.

The permanent host driver measures 171 checks through the complete coordinator,
resolver, actual Telegram wire classes and unchanged timer rules. Nine deliberate
guard removals fail named assertions with direct JVM exit 1. These are synthetic
playback endpoints. No native audio is started. The original supervisor failure
is preserved because the mode-authority mutant hit an earlier unchanged assertion
than the label expected by the supervisor; independent review accepted the exact
source, runtime exits, compiled classes and cleanup without rerunning them.

The MediaController adapter, current-song queue bridge, playback revalidation,
interruption/restore, playback status/Retry and bounded look-ahead now have private
host qualification. Retained downloads, sync, the coherent
full-43 campaign and native application qualification remain open. This private
implementation is not an installed playlist player.


### Native player connection

`PurplePlaylistsPlayer` connects the saved-order coordinator to Telegram's existing
MediaController, decoder, download, focus and sleep-timer wrappers. A song tap in
the stock Playlists detail screen starts its immutable song snapshot. Long-press
keeps the editing actions. The screen checks the selected scope and captured actor
identity before handing playback to a longer-lived account capture.

The native queue bridge installs only the actual resolved current message. It
does not manufacture unresolved MessageObjects or call the stock sorting setter.
Manual Next and Previous wrap through the active order. Previous seeks to
the start of the current track after ten seconds. At natural track end,
Repeat One restarts the current track, Repeat All wraps, and the queue otherwise
ends without falling back to chat history. Mode changes keep
the existing native current song. Stock music pages, history loads and next-file
prefetch are suppressed only while this playlist session owns the queue.

Tapping the now-playing deck opens Telegram's `AudioPlayerAlert`. Its layout,
transport, artwork and search interaction are shared with normal chat audio.
For Purple playback, `PurplePlaylistsAudioQueue` supplies the captured song rows
and routes selection through the existing resolver. The sheet closes when a
different playlist or voice playback takes over. The hook in
`AudioPlayerAlert.java` keeps the usual chat and profile audio queue on the
stock path.

Explicit resolution carries a one-shot Play request. Automatic resolution carries
its original timer ticket and re-enters the existing automatic wrapper at start.
The current wire message, document, captured account and peer metadata are checked
again before native admission. Stop, account retirement and replacement revoke
pending work. Account identity is included when comparing current messages. Voice
or round interruption retains the saved queue; restoration uses the stock timer
path and can adopt music paused after expiry. Native stream errors require Retry.

The initial native adapter phase measured 96 checks through the complete adapter and real
resolver, wire types and timer rules. Its driver extracts the native queue bridge,
public Play wrapper, history guards and timer automatic members from the current
production sources. Player, account, Android scheduling and transport endpoints
are inert. Nine guard removals fail direct named JVM assertions. An earlier
unchanged expiry assertion fires before the supervisor's expected label for one
negative; that original failure is retained separately from independent evidence
acceptance. Twenty-two complete proposed production classes also API-compile
together against the accepted Android classpath.

This is private host and API qualification. Native decoding, Cast, service and
voice restoration still need application runtime checks. Status/Retry, bounded
look-ahead and Add from messages have focused host/API qualification. Retained
downloads, sync, process-restart recovery, coherent full-43/fresh AGP, resource
linking and native UI comparisons remain required.


### Android playlist navigation

The library opens on saved playlists. Create lives in the app bar and in the
empty library. Sync and the default offline preference live under Playlist
settings in the overflow menu. A playlist opens directly on its songs, with
playlist actions and offline controls in its overflow menu. The add chooser
keeps its captured selection and the same library rows. A background read cannot
invalidate an edit while both files still match its snapshot. The chooser reports
confirmed counts, partial saves and refusals separately. The captured selection
can be tried again after a refusal that occurred before any write.

When music is active for the selected account, tap the deck artwork or title to
open the stock audio sheet. Play and pause have a separate target. The focused
song is marked on the playlist that owns playback, so a song shared by two
playlists does not appear selected in both.


### Playback status and manual Retry

The stock UniversalFragment uses TextCell rows and shadow text to show the actual
selected and playing song, resolution, Pause, media interruption, stream failure,
end of queue and a server-reported wait. Wait is a report of the response duration,
not a running countdown, and is never retried silently. Retry stays explicit.

Immutable playback snapshots carry a session identity and revision. Main-thread
observers are weak and close with the screen; closing one leaves playback alone.
Queued old revisions and other-account metadata are suppressed. Retry requires
the exact displayed state and current screen/worker/account identity, so an old
row cannot retry a replacement queue. Interruption preserves the music selection
without offering Retry over a voice message.

The status-focused phase measured 133 host assertions and rejects five new
single-hook negatives for Retry state, Retry revision, observer account, observer
revision and voice status. All 22 complete proposed production classes also
compile against the accepted Android API. This does not establish resource
linking, actual UI rendering, audio behavior or a full coherent application pass.


### Bounded source look-ahead

The current song starts before any following-song reply. The adapter then resolves
at most three following rows sequentially and retains at most four results,
including current. It follows the coordinator's actual saved/reverse/shuffled
traversal and repeat boundary. Repeat One keeps only current. Moving the window
evicts older rows. Pause, interruption, a new command, mode change, Stop or
account retirement cancel the owned read; a completion already queued on the
main thread cannot cross the window generation.

A prefetched server Wait stops later reads. Advancing to it reports the original
wait without retrying. Manual Retry resolves the failed current row afresh.
Prefetch takes no Play receipt; cached starts still consume the command's original
timer authority and revalidate captured account, actual wire and current peer
metadata. Prefetch cannot silently clear expiry or replace the selected song.

The final whole-player driver measures 197 host assertions and rejects seven
direct single-hook mutants covering the window bound, Wait, queued completion,
cancellation, current cache invalidation, cached peer admission and cached Play
authority. All 23 complete proposed production classes API-compile together.
The original cached-Retry regression and surviving generation mutant remain
preserved; the latter exposed a missing queued-completion interval in the fixture.
No full-43/AGP/resource/application/native audio pass is claimed.


## Add from regular music messages

The single-message menu and multi-selection action bar delegate to PurpleChatHooks.
They open the existing Playlists UniversalFragment as a chooser with stock rows,
Create dialog and Undo bulletin. Create makes an empty playlist first; choosing
its row is the separate explicit Add. The chooser reports skipped inputs and the
shared model's added, relinked and unchanged counts. Confirmed partial library
writes retain their counts and Undo alongside the partial-status notice. No-op
results remain visible even without a success bulletin. A submitted selection is
consumed once, including after failure; Refresh never silently repeats an Add.

PurplePlaylistsAdd captures account/lifetime and immutable regular-music metadata
at the command. It retains no MessageObject or raw wire references. Secret,
event-log, scheduled, sending, quick-reply, disappearing, voice, video, restricted,
invalid and unavailable sources are refused. Duplicate document IDs in the same
selection keep the first source and contribute to the skipped count. The shared
edit handles documents already in a playlist, including source relinking. Unsigned
document IDs and selected input order are preserved. Current peer restrictions,
existing unrestricted-save policy, selected account and lifetime are rechecked
before the actor command. The actor still requires its latest read capability
and matches the captured account; publication and Undo use the existing native
store protocol, without modifying any source message or Saved Music membership.

The complete screen/storage fixture measures 132 assertions over real shared-model
JNI and synthetic private files, including Add/relink/partial-write/Undo. Six
single-hook mutants fail named assertions for message account, current policy,
selection consumption, selected account, duplicate handling and expiry. Android
account caches, message wrappers and scheduling are inert endpoints. The stock
single/multi hooks and complete source classes have Android API compilation,
not application rendering or live chat qualification. Resources and the expanded
coherent host/AGP campaign remain required before integration and delivery.

Upstream edits: ChatActivity has only menu delegation, action-bar creation/update
and click routing through its existing PurpleChatHooks instance. No upstream menu
or selection logic is moved. Source-hook baseline verification remains part of
the coherent application integration campaign.

## Shared sync engine bridge

The private sync bridge calls the shared record metadata classifier, gzip codec,
record classifier, inventory reconciliation, step planner and acknowledged-cover
prune planner. It transports opaque library/state/publication bytes separately
from status and discovery details. Message and document identities use decimal
strings, so JSON floating-point numbers cannot change them. Strict JSON validation
rejects duplicate keys and invalid Unicode. Failed or missing reads are explicit;
only the shared planner decides merge/write/publication. This seam performs no
storage or provider operation and is not yet application-qualified.

The bridge passes43 checked-JNI cases with a coherent74-unit native image and
complete38-class production API compile. Four actual hook mutants are rejected
(foreign ownership, unproven payload origin, unauthorized account and stale bytes
after failed read). The permanent driver is in the48-suite roster; only its own
measured cases and the roster self-check are qualified here, not a full48 campaign.
Actual account transport, durable publication acknowledgement, status bookkeeping,
notifications and automatic scheduling remain application integration work.

## Captured playlist discovery

Playlist sync uses a separate raw record client from settings sync. The Telegram
client captures the verified account and bound chat; a binding replacement revokes
old discovery even when the selected chat later returns to the same value. Every
filename attribute, ownership verdict and exact unsigned document identity reaches
the shared metadata classifier. A binding refresh may conservatively cancel a read.

The worker transport exhausts bounded, strictly descending history, checks declared
totals, resolves missing memo IDs and preserves failed versus confirmed-gone reads.
Only owned candidates are downloaded. File reads reject symbolic links, changed
identity, truncation and oversized compressed data. A fresh exact message read must
match document, size, edit date and metadata before bytes become a merge proof.
The current in-memory receipt budget is 32 MiB across records; reaching it returns
incomplete discovery, so later work must merge/checkpoint incrementally for larger
sets. It never grants publication or deletion from an incomplete scan.

Discovery results replan against the actor's latest library and state rather than
retaining an old local snapshot. They do not commit files, publish, acknowledge
sync or prune. The permanent discovery driver tests the complete production classes
with real TL metadata, actual shared JNI and synthetic responses and files. Actual
publication/readback, durable bookkeeping, scheduling and native app checks remain.

Measured discovery proof: 73 cases, five actual-hook mutants and all 43 production
API classes pass. The canonical list has 49 suites; only the discovery driver
and static roster closure were run for this private composition.


## Captured playlist publication

The separate publication transport validates shared gzip bytes, uploads serial
256 KiB parts and builds the playlist document with its own filename, MIME and
caption. A captured account and chat, renewed posting authority, and a confirmed
pre-send journal callback are required before the single send. The caller supplies
the journaled random ID. A missing or late reply leaves an unresolved intent; the
transport never retries the send automatically.

Confirmation requires the actual Telegram random-ID receipt, an owned exact
message read, bounded no-follow file bytes matching the uploaded gzip, and a second
unchanged document and metadata read. Only that confirmation can supply a shared
PublishedThisStep proof. Close, account replacement and route revocation invalidate
the proof. Multipart and exact-read requests have an owned five-minute deadline.
The same bounded reader serves discovery without changing its 73 checks.

Host tests use the complete publication class, real shared JNI and actual TL
messages and receipt parser with inert network endpoints and synthetic files.
The adapter also compiles with the Android API. This does not execute a real
provider. The durable journal implementation, serialized actor commit and restart
reconciliation, notices, automatic scheduling, incremental large scans and coherent
application qualification remain pending. The transport alone does not mark local
state synced or prune a server record.


## Durable publication journal (private host proposal)

The publication journal uses the existing guarded account playlist file session,
with a new append-only name ordinal for `sync-publication.bin`. The native reader
and writer cap this file at 10 MiB plus 4096 header bytes before allocation and
while reading. Other file names and ordinals are unchanged. The versioned binary
record binds slot, environment, user, hashed login identity, chat, random ID,
canonical hash, compressed hash and the complete compressed bytes. SHA-256 covers
the header and payload. A newer or invalid record is preserved and refuses sends.

Preparing an intent confirms native file and directory durability, rereads the
record, and only then invokes the send continuation. The account worker adapter
uses its bounded store queue; it refuses changed accounts, closed workers and
stale scopes. Any existing pending or confirmed record blocks another publication,
including the same random ID. An uncertain write remains discoverable after reopen.
The journal never retries a send on its own.

A matched current publication or a fresh owned exact-byte discovery can confirm
an existing record. Account, login and chat changes refuse recovery; identical
canonical data in different gzip bytes does not settle the record. Confirmation
retains the payload until the actor durably acknowledges the latest local state.
That actor acknowledgement, journal retirement, automatic scheduling and real
provider/application qualification are still pending. Synthetic host proof alone
is not an application or incident-recovery claim.

### Durable sync acknowledgement

The account worker applies captured discovery and confirmed publication to the
latest native store snapshot. Shared-core planning preserves intervening edits
and unknown local fields. Typed local checkpoints retain the earliest unsynced
time until a current owned record contains the latest library. Store durability
confirms both library and local file identities with file and parent barriers.
An opaque acknowledgement capability then retires the exact confirmed publication
journal; the next publication must use a different random identifier. A partial
local write or failed durability barrier leaves the intent recoverable.

The private host gate exercises actual account-worker/JNI/file operations with
inert transport and Android scheduling ports. Full sync orchestration, steady-state
cache reuse, typed newer-record statuses, old-route intent migration, pruning and
automatic scheduling remain required before application integration.


The private Android sync actor joins discovery, latest-local durable commit,
conditional publication and journal retirement. An unchanged acknowledged memo
uses one history request and no download, write or screen invalidation. The
account runtime observes successful edits/imports, bound-chat updates and the
open-client lifecycle. Editing waits for sixty seconds of quiet, capped at ten
minutes from the earliest unsynced change; the foreground backstop is fifteen
minutes. Sync Off is read before any automatic network lookup. Keep downloaded
continues to govern retention after explicit Download all, not automatic Add
requests. Real application integration, route-migration preservation, fresh prune
witnesses, large-history parity and runtime visual/provider checks remain pending.
