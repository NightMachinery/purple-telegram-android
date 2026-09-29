#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
Scratch="$(mktemp -d "${TMPDIR:-/tmp}/purple-sync-store-test.XXXXXX")"
trap 'command rm -rf "$Scratch"' EXIT

javac -d "$Scratch" \
    "$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleAccountSyncStore.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/store_stubs/android/util/AtomicFile.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/store_stubs/org/telegram/messenger/ApplicationLoader.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/store_stubs/org/telegram/messenger/purple/PurpleAccountSyncCore.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/store_stubs/org/telegram/messenger/purple/PurpleAccountSyncStoreTest.java"
java -cp "$Scratch" org.telegram.messenger.purple.PurpleAccountSyncStoreTest
