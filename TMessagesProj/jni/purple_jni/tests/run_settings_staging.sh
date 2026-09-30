#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
Tests="$Root/TMessagesProj/jni/purple_jni/tests"
Java="${PURPLE_SETTINGS_STAGING_SOURCES:-$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple}"
BuildPath="$(mktemp -d "${TMPDIR:-/tmp}/purple-settings-staging.XXXXXX")"
trap 'command rm -rf "$BuildPath"' EXIT
mkdir -p "$BuildPath/classes" "$BuildPath/runs"

javac -Xlint:all,-options -Werror -d "$BuildPath/classes" \
    "$Java/PurpleSettingsStaging.java" \
    "$Tests/settings_staging/org/telegram/messenger/purple/PurpleSettingsStagingTest.java"

java -cp "$BuildPath/classes" -Dscratch="$BuildPath/runs" \
    org.telegram.messenger.purple.PurpleSettingsStagingTest
