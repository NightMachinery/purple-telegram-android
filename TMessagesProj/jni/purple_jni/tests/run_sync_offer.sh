#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
Tests="$Root/TMessagesProj/jni/purple_jni/tests"
Java="${PURPLE_SYNC_OFFER_SOURCES:-$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple}"
BuildPath="$(mktemp -d "${TMPDIR:-/tmp}/purple-sync-offer.XXXXXX")"
trap 'command rm -rf "$BuildPath"' EXIT
mkdir -p "$BuildPath/classes"

javac -Xlint:all,-options -Werror -d "$BuildPath/classes" \
    "$Java/PurpleSyncOfferRules.java" \
    "$Tests/sync_offer/org/telegram/messenger/purple/PurpleSyncOfferRulesTest.java"

java -cp "$BuildPath/classes" org.telegram.messenger.purple.PurpleSyncOfferRulesTest
