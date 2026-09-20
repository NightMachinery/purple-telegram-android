# Agent Guide for Purple Telegram Android

## Build scheduling

Run full Purple Telegram desktop and Android application builds only between
01:00 and 07:00 Europe/Berlin. Prepare source changes and lightweight checks at
other times, then delegate the application build to a worker during that night
window. Do not leave an active agent waiting for the window to open, and do not
start a build so late that it is expected to run past 07:00.

Non-building static checks may run outside this window.

## Release handoff

When an Android behavior change is complete and validated, finish the handoff
unless the user explicitly says not to:

1. Build the standalone arm64 APK using the repository's documented workflow.
2. Sign it on the Mac with `purple/sign.sh` and the release key kept outside
   the repository. Never send an unsigned APK or one signed with the build
   worker's disposable emulator key.
3. Verify the APK signature after signing.
4. Send the signed APK to the user through the established private Telegram
   delivery path.

Do not write the Telegram recipient id, signing credentials, keystore path, or
other private delivery details into this public repository.

The emulator runs on the Apple-silicon laptop. Compilation may run locally or
on the optional build worker, as described in the desktop repository's
`docs/remote-build-and-test/readme.md`; release signing remains on the Mac.
