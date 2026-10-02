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
  any failure. Run it before every commit that touches an upstream file.
- `purple/upstream-hooks.sh baseline [REV]` rewrites the ratchet from the tree
  and prints the lines that changed. Commit the ratchet together with the
  change that moved it.
- `purple/apk-facts.sh [APK]` runs on the build worker after a build and prints
  the facts a refactor batch must not change: `aapt2 dump badging`, AppName and
  AppNameBeta in every configuration, the sorted string pool, `lib/`, the
  manifest tree, the generated BuildConfig.java files (credential values shown
  only as a digest), the CMake configure commands and compile_commands.json
  under `.cxx`, and R8's usage.txt and seeds.txt. Store its output next to the
  APK and diff it against the build just before the batch.
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
  script narrows the native build to arm64-v8a, adds `APP_ID` and `APP_HASH`
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
hand out those modules.

An upstream change that breaks these scripts fails the Gradle configuration
instead of building something different:

- If upstream rewords or drops `-DANDROID_STL=c++_static`, library.gradle
  stops with a message that lists the CMake arguments it found. Without
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
- A9: NotificationsController's two `badgeRebuilt`.
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
