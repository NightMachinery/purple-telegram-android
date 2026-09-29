#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
BuildPath="$(mktemp -d "${TMPDIR:-/tmp}/purple-account-binding-java.XXXXXX")"
trap 'command rm -rf "$BuildPath"' EXIT

javac -d "$BuildPath" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/android/content/SharedPreferences.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/json/JSONException.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/json/JSONObject.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/MessagesController.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/UserConfig.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/purple/PurpleCore.java" \
    "$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleAccountBinding.java" \
    "$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleAccountSyncCore.java" \
    "$Root/TMessagesProj/jni/purple_jni/tests/java_binding/org/telegram/messenger/purple/PurpleAccountBindingTest.java"

java -cp "$BuildPath" org.telegram.messenger.purple.PurpleAccountBindingTest
