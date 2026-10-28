#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
BuildPath="$(mktemp -d "${TMPDIR:-/tmp}/purple-account-binding-java.XXXXXX")"
trap 'command rm -rf "$BuildPath"' EXIT

JsonJar=""
Library=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --json) JsonJar="$2"; shift 2 ;;
        --library) Library="$2"; shift 2 ;;
        *) command printf '%s\n' "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [ ! -f "$JsonJar" ] || [ ! -f "$Library" ]; then
    command printf '%s\n' 'Pass --json JAR and --library HOST_JNI_LIBRARY; the binding planner uses real core JNI.' >&2
    exit 2
fi

javac -cp "$JsonJar" -d "$BuildPath" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/androidx/annotation/Keep.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/android/content/SharedPreferences.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/MessagesController.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/UserConfig.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/purple/PurpleCore.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/purple/PurpleAccountFiles.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/purple/PurpleSyncChatCore.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/purple/PurpleSyncChatIO.java" \
    "$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleAccountBinding.java" \
    "$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleAccountSyncCore.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/purple/PurpleAccountBindingTest.java"

java -Dpurple.test.library="$Library" -cp "$BuildPath:$JsonJar" org.telegram.messenger.purple.PurpleAccountBindingTest
