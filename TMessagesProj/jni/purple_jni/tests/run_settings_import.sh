#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
Tests="$Root/TMessagesProj/jni/purple_jni/tests"
Java="${PURPLE_SETTINGS_IMPORT_SOURCES:-$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple}"
BuildPath="$(mktemp -d "${TMPDIR:-/tmp}/purple-settings-import.XXXXXX")"
trap 'command rm -rf "$BuildPath"' EXIT
mkdir -p "$BuildPath/classes"

javac -Xlint:all,-options -Werror -d "$BuildPath/classes" \
    "$Java/PurpleSettingsImportRules.java" \
    "$Tests/settings_import/org/telegram/messenger/purple/PurpleSettingsImportRulesTest.java"

java -cp "$BuildPath/classes" org.telegram.messenger.purple.PurpleSettingsImportRulesTest
