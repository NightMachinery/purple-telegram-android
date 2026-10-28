# Sync-chat discovery

Purple maps Android TL objects to the shared sync-chat contract. `PurpleSyncChatCore` exposes typed ownership, eligibility, exact mark parsing, ordering and resolution through `sync_chat_bridge.cpp`. `PurpleSyncChatFacts` consumes current native messages, peers and matching full info. Missing, contradictory or unsupported facts remain Unknown.

The broadcast policy is CreatorAndSoleAdmin. Broad outgoing-only acceptance stays disabled. Saved Messages does not need `out=true` or a sender, but excludes forwarded, bot, scheduled, TTL and nonregular messages. Private ownership uses `out`; groups also need the self sender. Android substitutes the dialog peer when `from_id` is absent. The mapper preserves the original sender flag: an absent group/supergroup sender stays Unknown, explicit other senders stay Other, and outgoing private behavior is unchanged. Channel admin counts require affirmative full-info flags. Basic groups use a unique full roster containing self; forbidden rosters remain unknown. Active secondary channel usernames count as public.

## Requests and incomplete evidence

`PurpleSyncChatRequests` builds R1 as ordinary global search, empty filter, limit 100, no folder/community filter, and the complete rate/typed-peer/id cursor. R2 searches self with the empty filter and stays enabled until native self parity is qualified. Both searches consume complete bounded pages, with a 20-page cap. A capped R1 or a missing eligible result leads to broadcasts-only retry and F1 over loaded main/archive group dialogs, at most 50 chats. F1 prioritizes created broadcasts named Purple sync, then other created broadcasts, then recent dialogs. It issues one bounded search per chat. A cap, truncated dialog list, malformed page, unstable count, flood or incomplete reply cannot prove absence or authorize setup/posting.

Core sorts and deduplicates complete candidate sets. A changed winner gets a fresh R3 before action. Valid marks must retain the saved positive immutable date; an empty message carrying a contradictory peer is Unknown. R3 uses `channels.getMessages` for a typed channel and `messages.getMessages` otherwise, followed by independent current full info. An exact missing/edited mark with a readable chat is MarkGone. Matching channel-private results from both independent requests establish ChatGone. Network/flood, wrong ids/peers, unsupported replies or unknown ownership stay Unknown. Qualified deactivation/migration facts map to Migrated; no migration target is followed. The old effective read cache survives uncertain evidence.

Q is a document-filtered self search for `#purplesync`, limit one. It runs only without an effective cache or eligible marks. Every recognized nonempty Q result is a hint to read the complete inventory, including a forwarded, scheduled or otherwise excluded first hit. Its first hit never establishes absence. Q's recognized exact empty result is Absent only with count zero, and that Absent hint also requires the complete inventory because filename-only records can be present. Inexact/flood/transport failures remain Unknown. Either recognized hint becomes Use only after a complete inventory contains validated config bytes and fresh matching self info permits the chat. An exact complete empty inventory can reject the hint; an incomplete inventory holds.

The modern native record client applies shared C0 ownership to both scan candidates and fresh metadata. Unknown scan ownership makes the scan incomplete. Fresh Unknown/Other is a failed/changed read, never Empty. Known excluded scan rows retain their ids so pagination still advances. The owned history response normalizer rejects inexact/flood, unexpected constructors, wrong-route messages and contradictory counts. The wrapper retains a declared total across constructors, requires it to stay stable whenever supplied, requires full declared coverage before accepting any empty tail, and fences obsolete scan replies. Reaching history page 1,000 is Unknown, even if that page is terminal; terminal page 999 can complete. Only exact complete history plus validated candidate reads can produce Empty/Present. Native inventory now captures fresh full info and reads the exact typed chat, including channel-scoped exact reads. No read route substitutes Self for a missing peer or access hash.

## Account service and cache

`PurpleSyncChat` provides `startSession`, `ensureResolved`, `checkBeforePost` and status (Unset, Searching, Use, Paused, Lost, reason, core resolution, completion/posting gates and a new-reader warning flag). Startup and the Sync screen enter the service without opening dialogs. EnsureResolved rereads local cache/binding under the lock and reuses a completed network result for the same stream for at most 15 minutes only while those bytes and bindings still match. CheckBeforePost always reloads local binding/cache and rereads R3/current full info; confirmed loss starts full resolution. Searching retries after 30, 120 and 600 seconds, then becomes Lost.

All three operations share `PurpleSyncOperations` with the existing manual/automatic account runner. A caller already holding that exact lock lends its ownership; discovery never releases the caller's lock. Queued own work cannot block a borrowed request behind that same caller. Owned RPCs have 30-second deadlines, the operation has a 120-second deadline, and close/cancel releases only owned work. Worker failures, stale tickets and synchronous logout during notification preserve the posting gate. Manual settings review/publish uses the resolved typed route and the scoped activation below. Automatic check/send/retire and legacy recovery retain their Self-only guards. The core automatic non-self guard is unchanged.

The origin captures slot, environment, user and a RAM UUID, with separate run/ticket fences. Requests, native peer-cache writes and completions check that origin. Input peers/channels are built from the captured account's native peer store, where access hashes remain. Send-as and booster rights use the fresh supplied full info; no active-account fallback helper is used.

The private device-local cache is `purple/sync-chat/<environment>-<user>.json`. Its admission authority is a domain-separated SHA-256 of the existing persisted B3 login anchor, separate from the RAM origin. Neither authority nor anchor is logged. An old-login cache is an unadmitted miss with its bytes retained for compare-and-swap; it cannot authorize a read or post. Logout deletes only matching admitted bytes, so an old deferred logout cannot delete a new login's cache. The B3 file snapshot adapter enforces private plain parents, bounded UTF-8 and compare-and-swap, including unchanged-byte saves. State/cache chat mismatches and pending journals hold.

The cache retains mark/title, typed chat, reader snapshot, stream warning receipts and pending journal. Full-info eligibility/readers are checked at resolution and before each post. A known new-reader change keeps the original baseline and exposes a persistent warning; it adds no posting pause after prior stream consent (C6). Unknown readers and newly ineligible rights still hold. Without a member list, only a higher count can reveal a change. With the private per-member digest set, additions and same-count replacements warn; departures alone do not. The owned per-account Sync chat screen presents this warning and the current settings reader disclosure.

Follow, Fill and ReadWinnerInventory never post or commit a moved cache/settings state. Complete winner inventory is supplied back to core with the original cache. Carry and established-stream switching remain separate work.

## Host checks and qualification limits

`TMessagesProj/jni/purple_jni/tests/run_sync_chat.py` uses an existing qualified Java classpath and a host library containing this bridge and pinned core. Supply `--receipt`, `--json`, `--library`, `--output` and `--suite facts` or `discovery`. Run `--phase compile` under compiler:local, release it, then run the same arguments with `--phase run`. The integration suite additionally takes `--overlay <discovery-output>/classes`; it compiles the retained modern-sync fixtures and exercises the changed runner against that production overlay. It does not rebuild the application.

Facts/request cases use genuine `TLRPC` objects and serialized request bytes. Injected client/model/service cases cover pagination, caps, stale replies, independent exact reads, Q/inventory completeness, C0 tri-state, login/cache fencing, real private-file CAS, Searching timers, shared locks, cancellation and reentrant logout. Class-origin assertions distinguish production overlays, genuine TL classes and Android process/file fixture ports.

These are deterministic source/host checks. They do not qualify native Android requests, server pagination, rights, migration, scheduling or UI behavior. R1/R2 native parity, multi-page/other-author coverage and outward setup remain under later user gates. No host test contacts Telegram or posts. Source completion, app compilation and native acceptance are separate results.

## Typed chat I/O

The I/O route captures the opening account/login origin, typed peer and full-info ownership facts. History uses that peer and channel exact reads use `channels.getMessages`; ordinary exact reads use `messages.getMessages`. Rows, document downloads, local queued-copy queries and account inventories carry or validate this scope. Wrong-peer/id replies, contradictory empty-message metadata, inexact/flood/NotModified results and incomplete counts remain Unknown. See the exact-absence rule below.

Candidate reads follow exact metadata, download, exact metadata again. Both metadata reads must retain peer, id, ownership, caption, filename, document id, size and edit date. The bytes must match the stated size. A vanished, changed or unknown second read cannot confirm Present or PendingFound.

Inventories carry typed chat and the RAM login origin. Config review/apply/post planning checks that inventory chat matches the v2 state chat, with v1 implicit Self. Default stores and raw commit entry points are Self-only; explicit typed stores require the matching v2 chat. Unscoped or differently typed readback cannot confirm a ledger message id. Automatic inventory refuses non-Self before the existing Self-only JNI/core path. Manual non-Self posting and setup remain held for A3 consent; this slice exposes typed read/inventory plumbing only.

Host cases exercise genuine TL request bytes and response objects through the production normalizers, scanner, reader, inventory and real scoped ledger files. They include all peer kinds, same-id wrong-peer replies and metadata changes during download. Source/host completion does not qualify native transport or authorize Telegram calls.

## Modern upload and receipts

Modern manual and automatic records share `PurpleSyncAutoUpload`. The native modern client no longer calls `SendMessagesHelper`; the separate legacy export in `PurpleSync` keeps its existing route. The owned upload keeps the canonical staged bytes and checksum, cancellation tracker and bounded timeout. After the upload part (and the edit's exact prior-byte read), it asks the account service for a fresh before-post check immediately before `sendMedia` or `editMessage`. Missing authority, denied/stale callbacks and unknown outcomes hold the final request. Duplicate approval cannot dispatch twice.

The runner lends its existing account lock. Automatic discovery uses a run/session/owner proof independent of the changing plan ticket; each effect callback and final request still checks the exact ticket. Resolver status is never the lock proof. Before-post keeps the existing fresh R3/full-info and 15-minute complete-discovery rule. Known new readers keep the C6 warning behavior.

The request builder uses the captured typed peer and access hash. `sendMedia` sets `silent=true`; the current `editMessage` schema has no silent field, so edits keep its ordinary media/message flags. Channel sends and edits use the same peer field but channel exact reads use `channels.getMessages`. The production native writer continues to refuse non-Self before upload until A3 consent is wired. Automatic sync also retains its core Self-only guard.

Receipts are hints for readback. New sends require the matching random-id mapping and a matching typed, C0-owned NewMessage/NewChannelMessage; edits require the matching id and EditMessage/EditChannelMessage. Contradictory mappings, wrong peers/forms, missing ownership and unsupported replies are unknown. ShortSentMessage is accepted only on the captured non-channel direct request, with a positive date/id and no TTL, plus outgoing for non-Self. Updates are still delivered normally to the captured login even if the upload operation has expired; logout or slot reuse fences their delivery.

The typed post result must match the inventory origin and chat before ledger confirmation. Exact readback checks candidate metadata, downloads the document, then repeats exact metadata and compares the bytes with the staged record. The automatic exact reader uses the same second-metadata defense. Uncertain outcomes preserve the durable pending record for reconciliation rather than blindly posting again.

Injected host controls cover serialized send/edit/receipt forms, post-upload denial, stale tickets, duplicate approval, timeout/cleanup, the real runner-to-upload-to-reader-to-ledger sequence, and typed exact reads changing during download. Transport deliveries are substituted; these checks do not execute native Telegram I/O or grant outward posting permission.

## Exact absence and native qualification

All zero-row exact replies are Unknown, for Self, ordinary peers and channel-scoped replies alike. Record exact reads and history/inventory pages require an explicit matching typed peer on messageEmpty. A peerless empty row holds the inventory; it cannot establish Empty or confirm a vanished pending record. A matching-peer empty is a recognized absence control.

The sole exception is R3, the single-id reread of a cached mark: a scoped request for that exact id/chat, answered by a same-id peerless messageEmpty plus independent readable current full info, is MarkGone. A contradictory peer, dangling peer flag, wrong id, unreadable chat, flood or uncertain response is Unknown. This keeps the A1 discovery behavior supported by S7/S11 while record inventories remain conservative.

N3: record whether the server sets peer_id on MessageEmpty for deleted Self/channel marks. Host cases cover zero-row exact replies, peerless inventory holds, the R3 readable-chat exception and contradicting-peer/wrong-id controls; they make no native qualification claim.

## Per-account screen and consent

Sync across devices opens the Purple-owned Sync chat screen for its captured account. Refresh uses the account service and lock; the opening login fences the picker and all deferred work. Closing or leaving the screen cancels owned work and releases its lock. No background setup or warning dialog is started.

Candidate display and fresh acceptance both call the shared restricted core eligibility predicate through the same Candidate projection. Unknown rights, broadcast admin counts or readers refuse. Acceptance resolves again, checks the binding and rereads full info; a changed reader snapshot requires review again. Settings disclosure follows account-settings r3: settings may include chat IDs and names, including chats from other accounts added to Work Mode lists. There is no synced account roster. The reader consent receipt records only the config stream in the local admitted cache, preserving the original C6 baseline and warning. A known new-reader warning remains visible and does not add a posting hold after prior consent. Its text distinguishes hidden pre-history and explains the count-only blind spot. Refused own marks are listed with their eligibility reason.

Saved Messages, an existing candidate and a new private Purple channel are offered only after complete Unset resolution with a valid unbound state and no effective cache. Bound changes and carry remain unavailable. The picker checks topics and secret chats before preview, then refuses ineligible selections with the same verdict used by acceptance. Setup confirmation connects the mark-only setup flow described below. This source change has no native acceptance result yet. It does not activate a first settings post or non-Self automatic sync.

## Android alignment: discovery receipt, inventory boundary and C6

Before-post always rereads R3/current full info, eligibility and readers. Direct R3 is permitted only with an in-memory complete full-discovery receipt for the exact RAM login origin, stream, local binding (install, space, binding token, state version and typed chat), and effective cache mark. A disk cache is no receipt. The completion age must be strictly below 15 minutes, with no backwards clock; direct R3 never renews it. Expiry during R3 or a deferred cache save restarts full discovery before completion is delivered. Changed bindings/cache marks and a new process/service require full discovery. Forced incomplete discovery discards the old receipt.

The C6 cache now retains sorted, purpose-scoped member digests in addition to its older aggregate hash. A current list containing an id outside the original set warns, including replacements or additions despite net departures. A subset alone does not. Without comparable lists, only a higher member count warns; old aggregate-only caches additionally detect equal-count hash replacements but cannot identify a new member hidden by net departures. The original baseline, prior stream consent and sticky warning remain unchanged. Losing creator rights also warns; unknown current facts still hold posting independently. No member ids are logged or added to diagnostics.

Two safe timing differences remain: when a recent positive mark vanishes, Android immediately starts full discovery in that attempt, while Desktop holds the attempt and invalidates its receipt for the next one. Android's direct branch requires a positive cached mark; implicit Self id 0 goes through full discovery, while Desktop obtains direct fresh evidence. Neither difference permits a stale post.

## Mark-only setup transport and UI

PurpleSyncChatSetup is connected through PurpleSyncChatSetupFlow, its worker-owned environment and PurpleSyncChatSetupTransport. Confirming Saved Messages, an existing chat or a new private channel starts the existing journaled executor. The UI also offers Resume unfinished setup independently of fresh setup availability, so discovery of this operation's mark cannot hide unfinished decorations. The limit sheet closes before offering Try again, Saved Messages or Cancel. Background, close, logout and account replacement revoke admission immediately and preserve the journal for an explicit resume.

Setup performs create/mark/pin/mute/archive only. Its journal keeps posting held unless the matching Complete setup is explicitly activated by the manual settings flow below. Discovery and reads remain available. Setup itself initializes no settings binding or record ledger. Source/host tests do not qualify these native endpoints, and this task performs no Telegram call.

The bounded private setup journal uses the account key, a separate setup-purpose digest of the persisted login authority, an operation UUID, stable mark random id, exact binding identity, original reader consent snapshot and sticky known-new-reader warning, creation attempt count and step. File replacement is compare-and-swap and private; a malformed, foreign-login or stale journal holds. The RAM login origin and request run/ticket are independent callback fences. Only explicit foreground starts/resumes execute steps. Closing, backgrounding, logout, slot reuse or binding change prevents further setup writes and releases owned work. Per-step requests have 30-second deadlines; owned transient read waits and definite write floods extend only the retained step deadline. Timeout delivery returns to the worker queue. Callbacks cannot dispatch twice.

Every start resolves again before outward effects. Complete Use can be adopted before any own created/marked work. A changed winner after such work, a bound stream, Follow, Fill or incomplete evidence holds. Creation intent is saved before dispatch. Creation has no server random-id facility: an uncertain CreateStarted step only reconciles or holds. Reconciliation requires retained Updates from the exact matching request, account/login and operation UUID; a title or a journal token alone is never evidence. If those Updates were lost on process exit, the user must review rather than create another channel. Known limits/restrictions may be retried explicitly, at most three creation attempts per operation. Flood deadlines persist across restart and forbid early retry.

After creation, Updates are applied before fresh full info. The same core eligibility predicate refuses unknown rights/admin/readers. A newly created channel must be a private broadcast, creator and sole admin, with no undisclosed readers. Existing-chat consent is compared with the freshly fetched reader snapshot. Mark intent and random id are saved before its silent send. A lost mark reply reconciles an exact own mark or holds; it never blindly reposts. Fresh exact readback must retain typed peer/id/date, full canonical own text, no media, current eligibility and known readers before local cache consent is committed. Before mark issuance, changed readers require fresh same-target consent. After issuance, known reader additions retain prior consent and the original baseline; the journal persists a sticky C6 warning before readback or decorations continue. Restarts keep that warning and pass the original baseline to the cache commit. Departures alone do not warn or clear a warning. Unknown readers, lost creator/send/admin rights and ineligible chats still hold. The executor changes no settings binding or ledger.

Only a channel created by this operation is then silently pinned, muted forever and archived, in journaled idempotent steps. Existing chats and Saved Messages skip those decorations. A retry after an uncertain decoration may repeat that same idempotent operation, after resolving and checking current facts again. The mute request uses mute_until INT_MAX; its local preferences, notification removal, storage flags, dialog settings and notification-center event all use the captured origin account. It does not call the upstream notification helper that consults selectedAccount. Updates also use the captured account on the stage queue, with login/operation checks before the UI peer-cache completion.

The executor and connected transport's injected cases cover every effect checkpoint, restart, exact request evidence, title-only refusal, stable random ids, limit/Self/cancel, durable flood waits, retry budget, foreground loss, logout, origin replacement, binding changes, duplicate callbacks and strict mark readback. Real private-file cases cover login-authority admission, restart, CAS and stale/background refusal. N5 must still qualify the actual UI, transport, origin notifications, crash recovery and idempotent server effects after the separate native GO.

### Target inventory and exact readback boundary

Complete Unset proves the discovery and Self inventory result; it does not establish that a newly selected existing chat has no unmarked Purple records. Every not-yet-issued setup mark therefore requires a separate complete exact inventory of that target, with the captured account/login origin and typed chat. Only validated Empty proceeds. Present, incomplete reads, unknown candidate bytes, wrong account/origin/chat or transport failures hold fresh-only setup without posting a mark, adopting records or moving a stream. Full info/readers are refreshed again after this potentially long scan. An explicit pre-post resume repeats it; an uncertain already-issued mark reconciles its saved intent/random id instead. Explicit fresh consent can update a pre-mark journal only for that same target and matching current readers, never change its destination.

The executor's exact-read seam retains the actual TL response and transport error plus independently refreshed chat info. It reapplies production R3 classification and requires exactly one message with noncontradictory channel count before checking the canonical owned mark. Inexact/partial/flood flags or fields, zero rows, duplicate rows, wrong constructors, count contradictions, wrong id/peer/date, media and ownership changes cannot be erased by a pre-normalized success status. The cached-mark peerless-messageEmpty exception remains MarkGone in discovery and cannot confirm a setup mark's canonical text.

Injected cases include real typed scanner/download/second-metadata inventories containing unmarked valid core records in private, group and channel targets, with a separate Unset Self result. They hold before any mark or cache commit. Exact-response negative controls exercise the full TL metadata boundary.

A complete discovery may find the same target's mark while its send result is still uncertain. That result is a candidate for reconciliation, not permission to label any matching caption as this operation's receipt. The reply must retain the saved random-id mapping and match the discovered typed mark, then pass the full exact canonical readback. Different random ids or a newer different winner hold. History/caption equality alone cannot reconstruct random-id authority.

### Settings disclosure after account-settings r3

The chooser and its consent flow are config-only for the captured account: ScreenPort resolves the `config` stream and remembers its config consent. They disclose settings for every account, including an inactive or non-root account and a fresh pre-join choice. There is no home-account restriction. The Automatic toggle only controls scheduling; manual settings sync and its disclosure do not turn off with that toggle. No playlist-only chooser or independent per-stream enable switch is exposed here. A reader-warning settings line is included whenever that candidate has other readers; cloud disclosure is shown for every candidate.

Both strings follow account-settings r3 section 11.4. A settings file may name chats from other accounts that were added to Work Mode lists; the withdrawn claim that it carries other accounts' user IDs and assigned settings is absent. The headless control renders the production disclosure with the actual resource strings for captured slots 0, 1 and 3, covering shared readers and sole-reader controls.

### Android MessageEmpty peer presence

The cached-mark R3 exception uses TL_messageEmpty.flags bit0 to decide whether peer_id was carried on the wire. Android's generated deserializer fills an absent peer with an empty TL_peerUser object. Non-null therefore does not prove presence. A scoped same-id response with no peer flag and independent readable chat evidence is MarkGone; a flagged peer must match the typed request. The real-TL host cases deserialize both absent and present peers for Self and channels, preserve the channel MarkGone control, and reject contradictory flagged peers or missing readability. History and record inventories keep their stricter Unknown rule.

## Transient read failures

Discovery and typed record reads retain transient Telegram failures as scheduled waits, preserving the read cache and holding posting. See [Transient Telegram requests](rpc-retry.md) for deadlines, retry ownership, dedicated-write reconciliation and the FileLoader boundary.


### Effect renewal and ambiguous outcomes

Every setup write uses the same resumeEffect gate, including its first dispatch and any definitely deferred retry. A create refreshes before-post resolution. A mark refreshes R3/full info, obtains complete exact Empty inventory for its typed target, then refreshes full info/readers again. A created-only decoration refreshes R3/full info and exact canonical owned mark readback. The retained executor replays this proof through the same executor logic while preserving the issued operation, run/ticket, chat, mark and random id. C6 cache publication precedes release of a decoration gate. A failed renewal journal save publishes Held immediately and releases the original capability.

Before proof gathering, a create/mark journal records that the effect is definitely unsent. The final successful gate saves the possibly-sent state before handing the request to the wire. A definite flood records a retry deadline and definitely-unsent state again, retaining the same command and one timer. It renews all proof before retrying. This state also permits safe explicit resume after restart. An ambiguous transport, server or own timeout never triggers a transport replay of create or mark.

Create recovery accepts only the original operation's retained serialized request and Updates response, bound to the opening account and purpose-specific login authority. It never searches or adopts by title. Mark recovery requires the retained original typed request, stable random id and matching Updates receipt, followed by fresh exact canonical readback. Missing or contradictory evidence holds. A late response may retain this local receipt after the screen closes, but only for the same login and matching journal operation; it dispatches no further effect.

Each foreground run borrows a fresh account-operation capability. Cancelling an old run cannot cancel a later run of the same executor. Owned discovery wait notifications extend only that run's callback deadline. Wire tokens, retry timers, fact readers and inventory scans are cancelled with the environment.

### Worker boundary and remaining native checks

Journal, receipt, binding/cache reads and writes, consent commits and local mute preference commits run on the owned worker queue. Native peer-cache/update and notification changes return to their existing account-scoped queues. The UI holds a disposable flow handle, the captured typed target needed for fresh reader review, foreground state and status labels. An unfinished operation hides fresh-setup choices; review can only renew consent for that original target.

Inherited GUI-thread disk work remains in PurpleSyncChat.account/Platform construction and Platform.current authority checks called by the existing screen/service entry points. It predates the newly connected executor and remains a known follow-up. This patch adds no executor journal/receipt/cache IO to the GUI thread.

Host cases combine literal write callbacks with the retained executor, real TL serialization, all five effect deferrals, ambiguous failures, cancellation/restart, strict chosen-target inventory, mark readback, C6 renewal, failed journal save and first-post hold controls. N5 must separately qualify the real UI, server effects, notifications and restart behavior after review and a native GO.

### Setup review recovery

Setup watchdog arms carry independent epochs. Cancelling or extending a deadline invalidates already queued deliveries before they can clear the current timer or finish a later step. A created private channel that gains readers before its first full-info result holds until the user accepts a fresh eligible candidate for that exact target; that consent can establish the previously missing baseline. Missing consent, a changed target, unknown readers and lost created-channel rights still hold.

An explicitly consented setup commit unions its config receipt with receipts saved by discovery before a restart. The original consent baseline and sticky new-reader warning remain intact. These recovery rules do not themselves enable settings posting; the setup journal requires the separate scoped manual activation below.

## Manual settings activation

The settings sync screen now reads the resolved typed sync chat. Completing setup still sends no settings. Review settings opens the ordinary inventory, settings and reader confirmation. A Complete setup journal is released only for that foreground account/login, exact mark and typed target, under the actual operation owner. The journal is retained; there is no global posting override. Unknown/incomplete facts or inventory, an unfinished or mismatched journal, and changed source/rights hold. Before any settings document is issued, changed readers require another review; afterwards known additions retain prior consent and sticky C6 warning. Unknown readers or lost rights always hold.

A fresh non-Self stream requires a second complete exact Empty inventory after confirmation and creates a version2 ledger for that exact typed chat. Existing Self-v1 or differently scoped state is never moved or replaced. Self retains its version1 initializer. The worker reuses the shared canonical BuildConfigRecord/planner and existing publisher, durable pending stage, typed upload port and metadata/download/second-metadata readback. Filename Purple settings sync.json, application/json MIME, #purplesync caption and silent send are unchanged. Settings documents add no pin, mute or archive effects. Automatic sync remains Self-only.

The activated manual path captures settings, opens/locks stores, initializes, stages and confirms on its serial worker. GUI/native admission predicates read RAM only; source file/impact authorization runs on the worker before and after fresh R3/full-info. Uploaded bytes are an immutable copy of the validated durable stage. File and send identities are domain-separated functions of that unique canonical record, stable across retries and pending recovery, with no identity or payload logging. Legacy Self automatic code retains its existing route.

Settings effects use resumeEffect semantics through PostAuthority: every deferred definite flood renews source, R3/full-info and source again before dispatch, retaining the same file/bytes/random id and original completion. One short X+1 retry extends only its RPC budget. Longer/repeated definite floods keep one logical-operation timer and display retryAt while the screen lives. Existing write cooldown is honored before a reconciled new attempt. RPC budgets and owned retry deadlines use monotonic elapsed time. The shared request controller exposes wall-time retryAt; the owned effect converts a newly observed cooldown once into monotonic remaining time and waits for the latest overlapping deadline. Wall-clock adjustments only affect the displayed time. Ambiguous server/transport/timeout results retain pending and report a typed retryAt; Finish sending first reads a complete typed inventory and confirms a found record before considering another send. Closing/backgrounding cancels owned wire/read/timer work and preserves pending. Reopening requires explicit confirmation. History remains available while posting admission is suspended.

The purpose-specific login authority on Android setup journals is independent of the state binding token. Initializing a fresh same-target ledger therefore does not rewrite the Complete setup journal. Current state binding is independently checked on the worker and by the store; a mismatch is refused. Desktop binds its controller journal differently and may atomically rebind the already verified same-target Complete journal while holding its account lock. This is a local persistence difference, not a wire or consent difference.

The inherited service/account construction paths noted above still deserve a separate GUI-IO audit. This activation adds no disk work to transport currentness predicates and does not change the automatic route or upstream files. Native qualification, N5 and any Telegram call require a separate GO; the source patch is host-qualified only.

## Queued manual cancellation

Apply workers capture the exact run and account-lock owner and recheck them before execution. Operation admission follows settings editor publication, history publication and ledger mutation. It is scoped separately from source snapshots used by a later publish ticket. Publisher staging and confirmation reject abandonment and stale operation authority immediately, even when close is queued behind an exact receipt. Pending bytes remain for explicit Finish sending reconciliation. PurpleSyncChatQueuedMutationIntegrationCases covers normal controls, queued apply, cancellation at settings replacement, both receipt/close orders, queued staging and history admission.

## Publication lock order

Editor publication takes AccountOwner, AccountFiles and AccountSettings in that order. Publication admission reads a captured per-acquisition RAM capability, run and owner identity; it never enters the global PurpleSyncOperations monitor. Registry liveness evaluates account suppliers outside its monitor, then revalidates both owner and acquisition token before changing registry state. Idle callbacks also run outside the monitor, including retirement-driven wakeups. Publication does not invoke registry lookup at all. Each operation revokes its volatile token before cancellation callbacks or registry release. Registry retirement and exact-owner release revoke the same token; stale owners cannot revoke a replacement. PurpleSyncChatAdmissionLockIntegrationCases adapts the reviewer two-thread probe, forces the opposing registry/account lock ordering, and requires both scoped and unscoped publication to complete without a JVM deadlock. Token revocation/replacement and the prior queued cancellation controls run alongside it.

## Acquisition-specific release

Every registry acquisition returns a unique capability. Release requires the key, captured owner and captured capability; matching only a retained actor identity is insufficient. The owner-only release and boolean begin APIs are removed, so compilation catches missed callers. Runner, chooser, setup, automatic sync and each discovery request retain their token and pass it during cleanup. Cleanup detaches its token before registry callbacks can start replacement work. Retirement wakeups and callbacks remain outside the monitor, and publication still reads immediate RAM revocation. OperationsRaceIntegrationCases ports the reviewer race schedules, including same-owner A retirement waking B before a late A release, then B retirement waking C before two consecutive stale releases. Only C’s own token may release C or wake its waiter.

## Cleanup and acquisition callbacks

Every acquisition is provisional until its caller revalidates the captured run, owner state and operation epoch after retirement callbacks return. Discovery also checks the exact queue head and absence of another active request. Invalidated acquisitions are revoked and released with their exact token. All release sites revoke before cleanup and retain the captured key/owner/token through a finally block; the capability stores its immutable acquisition key so cleanup never needs a fallible environment lookup to release. Discovery defers reentrant starts during cleanup, detaches timer/client handles before cancellation and releases even if cancel, inventory cleanup, timer cancellation or retry scheduling throws. CleanupIntegrationCases covers finish/abandon/close faults, retirement queue draining, reentrant close and synchronous worker errors.

Reentrant close records pending close work and a sticky logout request before returning from an active cleanup. Finish and abandon drain that work after exact-token release in their finally blocks, including a throwing cancellation callback. A drained logout is dispatched once on the worker and still loads the captured login authority and removes only matching CAS bytes; close(false) leaves the cache intact. Closed state suppresses completions and queued starts immediately. The close drain cancels any retry scheduled by the unwinding finish. LogoutIntegrationCases exercises finish/abandon, normal/throwing cancellation, inline/queued workers and matching/foreign authority, including replacement before the logout worker runs.

Finish and abandon also kick queued work in finally after delivering completion, so a throwing completion callback cannot strand a request enqueued during cleanup. The logout regression group covers both completion orderings with throwing and normal callbacks, and the unchanged reviewer CleanupReentryProbe exercises the literal service boundary.

## Move bridge

The pure carry/journal JNI surface and its host checks are documented in
[sync-chat-move.md](sync-chat-move.md). The storage, executor and native
qualification slices remain separate.

## Durable carry storage

The account-scoped move store now retains canonical journals, immutable state and
base images, checked directory barriers and stable send identities. Carry artifacts
fence ordinary recovery and writers across restart. This storage slice sends
nothing and leaves outward move execution held. See [the storage contract](sync-chat-move.md).

## Carry transport seam

Typed sealed record receipts, worker-owned send reservations and opaque carry
permits are consumed by the injected worker move executor. They preserve ordinary
publishing eligibility and the Self-only automatic guard. No carry callsite,
Telegram dispatch or cleanup is activated. See [carry I/O and transport authority](sync-chat-move.md#typed-carry-io-and-transport-authority) for identities, unknown outcomes, D8 absence rules and qualification gaps.

Carry reconciliation tickets include a nonrepeating store-shared slot generation.
A delayed renewal must preserve the opening epoch and journal revision for both
request effects and own uploads. The host suite pins uncertain-send ABA and
late cross-revision/cross-epoch callbacks.


## Carry executor and recovery

The package-private worker executor drives shared-core NextAction through injected
ports. It quiesces account publishers, persists each canonical transition, and
reopens exact images before resume. The ordinary store admits a completed move
only after validating the retained terminal wrapper, immutable objects and history.
New-login Discard preserves state/effects and repairs an already-committed deferred
pending file before abandonment. Same-chat RepairMark and no-state Fill retain
their core special cases. Required playlists hold before dispatch because no
playlist proof producer is supplied. There is no move screen or live backend.
See [action order and recovery](sync-chat-move.md#one-worker-carry-executor).

Carry's O-pending reconciliation requires a complete owned scan started after its live read ticket. Retained pre-send or pre-open scans cannot clear the pending stage. See [O-pending chronology](sync-chat-move.md#o-pending-read-chronology).
