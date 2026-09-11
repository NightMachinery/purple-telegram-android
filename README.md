## Purple Telegram

A personal fork of Telegram for Android that adds **Work Mode**. The desktop
counterpart lives at https://github.com/NightMachinery/tdesktop and the shared
logic both clients build on lives at https://github.com/NightMachinery/purple-core.

Purple Telegram installs **alongside** official Telegram: it ships under its own
application id, `org.purple.telegram`, with its own contacts account type and its
own launcher entry, so both apps can be signed in at the same time.

### Building

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
`aqt install-qt linux android 6.7.3 android_arm64_v8a -O /path/to/qt`.

Get the `api_id` / `api_hash` pair from https://my.telegram.org/apps. They are
injected into `BuildConfig` at build time, so no credential is ever hardcoded in
the source. A standalone build without them fails immediately with a message
saying so.

The native tree, jlatexmath and purple-core are git submodules, so after
cloning run `git submodule update --init --depth 1` (a plain shallow clone leaves them
empty and Gradle fails resolving `:jlatexmath`).

Then build the standalone flavor:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

The output APK is **unsigned** and arm64-v8a only — sign it yourself with
`apksigner` before installing.

### Work Mode

A preset in `settings.toml` decides which chats are in the chat list and which
may interrupt you. Pick one from the chat list's overflow menu, which is
labelled with the running preset, or from Settings; both open the same box,
which also shows whatever the file got wrong. The rules, the keys and the reasoning are the desktop fork's
`docs/purple/work_mode.md`, and the same file means the same thing here: both
apps compile the same core, so a `settings.toml` moved across through Saved
Messages behaves identically.

**Peek** is the temporary look past the running preset, and it sits in the same
box, under the preset it suspends. `[peek] tap` is how long a tap on it lasts
and `auto_off` is the fallback for a file that does not say - so a phone can
have its own length without touching the key a desktop reads, and a file with
neither key gives a peek that runs until you turn it off, which is also what
`"off"` means written out. `hotkey` and `hotkey_length` are read and ignored:
there is no key to bind on a phone.

Tapping the row starts a peek of `tap`; tapping it again while one is running
*extends* it by another `tap`, measured from the deadline it already has rather
than from now, so two quick taps buy two lengths. It stops at an hour - past
that it is not a peek any more, it is the preset off, and there is a plainer way
to say that. A tap that finds the cap already spent ends the peek instead and
says so, and so does a tap on a peek with no clock on it, which has no deadline
for an extension to move. A **long press ends it** outright. This is where the
phone parts company with the desktop, where the second press of the *checkbox*
ends the peek and extending is the hotkey's job: a phone has no hotkey to give
that to, and a tap while the chats are back is nearly always "not yet" rather
than "done".

Under the row is a row of **chips** - one minute to an hour, and `until I stop`
one position past the end of them. Tapping one starts a peek of that length, or
restarts a running one at it rather than adding to it: a chip that says `5 min`
and leaves eleven is a chip lying about what it did. The lit chip follows what
is *left*, not what was asked for, so a five-minute peek with ninety seconds on
it lights `2 min` - nothing anywhere remembers the length a peek was started
with, since `state.toml` holds a deadline and that is all. The lengths and the
rounding that picks the lit one are both the core's, so the phone's chips and
the desktop's row cannot drift apart. Under Normal the whole thing is dimmed,
beneath the line that says why: nothing is hidden there to peek at.

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

Three files live in the app's private storage, at
`/data/data/org.purple.telegram/files/purple/`. `settings.toml` is yours,
imported from Saved Messages. `state.toml` is the app's: the active preset,
and the last resolution that worked, so a `settings.toml` broken halfway
through an edit leaves the chat list exactly as it was rather than unhiding
everything. `settings.toml.good` is a copy of the last `settings.toml` the app
accepted, used when the real one is missing or unreadable - the resolution in
`state.toml` remembers the order a preset resolved to but not what its lists
contained, so without the copy a vanished `settings.toml` would leave every
chat unclaimed and therefore hidden. The preset picker says when it is running
from the copy. A fourth, `screentime.log`, is there only once `[screen_time]`
is switched on - see **Screen time** below.

A preset only ever *adds* a mute: a chat you muted by hand stays muted whichever
entry claims it, and switching presets never un-silences anything. So every
Mute/Unmute control still acts on your own mute, while the bell and the grey
unread counter show the effective one. A chat no entry claims is hidden *and*
silenced, because a chat you are not looking at has no business interrupting
you.

A hidden chat does not notify and does not light the app icon: the preset is
consulted by the same gate a mute goes through, so the message is never
collected into a notification at all. One rough edge remains - with
Notifications - Badge Counter - "Include muted chats" turned on, the in-app
"All chats" tab counter still includes hidden chats, though the launcher badge
does not.

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
Send to Saved Messages (the desktop's shape and caption, so either client can
import it), a check of Saved Messages for a newer file, Import from a file,
Share the file, and the path with a tap to copy. Every switch on it is written
into `settings.toml` through the same splice the desktop uses and read back
from it, so a refused write leaves the switch where the file is.

**Send to Saved Messages after every save** on the same screen is `[sync]
send_after_save_p`, off until you turn it on because sending is a message in a
real chat: with it on, every change the app makes to `settings.toml` posts the
file five seconds after the last one, so a run of checkbox taps is one document
rather than six. A file that arrived from Saved Messages is never sent back and
the same bytes are never sent twice - `state.toml` remembers the fingerprint of
the last file this device sent and the last one it imported, which is what
keeps two machines that already agree from bouncing the file between them.

The **editor** is the only way to change a line on a phone without root: a
monospace field parsed as you type, with a status line that says OK, how many
warnings, or the line and column of a syntax error - tap it to go there. Save
refuses a file that does not parse or is over 64 KB, refuses when the file on
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
**Contacts tab is deliberately not filtered**, and neither is its search: it is
the full address book, the same list the desktop's contacts box shows unhooked,
and the compose button opens exactly that screen with no frequent row of its
own. The **contacts home-screen widget** is not filtered either: with no chats
picked it falls back to the top four read straight out of SQLite on the storage
thread, before the users and chats are loaded, which is the same place and the
same reason the push-eligibility mirror is left alone.

A preset's `hide_archive_p` (default true) takes the archive out of the way
while it runs: no pull-down, no row, the state of an account that never
archived anything; archiving still works, search and the Archive settings
screen still reach it, and `hide_archive_p = false` on the preset keeps the
pull. Under Normal all three are stock.

The **stories strip** above the chat list follows the preset as well, through
`stories` on the preset. `"all"` leaves it alone, `"all_unseen"` keeps whoever
still has something unwatched, `"follow"` - the default - hides whoever the
preset excludes outright and keeps whoever it is merely holding back for being
quiet, `"follow_unseen"` is both, and `"none"` turns the strip off. A
`list_order` entry or a folder overrides that for its own people with
`"always"`, `"unseen"` or `"never"`; a folder beats an entry and both beat the
policy, which is the order hiding already uses. A peek reveals stories along
with the chats they belong to. Your own row is governed like anybody else's,
since the lists give Saved Messages no exemption either, so under `follow` the
add-a-story button goes away unless a list names you - and the strip goes with
it when nothing is left to draw.

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
`reasons_p` (default true) the chat header and the profile append *share yours
to see* to a status that is coarse **because of your own privacy settings**,
collapsing to an eye in a header too narrow for the sentence. Nothing is
appended when the other person hid theirs - that is their setting, and there is
nothing to offer about it - and nothing at all to "a long time ago": the server
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
read is different: it replaces the coarse phrase in every one of those places,
because what a trade bought is a fact about that person and a member list still
saying "last seen recently" beside a profile saying "last seen 14:32" would be
the fork disagreeing with itself in two windows of the same app.

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

Tapping the mark opens **Show mine to see theirs**, unless `trade_p = false`
turns the offer off and leaves the explanation. The sheet says what it costs
before anything is sent; Share once then adds that one person to your
last-seen allow list, asks the server for their status, and puts your privacy
settings back exactly as they were - after a success, after a timeout
(`trade_hold`, ten seconds by default) and after an error alike. What came back
is remembered for `trade_remember` (a day) and shown in place of the coarse
text as "last seen 14:32 · as of 3 min ago", and a second trade with the same
person has to wait out `trade_cooldown` (five minutes), so a tap cannot become
a standing subscription. Every trade is listed under **Trades** on the Purple
settings screen: who, what was read, how long ago. A trade that came back with
nothing is listed too, because the seconds of exposure happened either way.

The remembered line is a link too, and that is a repair rather than a flourish.
The tail used to be the only door into the sheet, so the first trade replaced
the door with the read and the second trade was unreachable for a whole
`trade_remember` unless the record happened to expire first. Tapping the
remembered line now opens the same sheet, which says what was last read and
counts the wait down - *You can refresh in 3:12*, recomputed from the clock
every second rather than decremented, so a box left open across a suspend does
not go on counting time that has already been spent. The button beside it is
held disabled until the wait runs out and then becomes **Refresh now** without
the box having to be closed and opened again. A first trade's sheet is exactly
the sheet it always was. The line stops being a link when their last seen is no
longer coarse because of *your* rules: they have changed their own privacy
since, and there is nothing left to trade for.

The other refusals are still toasts, because none of them is a wait: the offer
switched off, a last seen that is not coarse because of your own rules, and a
trade already running are each a "no" that will not turn into a "yes" while the
box sits there. And a remembered read is shown whatever `reasons_p` says. That
switch governs the fork *explaining* a status; a read the user asked for out
loud is an answer to their own question, and turning the explanations off
should not hide it. Its tap works for the same reason - a dead tap on a line
you can plainly see would be that switch reaching somewhere it was never about.

The trade also stands where Telegram's own offer stood. The sheet behind a
message's *seen by* line used to open with a button that made your last seen
visible to **everybody**, for good, on one tap; this fork does not carry a
control like that, so that button is the trade, and with `trade_p = false`
there is no button at all - only the note that the offer is off, where the
switch is, and that Telegram's own Privacy settings are still the deliberate
way to show your last seen to everyone. The profile's own button agrees with
the line beside it - both ask the core the one question - so it stands for a
remembered read as well, and it no longer hides itself from a premium account:
upstream's button was a promo for a permanent change, and this one is the
trade.

**Screen time** is `[screen_time]`, and it is off until `enabled_p = true` says
otherwise - it is a record of what you looked at and for how long, and nothing
should start keeping one of those because a version number moved. With it on,
the app appends raw events to `screentime.log` beside `settings.toml`: a chat
coming to the front and leaving it, the app entering and leaving the
foreground, the screen locking, the preset changing, and the send actions - a
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
hiding; and a month gets an hour-by-weekday heat map. Export writes a CSV to
the app's cache and hands it to the share sheet.

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

The APK is arm64-only, but an x86_64 system image with Google APIs (API 30 or
newer) runs it through Android's ARM translation, so a headless emulator on a
Linux build box with `/dev/kvm` is enough for a smoke test:

```bash
sdkmanager "emulator" "system-images;android-35;google_apis;x86_64"
avdmanager create avd -n purple -k "system-images;android-35;google_apis;x86_64" -d pixel_6
emulator -avd purple -no-window -no-audio -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb install -r PurpleTelegram-signed.apk
adb shell am start -n org.purple.telegram/org.telegram.ui.LaunchActivity
adb exec-out screencap -p > screen.png
```

Boot takes under a minute with KVM. Sign the APK with a throwaway key for this;
the emulator never needs your release key. The Google APIs image (not the Play
Store one) allows `adb root`, which is handy for reading the app's files under
`/data/data/org.purple.telegram/`.

On an Apple silicon Mac, use an **arm64** image instead and give it the real
GPU:

```bash
sdkmanager "system-images;android-36;google_apis;arm64-v8a"
avdmanager create avd -n purple -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6
emulator -avd purple -gpu host -no-audio -no-snapshot &
```

Nothing is translated - the APK's architecture is the host's - and the emulator
renders through MoltenVK on the Mac's own GPU. That is worth the 4.3 GB image:
the software renderer below is what makes popups and long-press menus
unreliable, and on this path they simply work. It costs about 6 GB of RAM while
running, so shut it down with `adb emu kill` afterwards.

Two settings save a lot of pain. In the AVD's `config.ini` set
`hw.gpu.enabled=yes` and `hw.gpu.mode=swiftshader_indirect` (or `host`, above);
a freshly created AVD may have the GPU disabled, and the fallback renderer
segfaults the whole emulator seconds after the app draws a chat. Then put the app in full
power-saver mode, which turns off chat blur and animations, by writing
`<int name="lite_mode" value="0" />` into its `mainconfig.xml` while it is
stopped. The renderer can still die under heavy drawing; the disk image
persists, so a crash costs only a restart.

The desktop fork's repository carries the longer version of this, including how
to keep an emulator session private on a shared build machine, in
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
