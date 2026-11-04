# Review: Android Magic Purple Sync R4b (automatic path on the magic group, first use)

Scope: `463220e42a..f24306f432` on `sync-magic-r4b` (R3 approved at 463220e42a). purple-core is out of scope
(already reviewed; gitlink moves to a9dba73e0b with no client change). Static review only: this VM cannot build
the app or run the host suites, so every trace below is read from the code, not executed.

Verdict: CHANGES. P1=1 P2=7 P3=6.

## The two device-run root causes

1. Every automatic pass threw `AutomaticNonSelfHeld`: fixed. `PurpleSyncAutoCore.Inventory` now requires the
   inventory's chat to equal `store.chat()` (the state's chat) and throws `AutomaticChatMismatch` otherwise
   (`PurpleSyncAutoEngine.java:428`). The inventory carries its own chat token to the bridge
   (`sync_auto_bridge.h:151-158`), so core compares the inventory chat with `facts.chat` instead of being handed a
   copy of it. The network read already came from `stateChat()`. `PurpleSyncMagicScenarioCases` drives the real
   engine over the magic group. Every exception of a pass now reaches `Port.failed` (engine catch at :485, mutate
   catch at :568, and the `queue` wrapper in `PurpleSyncAuto.Runtime.queue`).
2. Android's first publish started a fresh space: fixed for the cause stated. With a bound magic chat, the engine
   passed `state=null` to core whenever `PurpleSyncRecoveryModel` did not answer Bound (`PurpleSyncAutoEngine.java:207-216`).
   That scan refuses entries that say nothing about the binding, so core answered AccountUnbound and the moved state
   never published. The auto Runtime's Platform now overrides `requiresRecovery()` with `recoveryScanApplies(bound)`
   (Saved Messages or no binding only), which matches `PurpleSyncMagicManual.requiresRecovery`. Core's binding-token
   gate still runs. The scenario `migration` case plants `sync-chat-setup.json` in both folders and asserts one
   space, no first use and no AccountUnbound. `following()` naming the bound chat when there is no `state.json`
   is also correct.

## Desktop findings, checked on Android

- Answer asked again after a crash or failed step: mostly fixed. `PurpleSyncFirstUse.start` stores
  `choice|space` in main settings (`commit()`) before `setAside`, and a later Ask for the same space takes it.
  One gap remains: see P2-1.
- First-use failures without a reason code: fixed in diag. Every `finish` logs
  `sync first_use action= outcome= reason= archived=`. Gaps: `apply_refused` does not say which apply status, and
  `manualRunner` returning null is silent (P3-2).
- A device with no state scanning on every activation: fixed. `PurpleSyncFirstUse.due` plus the persisted
  `purple_sync_first_checked_<key>` limit lifecycle triggers to once per 15 minutes. Enable and LocalSaved always run.
- A join that re-derives the head: fixed. Join, "Use the synced settings" and both successor paths use core's
  `headKey`. The mismatch path re-plans after the set aside and uses `again.headKey`.
- Host cases for the migration and join claims: half fixed. The R1 (install D) / R2 (install A, parent R1) case
  exists, but only for fresh devices in `PurpleSyncFirstUseCases`. The migration case still has only one install
  that published before magic: see P2-6.

## P1

### P1-1. Setting aside a state next to completed carry leftovers leaves the store unopenable

Owner item 5 says pre-magic carry leftovers must not block first use. On Android they block it in the
`space_mismatch` path, after the old state is already gone.

Trace (an Android device that finished a pre-magic carry, so `carry.json`, `carry-objects`, `carry-history` and
maybe `carry-cache.json` stay next to `state.json`, and whose space differs from the magic chat's records):

1. The automatic check sees `spaceMismatch`, so `runFirstUse` runs. Its Check opens the store:
   `PurpleSyncChatMoveStore.artifacts(root)` is true, `completed(root, key)` reads `state.json`'s `binding_token`
   and the carry wrapper's `binding_sha256` matches, so `terminalCarry=true` and the open is Ready. The review is
   NeedsReview `space_mismatch`, and the plan is Ask.
2. The user answers. `answers.put` runs, then `PurpleSyncRunner.setAside`: `bound()` passes because
   `carryBlocks()` is false while `terminalCarry` holds. `setAside()` moves `pending`, `auto.json`,
   `retained-bases` and `state.json` into `orphaned-<date>`. It leaves the carry files in place
   (`PurpleAccountSyncStore.java:459`).
3. `recheck` opens a new store. `completed()` reads `state.json`, which is now missing, so `binding` is `""`
   (`PurpleSyncChatMoveStore.java:164-165`). `inspectRetained` asks the codec, which requires
   `binding_sha256 == RecordHash(binding)` (`sync_chat_move_store_bridge.cpp:439`). The hash of `""` is not the
   recorded one (the `mode==2` empty-state exception at `PurpleSyncChatMoveStore.java:114` does not apply to a real
   carry), so `completed` returns false. `terminalCarry` is false, and `open` returns `CarryPending`
   (`PurpleAccountSyncStore.open`, near line 308).
4. The check does not finish, so first use ends `check_failed`, or `check_changed` if a refusing review comes back.
   From then on, every Check, every automatic pass (`hasState()` is true because of `carry.json`, then
   `store.open` is not Ready, so `StoreMismatch`) and `initialize` (`if (carryOnly || carryBlocks()) return CarryPending`)
   refuse. The kept answer never gets a chance to act. The device cannot sync until someone moves files by hand.

Settings and History are intact, so no settings are lost, but sync on that device is dead after one tap of the
one-time choice. The host cases plant only `sync-chat-setup.json`, never a carry, so they cannot catch this.
Severity depends on whether any owner Android device carries completed carry files. The R4b brief lists carry
leftovers as a known D1 condition, so I rate it P1.

Fix: retire the carry leftovers in the same set aside, as Desktop does with `RetireSyncCarryLeftovers` to
`sync/carry-retired-<date>/`, or move them into the orphaned folder before `state.json`. Then make `hasState()` and
`open` treat a carry with no state as no state. Add a host case: completed carry, then mismatch, then each answer,
then Ready.

## P2

### P2-1. An answer tapped on the Sync screen is not persisted until a Check finishes

`ROW_FIRST_SYNCED` and `ROW_FIRST_THIS` only set the field `firstChoice` and call `startCheck`
(`PurpleSyncActivity.java:235-240`). The answer reaches `answers.put` only inside `PurpleSyncFirstUse.start`, after
`onChecked` gets a Finished check. If that Check fails or is cancelled (network, the user leaves, the process dies),
the answer exists only in the fragment field. The next automatic first use plans Ask again with no kept answer, so
the owner is asked twice. This is the Desktop finding in a narrower form. Persist `choice|firstAsk's plan.space` at
tap time.

### P2-2. Ask and Wait live only in memory, so the status says On after a restart

`Runtime.firstUse` is a volatile field. After a process restart within 15 minutes of the last first use, a lifecycle
trigger reaches `firstUse(event)`, `due()` is false, it returns true, and nothing sets a status. `render()` then
shows the plain ready text, not "Waiting for a device…" or the attention line, until the next due slot or a Save.
For a `space_mismatch` device, `held()` is false after restart, so automatic sync runs again until its next check
re-asks. Persist the last first-use result (with the space) next to the checked timestamp and render from it.

### P2-3. The attention line sends the owner to a Sync screen that shows no choice

`PurpleSyncFirstAskAttention` says "choose on the Sync screen which settings to keep". But the screen does not check
on open (`onResume` only builds the runner), and the answer rows show only when `firstAsk` was set by a Check on that
screen. So the owner opens Sync, sees no choice, and has to press Check first. Policy is "ONE choice, in the sync
screen". Run a Check on open when automatic first use reported Ask, or show the rows from the kept Ask result.

### P2-4. A failed successor publish ends as Joined, and nothing retries it

For "Use this device's settings" and for JoinSilently with other bytes, `act` applies with the local choice (the new
state joins at the head), then publishes `newContent(fingerprint, [headKey])` after a recheck. If that publish
refuses or fails, the result is `Joined`. The answer is cleared, and `settle` calls `engine.enabled(true)`, which
writes the automatic record for the new state. If that record takes the file on disk as saved, which is how the move
treats it, automatic sync sees no local change and never publishes this device's settings. Then the chat keeps the
other head, and this device keeps its own settings unshared, after the owner explicitly chose this device's.
Unverified (it needs core's Enable semantics). Add a host case (ThisDevice, then refuse the successor post, then an
automatic pass) and, if it holds, keep a retry or report it as Refused instead of Joined.

### P2-5. A transient exception now pauses automatic sync until a manual Check

`Port.failed` records a persisted failure for every exception, `io` and `illegal_state` included, and `held()` turns
it into a chat problem that core holds. A single file-race `IOException`, or "automatic pending state unavailable",
now stops automatic sync until the owner opens Sync and Checks or toggles the switch. Before R4b the next trigger
retried. "Never silent" is right, but "fully automatic" suggests logging every failure and pausing only after
repeated failures or for codes that cannot heal (`automatic_chat_mismatch`, `native_unavailable`), with a backoff
retry otherwise.

### P2-6. The migration claim is not tested with two installs that both published

`PurpleSyncMagicScenarioCases.migration` has device 1 publish in Saved Messages and device 2 only join. The case the
Desktop review asked for (R1 from install D, R2 from install A with parent R1, both in the shared Saved Messages space,
then both migrate) is not exercised. That is where "one head per install" matters: D's moved head R2 is missing from
the magic chat, so D publishes there; A then meets D's successor or both of them. Also, the docstring says "a Save on
the second device writes once and the first device applies it", but the code saves on device 1 and asserts device 2
holds `FileAbsent` (see P3-4).

### P2-7. The concurrent empty-chat race can still leave two spaces, with no detection afterwards

The recheck before a first record covers the sequential race (the `race` host case). Two fresh customised devices
that both pass the recheck before either post lands both publish parentless first records. A Verdict Empty ticket
is not fresh-checked again (`freshCheckFirst` is only Choose or Conflict). Each device then has records of its own
space in the chat, so `spaceMismatch` (none of the chat's records in my space) never fires, and the chat stays split
silently. The window is seconds, so the race is rare, but it is exactly the "second sync space" outcome. Suggest a
post-publish read that settles a tie deterministically (for example, the lower message id wins and the other device
runs first use), or a core rule for it. Raise it with core if it belongs there.

## P3

1. `PurpleSyncFirstRefused` says "nothing was changed", but after `setAside` (the `check_failed`, `check_changed`
   or `apply_refused` outcomes with `archived=1`) the old state was moved. Choose the text by `outcome.archived`.
2. Diag gaps: `PurpleSyncAuto.runFirstUse` returns silently when `manualRunner` yields null, and `apply_refused`
   omits the apply status. Log `first_use outcome=refused reason=runner_unavailable`, and add the
   `ApplyStatus` code.
3. `firstUseRunning` is reset only in callbacks. A runner callback dropped by `live(started)` (generation change)
   would block automatic first use until the process restarts. Reset it on a timeout, or in `runner.close()`.
4. The `migration` docstring does not match its code (see P2-6), and it asserts a permanent `FileAbsent` hold for a
   migrated device with no file, which is policy case 3 in spirit. Say whether that hold is intended.
5. `PurpleSyncFirstUse.finish` clears the kept answer on `NotFirstUse`. A transiently unclear review (an unbound
   NeedsReview such as `heads_unclear` during a race) drops a given answer. Clear it only once a bound Ready
   review exists in the answered space.
6. Docs: `docs/purple/sync-magic-r4b.md` still says "To do (none of it started)" for Android, and Android's
   negative controls (to-do item 6) are not listed anywhere in the tree. `PurpleSyncAutoFailedAbout` says "Nothing
   was sent", which is not guaranteed after a mutate-step failure.

## Other checks (no finding)

- Wrong or duplicate group: no new creation path. Automatic first use goes through `manualRunner`, whose
  `resolveChat` runs the lookup with `Trigger.auto` or `post`, never a manual lookup, so it neither clears problems
  nor creates. It runs only with a bound non-self chat.
- Two first uses at once (Sync screen and automatic): `PurpleSyncOperations` serialises them per account. A second
  `join` sees `AlreadyBound` and stops with NeedsRecheck.
- Settings loss: Join and "Use the synced settings" go through `PurpleSyncApply.writeWithHistory` with Undo. The
  set aside keeps the settings file, History and `chat.json`.
- Paths: the durable layer anchors at the files directory. `inside` and `below` in Java, and `Parent` in the
  bridge, refuse anything not strictly below it. Link checks use `notLink` (last component only), and the anchor is
  opened following links, which suits `/data/user/0`. `PurpleSyncImpactGuard`'s new outside-root throw compares
  absolute paths from the same root, so it is consistent.
- Search: no request added. `search` appears only in design-doc prose.
- Foreign or forwarded records: first use reads the same filtered inventory as Check. The bridge parses the
  inventory chat with the account user id, and core rejects a chat other than the state's.
- Upstream hygiene: no upstream file changed except the test stub `FileLog.java`. Row ids use the reserved range
  `0x70750110` and `0x70750111`.
- Privacy: no ids, phone numbers, emails or home paths in the diff. Diag lines carry codes only. The kept answer
  stores a space id in local preferences only.
