# Automatic settings sync on Android

Open Purple Settings, then Settings sync for the account you want to use. First
use manual Check to join its sync stream, then turn on **Automatic settings sync**.
It defaults off for every account. The older Send after Save switch stays separate.
Automatic mode supports the account's implicit Saved Messages stream; moved/v2
streams and library publishing remain held.

An opted-in account checks when its session is ready, the app returns to the
foreground or the device/app unlocks. Those checks are limited to one per 15 minutes.
Saving that account's existing settings file uses the existing five-second
save debounce and bypasses the lifecycle limit. A newer settled save replaces
queued intent. Creation, imports, remote applies, History/Undo and the legacy
Send after Save toggle do not become automatic Save events.

A strict remote successor with a Ready safe impact can apply through the editor,
including truthful History and Undo. Conflicts, losses, danger, schema/Work Mode
scope changes, absent/bad/fallback files and unanswered ownership stay held for
manual Check. The status row explains attention and retry state; background work
opens no dialog. The full opening environment/user/login lifetime, file authority,
role and impact inputs are checked at asynchronous boundaries and before effects.

Publication requires exact bytes from a real local Save and a fresh complete
inventory. It stages and reads back the issued record before upload, edits a freshly
verified owned head where supported, and reconciles after edit refusal or an
uncertain outcome before sending again. Only this install's issued obsolete
records may retire, at most four per run. A fresh exact-id read must prove the
owned body before deletion. Inventory omission never proves absence; uncertainty
requires explicit Absent from a fresh exact-id server response or a definitive
delete response before the own-message ledger can drop that entry.

Automatic uploads and final post/edit RPCs stay in Purple's in-memory executor.
They never enter Telegram's persistent outbox. Old modern staged outbox rows,
including positive-id edits and user-tapped retries, are diverted to current core
reconciliation. The reserved path and document filename/caption survive restart
and moved staging files. Manual modern sends resume through Settings sync rather
than an unchecked outbox retry. Ordinary Telegram retries stay unchanged.

## Persistence and recovery

sync/auto.json belongs to the resolved full account key. The core codec and
PlanSyncAuto at the pinned core revision own policy, unknown fields and exact 64-bit
counters. The app persists every transition, including NoOp/coalescing, with
compare-and-swap before effects. It shares the manual runner's account lease and
store lock, and releases physical locks before network waits. Interrupted writes
use the existing AtomicFile protocol; unknown, unsafe or opaque artifacts refuse
without resetting automatic work. Manual Check recognizes the auto file, while
Start again archives only pending/state and keeps automatic configuration.
A pending checkpoint cannot continue into a different install, space or binding.
Its routing/impact fingerprint is also retained across restart. Changed or unknown
opening authority holds old staged bytes for manual review; Disable never blesses
them with the new scope. A fresh later run captures its own authority.

Network uncertainty arms core retries after 30, 120 and 600 seconds, then stops until
a new eligible trigger. Triggers during backoff preserve its deadline and budget,
even after restart or expiry. Terminal run state survives Holds and Disable.
A stale retry callback cannot consume a newer run/ticket/deadline. Request and
execution state is released on timeout; at most 128 weak identity tombstones
recognize late replies and request reconciliation without delivering success. Request timeouts
are bounded one-shot transport deadlines. There is no polling timer or WorkManager
job. Disable blocks admission immediately, cancels reads and guards queued upload
and editor publication; uncertain already-issued work remains for reconciliation.

The enabled preference is an early admission hint and an immediate off switch.
It never authorizes an action in place of the durable core record. An explicit
Enable persists the core opt-in before making the hint live. Lifecycle events for
an off account do not open its settings or store. Opening the sync UI reads status
without creating a store. Retained signed-out files/preferences remain inert for
other accounts under the M2B3 discovery rules.

## Headless verification

The canonical driver is TMessagesProj/jni/purple_jni/tests/run_modern_sync.py.
Pass an actual AGP JavaCompile receipt, the real JSON jar, rebuilt JNI library and
an unused output directory as described in the modern_sync test README. Its auto
suite runs the production engine, policy, store, editor/History and network
scanner/reader/post paths against synthetic platform/RPC ports. It tests opt-in,
all trigger/retry boundaries, safe apply and holds, edit fallback, uncertain and
duplicate sends, archive/restart, exact-absence retirement, the four-record bound,
CAS loss, A/B leases and stale role/backend/logout/slot callbacks. The native core
source roster is checked against purple.cmake and all core translation units.
Real JavaCompile, host/arm64 JNI and exports are separate checks. Host ports do
not qualify widgets, Looper, actual Android filesystem/network behavior, devices,
APK packaging or shrinking.
