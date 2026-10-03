## Purple Telegram

A personal fork of Telegram for Android that adds **Work Mode**. The desktop
counterpart lives at https://github.com/NightMachinery/tdesktop and the shared
logic both clients build on lives at https://github.com/NightMachinery/purple-core.

Purple Telegram installs **alongside** official Telegram: it ships under its own
application id, `org.purple.telegram`, with its own contacts account type and its
own launcher entry, so both apps can be signed in at the same time.

### Pinned music

Open a chat or forum topic's menu and choose **Download all pinned songs**. The
dialog starts with one music song before and one after each pinned song; each
number can be set from 0 to 20. It also includes the other songs in a pinned
album by default. The action searches all pinned messages, including those not
yet loaded in the chat. In an ordinary migrated group it also searches the old
group. Repeated songs are queued once into Telegram's managed document media
directory, which the chat player recognizes, rather than the phone's public
Music or Downloads folders. Complete files left in Telegram's general cache
by older versions of this action are moved into that media directory safely.
Downloads continue after
the dialog or chat closes. The download job limits itself to two concurrent
tracks and retries server rate-limit failures after a cooldown. Its completed
count reflects complete files at the path the chat player uses,
including songs already downloaded by another chat action. Partial transfer
files do not count. Failed tracks can be retried without fetching completed
ones again. Completed tracks are checked again when a job is viewed, so removed
files become cache-only entries or retryable failures. A download that stops
without a callback is retried after its loading operation disappears. The
progress row and job state last only until the app process ends. Starting the
action again after an app restart searches again and reuses complete cached files.

While a job exists for the open chat or topic, a progress row sits in the
chat's top panel, below the pinned message. It shows the job's state
(searching, downloading, paused, rate-limited with a countdown, or finished),
how many songs are downloaded out of those selected, and the active and failed
counts. Its buttons pause or resume the job (pausing only stops new songs from
starting), retry failed songs or a failed search, and dismiss a finished job.
A song whose message carries no downloadable file counts as failed but cannot
be retried: Retry appears only while a failed search or at least one failed
download can be tried again, and leaves such songs failed.
The row reappears when the chat is reopened, for as long as the app process
keeps the job. Files found only in the older general cache count as incomplete
until moved to the path the chat player uses.

Tap the progress row to open **Pinned song files**, a page listing every
selected song with its title (or filename), artist, and either its duration
once downloaded or its transfer state, progress and failure reason. The search
button filters by title, artist and original file name; clearing or closing
the search shows every song again, and live download updates keep the query
and scroll position. Tapping a song closes the page and scrolls the chat to
that song's message, in the old group's history for a migrated group; it
never starts playback. The round button plays or pauses a downloaded song
through Telegram's normal player, and the next and previous controls then move
through the downloaded songs the page showed when playback started, in the
chat's message order rather than the page's order. Songs that are queued,
downloading, failed or only in the older cache show a progress ring instead
and cannot be played. Each retryable failed song has its own Retry button; the
row's Retry button still retries all failed songs.

Selection uses Telegram's music classification: non-voice audio document
attributes and its MIME fallbacks for FLAC, OGG, and Opus. Generic audio
documents without those attributes can be excluded even when named MP3 or M4A.

### Keep Media per chat

The chat menu shows how long this chat's cached media is kept, for example
**Keep Media: 1 week (default)** or **Keep Media: Forever (this chat)**. Tapping
it offers the same choices as Settings, and sets or deletes an exception for
this chat alone; the setting for all chats of its type is never changed from
here. Cache cleanup applies the per-chat choice to files whose download
recorded this chat as their owner. Older or shared files without that record
follow general retention instead. The cache size limit can remove files with
finite retention earlier; an explicit Forever exception protects files
attributed to that chat from that limit. Cleanup classifies supergroups as
channels. A supergroup follows the Channels setting, and its exception is
stored in the Channels list even though Settings lists supergroups under
Groups. The entry is also offered inside forum topics, where it controls the
whole chat, including all its topics, since Keep Media has no per-topic
setting. It is not offered in secret chats, channel comment threads, scheduled
messages, or other special chat modes.

### Storage used by a chat

**Storage used by this chat**, next to Keep Media in the chat menu, opens
Telegram's Storage Usage screen and, once its scan finishes, the existing
per-chat sheet with this chat's files by type and its cleanup controls. In a
group migrated to a supergroup, the sheet also includes files attributed to
the old group, and clearing them updates both chats' rows on the Storage Usage
screen. Only files whose download recorded one of these chats as their owner
are counted, so older files and files shared with other chats may be missing.
When no files are attributed to the chat, a message says so instead of showing
an empty sheet. The screen behind the sheet still shows storage for all chats.
The entry appears in the same chats as Keep Media.

### Building

Compile on any machine where the setup below is complete. That can be the
Apple-silicon laptop, once its checkout has `local.properties`, the native
submodules and the sparse patterns described below, or the shared Linux build
box as an optional compile worker, working in an isolated checkout so it never
disturbs another checkout's work in progress. Acceptance testing runs on the
laptop's emulator (see **Testing on an emulator**), and release signing stays
on the Mac: the release keystore never goes to a build worker.

Install Android SDK 36 with build-tools 36.0.0, Android NDK 27.2.12479018, a
compatible JDK/Gradle environment, and Qt 6 for Android arm64 before building.
Keep enough free disk space for the SDK, NDK, Qt, Gradle caches, native
intermediates, and APK outputs.

Create a `local.properties` at the repository root — it is gitignored and must
never be committed:

```properties
sdk.dir=/path/to/Android/sdk
TELEGRAM_APP_ID=1234567
TELEGRAM_APP_HASH=0123456789abcdef0123456789abcdef
PURPLE_QT_ANDROID=/path/to/qt/6.7.3/android_arm64_v8a
```

`PURPLE_QT_ANDROID` points at a Qt 6 for Android (arm64) prefix: the Work Mode
core is the same C++ the desktop fork uses (the `purple-core` submodule under
`TMessagesProj/jni/purple`) and links Qt Core, which ships inside the APK. The
official binaries come without an account through
[aqtinstall](https://github.com/miurahr/aqtinstall):
`aqt install-qt mac android 6.7.3 android_arm64_v8a -O /path/to/qt` on macOS.
On an optional Linux worker, use the `all_os` host instead, because the `linux`
host lists no Android archive for 6.7.3:
`aqt install-qt all_os android 6.7.3 android_arm64_v8a -O /path/to/qt`.

Get the `api_id` / `api_hash` pair from https://my.telegram.org/apps. They are
injected into `BuildConfig` at build time, so no credential is ever hardcoded in
the source. A standalone build without them fails immediately with a message
saying so.

The native tree, jlatexmath and purple-core are git submodules, so after
cloning run `git submodule update --init --depth 1` (a plain shallow clone
leaves them empty and Gradle fails resolving `:jlatexmath`). If this checkout
uses sparse checkout, tracked gitlink directories outside the sparse patterns
may be absent even after that command. Add every native submodule required by
the build to the sparse set first, then initialize them. Add the parent paths
rather than maintaining a partial list of their gitlinks:

```bash
git sparse-checkout add \
  TMessagesProj/jni \
  TMessagesProj/lib/jlatexmath
git submodule update --init --depth 1
```

This includes the standalone build's current native dependencies, such as
ffmpeg, tlottie, xiph, libyuv, and Purple core, without copying generated or
vendored files into the checkout.

Then build the standalone flavor:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

The output APK is **unsigned** and arm64-v8a only — sign it yourself with
`apksigner` before installing.

### Purple version

Purple has a version of its own, separate from Telegram's. The bottom of
Settings shows it on a line under Telegram's version, and the bottom of
Settings → Purple shows it again, in the form
`Purple 1.0.0 (10b3b45a7, core 337829e)`: the Purple version, the short commit
of this repository the APK was built from, and the short commit of the
purple-core submodule (`TMessagesProj/jni/purple`) it compiled. A tap or a
long press on the Purple line copies it, in Settings and on the Purple screen.
Telegram's version line above it keeps its own text and behaviour.

The version is `PurpleVersion.VERSION` in `org.telegram.messenger.purple`,
starting at 1.0.0. It is bumped when a build is delivered: the minor number
when the delivery adds a feature, the patch number when it only fixes things.

The two commits are read when Gradle configures the build, by
`purple/version.gradle`, which `purple/gradle/library.gradle` applies to
`TMessagesProj`, and they reach the app as `BuildConfig.PURPLE_GIT_HASH` and
`BuildConfig.PURPLE_CORE_HASH`.
The app commit gets `+dirty` when a tracked file of this repository differs
from that commit, staged or not, so a build made from a commit with a patch
applied on top says so. Changes inside submodules do not count, since the
build worker's submodule checkouts are known to differ from their commits, and
untracked files do not count either, so a patch that only adds new files
still shows a clean commit. When git is missing or fails, or the directory is
not the top of a checkout of its own (an uninitialized submodule, or a source
tree copied into some other repository), that commit shows as `unknown` and
the build goes on. `TMessagesProj/jni/purple_jni/tests/run_build_version.sh`
runs the script under Gradle against scratch repositories for each of these
cases. It needs a Gradle 7.5 or later launcher (`GRADLE`, else `gradle` on
PATH, else the newest wrapper distribution under `~/.gradle`) and JDK 21
(`GRADLE_JAVA_HOME` overrides it).

### Passcode keyboard layout

When unlocking with a passcode, Purple checks the text exactly as entered
first. If that fails, it checks one whole candidate converted from Persian
keyboard positions to their English equivalents by the shared `purple-core`
mapper. Latin characters and other unmapped characters stay as entered. The
two checks count as one unlock attempt. A passcode intentionally saved with
Persian characters still unlocks exactly, and setting or changing a passcode
stores the characters entered without conversion. The fallback also works for
older MD5 passcodes; after a successful unlock, migration to salted SHA-256
hashes the candidate that matched.

### Work Mode

A preset in `settings.toml` decides which chats are in the chat list and which
may interrupt you. Pick one from the chat list's overflow menu, which is
labelled with the running preset, or from Settings; both open the same box,
which also shows whatever the file got wrong. The rules, the keys and the reasoning are the desktop fork's
`docs/purple/work_mode.md`, and the same file means the same thing here: both
apps compile the same core, so a `settings.toml` moved across through Saved
Messages behaves identically.

**Peek** is the temporary look past the running preset, and it sits in the same
box, under the preset it suspends. `[peek] tap_mobile` is how long a tap on it
lasts here, and **five minutes** when the file does not write that key - not
`tap` and not `auto_off`, which is the one place these keys break their own
pattern. A phone has no `settings.toml` of its own: it reads one written at a
keyboard and carried over through Saved Messages, so falling back would mean
every phone tapping for a length nobody chose for it, and a tap on the list in
your hand is a longer look than a shortcut fired mid-sentence. Write
`tap_mobile = "off"` for a peek that runs until you turn it off. `tap`,
`auto_off`, `hotkey` and `hotkey_length` are read and ignored here: the first
two are what a keyboard taps for, and there is no key to bind on a phone.

Tapping the row starts a peek of `tap_mobile`, and **tapping it again ends it**,
the way the desktop's checkbox does: a checkbox that will not uncheck is broken
however good the reason. A **long press extends** the running peek by another
`tap_mobile`, measured from the deadline it already has rather than from now, so
two long presses buy two lengths; while a peek is running a grey line under the
row, the dial and the chips says `Long press to extend`, since that is the one
gesture here nothing about the control's own shape suggests. Extending is the
phone's share of what the desktop gives its hotkey, which has no key to be bound
to here. It stops at an hour - past that it is not a peek any more, it is the
preset off, and there is a plainer way to say that. A long press that finds the
cap already spent ends the peek instead and says so, and so does a long press on
a peek with no clock on it, which has no deadline for an extension to move.

Under the row is a row of **chips** - one minute to an hour, and `until I stop`
one position past the end of them. Tapping one starts a peek of that length, or
restarts a running one at it rather than adding to it: a chip that says `5 min`
and leaves eleven is a chip lying about what it did. The lit chip follows what
is *left*, not what was asked for, so a five-minute peek with ninety seconds on
it lights `2 min` - nothing anywhere remembers the length a peek was started
with, since `state.toml` holds a deadline and that is all. The lengths and the
rounding that picks the lit one are both the core's, so the phone's chips and
the desktop's row cannot drift apart.

Above the chips the same stops are a **dial**: a ring with a tick per length,
the selection drawn as a filled arc from the first stop round to it, and the
length - or the countdown, while a peek is running - written in the middle. It
does not take a touch just because a finger landed on it. A press on the ring is
watched rather than claimed, and the gesture becomes the dial's only on a
**press-and-hold** or on movement **along** the ring, so a mis-aimed swipe still
scrolls the sheet instead of starting a peek. Once it has the gesture there is a
haptic tick per stop crossed, the peek starts on the release, and releasing away
from the ring abandons it and leaves the peek as it was. A plain tap on a tick
starts that length outright, the way a chip does. It snaps to the same stops,
`until I stop` included, one position past the last; it never reads a continuous
angle, and between two stops it takes the nearer one with a tie going to the
shorter, because a control that quietly rounds a peek up reveals more than was
asked for. The chips stay: a row of words is the discoverable path and the dial
is the quick one once you know it is there.

Under Normal the whole thing - row, dial and chips - is dimmed and inert,
beneath the line that says why: nothing is hidden there to peek at. The grey
line about the long press is not drawn there, or at any other time no peek is
running, because there would be nothing for it to lengthen.

A peek ends at its deadline or when you end it, and on a phone that is very
nearly all there is to it. The device's **screen lock never ends a peek** and
there is no key to make it: a phone's screen locks all day by itself - a
timeout, a pocket, a glance away - and a peek that could not survive that would
be no use on the one device where the screen is always going off.
`[peek] end_on_app_lock_mobile_p` is the single exception and it is **off**
unless the file turns it on; with it on, Telegram's own passcode lock ends a
running peek. Off by default because that lock is usually on a short timer too,
so on by default would end a peek every few minutes for most people - somebody
who locks the app by hand can switch it on.

The desktop has both `end_on_screen_lock_p` and `end_on_app_lock_p` and turns
them **on** by default. That is one rule applied to two machines rather than an
inconsistency to be tidied away: a desktop locks because somebody got up, and a
machine nobody is sitting at should not be left showing what the preset hides,
while a phone's locks say nothing at all about where its owner is. Nothing
anywhere tries to tell a lock you performed from one a timer fired - that would
be a guess, made twice, on two platforms, and wrong quietly. The keys say it out
loud instead.

A chat can be filed into a list from the chat list itself: select it, then
**Work Mode lists** in the overflow. Every list is offered, ticked where the
chat is already a member, and a tap adds or removes it. The write goes through
the same splice the desktop uses, so your comments, ordering and blank lines
survive, and the line carries the chat's name as a trailing comment. This
exists because the ids in `members` are not shown anywhere in Telegram's UI, so
building a list otherwise means reading a number off a profile and typing it in
by hand. The box stays open across a tick, since filing a chat is usually more
than one decision, and its rows and verdict line are re-read from the file
after each one. A **New list…** row at the bottom makes one from a name
and puts the chat straight into it, so the first member of a list no longer has
to be typed into the file by hand; the core refuses an empty name, one starting
with `*` and one already taken, and says which. The app's own writes to `settings.toml` do not come back
through the file watcher as a second reload: the watcher compares the file
against the bytes the app is already running on, the way the desktop does.

The settings and state files live in the app's private storage, at
`/data/data/org.purple.telegram/files/purple/`. `settings.toml` is yours,
imported from Saved Messages. `state.toml` is the app's: the active preset,
and the last resolution that worked, so a `settings.toml` that stops
describing the running preset leaves the chat list exactly as it was rather
than falling back to defaults. Defaulting would unhide every chat you had
hidden, which is the one outcome Work Mode must never produce by accident.

Two mechanisms cover the two ways the lists behind the running preset can
disappear:

- **The file is missing or does not parse.** `settings.toml.good` is a copy of
  the last `settings.toml` the app accepted, and the app runs from it instead.
  Presets and lists both come from the copy, and the preset picker says it is
  running from it.
- **The file parses but no longer defines the running preset or its lists.**
  An import from Saved Messages, a sync Apply, a History restore or a hand
  edit can each do this. The new file is valid, so it replaces
  `settings.toml.good` as well, and the preset keeps running on the cached
  resolution in `state.toml`. That cache also keeps a copy of every list the
  running preset and its views name (title, members and kinds, as
  `[[resolved_cache.list_defs]]`). A list the file still defines, or defines
  again, answers with its live members; the copy answers only for a list the
  file dropped. Without the copy, the cached order would name lists nothing
  can look up, and every chat would be hidden and silenced. The preset picker
  names the lists running from the copy, and choosing Normal, or a preset the
  new file defines, ends it. A file with no presets at all still opens the
  picker while the dropped preset runs, with Normal as its only row.

The copy is rewritten on every reload, so a chat added to a list from the
chat list reaches it. It makes `state.toml` grow with the named lists, about
16 bytes a member, so the app reads a `state.toml` of up to twice the 4 MiB
cap on `settings.toml`: a state file refused for its size would read as
Normal. A `state.toml` written by an older build restores names only, as
before, and gains the copy at the next reload that can still see the lists.

In the same directory, `settings.toml.bak` keeps the file from
before the latest whole-file write, including an editor save, while
`settings.toml.import.bak` keeps the file from before the latest import that
replaced an existing settings file. Each backup has one slot: another import
replaces the import backup when a current file exists, but local saves leave it
alone. An import writes that required import backup before the ordinary one,
and stops without replacing `settings.toml` or touching `settings.toml.bak` if
it cannot. If the ordinary backup then fails, the import stops as well, with
the import backup already holding the current file.
`TMessagesProj/jni/purple_jni/tests/run_settings_backups.sh` tests this order.
`screentime.log` appears there only once `[screen_time]` is switched on - see
**Screen time** below.

A preset only ever *adds* a mute: a chat you muted by hand stays muted whichever
entry claims it, and switching presets never un-silences anything. So every
Mute/Unmute control still acts on your own mute, while the bell and the grey
unread counter show the effective one. A chat no entry claims is hidden *and*
silenced, because a chat you are not looking at has no business interrupting
you. While a Work Mode peek is active, the preset's added silencing is
suspended, so notification status follows Telegram's own per-dialog, topic and
global settings; it resumes when the peek ends. Starting or ending a peek also
refreshes the launcher badge for each active account, so its count follows the
chats currently shown and counted by the preset.

A hidden chat does not notify and does not light the app icon: the preset is
consulted by the same gate a mute goes through, so the message is never
collected into a notification at all. Nor does it put a number on the in-app
"All chats" tab, which used to disagree with the list right underneath it - a
badge of 1 over a list with nothing unread in it. That tab is the preset's own
view, so its counter is now summed only from the chats the preset is showing. A
folder tab still counts every chat it holds, because what a folder tab draws is
deliberately unfiltered, so there its badge and its rows already agree.

What the counter dropped goes to logcat, meant to be read next to the line the
chat list logs about its rows:

    Purple: 12 of 40 dialogs hidden, 0 unread-gated (0 showing), 12 silenced.
    Purple: All chats counter 3 of 5, 2 unread chats hidden by the preset left out.

Both are throttled to one a second. The second appears only while a preset is
actually costing the badge something, so an account with nothing hidden never
sees it - and the two lines together are how you check the fix on a phone,
without a debugger.

Between those rebuilds the total is not recounted but nudged: a message arriving
adds one, a chat being read takes one off. That arithmetic has two halves
separated in time, and a preset can change its mind about a chat in between - a
peek reveals one that was hidden when its message landed, a `show_mode` watching
unread hides one the moment you read it - so a badge kept in step that way drifts
in both directions and does not come back. While a preset is filtering, a nudge
that involves a chat it is not showing is therefore not applied to "All chats" at
all; the tab keeps the number its last rebuild gave it and one is scheduled, a
second and a half later, so that a burst of messages costs one rebuild rather
than one each. Every other tab is nudged as before, since those count what they
draw. This too says so in logcat:

    Purple: All chats delta held back, 2 unread chats the preset hides were in it; recount in 1500ms.

A preset also picks which folder tabs are on the strip, in the order it names
them. `"*ALL"` is every folder it does not name elsewhere, expanded where you
wrote it, and a preset that says nothing about folders shows no tabs at all -
`"*ALL"` is how you ask for them back. "All chats" always leads: it is already
the preset's own view, because the hiding happens in the list itself. A folder
named slightly wrong is skipped and logged rather than guessed at.

Reordering folders is refused while the strip is restricted - the drag, the
Reorder menu entry and the order upload all - because Telegram replaces the
whole server-side folder order with exactly the list it is handed, so dragging
a restricted strip would drop every hidden folder from the account rather than
from the view. A preset whose whole selection is `"*ALL"` leaves dragging alone.
The same reasoning refuses pin dragging inside a filtered chat list.

A folder can silence its chats, with `notify_p = false`. A list can already do
that for a hand-picked set of ids, and for those the list is the simpler tool;
what a list cannot do is track a folder defined by a *rule* - "all groups",
"non-contacts", "everything except these three" - whose membership moves on its
own as chats arrive. The rule a preset only ever *adds* a mute holds here too,
so a chat you muted by hand stays muted whichever folder it is in, and a folder
that silences a chat does not take it out of an "exclude muted" folder:
membership is decided as though the preset silenced nothing.

A folder can also be left out of the counts, with `badge_p = false`: no number
on its own tab, and its chats left out of the launcher badge. It is a third
axis, independent of the other two - a folder can be silenced without being
uncounted and uncounted without being silenced. What it deliberately does not
touch is the "All chats" counter, because that tab counts what is on screen in
front of you, which is a different question from whether the icon should light
up.

A folder can also pull its chats *into* the preset's view with
`include_in_main_view`, whatever the lists decided - `"all"` for everything in
it, `"pinned"` for the chats pinned inside that folder. A folder that names no
`show_mode` leaves them to the default for what each one is: it chose which
chats come in, and the kind still decides when they show. Two things it does
not do. A chat pulled in that no list claims arrives visible but silenced,
because the notify half still comes from the list - `notify_p` on the folder is
the separate lever.

Whatever a folder lets in comes in **even when the chat is archived**. Archiving
is how visibility gets controlled in stock Telegram; under a preset the preset
controls it, by name, so a folder that asked for its chats gets them wherever
they are filed - otherwise you would have to unarchive things to make a preset
work, which is editing the account to change a view. The chats stay archived:
they are still in the Archive, and a pin they carry there stays a pin there
rather than jumping them to the top of the main list.

**Settings → Purple** is the fork's own screen: Work Mode with the running
preset as its subtitle, the schedule, the Local Premium switch, the
hide-from-suggestions switch, this device's name and id, and a section for the
settings file - an editor,
Sync across devices (below), Send to Saved Messages (the desktop's shape and
caption, so either client can import it), a check of Saved Messages for a newer
file, Import from a file, Share the file, and the path with a tap to copy.
Its last line is the Purple version (see **Purple version** above).
Import from a file reads the picked file through the picker's grant whenever
the app may not open its path itself, such as a file another app saved in
Download and picked through the device's storage root. It reads the bytes
straight into memory, so no copy of the file is written anywhere, even when
the read fails partway.
Every switch on it is written into `settings.toml` through the same splice the
desktop uses and read back from it, so a refused write leaves the switch where
the file is.

**Sync across devices** is manual settings sync for the account the settings
screen belongs to. It names that account at the top; to sync another account,
switch to it with Telegram's own account switcher first. **Check Saved
Messages** reads every sync record in that account's Saved Messages and ends
in one status line and at most one action, decided by the same shared core as
the desktop's Sync across devices box: **Publish settings** when the account
has no settings records yet, **Join sync** when another device already has
exactly these settings, **Review update** when a newer version from another
device is ready, **Choose settings** when devices disagree or their histories
are unrelated, **Publish changes** after this device's settings changed, and
**Finish sending** when an earlier post from this device may not have
finished. A device that already syncs records a matching version silently.
Nothing changes on the phone without a review that names the source device
and its time, summarizes the change by TOML table and, on request, shows the
line diff (up to 400 lines). Nothing is sent without a check taken on that tap
and a confirmation that repeats the cloud disclosure: the file may contain
chat IDs and names, and Saved Messages is a Telegram cloud chat that every
signed-in session can read. Before sync replaces `settings.toml`, the old file
goes to **History**. An update that wrote the file offers Undo, both in the
notice that says so and as an **Undo last update** row, after a confirmation;
when a share follows the update, only the row offers it, once the share ends.
History restores any kept copy after a confirmation. Undo and restore stay on
this device until you publish them, even with **Send to Saved Messages after
every save** on. While the app runs from
`settings.toml.good` because `settings.toml` is missing or does not load, a
check says so, and sync changes nothing until you fix the file or restore a
version from History. The check judges this from the file it has just read as
well as from the app's state, so a check made in the moment between a bad save
and the app's reload refuses too. The one exception is a post this device had
already started: the check still offers Finish sending for it, as it would
with a working file. Leaving the screen cancels a running check; a post
already handed to Telegram stays in Telegram's queue, and a later check offers
Finish sending, which confirms that post rather than sending it again. The
older Send, Check and Import rows and the auto-send switch below work as
before.

The shared core now includes config version and remote-head classification,
strict JSON canonicalization, validated uncompressed sync envelopes, and
config payload inspection that checks ancestry, fingerprints, and TOML schema.
The shared core also derives sync status, attention tier, and recovery action
from engine facts; Android does not yet feed it runtime facts or show account
sync status.
The bridge now returns validated space, stream, writer, sequence, and time
for future-stream or unsupported-encoding records without treating their
payloads as usable config. Such records remain non-valid inspection outcomes.
Android exposes config record inspection and construction, local-state parsing
and sequence reservation, own-record clone checks, and version-aware config
confirmation through a raw-byte JNI bridge. Confirmation requires canonical
staged bytes matching the pending state and a validated own server read-back;
the read-back bytes must equal the staged canonical record exactly. It advances
the config base and lineage only for that record. A successful present
confirmation takes a positive server message ID and records it in the same
returned state. Clone verdicts remain available when a read-back differs.
The bridge returns a new state but does not persist it or call
the account transport. `initializeBoundLocalState` creates validated canonical
state bytes from install, device and space identifiers and binds them to the
currently active Telegram account. Java generates 16 secure random bytes for
a new binding and stores the returned token synchronously in that account's
private preferences under a key derived from its positive user ID. If that
user already has a valid token, initialization reuses it so a crash after the
preference write can be retried.
It refuses a malformed or differing stored token and discards the returned
state if the account changes or the token cannot be stored. The user ID is not
written into the state. `checkAccountBinding` reports the core's named binding
verdict for the active account and state, including missing and malformed
preference tokens. The older unbound `initializeLocalState` remains only for
legacy bridge tests.
`reserveConfigRecord` requires the current account number, reads its binding
token, and verifies that the same positive user remains active before and after
the native call. Native code validates that token along with the canonical own
config record and current state, reserves its sequence and payload hash, stores
its version key as pending in the returned state bytes, and appends the exact
canonical record hash to the issued log in that same returned state. The
platform stages the exact record and durably persists the returned state before
uploading. `appendIssuedConfigRecord` is
also exposed for an already reserved sequence and its canonical staged record.
The older account-bound `reserveConfigSeq` helper only reserves a counter and
hash; it does not set the pending version key. It uses the same preference
token and active-account checks. None of these bridge calls writes files or
sends messages.
`recordConfirmedOwnConfigMessage` also stores validated duplicate posts and
in-place edits of an own server record in the returned state.
`adoptIssuedOwnConfigMessage` adds a late duplicate post after confirmation
only when its fresh canonical read-back exactly matches an issued record. It
accepts records from an earlier space after a space move. The caller persists
the returned ledger state; adoption does not delete Telegram messages.
`checkOwnConfigMessageDeletion`
requires a fresh canonical server read-back for that same ID and permits
deletion only after a newer sequence is confirmed. Duplicate posts at the
current confirmed sequence stay protected. `removeAbsentOwnConfigMessage`
removes an older ledger entry only when the caller supplies
`PRESENCE_ABSENT` after an authoritative absence check. These calls return
state bytes for the platform to persist; none performs Telegram I/O.
`PurpleAccountSyncCore.buildConfigAcknowledgement` takes
an inspected remote record's canonical bytes and the exact local settings bytes.
JNI validates the remote envelope, config payload, space, and text again, then
publishes the same version key, parents, and lineage under this install's writer
metadata and sequence. It does not send or persist the record. Run
`TMessagesProj/jni/purple_jni/tests/run_account_sync_acknowledgement.sh` on
macOS to exercise that JNI path with Qt Core and a host JVM. The platform must
stage a built record before persisting
its reserved state and uploading it. The bridge formats install and space IDs
from exactly 16 caller-supplied bytes; platform code supplies randomness.
Shared core now also constructs time-ordered space IDs from a server-time
estimate and 10 secure random bytes, and compares valid IDs by decoded bytes.
Android exposes these through `PurpleAccountSyncCore.formatTimeOrderedSpaceId`
and `compareSpaceIds`. The formatter takes an explicit Telegram server-time
estimate in milliseconds, draws 10 bytes with Java `SecureRandom`, and
rejects zero or values outside the core's 48-bit range. It never substitutes
the device clock. The comparison result reports -1, 0 or 1 in `comparison`;
invalid IDs return an invalid result. Joining sync from the sync screen
calls the formatter when it starts a new space; nothing calls the comparison
yet. The core also validates device-local sync state and checks for
cloned or rewound installs. Its pure directory resolver groups candidates by
space, stream and install, and marks duplicate or ambiguous heads without
Telegram I/O. The pure publish planner selects the next action only after
account binding, complete discovery, and own-record reconciliation.
`PurpleSyncCore` reaches the core's whole settings sync flow through
a stateless JNI bridge: history page classification, review with its wording
and choices, diff, apply planning and completion, the config data commit
check, and publish planning with the send-queue check. `PurpleSyncInventory`
carries one check's candidates to it, and `PurpleSyncTransport` defines the
Telegram side (scan, read, post, cancel) and its main-thread callback
contract. `PurpleSyncTelegramTransport` implements it:
it scans Saved Messages with the core's candidate rule, reads every
candidate again, downloads records only into Telegram's private cache
(Telegram's own auto-download skips any document named
`Purple settings sync.json` or `Purple playlists sync.json`, so opening Saved
Messages does not copy records into Telegram Files; a tap still downloads one),
reports whether an unsent or failed record post still sits in Saved
Messages, and posts a record as `Purple settings sync.json` with
`#purplesync` and `application/json`, then reads that message back. It is
described in `docs/account-sync-transport.md` and tested by
`TMessagesProj/jni/purple_jni/tests/run_sync_transport.sh`. The bridge and
the contract are described in
`docs/account-sync-store.md`, and
`TMessagesProj/jni/purple_jni/tests/run_config_sync_bridge.sh` holds the
bridge to the core's flow tests on macOS. `PurpleAccountSyncStore` keeps bound state and canonical pending config
records under the app-private `purple/sync` directory, with exclusive ownership
and fail-closed crash recovery. It also commits the config data an apply
adopts, after the core's commit check, and tells an unjoined check whether any
sync state exists without creating the directory. Its exact operations and
recovery verdicts are documented in `docs/account-sync-store.md`. Compressed envelopes remain
future work.

Beside that store sits `PurpleSyncHistory`, which keeps copies of
`settings.toml` in the app-private `purple/sync-history` directory before a
sync update, choice, restore or undo replaces the file. It follows the
desktop's rules: each entry is the exact bytes plus JSON metadata, named by
creation milliseconds and a random suffix; an entry is valid only while both
files are owner-only regular files and the bytes still match the recorded
size and the core's settings fingerprint; at most 30 are kept, and the entry
a restore names is always among them. The fingerprint and the config version key
check come from the core through the settings sync flow bridge.
`PurpleSyncSettingsFile` reads `settings.toml` the way sync must: present only
as a regular file of at most 256 KiB, never through a symlink, and invalid
rather than absent when it breaks either rule. Each read also says whether the
app runs from `settings.toml.good`: when the gate says so, and when the file
just read is missing or does not parse while `settings.toml.good` does, so a
check made before the gate's reload has noticed a bad save refuses to sync
too. It writes through the editor's own replacement, so the usual backup and
reload happen, then reads the file back. An apply is stored as an import,
which the auto-send never posts back; a restore or undo skips the auto-send
entirely, as on the desktop.

`PurpleSyncRunner` drives manual settings sync for one account
over any `PurpleSyncTransport`. It runs one step at a time, drops callbacks
from a cancelled or superseded step, and checks before every write that the
account still has the user it started with. `PurpleSyncApply` joins, applies,
adopts, restores and undoes with the desktop's rules, writing History first,
then `settings.toml` on the main thread only if the file still holds the bytes
the user reviewed and the app has not fallen back to `settings.toml.good`
since, then the account's sync state. `PurpleSyncPublisher` posts only when
the user asks, confirms a record Saved Messages already holds instead of
posting it again, and does not post while an earlier sync post is still in
Telegram's send queue. The threading, write order and Undo rules are in
`docs/account-sync-store.md`, and
`TMessagesProj/jni/purple_jni/tests/run_sync_executors.sh` tests all three on
macOS against the real core and a fake transport. The Sync across devices
screen (`PurpleSyncActivity`, with `PurpleSyncReviewDialog` and
`PurpleSyncHistoryActivity`) holds one runner and one Telegram transport per
visit and closes both when it closes. Which sentence it shows comes from the
core; the wording is in `strings.xml`, through `PurpleSyncText`. The screen is
described in `docs/account-sync-store.md`.

**Send to Saved Messages after every save** on the same screen is `[sync]
send_after_save_p`, off until you turn it on because sending is a message in a
real chat: with it on, every change the app makes to `settings.toml`, other
than a sync restore or undo, posts the file five seconds after the last one, so
a run of checkbox taps is one document rather than six. A file that arrived
from Saved Messages is never sent back.
It posts only while the first account is the selected one. The first account
is the signed-in account in the lowest of the app's four account slots: on a
phone where no account has signed out it is the account that signed in first,
which the account switcher also lists first. The two can differ after an
account signs out and another signs in, because a new sign-in takes the
highest free slot while the switcher orders accounts by when they signed in.
When the five seconds run out while another account is selected, that save is
not posted, and the log says it was skipped because the selected account is
not the first account; the next save made with the first account selected
posts as usual. This keeps a test account signed in beside the daily one from
receiving the daily settings. The manual Send button is unchanged: it posts
from the account whose settings screen it is on.
`TMessagesProj/jni/purple_jni/tests/run_auto_send.sh` tests the rule.
`state.toml` remembers fingerprints for the last imported file and the last
confirmed send, suppressing repeated sends of the same bytes. The manual Send
button reports when the upload is queued. After server confirmation, the send
fingerprint advances only if those sent bytes still match the local file; this
keeps an older send that finishes last from marking a newer snapshot as sent.
The account's last-offered message ID advances for every confirmed own send.
Failed uploads can therefore be sent again after another save or a manual
tap. Each attempt stages its own `settings.toml`, keeping the filename intact even when
sends overlap. It is staged in the app's own external files directory
(`Android/data/org.purple.telegram/files/purple-sync/<UUID>/`, next to a
`.nomedia` file) rather than in Telegram's cache, because Telegram moves a sent
attachment out of its cache into Telegram Documents, which is shared storage on
Android 10 and lower. Without that directory (external storage not mounted)
the send is refused with the same error as any other staging failure; it never
falls back to the cache. A failed local message can still need that file for
Telegram's Retry action, so failed attempts stay there for up to 30 days. A
confirmed send keeps its staged file too, since Telegram keeps using that path
as the local attachment. Old staging directories, including ones left empty,
are pruned on the next send, both there and in the cache's `purple-sync/`,
where earlier builds staged; that cache directory is removed once it is empty.
Telegram's Retry action also records server confirmation for a failed
Purple settings send. So does Telegram's automatic resend, at the next app
start, of a send the app was closed during. Either one counts only for an
outgoing document in that
account's own Saved Messages whose attachment is a valid `settings.toml` in a
`purple-sync/<UUID>/` staging directory, under the external files directory
or, for a send an earlier build staged, under the cache, without a symlink
inside that directory. `TMessagesProj/jni/purple_jni/tests/run_settings_staging.sh`
tests these path rules. After confirmation, the sent fingerprint advances only
when the current settings file still matches the staged bytes; the offered
message ID advances for every confirmed retry or resend. Another failed
attempt leaves both unchanged. A process exit between server acceptance and
its callback can also leave them unchanged. The Saved Messages check searches up to
100 recent documents. An import tapped from its
offer waits up to five minutes for the download.

The offer to import a newer `settings.toml` from Saved Messages, made once per
message when an account's chat list first appears after the app starts, or
again when you tap the check on the settings screen, looks only at the active
account, the one whose chats are on screen. A file in the Saved Messages of
another signed-in account, a test account for example, is never offered. If
you switch accounts while the search runs, its answer is dropped without being
remembered as offered, so that account is checked again the next time it is
active. The offer names the account by its name and public username, never its
phone number, together with the date the file was sent, and the import
confirmation names both as well, including the confirmation reached from a
`settings.toml` message's menu in Saved Messages. If that account is no longer
the active one when the confirmation would appear or when you confirm it, for
example because the download finished after a switch, nothing is imported and
the app says why. Before an import replaces `settings.toml`, the previous file
is kept as `settings.toml.import.bak`, as described above.
`TMessagesProj/jni/purple_jni/tests/run_sync_offer.sh` tests these rules.

The **editor** is the only way to change a line on a phone without root: a
monospace field parsed as you type, with a status line that says OK, how many
warnings, or the line and column of a syntax error - tap it to go there. Save
refuses a file that does not parse or is over 4 MiB, refuses when the file on
disk moved since it was opened, backs up to `settings.toml.bak` and reloads
once. Warnings are listed after a save; the import path only counts them.

The **schedule screen** is the list of rulesets, with the master switch, the
pause, and a line saying what the schedule is doing now - "now: work until
17:00, then home" inside a window, "now: home until 09:00" outside one, and
"Mon 09:00" rather than a bare time for a window that is not today. Which of
those sentences it is comes from the core, along with every part of it; only
the wording is here, because it lives in `strings.xml` and goes through the
usual translation pipeline. The schedule tick and the focus policy went the
same way: they were written once here in the bridge and once in the desktop
fork, in C++ both times, and are now one tested function each. What
happens between the windows is a key rather than a constant: `[schedule]
outside` names the preset in force whenever no rule covers the moment, and a
window ending is a move to that, which still only undoes a preset the schedule
itself put there. The row shows that key as the file spells it, not what this
device works out from it: a ruleset may override the key, and when one does the
row says which ruleset is winning rather than showing its answer and then
saving over a key nobody here reads.

**Pausing** takes a date. "Pause the schedule" with no date is what it always
was, held off until you lift it; give it a date and the tick lifts it itself at
midnight of that day and catches up on the windows it missed in the same pass.
The pause lives in `state.toml`, so it never touches the file you edit.

A **ruleset** is a named group of rules and the devices it is for -
`any`, a class (`mobile`, `desktop`), a platform (`android`, `ios`, `macos`,
`windows`, `linux`) or one device's id - and what it does about them: disabled,
enabled, or always. Only the most specific *enabled* ruleset that applies to
this device runs, so a ruleset naming this phone replaces the one for mobiles
rather than piling on top of it; every applicable *always* ruleset runs
alongside whichever that is. A ruleset may name its own `outside`. One
settings.toml therefore describes every device you carry it to, and the phone
lists - and edits - the laptop's rulesets as well as its own. Rules written
before rulesets existed still work: they are shown as "Rules" and run
everywhere.

Tapping a ruleset opens its rules, with Mode and Applies to above them. A rule
is edited in a dialog: days, from and to, the preset (Normal first, since a
window has to be able to turn Work Mode off), enabled, delete. Rules have no
name; the app addresses one by its ruleset and its position in that ruleset and
hands the core the window and preset it read, so an outside edit landing under
an open dialog is refused rather than misapplied. Rules and rulesets the parser
refused are listed with its reason.

**This device** on the settings screen shows what this install calls itself -
`android-` and eight hex digits of a hash of the OS's own identifier, so the id
survives clearing the app's data and the raw identifier never lands in a file
you send anywhere. Tap it to give the device a name; the name goes into
`[devices]` in `settings.toml` and travels with the file, so the laptop calls
this one the same thing.

**Follow Do Not Disturb** on the same settings screen is `[focus_sync]`, the
key the desktop reads on macOS: turning any Do Not Disturb mode on puts
`enter_preset` in force, and turning it off goes back to `exit_preset` -
`previous`, the default, meaning whatever was running before. Android needs no
permission to read the interruption filter, so this is a switch and not a
prompt; the switch reflects what the parser did rather than the key, since a
`[focus_sync]` naming no `enter_preset` is turned off for you. A preset chosen
by hand during a focus session stands, and outlives it. If a schedule window
opens or closes while focus is holding the preset, leaving hands the chat list
to the schedule rather than to the preset the session started with - a window
missed for the length of a focus mode was the one case where the two features
disagreed.

Three keys new to the file, shared with the desktop. `[suggestions]
hide_invisible_p` (default true) keeps a chat the preset hides - and no extra
view or shown folder tab reaches - out of the frequent-contact strip, the
recent searches, the quick-share row, the gift and boost pickers and the OS
share sheet's direct targets. The Channels tab of global search follows the
same rule - your own channels are filtered by it, and the "similar channels"
the server suggests are left out of the tab entirely while a preset filters,
since they are the one strip not assembled out of your own chats, until
`[suggestions] recommended_channels_p = true` (default false) asks for them
back. Typed search and the forward picker still find it.

Four more surfaces are covered by the same key, each because it is a list the
app filled for you rather than one you wrote. The share sheet's own **Recent
chats** section keeps a second copy of the recent-search table instead of
reading the search screen's, so it is filtered where it loads that copy; its
top frequent-contacts row already went through the shared one. The **stories
notification exceptions** on both Notifications screens seed themselves with
the top five or six peers, and those seeded rows are the frequent strip under
another name - an exception you made by hand is yours and is left alone. The
**Contacts tab keeps its full address book by default**; its More menu offers
the optional, in-session Last Seen visibility filter described below. The
compose button opens that screen with no frequent row of its own. The **contacts
home-screen widget** is not filtered either: with no chats
picked it falls back to the top four read straight out of SQLite on the storage
thread, before the users and chats are loaded, which is the same place and the
same reason the push-eligibility mirror is left alone.

A preset's `hide_archive_p` (default true) takes the archive out of the way
while it runs: no pull-down, no row, the state of an account that never
archived anything; archiving still works, search and the Archive settings
screen still reach it, and `hide_archive_p = false` on the preset keeps the
pull. Under Normal all three are stock.

`hide_add_story_p` (default true) is the same shape for the **add-a-story
button**, your own row at the head of the stories strip: a preset that has
already named what gets through has no reason to leave a way in standing
there. Say `hide_add_story_p = false` on the preset to keep the button. A peek
puts it back, and under Normal it is untouched.

The **stories strip** above the chat list follows the preset as well, through
`stories` on the preset. `"all"` leaves it alone, `"all_unseen"` keeps whoever
still has something unwatched, `"follow"` - the default - hides whoever the
preset excludes outright and keeps whoever it is merely holding back for being
quiet, `"follow_unseen"` is both, and `"none"` turns the strip off. A
`list_order` entry or a folder overrides that for its own people with
`"always"`, `"unseen"` or `"never"`; a folder beats an entry and both beat the
policy, which is the order hiding already uses. A peek reveals stories along
with the chats they belong to. Your own row is the one none of that reaches:
it is `hide_add_story_p` and nothing else - not the `stories` policy, not a
list, not a folder - because a door to posting is not a chat the preset is
judging, and tying it to whether some list happens to name Saved Messages was
tying it to a chat it has nothing to do with. Hidden by default while a preset
filters, back under Normal and back for a peek, and the strip goes with it when
nothing is left to draw.

Three things follow the filter with it: the collapsed strip, the count in its
title, and the chain a swipe walks onward along, so swiping past the last
person shown cannot land on somebody the preset hid. Two deliberately do not.
The archive's hidden-stories strip is Telegram's own idea of hidden - the
people you moved there yourself - and has nothing to do with a preset. And the
story counters the rest of the app keeps are left alone, because filtering them
would be lying to code that never asked about a work mode.

**Last seen** is `[last_seen]`, and it answers a question the app normally
leaves hanging. Hide your own last seen and Telegram stops showing you other
people's - the server says so, in a `by_me` flag on the coarse status, and this
fork passes that on instead of printing a bare "last seen recently". With
`reasons_p` (default true) and `trade_p` (also default true), the chat header
and the profile append *Last Seen Peek* to a status that is coarse **because of
your own privacy settings**,
collapsing to an eye in a header too narrow for the sentence. Nothing is
appended when the other person hid theirs - that is their setting, and there is
no Last Seen Peek to perform - and nothing at all to "a long time ago": the server
does not say whether that is inactivity or a block, and the fork does not guess.

The words are for the two places with room for them and somewhere to put a
second tap: the chat header and the profile. Everywhere else the fork draws the
eye alone after the status - the contacts tab and the generic user rows, search
results, the group-create and add-participant pickers, the member lists on a
chat's Members screen, and the share and boost selectors. Those rows are
informational on purpose and not for want of trying: a row has exactly one
click and it belongs to the row, opening that person or ticking them, and a row
that sometimes opens a sheet instead would be worse than one that always does
the one thing. So the mark there says the fork has something to add about this
last seen, and the header and the profile are where you act on it. A remembered
read is different: it replaces the phrase underneath in every one of those
places, because what a Last Seen Peek read is a fact about that person, and a
member list still saying "last seen recently" beside a profile saying "last
seen 14:32" would be the fork disagreeing with itself in two windows of the same
app.

The Contacts page's **More** menu has **Visible or peekable last seen**.
It is an in-session filter, never a saved preference, and it applies to the
main list and Contacts search, including server and phone matches. Search keeps
only Telegram users that meet the same rule; phonebook-only contacts and other
non-user results are omitted. It keeps an ordinary exact or online status, a
remembered exact Peek that the status row currently displays, or a coarse
`by_me` status that an enabled Last Seen Peek could reveal. It leaves out
yourself, bots and deleted accounts. Turning `trade_p` off removes only the
coarse Peek candidates; exact and remembered results remain. If no contacts
match, the list explains that the filter is active and hides the New Contact
action; turning the filter off restores the ordinary empty-address-book state.

Some status lines are deliberately left alone. The chat list writes its own
subtitles, and a chat list row is the one row in the app whose whole job is to
be scanned - a mark there would be noise in the place that most needs to stay
quiet. The preview header at the top of that screen shows your *own* last seen,
where there is by definition nothing of yours in the way. The popup
notification, the add-contact screen, the phonebook share sheet and the
limit-reached sheet are each a single-purpose box where the status is context
rather than a subject. `DialogObject`'s helper is left alone for a
different reason: it is a generic formatter with no idea who is asking, so
hooking it would reach every one of those places blind. And `ProfileActivity2`
is unreachable in this build - nothing constructs it - so hooking it would be
hooking dead code.

Tapping the profile's last-seen text or the chat-header mark opens **Last Seen
Peek**. **Peek Last Seen** also appears in the chat menu and in the profile's
**More** menu. The profile text and both menu actions use the central
eligibility check: Last Seen Peeks are enabled and the server has sent a coarse
status with `by_me`. The other person therefore shares their last seen in
principle, but Telegram is withholding it because you do not share yours with
them. The first Last Seen access loads those settings before either decorating
the status or deciding whether its tap and menus are available, so a cold start
cannot leave a visible Peek action with nowhere to go. The chat menu also reads
the current user status when it opens, so a `by_me` update cannot leave it out
of step with the header. The chat-header mark is display-aware: its own
shared-core predicate requires either the explanatory tail or a remembered
read to be visible, then the peek itself rechecks central eligibility before
it starts. Actions stay
absent when the other person hid their own status, when an exact status is
already visible, for bots and deleted accounts, and when Last Seen Peeks are
disabled.

Starting a Last Seen Peek adds only that person to your last-seen allow list,
asks the server for their exact status, and restores your original privacy rules
after a success, a timeout (`trade_hold`, ten seconds by default), or an error.
Before opening the rule, the app synchronously saves the original server rules
in a private journal for that account. A deadline starts as soon as the open
request is sent, so a stalled status reply cannot extend the hold. Restoration
is checked against the server's rules before the journal is cleared. If the
app restarts with a pending journal, it retries restoration when that account
is active; a failed restore remains pending and blocks another Peek on that
account. While the app is force-stopped or offline it cannot send a restore,
so the temporary exception may remain open past the hold until it runs online
again. If the account is logged out and never reactivated on this device, this
client cannot complete the recovery; check Telegram's Last Seen privacy
settings from another active client in that case. A different account taking
the same app slot cannot use Peek while that slot holds the previous account's
pending journal; the app never applies those old rules to the new account.
The setting is labeled **Allow Last Seen Peeks**; its existing schema key remains
`trade_p` and defaults to true. The confirmation checkbox is labeled **Skip
confirmation for all future peeks on this device**. That one saved choice skips
the confirmation for every future peek on this device. Every peek still requires
a tap, still shares with only the selected person, and still ends after the short
hold. **Skip Last Seen Peek confirmation** in Purple settings reflects the same
device choice; turning it off makes the confirmation appear again. This switch
is independent of `trade_p`: it never enables or disables Last Seen Peeks.

What came back is remembered for `trade_remember` (a day) and shown in place of
whatever the status says, as "last seen 14:32 · as of 3 min ago". Another peek
with the same person has to wait out `trade_cooldown` (five minutes), so a tap
cannot become a standing subscription. Every read is listed under **Last Seen
Peeks** on the Purple settings screen. A peek that returned nothing is listed
too, because the seconds of exposure happened either way.

That read survives their status going quiet. If they stop being merely coarse
and become **a long time ago** - they went inactive, or shut you out - the
remembered line stays, for as long as `trade_remember` keeps it: what gates it
is the age of the memory, not the shape of the status underneath, and a read
does not stop being a real moment that was really read because they moved since.
If anything that is when it is worth the most, because it is now the only moment
anybody has. The only thing that takes its place is a fresher one: the server
handing over a real time again. The line is not tappable there, because your
privacy setting is no longer what hides their status, and "a long time ago" is
still never *explained*, since the server is
not withholding a moment and there is nobody to attribute it to.

With `trade_p` enabled, the remembered line is a link too, and that is a repair
rather than a flourish. The tail used to be the only door into the sheet, so the
first peek replaced the door with the read and the next peek was unreachable for
a whole `trade_remember` unless the record happened to expire first. Tapping the
remembered line now opens the same sheet, which says what was last read and
counts the wait down - *You can peek again in 3:12*, recomputed from the clock
every second rather than decremented, so a box left open across a suspend does
not go on counting time that has already been spent. The button beside it is
held disabled until the wait runs out and then becomes **Peek Again** without
the box having to be closed and opened again. The line stops being a link when
their last seen is no longer coarse because of *your* rules: they have changed
their own privacy since, and a Last Seen Peek is no longer eligible.

The other refusals are still toasts, because none of them is a wait: Last Seen
Peeks being disabled, a last seen that is not coarse because of your own rules,
and a peek already running are each a "no" that will not turn into a "yes" while
the box sits there. A remembered exact read is shown whatever `reasons_p` says.
That switch governs the fork *explaining* a status; a read the user asked for out
loud is an answer to their own question, and turning the explanations off should
not hide it. Turning `trade_p` off removes the suffix and every Peek action,
including the remembered line's tap, but keeps that valid remembered result
visible and inert.

The same Last Seen Peek also replaces Telegram's permanent-sharing button in a
message's *seen by* sheet. Its hidden-status row stays clickable for an eligible
Peek target on every account tier and opens the Peek version of the sheet rather
than the read-receipt version. With `trade_p = false` there is no button there;
the sheet explains where to enable Last Seen Peeks and where Telegram's own
Privacy settings live if the user deliberately wants to share with everybody.
This button requires `trade_p`; the explanatory suffix requires both `trade_p`
and `reasons_p`.

**Screen time** is `[screen_time]`, and it is off until `enabled_p = true` says
otherwise - it is a record of what you looked at and for how long, and nothing
should start keeping one of those because a version number moved. With it on,
the app appends raw events to `screentime.log` beside `settings.toml`: a chat
coming to the front and leaving it, the app entering and leaving the
foreground, the screen locking, the preset changing, a peek starting and
ending, and the send actions - a
composer edit, a send, a voice recording started, an attachment picked, a reply
or an edit begun. Time that is not in a chat - the list, search, settings - is
its own bucket, "elsewhere", so the splits add up to foreground time rather
than to something smaller with no name. Nothing is ever sent anywhere: the file
lives in the app's private storage, it is not part of the Saved Messages
exchange, and two devices would double-count nothing useful anyway.

What the log does *not* hold is a single total. Sessions, the active-versus-
reading split and every number on the screen are derived by the shared core at
read time, out of the raw lines and the thresholds in force when you look - so
changing one re-reads the history you already have instead of only shaping what
happens next. `action_span` (3 s) is how long one send action counts as active,
and also the throttle on composer edits, so a burst of typing is one event and
not one per key. `active_gap` (30 s) is how close two actions have to be for
the whole gap between them to count as well, which is what makes a conversation
read as active time rather than a row of three-second spikes. `idle_after`
(60 s) without a touch, scroll or send pauses the session, stamped back to
where the input actually stopped. `retention_days` (90) is how much is kept;
the log is walked for expired lines once a day, and 0 keeps everything.

Settings → Purple → **Screen time** draws it, with last week's line as the
row's own subtitle. A period switcher (Today, Week, Month, or a custom range
from two date pickers), a headline with the total, the active share and the
change against the period before, a bar chart stacked by chat kind - hours for
Today, days otherwise - and the chats ranked with proportional bars and each
one's active share. Tapping a chat opens its own page: the same chart and the
same hour-of-day profile for that chat alone. Chips filter it: active only,
one kind, one preset. Under that, **Reading load** folds every day in the
period onto one clock, which is what a schedule window gets placed by;
**Hidden while peeking** is the time spent in chats the running preset was
hiding; **Peeked** is how often a peek was started in the period and how long
the peeks ran; and a month gets an hour-by-weekday heat map. Export writes a
CSV to the app's cache and hands it to the share sheet.

Those last two are not the same number. Hidden-while-peeking is time spent *in*
the chats the preset hides, and most peeks are a look at the chat list itself -
they reveal the list, nothing on it gets opened, and that use shows up in
**Peeked** and nowhere else. Peek is the way out of the preset, so how much it
is used is how much of the period the preset was not being kept to, which is the
figure worth having. The log gains a line when a peek starts and another when it
ends, and one more whenever a running peek's deadline moves - that last is what
lets a peek that outlived the process be counted as the minutes it really ran
rather than as every hour until the app was next opened.

Every length on that screen is written by the shared core, so both clients
spell one the same way: `1 d 3 h 2 m`, `1 h`, `59 m`, `48 s`, and `0 s` for
nothing at all. The day unit is new here - a chat with twenty-seven hours in it
used to read as `27 h` - and an empty bar now says `0 s` rather than a bare
`0`.

Day boundaries are decided in the device's own local time, read from the C
library, which knows the whole daylight-saving rule - so a range that straddles
a change still has days of the right length. Java measures the offset in force
and the bridge checks its answer against it before trusting it; if the two ever
disagree the offset wins, which is an hour out across a change and right the
rest of the year. Qt's named zones are never asked for: resolving one needs a
JavaVM this library deliberately never installs, and asking crashed the process.

**Budgets** are `[[screen_time.budgets]]`, each with a `target` (`all`,
`chat:<id>`, `kind:<kind>`, `preset:<name>`), a `per_day`, and a `mode`. A
`soft` budget shows a bulletin at the limit, once per chat per day, and does
nothing else. A `hard` one puts a cover over the message list and the composer
naming the budget, with one `snooze` (5 m by default, at most `snoozes_per_day`
= 2; either at 0 disables it and makes the cap absolute). A hard cap never
touches messages or notifications - the chat still receives, still notifies,
is still in the list and still searchable - and the session keeps recording
behind the cover, counted as reading, so time spent sitting on it still shows
in the total. The Screen time screen lists the budgets with what each has spent
today and edits them in place - Add budget, or tap one, for a dialog over the
target, the allowance, the mode and the two snooze numbers - writing through the
core's budget splices, so a block keeps its comments and only the keys you
changed are rewritten.

Work Mode is ported, the launch-time offer of a settings import included. The
contents of a folder tab are deliberately unfiltered: a preset decides its own
view, a folder decides its own tab.

### Push notifications

`[notifications].preview_always` in `settings.toml` lets messages and reactions
from selected chats show their title, sender, content, and available media even
when Purple Telegram's preview settings or passcode screen would otherwise hide
them. Bots and channels are included by default. Set `preview_always = []` to
remove those defaults, or use typed entries such as `"private:123"` and
`"group:456"`. The full syntax is in
`TMessagesProj/jni/purple/docs/notifications.md`. This affects displayed
content only: Work Mode still controls delivery, passcode-locked replies stay
disabled, secret chats and unresolved peers stay hidden, and Android's
notification privacy settings still apply.

There is no FCM push in this fork, and no Firebase project of your own will
bring it back. Telegram's servers deliver pushes through Telegram's own Firebase
project only, so a token from any other project is unusable to them — and the
upstream `google-services.json` cannot be borrowed either: its API key is
locked to the official package names, and Firebase answers this app's
registration with a 403, "Requests from this Android client application
org.purple.telegram are blocked" (seen in logcat on first launch). The app
handles the failure quietly.

What works instead is the same thing that works on phones without Google
services: the background connection. **This fork turns it on by default**,
because without push it is the only way a notification ever arrives here and an
install that inherited upstream's "off" is silently unable to notify. The switch
is still there - Settings → Notifications and Sounds → **Background
Connection** - and turning it off is remembered.

**Keep-Alive Service**, next to it, is on by default too. It is a sticky
service and a relaunch broadcast, not a foreground service, so it costs no
permanent notification; what it buys is the process being asked back after the
OS kills it, which is the only thing standing in for push once the connection
is gone. Turning it off is remembered as well.

The launcher icon is the fork's own purple one from the first install. The
stock icon is still in Settings → Appearance → **App Icon**, as the first tile,
for whoever wants the app to look like Telegram on the home screen. The tint
of a notification's icon follows the choice, so the shade matches the home
screen.

See `docs/purple/defaults.md` in the desktop repository for this and the other
defaults this fork changes.

### Testing on an emulator

Run acceptance tests on the Apple-silicon laptop with an **Android 36 arm64**
Google APIs image and the host GPU. This matches the APK architecture and avoids
the software-renderer failures seen on the shared Linux box:

```bash
sdkmanager "emulator" "system-images;android-36;google_apis;arm64-v8a"
avdmanager create avd -n purple -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6
emulator -avd purple -gpu host -no-audio -no-snapshot &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb install -r PurpleTelegram-signed.apk
adb shell am start -n org.purple.telegram/org.telegram.ui.LaunchActivity
adb exec-out screencap -p > screen.png
```

Nothing is translated - the APK's architecture is the host's - and the emulator
renders through MoltenVK on the Mac's own GPU. That is worth the 4.3 GB image:
the Linux box's software renderer makes popups and long-press menus unreliable,
while the laptop path renders them normally. It costs about 6 GB of RAM while
running, so shut it down with `adb emu kill` afterwards. Sign a disposable test
APK with a throwaway key if desired; preserve the signer between upgrades. The
Google APIs image, unlike a Play Store image, allows `adb root` for controlled
test-state work under `/data/data/org.purple.telegram/`.

Check the AVD's `config.ini`: it must have `hw.gpu.enabled=yes` and
`hw.gpu.mode=host`. A newly created AVD may disable the GPU, silently losing the
reason for running on the laptop.

An x86_64 Google APIs image under KVM on a Linux build host can run the arm64
APK through Android's ARM translation, but this is a non-authoritative fallback
for compile-adjacent diagnostics. On the current `pi` build box, software GPU
rendering crashes under ordinary UI drawing and cannot supply acceptance
evidence. Do final UI verification on the laptop emulator or a suitable real
device.

The desktop fork's repository carries the longer version of this, including how
to use the box as an optional compile worker and keep a legacy diagnostic
emulator session private on a shared machine, in
`docs/remote-build-and-test/readme.md`.

Telegram's test data centres (the "Test Backend" checkbox is compiled out of the
standalone build; flip `TEST_BACKEND_IN_STORE` in `LoginActivity` for a
throwaway build) were rejecting their own documented fixed login codes
(`99966XYYYY` / `XXXXX`) with `PHONE_CODE_INVALID` on every DC when this was
last tried, so plan on a real account for anything past the login screen.

---

## Telegram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 36.

1. Clone the Telegram source code with its submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git Telegram
   ```
   In case you forgot the `--recursive` flag, change to the `Telegram` directory and run:
   ```bash
   git submodule init && git submodule update --init --recursive --depth=1
   ```
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
