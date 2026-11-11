# Media auto-reply

Purple Settings has an account-specific Media auto-reply screen. It uses stock
`UniversalFragment`, `UItem` settings rows, `AlertDialog`, `EditTextBoldCursor`
and `DialogsActivity` chat selection. It starts Off for voice and round notes;
ordinary video attachments and music are excluded. The shared text initially
reads `@TranscribeThisAI`. Mentioning a bot does not grant private-chat access.

Private chats, groups and channels each have voice Off / My own / Received /
Both. Round notes have Follow voice settings checked initially; unchecking
unlocks the separate round selector. Named typed chat sets are ordered, first
match wins, and their unspecified values inherit chat-type defaults. They do
not alter Work Mode memberships. The native picker supplies typed identities;
bare numeric Work Mode membership never drives an automatic send.

The account snapshot, resolution, parser, nested lossless writes, action ID
and journal reservation codec live in purple-core. Java does not reimplement
that policy. A newly added account does not inherit an enabled policy from
another account. Deferred events capture the account lifetime and generation;
settings invalidation resets the activation cutoff and cancels pending work.
Only genuine new-update origin or a final unscheduled outgoing ACK is admitted.
History, differences, edits, scheduled/shortcut messages and unsent uploads
never intentionally enter the dispatcher.

The dispatcher keeps bounded pending callbacks (32), in-flight requests (8)
and durable reservations (4096). A checked AtomicFile reservation precedes each
native `messages.sendMessage` request. Journal errors fail closed. The exact
original peer and message/topic are sent as a native reply; channel posts are
never remapped into a linked discussion. No optimistic local message is added,
so server-returned updates reconcile through the ordinary MessagesController.
Any result, duplicate/in-flight error, transport uncertainty, permission/flood
refusal or paid-send refusal remains reserved. There is no new-ID automatic
retry and no automatic Stars approval or paid flood-skip flag.

[The documented server contract](https://core.telegram.org/api/updates) covers
same-peer random-ID deduplication across all sessions of an account without
expiry. The shared feature ID is a fixed upper-32-bit namespace plus original
positive server message ID. Its within-feature uniqueness depends on keeping
the exact original outgoing peer, which this adapter does. It is not a globally
reserved random-ID namespace, and host tests cannot qualify live server behavior.

Upstream hooks are confined to MessagesController's actual new-message update
branch (with explicit difference/scheduled facts) and SendMessagesHelper's
successful ACK notifications (with explicit scheduled state). Purple's own
settings screen links the new page. Native UI rendering and live acceptance
are separate from source and host qualification.

Settings or account replacement cancels only request tokens owned by this
feature on their frozen native connection/session. Cancellation is best effort:
a reply the server already accepted may still arrive. Cancelled or uncertain
actions retain their durable reservation and never become automatically
sendable again. Late success/error callbacks do not reopen a reservation or
retire a newer generation's capacity.

The journal writer checks each write, file sync, close, same-directory replacement,
directory sync and expected-byte readback before advancing memory or transport.
A platform AtomicFile finishWrite return is not used as a commit result because
that API can log failures and return normally. Any unsuccessful checked stage
pauses this account without sending that action. The guarantee is limited to
these reported filesystem operations, not arbitrary power-loss behavior.

The settings page shows an account-bound status snapshot, last confirmed journal
capacity and in-flight count. Reasons distinguish storage/full/corrupt refusal,
permissions, flood limits, payment approval, cancellation and uncertain outcomes.
The stock status dialog explains manual approval and terminal reservations; it
does not grant payment or retry. Reopening the page refreshes the snapshot.
Selected-chat picker edits originate from the active chooser and retain the
opening account and full policy snapshot, so sync changes cannot be overwritten
by an older membership selection. Independent round inheritance is labeled as
inheritance, with chat-type and member effective values and a matching stock
radio chooser selection.

The checked journal writer uses public Android `Os.fstat` and `S_ISDIR` to
validate the parent descriptor before syncing it. An unavailable directory flag
is not assumed; a failed descriptor check refuses admission before transport.

Media settings use a forced own-account writer route, independent of the account
Work Mode. Ordinary Follow writes retain their existing global destination.
Picker policy snapshots gate admission before publication; after publication,
the editor validates the committed file receipt, live account and view through
refresh before acknowledging and closing. Native writer controls use actual AGP
writer/routing/editor classes and real JNI publication over controlled account,
view and filesystem-context ports. They do not render UI or run live sync.

Picker admission derives fresh effective policy from the writer's captured own,
global and fallback file bytes using the existing native layering and projection.
It does not trust a cached Effective while a watcher invalidation is queued. A
newer external membership therefore refuses an obsolete selection before any
publication or acknowledgement. The actual-writer fixture retains cached
Effective values until explicit invalidation and covers both that interval and
changes during publication/refresh, with the exact reviewed picker as a negative
control. Unrelated settings callers keep their existing cache behavior.
