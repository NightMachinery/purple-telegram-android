# Android settings sync transport

`PurpleSyncTelegramTransport` implements `PurpleSyncTransport` (the contract
is in `docs/account-sync-store.md`, "Sync transport contract") against
Telegram for one account slot and one user id:
`new PurpleSyncTelegramTransport(account, userId)`. The Sync across devices
screen builds one per visit for its account and user and hands it to its
`PurpleSyncRunner`.

The work is split so that everything except Telegram's own calls runs in a
host test:

- `PurpleSyncClient` is the seam: history and message requests, request
  cancellation, downloads, the local copy query, the send, the external
  files and cache directories,
  the clock, the active-user check and the two threads.
- `PurpleSyncTelegramClient` is the only implementation that touches
  Telegram (`ConnectionsManager`, `FileLoader`, `NotificationCenter`,
  `MessagesStorage`, `SendMessagesHelper`). Only the Android build compiles
  it.
- `PurpleSyncScanner`, `PurpleSyncReader`, `PurpleSyncCheck`,
  `PurpleSyncPost` and the transport itself hold the rules and run against
  any client.

## Threads, generations and the account

All transport state lives on one `DispatchQueue`, shared by every transport
instance. Request, download, storage and receipt callbacks arrive on
Telegram's threads and are posted to that queue before anything reads them.
Each `check` or `post` is an operation with a generation number. A new call
or `cancel()` moves the transport to a new generation at once, so a callback
from an older operation is dropped when it reaches the queue, even before
the older operation has been told it was cancelled. A call replaces the one
still running: the older call's `done` reports `Cancelled` (or
`OutcomeUnknown` for a post already handed to Telegram).

Before every request, download, queue query, hand-off and confirmation the
operation checks that the account slot is still signed in to the same user
(`PurpleAccountBinding.isSameActiveUser`). A check then ends with
`AccountChanged`. A post ends with `Cancelled` when nothing was handed to
Telegram yet, and with `OutcomeUnknown` after the hand-off.

`done` and `progress` are posted to the main thread from the transport
queue, so they are never called inside `check` or `post`, and every
`progress` of an operation arrives before its `done`. An unexpected
exception inside a step ends a check as `Finished` with an unfinished, empty
inventory (the review then says Incomplete) and a post as `OutcomeUnknown`.

## Check

1. Scan. First the check asks whether the send queue holds a sync record
   (below) and keeps that answer. Then `messages.getHistory` on
   `inputPeerSelf`, pages of 100 newest first, starting at offset 0 and
   continuing from the offset `PurpleSyncCore.classifyHistoryPage` returns.
   Every returned message goes into the page in the server's order with
   whether it is a message, whether it has a forward header, whether its
   media is a document, its caption and its first file name attribute. The
   candidate rule and the page rule are the core's. An empty page completes
   the scan. A stalled page, a failed or unreadable page, or a page the core
   cannot classify ends the scan unfinished, and the check then reads
   nothing and reports an inventory built with `scanComplete` false, as the
   desktop does. Progress is `Scanning` with the messages scanned and the
   server's history count.
2. Read. One `messages.getMessages` per candidate id, in scan order. Each
   candidate gets exactly one row:
   - request failed: `REQUEST_FAILED`;
   - no message, or an empty (deleted) message: `VANISHED`;
   - another id, a service message, a message outside Saved Messages, or a
     message that is no longer a candidate by the core's rule: `CHANGED`;
   - declared document size 0 or less: `INVALID`, without a download;
   - declared size over 4 MiB: `OVERSIZED`, without a download;
   - download failed, timed out after five minutes, or the file on disk does
     not have the declared size: `INACCESSIBLE`, never a fetched row;
   - more than 4 MiB read from the file (at most 4 MiB + 1 bytes are read):
     `OVERSIZED`;
   - otherwise `FETCHED` with the bytes.
   Rows past the candidate check keep the document's real id and the edit
   date (0 when the message was never edited). Progress is `Reading` with
   rows done and candidates total.
3. Queue. After the reads the check asks the send queue again, and
   `sendQueued` is true when either answer is. Asking only at the end misses
   a copy that Telegram sends while the scan is under way: the new message
   lands above pages the scan has already read, so the inventory lacks it,
   and by the end it has also left the queue. The emulator showed this when
   a queued post resent as the check began. The check logs
   `inventory.summary()` (records and bytes) with `sendQueued`, and notes
   when a queued copy left the queue during the check; there is no byte
   cap.

## Record privacy

Every sync record download is
`FileLoader.loadFile(document, parent, PRIORITY_NORMAL_UP, ImageLoader.CACHE_TYPE_CACHE)`,
cache type 1, with a `MessageObject` parent that is not put in the
Downloads list. For that cache type `FileLoader.loadFileInternal` keeps the
store directory at `getDirectory(MEDIA_DIR_CACHE)` under the attach name
(`<dc>_<document id>.json`); only cache type 0, 10 or a story can pick the
Telegram Documents or Telegram Files directories. `MEDIA_DIR_CACHE` is
`AndroidUtilities.getCacheDir()`: the app's external cache
(`Android/data/org.purple.telegram/cache`) when external storage is
mounted, else the internal cache, with a `.nomedia` file so the media
scanner skips it. Before downloading, the reader looks only at that same
cache path (`getPathToAttach(document, true)`) and uses a file there only
when its size matches the document. The gallery copy in `ImageLoader`
applies only to `.mp4` and `.jpg` files. On Android 10 and lower, apps with
the storage permission can read the external cache, as they can every
other Telegram cache file.

If another part of the app is already downloading the same document with
cache type 0 (for example a manual tap in the chat), FileLoader joins that
operation and the file lands where that operation stores it; the reader then
reads the file named in the `fileLoaded` notification.

A cancel, leaving the sync screen, or the five-minute timeout also stops the
network read: when FileLoader was not already loading that file as the
reader asked for it, the reader started the load, so it calls
`FileLoader.cancelLoadFile(document)`. A load that another part of the app
started first, such as that manual tap, is left running. No host harness
reaches this, because the transport tests replace the Telegram client with a
fake.

Telegram's own auto-download never saves a sync record. Before this rule,
opening Saved Messages with document auto-download on saved the visible
records to Telegram Files, as it does for any document. The emulator
acceptance saw four records land there. `PurpleSyncAutoDownload.excludes`
is true for a document with a file name attribute exactly equal to
`Purple settings sync.json` or `Purple playlists sync.json`, the two names the
core's candidate rule accepts (`PurpleSyncPost.RECORD_FILE_NAME` and
`PLAYLISTS_RECORD_FILE_NAME`). `DownloadController` checks it in the document
branch of each of its four per-message auto-download decisions and answers
"no auto-download":

- `canDownloadMediaInternal(MessageObject)`, behind
  `canDownloadMedia(MessageObject)` and `canDownloadMediaType(MessageObject)`.
  `ChatMessageCell.fileAttach` starts a document's auto-download from it;
- `canDownloadMediaInternal(MessageObject, long)`;
- `canDownloadMedia(TLRPC.Message)`, which `MessagesStorage.putMessages`
  consults before it queues a new message's media for background download;
- `canDownloadMedia(TLRPC.Message, TLRPC.MessageMedia)`.

`FileLoader` is untouched, so every explicit load works as before: the
reader's private cache load and a manual tap on a record, which downloads it
to Telegram Files and opens it. The
`canDownloadMedia(int type, long size)` overload has no message, and its
callers are video autoplay and photo thumbnails. Legacy `settings.toml`
documents and every other document keep upstream's behaviour. Media already
in the background download queue before this rule shipped still downloads
once.

The post stages its record outside the cache, in the app's own external files
directory (`getExternalFilesDir(null)`, that is
`Android/data/org.purple.telegram/files`), because of what Telegram does after
a send. After a confirmed send, Telegram moves an attachment whose path
starts with the `MEDIA_DIR_CACHE` path to the sent document's normal path
(`getPathToAttach(document, false)`, the Telegram Documents directory). On
Android 10 and lower that is `Telegram/Telegram Documents` in shared storage.
A staged record is never under `MEDIA_DIR_CACHE`, so Telegram leaves it where
it is and the sent message keeps it as its local attachment. The staging
root gets a `.nomedia` file, as the cache and Telegram's own media
directories do, so the media scanner skips it. The manual
`settings.toml` send stages the same way, in `<external files>/purple-sync`
(see the README).

## Send queue

`sendQueued` is true while Saved Messages holds a local copy of a sync
record that Telegram is still sending, will resend at the next start, or
failed to send and shows with a Retry action. The client reads
`messages_v2` rows of the account's own dialog with a negative (local)
message id, on `MessagesStorage`'s own queue through its public
`getDatabase()` and `getStorageQueue()`, so no upstream code changes for
it. Telegram keeps every unsent local message there: `send_state` 1 while
sending and for the rows it resends at start
(`MessagesStorage.getUnsentMessages` selects `mid < 0 AND send_state = 1`),
and 2 after a failure (`markMessageAsSendError`). A sent message gets its
positive server id. `PurpleSyncPost.holdsSyncRecord` then requires a
negative id, the own dialog, state 1 or 2 and a file name attribute equal
to `Purple settings sync.json`. A query that fails counts as held; a single
row that Telegram's message decoder cannot read is skipped, as Telegram's own
loaders skip it, so one damaged row cannot hold Finish sending forever.

It matches the file name, as the desktop does, rather than the staging
directory: a failed copy whose staged file was pruned or deleted still
sits in Saved Messages with a Retry action, and matching the name
keeps both clients' refusals the same. A file of that name sent by hand is
also counted; that only delays Finish sending. The post names its file with
the same constant, so what is sent and what is matched cannot drift apart,
and the host test checks that the name and the caption are candidates under
the core's rule.

The one gap is the preparation step: between the hand-off and the local
message (Telegram prepares the document on its global queue first), the copy
is not in the table yet. That is normally milliseconds. A second post in that
window would be byte identical, which the core accepts as a duplicate.

## Post

1. Validate: 1 byte to 256 KiB, and `inspectConfigRecord` must call it a
   valid config record whose canonical bytes are the staged bytes. Otherwise
   `InvalidRecord`.
2. Stage: write
   `<external files>/purple-sync-records/<UUID>/Purple settings sync.json`
   and read it back. First, staging directories older than 30 days are
   pruned (as the manual send does for its own) in that root and in
   `<MEDIA_DIR_CACHE>/purple-sync-records`, where builds up to `5b4c6038`
   staged. The cache root is removed once it is empty. The young copies
   there stay until they are 30 days old, since a failed send from such a
   build still needs its file for Retry. The staging copy must be outside
   the app's internal data directory, which Telegram refuses to send from
   (`AndroidUtilities.isInternalUri` refuses paths under `/data/data/<package>`).
   Without an external files directory (external storage not mounted) the
   post sends nothing and never falls back to the cache. That, like any
   staging failure, is `OutcomeUnknown`.
3. Hand off on the main thread:
   `SendMessagesHelper.prepareSendingPurpleDocument(account, path, "#purplesync", ownUserId, "application/json", receipt)`.
4. Receipt. A preparation failure removes the staging copy; it and a failed
   send (no server id) are `OutcomeUnknown`, and a failed copy keeps its
   staged file for Telegram's Retry, which uploads it again from the path
   stored with the local message, as does the resend at the next start. With
   a server id, the staged file stays as the sent message's local attachment
   until the prune, and its directory is removed only if the file is already
   gone. Then that one message is read
   back through the reader (so through the private cache download). Exactly
   the staged bytes give `Confirmed` with the id and the read-back bytes. A
   failed request or download is `OutcomeUnknown`; any other read-back
   (different bytes, vanished, changed) is `NeedsReview`. Both carry the
   message id.
5. `cancel()` before the hand-off is `Cancelled` and removes the staging
   copy; after it, `OutcomeUnknown`, and a late receipt is ignored. The
   next check finds the record (PendingFound) or Finish sending posts it
   again. A post drops its `done` callback once it has reported, so the
   receipt Telegram holds until the message is sent or fails keeps only the
   post itself, not the publisher, runner and sync screen behind it.

The legacy `PurpleSync.receiptForRetry` keeps ignoring records: it needs the
file name `settings.toml` and the `purple-sync` staging directory.

## Upstream edit

`SendMessagesHelper.prepareSendingPurpleDocument` (a Purple method) gained a
`mime` parameter and passes it on in place of the fixed `"text/plain"`. In
`prepareSendingDocumentInternal` that argument was read only for a content
URI without a path, so the manual `settings.toml` send, which now passes
`null`, builds exactly the same document as before. One upstream
addition sets the new document's `mime_type` to the given value when the
call carries a send receipt (only the Purple sender passes one) and a mime,
right after the extension-based mime is chosen.

## Tests

`TMessagesProj/jni/purple_jni/tests/run_sync_transport.sh` builds the core
bridges as a host library (the same recipe as `run_config_sync_bridge.sh`)
and runs `PurpleSyncTransportTest` against a scripted client with one
deterministic queue for the main, transport, network and storage threads. It
covers the happy check with paging offsets, progress and review of the
result; an empty history; a stalled page; a failed first or second page;
every reader outcome, including oversized before and after the download and
exactly 4 MiB; cancel during a page, a message request and a download;
account changes at each step; a stale generation; the send queue rule for
sending, unsent, failed, sent, other names and an unreadable queue; a copy
that leaves or joins the queue during the check, a queue unreadable only at
the start, and a cancel or account change before the first queue answer; a
step that throws; and posts that confirm, are invalid, lose their receipt,
fail, fail to prepare, throw at the hand-off, read back other bytes, vanish,
fail the read-back request or download, are cancelled before start, before
the hand-off or during the read-back, lose the account before or after the
hand-off, prune old staging directories, and replace a running check. A post
cancelled after the hand-off must leave its caller's callback collectable
while the client still holds the receipt. It
also checks where the record is staged: under the external files directory
next to a `.nomedia` file, never under the cache, with nothing sent when
there is no external files directory, and old copies pruned from both roots.
`PURPLE_SYNC_TRANSPORT_SOURCES` points the build at another copy of the
transport sources, for mutation runs.

The same runner then runs `PurpleSyncAutoDownloadTest` over a minimal
`TLRPC` stub. It checks four things:

- the core calls both record names candidates, and does not call
  `settings.toml` one;
- both names are excluded, including when the name is in a later attribute;
- near misses are not excluded: `settings.toml`, other names, other case,
  suffixes, a name in a non-file-name attribute, and empty or missing
  attributes;
- the `DownloadController` source (`PURPLE_DOWNLOAD_CONTROLLER` for mutation
  runs) has exactly four `type = AUTODOWNLOAD_TYPE_DOCUMENT` branches, each
  guarded by the check on that branch's own document.

Each of these mutations fails a check:

- removing one guard;
- making the check never exclude;
- matching only one of the two names.

Only an APK and a live account prove `PurpleSyncTelegramClient`: that the
history and message requests return what the scan and reader expect, that
the download lands in the external cache, that a sending, failed and
restart-pending copy each show up in the `messages_v2` query, that the
posted document carries `application/json`, and that the staged record stays
in `Android/data/org.purple.telegram/files/purple-sync-records` after the
send instead of moving to Telegram Documents.
