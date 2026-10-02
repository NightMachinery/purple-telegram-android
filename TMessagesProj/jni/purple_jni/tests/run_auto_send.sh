#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
Tests="$Root/TMessagesProj/jni/purple_jni/tests"
Java="${PURPLE_AUTO_SEND_SOURCES:-$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple}"
BuildPath="$(mktemp -d "${TMPDIR:-/tmp}/purple-auto-send.XXXXXX")"
trap 'command rm -rf "$BuildPath"' EXIT
mkdir -p "$BuildPath/classes"

javac -Xlint:all,-options -Werror -d "$BuildPath/classes" \
    "$Java/PurpleAutoSendRules.java" \
    "$Tests/auto_send/org/telegram/messenger/purple/PurpleAutoSendRulesTest.java"

java -cp "$BuildPath/classes" org.telegram.messenger.purple.PurpleAutoSendRulesTest
