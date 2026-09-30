# Agent Guide for Purple Telegram Android

## Build scheduling

Full Purple Telegram desktop and Android application builds may run whenever
the user explicitly authorizes the build and grants access to the machine.
Night scheduling is an option when the user requests it, not a standing time
restriction. Do not leave an active agent waiting for a requested window to
open. Non-building static checks may run at any time.

## Keeping upstream merges easy

Upstream Telegram Android is merged into this fork again and again, and every
line Purple changes in an upstream file is a conflict waiting in some future
merge. So Purple code lives in Purple's own files: the
`org.telegram.messenger.purple` package, `TMessagesProj/jni/purple_jni/`, or
the purple-core submodule at `TMessagesProj/jni/purple` when desktop needs the
same logic.

- Touch an upstream file only to add a hook: one import, one guarded call into
  a Purple class, one early return, or one menu row that delegates to Purple
  code. Put the decision and the work behind that call, in a Purple file.
- Do not copy, move or restructure upstream code to make room for Purple
  logic, and do not reformat upstream lines you pass through. If there is no
  place for a hook, add the smallest extension point that makes one.
- When a feature seems to need a large edit inside an upstream file (for
  example `ChatActivity`, `MediaController` or `DialogsActivity`), look for a
  smaller hook first. If the edit is still needed, say in the README section
  for that feature which upstream files it touches and why.
- Review your own diff of upstream files before committing; each hunk should
  read as a hook, not as feature code.

New work follows this from the start. Older Purple code that still sits inside
upstream files is being moved out.

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
