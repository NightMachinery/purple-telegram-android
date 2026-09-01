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
```

Get the `api_id` / `api_hash` pair from https://my.telegram.org/apps. They are
injected into `BuildConfig` at build time, so no credential is ever hardcoded in
the source. A standalone build without them fails immediately with a message
saying so.

Then build the standalone flavor:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

The output APK is **unsigned** and arm64-v8a only — sign it yourself with
`apksigner` before installing.

Push notifications are dead until you drop in your own Firebase project's
`google-services.json` (with an `org.purple.telegram` android app registered) in
place of the upstream one. Everything else works without it.

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
