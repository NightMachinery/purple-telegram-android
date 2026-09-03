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
from the copy.

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

What is not ported yet: extra views, the "... until" overrides, peek and the
schedule. The `folders` key itself is complete. The
archive and the contents of a folder tab are deliberately unfiltered: a preset
decides its own view, a folder decides its own tab.

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
services: Settings → Notifications and Sounds → **Background Connection** (and
**Keep-Alive Service** if the OS keeps killing the app). Enable it once after
signing in.

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

Two settings save a lot of pain. In the AVD's `config.ini` set
`hw.gpu.enabled=yes` and `hw.gpu.mode=swiftshader_indirect`; a freshly created
AVD may have the GPU disabled, and the fallback renderer segfaults the whole
emulator seconds after the app draws a chat. Then put the app in full
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
