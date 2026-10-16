# Merging upstream Telegram

Purple keeps its code out of upstream files and touches them only through
small hooks (see "Keeping upstream merges easy" in AGENTS.md). This page is
the checklist for merging a new upstream release, and it describes the tools
that measure how far Purple's edits reach into upstream files.

## Terms

- **Upstream file**: a path that exists at the upstream base commit. Files
  Purple added (the `org.telegram.messenger.purple` package, `ui/Purple*.java`,
  `TMessagesProj/jni/purple_jni/`, `purple/`, `docs/`, the purple-core
  submodule) are not upstream files.
- **Upstream base**: the upstream commit Purple last merged, recorded in
  `purple/UPSTREAM_BASE`. This repository has no upstream remote, so the base
  lives in that file. Today it is 62b56a07c, upstream's "update to 12.10.1
  (7038)".
- **Ratchet**: `docs/upstream-hooks.baseline`, the recorded size of every
  Purple edit to an upstream file. The check refuses to let an edit grow
  without the file being regenerated in the same commit. A change that
  shrinks an edit regenerates it too, so the recorded size drops with it.
- **Refactor batch**: one step of the 2026-10 refactor that moves older Purple
  code out of upstream files (A0 to A20, C0 to C6). The batch names below say
  which step removes an entry.

## The tools

`purple/upstream-hooks.sh` reads git only, so it needs no build and can run at
any time. Without a revision it reads the working tree: tracked files, staged
or not, plus new files once they are marked with `git add -N`. It runs with
`GIT_NO_LAZY_FETCH=1`, so in this blobless clone a missing object stops it with
an error instead of starting a download.

- `purple/upstream-hooks.sh report [--lines] [REV]` prints, for each edited
  upstream file, the added and removed lines, the hunks (three lines of
  context), the hunks that add more than three lines, and the Purple comments
  longer than one line. Removed lines are upstream lines Purple changed or
  deleted, which signals a restructure. It then prints the totals, the Java
  breakdown (comments, blank lines and imports among the added lines), the
  account-less Work Mode calls, the callers to classify at a merge, and the
  counter arrays next to `dialogsWithUnread` in MessagesStorage. `--lines`
  adds where each big hunk, multi-line comment and removal sits. README.md is
  shown apart and never ratcheted: its Purple sections sit before upstream's
  text on purpose.
- `purple/upstream-hooks.sh check [REV]` runs the rules below and exits 1 on
  any failure. With REV, it reads the ratchet from that revision too; without
  REV, it reads the working-tree ratchet. A nonempty `PURPLE_HOOKS_BASELINE`
  explicitly overrides the ratchet path in either mode. Run it before every
  commit that touches an upstream file.
- `purple/upstream-hooks.sh baseline [REV]` rewrites the ratchet from the tree
  and prints the lines that changed. Commit the ratchet together with the
  change that moved it.
- `purple/apk-facts.sh [APK]` runs on the build worker after a build and prints
  the facts a refactor batch must not change: `aapt2 dump badging`, AppName and
  AppNameBeta in every configuration, the sorted string pool, `lib/`, the
  manifest tree, the generated BuildConfig.java files (credential values shown
  only as a digest), the CMake configure commands and compile_commands.json
  under `.cxx`, and R8's usage.txt and sorted seeds.txt. Each dumped file ends
  with a newline so the following header stays separate. Store its output next
  to the APK and diff it against the build just before the batch.
- `TMessagesProj/jni/purple_jni/tests/run_upstream_hooks.sh` tests the hooks
  script against a scratch repository, one case per rule.

### What the check refuses

- **The base moved.** The ratchet names the base it was made against. When
  `purple/UPSTREAM_BASE` names another commit, the ratchet must be regenerated.
- **An edit grew.** An upstream file has more added or more removed lines than
  its ratchet entry. An upstream file with no entry must equal upstream, which
  is how a file that a refactor batch returned to upstream (gradle.properties,
  the strings files, MessageObject and others) stays that way.
- **A Work Mode call without the account.** A preset-level `PurpleGate` call in
  an upstream file that does not pass the account it acts for, beyond the ones
  recorded as needing the account. See the next section.
- **An unclassified caller.** A file has more lines calling `getDialogFilters()`
  or `isDialogMuted(` than the ratchet records, anywhere in the Java sources.
- **A locale without the app's name.** A library `values*/strings.xml` that
  defines AppName exactly as upstream does, while the standalone module defines
  no AppName for the same qualifier. Until the AppName overrides move to the
  standalone module, every library strings file still carries Purple's own
  AppName edit, so this only fires for a locale upstream newly adds.
- **The stock last-seen request without its guard.** MessagePrivateSeenView
  contains `TL_inputPrivacyValueAllowAll` without the line calling
  `PurpleLastSeenSheet.onLastSeenButton(`. Upstream's button there shows your
  last seen to everybody, permanently.
- **Last seen paired with AllowAll elsewhere.** An upstream file other than
  PrivacyControlActivity, the TLRPC schema and MessagePrivateSeenView mentions
  both `TL_inputPrivacyKeyStatusTimestamp` and `TL_inputPrivacyValueAllowAll`.
- **An unread counter the mirror misses.** Once
  `messenger/purple/PurpleUnreadMirror.java` exists, every `int[]` or `int[][]`
  field declared next to `dialogsWithUnread` in MessagesStorage must be named
  in it, so name `start()`'s parameters after those fields.

## Build files

Purple's Gradle settings live in `purple/gradle/`. Each upstream build file
that needs them carries one hook, an `apply from:` as its last line, so the
Purple script runs after upstream's `android` block and changes it in place:

- `TMessagesProj/build.gradle` applies `purple/gradle/library.gradle`. That
  script adds arm64-v8a to the native ABI filters, adds `APP_ID` and `APP_HASH`
  to BuildConfig from `local.properties`, replaces upstream's
  `-DANDROID_STL=c++_static` with `c++_shared` in place, adds
  `-DPURPLE_QT_ANDROID=` and the `purplecore` target, and applies
  `purple/version.gradle`.
- `TMessagesProj_AppStandalone/build.gradle` applies
  `purple/gradle/standalone.gradle`. That script sets the package id
  `org.purple.telegram`, drops upstream's `.web` suffix from the `debug` and
  `standalone` build types, leaves the standalone APK unsigned, keeps only
  arm64-v8a in the `afat` flavor, and refuses a standalone build when
  `local.properties` has no `TELEGRAM_APP_ID`.
- `purple/gradle/common.gradle` gives both scripts `purpleProp`, a reader for
  `local.properties`, because upstream's `getProps` is a method of
  `TMessagesProj/build.gradle` that an applied script cannot call.

`gradle.properties` is upstream's, so it merges cleanly although every release
bumps its version lines next to `APP_PACKAGE`. That includes
`APP_PACKAGE=org.telegram.messenger`: only the standalone module gets Purple's
package id. This fork builds only the standalone module
(`:TMessagesProj_AppStandalone:assembleAfatStandalone`, as README.md says).
The other app modules (TMessagesProj_App, TMessagesProj_AppHuawei,
TMessagesProj_AppHockeyApp and TMessagesProj_AppTests) are upstream's as they
stand, so building one of them makes an APK with Purple code inside that
claims Telegram's own package id, `org.telegram.messenger`. Do not build or
hand out those modules. The repo-root `Dockerfile` is upstream's and builds
TMessagesProj_App and TMessagesProj_AppHuawei alongside the standalone module;
do not use its unchanged build command for Purple.

An upstream change that breaks these scripts fails the Gradle configuration
instead of building something different:

- library.gradle replaces `-DANDROID_STL=c++_static` with `c++_shared` in
  place across defaultConfig, buildTypes and productFlavors. It then requires
  exactly one `-DANDROID_STL=` argument across those scopes, with the value
  `c++_shared`, and lists the STL arguments if one is missing, duplicated or
  conflicting. Other CMake arguments keep their order and values. Without
  c++_shared, AGP does not package `libc++_shared.so`, and `libpurplecore.so`
  would fail to load on the phone.
- If upstream renames the `debug` or `standalone` build type or the `afat`
  flavor, standalone.gradle's `getByName` fails, where the DSL's `debug { }`
  form would quietly create an empty one.

## Work Mode hooks carry the account

Work Mode lists are to become per account, so a Work Mode hook in an upstream
file must take the account or session it acts for instead of reading a
global. The report lists every preset-level `PurpleGate` call in an upstream
file that passes no account: `filtering`, `foldersRestricted`, `peeking`,
`hidingEverywhere`, `hidingArchive`, `badgeRebuilt`, `shownFilters`,
`refillViewPinsIfNeeded`, `hidingFromSuggestions`, `filteringStories`,
`addStoryShown`, `state`, `recentStyle` and `menuLabel`. A call counts as
carrying the account when it has an argument list of its own, or for
`shownFilters` a second argument.

The ratchet records the 26 account-less calls of today, and that record is the
hook registry. 24 of them still need the account, and the refactor batch
named here gives each one its account:

- A3 (DialogsActivity): the drag early return (`filtering`), the archive guard
  (`hidingArchive`), the FilterReorder guard (`foldersRestricted`), the strip
  identity (`state`), and the menu label (`menuLabel`, a label only).
- A4: the Settings row subtitle in SettingsActivity (`state`, a label only).
- A6: MessagesController's `shownFilters`, `hidingArchive`, `hidingEverywhere`
  and `refillViewPinsIfNeeded`; FilterTabsView's three `foldersRestricted`;
  MediaDataController's `hidingEverywhere` and `hidingFromSuggestions`.
- A9: NotificationsController's badge calls now pass the loop account to
  `PurpleBadge`; its legacy `badgeRebuilt` flag remains shared.
- A10: DialogStoriesCell's `filteringStories`, and StoriesController's
  `filteringStories` and `addStoryShown`.
- A11: DialogsSearchAdapter's `hidingEverywhere` and `hidingFromSuggestions`,
  and DialogsChannelsAdapter's `filtering` and `state`.

The other two are DialogCell's `recentStyle()` reads, and they stay without an
account for good: the mark style is app-wide, and the mark itself is null for
an account that shows no temporary rows. Once the batches above have landed,
they are the only entries left. A new hook whose call site has no account in
hand is recorded in the ratchet as needing one, and the commit says so,
rather than inventing an account.

## Checklist for an upstream merge

1. Run `purple/upstream-hooks.sh check` on the tree before the merge. It
   should pass.
2. Merge the upstream release.
3. Set `purple/UPSTREAM_BASE` to the full hash of the merged upstream commit.
4. Run `purple/upstream-hooks.sh check`. The `base` failure is expected. Every
   other failure is merge work:
   - `ratchet`: a Purple edit grew while resolving a conflict. Shrink it back to
     a hook.
   - `caller`: upstream added a call to `getDialogFilters()` or
     `isDialogMuted(`. Classify each new call. A site that displays folders
     keeps `getDialogFilters()`, which returns the folders the running preset
     shows; a site that edits or counts every folder uses
     `getDialogFiltersUnrestricted()`. A mute check that must follow the
     user's own mute under a preset uses `mutedWithoutPreset`.
   - `appname`: upstream added a locale that defines AppName. Give that
     qualifier Purple's AppName and AppNameBeta the way the other locales
     carry them.
   - `account`, `privacy` and `counters`: see the rules above.
5. Run `purple/upstream-hooks.sh baseline`, read the lines it prints, and
   commit the ratchet with `purple/UPSTREAM_BASE`.
6. Re-read the upstream code that Purple restates or replaces, since a merge
   that changes it applies cleanly and changes behaviour silently:
   - `StoriesController.hasStories` and `hasOnlySelfStories`;
   - ProfileActivity's `hiddenStatusButton` expression, which Purple replaces
     with its own eligibility test for Last Seen Peek;
   - where a Purple `continue` skips uncounted chats in
     `MessagesController.checkCollapsedDialogsInCommunity`, it is correct only
     while nothing follows the if/else it sits in;
   - where a Purple local `hints` in `MediaDataController.buildShortcuts`
     shadows the field, upstream code added later in that if-block reads the
     filtered list;
   - where ChatMessageCell passes private fields to PurpleImportButton, they
     must still mean what they did;
   - NotificationsController's `PurpleBadge` arguments must still be the
     loop account and that controller's own `pushDialogs` and unread total.
     Keep shown-then-counted filtering in the muted loops separate from the
     count-only rebuild. Preserve the message loop's outer null guard and
     the chat loop's earlier stock `dialog.id` dereference and catch;
   - direct `PurpleNotify.previewAlways` calls must test all three story
     flags before the lazy dialog-id getter. The summary keeps its one-dialog
     short circuit and precomputed id. Grouped notifications instead exclude
     story groups and story reactions, with a last message for non-story
     groups; they deliberately do not add the direct story-mention exclusion;
   - keep `passcode && !previewAlways` in the group-summary photo gate. The
     grouped stock passcode block stays intact between name/photo save and
     restoration for the same preview decision, and still disables replies;
   - `PurpleNotify.silenced` must load the gate before the mute query, before
     the controller's stock override lookup. It adds no load to Gate.silenced
     itself, which other callers intentionally use without loading;
   - the CMake arguments and targets in `TMessagesProj/build.gradle`'s
     `defaultConfig`, which `purple/gradle/library.gradle` edits in place, and
     the standalone module's `applicationId`, build types, `afat` flavor and
     signing, which `purple/gradle/standalone.gradle` overrides (see "Build
     files"). A CMake argument that upstream adds there reaches Purple's
     native build too.
7. If upstream adds a counter array next to `dialogsWithUnread` in
   MessagesStorage, Purple's unread counting must cover it: today the
   `purpleBucket` calls in the counting loops, and after A8 PurpleUnreadMirror.
   The report prints the current list.
8. When upstream changes the signature of
   `SendMessagesHelper.prepareSendingDocumentInternal`, Purple's added
   `sendReceipt` parameter has to follow at every call site, and
   `prepareSendingPurpleDocument` calls it too. A mistake is a compile error,
   so it cannot pass silently.
9. Build, and diff `purple/apk-facts.sh` against the build before the merge.
   Expect upstream's own changes, and check that the package id, the AppName
   values and the Purple libraries in `lib/` are unchanged.

## Last Seen Peek UI seams

- ProfileActivity deliberately replaces the stock hidden-status button predicate
  with PurpleLastSeenTrade.eligible(user). Do not OR the stock predicate back in:
  it adds the non-Premium by_me button while peeks are off and excludes Premium
  accounts. The omitted upstream expression is:
  `hiddenStatusButton = user != null && !isOnline[0] && !getUserConfig().isPremium() && user.status != null && (user.status instanceof TLRPC.TL_userStatusRecently || user.status instanceof TLRPC.TL_userStatusLastMonth || user.status instanceof TLRPC.TL_userStatusLastWeek) && user.status.by_me;`
- Keep the profile right-drawable click routed directly to the trade, without
  inserting MessagePrivateSeenView.showSheet in front of its confirmation.
- MessagePrivateSeenView keeps an empty username when its stock positive-dialog
  lookup returns null. Preserve the null guard around UserObject.getFirstName:
  the stock utility returns "DELETED" for null, changing the read-time text.
- MessagePrivateSeenView's first button must begin with the lastSeen guard calling
  PurpleLastSeenSheet.onLastSeenButton. That helper always consumes lastSeen,
  including peeks off, ineligible targets and no safe fragment. Keep it before
  setLoading and the stock TL_inputPrivacyValueAllowAll request. adapt only
  relabels/removes the button and is not the privacy barrier.
- The cell wrappers keep the exact original formatUserStatus arguments and one
  setText/layout. Subtitle private pressed, bounce and long-press resets remain
  in ChatAvatarContainer and run before the trade.
- start keeps its main-looper fast path, posts only off-thread callers before
  idempotence/registration, and retains recover's deferred dispatch.
- Recovery connection observers use the event account and late selected account;
  LaunchActivity resume recovery stays within super.onResume's lifecycle dispatch.

## Work Mode lists, folders and mute seams

- checkCollapsedDialogsInCommunity: each countedInTotals continue follows the
  dialogsCommunityLastMessageDate.put and precedes the stock count/mark block.
  At this baseline no executable statements follow either collapsed branch
  before the enclosing allDialogs loop ends. Reassess the continue if upstream
  adds such work, so uncounted chats do not lose new side effects.
- buildShortcuts: local hints shadows this.hints only inside the passcode block.
  Keep its restored capped loop and original peer/index/order operations; do not
  accidentally add field hints reads inside that scope or move filtering past
  the maxShortcuts limit. No filtering returns the original list instance.
- NotificationsSettingsActivity: retain the stock topPeers copy before filtering
  it, then keep exception seeding and PurpleDefaults values unchanged. The live
  MediaDataController.hints list stays untouched.
- MessagesController uses its currentAccount for facade hooks and passes the
  actual controller for promo exclusion. FilterTabsView uses late-bound
  UserConfig.selectedAccount consistently with its real requests. Fixed legacy
  Gate flag/view/pin methods remain shared-policy delegates behind the facade;
  these contracts do not implement future per-account storage or a new policy.
- Preserve isPromoDialog(id, false) before hiddenEverywhere, so the promo row is
  removed/reinserted once and never filtered twice. Keep mutedWithoutPreset for
  user toggles and isDialogMuted for effective mute; membership ignores preset mute.

## Local Premium cache and folder seams

- getSponsoredMessages must consult Local Premium before its first cache access,
  including the loading/five-minute cache hit. Keep the exact stock request and
  callback body after that hook, and bind both actual ChatActivity null consumers.
  removeFromSponsored separately retains an eligible cached-list mutation
  through its owner read-only seam, without requesting under Local Premium.
- Keep ChatActivity's stock premium/settings/eligibility early return. The A2
  sponsored hook and its PurpleChatHooks method are removed here; do not remove
  unrelated purple hooks or settings-import code, or restore the old A2
  "(your setting)" log. Sponsored policy logs occur per controller invocation
  and are not network-request completion evidence.
- lockFiltersInternal must return after a handled Local Premium/non-Premium
  unlock, so stock locking cannot run afterward and duplicate notifications
  cannot be posted. The helper uses the actual controller account, clears only
  existing locked flags, logs only over the same count limit, and posts the
  original event only when a flag changed. Keep the nonhandled stock algorithm,
  count limit and log-before-changed-notification ordering.

### Sponsored cache removal during Local Premium

The display/request hook refuses sponsored content before cache access while
Local Premium is enabled. The existing removal caller separately receives only
an already cached loading or unexpired result through the owning controller;
its original removal and bot-message fast path stay unchanged. This path makes
no sponsored request while Local Premium is enabled. With the switch off, it
uses the original getter. Missing or expired cached results under Local Premium
remain absent; this is an intentional request omission, not cache clearing.
Keep the original elapsedRealtime, absolute-difference and five-minute cache
eligibility together with this removal hook when merging upstream.

## Storage unread mirror seams

- Preserve in-place resets and all eight adjacent counter-array field names.
  PurpleUnreadMirror retains the real six bucket references and read mention
  references; a new array requires an explicit mirror extension, as the unchanged
  original counter-field audit checks.
- Before each full/read user/chat classification chain, capture one account-bound
  counted answer. Guard the whole chain, including lazy community-cache admission,
  with that answer and pass it unchanged to the following mirror hook. Keep trailing
  user/chat puts and encrypted-user counts outside the eligibility guard. An
  uncounted child must not newly expose its community to folder exceptions.
  Never move a countedInTotals skip into
  collection or type-loop entry: unread/mention maps, newUnreadDialogs and secret
  chat/user/chat dictionaries must still receive their original side effects.
- The read chat hook passes !ChatObject.isCommunity(chat), and every changed
  cell is restored or retained before re-snapshot. Keep mention-plus-group/channel
  double increments and all Archive/main array dimensions intact.
- Keep full uncounted logging before the filter loop and stock-sum logging after
  main assignment. Read uncounted logging precedes its same hold/recount decision;
  the main continue preserves pending-main state while folders/Archive apply deltas.
- Keep the public updateAllFiltersCountersForPurple compatibility hook while Gate
  calls it; recountAll receives this explicitly and posts the original false-reset through
  that exact owner storage queue; the account-only entry uses the singleton. Retain the seven lazy community exception-walk guards unchanged.
