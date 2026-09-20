# Agent Guide for Purple Telegram Android

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
