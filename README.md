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

What is not ported yet: the unread badge when the app is set to count muted
chats, folder tabs, extra views, the "... until" overrides, peek and the
schedule. The archive and folder tabs are deliberately unfiltered, and pin
dragging is refused while a preset runs, because Android sends a reordered pin
list to the server with the force flag and would drop the hidden chats' pins
from every device.

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
